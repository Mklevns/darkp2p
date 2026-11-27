package com.darkp2p.service

import com.darkp2p.protocol.ChatFailure
import com.darkp2p.protocol.DeclineReason
import com.darkp2p.protocol.LocalPolicy
import com.darkp2p.protocol.Notice
import com.darkp2p.protocol.RoomEvent
import com.darkp2p.protocol.RoomInput
import com.darkp2p.protocol.RoomLink
import com.darkp2p.protocol.RoomState
import com.darkp2p.transport.ChatEvent
import com.darkp2p.transport.RoomSession
import com.darkp2p.transport.RoomSnapshot
import com.darkp2p.transport.TransferIo
import com.darkp2p.transport.WebRtcFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** A file the peer offers. Names and types are already made safe to show. */
public data class IncomingOffer(val name: String, val size: Long, val mime: String)

public data class ChatMessage(val id: Long, val mine: Boolean, val text: String, val status: Status) {
    public enum class Status { SENDING, DELIVERED, FAILED, RECEIVED }

    override fun toString(): String = "ChatMessage($id, mine=$mine, $status)"
}

/** What the foreground service needs to know to hold or release its locks and to stop when idle. */
public data class RoomActivity(
    /** A file is being sent or received (offered files waiting for an answer do not count). */
    val transferActive: Boolean,
    /** When the last byte of a transfer moved (the clock passed to the controller). */
    val lastProgressAt: Long,
    /** When the room last had a transfer, an open offer or a handshake in progress. */
    val lastBusyAt: Long,
)

/**
 * One joined room, as the app's screens and its foreground service use it: the
 * live session plus what belongs to a room but not to the protocol: the queue
 * of files to send, where an incoming file goes, the chat history.
 *
 * Chat history and file names exist only in this object's memory and are gone
 * when the room is left.
 */
