package com.darkp2p.protocol

/** Which of the two transports carries a frame. */
public enum class ChannelKind { DATA_CHANNEL, WS_RELAY }

/** A concrete channel: the WebRTC session or signaling socket a frame belongs to. */
public data class ChannelRef(val kind: ChannelKind, val id: Int)

public enum class SdpKind { OFFER, ANSWER }

public enum class PcState { CONNECTED, DISCONNECTED, FAILED }

public enum class TimerId { HELLO, ICE, RELAY_RETRY, TX_WATCHDOG, STABLE, RECONNECT }

/** Why a signaling socket ended. */
public sealed interface CloseCause {
    public data class Closed(val code: Int) : CloseCause

    /** The WebSocket upgrade was refused: 403 is a wrong Origin (not retried), 429 a rate limit. */
    public data class HttpRejected(val status: Int) : CloseCause
    public data object NetworkError : CloseCause
}

/** What the user chose on this device. */
public data class LocalPolicy(
    /** Always use the WebSocket relay. */
    val forceWsRelay: Boolean = false,
    /** Hide My IP: TURN only, or the WebSocket relay when the room has no TURN. */
    val hideIp: Boolean = false,
    /** False in Tor mode: WebRTC is never started. */
    val webRtcAvailable: Boolean = true,
)

/** Everything that can happen to a room. Each is handled to completion, in arrival order. */
public sealed interface RoomInput {
    // ---- Signaling socket ----
    /** The socket is open and the host is ready to join (after its short wait for a pending token claim). */
    public data class SignalingOpened(val socket: Int) : RoomInput
    public data class SignalingClosed(val socket: Int, val cause: CloseCause) : RoomInput
    public data class Server(val socket: Int, val msg: ServerMessage) : RoomInput

    // ---- Frames (either transport) ----
    public class Frame(public val channel: ChannelRef, public val bytes: ByteArray) : RoomInput

    /** The frame of [RoomEffect.SendFrame] with this token left the local send buffer. */
    public data class FrameSent(val token: Int) : RoomInput

    // ---- WebRTC session [pc] ----
    public data class LocalSdp(val pc: Int, val kind: SdpKind, val sdp: String) : RoomInput
    public data class LocalCandidate(val pc: Int, val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?) : RoomInput
    public data class PeerConnectionState(val pc: Int, val state: PcState) : RoomInput
    public data class DataChannelOpened(val pc: Int) : RoomInput
    public data class DataChannelClosed(val pc: Int) : RoomInput

    /** The selected ICE route: true through TURN, false direct, null unknown. */
    public data class SelectedRoute(val pc: Int, val relay: Boolean?) : RoomInput
    public data class WebRtcFailed(val pc: Int) : RoomInput

    public data class TimerFired(val id: TimerId) : RoomInput

    // ---- User ----
    public data class SendFile(val name: String, val size: Long, val mime: String) : RoomInput
    public data object CancelSend : RoomInput

    /** The user accepted the offer and the destination is open for writing. */
    public data object AcceptIncoming : RoomInput
    public data class DeclineIncoming(val reason: DeclineReason = DeclineReason.USER) : RoomInput
    public data object CancelReceive : RoomInput
    public data class SetPolicy(val policy: LocalPolicy) : RoomInput
    public data class SetToken(val token: String) : RoomInput

    /** Resume a relay the server paused for idleness. */
    public data object ResumeRelay : RoomInput

    /** Leave and rejoin on a new signaling socket (after a failed tunnel). */
    public data object Reconnect : RoomInput

    // ---- File I/O results ----
    public class ChunkRead(public val transfer: Int, public val bytes: ByteArray) : RoomInput
    public data class ChunkReadFailed(val transfer: Int) : RoomInput
    public data class ChunkWritten(val transfer: Int) : RoomInput
    public data class SinkCommitted(val transfer: Int) : RoomInput
    public data class SinkFailed(val transfer: Int) : RoomInput
}

/** What the host must do, in order. */
public sealed interface RoomEffect {
    public data class OpenSignaling(val socket: Int) : RoomEffect
    public data class CloseSignaling(val socket: Int) : RoomEffect
    public data class SendSignal(val socket: Int, val msg: ClientMessage) : RoomEffect

    /**
     * Send one encrypted frame. Frames of one channel must leave in the order
     * given. With [notifyToken], answer [RoomInput.FrameSent] once the frame
     * has left the local send buffer (the sender's backpressure).
     */
    public class SendFrame(public val channel: ChannelRef, public val bytes: ByteArray, public val notifyToken: Int?) : RoomEffect

    public data class StartWebRtc(val pc: Int, val initiator: Boolean, val iceServers: List<IceServer>, val mode: IceMode) : RoomEffect
    public data class ApplyRemoteSdp(val pc: Int, val kind: SdpKind, val sdp: String) : RoomEffect
    public data class AddRemoteCandidate(val pc: Int, val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?) : RoomEffect
    public data class CloseWebRtc(val pc: Int) : RoomEffect

