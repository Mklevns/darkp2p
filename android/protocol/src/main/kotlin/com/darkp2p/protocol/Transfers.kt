package com.darkp2p.protocol

// Forward frames (sender -> receiver): meta seq 0, DATA 1..N, done N+1, abort = next seq.
// Replies (receiver -> sender) use the same transferId under the receiver's role,
// numbered 1, 2, 3, ...: accept | decline | ack | complete | abort.
// Both classes are ports of the web client's classes of the same name; time is passed in.

/** Receiver side of one transfer. */
public class RxStateMachine(public val meta: ValidMeta) {
    public enum class Phase(public val wire: String) {
        OFFERED("offered"),
        RECEIVING("receiving"),
        VERIFYING("verifying"),
        COMPLETE("complete"),
        DECLINED("declined"),
        ABORTED("aborted"),
    }

    public var phase: Phase = Phase.OFFERED
        private set
    public var expectedSeq: Long = 1
        private set
    public var chunks: Long = 0
        private set
    public var bytes: Long = 0
        private set
    public var replySeq: Long = 0
        private set

    public val active: Boolean get() = phase == Phase.OFFERED || phase == Phase.RECEIVING || phase == Phase.VERIFYING

    public fun nextReplySeq(): Long {
        replySeq += 1
        return replySeq
    }

    private fun expectPhase(expected: Phase, what: String) {
        if (phase != expected) throw ProtocolError(AbortReason.INTEGRITY, "Unexpected $what (${phase.wire})")
    }

    public fun accept() {
        expectPhase(Phase.OFFERED, "accept")
        phase = Phase.RECEIVING
    }

    public fun decline() {
        expectPhase(Phase.OFFERED, "decline")
        phase = Phase.DECLINED
    }

    /** Returns the seq to acknowledge, or null when no ack is due. */
    public fun onData(seq: Long, length: Int): Long? {
        expectPhase(Phase.RECEIVING, "chunk")
        if (seq != expectedSeq) throw ProtocolError(AbortReason.INTEGRITY, "Out-of-order chunk (expected $expectedSeq, got $seq)")
        if (chunks >= meta.chunks) throw ProtocolError(AbortReason.INTEGRITY, "More chunks than announced")
        if (length < 1) throw ProtocolError(AbortReason.INTEGRITY, "Empty chunk")
        if (bytes + length > meta.size) throw ProtocolError(AbortReason.INTEGRITY, "Chunk exceeds announced file size")
        expectedSeq += 1
        chunks += 1
        bytes += length
        return if (chunks % WireConstants.ACK_EVERY == 0L) seq else null
    }

    /** Returns the seq of the final acknowledgement (the last DATA seq), sent with `done: true`. */
    public fun onDone(seq: Long, done: Ctrl.Done): Long {
        expectPhase(Phase.RECEIVING, "done")
        if (seq != expectedSeq) throw ProtocolError(AbortReason.INTEGRITY, "Out-of-order done (expected $expectedSeq, got $seq)")
        if (done.chunks != chunks || done.size != meta.size || chunks != meta.chunks || bytes != meta.size) {
            throw ProtocolError(AbortReason.INTEGRITY, "Integrity mismatch (got $bytes/${meta.size} bytes)")
        }
        expectedSeq += 1
        phase = Phase.VERIFYING
        return chunks
    }

    public fun complete() {
        expectPhase(Phase.VERIFYING, "complete")
        phase = Phase.COMPLETE
    }

    /** Abort sent by the peer (forward direction). */
    public fun onAbort(seq: Long) {
        if (!active) throw ProtocolError(AbortReason.INTEGRITY, "Unexpected abort (${phase.wire})")
        if (seq != expectedSeq) throw ProtocolError(AbortReason.INTEGRITY, "Out-of-order abort (expected $expectedSeq, got $seq)")
        phase = Phase.ABORTED
    }

    /** Local abort. Returns true if the transfer was active (so the peer must be told). */
    public fun abort(): Boolean {
        if (!active) return false
        phase = Phase.ABORTED
        return true
    }
}

