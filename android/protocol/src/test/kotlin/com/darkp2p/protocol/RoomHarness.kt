package com.darkp2p.protocol

import java.security.SecureRandom
import java.util.Random

/** Deterministic stand-in for SecureRandom: tests must be repeatable. */
internal class SeededRandom(seed: Long) : SecureRandom() {
    private val source = Random(seed)

    override fun nextBytes(bytes: ByteArray) = source.nextBytes(bytes)

    override fun nextDouble(): Double = source.nextDouble()
}

/**
 * The parts of server.js a room touches, with the same rules: two peers per
 * room, `ready` on pairing, sanitized forwarding, the relay switch, the room's
 * relay budget counted on every binary frame, `peer-left` on leaving.
 */
internal class FakeServer(var cap: Long = 50L * 1024 * 1024, val minRemaining: Long = 4096) {
    class Conn(val peer: Peer, val socket: Int) {
        var joined = false
        var pro = false
    }

    val conns = ArrayList<Conn>()
    var relayActive = false
    var relayBytes = 0L
    var limitNotified = false
    var relayFull = false // MAX_RELAY_ROOMS reached elsewhere
    var rejectUpgrades: CloseCause? = null
    var turn = false
    var upgrades = 0
    var fallbackRequests = 0
    val candidates = ArrayList<String>()
    var droppedOverCap = 0

    private fun room() = conns.filter { it.joined }

    private fun other(c: Conn) = room().firstOrNull { it !== c }

    private fun send(c: Conn, json: String) {
        c.peer.post(RoomInput.Server(c.socket, ServerMessage.parse(json) ?: error("server sent unparsable $json")))
    }

    fun connect(peer: Peer, socket: Int) {
        upgrades++
        rejectUpgrades?.let {
            peer.post(RoomInput.SignalingClosed(socket, it))
            return
        }
        conns += Conn(peer, socket)
        peer.post(RoomInput.SignalingOpened(socket))
    }

    fun find(peer: Peer, socket: Int) = conns.firstOrNull { it.peer === peer && it.socket == socket }

    /** The client closed the socket, or the server drops it ([notifyClient]). */
    fun disconnect(c: Conn, notifyClient: Boolean) {
        if (!conns.remove(c)) return
        if (c.joined) {
            relayActive = false
            other(c)?.let { send(it, """{"type":"peer-left"}""") }
            if (room().isEmpty()) {
                relayBytes = 0
                limitNotified = false
            }
        }
        if (notifyClient) c.peer.post(RoomInput.SignalingClosed(c.socket, CloseCause.Closed(1006)))
    }

    fun onText(c: Conn, msg: ClientMessage) {
        if (msg is ClientMessage.Join) {
            if (c.joined) return
            if (room().size >= 2) {
                send(c, """{"type":"full"}""")
                return
            }
            c.joined = true
            c.pro = msg.token == "pro"
            val peers = room()
            if (peers.size != 2) return
            relayActive = false
            limitNotified = false
            val pro = peers.any { it.pro }
            val roomCap = if (pro) cap * 10 else cap
            val turnOn = pro && turn
            val ice = if (turnOn) {
                """[{"urls":"stun:turn.test:3478"},{"urls":["turn:turn.test:3478?transport=udp","turn:turn.test:3478?transport=tcp"],"username":"u","credential":"c"}]"""
            } else {
                """[{"urls":"stun:turn.test:3478"}]"""
            }
            for (p in peers) {
                send(
                    p,
                    """{"type":"ready","initiator":${p === c},"iceServers":$ice,"turnEnabled":$turnOn,"relayEnabled":$turnOn,""" +
                        """"wsRelayCap":$roomCap,"wsRelayRemaining":${maxOf(0, roomCap - relayBytes)}}""",
                )
            }
            return
        }
        val peer = other(c) ?: return
        if (room().size != 2) return
        val pro = room().any { it.pro }
        val roomCap = if (pro) cap * 10 else cap
        when (msg) {
            is ClientMessage.Candidate -> candidates += msg.candidate
            ClientMessage.WsFallback -> {
                fallbackRequests++
                if (!relayActive) {
                    if (roomCap - relayBytes < minRemaining) {
                        send(peer, msg.toJson())
                        notifyLimit()
                        return
                    }
                    if (relayFull) {
                        send(c, """{"type":"error","reason":"relay-busy"}""")
                        return
                    }
                    relayActive = true
                }
            }
            else -> Unit
        }
        send(peer, msg.toJson())
    }

