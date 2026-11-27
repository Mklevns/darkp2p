package com.darkp2p.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the process running, and on the network, while an onion file drop is
 * offered.
 *
 * The drop itself lives in the app. Without a foreground service Android
 * suspends a background app within seconds: the link stopped answering the
 * moment the user switched to another app to send it, and a download in
 * progress was cut off. This service does nothing but hold the foreground
 * state, a wake lock and the notification, for exactly as long as the drop.
 *
 * The notification says only that a file is being shared, never which.
 */
public class DropService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_DROP, getString(R.string.darkp2p_channel_drop), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.darkp2p_channel_drop_description)
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // From the notification: the app takes the link down, which stops this service too.
            end()
            return START_NOT_STICKY
        }
        // Always, even if the drop is already over: a service asked for with
        // startForegroundService must show its notification once or the system kills the app.
        startForeground(NOTIFICATION_DROP, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val wanted = synchronized(lock) {
            started = true
            wanted
        }
        if (!wanted) {
            finish()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            // A download must not stall because the screen went off. Bounded, in case this service is never told to stop.
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "darkp2p:drop").apply {
                setReferenceCounted(false)
                acquire(MAX_HOLD_MS)
            }
        }
        // If the system kills the process, the drop is gone with it: there is nothing to come back for.
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, DropService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL_DROP)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.darkp2p_notification_drop_title))
            // Generic words only: nothing here tells a bystander what is being shared.
            .setContentText(getString(R.string.darkp2p_notification_drop_text))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.darkp2p_notification_drop_stop), stop).build())
            .build()
    }

    // Asks the owner of the drop to end it. The owner then calls stop(), which ends this service.
    private fun end() {
        val listener = onStopRequested
        if (listener != null) listener() else finish()
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Android 15: the daily time allowance of data-sync foreground services is used up.
    // The service must stop within a few seconds or the system raises an error.
    override fun onTimeout(startId: Int, fgsType: Int) {
        end()
        finish()
    }

    override fun onDestroy() {
        synchronized(lock) { started = false }
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    public companion object {
        private const val ACTION_STOP = "com.darkp2p.service.DROP_STOP"
        private const val CHANNEL_DROP = "drop"
        private const val NOTIFICATION_DROP = 3
        private const val MAX_HOLD_MS = 6 * 60 * 60 * 1000L

        // Set by the owner of the running drop; called on the main thread.
        @Volatile private var onStopRequested: (() -> Unit)? = null

        /**
         * Call when a drop starts, while the app is in front (a foreground service cannot be started from the background).
         *
         * @param onStop the user tapped "Stop sharing" in the notification, or the system's time allowance ran out.
         */
        public fun start(context: Context, onStop: () -> Unit) {
            onStopRequested = onStop
            synchronized(lock) { wanted = true }
            context.startForegroundService(Intent(context, DropService::class.java))
        }

        /** Call when the drop is over, however it ended. */
        public fun stop(context: Context) {
            onStopRequested = null
            // Stopping a service that was asked for but has not shown its notification yet is an
            // error to the system. In that case it stops itself as soon as it has (onStartCommand).
            val running = synchronized(lock) {
                wanted = false
                started
            }
            if (running) context.stopService(Intent(context, DropService::class.java))
        }

        private val lock = Any()
        private var wanted = false
        private var started = false
    }
}