    /** (Re)starts the timer [id]; a running one is replaced. */
    public data class ArmTimer(val id: TimerId, val delayMs: Long) : RoomEffect
    public data class CancelTimer(val id: TimerId) : RoomEffect

    /** Read [length] bytes at [offset] of the file being sent; answer [RoomInput.ChunkRead]. */
    public data class ReadChunk(val transfer: Int, val offset: Long, val length: Int) : RoomEffect

    /** Append to the incoming file; answer [RoomInput.ChunkWritten]. */
    public class WriteChunk(public val transfer: Int, public val bytes: ByteArray) : RoomEffect

    /** The file is complete and verified: make it durable and visible; answer [RoomInput.SinkCommitted]. */
    public data class CommitSink(val transfer: Int) : RoomEffect

    /** The incoming transfer ended without a verified file: delete what was written. */
    public data class DiscardSink(val transfer: Int) : RoomEffect

    public data class Notify(val event: RoomEvent) : RoomEffect
}

/** Something to tell the user. [detail] is a file name or a reason, never a room ID, key or token. */
public data class RoomEvent(val notice: Notice, val detail: String? = null)

public enum class Notice {
    SIGNALING_CONNECTED, SIGNALING_LOST, SIGNALING_LOST_TUNNEL_UP, SIGNALING_RESTORED, REJOINING,
    PEER_DETECTED, PEER_LEFT, PEER_SIGNALING_AWAY, KEPT_TUNNEL, ROOM_FULL, SERVER_ERROR, ORIGIN_REJECTED,
    TUNNEL_OPEN, TUNNEL_CLOSED, TUNNEL_DISCONNECTED, TUNNEL_FAILED, WEBRTC_TIMEOUT, WEBRTC_FAILED,
    RELAY_ACTIVE, RELAY_ACTIVE_HIDE_IP_NO_PRO, RELAY_CLOSED, RELAY_BUSY, RELAY_IDLE, RELAY_EXHAUSTED,
    KEY_VERIFIED, KEY_MISMATCH, PEER_OUTDATED, SESSION_UNCONFIRMED, SESSION_RENEWED,
    OFFERED, DOES_NOT_FIT_RELAY, FILE_TOO_LARGE, SENDING, AWAITING_CONFIRMATION, DELIVERED, SENT_UNCONFIRMED,
    NOT_SENT_DECLINED, NOT_SENT_PEER_BUSY, NOT_SENT_TOO_LARGE_FOR_PEER, SEND_ABORTED, SEND_INTERRUPTED, SEND_CANCELLED,
    RECEIVER_ABORTED,
    INCOMING_OFFER, DECLINED_BUSY, RECEIVING, SAVED, RECEIVE_ABORTED, RECEIVE_INTERRUPTED, SENDER_ABORTED, FRAME_REFUSED,
}

public enum class Transport { DIRECT, TURN, WS_RELAY }

/** Whether the peer has learned one of this device's addresses. Once exposed, it stays exposed. */
public enum class IpExposure { MASKED, EXPOSED, UNVERIFIED }

public enum class FatalReason { ORIGIN_REJECTED, INVALID_ROOM }

/** The room as the user interface sees it. */
public sealed interface RoomState {
    public data class Connecting(val attempt: Int, val reconnecting: Boolean) : RoomState
    public data object WaitingForPeer : RoomState
    public data class Negotiating(val mode: IceMode) : RoomState

    /** [unconfirmed]: the peer's hello arrived but it has not confirmed the session key. */
    public data class Verifying(val transport: Transport, val unconfirmed: Boolean) : RoomState

    /** The only state in which a file can be sent. */
    public data class Ready(
        val transport: Transport,
        val ip: IpExposure,
        val relayCap: Long?,
        val relayRemaining: Long?,
        val peerSignalingAway: Boolean,
        /** Hide My IP is on without TURN: the relay stands in. Pro unlocks TURN and a larger relay cap. */
        val hideIpViaRelay: Boolean,
    ) : RoomState

    /** [canReconnect]: the tunnel failed and only a manual reconnect (or the peer asking for the relay) restores it. */
    public data class Interrupted(val transport: Transport, val canReconnect: Boolean) : RoomState
    public data object RelayBusy : RoomState
    public data object RelayIdle : RoomState
    public data class RelayExhausted(val capBytes: Long?, val canTryDirect: Boolean, val proWouldRaise: Boolean) : RoomState
    public data object KeyMismatch : RoomState
    public data object PeerOutdated : RoomState
    public data object RoomFull : RoomState
    public data class Fatal(val reason: FatalReason) : RoomState
}

public data class TransferProgress(val name: String, val size: Long, val bytesDone: Long, val phase: String)
