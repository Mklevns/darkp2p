package com.darkp2p.transport

import com.darkp2p.protocol.IceMode
import com.darkp2p.protocol.IceServer
import com.darkp2p.protocol.PcState
import com.darkp2p.protocol.SdpKind
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCIceTransportPolicy
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioLayer
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [WebRtcFactory] on a desktop build of libwebrtc (webrtc-java), so the room's
 * WebRTC path can run on the host JVM against a real browser. It mirrors the
 * Android implementation call for call; only the binding differs.
 */
internal class DesktopWebRtcFactory : WebRtcFactory {
    // No audio is ever used; a dummy device also lets this run on machines without sound.
    private val factory = PeerConnectionFactory(AudioDeviceModule(AudioLayer.kDummyAudio))

    /** Every candidate line libwebrtc reported, before the room's policy saw it. */
    val gathered = CopyOnWriteArrayList<String>()

    override fun create(initiator: Boolean, iceServers: List<IceServer>, mode: IceMode, events: WebRtcEvents): WebRtcPeer {
        val config = RTCConfiguration().apply {
            this.iceServers = iceServers.map { s ->
                RTCIceServer().apply {
                    urls = s.urls
                    username = s.username
                    password = s.credential
                }
            }
            iceTransportPolicy = if (mode == IceMode.RELAY) RTCIceTransportPolicy.RELAY else RTCIceTransportPolicy.ALL
        }
        return Peer(config, initiator, events)
    }

    private inner class Peer(config: RTCConfiguration, initiator: Boolean, private val events: WebRtcEvents) : WebRtcPeer {
        private val closed = AtomicBoolean()
        private val pendingCandidates = ArrayList<RTCIceCandidate>()
        private var remoteSet = false

        @Volatile private var channel: RTCDataChannel? = null

        private val pc: RTCPeerConnection = factory.createPeerConnection(
            config,
            object : PeerConnectionObserver {
                override fun onIceCandidate(candidate: RTCIceCandidate) {
                    if (closed.get()) return
                    gathered += candidate.sdp
                    events.onLocalCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                }

                override fun onConnectionChange(state: RTCPeerConnectionState) {
                    if (closed.get()) return
                    when (state) {
                        RTCPeerConnectionState.CONNECTED -> events.onState(PcState.CONNECTED)
                        RTCPeerConnectionState.DISCONNECTED -> events.onState(PcState.DISCONNECTED)
                        RTCPeerConnectionState.FAILED -> events.onState(PcState.FAILED)
                        else -> Unit
                    }
                }

                override fun onDataChannel(dataChannel: RTCDataChannel) = adopt(dataChannel)
            },
        )

        init {
            if (initiator) {
                adopt(pc.createDataChannel("darkdrop", RTCDataChannelInit().apply { ordered = true }))
                pc.createOffer(RTCOfferOptions(), created(SdpKind.OFFER))
            }
        }

        private fun adopt(dc: RTCDataChannel) {
            channel = dc
            dc.registerObserver(object : RTCDataChannelObserver {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    if (closed.get()) return
                    when (dc.state) {
                        RTCDataChannelState.OPEN -> events.onDataChannelOpen()
                        RTCDataChannelState.CLOSED -> events.onDataChannelClosed()
                        else -> Unit
                    }
                }

                override fun onMessage(buffer: RTCDataChannelBuffer) {
                    if (closed.get() || !buffer.binary) return
                    // The native buffer is only valid during this call.
                    val copy = ByteArray(buffer.data.remaining())
                    buffer.data.get(copy)
                    events.onFrame(copy)
                }
            })
        }

        private fun created(kind: SdpKind) = object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) {
                pc.setLocalDescription(
                    description,
                    object : SetSessionDescriptionObserver {
                        override fun onSuccess() {
                            if (!closed.get()) events.onLocalSdp(kind, description.sdp)
                        }

                        override fun onFailure(error: String) = failed()
                    },
                )
            }

            override fun onFailure(error: String) = failed()
        }

        private fun failed() {
            if (!closed.get()) events.onFailed()
        }

        override fun applyRemoteSdp(kind: SdpKind, sdp: String) {
            if (closed.get()) return
            val description = RTCSessionDescription(if (kind == SdpKind.OFFER) RTCSdpType.OFFER else RTCSdpType.ANSWER, sdp)
            pc.setRemoteDescription(
                description,
                object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        synchronized(pendingCandidates) {
                            remoteSet = true
                            pendingCandidates.forEach { runCatching { pc.addIceCandidate(it) } }
                            pendingCandidates.clear()
                        }
                        if (kind == SdpKind.OFFER && !closed.get()) pc.createAnswer(RTCAnswerOptions(), created(SdpKind.ANSWER))
                    }

                    override fun onFailure(error: String) = failed()
                },
            )
        }

        override fun addRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
            if (closed.get()) return
            val c = RTCIceCandidate(sdpMid ?: "0", sdpMLineIndex ?: 0, candidate)
            synchronized(pendingCandidates) {
                if (remoteSet) runCatching { pc.addIceCandidate(c) } else pendingCandidates += c
            }
        }

        override val bufferedAmount: Long get() = channel?.takeIf { !closed.get() }?.bufferedAmount ?: 0

        override fun send(frame: ByteArray): Boolean {
            val dc = channel ?: return false
            if (closed.get() || dc.state != RTCDataChannelState.OPEN) return false
            return runCatching { dc.send(RTCDataChannelBuffer(ByteBuffer.wrap(frame), true)) }.isSuccess
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { channel?.unregisterObserver() }
            runCatching { channel?.close() }
            runCatching { pc.close() }
        }
    }
}
