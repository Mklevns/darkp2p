package com.darkp2p.protocol

/**
 * Constants of the `darkp2p/v3` wire format and its timing rules.
 *
 * Each value mirrors the constant of the same name in the web client
 * (`public/index.html`). `WireConstantsTest` compares them with the constants
 * exported into `test/vectors/v3.json`, so a change on either side fails the build.
 */
public object WireConstants {
    // Frame layout: type(1) | transferId(16) | seq(4, BE) | iv(12) | ciphertext || tag(16).
    public const val FRAME_TYPE_HELLO: Int = 0x20
    public const val FRAME_TYPE_CTRL: Int = 0x21
    public const val FRAME_TYPE_DATA: Int = 0x22

    /** Frame types of wire formats v1 and v2: a peer sending one is outdated. */
    public val LEGACY_FRAME_TYPES: List<Int> = listOf(0x01, 0x02, 0x11, 0x12)

    public const val ROLE_INITIATOR: Int = 0x01
    public const val ROLE_RESPONDER: Int = 0x02

    public const val ROOM_KEY_LEN: Int = 32
    public const val SESSION_NONCE_LEN: Int = 32
    public const val TRANSFER_ID_LEN: Int = 16
    public const val FRAME_IV_LEN: Int = 12
    public const val FRAME_TAG_LEN: Int = 16
    public const val FRAME_HEADER_LEN: Int = 1 + TRANSFER_ID_LEN + 4 + FRAME_IV_LEN
    public const val FRAME_OVERHEAD: Int = FRAME_HEADER_LEN + FRAME_TAG_LEN

    public const val AAD_MAGIC: String = "DP3"
    public const val HKDF_INFO_HELLO: String = "darkp2p/v3/hello"
    public const val HKDF_INFO_TRANSFER: String = "darkp2p/v3/transfer"

    /** The protocol version carried in `hello` and `hello-ack`. */
    public const val PROTOCOL_VERSION: Int = 3

    // Transfer protocol.
    public const val CHUNK_SIZE: Int = 16_000
    public const val ACK_EVERY: Int = 64
    public const val TX_WINDOW: Int = 256

    /** `done` (N+1) and an abort (N+2) must still fit in the uint32 seq. */
    public const val MAX_FILE_CHUNKS: Long = 0xFFFF_FFFFL - 2

    /** The web client's cap for receives it must buffer in memory. Android streams to disk. */
    public const val RAM_MODE_MAX_BYTES: Long = 512L * 1024 * 1024
    public const val ACCEPT_TIMEOUT_MS: Long = 5 * 60 * 1000L
    public const val ACK_STALL_MS: Long = 60 * 1000L
    public const val COMPLETE_TIMEOUT_MS: Long = 5 * 60 * 1000L
    public const val TX_WATCHDOG_MS: Long = 1000L
    public const val HELLO_TIMEOUT_MS: Long = 5000L

    public val ABORT_REASONS: List<String> = listOf("user", "integrity", "disk", "timeout")
    public val DECLINE_REASONS: List<String> = listOf("user", "size", "busy")

    // Bounded bookkeeping.
    public const val TRANSFER_KEY_CACHE_MAX: Int = 8
    public const val SEEN_TRANSFER_IDS_MAX: Int = 64
    public const val ENDED_TRANSFER_IDS_MAX: Int = 16
    public const val RX_MAX_PENDING_CHUNKS: Int = 256
    public const val RX_MAX_PENDING_CTRL: Int = 32

    /** Local send buffer above which the sender waits (DataChannel and WebSocket). */
    public const val HIGH_WATER: Long = 1_048_576L

    // Signaling and relay.
    public const val RECONNECT_BASE_MS: Long = 1000L
    public const val RECONNECT_MAX_MS: Long = 30_000L

    /** The reconnect backoff resets only after a connection stayed up this long. */
    public const val RECONNECT_STABLE_MS: Long = 10_000L
    public const val FULL_RETRY_MS: Long = 90_000L

    /** Relay budget reserved per file for meta/done/ack/complete frames. */
    public const val RELAY_CTRL_RESERVE: Long = 1024L

    /** Below this much relay budget a room cannot complete the key handshake. */
    public const val RELAY_MIN_REMAINING: Long = 4096L
    public const val RELAY_BUSY_RETRY_MS: Long = 30_000L
}
