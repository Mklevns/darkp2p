package com.darkp2p.transport

import com.darkp2p.protocol.IceMode
import com.darkp2p.protocol.IceServer
import com.darkp2p.protocol.PcState
import com.darkp2p.protocol.SdpKind

/**
 * Creates WebRTC sessions. A room is given one only on a clearnet profile:
 * without a factory the room cannot start WebRTC at all, which is how Tor
 * mode is kept free of ICE.
 */
public interface WebRtcFactory {
    /**
     * A new peer connection. The initiator creates the DataChannel
     * (`"darkdrop"`, ordered) and the offer, and reports the offer through
     * [events]; the responder waits for [WebRtcPeer.applyRemoteSdp].
     *
     * @param mode [IceMode.RELAY] must gather TURN candidates only.
     */
    public fun create(initiator: Boolean, iceServers: List<IceServer>, mode: IceMode, events: WebRtcEvents): WebRtcPeer
}

/** One peer connection and its single DataChannel. Safe to call from any thread. */
public interface WebRtcPeer {
    /** An offer makes the peer create an answer and report it; an answer completes the exchange. */
    public fun applyRemoteSdp(kind: SdpKind, sdp: String)

    /** Candidates that arrive before the remote description are kept until it is set. */
    public fun addRemoteCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?)

    /** Bytes handed to the DataChannel that have not left yet. */
    public val bufferedAmount: Long

    /** False if the channel is not open. */
    public fun send(frame: ByteArray): Boolean

    /** After this, no event of this peer is delivered. */
    public fun close()
}

/** Called on WebRTC's own threads: implementations only hand the event on. */
public interface WebRtcEvents {
    public fun onLocalSdp(kind: SdpKind, sdp: String)

    public fun onLocalCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?)

    public fun onState(state: PcState)

    public fun onDataChannelOpen()

    public fun onDataChannelClosed()

    /** [bytes] is the receiver's own copy. */
    public fun onFrame(bytes: ByteArray)

    /** The selected route: true through TURN, false direct, null unknown. */
    public fun onRoute(relay: Boolean?)

    /** Creating the offer or answer failed. */
    public fun onFailed()
}
