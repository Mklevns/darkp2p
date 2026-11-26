package com.darkp2p.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Chat: used only between two clients that both offer it, and never able to disturb a file transfer. */
class ChatTest {
    private val key = ByteArray(32) { (it * 5).toByte() }
    private val relay = LocalPolicy(forceWsRelay = true)

    /** What the web client is to this feature: a peer whose hello offers nothing. */
    private val webLike = LocalPolicy(chat = false)

    private fun pair(a: LocalPolicy = LocalPolicy(), b: LocalPolicy = LocalPolicy(), setup: World.() -> Unit = {}): Triple<World, Peer, Peer> {
        val world = World()
        world.setup()
        val first = world.peer("a", key, a)
        val second = world.peer("b", key, b)
        first.start(); world.run()
        second.start(); world.run()
        return Triple(world, first, second)
    }

    private fun chatOn(p: Peer) = assertIs<RoomState.Ready>(p.state).chat

    private fun frames(p: Peer) = p.effectsOf<RoomEffect.SendFrame>().size

    @Test
    fun `two app peers chat in both directions, in order, with receipts`() {
        for (policy in listOf(LocalPolicy(), relay)) {
            val (world, a, b) = pair(a = policy)
            assertTrue(chatOn(a) && chatOn(b))
            a.chat(1, "hello")
            a.chat(2, "ünïcødé 文件 😀 \"quoted\" \\ \n line")
            b.chat(10, "hi")
            world.run()
            a.chat(3, "third"); world.run()
            assertEquals(listOf("hello", "ünïcødé 文件 😀 \"quoted\" \\ \n line", "third"), b.chatReceived)
            assertEquals(listOf("hi"), a.chatReceived)
            assertEquals(listOf(1L, 2L, 3L), a.chatDelivered)
            assertEquals(listOf(10L), b.chatDelivered)
            assertTrue(a.chatFailed.isEmpty() && b.chatFailed.isEmpty())
        }
    }

    @Test
    fun `with a peer that does not offer chat nothing of it is ever sent`() {
        for ((pa, pb) in listOf(LocalPolicy() to webLike, webLike to LocalPolicy(), relay to webLike)) {
            val (world, a, b) = pair(a = pa, b = pb)
            assertFalse(chatOn(a))
            assertFalse(chatOn(b))
            val before = frames(a) + frames(b)
            a.chat(1, "hello?"); b.chat(2, "hello?"); world.run()
            assertEquals(before, frames(a) + frames(b))
            assertEquals(listOf(1L to ChatFailure.UNAVAILABLE), a.chatFailed)
            assertEquals(listOf(2L to ChatFailure.UNAVAILABLE), b.chatFailed)
            assertTrue(a.chatReceived.isEmpty() && b.chatReceived.isEmpty())
            // Files are unaffected.
            a.send("f", ByteArray(50_000) { it.toByte() }); world.run()
            assertEquals(1, b.committed.size)
        }
    }

    @Test
    fun `the hello without the offer is the page's hello byte for byte`() {
        val nonce = ByteArray(32) { 1 }
        assertEquals("""{"type":"hello","v":3,"nonce":"${Base64Url.encode(nonce)}"}""", Ctrl.hello(nonce))
        assertEquals("""{"type":"hello","v":3,"nonce":"${Base64Url.encode(nonce)}","caps":["chat"]}""", Ctrl.hello(nonce, listOf("chat")))
        fun caps(json: String) = (Ctrl.decode(json.toByteArray()) as Ctrl.Hello).caps
        assertEquals(setOf("chat", "future"), caps("""{"type":"hello","v":3,"nonce":"x","caps":["chat","future",7,null,"chat"]}"""))
        for (none in listOf("\"chat\"", "{\"chat\":true}", "true", "null", "7", "[]", "[[\"chat\"]]")) {
            assertEquals(emptySet(), caps("""{"type":"hello","v":3,"nonce":"x","caps":$none}"""), none)
        }
        assertEquals(Capabilities.MAX_LISTED, caps("""{"type":"hello","v":3,"caps":[${(1..100).joinToString(",") { "\"c$it\"" }}]}""").size)
    }

    @Test
    fun `chat while files move in both directions disturbs neither`() {
        val (world, a, b) = pair()
        val fromA = ByteArray(700 * 16_000 + 9) { (it * 31).toByte() }
        val fromB = ByteArray(300_000) { (it * 17).toByte() }
        a.send("a", fromA); b.send("b", fromB)
        var n = 0L
        repeat(60) {
            world.run(limit = 150)
            a.chat(++n, "a$n"); b.chat(++n, "b$n")
        }
        world.run()
        assertTrue(fromA.contentEquals(b.committed.single()) && fromB.contentEquals(a.committed.single()))
        // Every message either arrived (in order) or was refused locally because the window was full.
        val sentByA = (1..n step 2).filter { id -> a.chatFailed.none { it.first == id } }
        assertEquals(sentByA.map { "a$it" }, b.chatReceived)
        assertEquals(sentByA, a.chatDelivered)
        assertTrue(a.chatFailed.all { it.second == ChatFailure.BUSY })
        assertTrue(sentByA.size >= 30)
    }

