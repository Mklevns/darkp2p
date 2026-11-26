package com.darkp2p.transport

import com.darkp2p.protocol.ChatFailure
import com.darkp2p.protocol.IpExposure
import com.darkp2p.protocol.LocalPolicy
import com.darkp2p.protocol.Notice
import com.darkp2p.protocol.RelayBudget
import com.darkp2p.protocol.RoomInput
import com.darkp2p.protocol.RoomLink
import com.darkp2p.protocol.RoomState
import com.darkp2p.protocol.Transport
import com.darkp2p.protocol.WireConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The app's protocol and transport code against the unmodified server.js and
 * the unmodified page (its real script and a real WebSocket, run by Node).
 */
class InteropTest {
    private val relayOk = "^DROP FILE HERE OR CLICK TO SELECT \\(WS RELAY\\)$"

    @Test
    fun `app to page and page to app over the relay, at every boundary size`() = runBlocking<Unit> {
        WebSide(mapOf("RELAY_CAP_FREE_MB" to "200")).use { web ->
            val link = web.newLink()
            val page = web.page("p", link)
            page.waitLog("^Connected to signaling broker")
            AppPeer(link).use { app ->
                val ready = app.awaitReady()
                assertEquals(Transport.WS_RELAY, ready.transport)
                assertEquals(IpExposure.MASKED, ready.ip)
                assertFalse(ready.chat) // the page offers no chat
                page.waitLog("^Peer key verified, session key confirmed")
                page.waitReady()

                val sizes = listOf(0, 1, 15_999, 16_000, 16_001, 64 * 16_000, 64 * 16_000 + 1, 256 * 16_000 + 1, 5_000_003)
                for ((i, size) in sizes.withIndex()) {
                    // App -> page.
                    val out = fileBytes("out$i", size)
                    app.send("out-$i.bin", out)
                    page.accept()
                    assertEquals(sha256(out) to size, page.download(i), "app -> page, $size bytes")
                    app.awaitNotice(Notice.DELIVERED, count = i + 1)
                    page.waitLog("^Verified & saved: out-$i\\.bin$")

                    // Page -> app.
                    page.send("in-$i.bin", "in$i", size)
                    app.awaitNotice(Notice.SAVED, count = i + 1)
                    page.waitLog("^Delivered: in-$i\\.bin$", 30_000)
                    assertContentEquals(fileBytes("in$i", size), app.io.committed[i], "page -> app, $size bytes")
                }
                assertEquals(emptyList(), page.errors())
                assertEquals(0, app.io.discarded.get())
                assertIs<RoomState.Ready>(app.state)
            }
        }
    }

    @Test
    fun `the app's relay budget is the server's own count, to the byte`() = runBlocking<Unit> {
        WebSide().use { web ->
            val link = web.newLink()
            val page = web.page("p", link)
            page.waitLog("^Connected to signaling broker")
            AppPeer(link).use { app ->
                val first = app.awaitReady()
                assertEquals(50L * 1024 * 1024, first.relayCap)
                app.send("a.bin", fileBytes("a", 700_001)); page.accept(); page.download(0)
                app.awaitNotice(Notice.DELIVERED)
                page.send("b.bin", "b", 300_000); app.awaitNotice(Notice.SAVED)
                page.waitLog("^Delivered: b\\.bin$")
                delay(300) // the last control frames settle
                val before = (app.state as RoomState.Ready).relayRemaining!!
                assertTrue(before < first.relayRemaining!! - 1_000_000)

                // Rejoin: the server's `ready` carries what IT counted for this room
                // (the page stayed, so the room and its counter lived on).
                app.session.post(RoomInput.Reconnect)
                app.awaitNotice(Notice.KEY_VERIFIED, count = 2)
                val after = app.awaitReady().relayRemaining!!
                // Since that `ready`, only the new key handshake crossed the relay:
                // the page's hello (125 bytes), the app's with its caps field (141), two hello-acks (75 each).
                assertEquals(before - (125 + 141 + 75 + 75), after)
                assertEquals(emptyList(), page.errors().filterNot { it.startsWith("Remote peer disconnected") })
            }
        }
    }