public class RoomController(
    link: RoomLink,
    client: OkHttpClient,
    private val store: FileStore,
    parentScope: CoroutineScope,
    policy: LocalPolicy = LocalPolicy(),
    token: String = "",
    webRtc: WebRtcFactory? = null,
    beforeJoin: suspend () -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    // One coroutine at a time, in the order launched: files are queued in the order they were given.
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.Default.limitedParallelism(1))
    private val lock = Mutex()

    // Guarded by `lock`.
    private var sending: FileSource? = null
    private var sink: FileSink? = null
    private var sinkCommitted = false
    private var accepting = false

    private val io = object : TransferIo {
        override suspend fun read(transfer: Int, offset: Long, length: Int): ByteArray =
            (lock.withLock { sending } ?: throw IOException("no file is being sent")).read(offset, length)

        override suspend fun write(transfer: Int, bytes: ByteArray) {
            (lock.withLock { sink } ?: throw IOException("no destination is open")).write(bytes)
        }

        override suspend fun commit(transfer: Int) {
            val s = lock.withLock { sink } ?: throw IOException("no destination is open")
            val savedAs = s.commit()
            lock.withLock {
                sinkCommitted = true
                sink = null
            }
            _saved.tryEmit(savedAs)
        }

        override suspend fun discard(transfer: Int) = discardSink()
    }

    private val session = RoomSession(link, client, io, scope, policy, token, beforeJoin, webRtc = webRtc)

    public val room: StateFlow<RoomSnapshot> get() = session.snapshot
    public val notices: SharedFlow<RoomEvent> get() = session.notices

    private val _queue = MutableStateFlow<List<String>>(emptyList())

    /** Names of the files waiting to be sent, first to go first. */
    public val queue: StateFlow<List<String>> get() = _queue
    private val waiting = ArrayDeque<FileSource>() // guarded by `lock`

    private val _offer = MutableStateFlow<IncomingOffer?>(null)

    /** The file the peer is offering right now, until it is accepted or declined. */
    public val offer: StateFlow<IncomingOffer?> get() = _offer

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    public val chat: StateFlow<List<ChatMessage>> get() = _chat

    private val _saved = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** The name each received file was saved under. */
    public val saved: SharedFlow<String> get() = _saved

    private val _activity = MutableStateFlow(RoomActivity(false, clock(), clock()))
    public val activity: StateFlow<RoomActivity> get() = _activity

    private val chatIds = AtomicLong()

    public fun start() {
        scope.launch { session.snapshot.collect(::onSnapshot) }
        scope.launch { session.chat.collect(::onChat) }
        session.start()
    }

    private var lastOut = -1L
    private var lastIn = -1L

    private suspend fun onSnapshot(s: RoomSnapshot) {
        val now = clock()
        val incoming = s.incoming
        val outgoing = s.outgoing

        // The offer on screen follows the machine.
        val offered = incoming?.takeIf { it.phase == "offered" }
        if (offered == null) {
            _offer.value = null
        } else if (_offer.value == null && !lock.withLock { accepting || sink != null }) {
            val shown = IncomingOffer(FileNames.safe(offered.name), offered.size, FileNames.safeMime(s.incomingMime))
            // No room for it: say so to the sender now instead of failing halfway.
            val free = runCatching { store.freeSpace(SaveTarget.Downloads) }.getOrNull()
            if (free != null && free < offered.size + FREE_SPACE_MARGIN) session.post(RoomInput.DeclineIncoming(DeclineReason.SIZE)) else _offer.value = shown
        }
        // The receive ended without a saved file: whatever was written goes.
        if (incoming == null) discardSink()

        // The send ended, however it ended: release its file and start the next.
        if (outgoing == null) {
            lock.withLock { sending }?.let { done ->
                lock.withLock { sending = null }
                runCatching { done.close() }
            }
        }
        pump(s)

        val moving = (outgoing?.phase == "sending" || outgoing?.phase == "awaiting-complete") || (incoming != null && incoming.phase != "offered")
        val progressed = (outgoing?.bytesDone ?: -1) != lastOut || (incoming?.bytesDone ?: -1) != lastIn
        lastOut = outgoing?.bytesDone ?: -1
        lastIn = incoming?.bytesDone ?: -1
        val busy = outgoing != null || incoming != null || s.state is RoomState.Negotiating || s.state is RoomState.Verifying
        _activity.update { RoomActivity(moving, if (moving && progressed) now else it.lastProgressAt, if (busy) now else it.lastBusyAt) }
    }

    // Offers the next queued file as soon as the room can take it.
    private suspend fun pump(s: RoomSnapshot) {
        if (!s.canSend || s.outgoing != null) return
        val next = lock.withLock {
            if (sending != null || waiting.isEmpty()) return
            waiting.removeFirst().also { sending = it }
        }
        publishQueue()
        session.post(RoomInput.SendFile(next.name, next.size, next.mime))
    }

    private suspend fun publishQueue() {
        _queue.value = lock.withLock { waiting.map { it.name } }
    }

    /** Queues a file. It is offered when the room is ready and the files before it are done. */
    public fun enqueue(source: FileSource) {
        scope.launch {
            lock.withLock { waiting.addLast(source) }
            publishQueue()
            pump(session.snapshot.value)
        }
    }

    /** Cancels the file being sent, or offered. The queue carries on with the next one. */
    public fun cancelSend(): Unit = session.post(RoomInput.CancelSend)

    /** Drops every file that has not been offered yet. */
    public fun clearQueue() {
        scope.launch {
            val dropped = lock.withLock { waiting.toList().also { waiting.clear() } }
            dropped.forEach { runCatching { it.close() } }
            publishQueue()
        }
    }

    /** Accepts the offered file into [target]. The destination is opened first; if that fails, the file is declined. */
    public fun accept(target: SaveTarget = SaveTarget.Downloads) {
        scope.launch {
            val offered = _offer.value ?: return@launch
            lock.withLock {
                if (accepting || sink != null) return@launch
                accepting = true
            }
            _offer.value = null
            val created = runCatching { store.create(target, offered.name, offered.mime, offered.size) }.getOrNull()
            lock.withLock {
                accepting = false
                if (created != null) {
                    sink = created
                    sinkCommitted = false
                }
            }
            if (created == null) {
                session.post(RoomInput.DeclineIncoming(DeclineReason.USER))
                return@launch
            }
            session.post(RoomInput.AcceptIncoming)
            // The sender may have given up while the destination was being opened.
            if (session.snapshot.value.incoming == null) discardSink()
        }
    }

    public fun decline(): Unit = session.post(RoomInput.DeclineIncoming(DeclineReason.USER))

    public fun cancelReceive(): Unit = session.post(RoomInput.CancelReceive)

    private suspend fun discardSink() {
        val s = lock.withLock {
            if (accepting) return
            sink.also { sink = null }
        } ?: return
        runCatching { s.discard() }
    }

    public fun setPolicy(policy: LocalPolicy): Unit = session.post(RoomInput.SetPolicy(policy))

    public fun setToken(token: String): Unit = session.post(RoomInput.SetToken(token))

    public fun resumeRelay(): Unit = session.post(RoomInput.ResumeRelay)

    public fun reconnect(): Unit = session.post(RoomInput.Reconnect)

    /** Sends a chat message; its status in [chat] follows. Only possible while the room state says chat is on. */
    public fun sendChat(text: String): Long {
        val id = chatIds.incrementAndGet()
        _chat.update { (it + ChatMessage(id, mine = true, text = text, status = ChatMessage.Status.SENDING)).takeLast(MAX_CHAT_HISTORY) }
        session.post(RoomInput.SendChat(id, text))
        return id
    }

    private fun onChat(event: ChatEvent) {
        when (event) {
            is ChatEvent.Received -> _chat.update {
                (it + ChatMessage(chatIds.incrementAndGet(), mine = false, text = event.text, status = ChatMessage.Status.RECEIVED)).takeLast(MAX_CHAT_HISTORY)
            }
            is ChatEvent.Delivered -> setChatStatus(event.localId, ChatMessage.Status.DELIVERED)
            is ChatEvent.Failed -> {
                setChatStatus(event.localId, ChatMessage.Status.FAILED)
                _chatFailures.tryEmit(event.localId to event.reason)
            }
        }
    }

    private val _chatFailures = MutableSharedFlow<Pair<Long, ChatFailure>>(extraBufferCapacity = 16)

    /** Why a message could not be sent or confirmed. */
    public val chatFailures: SharedFlow<Pair<Long, ChatFailure>> get() = _chatFailures

    private fun setChatStatus(id: Long, status: ChatMessage.Status) {
        _chat.update { list -> list.map { if (it.id == id && it.mine) it.copy(status = status) else it } }
    }

    /** Leaves the room: the session ends, open files are released, a partial file is deleted, the chat is forgotten. */
    public fun close() {
        session.close()
        val (source, queued, partial) = Triple(sending, waiting.toList(), sink)
        sending = null
        waiting.clear()
        sink = null
        runCatching { source?.close() }
        queued.forEach { runCatching { it.close() } }
        _chat.value = emptyList()
        _queue.value = emptyList()
        _offer.value = null
        // Deleting the partial file may take a moment; it must not be skipped because the room is gone.
        if (partial != null) CoroutineScope(Dispatchers.IO).launch { runCatching { partial.discard() } }
        scope.cancel()
    }

    public companion object {
        /** Chat lines kept in memory. */
        public const val MAX_CHAT_HISTORY: Int = 500
        private const val FREE_SPACE_MARGIN = 8L * 1024 * 1024

        /** Notices that mean "a file arrived" or "a peer is here": worth a notification when the app is not on screen. */
        public val ATTENTION: Set<Notice> = setOf(Notice.INCOMING_OFFER, Notice.SAVED, Notice.DELIVERED, Notice.KEY_MISMATCH, Notice.PEER_DETECTED)
    }
}

/** When the foreground service may hold its wake and Wi-Fi locks, and when it should stop. */
public object ServicePolicy {
    /** Locks are released this long after the last byte moved, even if a transfer is nominally still open. */
    public const val LOCK_LINGER_MS: Long = 5_000

    /** With no transfer, offer or handshake for this long, the room is left and the service stops. */
    public const val IDLE_STOP_MS: Long = 10 * 60 * 1000

    public fun holdLocks(a: RoomActivity, now: Long): Boolean = a.transferActive && now - a.lastProgressAt < LOCK_LINGER_MS

    public fun shouldStop(a: RoomActivity, now: Long): Boolean = !a.transferActive && now - a.lastBusyAt >= IDLE_STOP_MS
}
