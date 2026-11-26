package com.darkp2p.protocol

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

public enum class FrameType(public val wire: Int) {
    HELLO(WireConstants.FRAME_TYPE_HELLO),
    CTRL(WireConstants.FRAME_TYPE_CTRL),
    DATA(WireConstants.FRAME_TYPE_DATA),
    ;

    public companion object {
        public fun fromWire(wire: Int): FrameType? = entries.firstOrNull { it.wire == wire }
    }
}

public enum class Role(public val wire: Int) {
    INITIATOR(WireConstants.ROLE_INITIATOR),
    RESPONDER(WireConstants.ROLE_RESPONDER),
    ;

    public val peer: Role get() = if (this == INITIATOR) RESPONDER else INITIATOR

    public companion object {
        public fun fromWire(wire: Int): Role? = entries.firstOrNull { it.wire == wire }
    }
}

/** A 16-byte transfer ID. Compared in constant time; [hex] is the map key. */
public class TransferId(bytes: ByteArray) {
    private val value: ByteArray = bytes.copyOf()

    init {
        require(value.size == WireConstants.TRANSFER_ID_LEN) { "transferId must be 16 bytes" }
    }

    public val hex: String = Bytes.toHex(value)

    public fun bytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is TransferId && Bytes.constantTimeEquals(value, other.value)

    override fun hashCode(): Int = hex.hashCode()

    override fun toString(): String = "TransferId(${hex.take(8)}…)"
}

/** The 32-byte secret from the room link. Input keying material only: never used as a cipher key. */
public class RoomKey(bytes: ByteArray) : AutoCloseable {
    private val ikm: ByteArray = bytes.copyOf()

    init {
        require(ikm.size == WireConstants.ROOM_KEY_LEN) { "Room key must be 32 bytes" }
    }

    internal fun derive(salt: ByteArray, info: ByteArray): AeadKey = AeadKey(Hkdf.sha256(ikm, salt, info, 32))

    /** The key for the share link. */
    public fun toBase64Url(): String = Base64Url.encode(ikm)

    /** Overwrites this copy of the key. Keys already derived from it are not affected. */
    override fun close() {
        ikm.fill(0)
    }

    override fun toString(): String = "RoomKey(***)"
}

/** An AES-256-GCM frame key. */
public class AeadKey internal constructor(raw: ByteArray) {
    internal val key: SecretKey = SecretKeySpec(raw, "AES")

    init {
        require(raw.size == 32) { "AES-256 key must be 32 bytes" }
        raw.fill(0)
    }

    override fun toString(): String = "AeadKey(***)"

    public companion object {
        /** For test vectors, which list raw keys. */
        public fun ofRaw(raw: ByteArray): AeadKey = AeadKey(raw.copyOf())
    }
}

/** Both peers' hello nonces; the initiator's first, whoever computes the key. */
public class SessionNonces(init: ByteArray, resp: ByteArray) {
    internal val init: ByteArray = init.copyOf()
    internal val resp: ByteArray = resp.copyOf()

    init {
        require(this.init.size == WireConstants.SESSION_NONCE_LEN && this.resp.size == WireConstants.SESSION_NONCE_LEN) {
            "Session nonce must be 32 bytes"
        }
    }
}

public object FrameKeys {
    private val INFO_HELLO = WireConstants.HKDF_INFO_HELLO.toByteArray(Charsets.US_ASCII)
    private val INFO_TRANSFER = WireConstants.HKDF_INFO_TRANSFER.toByteArray(Charsets.US_ASCII)

    /** Key of a HELLO frame: the room key only (no session exists yet). */
    public fun hello(room: RoomKey, id: TransferId): AeadKey = room.derive(id.bytes(), INFO_HELLO)

    /** Key of a CTRL/DATA frame: bound to the transfer and to both session nonces. */
    public fun transfer(room: RoomKey, id: TransferId, nonces: SessionNonces): AeadKey =
        room.derive(id.bytes() + nonces.init + nonces.resp, INFO_TRANSFER)
}

/** One session's transfer keys: at most [max], least recently used evicted. */
public class TransferKeyCache(
    private val room: RoomKey,
    private val nonces: SessionNonces,
    private val max: Int = WireConstants.TRANSFER_KEY_CACHE_MAX,
) {
    // Access order: a lookup moves the entry to the end; the eldest is evicted.
    private val entries = LinkedHashMap<String, AeadKey>(16, 0.75f, true)

    public operator fun get(id: TransferId): AeadKey {
        entries[id.hex]?.let { return it }
        val key = FrameKeys.transfer(room, id, nonces)
        entries[id.hex] = key
        while (entries.size > max) entries.remove(entries.keys.first())
        return key
    }
}