    @Test
    fun `a spent relay budget ends in the exhausted state on both sides, without a retry loop`() = runBlocking<Unit> {
        WebSide(mapOf("RELAY_CAP_FREE_MB" to "1")).use { web ->
            val link = web.newLink()
            val page = web.page("p", link)
            page.waitLog("^Connected to signaling broker")
            AppPeer(link).use { app ->
                app.awaitReady()
                // Larger than the room: refused locally, nothing offered.
                app.send("big.bin", fileBytes("big", 2_000_000))
                app.awaitNotice(Notice.DOES_NOT_FIT_RELAY)
                assertFalse(page.logs().any { it.startsWith("Incoming offer") })

                // The largest file that still fits leaves less than the handshake minimum.
                val remaining = (app.state as RoomState.Ready).relayRemaining!!
                var size = remaining.toInt()
                while (RelayBudget.wireBytes(size.toLong(), WireConstants.CHUNK_SIZE) > remaining) size -= 1
                app.send("fits.bin", fileBytes("fits", size)); page.accept()
                assertEquals(size, page.download(0).second)
                app.awaitNotice(Notice.DELIVERED)
                delay(300)
                val left = (app.state as RoomState.Ready).relayRemaining!!
                assertTrue(left < WireConstants.RELAY_MIN_REMAINING, "left $left")

                // A fresh pairing on the relay: the server refuses the relay, both sides stop.
                app.session.post(RoomInput.Reconnect)
                app.await("the exhausted state") { app.state is RoomState.RelayExhausted }
                page.waitLabel("^RELAY BUDGET USED UP")
                val sockets = page.state().int("sockets")
                val notices = app.notices.size
                delay(3_000)
                assertIs<RoomState.RelayExhausted>(app.state)
                assertEquals(sockets, page.state().int("sockets")) // the page did not reconnect
                assertEquals(notices, app.notices.size) // and the app did nothing more
                assertEquals(1, app.notices.count { it.notice == Notice.RELAY_EXHAUSTED })
                app.send("more.bin", fileBytes("more", 10)); delay(300)
                assertEquals(null, app.session.snapshot.value.outgoing)
            }
        }
    }

    @Test
    fun `an altered key is a mismatch on both sides`() = runBlocking<Unit> {
        WebSide().use { web ->
            val link = web.newLink()
            val other = RoomLink.parse("${web.origin}/#${link.roomId}:${RoomLink.generate(web.origin, SecureRandom()).key.toBase64Url()}")!!
            val page = web.page("p", link)
            page.waitLog("^Connected to signaling broker")
            AppPeer(other).use { app ->
                app.await("key mismatch") { app.state == RoomState.KeyMismatch }
                page.waitLabel("^Key mismatch")
                delay(6_000) // past the hello timeout: it stays a mismatch, not "outdated"
                assertEquals(RoomState.KeyMismatch, app.state)
                assertFalse(app.session.snapshot.value.canSend)
                assertTrue(page.state().str("label").startsWith("Key mismatch"))
            }
        }
    }

    @Test
    fun `a full relay is reported as busy, not as an outdated peer, and connects once a slot frees`() = runBlocking<Unit> {
        WebSide(mapOf("MAX_RELAY_ROOMS" to "1")).use { web ->
            // Another room holds the server's only relay slot.
            val busyLink = web.newLink()
            val c = web.page("c", busyLink)
            c.waitLog("^Connected to signaling broker")
            val d = web.page("d", busyLink)
            c.waitReady(); d.waitReady()

            val link = web.newLink()
            val page = web.page("p", link)
            page.waitLog("^Connected to signaling broker")
            AppPeer(link).use { app ->
                app.await("the busy state") { app.state == RoomState.RelayBusy }
                delay(6_000) // past the 5 s hello timeout
                assertEquals(RoomState.RelayBusy, app.state)
                assertFalse(app.has(Notice.PEER_OUTDATED))
                assertEquals(1, app.notices.count { it.notice == Notice.RELAY_BUSY })

                c.close(); d.close()
                app.awaitReady(timeoutMs = 45_000) // the 30 s retry
                page.waitReady()
                app.send("f.bin", fileBytes("f", 50_000)); page.accept()
                assertEquals(50_000, page.download(0).second)
            }
        }
    }

