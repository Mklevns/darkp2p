package com.darkp2p.app

import com.darkp2p.protocol.FatalReason
import com.darkp2p.protocol.IceMode
import com.darkp2p.protocol.IpExposure
import com.darkp2p.protocol.LocalPolicy
import com.darkp2p.protocol.Notice
import com.darkp2p.protocol.RoomEvent
import com.darkp2p.protocol.RoomLink
import com.darkp2p.protocol.RoomState
import com.darkp2p.protocol.Transport
import com.google.zxing.RGBLuminanceSource
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLogicTest {
    // The release server, whatever a debug build was pointed at.
    private val home = "https://darkp2p.com"
    private val link = RoomLink.generate(home, SecureRandom())
    private val onion = "http://${"a".repeat(56)}.onion"

    private fun rooms(text: String?) = LinkIntake.parse(text, home).filterIsInstance<Intake.Room>()

    private fun payments(text: String?) = LinkIntake.parse(text, home).filterIsInstance<Intake.PaymentReturn>()

    @Test
    fun `a default-server link is joined, any other server is asked about first`() {
        val own = rooms(link.shareUrl).single()
        assertTrue(own.trusted)
        assertEquals(link.shareUrl, own.link.shareUrl)
        // The bare "room:key" part (a QR code may carry just that) means the default server.
        assertEquals(link.shareUrl, rooms(link.fragment).single().link.shareUrl)
        assertEquals(link.shareUrl, rooms("  #${link.fragment}\n").single().link.shareUrl)

        for (other in listOf("https://evil.example", "https://darkp2p.com.evil.example", "https://darkp2p.com:8443", onion)) {
            val room = rooms("$other/#${link.fragment}").single()
            assertFalse(room.trusted, other)
            assertEquals(other, room.link.origin)
        }
    }

    @Test
    fun `what is not a room link does nothing`() {
        for (bad in listOf(
            null, "", "   ", "hello", "https://darkp2p.com/", "https://darkp2p.com/#abc", "http://darkp2p.com/#${link.fragment}",
            "javascript:alert(1)#${link.fragment}", "content://x/#${link.fragment}", "file:///sdcard/#${link.fragment}",
            "https://user:pw@darkp2p.com/#${link.fragment}", "x".repeat(3000),
        )) {
            assertEquals(emptyList(), rooms(bad), bad?.take(40))
        }
    }

    @Test
    fun `a payment return is honoured only from the default server and only with a real payment ID`() {
        assertEquals("4000000001", payments("https://darkp2p.com/?NP_id=4000000001").single().paymentId)
        // Together with a room link: both are acted on.
        val both = LinkIntake.parse("https://darkp2p.com/?NP_id=4000000001#${link.fragment}", home)
        assertEquals(1, both.count { it is Intake.Room })
        assertEquals(1, both.count { it is Intake.PaymentReturn })
        assertEquals("7", payments("https://darkp2p.com/?x=1&NP_id=7&y=2").single().paymentId)

        for (bad in listOf(
            "https://evil.example/?NP_id=4000000001", "$onion/?NP_id=4000000001", "https://darkp2p.com/?NP_id=", "https://darkp2p.com/?NP_id=abc",
            "https://darkp2p.com/?NP_id=0", "https://darkp2p.com/?NP_id=1%26orderId=x", "https://darkp2p.com/#${link.fragment}?NP_id=5", "NP_id=5",
        )) {
            assertEquals(emptyList(), payments(bad), bad)
        }
    }

    private val everyState: List<RoomState> = listOf(
        RoomState.Connecting(0, false), RoomState.Connecting(3, true), RoomState.WaitingForPeer, RoomState.Negotiating(IceMode.ALL),
        RoomState.Negotiating(IceMode.RELAY), RoomState.Verifying(Transport.DIRECT, false), RoomState.Verifying(Transport.WS_RELAY, true),
        RoomState.Ready(Transport.DIRECT, IpExposure.EXPOSED, null, null, false, false, true),
        RoomState.Ready(Transport.TURN, IpExposure.MASKED, null, null, false, false, false),
        RoomState.Ready(Transport.WS_RELAY, IpExposure.MASKED, 50, 40, false, true, false),
        RoomState.Interrupted(Transport.DIRECT, false), RoomState.Interrupted(Transport.DIRECT, true), RoomState.RelayBusy, RoomState.RelayIdle,
        RoomState.RelayExhausted(50, true, true), RoomState.RelayExhausted(50, false, false), RoomState.KeyMismatch, RoomState.PeerOutdated,
        RoomState.RoomFull, RoomState.Fatal(FatalReason.ORIGIN_REJECTED), RoomState.Fatal(FatalReason.INVALID_ROOM),
    )

    @Test
    fun `every room state has words, and the dangerous ones are marked`() {
        val texts = everyState.map(RoomTexts::state)
        assertTrue(texts.all { it != 0 })
        // States a user must be able to tell apart have different words.
        assertNotEquals(RoomTexts.state(RoomState.KeyMismatch), RoomTexts.state(RoomState.PeerOutdated))
        assertNotEquals(RoomTexts.state(RoomState.RelayBusy), RoomTexts.state(RoomState.Verifying(Transport.WS_RELAY, false)))
        assertNotEquals(RoomTexts.state(RoomState.Interrupted(Transport.DIRECT, true)), RoomTexts.state(RoomState.Interrupted(Transport.DIRECT, false)))
        assertEquals(3, listOf(Transport.DIRECT, Transport.TURN, Transport.WS_RELAY).map { RoomTexts.state(RoomState.Ready(it, IpExposure.MASKED, null, null, false, false, false)) }.toSet().size)

        assertTrue(RoomTexts.isProblem(RoomState.KeyMismatch))
        assertTrue(RoomTexts.isProblem(RoomState.RelayExhausted(1, false, false)))
        assertTrue(RoomTexts.isProblem(RoomState.Interrupted(Transport.DIRECT, true)))
        assertFalse(RoomTexts.isProblem(RoomState.WaitingForPeer))
        assertFalse(RoomTexts.isProblem(RoomState.Ready(Transport.DIRECT, IpExposure.EXPOSED, null, null, false, false, true)))
        assertEquals(3, IpExposure.entries.map(RoomTexts::ip).toSet().size)
    }

    @Test
    fun `notices map to lines without losing the outcomes that matter`() {
        for (notice in Notice.entries) RoomTexts.notice(RoomEvent(notice, "x")) // none throws
        for (must in listOf(
            Notice.DELIVERED, Notice.NOT_SENT_DECLINED, Notice.DOES_NOT_FIT_RELAY, Notice.SEND_ABORTED, Notice.RECEIVE_ABORTED,
            Notice.SENDER_ABORTED, Notice.RECEIVER_ABORTED, Notice.SENT_UNCONFIRMED, Notice.KEY_VERIFIED,
        )) {
            assertTrue(RoomTexts.notice(RoomEvent(must)) != null, must.name)
        }
        // Chat and frame-level notices never become a line (they carry no text the user needs).
        assertNull(RoomTexts.notice(RoomEvent(Notice.CHAT_FRAME_REFUSED)))
        assertNull(RoomTexts.notice(RoomEvent(Notice.FRAME_REFUSED)))
    }

    @Test
    fun `sizes, token expiry and the policy behind the switches`() {
        assertEquals("0 B", RoomTexts.size(0))
        assertEquals("1023 B", RoomTexts.size(1023))
        assertEquals("1.0 KB", RoomTexts.size(1024))
        assertEquals("50.0 MB", RoomTexts.size(50L * 1024 * 1024))
        assertEquals("4.00 GB", RoomTexts.size(4L * 1024 * 1024 * 1024))

        assertEquals(1999999999L, RoomTexts.tokenExpiry("1999999999.${"a".repeat(64)}"))
        for (bad in listOf("", "abc.def", ".abc", "12x.abc", "9".repeat(13) + ".a")) assertNull(RoomTexts.tokenExpiry(bad), bad)

        assertEquals(LocalPolicy(), Prefs(secure = false).policy)
        // Tor mode switches WebRTC off whatever else is set.
        assertFalse(Prefs(tor = true).policy.webRtcAvailable)
        assertFalse(Prefs(tor = true, forceRelay = false, hideIp = false).policy.webRtcAvailable)
        assertEquals(LocalPolicy(forceWsRelay = true, hideIp = true, chat = false), Prefs(forceRelay = true, hideIp = true, chat = false).policy)
        // Screenshots are blocked and chat is offered unless the user says otherwise.
        assertTrue(Prefs().secure && Prefs().chat && !Prefs().tor && !Prefs().lock)
    }

    @Test
    fun `a share link survives being drawn as a QR code and read back`() {
        for (text in listOf(link.shareUrl, "$onion/#${link.fragment}", link.fragment)) {
            val matrix = QrCodes.encode(text)
            // Drawn as it is on screen: dark on white, several pixels per module.
            val scale = 4
            val size = matrix.width * scale
            val pixels = IntArray(size * size) { i -> if (matrix[(i % size) / scale, (i / size) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            assertEquals(text, QrCodes.decode(RGBLuminanceSource(size, size, pixels)))
            assertEquals(link.shareUrl.takeIf { text == it } ?: text, text)
        }
        // A picture with no code in it reads as nothing.
        assertNull(QrCodes.decode(RGBLuminanceSource(64, 64, IntArray(64 * 64) { 0xFFFFFFFF.toInt() })))
    }

    @Test
    fun `only text in the form the server issues is taken as a Pro token`() {
        val sig = "0123456789abcdef".repeat(4)
        assertTrue(RoomTexts.isTokenShaped("1800000000.$sig"))
        assertTrue(RoomTexts.isTokenShaped("  1800000000.${sig.uppercase()}\n"))
        assertEquals(1_800_000_000L, RoomTexts.tokenExpiry("1800000000.$sig"))
        for (bad in listOf(
            "", " ", "not-a-real-token", sig, "1800000000", "1800000000.", ".$sig", "1800000000.${sig.dropLast(1)}",
            "1800000000.${sig}0", "1800000000.${"g".repeat(64)}", "1234567890123.$sig", "1800000000.$sig.$sig", "18000 00000.$sig",
        )) {
            assertFalse(RoomTexts.isTokenShaped(bad), bad)
        }
    }

    @Test
    fun `a missing Orbot is explained by what made Tor necessary`() {
        assertEquals(R.string.tor_needs_orbot, RoomTexts.orbotMissing(onionRoom = true))
        assertEquals(R.string.tor_setting_needs_orbot, RoomTexts.orbotMissing(onionRoom = false))
        assertNotEquals(RoomTexts.orbotMissing(onionRoom = true), RoomTexts.orbotMissing(onionRoom = false))
    }

    @Test
    fun `flipping a privacy switch in an open room says so when it does not simply apply`() {
        fun ready(transport: Transport, ip: IpExposure) = RoomState.Ready(transport, ip, 50, 40, false, false, false)
        val exposedRelay = ready(Transport.WS_RELAY, IpExposure.EXPOSED)
        val maskedRelay = ready(Transport.WS_RELAY, IpExposure.MASKED)
        val direct = ready(Transport.DIRECT, IpExposure.EXPOSED)

        // Hiding the address after it was seen cannot take that back.
        assertEquals(R.string.room_hide_ip_late, RoomTexts.policyNotice(Prefs(), Prefs(hideIp = true), exposedRelay))
        assertEquals(R.string.room_hide_ip_late, RoomTexts.policyNotice(Prefs(), Prefs(hideIp = true), direct))
        assertNull(RoomTexts.policyNotice(Prefs(), Prefs(hideIp = true), maskedRelay))

        // Dropping the last reason to stay on the relay pairs again for a direct connection.
        assertEquals(R.string.room_reconnect_direct, RoomTexts.policyNotice(Prefs(forceRelay = true), Prefs(), maskedRelay))
        assertEquals(R.string.room_reconnect_direct, RoomTexts.policyNotice(Prefs(hideIp = true), Prefs(), maskedRelay))
        assertNull(RoomTexts.policyNotice(Prefs(forceRelay = true, hideIp = true), Prefs(hideIp = true), maskedRelay))
        assertNull(RoomTexts.policyNotice(Prefs(forceRelay = true, hideIp = true), Prefs(forceRelay = true), maskedRelay))
        // On the relay only because WebRTC failed: nothing was holding it there.
        assertNull(RoomTexts.policyNotice(Prefs(), Prefs(chat = false), exposedRelay))

        // Turning the relay on, any change outside a ready room, and Tor mode need no words.
        assertNull(RoomTexts.policyNotice(Prefs(), Prefs(forceRelay = true), direct))
        assertNull(RoomTexts.policyNotice(Prefs(), Prefs(hideIp = true), RoomState.WaitingForPeer))
        assertNull(RoomTexts.policyNotice(Prefs(), Prefs(hideIp = true), null))
        assertNull(RoomTexts.policyNotice(Prefs(forceRelay = true), Prefs(tor = true), maskedRelay))
    }
}