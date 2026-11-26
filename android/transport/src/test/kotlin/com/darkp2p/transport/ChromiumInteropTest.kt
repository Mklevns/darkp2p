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
}