    @Test
    fun `an idle relay pauses both sides and resumes on request`() = runBlocking<Unit> {
        WebSide(mapOf("RELAY_IDLE_MS" to "1500", "HEARTBEAT_MS" to "1000")).use { web ->
            val link = web.newLink()
            val page = web.page("p", link)
            page.waitLog("^Connected to signaling broker")
            AppPeer(link).use { app ->
                app.awaitReady()
                app.await("the idle state") { app.state == RoomState.RelayIdle }
                page.waitLabel("^RELAY PAUSED \\(IDLE\\)")
                app.session.post(RoomInput.ResumeRelay)
                app.awaitReady()
                page.waitReady()
                app.send("f.bin", fileBytes("f", 123_456)); page.accept()
                assertEquals(sha256(fileBytes("f", 123_456)) to 123_456, page.download(0))

                // And the other way round: the page resumes, the app follows.
                app.await("idle again") { app.state == RoomState.RelayIdle }
                page.waitLabel("^RELAY PAUSED \\(IDLE\\)")
                page.clickDropzone()
                app.awaitReady()
                page.send("g.bin", "g", 40_000)
                app.awaitNotice(Notice.SAVED)
            }
        }
    }

    @Test
    fun `the server restarting is survived, with backoff, and files move again`() = runBlocking<Unit> {
        WebSide().use { web ->
            val link = web.newLink()
            val page = web.page("p", link)
            page.waitLog("^Connected to signaling broker")
            AppPeer(link).use { app ->
                app.awaitReady()
                web.call("killServer")
                app.await("signaling lost") { app.state is RoomState.Connecting }
                delay(4_500) // refused connections at about 1 s, 2 s, 4 s...
                val attempts = (app.state as RoomState.Connecting).attempt
                assertTrue(attempts in 2..4, "attempts in 4.5 s: $attempts")

                web.call("restartServer")
                app.awaitReady(timeoutMs = 60_000)
                page.waitReady()
                app.send("f.bin", fileBytes("f", 200_000)); page.accept()
                assertEquals(200_000, page.download(0).second)
            }
        }
    }

    @Test
    fun `a Pro token raises the room's relay cap`() = runBlocking<Unit> {
        WebSide().use { web ->
            val token = web.call("token").str("token")
            val link = web.newLink()
            val page = web.page("p", link)
            page.waitLog("^Connected to signaling broker")
            AppPeer(link, token = token).use { app ->
                assertEquals(500L * 1024 * 1024, app.awaitReady().relayCap)
            }
            // An invalid token is simply a free room.
            val link2 = web.newLink()
            web.page("q", link2).waitLog("^Connected to signaling broker")
            AppPeer(link2, token = "1.${"0".repeat(64)}").use { app ->
                assertEquals(50L * 1024 * 1024, app.awaitReady().relayCap)
            }
        }
    }