    private fun notifyLimit() {
        if (limitNotified) return
        limitNotified = true
        for (p in room()) send(p, """{"type":"error","reason":"relay-limit-exceeded"}""")
    }

    fun onBinary(c: Conn, bytes: ByteArray) {
        val peer = other(c) ?: return
        if (!relayActive) return
        val roomCap = if (room().any { it.pro }) cap * 10 else cap
        if (relayBytes + bytes.size > roomCap) {
            droppedOverCap++
            notifyLimit()
            return
        }
        relayBytes += bytes.size
        peer.peer.post(RoomInput.Frame(ChannelRef(ChannelKind.WS_RELAY, peer.socket), bytes))
    }

    /** RELAY_IDLE_MS passed without a relayed frame. */
    fun expireIdleRelay() {
        relayActive = false
        for (p in room()) send(p, """{"type":"error","reason":"relay-idle"}""")
    }
}

/** A world with one server, any number of peers, virtual time and one ordered event queue. */
internal class World(seed: Long = 1) {
    var now = 1_000_000L
    val server = FakeServer()
    val peers = ArrayList<Peer>()
    private val queue = ArrayDeque<Pair<Peer, RoomInput>>()
    private var seeds = seed

    /** Fake WebRTC: whether a started session ever opens its DataChannel. */
    var webRtcConnects = true

    fun peer(name: String, key: ByteArray, policy: LocalPolicy = LocalPolicy(), token: String = ""): Peer {
        val random = SeededRandom(seeds++)
        val machine = RoomMachine("a1b2c3d4e5f60718293a4b5c6d7e8f90", RoomKey(key), { now }, random, policy, token, jitter = { 0.5 })
        return Peer(name, this, machine).also { peers += it }
    }

    fun post(peer: Peer, input: RoomInput) {
        queue.addLast(peer to input)
    }

    /** Runs until nothing is queued (timers do not fire by themselves), or for at most [limit] inputs. */
    fun run(limit: Int = Int.MAX_VALUE) {
        var steps = 0
        while (queue.isNotEmpty() && steps < limit) {
            val (peer, input) = queue.removeFirst()
            peer.apply(peer.machine.handle(input))
            check(++steps < 2_000_000) { "the room does not settle" }
        }
    }

    /** Advances virtual time, firing timers as they come due. */
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            run()
            val next = peers.flatMap { p -> p.timers.map { Triple(it.value, p, it.key) } }.filter { it.first <= end }.minByOrNull { it.first } ?: break
            now = maxOf(now, next.first)
            next.second.timers.remove(next.third)
            post(next.second, RoomInput.TimerFired(next.third))
        }
        now = end
        run()
    }

    /** The fake DataChannel between the two current WebRTC sessions, if it is up. */
    var link: Pair<Pair<Peer, Int>, Pair<Peer, Int>>? = null
}

internal class Peer(val name: String, private val world: World, val machine: RoomMachine) {
    val timers = HashMap<TimerId, Long>()
    val notices = ArrayList<RoomEvent>()
    val effects = ArrayList<RoomEffect>()
    var autoAccept = true

    /** The file being sent. */
    var source = ByteArray(0)

    /** Incoming file contents by transfer token. */
    val received = HashMap<Int, java.io.ByteArrayOutputStream>()
    val committed = ArrayList<ByteArray>()
    var discarded = 0

    /** Fails the next write, as a full disk would. */
    var failWrites = false

    /** Leaves a write unanswered, as a slow disk would, until the test answers it. */
    var holdWrites = false
    var heldWrite: Int? = null

    var pc: Int? = null
    private var initiator = false

    fun post(input: RoomInput) = world.post(this, input)

    fun start() = apply(machine.start())

    fun send(name: String, bytes: ByteArray, mime: String = "application/octet-stream") {
        source = bytes
        post(RoomInput.SendFile(name, bytes.size.toLong(), mime))
    }

    val state get() = machine.state

    fun has(notice: Notice) = notices.any { it.notice == notice }

    inline fun <reified T : RoomEffect> effectsOf() = effects.filterIsInstance<T>()

