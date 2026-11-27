package com.darkp2p.transport

import com.darkp2p.protocol.Json
import com.darkp2p.protocol.JsonParseException
import com.darkp2p.protocol.JsonValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI

public enum class ProTier(public val wire: String) { DAY("pro-1d"), MONTH("pro-30d") }

/**
 * A checkout that was started and not yet turned into a token. The same record
 * the page keeps as `darkp2p_pending_claim`.
 *
 * [orderId] is the claim secret: the server gives the token only to a claim
 * that presents it. A claim needs [paymentId] or [invoiceId] as well.
 */
public data class PendingClaim(val orderId: String, val startedAt: Long, val invoiceId: String? = null, val paymentId: String? = null) {
    public val claimable: Boolean get() = isOrderId(orderId) && (paymentId?.let(::isNowPaymentsId) == true || invoiceId?.let(::isNowPaymentsId) == true)

    override fun toString(): String = "PendingClaim(***)"

    public companion object {
        private val ORDER_ID = Regex("^(pro-1d|pro-30d):[a-f0-9]{32}$")
        private val NP_ID = Regex("^[1-9][0-9]{0,19}$")

        public fun isOrderId(value: String): Boolean = ORDER_ID.matches(value)

        /** NOWPayments payment and invoice IDs are decimal integers. */
        public fun isNowPaymentsId(value: String): Boolean = NP_ID.matches(value)
    }
}

public sealed interface CheckoutResult {
    /** Open [invoiceUrl] to pay; keep [claim] until its token is claimed. */
    public class Created(public val invoiceUrl: String, public val claim: PendingClaim) : CheckoutResult
    public data object RateLimited : CheckoutResult

    /** Checkout is switched off on this server, or the payment provider did not answer. */
    public data object Unavailable : CheckoutResult
}

public sealed interface ClaimResult {
    public class Token(public val token: String) : ClaimResult {
        override fun toString(): String = "Token(***)"
    }

    /**
     * Not ready (or not known to the server): ask again later.
     * @param retryAfterMs the server's `Retry-After`, capped at ten minutes; null if it gave none.
     * @param partiallyPaid the payment arrived but was less than the invoice.
     */
    public data class Pending(val retryAfterMs: Long?, val partiallyPaid: Boolean) : ClaimResult
}

/** The server's two payment endpoints. Uses the room's HTTP client, so it follows Tor mode. */
public class ServerApi(private val client: OkHttpClient, private val endpoint: ServerEndpoint, private val now: () -> Long = System::currentTimeMillis) {
    /** `POST /api/checkout {tier}` -> `{invoiceUrl, invoiceId, orderId}`. */
    public suspend fun checkout(tier: ProTier): CheckoutResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint.url("/api/checkout"))
            // Exactly this type: the server refuses anything else.
            .post("""{"tier":"${tier.wire}"}""".toRequestBody(JSON))
            .header("Cache-Control", "no-store")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 429) return@use CheckoutResult.RateLimited
                val body = response.takeIf { it.isSuccessful }?.let(::jsonObject) ?: return@use CheckoutResult.Unavailable
                val invoiceUrl = body["invoiceUrl"]?.stringOrNull?.takeIf(::isHttpsUrl) ?: return@use CheckoutResult.Unavailable
                val orderId = body["orderId"]?.stringOrNull?.takeIf(PendingClaim::isOrderId) ?: return@use CheckoutResult.Unavailable
                val invoiceId = body["invoiceId"]?.stringOrNull?.takeIf(PendingClaim::isNowPaymentsId)
                CheckoutResult.Created(invoiceUrl, PendingClaim(orderId, now(), invoiceId))
            }
        } catch (e: IOException) {
            CheckoutResult.Unavailable
        }
    }

    /** `GET /api/claim?paymentId=…&orderId=…` (or `invoiceId=`). One attempt; the caller schedules the next. */
    public suspend fun claim(claim: PendingClaim): ClaimResult = withContext(Dispatchers.IO) {
        require(claim.claimable) { "claim has no usable ID" }
        // Every value matched a strict pattern above: digits, or the order ID's [a-z0-9:-].
        val id = claim.paymentId?.takeIf(PendingClaim::isNowPaymentsId)?.let { "paymentId=$it" } ?: "invoiceId=${claim.invoiceId}"
        val request = Request.Builder()
            .url(endpoint.url("/api/claim?$id&orderId=${claim.orderId.replace(":", "%3A")}"))
            .header("Cache-Control", "no-store")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = jsonObject(response) ?: return@use ClaimResult.Pending(null, false)
                    val token = body["token"]?.stringOrNull
                    if (body["ready"] == JsonValue.Bool(true) && !token.isNullOrEmpty()) return@use ClaimResult.Token(token)
                    return@use ClaimResult.Pending(null, partiallyPaid = body["status"]?.stringOrNull == "partially_paid")
                }
                if (response.code == 429 || response.code == 503) {
                    val seconds = response.header("Retry-After")?.trim()?.toDoubleOrNull()
                    if (seconds != null && seconds > 0 && !seconds.isInfinite()) {
                        return@use ClaimResult.Pending(minOf((seconds * 1000).toLong(), MAX_RETRY_AFTER_MS), false)
                    }
                }
                // 404 means not settled yet (or not yet visible): keep polling.
                ClaimResult.Pending(null, false)
            }
        } catch (e: IOException) {
            ClaimResult.Pending(null, false)
        }
    }

    private fun jsonObject(response: Response): JsonValue.Obj? {
        val text = response.body.source().let { source ->
            // The server's answers are tiny; a larger one is not the server's.
            if (!source.request(MAX_BODY_BYTES + 1)) source.buffer.readUtf8() else return null
        }
        return try {
            Json.parse(text) as? JsonValue.Obj
        } catch (e: JsonParseException) {
            null
        }
    }

    private fun isHttpsUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
    } catch (e: Exception) {
        false
    }

    public companion object {
        /** The page's `CLAIM_MAX_RETRY_AFTER_MS`. */
        public const val MAX_RETRY_AFTER_MS: Long = 10 * 60 * 1000
        private const val MAX_BODY_BYTES = 16L * 1024
        private val JSON = "application/json".toMediaType()
    }
}
