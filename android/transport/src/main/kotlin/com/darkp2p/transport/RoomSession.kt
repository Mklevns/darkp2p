package com.darkp2p.transport

import com.darkp2p.protocol.ChannelKind
import com.darkp2p.protocol.ChannelRef
import com.darkp2p.protocol.ChatFailure
import com.darkp2p.protocol.CloseCause
import com.darkp2p.protocol.LocalPolicy
import com.darkp2p.protocol.RoomEffect
import com.darkp2p.protocol.RoomEvent
import com.darkp2p.protocol.RoomInput
import com.darkp2p.protocol.RoomLink
import com.darkp2p.protocol.RoomMachine
import com.darkp2p.protocol.RoomState
import com.darkp2p.protocol.ServerMessage
import com.darkp2p.protocol.TimerId
import com.darkp2p.protocol.TransferProgress
import com.darkp2p.protocol.WireConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom

/** What the room shows besides its [RoomState]. */
public data class RoomSnapshot(
    val state: RoomState,
    val canSend: Boolean,
    val outgoing: TransferProgress?,
    val incoming: TransferProgress?,
    val incomingMime: String?,
)

public sealed interface ChatEvent {
    /** A message from the peer. */
    public class Received(public val text: String) : ChatEvent {
        override fun toString(): String = "Received(***)"
    }

    public data class Delivered(val localId: Long) : ChatEvent
    public data class Failed(val localId: Long, val reason: ChatFailure) : ChatEvent
}

/**
 * A live room: [RoomMachine] connected to a real signaling WebSocket, timers
 * and file access.
 *
 * Everything that happens (socket callbacks, timers, I/O results, user
 * actions) becomes a [RoomInput] in one queue, handled by one coroutine. The
 * machine's effects are carried out in the order it returns them. This is the
 * only code that touches the machine.
 *
 * Phase 2: the WebSocket relay is the only transport, so the machine is told
 * WebRTC is unavailable whatever [policy] says.
 *
 * @param beforeJoin runs after the socket opens and before the room is joined
 *   (the app waits here, briefly, for a pending Pro token claim).
 */