    @Test
    fun `declines and cancels in both directions leave both sides usable`() = runBlocking<Unit> {
        WebSide().use { web ->
            val link = web.newLink()
            val page = web.page("p", link)
            page.waitLog("^Connected to signaling broker")
            AppPeer(link, autoAccept = false).use { app ->
                app.awaitReady()
                app.send("no.bin", fileBytes("no", 1000)); page.decline()
                app.awaitNotice(Notice.NOT_SENT_DECLINED)

                page.send("no2.bin", "no2", 1000)
                app.awaitNotice(Notice.INCOMING_OFFER)
                app.session.post(RoomInput.DeclineIncoming())
                page.waitLog("^Not sent: no2\\.bin \\(declined by the peer\\)")

                // The app cancels its own offer; the page sees the abort.
                app.send("gone.bin", fileBytes("gone", 1000))
                page.waitLog("^Incoming offer: gone\\.bin")
                app.session.post(RoomInput.CancelSend)
                page.waitLog("^Sender aborted gone\\.bin \\(user\\)")

                // The app cancels a receive in flight; the page's sender sees why.
                page.send("big.bin", "big", 3_000_000)
                app.awaitNotice(Notice.INCOMING_OFFER, count = 2)
                app.session.post(RoomInput.AcceptIncoming)
                app.await("bytes arriving") { (app.session.snapshot.value.incoming?.bytesDone ?: 0) > 100_000 }
                app.session.post(RoomInput.CancelReceive)
                page.waitLog("^Receiver aborted big\\.bin \\(user\\)")
                app.await("the receive to end") { app.session.snapshot.value.incoming == null }
                assertEquals(1, app.io.discarded.get())

                page.send("ok.bin", "ok", 70_000)
                app.awaitNotice(Notice.INCOMING_OFFER, count = 3)
                app.session.post(RoomInput.AcceptIncoming)
                app.awaitNotice(Notice.SAVED)
                assertContentEquals(fileBytes("ok", 70_000), app.io.committed.single())
            }
        }
    }

    @Test
    fun `chat - never sent to the page, and working between two apps over the real server`() = runBlocking<Unit> {
        WebSide().use { web ->
            val link = web.newLink()
            val page = web.page("p", link)
            page.waitLog("^Connected to signaling broker")
            AppPeer(link).use { app ->
                assertFalse(app.awaitReady().chat)
                app.session.post(RoomInput.SendChat(1, "is anyone there?"))
                app.await("the refusal") { app.chat.isNotEmpty() }
                assertEquals(ChatEvent.Failed(1, ChatFailure.UNAVAILABLE), app.chat.single())
                delay(500)
                assertEquals(emptyList(), page.errors())
                // The page stayed a working peer.
                app.send("f.bin", fileBytes("f", 20_000)); page.accept()
                assertEquals(20_000, page.download(0).second)
            }

            val link2 = web.newLink()
            AppPeer(link2).use { a ->
                a.await("waiting for a peer") { a.state == RoomState.WaitingForPeer }
                AppPeer(link2).use { b ->
                    assertTrue(a.awaitReady().chat && b.awaitReady().chat)
                    a.session.post(RoomInput.SendChat(1, "hello from a – 文件 😀"))
                    b.session.post(RoomInput.SendChat(7, "hello from b"))
                    a.await("a's receipt and b's message") { a.chat.size >= 2 }
                    b.await("b's receipt and a's message") { b.chat.size >= 2 }
                    assertEquals(listOf("hello from b"), a.chat.filterIsInstance<ChatEvent.Received>().map { it.text })
                    assertEquals(listOf("hello from a – 文件 😀"), b.chat.filterIsInstance<ChatEvent.Received>().map { it.text })
                    assertTrue(ChatEvent.Delivered(1) in a.chat && ChatEvent.Delivered(7) in b.chat)
                    // A file between two apps, with chat in the middle of it.
                    val data = fileBytes("aa", 2_000_000)
                    a.send("aa.bin", data)
                    b.await("the transfer under way") { (b.session.snapshot.value.incoming?.bytesDone ?: 0) > 0 }
                    a.session.post(RoomInput.SendChat(2, "sending you a file"))
                    b.awaitNotice(Notice.SAVED)
                    assertContentEquals(data, b.io.committed.single())
                    b.await("the second message") { b.chat.filterIsInstance<ChatEvent.Received>().size == 2 }
                }
            }
        }
    }
}