    fun apply(list: List<RoomEffect>) {
        effects += list
        for (e in list) when (e) {
            is RoomEffect.OpenSignaling -> world.server.connect(this, e.socket)
            is RoomEffect.CloseSignaling -> world.server.find(this, e.socket)?.let { world.server.disconnect(it, notifyClient = false) }
            is RoomEffect.SendSignal -> world.server.find(this, e.socket)?.let { world.server.onText(it, e.msg) }
            is RoomEffect.SendFrame -> {
                if (e.channel.kind == ChannelKind.WS_RELAY) {
                    world.server.find(this, e.channel.id)?.let { world.server.onBinary(it, e.bytes) }
                } else {
                    val link = world.link
                    val me = this to e.channel.id
                    val other = when (me) {
                        link?.first -> link.second
                        link?.second -> link.first
                        else -> null
                    }
                    other?.let { (peer, pc) -> peer.post(RoomInput.Frame(ChannelRef(ChannelKind.DATA_CHANNEL, pc), e.bytes)) }
                }
                e.notifyToken?.let { post(RoomInput.FrameSent(it)) }
            }
            is RoomEffect.StartWebRtc -> {
                pc = e.pc
                initiator = e.initiator
                // A host candidate and, with TURN, a relay candidate, as libwebrtc would report them.
                post(RoomInput.LocalCandidate(e.pc, "candidate:1 1 udp 2122260223 192.168.1.${10 + world.peers.indexOf(this)} 5000 typ host", "0", 0))
                if (e.iceServers.any { s -> s.urls.any { it.startsWith("turn:") } }) {
                    post(RoomInput.LocalCandidate(e.pc, "candidate:3 1 udp 41885439 198.51.100.9 49200 typ relay raddr 203.0.113.7 rport 61000", "0", 0))
                }
                if (e.initiator) post(RoomInput.LocalSdp(e.pc, SdpKind.OFFER, "v=0\r\na=candidate:1 1 udp 1 192.168.1.2 1 typ host\r\ns=offer"))
            }
            is RoomEffect.ApplyRemoteSdp -> {
                if (e.kind == SdpKind.OFFER) {
                    post(RoomInput.LocalSdp(e.pc, SdpKind.ANSWER, "v=0\r\ns=answer"))
                } else if (world.webRtcConnects) {
                    // The answer reached the initiator: the channel opens on both sides.
                    val other = world.peers.first { it !== this && it.pc != null }
                    world.link = (this to e.pc) to (other to other.pc!!)
                    for ((p, id) in listOf(this to e.pc, other to other.pc!!)) {
                        p.post(RoomInput.PeerConnectionState(id, PcState.CONNECTED))
                        p.post(RoomInput.DataChannelOpened(id))
                    }
                }
            }
            is RoomEffect.AddRemoteCandidate -> Unit
            is RoomEffect.CloseWebRtc -> {
                val link = world.link
                if (link != null && (link.first == this to e.pc || link.second == this to e.pc)) {
                    val (other, id) = if (link.first.first === this) link.second else link.first
                    world.link = null
                    other.post(RoomInput.DataChannelClosed(id))
                }
                if (pc == e.pc) pc = null
            }
            is RoomEffect.ArmTimer -> timers[e.id] = world.now + e.delayMs
            is RoomEffect.CancelTimer -> timers.remove(e.id)
            is RoomEffect.ReadChunk -> {
                // Like a real file: a read past the end comes back short.
                val from = minOf(e.offset.toInt(), source.size)
                post(RoomInput.ChunkRead(e.transfer, source.copyOfRange(from, minOf(source.size, from + e.length))))
            }
            is RoomEffect.WriteChunk -> {
                if (failWrites) {
                    post(RoomInput.SinkFailed(e.transfer))
                } else if (holdWrites) {
                    heldWrite = e.transfer
                } else {
                    received.getOrPut(e.transfer) { java.io.ByteArrayOutputStream() }.write(e.bytes)
                    post(RoomInput.ChunkWritten(e.transfer))
                }
            }
            is RoomEffect.CommitSink -> {
                committed += (received.remove(e.transfer)?.toByteArray() ?: ByteArray(0))
                post(RoomInput.SinkCommitted(e.transfer))
            }
            is RoomEffect.DiscardSink -> {
                received.remove(e.transfer)
                discarded++
            }
            is RoomEffect.Notify -> {
                notices += e.event
                if (e.event.notice == Notice.INCOMING_OFFER && autoAccept) post(RoomInput.AcceptIncoming)
            }
        }
    }
}
