package com.darkp2p.storage

import com.darkp2p.transport.CheckoutResult
import com.darkp2p.transport.HttpClients
import com.darkp2p.transport.NetworkProfile
import com.darkp2p.transport.PendingClaim
import com.darkp2p.transport.ProTier
import com.darkp2p.transport.ServerApi
import com.darkp2p.transport.ServerEndpoint
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaimsTest {
    private val order = "pro-1d:${"ab".repeat(16)}"
    private val token = "1999999999.${"f".repeat(64)}"

    /** A server whose claim answers are scripted, one per request; the last one repeats. */
    private class Server(private val checkout: String?, private val claims: List<Triple<Int, String, String?>>) : AutoCloseable {
        private val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val claimRequests = CopyOnWriteArrayList<String>()

        init {
            http.createContext("/api/checkout") { ex ->
                ex.requestBody.readBytes()
                val body = (checkout ?: """{"error":"x"}""").toByteArray()
                ex.sendResponseHeaders(if (checkout != null) 200 else 502, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            http.createContext("/api/claim") { ex ->
                val (status, text, retryAfter) = claims[minOf(claimRequests.size, claims.size - 1)]
                claimRequests += ex.requestURI.query
                retryAfter?.let { ex.responseHeaders.add("Retry-After", it) }
                ex.sendResponseHeaders(status, text.length.toLong())
                ex.responseBody.use { it.write(text.toByteArray()) }
            }
            http.start()
        }

        val origin get() = "http://127.0.0.1:${http.address.port}"

        override fun close() = http.stop(0)
    }

    private fun vault() = Vault(File(Files.createTempDirectory("claims").toFile(), "vault.bin"), MemoryVaultCipher())

    private val client = HttpClients.create(NetworkProfile.Clearnet)

    private fun claims(vault: Vault, now: () -> Long, events: MutableList<ClaimEvent>) =
        Claims(vault, { origin -> ServerApi(client, ServerEndpoint(origin), now) }, now) { events += it }

    private val notYet = Triple(404, """{"ready":false}""", null as String?)
    private val ready = Triple(200, """{"ready":true,"token":"$token"}""", null as String?)

    @Test
    fun `checkout saves the claim before the invoice is shown, for the server it was made on`() = runTest {
        Server("""{"invoiceUrl":"https://pay.example/i/5","invoiceId":"5000000001","orderId":"$order"}""", listOf(notYet)).use { server ->
            val vault = vault()
            val events = ArrayList<ClaimEvent>()
            val created = assertIs<CheckoutResult.Created>(claims(vault, { 1000 }, events).checkout(server.origin, ProTier.DAY))
            assertEquals("https://pay.example/i/5", created.invoiceUrl)
            assertEquals(StoredClaim(server.origin, PendingClaim(order, 1000, invoiceId = "5000000001")), vault.pendingClaim())
        }
        Server(null, listOf(notYet)).use { server ->
            val vault = vault()
            assertEquals(CheckoutResult.Unavailable, claims(vault, { 1 }, ArrayList()).checkout(server.origin, ProTier.DAY))
            assertNull(vault.pendingClaim())
        }
    }

    @Test
    fun `foreground polling follows the page's schedule and stores the token for that server only`() = runTest {
        Server(null, listOf(notYet, notYet, notYet, notYet, notYet, ready)).use { server ->
            val vault = vault()
            val events = ArrayList<ClaimEvent>()
            vault.setPendingClaim(StoredClaim(server.origin, PendingClaim(order, 0, invoiceId = "5000000001", paymentId = "4000000001")))
            val start = currentTime
            claims(vault, { currentTime }, events).pollInForeground()
            // Five waits before the sixth request: 3, 6, 12, 24, 30 seconds (doubling, capped at 30).
            assertEquals(3_000L + 6_000 + 12_000 + 24_000 + 30_000, currentTime - start)
            assertEquals(6, server.claimRequests.size)
            assertTrue(server.claimRequests.all { it.startsWith("paymentId=4000000001&orderId=pro-1d") })
            assertEquals(token, vault.token(server.origin))
            assertNull(vault.token("https://darkp2p.com"))
            assertNull(vault.pendingClaim())
            assertEquals(listOf<ClaimEvent>(ClaimEvent.TokenSaved(server.origin)), events)
        }
    }

    @Test
    fun `a checkout the buyer has not returned from is asked about at most every five minutes`() = runTest {
        Server(null, listOf(notYet)).use { server ->
            val vault = vault()
            vault.setPendingClaim(StoredClaim(server.origin, PendingClaim(order, 0, invoiceId = "5000000001")))
            val start = currentTime
            claims(vault, { currentTime }, ArrayList()).pollInForeground()
            // The run ends after six hours without a token; the claim stays for the background schedule.
            assertEquals(ClaimPolicy.FOREGROUND_RUN_MS, currentTime - start)
            // 3, 6, 12, 24, 48, 96, 192 s, then every 300 s.
            assertEquals(8 + ((6 * 3600 - 381) / 300) + 1, server.claimRequests.size)
            assertTrue(server.claimRequests.all { it.startsWith("invoiceId=5000000001&") })
            assertEquals(order, vault.pendingClaim()!!.claim.orderId)
        }
    }

    @Test
    fun `retry-after stretches a wait, a partial payment is reported once, and polling goes on`() = runTest {
        val partial = Triple(200, """{"ready":false,"status":"partially_paid"}""", null as String?)
        Server(null, listOf(Triple(503, "{}", "120"), partial, partial, ready)).use { server ->
            val vault = vault()
            val events = ArrayList<ClaimEvent>()
            vault.setPendingClaim(StoredClaim(server.origin, PendingClaim(order, 0, paymentId = "4000000001")))
            val start = currentTime
            claims(vault, { currentTime }, events).pollInForeground()
            // 120 s (the server's wish, longer than 3 s), then 6 s, then 12 s.
            assertEquals(120_000L + 6_000 + 12_000, currentTime - start)
            assertEquals(listOf(ClaimEvent.PartiallyPaid, ClaimEvent.TokenSaved(server.origin)), events)
            assertEquals(token, vault.token(server.origin))
        }
    }

    @Test
    fun `a claim is given up 48 hours after the checkout`() = runTest {
        Server(null, listOf(notYet)).use { server ->
            for (paid in listOf(true, false)) {
                val vault = vault()
                val events = ArrayList<ClaimEvent>()
                vault.setPendingClaim(StoredClaim(server.origin, PendingClaim(order, 0, invoiceId = "7", paymentId = "8".takeIf { paid })))
                val before = server.claimRequests.size
                val c = claims(vault, { ClaimPolicy.MAX_AGE_MS + 1 }, events)
                assertEquals(ClaimAttempt.Finished, c.attemptOnce())
                assertEquals(before, server.claimRequests.size) // not even asked
                assertNull(vault.pendingClaim())
                assertEquals(listOf<ClaimEvent>(ClaimEvent.Expired(hadPaymentId = paid)), events)
            }
            // One millisecond earlier it is still asked.
            val vault = vault()
            vault.setPendingClaim(StoredClaim(server.origin, PendingClaim(order, 0, invoiceId = "7")))
            assertIs<ClaimAttempt.Again>(claims(vault, { ClaimPolicy.MAX_AGE_MS }, ArrayList()).attemptOnce())
        }
    }

    @Test
    fun `background attempts are at least five minutes apart and end with the token`() = runTest {
        Server(null, listOf(notYet, Triple(429, "{}", "60"), Triple(503, "{}", "900"), ready)).use { server ->
            val vault = vault()
            val events = ArrayList<ClaimEvent>()
            vault.setPendingClaim(StoredClaim(server.origin, PendingClaim(order, 0, paymentId = "4000000001")))
            val c = claims(vault, { 1 }, events)
            assertEquals(ClaimAttempt.Again(300_000), c.attemptOnce())
            assertEquals(ClaimAttempt.Again(300_000), c.attemptOnce()) // 60 s asked for: still five minutes
            assertEquals(ClaimAttempt.Again(600_000), c.attemptOnce()) // 900 s asked for, capped at ten minutes
            assertEquals(ClaimAttempt.Finished, c.attemptOnce())
            assertEquals(token, vault.token(server.origin))
            assertEquals(ClaimAttempt.Finished, c.attemptOnce()) // nothing left: no request
            assertEquals(4, server.claimRequests.size)
        }
        assertEquals(300_000, ClaimPolicy.backgroundDelay(null))
    }

    @Test
    fun `a payment return adds the payment ID to the saved claim, or says there is none`() = runTest {
        val vault = vault()
        val events = ArrayList<ClaimEvent>()
        val c = claims(vault, { 1 }, events)
        assertFalse(c.onPaymentReturn("4000000001"))
        assertEquals(listOf<ClaimEvent>(ClaimEvent.NoCheckoutRecord), events)

        vault.setPendingClaim(StoredClaim("https://darkp2p.com", PendingClaim(order, 1, invoiceId = "5")))
        assertTrue(c.onPaymentReturn("4000000001"))
        assertEquals("4000000001", vault.pendingClaim()!!.claim.paymentId)
        assertEquals("5", vault.pendingClaim()!!.claim.invoiceId)
        // Anything that is not a payment ID is ignored, whatever a link claims.
        for (bad in listOf("", "0", "12a", "1&orderId=x", "9".repeat(21))) assertFalse(c.onPaymentReturn(bad), bad)
        assertEquals("4000000001", vault.pendingClaim()!!.claim.paymentId)
    }
}
