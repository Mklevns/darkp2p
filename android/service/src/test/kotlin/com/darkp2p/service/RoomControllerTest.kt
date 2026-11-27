package com.darkp2p.service

import com.darkp2p.protocol.LocalPolicy
import com.darkp2p.protocol.RoomLink
import com.darkp2p.protocol.RoomState
import com.darkp2p.transport.HttpClients
import com.darkp2p.transport.NetworkProfile
import com.darkp2p.transport.WebSide
import com.darkp2p.transport.fileBytes
import com.darkp2p.transport.sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class MemorySource(override val name: String, private val bytes: ByteArray, override val mime: String = "application/octet-stream") : FileSource {
    override val size: Long get() = bytes.size.toLong()
    @Volatile var closed = false

    override suspend fun read(offset: Long, length: Int): ByteArray = bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))

    override fun close() {
        closed = true
    }
}

private class MemoryStore(var free: Long? = null, var failCreate: Boolean = false) : FileStore {
    class Saved(val target: SaveTarget, val name: String, val mime: String, val bytes: ByteArray)

    val saved = CopyOnWriteArrayList<Saved>()
    val discarded = AtomicInteger()
    val open = AtomicInteger()

    override suspend fun freeSpace(target: SaveTarget): Long? = free

    override suspend fun create(target: SaveTarget, name: String, mime: String, size: Long): FileSink {
        if (failCreate) throw IOException("no access")
        open.incrementAndGet()
        val buffer = ByteArrayOutputStream()
        return object : FileSink {
            override suspend fun write(bytes: ByteArray) = buffer.write(bytes)

            override suspend fun commit(): String {
                saved += Saved(target, name, mime, buffer.toByteArray())
                open.decrementAndGet()
                return name
            }

            override suspend fun discard() {
                discarded.incrementAndGet()
                open.decrementAndGet()
            }
        }
    }
}

/** The room controller (queue, destinations, chat history) against the real server.js and page. */
class RoomControllerTest {
    private class App(link: RoomLink, val store: MemoryStore = MemoryStore()) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = RoomController(link, HttpClients.create(NetworkProfile.Clearnet), store, scope, LocalPolicy(forceWsRelay = true))
        val savedNames = CopyOnWriteArrayList<String>()

        init {
            scope.launch { controller.saved.collect { savedNames += it } }
            controller.start()
        }

