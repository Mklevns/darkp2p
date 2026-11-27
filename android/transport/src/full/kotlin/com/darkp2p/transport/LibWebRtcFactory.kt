package com.darkp2p.transport

import android.content.Context
import com.darkp2p.protocol.IceMode
import com.darkp2p.protocol.IceServer
import com.darkp2p.protocol.PcState
import com.darkp2p.protocol.SdpKind
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [WebRtcFactory] on Android's libwebrtc. Data only: no audio or video track
 * is ever created, and the microphone and camera permissions are not requested.
 *
 * The native library is loaded when the first peer is created, so a process
 * that only ever runs in Tor mode never loads it.
 *
 * Call for call the same as the desktop factory the host-JVM tests use
 * (`DesktopWebRtcFactory`), which is how this logic is exercised against a
 * real browser. This class itself runs only on a device.
 */
internal class LibWebRtcFactory(private val appContext: Context) : WebRtcFactory {
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions())
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    // Peers are closed and disposed here, never on one of libwebrtc's own
    // threads (disposing from inside a callback deadlocks).
    private val disposer: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "webrtc-dispose").apply { isDaemon = true } }

    override fun create(initiator: Boolean, iceServers: List<IceServer>, mode: IceMode, events: WebRtcEvents): WebRtcPeer {
        val servers = iceServers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let(::setUsername)
                s.credential?.let(::setPassword)
            }.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            // Hide My IP: TURN candidates only, so no local or public address is gathered.
            iceTransportsType = if (mode == IceMode.RELAY) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Gather once: the server allows 200 candidates per pairing.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        return Peer(config, initiator, events)
    }

    private inner class Peer(config: PeerConnection.RTCConfiguration, initiator: Boolean, private val events: WebRtcEvents) : WebRtcPeer {
        private val closed = AtomicBoolean()
        private val pendingCandidates = ArrayList<IceCandidate>()
        private var remoteSet = false

        @Volatile private var channel: DataChannel? = null

        private val pc: PeerConnection = factory.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    if (!closed.get()) events.onLocalCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    if (closed.get()) return
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> events.onState(PcState.CONNECTED)
                        PeerConnection.PeerConnectionState.DISCONNECTED -> events.onState(PcState.DISCONNECTED)
                        PeerConnection.PeerConnectionState.FAILED -> events.onState(PcState.FAILED)
                        else -> Unit
                    }
                }

                override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
                    // Only OUR side of the pair matters: a relayed remote candidate does not hide our address.
                    if (!closed.get()) events.onRoute(" typ relay" in event.local.sdp)
                }

                override fun onDataChannel(dataChannel: DataChannel) = adopt(dataChannel)

                override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit

                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit

                override fun onAddStream(stream: MediaStream) = Unit

                override fun onRemoveStream(stream: MediaStream) = Unit

                override fun onRenegotiationNeeded() = Unit
            },
        ) ?: throw IllegalStateException("libwebrtc could not create a peer connection")

        init {
            if (initiator) {
                adopt(pc.createDataChannel("darkdrop", DataChannel.Init().apply { ordered = true }))
                pc.createOffer(created(SdpKind.OFFER), MediaConstraints())
            }
        }

        private fun adopt(dc: DataChannel) {
            channel = dc
            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    if (closed.get()) return
                    when (dc.state()) {
                        DataChannel.State.OPEN -> events.onDataChannelOpen()
                        DataChannel.State.CLOSED -> events.onDataChannelClosed()
                        else -> Unit
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (closed.get() || !buffer.binary) return
                    // The native buffer is only valid during this call.
                    val copy = ByteArray(buffer.data.remaining())
                    buffer.data.get(copy)
                    events.onFrame(copy)
                }
            })
        }

        private fun failed() {
            if (!closed.get()) events.onFailed()
        }

        // Sets what was created as the local description, then reports it.
        private fun created(kind: SdpKind) = object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                pc.setLocalDescription(
                    object : SdpObserver {
                        override fun onSetSuccess() {
                            if (!closed.get()) events.onLocalSdp(kind, description.description)
                        }

                        override fun onSetFailure(error: String?) = failed()

                        override fun onCreateSuccess(d: SessionDescription?) = Unit

                        override fun onCreateFailure(error: String?) = Unit
                    },
                    description,
                )
            }

            override fun onCreateFailure(error: String?) = failed()

            override fun onSetSuccess() = Unit

            override fun onSetFailure(error: String?) = Unit
        }

        override fun applyRemoteSdp(kind: SdpKind, sdp: String) {
            if (closed.get()) return
            val type = if (kind == SdpKind.OFFER) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
            pc.setRemoteDescription(
                object : SdpObserver {
                    override fun onSetSuccess() {
                        synchronized(pendingCandidates) {
                            remoteSet = true
                            pendingCandidates.forEach { pc.addIceCandidate(it) }
                            pendingCandidates.clear()
                        }
                        if (kind == SdpKind.OFFER && !closed.get()) pc.createAnswer(created(SdpKind.ANSWER), MediaConstraints())
                    }

                    override fun onSetFailure(error: String?) = failed()

                    override fun onCreateSuccess(d: SessionDescription?) = Unit

                    override fun onCreateFailure(error: String?) = Unit
                },
                SessionDescription(type, sdp),
            )
        }

        override fun addRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
            if (closed.get()) return
            val c = IceCandidate(sdpMid ?: "0", sdpMLineIndex ?: 0, candidate)
            synchronized(pendingCandidates) {
                if (remoteSet) pc.addIceCandidate(c) else pendingCandidates += c
            }
        }

        override val bufferedAmount: Long
            get() = if (closed.get()) 0 else channel?.bufferedAmount() ?: 0

        override fun send(frame: ByteArray): Boolean {
            val dc = channel ?: return false
            if (closed.get() || dc.state() != DataChannel.State.OPEN) return false
            return dc.send(DataChannel.Buffer(ByteBuffer.wrap(frame), true))
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            disposer.execute {
                channel?.let {
                    it.unregisterObserver()
                    it.close()
                    it.dispose()
                }
                pc.dispose()
            }
        }
    }
}
