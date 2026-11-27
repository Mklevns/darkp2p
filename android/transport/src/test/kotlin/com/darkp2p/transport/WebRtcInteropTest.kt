package com.darkp2p.transport

import com.darkp2p.protocol.CandidatePolicy
import com.darkp2p.protocol.IpExposure
import com.darkp2p.protocol.LocalPolicy
import com.darkp2p.protocol.Notice
import com.darkp2p.protocol.RoomInput
import com.darkp2p.protocol.RoomState
import com.darkp2p.protocol.Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The room's WebRTC path with a real libwebrtc on both ends (a desktop build,
 * see [DesktopWebRtcFactory]): real ICE, DTLS and SCTP on this machine's
 * network, signaled through the real server.js.
 */
class WebRtcInteropTest {
    private val direct = LocalPolicy()

    private fun assumeDesktopWebRtc() {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        assumeTrue("linux" in os && (arch == "amd64" || arch == "x86_64"), "the desktop libwebrtc used here is the Linux x86-64 build")
    }

    @Test
    fun `two apps open a direct DataChannel and exchange files and chat`() = runBlocking<Unit> {
        assumeDesktopWebRtc()
        WebSide().use { web ->
            val link = web.newLink()
            val fa = DesktopWebRtcFactory()
            val fb = DesktopWebRtcFactory()
            AppPeer(link, policy = direct, webRtc = fa).use { a ->
                a.await("waiting for a peer") { a.state == RoomState.WaitingForPeer }
                AppPeer(link, policy = direct, webRtc = fb).use { b ->
                    val ready = a.awaitReady(30_000)
                    b.awaitReady(30_000)
                    assertEquals(Transport.DIRECT, ready.transport)
                    assertEquals(null, ready.relayRemaining) // no relay, no budget
                    assertTrue(ready.chat)
                    assertTrue(a.has(Notice.TUNNEL_OPEN) && b.has(Notice.TUNNEL_OPEN))
                    // Host candidates were signaled: the peer knows this address, and the app says so.
                    assertEquals(IpExposure.EXPOSED, ready.ip)
                    assertTrue(fa.gathered.isNotEmpty())

                    // Far more than the relay would allow, and large enough to fill the send buffer many times.
                    val big = fileBytes("big", 80 * 1024 * 1024)
                    a.send("big.bin", big)
                    val small = fileBytes("small", 3 * 16_000 + 1)
                    b.send("small.bin", small)
                    a.session.post(RoomInput.SendChat(1, "direct hello"))
                    b.awaitNotice(Notice.SAVED, timeoutMs = 120_000)
                    a.awaitNotice(Notice.SAVED, timeoutMs = 120_000)
                    a.awaitNotice(Notice.DELIVERED, timeoutMs = 120_000)
                    assertContentEquals(big, b.io.committed.single())
                    assertContentEquals(small, a.io.committed.single())
                    b.await("the chat message") { b.chat.any { it is ChatEvent.Received && it.text == "direct hello" } }
                    assertFalse(a.has(Notice.RELAY_ACTIVE) || b.has(Notice.RELAY_ACTIVE))
                }
                // The peer left: its tunnel closes and the room waits again.
                a.await("standby after the peer left") { a.state == RoomState.WaitingForPeer }
            }
        }
    }

    @Test
    fun `hide my IP with TURN that cannot be reached signals no address and ends on the relay`() = runBlocking<Unit> {
        assumeDesktopWebRtc()
        // A Pro room on a server with TURN configured, but nothing listens at the TURN address.
        WebSide(mapOf("TURN_HOST" to "localhost", "TURN_SECRET" to "interop-turn-secret-0123456789abcdef")).use { web ->
            val token = web.call("token").str("token")
            val link = web.newLink()
            val hide = LocalPolicy(hideIp = true)
            val fa = DesktopWebRtcFactory()
            AppPeer(link, policy = hide, token = token, webRtc = fa).use { a ->
                a.await("waiting for a peer") { a.state == RoomState.WaitingForPeer }
                AppPeer(link, policy = hide, webRtc = DesktopWebRtcFactory()).use { b ->
                    a.await("TURN-only negotiation") { a.state is RoomState.Negotiating }
                    // No TURN, so no tunnel: after 12 s both fall back to the relay.
                    val ready = a.awaitReady(30_000)
                    b.awaitReady(30_000)
                    assertEquals(Transport.WS_RELAY, ready.transport)
                    assertFalse(ready.hideIpViaRelay) // the room HAS TURN; it just did not answer
                    assertTrue(a.has(Notice.WEBRTC_TIMEOUT) || b.has(Notice.WEBRTC_TIMEOUT))
                    // Nothing that reveals an address was gathered, let alone signaled.
                    assertTrue(fa.gathered.none(CandidatePolicy::exposesIp), fa.gathered.toString())
                    assertEquals(IpExposure.MASKED, ready.ip)
                    assertEquals(IpExposure.MASKED, (b.state as RoomState.Ready).ip)
                    a.send("f.bin", fileBytes("f", 50_000))
                    b.awaitNotice(Notice.SAVED)
                }
            }
        }
    }

    @Test
    fun `a tunnel lost after it was up waits for a reconnect, which opens a new one`() = runBlocking<Unit> {
        assumeDesktopWebRtc()
        WebSide().use { web ->
            val link = web.newLink()
            AppPeer(link, policy = direct, webRtc = DesktopWebRtcFactory()).use { a ->
                a.await("waiting for a peer") { a.state == RoomState.WaitingForPeer }
                AppPeer(link, policy = direct, webRtc = DesktopWebRtcFactory()).use { b ->
                    a.awaitReady(30_000); b.awaitReady(30_000)
                    // The user asks for a clean rejoin: old tunnel closed, new pairing, new tunnel.
                    a.session.post(RoomInput.Reconnect)
                    a.awaitNotice(Notice.TUNNEL_OPEN, count = 2, timeoutMs = 30_000)
                    assertEquals(Transport.DIRECT, a.awaitReady(30_000).transport)
                    b.awaitReady(30_000)
                    a.send("again.bin", fileBytes("again", 1_000_000))
                    b.awaitNotice(Notice.SAVED, timeoutMs = 30_000)

                    // Switching to "force relay" mid-session moves both to the relay; switching back re-pairs for WebRTC.
                    a.session.post(RoomInput.SetPolicy(LocalPolicy(forceWsRelay = true)))
                    a.await("the relay") { (a.state as? RoomState.Ready)?.transport == Transport.WS_RELAY }
                    b.await("the relay") { (b.state as? RoomState.Ready)?.transport == Transport.WS_RELAY }
                    a.session.post(RoomInput.SetPolicy(direct))
                    a.await("a direct tunnel again", 30_000) { (a.state as? RoomState.Ready)?.transport == Transport.DIRECT }
                    delay(200)
                    assertIs<RoomState.Ready>(b.state)
                }
            }
        }
    }
}
