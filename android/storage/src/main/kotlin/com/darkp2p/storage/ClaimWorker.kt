package com.darkp2p.storage

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Background claim polling. WorkManager's periodic work cannot run more often
 * than every 15 minutes, so this is a chain of one-time requests: each run
 * makes one attempt and, if the claim is still open, schedules the next one
 * no sooner than five minutes later ([ClaimPolicy.backgroundDelay]). The chain
 * ends with the token, or 48 hours after the checkout.
 */
public class ClaimWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // The app installs this at startup. Without it (a process started only for
        // this work, before the app has set itself up) there is nothing safe to do:
        // above all, no request outside the network profile the user chose.
        val claims = ClaimRuntime.claims ?: return Result.success()
        when (val next = claims.attemptOnce()) {
            ClaimAttempt.Finished -> Unit
            is ClaimAttempt.Again -> schedule(applicationContext, next.delayMs)
        }
        return Result.success()
    }

    public companion object {
        private const val UNIQUE_NAME = "darkp2p-claim"

        /** Schedules the next attempt, replacing any that is waiting. */
        public fun schedule(context: Context, delayMs: Long = ClaimPolicy.BACKGROUND_MIN_DELAY_MS) {
            val request = OneTimeWorkRequestBuilder<ClaimWorker>()
                .setInitialDelay(maxOf(delayMs, ClaimPolicy.BACKGROUND_MIN_DELAY_MS), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        public fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}

/** Where the app hands its [Claims] to background work. */
public object ClaimRuntime {
    @Volatile
    public var claims: Claims? = null
}
