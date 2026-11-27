package com.darkp2p.protocol

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoomMachineTest {
    private val key = ByteArray(32) { (it * 7).toByte() }
    private val relay = LocalPolicy(forceWsRelay = true)

    private fun bytes(n: Int, seed: Long = 42) = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun pair(a: LocalPolicy = LocalPolicy(), b: LocalPolicy = LocalPolicy(), setup: World.() -> Unit = {}): Triple<World, Peer, Peer> {
        val world = World()
        world.setup()
        val first = world.peer("a", key, a)
        val second = world.peer("b", key, b)
        first.start()
        world.run()
        second.start()
        world.run()
        return Triple(world, first, second)
    }

    private fun assertReady(peer: Peer, transport: Transport): RoomState.Ready {
        val state = assertIs<RoomState.Ready>(peer.state, "${peer.name}: ${peer.state}")
        assertEquals(transport, state.transport, peer.name)
        return state
    }

    // ---- Happy paths ----

    @Test
    fun `direct tunnel - handshake, then a file each way at once`() {
        val (world, a, b) = pair()
        assertReady(a, Transport.DIRECT)
        assertReady(b, Transport.DIRECT)
        assertTrue(a.has(Notice.KEY_VERIFIED) && b.has(Notice.KEY_VERIFIED))
        assertTrue(a.machine.canSend)

        val fromA = bytes(300 * 16_000 + 5, 1)
        val fromB = bytes(70_001, 2)
        a.send("a.bin", fromA)
        b.send("b.bin", fromB)
        world.run()
        assertContentEquals(fromA, b.committed.single())
        assertContentEquals(fromB, a.committed.single())
        assertTrue(a.has(Notice.DELIVERED) && b.has(Notice.DELIVERED))
        assertEquals(0, a.discarded + b.discarded)
        assertTrue(a.machine.canSend && a.machine.outgoing == null && a.machine.incoming == null)
        // The second peer joined last: it is the initiator, and the server never saw the SDP's candidates.
        assertTrue(a.effectsOf<RoomEffect.SendSignal>().none { (it.msg as? ClientMessage.Answer)?.sdp?.contains("a=candidate") == true })
        assertTrue(b.effectsOf<RoomEffect.SendSignal>().none { (it.msg as? ClientMessage.Offer)?.sdp?.contains("a=candidate") == true })
        // Host candidates were signaled: the address is exposed and says so.
        assertEquals(IpExposure.EXPOSED, assertReady(a, Transport.DIRECT).ip)
    }

    @Test
    fun `relay - every size around the chunk, ack and window boundaries, and the budget mirrors the server`() {
        val sizes = listOf(0, 1, 15_999, 16_000, 16_001, 64 * 16_000, 64 * 16_000 + 1, 256 * 16_000 + 1, 600 * 16_000 + 123)
        val (world, a, b) = pair(a = relay)
        assertReady(a, Transport.WS_RELAY)
        assertReady(b, Transport.WS_RELAY)
        assertEquals(IpExposure.MASKED, assertReady(a, Transport.WS_RELAY).ip)
        for ((i, size) in sizes.withIndex()) {
            val data = bytes(size, i.toLong())
            val (from, to) = if (i % 2 == 0) a to b else b to a
            from.send("f$i", data)
            world.run()
            assertContentEquals(data, to.committed.last(), "size $size")
            // Both clients count exactly what the server counted.
            val expected = world.server.cap - world.server.relayBytes
            assertEquals(expected, assertReady(a, Transport.WS_RELAY).relayRemaining, "a after $size")
            assertEquals(expected, assertReady(b, Transport.WS_RELAY).relayRemaining, "b after $size")
        }
        assertEquals(sizes.size, a.committed.size + b.committed.size)
        assertEquals(0, world.server.droppedOverCap)
    }

    @Test
    fun `the predicted relay cost of a file is at least what it really costs`() {
        for (size in listOf(0L, 1L, 16_000L, 1_000_000L)) {
            val (world, a, b) = pair(a = relay)
            val before = world.server.relayBytes
            a.send("f", bytes(size.toInt()))
            world.run()
            assertEquals(1, b.committed.size)
            val used = world.server.relayBytes - before
            assertTrue(used <= RelayBudget.wireBytes(size, WireConstants.CHUNK_SIZE), "size $size used $used")
        }
    }

    // ---- Sticky errors ----

    @Test
    fun `an altered key is a mismatch that fallbacks cannot overwrite, and a new pairing clears`() {
        val world = World()
        val a = world.peer("a", key)
        val b = world.peer("b", ByteArray(32) { 9 })
        a.start(); b.start(); world.run()
        assertEquals(RoomState.KeyMismatch, a.state)
        assertEquals(RoomState.KeyMismatch, b.state)
        assertFalse(a.machine.canSend)

        // Relay events and a forced fallback leave the mismatch in place.
        val socket = world.server.conns.first { it.peer === a }.socket
        for (reason in listOf("relay-busy", "relay-idle", "relay-limit-exceeded")) {
            a.post(RoomInput.Server(socket, ServerMessage.Error(reason)))
            world.run()
            assertEquals(RoomState.KeyMismatch, a.state, reason)
        }
        a.post(RoomInput.SetPolicy(relay)); world.run()
        assertEquals(RoomState.KeyMismatch, a.state)
        a.post(RoomInput.SendFile("x", 1, "")); world.run()
        assertEquals(null, a.machine.outgoing)
        world.advance(60_000)
        assertEquals(RoomState.KeyMismatch, a.state)

        // The peer leaves: standby. Its mismatch belonged to that pairing.
        world.server.disconnect(world.server.conns.first { it.peer === b }, notifyClient = false)
        world.run()
        assertEquals(RoomState.WaitingForPeer, a.state)
    }

    @Test
    fun `a legacy frame or a silent peer is an outdated peer`() {
        val (world, a, b) = pair(a = relay)
        val sa = world.server.conns.first { it.peer === a }.socket
        a.post(RoomInput.Frame(ChannelRef(ChannelKind.WS_RELAY, sa), byteArrayOf(0x11, 1, 2, 3)))
        world.run()
        assertEquals(RoomState.PeerOutdated, a.state)
        assertReady(b, Transport.WS_RELAY)

        // A peer that never sends a hello: outdated after 5 s, not before.
        val w = World()
        val solo = w.peer("solo", key, relay)
        solo.start(); w.run()
        val s = w.server.conns.single().socket
        solo.post(RoomInput.Server(s, ServerMessage.Ready(true, emptyList(), false, 1000000, 1000000))); w.run()
        assertIs<RoomState.Verifying>(solo.state)
        w.advance(4_999)
        assertIs<RoomState.Verifying>(solo.state)
        w.advance(2)
        assertEquals(RoomState.PeerOutdated, solo.state)
    }

    // ---- Relay states ----

    @Test
    fun `relay-busy is its own state and is retried every 30 seconds`() {
        val (world, a, b) = pair(a = relay) { server.relayFull = true }
        assertEquals(RoomState.RelayBusy, a.state)
        assertTrue(a.has(Notice.RELAY_BUSY))
        // The hello timer was cancelled: no "outdated peer" while the relay is full.
        world.advance(29_000)
        assertEquals(RoomState.RelayBusy, a.state)
        assertEquals(1, a.notices.count { it.notice == Notice.RELAY_BUSY })
        world.advance(2_000)
        assertEquals(RoomState.RelayBusy, a.state)
        assertEquals(1, a.notices.count { it.notice == Notice.RELAY_BUSY }) // said once

        world.server.relayFull = false
        world.advance(30_000)
        assertReady(a, Transport.WS_RELAY)
        assertReady(b, Transport.WS_RELAY)
        a.send("f", bytes(40_000)); world.run()
        assertEquals(1, b.committed.size)
    }

    @Test
    fun `an idle relay pauses and resumes on request`() {
        val (world, a, b) = pair(a = relay)
        a.send("f", bytes(5 * 16_000)); world.run()
        world.server.expireIdleRelay(); world.run()
        assertEquals(RoomState.RelayIdle, a.state)
        assertEquals(RoomState.RelayIdle, b.state)
        assertFalse(a.machine.canSend)
        world.advance(60_000)
        assertEquals(RoomState.RelayIdle, a.state) // idle is not an outdated peer

        b.post(RoomInput.ResumeRelay); world.run()
        assertReady(a, Transport.WS_RELAY)
        assertReady(b, Transport.WS_RELAY)
        b.send("g", bytes(1234)); world.run()
        assertEquals(1, a.committed.size)
    }

    @Test
    fun `a spent relay budget is terminal for the pairing and only a fresh pairing leaves it`() {
        val (world, a, b) = pair(a = relay) { server.cap = 100_000 }
        // Too large for what is left: refused before anything is sent.
        val before = world.server.relayBytes
        a.send("big", bytes(120_000)); world.run()
        assertTrue(a.has(Notice.DOES_NOT_FIT_RELAY))
        assertEquals(before, world.server.relayBytes)
        assertEquals(0, b.notices.count { it.notice == Notice.INCOMING_OFFER })

        // A file that fits uses most of the room.
        a.send("fits", bytes(90_000)); world.run()
        assertEquals(1, b.committed.size)
        val left = assertReady(a, Transport.WS_RELAY).relayRemaining!!
        assertTrue(left < 10_000)
        b.send("next", bytes(20_000)); world.run()
        assertTrue(b.has(Notice.DOES_NOT_FIT_RELAY))

        // The server reports the limit: both sides stop for good.
        for (c in world.server.conns) c.peer.post(RoomInput.Server(c.socket, ServerMessage.Error("relay-limit-exceeded")))
        world.run()
        assertIs<RoomState.RelayExhausted>(a.state)
        assertIs<RoomState.RelayExhausted>(b.state)
        val fallbacks = world.server.fallbackRequests
        val upgrades = world.server.upgrades
        world.advance(10 * 60_000)
        a.post(RoomInput.ResumeRelay); a.post(RoomInput.SendFile("x", 1, "")); world.run()
        assertIs<RoomState.RelayExhausted>(a.state)
        assertEquals(fallbacks, world.server.fallbackRequests) // no retry loop
        assertEquals(upgrades, world.server.upgrades) // no reconnect loop

        // "Force relay" off: a clean rejoin on a new socket, then direct WebRTC.
        val oldSocket = world.server.conns.first { it.peer === a }.socket
        a.post(RoomInput.SetPolicy(LocalPolicy())); world.advance(2_000)
        assertTrue(world.server.conns.first { it.peer === a }.socket != oldSocket)
        assertReady(a, Transport.DIRECT)
        assertReady(b, Transport.DIRECT)
        a.send("direct", bytes(300_000)); world.run()
        assertEquals(2, b.committed.size)
    }

    @Test
    fun `a room that rejoins with its budget already spent goes straight to exhausted`() {
        val w = World()
        val solo = w.peer("solo", key, relay)
        solo.start(); w.run()
        val s = w.server.conns.single().socket
        solo.post(RoomInput.Server(s, ServerMessage.Ready(true, emptyList(), false, 50_000_000, 4095))); w.run()
        assertIs<RoomState.RelayExhausted>(solo.state)
        // It asked for the relay once (so the peer learns of it) and sent no frame.
        assertEquals(1, solo.effectsOf<RoomEffect.SendSignal>().count { it.msg == ClientMessage.WsFallback })
        assertEquals(0, solo.effectsOf<RoomEffect.SendFrame>().size)
    }

    // ---- v1 decisions ----

    @Test
    fun `hide my IP without TURN uses the relay and never starts WebRTC`() {
        val (world, a, b) = pair(a = LocalPolicy(hideIp = true))
        val ready = assertReady(a, Transport.WS_RELAY)
        assertTrue(ready.hideIpViaRelay)
        assertEquals(IpExposure.MASKED, ready.ip)
        assertTrue(a.has(Notice.RELAY_ACTIVE_HIDE_IP_NO_PRO))
        assertEquals(0, a.effectsOf<RoomEffect.StartWebRtc>().size)
        assertEquals(0, world.server.candidates.size)
        assertReady(b, Transport.WS_RELAY)
    }

    @Test
    fun `hide my IP with TURN signals relay candidates only, with a blank related address`() {
        val world = World()
        world.server.turn = true
        val a = world.peer("a", key, LocalPolicy(hideIp = true), token = "pro")
        val b = world.peer("b", key, LocalPolicy(hideIp = true))
        a.start(); world.run(); b.start(); world.run()
        assertEquals(IceMode.RELAY, a.effectsOf<RoomEffect.StartWebRtc>().single().mode)
        assertTrue(world.server.candidates.isNotEmpty())
        for (c in world.server.candidates) {
            assertTrue(" typ relay " in "$c ", c)
            assertTrue("raddr 0.0.0.0 rport 0" in c, c)
            assertFalse(CandidatePolicy.exposesIp(c), c)
        }
        a.post(RoomInput.SelectedRoute(a.pc!!, relay = true)); world.run()
        val ready = assertReady(a, Transport.TURN)
        assertEquals(IpExposure.MASKED, ready.ip)
        assertFalse(ready.hideIpViaRelay)
    }

    @Test
    fun `a tunnel that fails after it was up waits for a manual reconnect`() {
        val (world, a, b) = pair()
        a.send("f", bytes(500_000)); world.run()
        val signalsBefore = a.effectsOf<RoomEffect.SendSignal>().size
        val startsBefore = a.effectsOf<RoomEffect.StartWebRtc>().size

        a.post(RoomInput.PeerConnectionState(a.pc!!, PcState.DISCONNECTED)); world.run()
        assertEquals(RoomState.Interrupted(Transport.DIRECT, canReconnect = false), a.state)
        a.post(RoomInput.PeerConnectionState(a.pc!!, PcState.FAILED)); world.run()
        assertEquals(RoomState.Interrupted(Transport.DIRECT, canReconnect = true), a.state)
        world.advance(5 * 60_000)
        // Nothing was signaled and nothing gathered: no relay request, no new session.
        assertEquals(signalsBefore, a.effectsOf<RoomEffect.SendSignal>().size)
        assertEquals(startsBefore, a.effectsOf<RoomEffect.StartWebRtc>().size)
        assertFalse(a.machine.canSend)

        a.post(RoomInput.Reconnect); world.advance(2_000)
        assertReady(a, Transport.DIRECT)
        assertReady(b, Transport.DIRECT)
        a.send("again", bytes(1000)); world.run()
        assertEquals(2, b.committed.size)
    }

    @Test
    fun `after a failed tunnel the peer's relay request is honoured`() {
        val (world, a, b) = pair()
        a.post(RoomInput.PeerConnectionState(a.pc!!, PcState.FAILED)); world.run()
        assertIs<RoomState.Interrupted>(a.state)
        // The other side (a web client) falls back by itself.
        b.post(RoomInput.SetPolicy(relay)); world.run()
        assertReady(a, Transport.WS_RELAY)
        assertReady(b, Transport.WS_RELAY)
        b.send("f", bytes(50_000)); world.run()
        assertEquals(1, a.committed.size)
    }

    @Test
    fun `before the tunnel first opens, a timeout or failure falls back to the relay`() {
        val (world, a, b) = pair { webRtcConnects = false }
        assertIs<RoomState.Negotiating>(a.state)
        world.advance(11_900)
        assertIs<RoomState.Negotiating>(a.state)
        world.advance(200)
        assertReady(a, Transport.WS_RELAY)
        assertReady(b, Transport.WS_RELAY)
        assertTrue(a.has(Notice.WEBRTC_TIMEOUT) || b.has(Notice.WEBRTC_TIMEOUT))
        // The host candidate signaled during the attempt already reached the peer.
        assertEquals(IpExposure.EXPOSED, assertReady(a, Transport.WS_RELAY).ip)

        val (w2, c, d) = pair { webRtcConnects = false }
        c.post(RoomInput.PeerConnectionState(c.pc!!, PcState.FAILED)); w2.run()
        assertReady(c, Transport.WS_RELAY)
        assertReady(d, Transport.WS_RELAY)
    }

    @Test
    fun `a relay fallback between peers behind one router is told apart from any other`() {
        fun srflx(public: String, n: Int) = "candidate:2 1 udp 1685987071 $public 4000$n typ srflx raddr 192.168.1.1$n rport 5000"
        fun relayed(aPublic: String?, bPublic: String?, connects: Boolean = false): Pair<RoomState.Ready, RoomState.Ready> {
            val (world, a, b) = pair { webRtcConnects = connects }
            aPublic?.let { a.post(RoomInput.LocalCandidate(a.pc!!, srflx(it, 0), "0", 0)) }
            bPublic?.let { b.post(RoomInput.LocalCandidate(b.pc!!, srflx(it, 1), "0", 0)) }
            world.advance(RoomMachine.ICE_TIMEOUT_MS + 100)
            val transport = if (connects) Transport.DIRECT else Transport.WS_RELAY
            return assertReady(a, transport) to assertReady(b, transport)
        }

        // The same public address on both sides: one router. Both peers see it.
        relayed("203.0.113.50", "203.0.113.50").let { (a, b) -> assertTrue(a.relayedOnSameNetwork && b.relayedOnSameNetwork) }
        // Different routers, or an address missing on either side (Tor, a VPN, no STUN): nothing to offer.
        relayed("203.0.113.50", "198.51.100.7").let { (a, b) -> assertFalse(a.relayedOnSameNetwork || b.relayedOnSameNetwork) }
        relayed("203.0.113.50", null).let { (a, b) -> assertFalse(a.relayedOnSameNetwork || b.relayedOnSameNetwork) }
        relayed(null, null).let { (a, b) -> assertFalse(a.relayedOnSameNetwork || b.relayedOnSameNetwork) }
        // Connected directly: there is nothing to fix.
        relayed("203.0.113.50", "203.0.113.50", connects = true).let { (a, b) -> assertFalse(a.relayedOnSameNetwork || b.relayedOnSameNetwork) }

        // A new pairing starts from nothing: last time's addresses do not carry over.
        val (world, a, b) = pair { webRtcConnects = false }
        a.post(RoomInput.LocalCandidate(a.pc!!, srflx("203.0.113.50", 0), "0", 0))
        b.post(RoomInput.LocalCandidate(b.pc!!, srflx("203.0.113.50", 1), "0", 0))
        world.advance(RoomMachine.ICE_TIMEOUT_MS + 100)
        assertTrue(assertReady(a, Transport.WS_RELAY).relayedOnSameNetwork)
        a.post(RoomInput.Reconnect)
        world.advance(RoomMachine.ICE_TIMEOUT_MS + 5_000)
        assertFalse(assertReady(a, Transport.WS_RELAY).relayedOnSameNetwork)
        assertFalse(assertReady(b, Transport.WS_RELAY).relayedOnSameNetwork)

        // Only server-reflexive lines carry such an address, and a peer cannot make the set grow without bound.
        assertEquals("203.0.113.50", CandidatePolicy.reflexiveAddress(srflx("203.0.113.50", 0)))
        assertEquals("2001:db8::1", CandidatePolicy.reflexiveAddress("candidate:2 1 udp 1 2001:DB8::1 4000 typ srflx raddr :: rport 0"))
        for (other in listOf("", "candidate:1 1 udp 1 192.168.1.2 5000 typ host", "candidate:3 1 udp 1 198.51.100.9 49200 typ relay raddr 203.0.113.7 rport 61000", "typ srflx", "candidate:2 1 udp 1 203.0.113.50 4000 typ")) {
            assertEquals(null, CandidatePolicy.reflexiveAddress(other), other)
        }
        val (w2, c, d) = pair { webRtcConnects = false }
        repeat(60) { c.post(RoomInput.LocalCandidate(c.pc!!, srflx("203.0.113.${it + 1}", 0), "0", 0)) }
        d.post(RoomInput.LocalCandidate(d.pc!!, srflx("203.0.113.60", 1), "0", 0))
        w2.advance(RoomMachine.ICE_TIMEOUT_MS + 100)
        // The sixtieth address fell outside what is kept: no match, and no error.
        assertFalse(assertReady(c, Transport.WS_RELAY).relayedOnSameNetwork)
    }

    @Test
    fun `tor mode never starts WebRTC and signals no candidate`() {
        val (world, a, b) = pair(a = LocalPolicy(webRtcAvailable = false))
        assertReady(a, Transport.WS_RELAY)
        assertReady(b, Transport.WS_RELAY)
        assertEquals(0, a.effectsOf<RoomEffect.StartWebRtc>().size)
        assertTrue(a.effectsOf<RoomEffect.SendSignal>().none { it.msg is ClientMessage.Candidate || it.msg is ClientMessage.Offer || it.msg is ClientMessage.Answer })
        assertEquals(IpExposure.MASKED, assertReady(a, Transport.WS_RELAY).ip)
        // Unticking "force relay" cannot lead to WebRTC either.
        a.post(RoomInput.SetPolicy(LocalPolicy(webRtcAvailable = false))); world.advance(5_000)
        assertEquals(0, a.effectsOf<RoomEffect.StartWebRtc>().size)
        assertEquals(0, world.server.candidates.count { "192.168.1.10" in it })
    }

    @Test
    fun `at most 150 candidates are signaled per pairing`() {
        val (world, a, _) = pair { webRtcConnects = false }
        repeat(400) { a.post(RoomInput.LocalCandidate(a.pc!!, "candidate:$it 1 udp 1 10.0.0.$it 1 typ host", "0", 0)) }
        world.run()
        assertEquals(150, a.effectsOf<RoomEffect.SendSignal>().count { it.msg is ClientMessage.Candidate })
    }

    // ---- Signaling resilience ----

    @Test
    fun `reconnect backoff doubles and resets only after ten stable seconds`() {
        val world = World()
        val a = world.peer("a", key)
        world.server.rejectUpgrades = CloseCause.HttpRejected(429)
        a.start(); world.run()
        val delays = ArrayList<Long>()
        repeat(7) {
            val arm = a.effectsOf<RoomEffect.ArmTimer>().last { it.id == TimerId.RECONNECT }
            delays += arm.delayMs
            world.advance(arm.delayMs)
        }
        assertEquals(listOf(1000L, 2000, 4000, 8000, 16000, 30000, 30000), delays)
        assertIs<RoomState.Connecting>(a.state)

        // A connection that opens and drops within the stable window keeps the backoff.
        world.server.rejectUpgrades = null
        world.advance(30_000)
        assertEquals(RoomState.WaitingForPeer, a.state)
        world.advance(9_000)
        world.server.disconnect(world.server.conns.single(), notifyClient = true); world.run()
        assertEquals(30_000, a.effectsOf<RoomEffect.ArmTimer>().last { it.id == TimerId.RECONNECT }.delayMs)

        // Ten stable seconds reset it.
        world.advance(30_000)
        world.advance(10_001)
        world.server.disconnect(world.server.conns.single(), notifyClient = true); world.run()
        assertEquals(1000, a.effectsOf<RoomEffect.ArmTimer>().last { it.id == TimerId.RECONNECT }.delayMs)
    }

    @Test
    fun `a rejected origin is fatal and never retried`() {
        val world = World()
        val a = world.peer("a", key)
        world.server.rejectUpgrades = CloseCause.HttpRejected(403)
        a.start(); world.run()
        assertEquals(RoomState.Fatal(FatalReason.ORIGIN_REJECTED), a.state)
        world.advance(10 * 60_000)
        assertEquals(1, world.server.upgrades)
    }

    @Test
    fun `losing signaling keeps a verified direct tunnel, and so does the next ready`() {
        val (world, a, b) = pair()
        world.server.disconnect(world.server.conns.first { it.peer === a }, notifyClient = true); world.run()
        assertTrue(a.has(Notice.SIGNALING_LOST_TUNNEL_UP))
        assertTrue(b.has(Notice.PEER_SIGNALING_AWAY))
        assertTrue(assertReady(b, Transport.DIRECT).peerSignalingAway)
        // The tunnel still carries a file while signaling is down.
        a.send("f", bytes(100_000)); world.run()
        assertEquals(1, b.committed.size)

        world.advance(2_000) // a reconnects and both get `ready`
        assertTrue(a.has(Notice.KEPT_TUNNEL) && b.has(Notice.KEPT_TUNNEL))
        assertEquals(1, a.effectsOf<RoomEffect.StartWebRtc>().size)
        assertFalse(assertReady(b, Transport.DIRECT).peerSignalingAway)
        b.send("g", bytes(100_000)); world.run()
        assertEquals(1, a.committed.size)
    }

    @Test
    fun `losing signaling on the relay ends the session and a reconnect restores it`() {
        val (world, a, b) = pair(a = relay)
        a.send("f", bytes(3_000_000))
        // Drop a's socket in the middle of the transfer.
        world.run(limit = 400)
        assertEquals("receiving", b.machine.incoming?.phase)
        world.server.disconnect(world.server.conns.first { it.peer === a }, notifyClient = true)
        world.run()
        assertIs<RoomState.Connecting>(a.state)
        assertEquals(RoomState.WaitingForPeer, b.state)
        world.advance(2_000)
        assertReady(a, Transport.WS_RELAY)
        assertReady(b, Transport.WS_RELAY)
        assertEquals(0, b.committed.size + b.received.size) // nothing half-written survives
        a.send("f", bytes(30_000)); world.run()
        assertEquals(1, b.committed.size)
    }

    @Test
    fun `a full room right after a drop is retried, a full room otherwise is final`() {
        val world = World()
        val a = world.peer("a", key, relay)
        val b = world.peer("b", key, relay)
        val c = world.peer("c", key)
        a.start(); b.start(); world.run()
        assertReady(b, Transport.WS_RELAY)
        c.start(); world.run()
        assertEquals(RoomState.RoomFull, c.state)
        val upgrades = world.server.upgrades
        world.advance(120_000)
        assertEquals(upgrades, world.server.upgrades)

        // b's connection dropped, but the server still holds its old socket.
        val old = world.server.conns.first { it.peer === b }
        b.post(RoomInput.SignalingClosed(old.socket, CloseCause.NetworkError)); world.run()
        world.advance(1_500) // b reconnects: the room looks full
        assertIs<RoomState.Connecting>(b.state)
        assertTrue(world.server.upgrades > upgrades)
        world.server.disconnect(old, notifyClient = false) // the heartbeat reaps the old socket
        world.advance(10_000)
        assertReady(a, Transport.WS_RELAY)
        assertReady(b, Transport.WS_RELAY)
    }

    // ---- Transfer edge cases ----

    @Test
    fun `decline, cancel on either side, and a second offer while busy`() {
        val (world, a, b) = pair()
        b.autoAccept = false
        a.send("declined", bytes(10)); world.run()
        assertEquals("offered", b.machine.incoming?.phase)
        b.post(RoomInput.DeclineIncoming()); world.run()
        assertTrue(a.has(Notice.NOT_SENT_DECLINED))
        assertEquals(null, a.machine.outgoing)

        // The receiver has no room for it.
        a.send("huge", bytes(10)); world.run()
        b.post(RoomInput.DeclineIncoming(DeclineReason.SIZE)); world.run()
        assertTrue(a.has(Notice.NOT_SENT_TOO_LARGE_FOR_PEER))

        // The sender cancels while the offer is open.
        a.send("cancelled", bytes(10)); world.run()
        a.post(RoomInput.CancelSend); world.run()
        assertTrue(b.has(Notice.SENDER_ABORTED))
        assertEquals(null, b.machine.incoming)

        // The receiver cancels mid-transfer.
        b.autoAccept = true
        val discardedBefore = b.discarded
        a.send("stopped", bytes(2_000_000))
        world.run(limit = 200)
        assertEquals("receiving", b.machine.incoming?.phase)
        b.post(RoomInput.CancelReceive)
        world.run()
        assertTrue(a.notices.any { it.notice == Notice.RECEIVER_ABORTED && it.detail == "user" })
        assertEquals(discardedBefore + 1, b.discarded)
        assertTrue(b.received.isEmpty())
        assertEquals(null, a.machine.outgoing)
        assertEquals(0, b.committed.size)

        // Both ends are usable afterwards.
        a.send("ok", bytes(40_000)); world.run()
        assertEquals(1, b.committed.size)
    }

    @Test
    fun `a failed write aborts with the disk reason and deletes the partial file`() {
        val (world, a, b) = pair()
        b.failWrites = true
        a.send("f", bytes(100_000)); world.run()
        assertTrue(a.notices.any { it.notice == Notice.RECEIVER_ABORTED && it.detail == "disk" })
        assertEquals(0, b.committed.size)
        assertEquals(1, b.discarded)
        assertTrue(b.received.isEmpty())
    }

    @Test
    fun `a file that changes while it is sent is aborted with the disk reason`() {
        val (world, a, b) = pair()
        b.autoAccept = false
        a.send("f", bytes(50_000)); world.run()
        a.source = bytes(20_000) // truncated before the receiver accepts
        b.post(RoomInput.AcceptIncoming); world.run()
        assertTrue(a.has(Notice.SEND_ABORTED))
        assertTrue(b.notices.any { it.notice == Notice.SENDER_ABORTED && it.detail == "disk" })
        assertEquals(0, b.committed.size)
        assertTrue(b.received.isEmpty())
        assertTrue(a.machine.canSend)
    }

    @Test
    fun `no answer to an offer times out after five minutes`() {
        val (world, a, b) = pair()
        b.autoAccept = false
        a.send("f", bytes(10)); world.run()
        world.advance(299_000)
        assertEquals("offered", a.machine.outgoing?.phase)
        world.advance(2_000)
        assertEquals(null, a.machine.outgoing)
        assertTrue(a.notices.any { it.notice == Notice.SEND_ABORTED })
        assertTrue(b.notices.any { it.notice == Notice.SENDER_ABORTED && it.detail == "timeout" })
        assertTrue(a.machine.canSend)
    }

    @Test
    fun `frames replayed by the relay are refused and change nothing`() {
        val (world, a, b) = pair(a = relay)
        a.send("f", bytes(40_000)); world.run()
        assertEquals(1, b.committed.size)
        val sb = world.server.conns.first { it.peer === b }.socket
        val before = b.notices.size
        // Everything a sent, delivered to b a second time.
        for (f in a.effectsOf<RoomEffect.SendFrame>()) b.post(RoomInput.Frame(ChannelRef(ChannelKind.WS_RELAY, sb), f.bytes))
        world.run()
        assertEquals(1, b.committed.size)
        assertReady(b, Transport.WS_RELAY)
        assertTrue(b.notices.drop(before).none { it.notice == Notice.INCOMING_OFFER || it.notice == Notice.KEY_MISMATCH })
        // A frame reflected back to its sender fails authentication and is refused.
        val sa = world.server.conns.first { it.peer === a }.socket
        a.post(RoomInput.Frame(ChannelRef(ChannelKind.WS_RELAY, sa), a.effectsOf<RoomEffect.SendFrame>().last().bytes)); world.run()
        assertReady(a, Transport.WS_RELAY)
    }

    @Test
    fun `a sender that ignores the credit window is aborted once`() {
        val (world, a, b) = pair()
        b.holdWrites = true
        a.send("f", bytes(400 * 16_000)); world.run()
        // The honest sender stopped at its window: the first write is still pending.
        assertEquals("receiving", b.machine.incoming?.phase)
        val honest = a.effectsOf<RoomEffect.SendFrame>().count { it.notifyToken != null }
        assertEquals(WireConstants.TX_WINDOW, honest)

        // A sender that keeps going fills the receiver's queue: frames beyond 256 are dropped.
        val extra = a.effectsOf<RoomEffect.SendFrame>().last().bytes
        repeat(50) { b.post(RoomInput.Frame(ChannelRef(ChannelKind.DATA_CHANNEL, b.pc!!), extra)) }
        world.run()
        b.holdWrites = false
        b.post(RoomInput.ChunkWritten(b.heldWrite!!)); world.run()
        assertEquals(1, b.notices.count { it.notice == Notice.RECEIVE_ABORTED })
        assertTrue(a.notices.any { it.notice == Notice.RECEIVER_ABORTED && it.detail == "integrity" })
        assertEquals(0, b.committed.size)
        assertEquals(null, b.machine.incoming)
        // Both ends recover.
        a.send("g", bytes(1000)); world.run()
        assertEquals(1, b.committed.size)
    }

    @Test
    fun `garbage never throws and never produces a file`() {
        val random = Random(7)
        val (world, a, b) = pair(a = relay)
        a.send("f", bytes(100_000)); world.run()
        val real = a.effectsOf<RoomEffect.SendFrame>().map { it.bytes }
        val sb = world.server.conns.first { it.peer === b }.socket
        repeat(20_000) {
            val bytes = when (random.nextInt(4)) {
                0 -> ByteArray(random.nextInt(120)).also(random::nextBytes)
                1 -> real[random.nextInt(real.size)].copyOf().also { f -> f[random.nextInt(f.size)] = random.nextInt().toByte() }
                2 -> real[random.nextInt(real.size)].let { f -> f.copyOf(random.nextInt(f.size + 1)) }
                else -> ByteArray(49 + random.nextInt(30)).also { f -> random.nextBytes(f); f[0] = (0x20 + random.nextInt(3)).toByte() }
            }
            b.post(RoomInput.Frame(ChannelRef(ChannelKind.WS_RELAY, sb), bytes))
            if (it % 500 == 0) world.run()
        }
        world.run()
        assertEquals(1, b.committed.size)
    }

    @Test
    fun `stale sockets, sessions and transfers are ignored`() {
        val (world, a, _) = pair()
        val effects = ArrayList<RoomEffect>()
        effects += a.machine.handle(RoomInput.SignalingOpened(99))
        effects += a.machine.handle(RoomInput.Server(99, ServerMessage.PeerLeft))
        effects += a.machine.handle(RoomInput.DataChannelClosed(99))
        effects += a.machine.handle(RoomInput.PeerConnectionState(99, PcState.FAILED))
        effects += a.machine.handle(RoomInput.ChunkRead(99, ByteArray(1)))
        effects += a.machine.handle(RoomInput.ChunkWritten(99))
        effects += a.machine.handle(RoomInput.SinkCommitted(99))
        effects += a.machine.handle(RoomInput.FrameSent(99))
        effects += a.machine.handle(RoomInput.TimerFired(TimerId.RELAY_RETRY))
        effects += a.machine.handle(RoomInput.Frame(ChannelRef(ChannelKind.WS_RELAY, 99), ByteArray(60)))
        assertEquals(emptyList(), effects)
        assertReady(a, Transport.DIRECT)
        world.run()
    }

    @Test
    fun `signaling messages parse as the page would read them`() {
        val ready = ServerMessage.parse(
            """{"type":"ready","initiator":true,"iceServers":[{"urls":"stun:a"},{"urls":["turn:b","turn:c"],"username":"u","credential":"p"},7],""" +
                """"turnEnabled":true,"relayEnabled":true,"wsRelayCap":524288000,"wsRelayRemaining":1,"extra":1}""",
        )
        assertEquals(
            ServerMessage.Ready(true, listOf(IceServer(listOf("stun:a")), IceServer(listOf("turn:b", "turn:c"), "u", "p")), true, 524288000, 1),
            ready,
        )
        assertEquals(ServerMessage.Ready(false, emptyList(), false, null, null), ServerMessage.parse("""{"type":"ready"}"""))
        assertEquals(ServerMessage.Error("relay-busy"), ServerMessage.parse("""{"type":"error","reason":"relay-busy"}"""))
        assertEquals(ServerMessage.Candidate("c", null, null), ServerMessage.parse("""{"type":"candidate","candidate":{"candidate":"c","sdpMid":null,"sdpMLineIndex":null,"usernameFragment":null}}"""))
        for (bad in listOf("", "null", "[]", "7", "{", """{"type":"offer"}""", """{"type":"offer","offer":{"sdp":7}}""", """{"type":"nope"}""", """{"type":7}""")) {
            assertEquals(null, ServerMessage.parse(bad), bad)
        }
        assertEquals("""{"type":"join","room":"abc","token":""}""", ClientMessage.Join("abc", "").toJson())
        assertEquals("""{"type":"offer","offer":{"type":"offer","sdp":"v=0\r\n"}}""", ClientMessage.Offer("v=0\r\n").toJson())
        assertEquals(
            """{"type":"candidate","candidate":{"candidate":"c","sdpMid":"0","sdpMLineIndex":0,"usernameFragment":null}}""",
            ClientMessage.Candidate("c", "0", 0).toJson(),
        )
        assertFalse("secret" in ClientMessage.Join("room", "secret").toString())
    }

    @Test
    fun `share links`() {
        val link = RoomLink.generate("https://DarkP2P.com/", SeededRandom(3))
        assertEquals("https://darkp2p.com", link.origin)
        assertEquals(32, link.roomId.length)
        val parsed = RoomLink.parse(link.shareUrl + "")!!
        assertEquals(link.shareUrl, parsed.shareUrl)
        assertEquals(link.shareUrl, RoomLink.parse("https://darkp2p.com/?NP_id=123#${link.fragment}")!!.shareUrl) // the query never survives
        assertFalse(link.isOnion)
        val onion = "http://${"a".repeat(56)}.onion"
        assertTrue(RoomLink.parse("$onion/#${link.fragment}")!!.isOnion)
        assertEquals("https://example.org:8443", RoomLink.normalizeOrigin("https://example.org:8443/path?q"))
        for (bad in listOf("http://darkp2p.com/#${link.fragment}", "ftp://x/#${link.fragment}", "https://user@x.org/#${link.fragment}", "https://darkp2p.com/", "nonsense", "https://darkp2p.com/#abc")) {
            assertEquals(null, RoomLink.parse(bad), bad)
        }
        assertFalse(link.fragment in link.toString() || link.key.toBase64Url() in link.key.toString())
    }
}