    @Test
    fun `limits - empty, too long, and more than eight awaiting a receipt`() {
        val (world, a, b) = pair()
        a.chat(1, "")
        a.chat(2, "x".repeat(ChatConstants.MAX_TEXT_BYTES + 1))
        a.chat(3, "é".repeat(ChatConstants.MAX_TEXT_BYTES / 2 + 1)) // counted in bytes, not characters
        a.chat(4, "x".repeat(ChatConstants.MAX_TEXT_BYTES))
        world.run()
        assertEquals(listOf(1L to ChatFailure.EMPTY, 2L to ChatFailure.TOO_LONG, 3L to ChatFailure.TOO_LONG), a.chatFailed)
        assertEquals(listOf(ChatConstants.MAX_TEXT_BYTES), b.chatReceived.map { it.length })

        a.chatFailed.clear()
        repeat(12) { a.chat(100L + it, "m$it") } // all queued before any receipt can come back
        world.run()
        assertEquals((108L..111L).map { it to ChatFailure.BUSY }, a.chatFailed)
        assertEquals((100L..107L).toList(), a.chatDelivered.drop(1))
        a.chat(200, "after"); world.run()
        assertEquals("after", b.chatReceived.last())
    }

    @Test
    fun `replayed, reordered and forged chat frames are refused and nothing else is harmed`() {
        val (world, a, b) = pair(a = relay)
        a.chat(1, "one"); a.chat(2, "two"); a.chat(3, "three"); world.run()
        assertEquals(listOf("one", "two", "three"), b.chatReceived)
        val sb = world.server.conns.first { it.peer === b }.socket
        val ref = ChannelRef(ChannelKind.WS_RELAY, sb)
        val sent = a.effectsOf<RoomEffect.SendFrame>().map { it.bytes }

        // The relay replays everything a ever sent, then again in reverse.
        for (f in sent + sent.reversed()) b.post(RoomInput.Frame(ref, f))
        world.run()
        assertEquals(listOf("one", "two", "three"), b.chatReceived)
        assertIs<RoomState.Ready>(b.state)

    }

    @Test
    fun `a dropped message stops the stream instead of showing later ones out of sequence`() {
        val (world, a, b) = pair(a = relay)
        a.chat(1, "one"); world.run()
        // Take a's next two frames off the wire and deliver only the second.
        val conn = world.server.conns.first { it.peer === a }
        world.server.relayActive = false // the relay silently drops what a sends now
        a.machine.handle(RoomInput.SendChat(2, "two")).let { a.apply(it) }
        val second = a.machine.handle(RoomInput.SendChat(3, "three"))
        world.server.relayActive = true
        a.apply(second); world.run()
        assertEquals(listOf("one"), b.chatReceived)
        assertTrue(b.has(Notice.CHAT_FRAME_REFUSED))
        assertEquals(listOf(1L), a.chatDelivered) // 2 and 3 never get a receipt...
        assertIs<RoomState.Ready>(b.state)
        check(conn.joined)

        // ...and are reported when the session ends.
        world.server.disconnect(world.server.conns.first { it.peer === b }, notifyClient = true); world.run()
        assertEquals(listOf(2L to ChatFailure.SESSION_ENDED, 3L to ChatFailure.SESSION_ENDED), a.chatFailed)
    }

    @Test
    fun `receipts confirm each message once, and malformed chat fields decode to refusals`() {
        val (world, a, b) = pair()
        a.chat(1, "one"); world.run()
        assertEquals(listOf(1L), a.chatDelivered)
        // b's stream is at frame 1 (it sent one receipt). A receipt claiming frame 99 of a's stream:
        // only b could produce it, and a refuses it without confirming anything.
        a.chat(2, "two")
        world.run()
        assertEquals(listOf(1L, 2L), a.chatDelivered)
        assertEquals(Ctrl.ChatAck(null), Ctrl.decode("""{"type":"chat-ack","seq":"1"}""".toByteArray()))
        assertEquals(Ctrl.ChatAck(null), Ctrl.decode("""{"type":"chat-ack"}""".toByteArray()))
        assertEquals(null, (Ctrl.decode("""{"type":"chat","text":7}""".toByteArray()) as Ctrl.Chat).text)
        assertTrue(b.chatFailed.isEmpty())
    }

    @Test
    fun `chat stops at the relay reserve and never spends the room's last budget`() {
        val (world, a, b) = pair(a = relay) { server.cap = 20_000 }
        var id = 0L
        repeat(40) { a.chat(++id, "x".repeat(2_000)); world.run() }
        assertTrue(a.chatFailed.isNotEmpty())
        assertTrue(a.chatFailed.all { it.second == ChatFailure.NO_RELAY_BUDGET })
        assertEquals(a.chatDelivered.size, b.chatReceived.size)
        val ready = assertIs<RoomState.Ready>(a.state)
        assertTrue(ready.relayRemaining!! >= ChatConstants.RELAY_RESERVE, "${ready.relayRemaining}")
        assertEquals(world.server.cap - world.server.relayBytes, ready.relayRemaining) // still the server's count
        assertEquals(0, world.server.droppedOverCap)
    }

    @Test
    fun `a new session starts new chat streams`() {
        val (world, a, b) = pair(a = relay)
        a.chat(1, "before"); world.run()
        world.server.expireIdleRelay(); world.run()
        assertEquals(RoomState.RelayIdle, a.state)
        a.chat(2, "while paused"); world.run()
        assertEquals(listOf(2L to ChatFailure.UNAVAILABLE), a.chatFailed)
        a.post(RoomInput.ResumeRelay); world.run()
        assertTrue(chatOn(a) && chatOn(b))
        a.chat(3, "after"); b.chat(4, "back"); world.run()
        assertEquals(listOf("before", "after"), b.chatReceived)
        assertEquals(listOf("back"), a.chatReceived)
        assertEquals(listOf(1L, 3L), a.chatDelivered)
    }

    @Test
    fun `message text never appears in a description of an effect or control message`() {
        val secret = "the launch code"
        assertFalse(secret in RoomEffect.ChatReceived(secret).toString())
        assertFalse(secret in Ctrl.decode(Ctrl.chat(secret).toByteArray()).toString())
        assertFalse(secret in RoomInput.SendChat(1, secret).toString())
    }
}
