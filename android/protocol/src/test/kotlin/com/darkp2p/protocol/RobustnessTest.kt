package com.darkp2p.protocol

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hostile and random input to every parser: the only acceptable failures are the declared ones. */
class RobustnessTest {
    private val random = Random(99)

    private fun randomText(alphabet: String, max: Int) = buildString { repeat(random.nextInt(max)) { append(alphabet[random.nextInt(alphabet.length)]) } }

    @Test
    fun `JSON parser - random text either parses or fails with JsonParseException`() {
        val alphabet = "{}[]\",:\\ \n\t0123456789.-+eEtruefalsn\u0000\u00e9\ud83d\ude00abu"
        var parsed = 0
        repeat(200_000) {
            try {
                val value = Json.parse(randomText(alphabet, 24))
                parsed++
                // Whatever parses survives a round trip through the serializer
                // (compared as text: -0 is written as 0, as JSON.stringify writes it).
                val text = Json.stringify(value)
                assertEquals(text, Json.stringify(Json.parse(text)))
            } catch (e: JsonParseException) {
                // expected for most inputs
            }
        }
        assertTrue(parsed > 1000, "the alphabet should produce some valid JSON ($parsed)")
    }

    @Test
    fun `JSON parser - the grammar is JSON's, not a superset`() {
        for (bad in listOf(
            "", " ", "{", "[1,]", "{\"a\":1,}", "{'a':1}", "{a:1}", "01", "1.", ".5", "+1", "1e", "0x10", "NaN", "Infinity", "undefined",
            "tru", "nul", "\"\\x41\"", "\"\\u12\"", "\"a\nb\"", "\"unterminated", "[1 2]", "{\"a\" 1}", "1 2", "// c\n1", "\u00a01", "\"\\ud800\\u\"",
        )) {
            assertFailsWith<JsonParseException>(bad) { Json.parse(bad) }
        }
        assertEquals(JsonValue.Num(-0.5e3), Json.parse(" -0.5E+3 "))
        assertEquals(JsonValue.Str("a\"\\/\b\u000c\n\r\t\u00e9\ud83d\ude00"), Json.parse("\"a\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00e9\\ud83d\\ude00\""))
        assertEquals(Json.obj("a" to Json.num(2)), Json.parse("""{"a":1,"a":2}""")) // last duplicate wins
        assertEquals(JsonValue.Null, Json.parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "null".toByteArray())) // BOM
        assertFailsWith<JsonParseException> { Json.parse("[".repeat(100) + "]".repeat(100)) }
        // Serializer: control characters and lone surrogates are escaped, as JSON.stringify does.
        assertEquals("\"\\u0001\\ud800x\\\"\\\\\\n\"", Json.stringify(Json.str("\u0001\ud800x\"\\\n")))
        assertEquals("[null,true,1.5,-3]", Json.stringify(JsonValue.Arr(listOf(JsonValue.Null, JsonValue.Bool(true), JsonValue.Num(1.5), JsonValue.Num(-3.0)))))
    }

    @Test
    fun `control frames - anything that is not a JSON object or array is an integrity error`() {
        for (bad in listOf("", "7", "\"x\"", "null", "true", "{", "{\"type\":}")) {
            assertEquals(AbortReason.INTEGRITY, assertFailsWith<ProtocolError>(bad) { Ctrl.decode(bad.toByteArray()) }.reason)
        }
        assertEquals(Ctrl.Unknown, Ctrl.decode("[]".toByteArray()))
        assertEquals(Ctrl.Unknown, Ctrl.decode("{}".toByteArray()))
        assertEquals(Ctrl.Unknown, Ctrl.decode("""{"type":7}""".toByteArray()))
        assertEquals(Ctrl.Hello(false, null), Ctrl.decode("""{"type":"hello","v":"3"}""".toByteArray()))
        assertEquals(Ctrl.Hello(true, "x"), Ctrl.decode("""{"type":"hello","v":3.0,"nonce":"x"}""".toByteArray()))
        assertEquals(Ctrl.Ack(5, done = false, valid = true), Ctrl.decode("""{"type":"ack","seq":5}""".toByteArray()))
        assertEquals(false, (Ctrl.decode("""{"type":"ack","seq":5,"done":false}""".toByteArray()) as Ctrl.Ack).valid)
        assertEquals(false, (Ctrl.decode("""{"type":"ack","seq":"5"}""".toByteArray()) as Ctrl.Ack).valid)
        assertEquals(Ctrl.Done(null, 3), Ctrl.decode("""{"type":"done","chunks":1.5,"size":3}""".toByteArray()))
        // File names the page would also build from non-text values.
        fun name(json: String) = Ctrl.parseMeta(Ctrl.decode("""{"type":"meta","name":$json,"size":1,"chunks":1}""".toByteArray()) as Ctrl.Meta).name
        assertEquals("1,,a,true", name("""[1,null,"a",true]"""))
        assertEquals("", name("[]"))
        assertEquals("[object Object]", name("{}"))
        assertEquals("1.5", name("1.5"))
        assertEquals("download.bin", name("0"))
        assertEquals("a_b_c", name("\"a/b\\\\c\""))
    }

    @Test
    fun `frame decoder - random bytes are refused with a FrameError`() {
        val key = AeadKey.ofRaw(ByteArray(32) { 3 })
        repeat(100_000) {
            val bytes = ByteArray(random.nextInt(140)).also(random::nextBytes)
            if (bytes.isNotEmpty() && random.nextBoolean()) bytes[0] = (0x20 + random.nextInt(3)).toByte()
            assertFailsWith<FrameError> { FrameCodec.decode(bytes, Role.INITIATOR) { _, _, _ -> key } }
        }
    }

    @Test
    fun `links and base64 - random text never throws`() {
        val alphabet = "abcdefABCDEF0123456789:-_+/= #?.ghXYZ\n"
        repeat(100_000) {
            val text = randomText(alphabet, 120)
            RoomLink.parseFragment(text)
            RoomLink.parse("https://darkp2p.com/#$text")
            RoomLink.parse(text)
            Base64Url.decode(text)
        }
        // Round trip for every length.
        for (n in 0..70) {
            val bytes = ByteArray(n).also(random::nextBytes)
            assertEquals(Bytes.toHex(bytes), Bytes.toHex(Base64Url.decode(Base64Url.encode(bytes))!!))
            assertEquals(Bytes.toHex(bytes), Bytes.toHex(Bytes.fromHex(Bytes.toHex(bytes))))
        }
        assertNull(Base64Url.decode("abcde"))
        assertNull(Base64Url.decode("ab=d"))
        assertEquals(1, Base64Url.decode(" QQ==   ")!!.size) // atob ignores whitespace
        assertNull(Base64Url.decode(" Q Q = = ")) // ...but the page pads before atob sees it, so this fails there too
        assertFailsWith<IllegalArgumentException> { Bytes.fromHex("abc") }
        assertFailsWith<IllegalArgumentException> { Bytes.fromHex("zz") }
        assertTrue(Bytes.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertTrue(!Bytes.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)) && !Bytes.constantTimeEquals(null, byteArrayOf()))
    }

    @Test
    fun `candidate policy - relay mode lets nothing but blanked relay candidates through`() {
        val relay = "candidate:3 1 udp 41885439 198.51.100.9 49200 typ relay raddr 203.0.113.7 rport 61000 generation 0"
        assertEquals("candidate:3 1 udp 41885439 198.51.100.9 49200 typ relay raddr 0.0.0.0 rport 0 generation 0", CandidatePolicy.toSignal(relay, IceMode.RELAY))
        assertEquals(relay, CandidatePolicy.toSignal(relay, IceMode.ALL))
        for (dropped in listOf(
            "candidate:1 1 udp 1 192.168.1.2 5 typ host", "candidate:2 1 udp 1 203.0.113.7 5 typ srflx raddr 192.168.1.2 rport 5",
            "candidate:4 1 udp 1 203.0.113.7 5 typ prflx", "candidate:5 1 udp 1 198.51.100.9 5 typ relay raddr", "candidate:5 1 udp 1 198.51.100.9 5 typ relay rport",
            "candidate:6 1 udp 1 198.51.100.9 5 typ", "garbage", "", "   ",
        )) {
            assertNull(CandidatePolicy.toSignal(dropped, IceMode.RELAY), dropped)
        }
        assertNull(CandidatePolicy.toSignal("", IceMode.ALL))
        // Random candidate-shaped lines: whatever relay mode signals exposes no address.
        val words = listOf("candidate:1", "1", "udp", "tcp", "typ", "relay", "host", "srflx", "raddr", "rport", "192.168.1.2", "0.0.0.0", "::", "203.0.113.7", "9", "x.local")
        repeat(100_000) {
            val line = List(random.nextInt(14)) { words[random.nextInt(words.size)] }.joinToString(" ")
            CandidatePolicy.toSignal(line, IceMode.RELAY)?.let { assertTrue(!CandidatePolicy.exposesIp(it), "$line -> $it") }
        }
    }
}
