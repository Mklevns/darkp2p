package com.darkp2p.transport

import com.darkp2p.protocol.Notice
import com.darkp2p.protocol.RoomState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The same transfers as [InteropTest], with the page in a real headless
 * Chromium: its own WebSocket, Web Crypto, private file storage and downloads.
 *
 * Runs only where Playwright and its Chromium are installed
 * (`DARKP2P_CHROMIUM=1`); elsewhere it is reported as skipped.
 */
class ChromiumInteropTest {
    @Test
    fun `app and Chromium exchange files over the relay`() = runBlocking<Unit> {
        assumeTrue(System.getenv("DARKP2P_CHROMIUM") == "1", "set DARKP2P_CHROMIUM=1 to run against a real Chromium")
        WebSide(mapOf("RELAY_CAP_FREE_MB" to "200")).use { web ->
            val link = web.newLink()
            val page = web.page("p", link, browser = "chromium")
            page.waitLog("^Connected to signaling broker")
            AppPeer(link).use { app ->
                app.awaitReady()
                page.waitLog("^Peer key verified, session key confirmed")
                page.waitReady()
                for ((i, size) in listOf(0, 1, 16_000, 64 * 16_000 + 1, 256 * 16_000 + 1, 40 * 1024 * 1024).withIndex()) {
                    val out = fileBytes("out$i", size)
                    app.send("out-$i.bin", out)
                    page.accept()
                    assertEquals(sha256(out) to size, page.download(i), "app -> Chromium, $size bytes")
                    app.awaitNotice(Notice.DELIVERED, count = i + 1, timeoutMs = 120_000)

                    page.send("in-$i.bin", "in$i", size)
                    app.awaitNotice(Notice.SAVED, count = i + 1, timeoutMs = 120_000)
                    page.waitLog("^Delivered: in-$i\\.bin$", 120_000)
                    assertContentEquals(fileBytes("in$i", size), app.io.committed[i], "Chromium -> app, $size bytes")
                }
                assertEquals(emptyList(), page.errors())
                assertIs<RoomState.Ready>(app.state)
            }
        }
    }

    @Test
    fun `app and Chromium open a direct WebRTC DataChannel and exchange files`() = runBlocking<Unit> {
        assumeTrue(System.getenv("DARKP2P_CHROMIUM") == "1", "set DARKP2P_CHROMIUM=1 to run against a real Chromium")
        // A STUN address nothing answers on: this machine's own addresses are enough here.
        WebSide(mapOf("STUN_URLS" to "stun:127.0.0.1:9")).use { web ->
            for (appJoinsFirst in listOf(false, true)) {
                val link = web.newLink()
                val id = "p$appJoinsFirst"
                val factory = DesktopWebRtcFactory()
                var page: WebPage? = null
                if (!appJoinsFirst) page = web.page(id, link, forceWsRelay = false, browser = "chromium").also { it.waitLog("^Connected to signaling broker") }
                AppPeer(link, policy = com.darkp2p.protocol.LocalPolicy(), webRtc = factory).use { app ->
                    if (appJoinsFirst) {
                        app.await("waiting for a peer") { app.state == RoomState.WaitingForPeer }
                        page = web.page(id, link, forceWsRelay = false, browser = "chromium")
                    }
                    val p = page!!
                    val ready = app.awaitReady(30_000)
                    // Whoever joins second is the initiator and creates the DataChannel: both orders must work.
                    assertEquals(com.darkp2p.protocol.Transport.DIRECT, ready.transport, "app joined first: $appJoinsFirst")
                    assertEquals(null, ready.relayRemaining)
                    p.waitLog("^E2E encrypted P2P tunnel established")
                    p.waitLog("^Peer key verified, session key confirmed")
                    p.waitLabel("^DROP FILE HERE OR CLICK TO SELECT$")
                    assertEquals(false, app.has(Notice.RELAY_ACTIVE))

                    // Larger than the relay's free cap: this can only have gone over the DataChannel.
                    val out = fileBytes("direct-out", 60 * 1024 * 1024)
                    app.send("direct-out.bin", out)
                    p.accept()
                    assertEquals(sha256(out) to out.size, p.download(0), "app -> Chromium")
                    app.awaitNotice(Notice.DELIVERED, timeoutMs = 120_000)

                    p.send("direct-in.bin", "direct-in", 60 * 1024 * 1024)
                    app.awaitNotice(Notice.SAVED, timeoutMs = 120_000)
                    p.waitLog("^Delivered: direct-in\\.bin$", 120_000)
                    assertContentEquals(fileBytes("direct-in", 60 * 1024 * 1024), app.io.committed.single())
                    assertEquals(emptyList(), p.errors())
                    assertEquals("TUNNEL: DIRECT P2P (STUN)", p.state().str("mode"))
                }
                page!!.close()
            }
        }
    }
}
