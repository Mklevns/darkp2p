package com.darkp2p.transport

import com.darkp2p.protocol.Json
import com.darkp2p.protocol.JsonValue
import com.darkp2p.protocol.LocalPolicy
import com.darkp2p.protocol.Notice
import com.darkp2p.protocol.RoomEvent
import com.darkp2p.protocol.RoomInput
import com.darkp2p.protocol.RoomLink
import com.darkp2p.protocol.RoomState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The same bytes test/interop/peer.mjs generates for (seed, size). */
internal fun fileBytes(seed: String, size: Int): ByteArray {
    val out = ByteArray(size)
    var block = 0
    var off = 0
    while (off < size) {
        val digest = MessageDigest.getInstance("SHA-256").digest("$seed|$block".toByteArray())
        System.arraycopy(digest, 0, out, off, minOf(32, size - off))
        block++
        off += 32
    }
    return out
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * The web side: a Node process running the real server.js and, on request,
 * real copies of the page (test/interop/peer.mjs).
 */
internal class WebSide(env: Map<String, String> = emptyMap()) : AutoCloseable {
    private val repo = File(System.getProperty("darkp2p.repo") ?: error("darkp2p.repo is not set"))
    private val process = ProcessBuilder("node", "test/interop/peer.mjs").directory(repo).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val writer = process.outputStream.bufferedWriter()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonValue.Obj>>()
    private val ready = CompletableFuture<Unit>()
    private val counter = AtomicInteger()
    val port: Int

    init {
        Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                val msg = runCatching { Json.parse(line) as JsonValue.Obj }.getOrNull() ?: return@forEachLine
                if (msg["event"] != null) ready.complete(Unit)
                msg["req"]?.safeIntegerOrNull?.let { pending.remove(it)?.complete(msg) }
            }
            pending.values.forEach { it.completeExceptionally(IllegalStateException("the web side exited")) }
        }.apply { isDaemon = true }.start()
        ready.get(20, TimeUnit.SECONDS)
        port = call("start", "env" to JsonValue.Obj(env.mapValues { Json.str(it.value) })).int("port")
    }

    fun call(cmd: String, vararg fields: Pair<String, JsonValue>, timeoutS: Long = 60): JsonValue.Obj {
        val req = counter.incrementAndGet().toLong()
        val future = CompletableFuture<JsonValue.Obj>()
        pending[req] = future
        val msg = Json.obj("req" to Json.num(req), "cmd" to Json.str(cmd), *fields)
        synchronized(writer) {
            writer.write(Json.stringify(msg))
            writer.newLine()
            writer.flush()
        }
        val reply = future.get(timeoutS, TimeUnit.SECONDS)
        check(reply["ok"] == JsonValue.Bool(true)) { "$cmd failed: ${reply["error"]?.stringOrNull}" }
        return reply
    }

    val origin get() = "http://127.0.0.1:$port"

    /** A new room on this server. */
    fun newLink(): RoomLink = RoomLink.generate(origin, SecureRandom())

    /** [browser]: "node" runs the page's script in Node; "chromium" opens it in a real headless Chromium. */
    fun page(id: String, link: RoomLink, forceWsRelay: Boolean = true, browser: String = "node") = WebPage(this, id).also {
        call("page", "id" to Json.str(id), "hash" to Json.str("#${link.fragment}"), "forceWsRelay" to JsonValue.Bool(forceWsRelay), "browser" to Json.str(browser))
    }

    override fun close() {
        runCatching { writer.close() } // stdin EOF: the web side stops its server and exits
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}

internal fun JsonValue.Obj.int(key: String): Int = fields.getValue(key).safeIntegerOrNull!!.toInt()
internal fun JsonValue.Obj.str(key: String): String = (fields.getValue(key) as JsonValue.Str).value
internal fun JsonValue.Obj.strings(key: String): List<String> = (fields.getValue(key) as JsonValue.Arr).items.map { (it as JsonValue.Str).value }

internal class WebPage(private val web: WebSide, private val id: String) {
    private val me = "id" to Json.str(id)

    fun waitLog(re: String, timeoutMs: Long = 10_000) = web.call("waitLog", me, "re" to Json.str(re), "timeout" to Json.num(timeoutMs))

