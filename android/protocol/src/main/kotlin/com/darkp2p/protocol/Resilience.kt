package com.darkp2p.protocol

/**
 * The room's WebSocket relay budget as the server counts it: every binary frame
 * relayed in either direction, from the `ready` snapshot onwards.
 */
public class RelayBudget private constructor(public val cap: Long, remaining: Long) {
    public var remaining: Long = remaining
        private set

    public fun charge(bytes: Long) {
        if (bytes > 0) remaining = maxOf(0, remaining - bytes)
    }

    /** The server said the budget is spent. */
    public fun exhaust() {
        remaining = 0
    }

    public fun fits(size: Long, chunkSize: Int = WireConstants.CHUNK_SIZE): Boolean = wireBytes(size, chunkSize) <= remaining

    /** Too little left for even the key handshake: the relay is not attempted. */
    public val belowHandshakeMinimum: Boolean get() = remaining < WireConstants.RELAY_MIN_REMAINING

    public companion object {
        private const val MAX_SAFE = 9007199254740991L
        public const val FALLBACK_CAP_FREE: Long = 50L * 1024 * 1024
        public const val FALLBACK_CAP_PRO: Long = 500L * 1024 * 1024

        /** Null for figures the web client would reject (missing, negative, remaining above cap). */
        public fun of(cap: Long?, remaining: Long?): RelayBudget? {
            if (cap == null || remaining == null || cap < 0 || cap > MAX_SAFE || remaining < 0 || remaining > cap) return null
            return RelayBudget(cap, remaining)
        }

        /** From a `ready` message; a server too old to send figures gets a fresh room at the default caps. */
        public fun fromReady(cap: Long?, remaining: Long?, pro: Boolean): RelayBudget =
            of(cap, remaining) ?: (if (pro) FALLBACK_CAP_PRO else FALLBACK_CAP_FREE).let { RelayBudget(it, it) }

        /**
         * Relay bytes a file of [size] bytes uses: every chunk plus its frame overhead,
         * the receiver's acks (one per 64 chunks) and the meta/done/complete frames.
         */
        public fun wireBytes(size: Long, chunkSize: Int): Long {
            val chunks = (size + chunkSize - 1) / chunkSize
            val acks = (chunks + WireConstants.ACK_EVERY - 1) / WireConstants.ACK_EVERY + 1
            return size + chunks * WireConstants.FRAME_OVERHEAD + acks * (WireConstants.FRAME_OVERHEAD + 32) + WireConstants.RELAY_CTRL_RESERVE
        }
    }
}

public object Backoff {
    /**
     * Delay before signaling reconnect attempt [attempt] (0-based): 1 s doubling to a
     * 30 s cap, with +-20 % jitter so many clients do not reconnect in lockstep.
     * [rand] is in [0, 1).
     */
    public fun reconnectDelay(attempt: Int, rand: Double): Long {
        val n = if (attempt > 0) minOf(attempt, 16) else 0
        val base = minOf(WireConstants.RECONNECT_MAX_MS, WireConstants.RECONNECT_BASE_MS shl n)
        val r = if (rand >= 0 && rand < 1) rand else 0.5
        return Math.round(base * (0.8 + 0.4 * r))
    }
}

/** How ICE candidates are gathered and signaled. */
public enum class IceMode {
    /** Direct P2P: every candidate is signaled, so the peer learns local and public addresses. */
    ALL,

    /** Hide My IP: TURN relay candidates only. */
    RELAY,
}

public object CandidatePolicy {
    /** Candidates signaled per pairing. The server closes the socket above 200. */
    public const val MAX_CANDIDATES_PER_PAIRING: Int = 150

    private val WHITESPACE = Regex("\\s+")

    /**
     * Whether signaling this ICE candidate reveals one of our real addresses to the
     * peer. Port of the page's `candidateExposesIp`: the parsed fields win where
     * given, the candidate line is read otherwise. Anything unreadable counts as
     * exposing: the IP status must never say "masked" on a guess.
     */
    public fun exposesIp(line: String?, type: String? = null, address: String? = null, relatedAddress: String? = null): Boolean {
        val tokens = (line ?: "").trim().split(WHITESPACE)
        fun after(word: String): String {
            val i = tokens.indexOf(word)
            return if (i != -1 && i + 1 < tokens.size) tokens[i + 1] else ""
        }
        val t = type?.takeIf { it.isNotEmpty() } ?: after("typ")
        val addr = address?.takeIf { it.isNotEmpty() } ?: if (tokens.size >= 6) tokens[4] else ""
        if (t == "host") return addr.isEmpty() || !addr.lowercase().endsWith(".local")
        if (t == "relay") {
            // A relay candidate's own address is the TURN server's. Its related
            // address is ours unless it is blanked.
            val raddr = relatedAddress?.takeIf { it.isNotEmpty() } ?: after("raddr")
            return raddr.isNotEmpty() && raddr != "0.0.0.0" && raddr != "::"
        }
        // srflx, prflx, or a type we cannot read.
        return true
    }

    /**
     * The public address in a server-reflexive (`typ srflx`) candidate line: what a
     * STUN server saw, which is the router's address. Null for any other line.
     * Two peers that report the same one are behind the same router.
     */
    public fun reflexiveAddress(line: String): String? {
        val tokens = line.trim().split(WHITESPACE)
        val typ = tokens.indexOf("typ")
        if (tokens.size < 6 || typ == -1 || typ + 1 >= tokens.size || tokens[typ + 1] != "srflx") return null
        return tokens[4].lowercase()
    }

    /**
     * The candidate line to signal, or null to drop it.
     *
     * [IceMode.ALL] signals everything unchanged. [IceMode.RELAY] signals only
     * `typ relay` candidates and blanks their related address, whatever the
     * WebRTC library put there, so the mode cannot leak an address even if the
     * library's own filtering changes.
     */
    public fun toSignal(line: String, mode: IceMode): String? {
        if (line.isBlank()) return null
        if (mode == IceMode.ALL) return line
        val tokens = line.trim().split(WHITESPACE).toMutableList()
        val typ = tokens.indexOf("typ")
        if (typ == -1 || typ + 1 >= tokens.size || tokens[typ + 1] != "relay") return null
        val raddr = tokens.indexOf("raddr")
        if (raddr != -1) {
            if (raddr + 1 >= tokens.size) return null
            tokens[raddr + 1] = "0.0.0.0"
        }
        val rport = tokens.indexOf("rport")
        if (rport != -1) {
            if (rport + 1 >= tokens.size) return null
            tokens[rport + 1] = "0"
        }
        val out = tokens.joinToString(" ")
        return if (exposesIp(out)) null else out
    }
}