public class RoomSession(
    private val link: RoomLink,
    private val client: OkHttpClient,
    private val io: TransferIo,
    parentScope: CoroutineScope,
    policy: LocalPolicy = LocalPolicy(),
    token: String = "",
    private val beforeJoin: suspend () -> Unit = {},
    random: SecureRandom = SecureRandom(),
) {
    private sealed interface Msg {
        class Input(val input: RoomInput) : Msg
        class Timer(val id: TimerId, val generation: Int) : Msg
        data object Start : Msg
    }

    private class OutFrame(val bytes: ByteArray, val notifyToken: Int?)

    private class Socket(val ws: WebSocket, val outbound: Channel<OutFrame>, val sender: Job)

    private val endpoint = ServerEndpoint(link.origin)
    private val machine = RoomMachine(
        link.roomId, link.key, clock = { System.nanoTime() / 1_000_000 }, random = random,
        policy = policy.copy(webRtcAvailable = false), token = token,
    )

    // One coroutine at a time: the machine is not thread-safe and does not need to be.
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.Default.limitedParallelism(1))

    // Bounded: when the machine falls behind, OkHttp's reader thread blocks in
    // post(), the TCP window closes and the relay slows the sender down.
    private val inbox = Channel<Msg>(INBOX_CAPACITY)
    private val ioTasks = Channel<suspend () -> RoomInput?>(Channel.UNLIMITED)

    private val sockets = HashMap<Int, Socket>()
    private val timers = HashMap<TimerId, Job>()
    private val timerGeneration = HashMap<TimerId, Int>()

    private val _snapshot = MutableStateFlow(snapshot())
    public val snapshot: StateFlow<RoomSnapshot> get() = _snapshot

    private val _notices = MutableSharedFlow<RoomEvent>(replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Things to tell the user, in order. */
    public val notices: SharedFlow<RoomEvent> get() = _notices

    private val _chat = MutableSharedFlow<ChatEvent>(replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)
    public val chat: SharedFlow<ChatEvent> get() = _chat

    /** Joins the room. Call once. */
    public fun start() {
        require(endpoint.allows(profileOf(client))) { "an onion server is reached through Tor only" }
        scope.launch {
            for (msg in inbox) {
                val effects = when (msg) {
                    Msg.Start -> machine.start()
                    is Msg.Input -> machine.handle(msg.input)
                    is Msg.Timer -> if (timerGeneration[msg.id] == msg.generation) machine.handle(RoomInput.TimerFired(msg.id)) else emptyList()
                }
                for (effect in effects) execute(effect)
                _snapshot.value = snapshot()
            }
        }
        scope.launch(Dispatchers.IO.limitedParallelism(1)) {
            for (task in ioTasks) task()?.let { inbox.send(Msg.Input(it)) }
        }
        scope.launch { inbox.send(Msg.Start) }
    }

    /** A user action. Safe from any thread. */
    public fun post(input: RoomInput) {
        scope.launch { inbox.send(Msg.Input(input)) }
    }

    /** Leaves the room and releases everything. */
    public fun close() {
        // By now `sockets` is only read: closing a WebSocket is safe from any thread.
        for (socket in sockets.values.toList()) socket.ws.close(1000, null)
        scope.cancel()
        inbox.close()
        ioTasks.close()
    }

    private fun snapshot() = RoomSnapshot(machine.state, machine.canSend, machine.outgoing, machine.incoming, machine.incomingMime)

    private suspend fun execute(effect: RoomEffect) {
        when (effect) {
            is RoomEffect.OpenSignaling -> openSignaling(effect.socket)
            is RoomEffect.CloseSignaling -> sockets.remove(effect.socket)?.let {
                it.sender.cancel()
                it.ws.close(1000, null)
            }
            is RoomEffect.SendSignal -> sockets[effect.socket]?.ws?.send(effect.msg.toJson())
            is RoomEffect.SendFrame ->
                if (effect.channel.kind == ChannelKind.WS_RELAY) {
                    sockets[effect.channel.id]?.outbound?.send(OutFrame(effect.bytes, effect.notifyToken))
                }
            is RoomEffect.ArmTimer -> {
                timers.remove(effect.id)?.cancel()
                val generation = (timerGeneration[effect.id] ?: 0) + 1
                timerGeneration[effect.id] = generation
                timers[effect.id] = scope.launch {
                    delay(effect.delayMs)
                    inbox.send(Msg.Timer(effect.id, generation))
                }
            }
            is RoomEffect.CancelTimer -> {
                timers.remove(effect.id)?.cancel()
                // A timer that already fired into the queue must not count either.
                timerGeneration[effect.id] = (timerGeneration[effect.id] ?: 0) + 1
            }
            is RoomEffect.ReadChunk -> ioTasks.send {
                try {
                    RoomInput.ChunkRead(effect.transfer, io.read(effect.transfer, effect.offset, effect.length))
                } catch (e: Exception) {
                    RoomInput.ChunkReadFailed(effect.transfer)
                }
            }
            is RoomEffect.WriteChunk -> ioTasks.send {
                try {
                    io.write(effect.transfer, effect.bytes)
                    RoomInput.ChunkWritten(effect.transfer)
                } catch (e: Exception) {
                    RoomInput.SinkFailed(effect.transfer)
                }
            }
            is RoomEffect.CommitSink -> ioTasks.send {
                try {
                    io.commit(effect.transfer)
                    RoomInput.SinkCommitted(effect.transfer)
                } catch (e: Exception) {
                    RoomInput.SinkFailed(effect.transfer)
                }
            }
            is RoomEffect.DiscardSink -> ioTasks.send {
                runCatching { io.discard(effect.transfer) }
                null
            }
            is RoomEffect.Notify -> _notices.tryEmit(effect.event)
            is RoomEffect.ChatReceived -> _chat.emit(ChatEvent.Received(effect.text))
            is RoomEffect.ChatDelivered -> _chat.emit(ChatEvent.Delivered(effect.localId))
            is RoomEffect.ChatFailed -> _chat.emit(ChatEvent.Failed(effect.localId, effect.reason))
            // WebRTC arrives in Phase 3. The machine was told it is unavailable, so none of these occur.
            is RoomEffect.StartWebRtc, is RoomEffect.ApplyRemoteSdp, is RoomEffect.AddRemoteCandidate, is RoomEffect.CloseWebRtc -> Unit
        }
    }

    private fun openSignaling(id: Int) {
        val request = Request.Builder()
            .url(endpoint.webSocketUrl)
            // The server accepts the upgrade only from its own origin.
            .header("Origin", endpoint.origin)
            .build()
        val ws = client.newWebSocket(request, Listener(id))
        val outbound = Channel<OutFrame>(Channel.UNLIMITED)
        // Frames leave in order, and only while OkHttp's queue is short: above
        // 16 MiB it would close the socket, and a long queue hides the relay's
        // backpressure from the machine.
        val sender = scope.launch {
            for (frame in outbound) {
                while (ws.queueSize() > WireConstants.HIGH_WATER) delay(SEND_POLL_MS)
                if (!ws.send(frame.bytes.toByteString())) break
                frame.notifyToken?.let { inbox.send(Msg.Input(RoomInput.FrameSent(it))) }
            }
        }
        sockets[id] = Socket(ws, outbound, sender)
    }

    // Called on OkHttp's threads. Blocking there is the backpressure (see `inbox`).
    private fun postBlocking(input: RoomInput) {
        inbox.trySendBlocking(Msg.Input(input))
    }

    private inner class Listener(private val id: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            scope.launch {
                beforeJoin()
                inbox.send(Msg.Input(RoomInput.SignalingOpened(id)))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            ServerMessage.parse(text)?.let { postBlocking(RoomInput.Server(id, it)) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            postBlocking(RoomInput.Frame(ChannelRef(ChannelKind.WS_RELAY, id), bytes.toByteArray()))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            postBlocking(RoomInput.SignalingClosed(id, CloseCause.Closed(code)))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // A response here is the refused upgrade: 403 wrong Origin, 429 rate limit.
            val cause = response?.code?.let { CloseCause.HttpRejected(it) } ?: CloseCause.NetworkError
            response?.close()
            postBlocking(RoomInput.SignalingClosed(id, cause))
        }
    }

    private companion object {
        const val INBOX_CAPACITY = 1024
        const val SEND_POLL_MS = 20L

        fun profileOf(client: OkHttpClient): NetworkProfile =
            if (client.dns === TorDns) NetworkProfile.Tor(java.net.InetSocketAddress.createUnresolved("proxy", 0)) else NetworkProfile.Clearnet
    }
}
