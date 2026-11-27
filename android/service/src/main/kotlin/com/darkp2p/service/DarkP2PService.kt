package com.darkp2p.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.darkp2p.protocol.LocalPolicy
import com.darkp2p.protocol.RoomLink
import com.darkp2p.protocol.RoomState
import com.darkp2p.storage.Vault
import com.darkp2p.transport.HttpClients
import com.darkp2p.transport.NetworkProfile
import com.darkp2p.transport.PlatformWebRtc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps a room alive while the app is not on screen: a `dataSync` foreground
 * service with an ongoing notification.
 *
 *  - Wake and Wi-Fi locks are held only while bytes move ([ServicePolicy.holdLocks]).
 *  - After ten idle minutes the room is left and the service stops, leaving a quiet note.
 *  - Android 15 limits `dataSync` services to six hours a day: [onTimeout] leaves the room cleanly.
 *  - The notification never shows a file name, a room or a message.
 *
 * Not yet exercised on a device.
 */
public class DarkP2PService : Service() {
    public inner class LocalBinder : Binder() {
        public val service: DarkP2PService get() = this@DarkP2PService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null

    private val _controller = MutableStateFlow<RoomController?>(null)

    /** The room this service holds, if any. */
    public val controller: StateFlow<RoomController?> get() = _controller

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastText = 0

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ROOM, getString(R.string.darkp2p_channel_room), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.darkp2p_channel_room_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, getString(R.string.darkp2p_channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.darkp2p_channel_status_description)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEAVE) {
            leave()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ROOM, notification(R.string.darkp2p_notification_room_open), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        // A room cannot be restored after the process dies (its key is only in memory): do not restart.
        return START_NOT_STICKY
    }

    /**
     * Joins [link]. The app starts this service (`startForegroundService`) from a
     * visible screen first, binds, then calls this.
     *
     * @param profile how to reach the server. An onion room requires [NetworkProfile.Tor].
     * @param token the Pro token **for this link's server**, or empty.
     */
    public fun join(link: RoomLink, profile: NetworkProfile, policy: LocalPolicy, token: String, vault: Vault?, beforeJoin: suspend () -> Unit = {}): RoomController {
        leaveRoom()
        val store = ContentFileStore(this, vault)
        // WebRTC exists only on the clearnet profile; in Tor mode the room is never given one.
        val webRtc = if (profile == NetworkProfile.Clearnet && !link.isOnion) PlatformWebRtc.factory(this) else null
        val room = RoomController(link, HttpClients.create(profile), store, scope, policy, token, webRtc, beforeJoin = {
            store.sweep()
            beforeJoin()
        }, clock = SystemClock::elapsedRealtime)
        _controller.value = room
        room.start()
        ticker = scope.launch {
            while (true) {
                tick(room)
                delay(1_000)
            }
        }
        return room
    }

    private fun tick(room: RoomController) {
        val now = SystemClock.elapsedRealtime()
        val activity = room.activity.value
        setLocks(ServicePolicy.holdLocks(activity, now))
        if (ServicePolicy.shouldStop(activity, now)) {
            stopWithNote(R.string.darkp2p_notification_idle_text)
            return
        }
        val snapshot = room.room.value
        val text = when {
            room.offer.value != null -> R.string.darkp2p_notification_offer
            snapshot.incoming != null -> R.string.darkp2p_notification_receiving
            snapshot.outgoing != null -> R.string.darkp2p_notification_sending
            snapshot.state is RoomState.Ready -> R.string.darkp2p_notification_connected
            else -> R.string.darkp2p_notification_waiting
        }
        val transfer = snapshot.incoming ?: snapshot.outgoing
        val percent = transfer?.takeIf { it.size > 0 && it.phase != "offered" }?.let { (it.bytesDone * 100 / it.size).toInt() }
        // At most once a second, and only when something visible changed.
        val shown = text * 1000 + (percent ?: -1)
        if (shown != lastText) {
            lastText = shown
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ROOM, notification(text, percent))
        }
    }

    private fun notification(text: Int, percent: Int? = null): Notification {
        val leave = PendingIntent.getService(
            this, 0, Intent(this, DarkP2PService::class.java).setAction(ACTION_LEAVE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL_ROOM)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.darkp2p_notification_room_open))
            // Generic words only: nothing here tells a bystander what is being sent, or to whom.
            .setContentText(getString(text))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .apply { if (percent != null) setProgress(100, percent, false) }
            .addAction(Notification.Action.Builder(null, getString(R.string.darkp2p_notification_leave), leave).build())
            .build()
    }

    private fun setLocks(hold: Boolean) {
        if (hold) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "darkp2p:transfer").apply {
                    setReferenceCounted(false)
                }
            }
            // Renewed every second while bytes move; if this service stops ticking, the lock runs out by itself.
            wakeLock?.acquire(LOCK_TIMEOUT_MS)
            if (wifiLock == null) {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wifi.createWifiLock(mode, "darkp2p:transfer").apply { setReferenceCounted(false) }
            }
            wifiLock?.takeUnless { it.isHeld }?.acquire()
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
        }
    }

    private fun leaveRoom() {
        ticker?.cancel()
        ticker = null
        setLocks(false)
        _controller.value?.close()
        _controller.value = null
    }

    /** Leaves the room and stops the service. */
    public fun leave() {
        leaveRoom()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopWithNote(text: Int) {
        leave()
        val note = Notification.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(R.string.darkp2p_notification_idle_title))
            .setContentText(getString(text))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_STATUS, note)
    }

    // Android 15: the daily time allowance of data-sync foreground services is used up.
    // The service must stop within a few seconds or the system raises an error.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopWithNote(R.string.darkp2p_notification_timeout_text)
    }

    override fun onDestroy() {
        leaveRoom()
        scope.cancel()
        super.onDestroy()
    }

    public companion object {
        public const val ACTION_LEAVE: String = "com.darkp2p.service.LEAVE"
        private const val CHANNEL_ROOM = "room"
        private const val CHANNEL_STATUS = "status"
        private const val NOTIFICATION_ROOM = 1
        private const val NOTIFICATION_STATUS = 2
        private const val LOCK_TIMEOUT_MS = 30_000L
    }
}
