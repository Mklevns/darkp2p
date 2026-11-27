package com.darkp2p.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CryptoVectorsTest {
    @Test
    fun `HKDF matches the web client and RFC 5869`() {
        val vectors = Vectors.list("hkdf")
        assertTrue(vectors.size >= 9)
        for (v in vectors) {
            assertEquals(v.str("okm"), Bytes.toHex(Hkdf.sha256(v.hex("ikm"), v.hex("salt"), v.hex("info"), 32)), v.str("name"))
        }
    }

    @Test
    fun `AAD layout`() {
        for (v in Vectors.list("aad")) {
            val aad = FrameCodec.aad(FrameType.fromWire(v.int("type"))!!, Role.fromWire(v.int("role"))!!, TransferId(v.hex("transferId")), v.long("seq"))
            assertEquals(v.str("aad"), Bytes.toHex(aad))
        }
    }

    @Test
    fun `frames - same keys, same bytes, and they decode`() {
        val group = Vectors.obj("frames")
        val room = RoomKey(group.hex("roomKey"))
        val nonces = SessionNonces(group.hex("initNonce"), group.hex("respNonce"))
        val cache = TransferKeyCache(room, nonces, 64)
        val frames = group.arr("frames").map { it as JsonValue.Obj }
        assertTrue(frames.size >= 20)
        for (v in frames) {
            val name = v.str("name")
            val type = FrameType.fromWire(v.int("type"))!!
            val role = Role.fromWire(v.int("role"))!!
            val id = TransferId(v.hex("transferId"))
            val key = if (type == FrameType.HELLO) FrameKeys.hello(room, id) else cache[id]

            // Our derived key encrypts to the page's exact bytes (so it is the same key).
            val ours = FrameCodec.encode(type, role, id, v.long("seq"), v.hex("payload"), key, v.hex("iv"))
            assertEquals(v.str("frame"), Bytes.toHex(ours), name)
            // The raw key listed in the vector does too.
            val raw = FrameCodec.encode(type, role, id, v.long("seq"), v.hex("payload"), AeadKey.ofRaw(v.hex("key")), v.hex("iv"))
            assertEquals(v.str("frame"), Bytes.toHex(raw), name)

            val decoded = FrameCodec.decode(v.hex("frame"), role) { _, _, _ -> key }
            assertEquals(type, decoded.type, name)
            assertEquals(id, decoded.transferId, name)
            assertEquals(v.long("seq"), decoded.seq, name)
            assertContentEquals(v.hex("payload"), decoded.payload, name)
            // Reflection: the sender's own role as peer role fails.
            assertFailsWith<FrameError.Auth>(name) { FrameCodec.decode(v.hex("frame"), role.peer) { _, _, _ -> key } }
        }
    }

    @Test
    fun `control messages serialize to the page's exact JSON`() {
        val group = Vectors.obj("frames")
        val initNonce = group.hex("initNonce")
        val produced = setOf(
            Ctrl.hello(initNonce), Ctrl.helloAck(), Ctrl.meta("report.pdf", 40_000, "application/pdf", 3),
            Ctrl.meta("ünïcødé – 文件.bin", 0, "", 0), Ctrl.done(3, 40_000), Ctrl.accept(),
            Ctrl.decline(DeclineReason.USER), Ctrl.decline(DeclineReason.SIZE), Ctrl.decline(DeclineReason.BUSY),
            Ctrl.ack(64), Ctrl.ack(3, final = true), Ctrl.complete(),
            Ctrl.abort(AbortReason.USER), Ctrl.abort(AbortReason.INTEGRITY), Ctrl.abort(AbortReason.DISK), Ctrl.abort(AbortReason.TIMEOUT),
        )
        val theirs = group.arr("frames").mapNotNull { (it as JsonValue.Obj)["json"]?.stringOrNull }.toSet()
        assertEquals(theirs, produced)
        // And each one decodes to something other than Unknown.
        for (json in theirs) assertTrue(Ctrl.decode(json.toByteArray()) != Ctrl.Unknown, json)
    }

    @Test
    fun `refused frames are refused for the same reason`() {
        val group = Vectors.obj("framesBad")
        val cache = TransferKeyCache(RoomKey(group.hex("roomKey")), SessionNonces(group.hex("initNonce"), group.hex("respNonce")), 1024)
        val base = group.hex("base")
        val peer = Role.fromWire(group.int("peerRole"))!!
        fun code(bytes: ByteArray, role: Role): String = try {
            FrameCodec.decode(bytes, role) { id, _, _ -> cache[id] }
            "ok"
        } catch (e: FrameError) {
            e.code
        }
        assertEquals("ok", code(base, peer))

        val flips = group.arr("bitFlips").map { (it as JsonValue.Arr).items }
        assertEquals(base.size * 8, flips.size)
        for ((index, bit, expected) in flips) {
            val t = base.copyOf()
            t[index.long().toInt()] = (t[index.long().toInt()].toInt() xor (1 shl bit.long().toInt())).toByte()
            assertEquals(expected.stringOrNull, code(t, peer), "byte ${index.long()} bit ${bit.long()}")
        }
        for ((length, expected) in group.arr("truncations").map { (it as JsonValue.Arr).items }) {
            assertEquals(expected.stringOrNull, code(base.copyOf(length.long().toInt()), peer), "length ${length.long()}")
        }
        for (v in group.arr("others").map { it as JsonValue.Obj }) {
            assertEquals(v.str("code"), code(v.hex("frame"), Role.fromWire(v.int("peerRole"))!!), v.str("name"))
        }
    }

    @Test
    fun `transfer key cache evicts the least recently used`() {
        val room = RoomKey(ByteArray(32) { it.toByte() })
        val nonces = SessionNonces(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        val ids = List(12) { i -> TransferId(ByteArray(16) { i.toByte() }) }
        for (v in Vectors.list("transferKeyCache")) {
            val cache = TransferKeyCache(room, nonces, v.int("max"))
            val last = HashMap<Int, AeadKey>()
            val order = v.arr("order").map { it.long().toInt() }
            val hits = v.arr("hits").map { (it as JsonValue.Bool).value }
            order.forEachIndexed { step, i ->
                val key = cache[ids[i]]
                if (hits[step]) assertSame(last[i], key, "step $step") else assertNotSame(last[i], key, "step $step")
                last[i] = key
            }
        }
    }

    @Test
    fun `encode refuses bad inputs`() {
        val key = AeadKey.ofRaw(ByteArray(32))
        val id = TransferId(ByteArray(16))
        assertFailsWith<FrameError.Malformed> { FrameCodec.encode(FrameType.DATA, Role.INITIATOR, id, -1, ByteArray(1), key, ByteArray(12)) }
        assertFailsWith<FrameError.Malformed> { FrameCodec.encode(FrameType.DATA, Role.INITIATOR, id, 0x1_0000_0000, ByteArray(1), key, ByteArray(12)) }
        assertFailsWith<FrameError.Malformed> { FrameCodec.encode(FrameType.DATA, Role.INITIATOR, id, 0, ByteArray(1), key, ByteArray(11)) }
        assertFailsWith<IllegalArgumentException> { TransferId(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { RoomKey(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { SessionNonces(ByteArray(32), ByteArray(31)) }
        assertEquals(16_049, FrameCodec.encode(FrameType.DATA, Role.INITIATOR, id, 1, ByteArray(16_000), key, ByteArray(12)).size)
    }

    @Test
    fun `a key lookup can refuse a frame before decryption`() {
        val key = AeadKey.ofRaw(ByteArray(32))
        val frame = FrameCodec.encode(FrameType.DATA, Role.INITIATOR, TransferId(ByteArray(16)), 1, ByteArray(4), key, ByteArray(12))
        assertFailsWith<FrameError.Rejected> { FrameCodec.decode(frame, Role.INITIATOR) { _, _, _ -> throw FrameError.Rejected("no") } }
        assertFailsWith<FrameError.Stale> { FrameCodec.decode(frame, Role.INITIATOR) { _, _, _ -> throw FrameError.Stale() } }
        assertFailsWith<FrameError.Dropped> { FrameCodec.decode(frame, Role.INITIATOR) { _, _, _ -> throw FrameError.Dropped() } }
    }
}