/** Sender side of one transfer: the credit window and its timeouts. */
public class TxWindow(
    public val chunks: Long,
    now: Long,
    private val window: Int = WireConstants.TX_WINDOW,
    private val acceptTimeoutMs: Long = WireConstants.ACCEPT_TIMEOUT_MS,
    private val ackStallMs: Long = WireConstants.ACK_STALL_MS,
    private val completeTimeoutMs: Long = WireConstants.COMPLETE_TIMEOUT_MS,
) {
    public enum class Phase(public val wire: String) {
        OFFERED("offered"),
        SENDING("sending"),
        AWAITING_COMPLETE("awaiting-complete"),
        DELIVERED("delivered"),
        UNCONFIRMED("unconfirmed"),
        DECLINED("declined"),
        ABORTED("aborted"),
    }

    public enum class Reply(public val wire: String) { ACCEPT("accept"), DECLINE("decline"), ACK("ack"), COMPLETE("complete"), ABORT("abort") }

    /** [UNCONFIRMED] is not a failure: done was acknowledged, only `complete` is missing. */
    public enum class Timeout(public val wire: String) { ACCEPT("accept"), ACK("ack"), COMPLETE("complete"), UNCONFIRMED("unconfirmed") }

    init {
        if (chunks < 0 || chunks > WireConstants.MAX_FILE_CHUNKS) throw ProtocolError(AbortReason.INTEGRITY, "Invalid chunk count")
    }

    public var phase: Phase = Phase.OFFERED
        private set
    public var dataSent: Long = 0
        private set
    public var acked: Long = 0
        private set

    /** The last forward seq used (0 = meta). */
    public var forwardSeq: Long = 0
        private set
    public var expectedReplySeq: Long = 1
        private set

    /** Start of the current wait (accept, ack credit, complete). */
    public var since: Long = now
        private set
    public var declineReason: DeclineReason? = null
        private set
    public var abortReason: AbortReason? = null
        private set

    /** The receiver acknowledged done (every frame reached it). */
    public var doneAcked: Boolean = false
        private set

    public val active: Boolean get() = phase == Phase.OFFERED || phase == Phase.SENDING || phase == Phase.AWAITING_COMPLETE

    public val inFlight: Long get() = dataSent - acked

    public fun canSendData(): Boolean = phase == Phase.SENDING && dataSent < chunks && inFlight < window

    public fun nextDataSeq(now: Long): Long {
        if (!canSendData()) throw ProtocolError(AbortReason.INTEGRITY, "No send credit")
        dataSent += 1
        forwardSeq = dataSent
        if (inFlight >= window) since = now // window just filled
        return dataSent
    }

    public fun doneSeq(now: Long): Long {
        if (phase != Phase.SENDING || dataSent != chunks) throw ProtocolError(AbortReason.INTEGRITY, "done before all chunks were sent")
        phase = Phase.AWAITING_COMPLETE
        since = now
        forwardSeq = chunks + 1
        return forwardSeq
    }

    /** Local abort: returns the forward seq for the abort frame, or null if already finished. */
    public fun abortSeq(reason: AbortReason): Long? {
        if (!active) return null
        phase = Phase.ABORTED
        abortReason = reason
        forwardSeq += 1
        return forwardSeq
    }

    /** Applies a reply from the receiver. Throws [ProtocolError] on violations. */
    public fun onReply(seq: Long, msg: Ctrl?, now: Long): Reply {
        if (!active) throw ProtocolError(AbortReason.INTEGRITY, "Reply to a finished transfer (${phase.wire})")
        if (seq != expectedReplySeq) throw ProtocolError(AbortReason.INTEGRITY, "Out-of-order reply (expected $expectedReplySeq, got $seq)")
        expectedReplySeq += 1
        fun bad(what: String) = ProtocolError(AbortReason.INTEGRITY, "Unexpected $what (${phase.wire})")

        when (msg) {
            Ctrl.Accept -> {
                if (phase != Phase.OFFERED) throw bad("accept")
                phase = Phase.SENDING
                since = now
                return Reply.ACCEPT
            }
            is Ctrl.Decline -> {
                if (phase != Phase.OFFERED) throw bad("decline")
                phase = Phase.DECLINED
                declineReason = DeclineReason.fromWire(msg.reason) ?: DeclineReason.USER
                return Reply.DECLINE
            }
            is Ctrl.Ack -> {
                if (phase != Phase.SENDING && phase != Phase.AWAITING_COMPLETE) throw bad("ack")
                if (!msg.valid || msg.seq < acked || msg.seq > dataSent) {
                    throw ProtocolError(AbortReason.INTEGRITY, "Invalid ack (acked $acked, sent $dataSent)")
                }
                if (msg.done) {
                    if (phase != Phase.AWAITING_COMPLETE || msg.seq != chunks || doneAcked) {
                        throw ProtocolError(AbortReason.INTEGRITY, "Unexpected final ack (${phase.wire})")
                    }
                    doneAcked = true
                }
                if (msg.seq > acked) {
                    acked = msg.seq
                    if (phase == Phase.SENDING) since = now
                }
                return Reply.ACK
            }
            Ctrl.Complete -> {
                if (phase != Phase.AWAITING_COMPLETE) throw bad("complete")
                if (!doneAcked) throw ProtocolError(AbortReason.INTEGRITY, "complete before the final ack")
                phase = Phase.DELIVERED
                return Reply.COMPLETE
            }
            is Ctrl.Abort -> {
                phase = Phase.ABORTED
                abortReason = AbortReason.fromWire(msg.reason) ?: AbortReason.INTEGRITY
                return Reply.ABORT
            }
            else -> throw ProtocolError(AbortReason.INTEGRITY, "Unknown reply")
        }
    }

    /** Which wait has timed out at [now], if any. */
    public fun timedOut(now: Long): Timeout? {
        if (phase == Phase.OFFERED && now - since >= acceptTimeoutMs) return Timeout.ACCEPT
        if (phase == Phase.SENDING && inFlight >= window && now - since >= ackStallMs) return Timeout.ACK
        if (phase == Phase.AWAITING_COMPLETE && now - since >= completeTimeoutMs) {
            return if (doneAcked) Timeout.UNCONFIRMED else Timeout.COMPLETE
        }
        return null
    }

    /**
     * Ends a transfer whose done was acknowledged but whose `complete` never came
     * (timeout, or the peer closed). Neutral outcome: nothing is sent to the peer.
     */
    public fun endUnconfirmed(): Boolean {
        if (phase != Phase.AWAITING_COMPLETE || !doneAcked) return false
        phase = Phase.UNCONFIRMED
        return true
    }
}