        suspend fun await(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
            try {
                withTimeout(timeoutMs) { while (!condition()) delay(10) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("timed out waiting for $what (state ${controller.room.value.state})")
            }
        }

        suspend fun awaitReady() = await("a verified session") { controller.room.value.state is RoomState.Ready }

        override fun close() {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun `queued files are offered one after another and their sources released`() = runBlocking<Unit> {
        WebSide().use { web ->
            val link = web.newLink()
            val page = web.page("p", link)
            page.waitLog("^Connected to signaling broker")
            App(link).use { app ->
                // Queued before the room is even ready (a share from another app).
                val sources = listOf(
                    MemorySource("one.bin", fileBytes("one", 40_000)),
                    MemorySource("two.bin", fileBytes("two", 0)),
                    MemorySource("three.bin", fileBytes("three", 70_001)),
                )
                sources.forEach(app.controller::enqueue)
                app.await("the queue to show") { app.controller.queue.value.isNotEmpty() || app.controller.room.value.outgoing != null }
                app.awaitReady()
                for ((i, source) in sources.withIndex()) {
                    page.accept()
                    assertEquals(sha256(fileBytes(source.name.removeSuffix(".bin"), source.size.toInt())) to source.size.toInt(), page.download(i), source.name)
                }
                app.await("the queue to drain") { app.controller.queue.value.isEmpty() && app.controller.room.value.outgoing == null }
                app.await("every source closed") { sources.all { it.closed } }
                assertEquals(emptyList(), page.errors())

                // A declined file does not hold up the next one.
                val a = MemorySource("declined.bin", fileBytes("d", 10))
                val b = MemorySource("after.bin", fileBytes("after", 5_000))
                app.controller.enqueue(a); app.controller.enqueue(b)
                page.decline()
                page.accept()
                assertEquals(5_000, page.download(3).second)
                app.await("both closed") { a.closed && b.closed }
            }
        }
    }

    @Test
    fun `an incoming file is shown with a safe name, saved on accept, and nothing is left on decline or cancel`() = runBlocking<Unit> {
        WebSide().use { web ->
            val link = web.newLink()
            val page = web.page("p", link)
            page.waitLog("^Connected to signaling broker")
            App(link).use { app ->
                app.awaitReady()
                page.waitReady()

                page.send("in‮voice.pdf", "a", 123_456)
                app.await("the offer") { app.controller.offer.value != null }
                assertEquals(IncomingOffer("invoice.pdf", 123_456, "application/octet-stream"), app.controller.offer.value)
                app.controller.accept(SaveTarget.Downloads)
                app.await("the saved file") { app.store.saved.size == 1 }
                assertContentEquals(fileBytes("a", 123_456), app.store.saved[0].bytes)
                assertEquals("invoice.pdf", app.store.saved[0].name)
                app.await("the saved name") { app.savedNames == listOf("invoice.pdf") }
                assertEquals(0, app.store.open.get())
                page.waitLog("^Delivered: ") // the page offers its next file only after this one is confirmed

                // Declined: no destination is ever opened.
                page.send("no.bin", "b", 1000)
                app.await("the second offer") { app.controller.offer.value?.name == "no.bin" }
                app.controller.decline()
                page.waitLog("^Not sent: no\\.bin \\(declined by the peer\\)")
                assertEquals(0, app.store.open.get() + app.store.discarded.get())

                // Cancelled halfway: what was written is discarded.
                page.send("big.bin", "c", 4_000_000)
                app.await("the third offer") { app.controller.offer.value?.name == "big.bin" }
                app.controller.accept(SaveTarget.Document("content://docs/42"))
                app.await("bytes arriving") { (app.controller.room.value.incoming?.bytesDone ?: 0) > 200_000 }
                assertTrue(app.controller.activity.value.transferActive)
                app.controller.cancelReceive()
                page.waitLog("^Receiver aborted big\\.bin \\(user\\)")
                app.await("the partial file discarded") { app.store.discarded.get() == 1 && app.store.open.get() == 0 }
                assertEquals(1, app.store.saved.size)
                app.await("activity to stop") { !app.controller.activity.value.transferActive }
            }
        }
    }

    @Test
    fun `no space or no access means the sender is told, not that the transfer dies halfway`() = runBlocking<Unit> {
        WebSide().use { web ->
            val link = web.newLink()
            val page = web.page("p", link)
            page.waitLog("^Connected to signaling broker")
            App(link, MemoryStore(free = 1_000_000)).use { app ->
                app.awaitReady(); page.waitReady()
                page.send("huge.bin", "h", 5_000_000)
                page.waitLog("^Not sent: huge\\.bin \\(too large for the peer's browser\\)")
                assertEquals(null, app.controller.offer.value)

                // The destination cannot be opened (permission withdrawn): declined.
                app.store.free = null
                app.store.failCreate = true
                page.send("x.bin", "x", 1000)
                app.await("the offer") { app.controller.offer.value != null }
                app.controller.accept()
                page.waitLog("^Not sent: x\\.bin \\(declined by the peer\\)")

                app.store.failCreate = false
                page.waitLog("^Not sent: x")
                page.send("ok.bin", "ok", 1000)
                app.await("the offer") { app.controller.offer.value?.name == "ok.bin" }
                app.controller.accept()
                app.await("saved") { app.store.saved.size == 1 }
            }
        }
    }

    @Test
    fun `leaving the room mid-receive deletes the partial file and forgets the chat`() = runBlocking<Unit> {
        WebSide().use { web ->
            val link = web.newLink()
            App(link).use { a ->
                a.await("waiting") { a.controller.room.value.state == RoomState.WaitingForPeer }
                val b = App(link)
                a.awaitReady(); b.awaitReady()
                assertTrue((a.controller.room.value.state as RoomState.Ready).chat)

                val id = a.controller.sendChat("hello")
                b.await("the first message") { b.controller.chat.value.size == 1 }
                b.controller.sendChat("hi back")
                a.await("the conversation") { a.controller.chat.value.size == 2 && a.controller.chat.value.first { it.id == id }.status == ChatMessage.Status.DELIVERED }
                b.await("the conversation") { b.controller.chat.value.size == 2 }
                assertEquals(listOf("hello" to false, "hi back" to true), b.controller.chat.value.map { it.text to it.mine })
                assertFalse("hello" in a.controller.chat.value.toString())
                val tooLong = a.controller.sendChat("x".repeat(9000))
                a.await("the failure") { a.controller.chat.value.first { it.id == tooLong }.status == ChatMessage.Status.FAILED }

                a.controller.enqueue(MemorySource("big.bin", fileBytes("big", 5_000_000)))
                b.await("the offer") { b.controller.offer.value != null }
                b.controller.accept()
                b.await("bytes arriving") { (b.controller.room.value.incoming?.bytesDone ?: 0) > 100_000 }
                b.close()
                b.await("the partial file deleted") { b.store.discarded.get() == 1 }
                assertEquals(0, b.store.saved.size)
                assertEquals(emptyList(), b.controller.chat.value)
                assertEquals(null, b.controller.offer.value)
            }
        }
    }
}