    fun waitLabel(re: String, timeoutMs: Long = 10_000) = web.call("waitLabel", me, "re" to Json.str(re), "timeout" to Json.num(timeoutMs))

    fun waitReady() = waitLabel("^DROP FILE HERE OR CLICK TO SELECT \\(WS RELAY\\)$")

    fun send(name: String, seed: String, size: Int) = web.call("send", me, "name" to Json.str(name), "seed" to Json.str(seed), "size" to Json.num(size.toLong()))

    fun accept() = web.call("accept", me, "timeout" to Json.num(15_000))

    fun decline() = web.call("decline", me, "timeout" to Json.num(15_000))

    /** SHA-256 and size of the page's download number [index] (waits for it). */
    fun download(index: Int): Pair<String, Int> = web.call("download", me, "index" to Json.num(index.toLong()), "timeout" to Json.num(60_000)).let { it.str("sha256") to it.int("size") }

    fun state() = web.call("state", me)

    fun errors(): List<String> = state().strings("errors")

    fun logs(): List<String> = state().strings("logs")

    fun setForceWsRelay(checked: Boolean) = web.call("setForceWsRelay", me, "checked" to JsonValue.Bool(checked))

    fun clickDropzone() = web.call("clickDropzone", me)

    fun close() = web.call("closePage", me)
}

/** Files in memory: one being sent, any number received. */
internal class MemoryIo : TransferIo {
    @Volatile var source = ByteArray(0)
    private val partial = ConcurrentHashMap<Int, ByteArrayOutputStream>()
    val committed = CopyOnWriteArrayList<ByteArray>()
    val discarded = AtomicInteger()

    override suspend fun read(transfer: Int, offset: Long, length: Int): ByteArray {
        val from = minOf(offset.toInt(), source.size)
        return source.copyOfRange(from, minOf(source.size, from + length))
    }

    override suspend fun write(transfer: Int, bytes: ByteArray) {
        partial.getOrPut(transfer) { ByteArrayOutputStream() }.write(bytes)
    }

    override suspend fun commit(transfer: Int) {
        committed += partial.remove(transfer)?.toByteArray() ?: ByteArray(0)
    }

    override suspend fun discard(transfer: Int) {
        if (partial.remove(transfer) != null) discarded.incrementAndGet()
    }
}

/** The app side: a [RoomSession] with files in memory, driven as the app's service would drive it. */
internal class AppPeer(
    link: RoomLink,
    client: OkHttpClient = HttpClients.create(NetworkProfile.Clearnet),
    policy: LocalPolicy = LocalPolicy(forceWsRelay = true),
    token: String = "",
    private val autoAccept: Boolean = true,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val io = MemoryIo()
    val notices = CopyOnWriteArrayList<RoomEvent>()
    val chat = CopyOnWriteArrayList<ChatEvent>()
    val states = CopyOnWriteArrayList<RoomState>()
    val session = RoomSession(link, client, io, scope, policy, token)

    init {
        scope.launch {
            session.notices.collect {
                notices += it
                if (it.notice == Notice.INCOMING_OFFER && autoAccept) session.post(RoomInput.AcceptIncoming)
            }
        }
        scope.launch { session.chat.collect { chat += it } }
        scope.launch { session.snapshot.collect { states += it.state } }
        session.start()
    }

    val state: RoomState get() = session.snapshot.value.state

    suspend fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what\n  state: $state\n  notices: ${notices.map { it.notice }}")
        }
    }

    suspend fun awaitReady(timeoutMs: Long = 15_000): RoomState.Ready {
        await("a verified session", timeoutMs) { state is RoomState.Ready }
        return state as RoomState.Ready
    }

    suspend fun awaitNotice(notice: Notice, count: Int = 1, timeoutMs: Long = 30_000) =
        await("$count x $notice", timeoutMs) { notices.count { it.notice == notice } >= count }

    fun has(notice: Notice) = notices.any { it.notice == notice }

    fun send(name: String, bytes: ByteArray, mime: String = "application/octet-stream") {
        io.source = bytes
        session.post(RoomInput.SendFile(name, bytes.size.toLong(), mime))
    }

    override fun close() {
        session.close()
        scope.cancel()
    }
}
