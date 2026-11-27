package com.darkp2p.transport

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerApiTest {
    private val order = "pro-1d:${"ab".repeat(16)}"

    /** A scripted server: one canned answer per path. */
    private class Fake(private val answers: Map<String, Triple<Int, Map<String, String>, String>>) : AutoCloseable {
        private val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val seen = CopyOnWriteArrayList<String>()

        init {
            http.createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                seen += "${exchange.requestMethod} ${exchange.requestURI} ${exchange.requestHeaders.getFirst("Content-Type") ?: "-"} " +
                    "${exchange.requestHeaders.getFirst("User-Agent")} $body"
                val (status, headers, text) = answers[exchange.requestURI.path] ?: Triple(404, emptyMap(), "{\"ready\":false}")
                headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
                val bytes = text.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            http.start()
        }

        val api get() = ServerApi(HttpClients.create(NetworkProfile.Clearnet), ServerEndpoint("http://127.0.0.1:${http.address.port}"), now = { 1234L })

        override fun close() = http.stop(0)
    }

    private fun answer(status: Int, body: String, vararg headers: Pair<String, String>) = Triple(status, headers.toMap(), body)

    @Test
    fun `checkout returns the invoice and the claim to keep`() = runBlocking<Unit> {
        Fake(mapOf("/api/checkout" to answer(200, """{"invoiceUrl":"https://nowpayments.io/payment/?iid=5","invoiceId":"5000000001","orderId":"$order"}"""))).use { fake ->
            val created = assertIs<CheckoutResult.Created>(fake.api.checkout(ProTier.DAY))
            assertEquals("https://nowpayments.io/payment/?iid=5", created.invoiceUrl)
            assertEquals(PendingClaim(order, 1234L, invoiceId = "5000000001"), created.claim)
            assertTrue(created.claim.claimable)
            // Exactly what the server requires: POST, application/json, {"tier":...}; and no library version in the User-Agent.
            assertEquals("POST /api/checkout application/json; charset=utf-8 darkp2p-android {\"tier\":\"pro-1d\"}", fake.seen.single())
        }
        // No invoice ID yet (the server sends null): the claim waits for a payment ID.
        Fake(mapOf("/api/checkout" to answer(200, """{"invoiceUrl":"https://x.example/i","invoiceId":null,"orderId":"$order"}"""))).use { fake ->
            assertFalse(assertIs<CheckoutResult.Created>(fake.api.checkout(ProTier.MONTH)).claim.claimable)
            assertTrue("{\"tier\":\"pro-30d\"}" in fake.seen.single())
        }
    }

    @Test
    fun `checkout answers that are not usable are refused`() = runBlocking<Unit> {
        for (body in listOf(
            """{"invoiceUrl":"http://not-https.example/i","invoiceId":"5","orderId":"$order"}""",
            """{"invoiceUrl":"https://user:pw@x.example/i","invoiceId":"5","orderId":"$order"}""",
            """{"invoiceUrl":"javascript:alert(1)","invoiceId":"5","orderId":"$order"}""",
            """{"invoiceUrl":"https://x.example/i","invoiceId":"5","orderId":"pro-9d:${"ab".repeat(16)}"}""",
            """{"invoiceUrl":"https://x.example/i","invoiceId":"5"}""",
            """[]""", """not json""", "x".repeat(40_000),
        )) {
            Fake(mapOf("/api/checkout" to answer(200, body))).use { assertEquals(CheckoutResult.Unavailable, it.api.checkout(ProTier.DAY), body.take(60)) }
        }
        Fake(mapOf("/api/checkout" to answer(429, """{"error":"rate limited"}""", "Retry-After" to "60"))).use { assertEquals(CheckoutResult.RateLimited, it.api.checkout(ProTier.DAY)) }
        for (status in listOf(404, 415, 502, 503)) {
            Fake(mapOf("/api/checkout" to answer(status, """{"error":"x"}"""))).use { assertEquals(CheckoutResult.Unavailable, it.api.checkout(ProTier.DAY)) }
        }
    }

    @Test
    fun `claim - token, partial payment, retry-after and not yet`() = runBlocking<Unit> {
        val byPayment = PendingClaim(order, 1, invoiceId = "5000000001", paymentId = "4000000001")
        val byInvoice = PendingClaim(order, 1, invoiceId = "5000000001")

        Fake(mapOf("/api/claim" to answer(200, """{"ready":true,"token":"1999999999.${"a".repeat(64)}"}"""))).use { fake ->
            assertEquals("1999999999.${"a".repeat(64)}", assertIs<ClaimResult.Token>(fake.api.claim(byPayment)).token)
            fake.api.claim(byInvoice)
            // The payment ID is preferred; the order ID's colon is escaped as the page escapes it.
            assertTrue(fake.seen[0].startsWith("GET /api/claim?paymentId=4000000001&orderId=pro-1d%3A${"ab".repeat(16)} "), fake.seen[0])
            assertTrue(fake.seen[1].startsWith("GET /api/claim?invoiceId=5000000001&orderId=pro-1d%3A"), fake.seen[1])
        }
        Fake(mapOf("/api/claim" to answer(200, """{"ready":false,"status":"partially_paid"}"""))).use {
            assertEquals(ClaimResult.Pending(null, partiallyPaid = true), it.api.claim(byPayment))
        }
        for (body in listOf("""{"ready":false}""", """{"ready":true}""", """{"ready":true,"token":""}""", """{"ready":"yes","token":"t"}""", "nonsense")) {
            Fake(mapOf("/api/claim" to answer(200, body))).use { assertEquals(ClaimResult.Pending(null, false), it.api.claim(byPayment), body) }
        }
        Fake(mapOf("/api/claim" to answer(404, """{"ready":false}"""))).use { assertEquals(ClaimResult.Pending(null, false), it.api.claim(byPayment)) }
        Fake(mapOf("/api/claim" to answer(429, """{"ready":false}""", "Retry-After" to "60"))).use { assertEquals(ClaimResult.Pending(60_000, false), it.api.claim(byPayment)) }
        Fake(mapOf("/api/claim" to answer(503, """{"ready":false}""", "Retry-After" to "30"))).use { assertEquals(ClaimResult.Pending(30_000, false), it.api.claim(byPayment)) }
        // A huge or senseless Retry-After cannot park the claim for long, or at all.
        Fake(mapOf("/api/claim" to answer(503, "{}", "Retry-After" to "86400"))).use { assertEquals(ClaimResult.Pending(ServerApi.MAX_RETRY_AFTER_MS, false), it.api.claim(byPayment)) }
        for (bad in listOf("soon", "-5", "0", "Infinity")) {
            Fake(mapOf("/api/claim" to answer(503, "{}", "Retry-After" to bad))).use { assertEquals(ClaimResult.Pending(null, false), it.api.claim(byPayment), bad) }
        }
    }

    @Test
    fun `claims are validated before anything is sent`() = runBlocking<Unit> {
        assertFalse(PendingClaim(order, 1).claimable)
        assertFalse(PendingClaim("pro-1d:xyz", 1, paymentId = "5").claimable)
        assertFalse(PendingClaim(order, 1, paymentId = "05").claimable)
        assertFalse(PendingClaim(order, 1, paymentId = "5&orderId=other").claimable)
        assertTrue(PendingClaim(order, 1, paymentId = "nonsense", invoiceId = "7").claimable) // falls back to the invoice ID
        Fake(emptyMap()).use { fake ->
            assertFailsWith<IllegalArgumentException> { fake.api.claim(PendingClaim(order, 1)) }
            assertEquals(0, fake.seen.size)
            fake.api.claim(PendingClaim(order, 1, paymentId = "nonsense", invoiceId = "7"))
            assertTrue(fake.seen.single().startsWith("GET /api/claim?invoiceId=7&"))
        }
        assertFalse(order in PendingClaim(order, 1, paymentId = "5").toString())
        assertFalse("secret" in ClaimResult.Token("secret").toString())
    }

    @Test
    fun `against the real server - an unknown claim is not ready, and checkout without the payment provider is unavailable`() = runBlocking<Unit> {
        WebSide().use { web ->
            val api = ServerApi(HttpClients.create(NetworkProfile.Clearnet), ServerEndpoint(web.origin))
            assertEquals(ClaimResult.Pending(null, false), api.claim(PendingClaim(order, 1, invoiceId = "5000000001")))
            // The test server's payment provider is unreachable: the server answers 502.
            assertEquals(CheckoutResult.Unavailable, api.checkout(ProTier.DAY))
            // Nothing identifying was logged by the server for either request.
            assertFalse(order in web.call("serverLog").str("log"))
        }
    }

    @Test
    fun `a server that is down is not ready, not an exception`() = runBlocking<Unit> {
        val api = ServerApi(HttpClients.create(NetworkProfile.Clearnet), ServerEndpoint("http://127.0.0.1:9"))
        assertEquals(ClaimResult.Pending(null, false), api.claim(PendingClaim(order, 1, paymentId = "5")))
        assertEquals(CheckoutResult.Unavailable, api.checkout(ProTier.DAY))
    }
}
