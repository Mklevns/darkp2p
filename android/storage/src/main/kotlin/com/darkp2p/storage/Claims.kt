package com.darkp2p.storage

import com.darkp2p.transport.CheckoutResult
import com.darkp2p.transport.ClaimResult
import com.darkp2p.transport.PendingClaim
import com.darkp2p.transport.ProTier
import com.darkp2p.transport.ServerApi
import kotlinx.coroutines.delay

/** When to ask the server for the token of a paid checkout. The page's figures, plus the background rule. */
public object ClaimPolicy {
    /** A claim older than this is given up: the server keeps tokens claimable for 48 hours. */
    public const val MAX_AGE_MS: Long = 48L * 3600 * 1000
    public const val FIRST_DELAY_MS: Long = 3_000
    public const val MAX_DELAY_MS: Long = 30_000

    /**
     * Without a payment ID the buyer has not come back from the checkout: it
     * may never be paid, so it is asked about far less often.
     */
    public const val UNCONFIRMED_MAX_DELAY_MS: Long = 5 * 60 * 1000

    /** One foreground poll run ends after this long; the background schedule carries on. */
    public const val FOREGROUND_RUN_MS: Long = 6L * 3600 * 1000

    /** In the background: at most one request every five minutes. */
    public const val BACKGROUND_MIN_DELAY_MS: Long = 5 * 60 * 1000

    public fun expired(claim: PendingClaim, now: Long): Boolean = now - claim.startedAt > MAX_AGE_MS

    /** The delay after [previous] (null before the first wait): doubling up to the cap for this kind of claim. */
    public fun nextForegroundDelay(previous: Long?, claim: PendingClaim): Long {
        val cap = if (claim.paymentId != null) MAX_DELAY_MS else UNCONFIRMED_MAX_DELAY_MS
        return if (previous == null) FIRST_DELAY_MS else minOf(previous * 2, cap)
    }

    /** The background delay honours a longer `Retry-After` and never goes below five minutes. */
    public fun backgroundDelay(retryAfterMs: Long?): Long = maxOf(BACKGROUND_MIN_DELAY_MS, retryAfterMs ?: 0)
}

public sealed interface ClaimEvent {
    /** The token was saved. It applies the next time a room on that server is joined. */
    public data class TokenSaved(val origin: String) : ClaimEvent

    /** The payment was less than the invoice: no token, and polling goes on in case it is completed. */
    public data object PartiallyPaid : ClaimEvent

    /** 48 hours passed. [hadPaymentId]: the buyer had paid or tried to, so this is worth telling them. */
    public data class Expired(val hadPaymentId: Boolean) : ClaimEvent

    /** A payment return arrived but this device has no record of the checkout. */
    public data object NoCheckoutRecord : ClaimEvent
}

/** What one background attempt decided. */
public sealed interface ClaimAttempt {
    /** Nothing (left) to claim: stop. */
    public data object Finished : ClaimAttempt
    public data class Again(val delayMs: Long) : ClaimAttempt
}

/**
 * Checkout and the claim that follows it, kept across restarts in the [Vault].
 *
 * @param api the payment endpoints of a server, over the current network profile (Tor included).
 */
public class Claims(
    private val vault: Vault,
    private val api: (origin: String) -> ServerApi,
    private val now: () -> Long = System::currentTimeMillis,
    private val onEvent: suspend (ClaimEvent) -> Unit = {},
) {
    /**
     * Starts a checkout on [origin]. The claim is saved before the invoice URL
     * is returned, so the token can still be claimed if the app is closed while
     * the buyer pays.
     */
    public suspend fun checkout(origin: String, tier: ProTier): CheckoutResult {
        val result = api(origin).checkout(tier)
        if (result is CheckoutResult.Created) {
            partialNotified = false
            vault.setPendingClaim(StoredClaim(origin, result.claim))
        }
        return result
    }

    /**
     * NOWPayments sent the buyer back with `?NP_id=<payment id>`, or the buyer
     * pasted the ID. It is added to the saved claim; without that saved order
     * ID (another device, cleared storage) nothing can be claimed.
     */
    public suspend fun onPaymentReturn(paymentId: String): Boolean {
        if (!PendingClaim.isNowPaymentsId(paymentId)) return false
        val stored = vault.pendingClaim()
        if (stored == null) {
            onEvent(ClaimEvent.NoCheckoutRecord)
            return false
        }
        if (stored.claim.paymentId != paymentId) vault.setPendingClaim(stored.copy(claim = stored.claim.copy(paymentId = paymentId)))
        return true
    }

    private var partialNotified = false

    // One request and its bookkeeping. Returns the server's Retry-After (or null) while still pending, or `done`.
    private suspend fun attempt(): Pair<Boolean, Long?> {
        val stored = vault.pendingClaim() ?: return true to null
        if (!stored.claim.claimable) return true to null
        if (ClaimPolicy.expired(stored.claim, now())) {
            vault.setPendingClaim(null)
            onEvent(ClaimEvent.Expired(hadPaymentId = stored.claim.paymentId != null))
            return true to null
        }
        when (val result = api(stored.origin).claim(stored.claim)) {
            is ClaimResult.Token -> {
                vault.setToken(stored.origin, result.token)
                vault.setPendingClaim(null)
                onEvent(ClaimEvent.TokenSaved(stored.origin))
                return true to null
            }
            is ClaimResult.Pending -> {
                if (result.partiallyPaid && !partialNotified) {
                    partialNotified = true
                    onEvent(ClaimEvent.PartiallyPaid)
                }
                return false to result.retryAfterMs
            }
        }
    }

    /** One attempt, for the background worker: what to do next. */
    public suspend fun attemptOnce(): ClaimAttempt {
        val (done, retryAfter) = attempt()
        return if (done) ClaimAttempt.Finished else ClaimAttempt.Again(ClaimPolicy.backgroundDelay(retryAfter))
    }

    /**
     * Polls while the app is open, on the page's schedule: 3 s, doubling to
     * 30 s (5 min before the buyer has come back), for at most six hours.
     * Returns when the claim is settled, expired, or the run is over.
     */
    public suspend fun pollInForeground() {
        val until = now() + ClaimPolicy.FOREGROUND_RUN_MS
        var delayMs: Long? = null
        while (true) {
            val (done, retryAfter) = attempt()
            if (done || now() >= until) return
            val claim = vault.pendingClaim()?.claim ?: return
            delayMs = ClaimPolicy.nextForegroundDelay(delayMs, claim)
            delay(minOf(maxOf(delayMs, retryAfter ?: 0), maxOf(0, until - now())))
        }
    }
}