/** Why a frame was not accepted. [code] is the web client's `FrameError.code`. */
public sealed class FrameError(public val code: String, message: String) : Exception(message) {
    public class Malformed(message: String) : FrameError("malformed", message)
    public class Legacy : FrameError("legacy", "Legacy (v1/v2) frame")
    public class Auth : FrameError("auth", "Frame authentication failed")

    /** Refused by the key lookup before decryption. */
    public class Rejected(message: String) : FrameError("rejected", message)
    public class Stale : FrameError("stale", "No session yet")
    public class Dropped : FrameError("dropped", "Frame of an ended transfer")
}

public class DecodedFrame(
    public val type: FrameType,
    public val transferId: TransferId,
    public val seq: Long,
    public val payload: ByteArray,
)

/**
 * Wire format v3: `type(1) | transferId(16) | seq(4, BE) | iv(12) | ciphertext || tag(16)`.
 * AAD = `"DP3" | type | senderRole | transferId | seq`.
 */
public object FrameCodec {
    public const val HEADER_LEN: Int = WireConstants.FRAME_HEADER_LEN
    public const val OVERHEAD: Int = WireConstants.FRAME_OVERHEAD
    public const val MAX_SEQ: Long = 0xFFFF_FFFFL

    private const val TAG_BITS = WireConstants.FRAME_TAG_LEN * 8
    private val MAGIC = WireConstants.AAD_MAGIC.toByteArray(Charsets.US_ASCII)

    public fun aad(type: FrameType, senderRole: Role, id: TransferId, seq: Long): ByteArray = aad(type.wire, senderRole, id, seq)

    private fun aad(typeWire: Int, senderRole: Role, id: TransferId, seq: Long): ByteArray =
        ByteBuffer.allocate(MAGIC.size + 2 + WireConstants.TRANSFER_ID_LEN + 4)
            .put(MAGIC).put(typeWire.toByte()).put(senderRole.wire.toByte()).put(id.bytes()).putInt(seq.toInt())
            .array()

    /**
     * @param iv 12 bytes that must never repeat under [key]: production code passes
     *   fresh `SecureRandom` output; only test vectors fix it.
     */
    public fun encode(type: FrameType, role: Role, id: TransferId, seq: Long, payload: ByteArray, key: AeadKey, iv: ByteArray): ByteArray {
        if (seq < 0 || seq > MAX_SEQ) throw FrameError.Malformed("seq must be a uint32")
        if (iv.size != WireConstants.FRAME_IV_LEN) throw FrameError.Malformed("iv must be 12 bytes")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key.key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(type, role, id, seq))
        val ct = cipher.doFinal(payload)
        return ByteBuffer.allocate(HEADER_LEN + ct.size)
            .put(type.wire.toByte()).put(id.bytes()).putInt(seq.toInt()).put(iv).put(ct)
            .array()
    }

    /**
     * @param peerRole the role of the frame's SENDER.
     * @param keyLookup runs before decryption and may throw a [FrameError] to refuse the frame.
     */
    public fun decode(bytes: ByteArray, peerRole: Role, keyLookup: (TransferId, FrameType, Long) -> AeadKey): DecodedFrame {
        if (bytes.isEmpty()) throw FrameError.Malformed("Empty frame")
        val typeWire = bytes[0].toInt() and 0xff
        if (typeWire in WireConstants.LEGACY_FRAME_TYPES) throw FrameError.Legacy()
        val type = FrameType.fromWire(typeWire) ?: throw FrameError.Malformed("Unknown frame type")
        if (bytes.size < OVERHEAD) throw FrameError.Malformed("Truncated frame")

        val buf = ByteBuffer.wrap(bytes)
        val id = TransferId(bytes.copyOfRange(1, 1 + WireConstants.TRANSFER_ID_LEN))
        val seq = buf.getInt(1 + WireConstants.TRANSFER_ID_LEN).toLong() and MAX_SEQ
        val key = keyLookup(id, type, seq)

        val payload = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key.key, GCMParameterSpec(TAG_BITS, bytes, HEADER_LEN - WireConstants.FRAME_IV_LEN, WireConstants.FRAME_IV_LEN))
            cipher.updateAAD(aad(type, peerRole, id, seq))
            cipher.doFinal(bytes, HEADER_LEN, bytes.size - HEADER_LEN)
        } catch (e: GeneralSecurityException) {
            throw FrameError.Auth()
        }
        return DecodedFrame(type, id, seq, payload)
    }
}
