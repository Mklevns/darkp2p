package com.darkp2p.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Orbot (the Tor Project's Android app) as the app's Tor: ask it to start,
 * learn its SOCKS port, and check the port answers before trusting it.
 *
 * Uses Orbot's public broadcast interface. Not yet exercised on a device.
 */
public object Orbot {
    public const val PACKAGE: String = "org.torproject.android"
    public const val DEFAULT_SOCKS_PORT: Int = 9050

    private const val ACTION_START = "org.torproject.android.intent.action.START"
    private const val ACTION_STATUS = "org.torproject.android.intent.action.STATUS"
    private const val EXTRA_PACKAGE_NAME = "org.torproject.android.intent.extra.PACKAGE_NAME"
    private const val EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS"
    private const val EXTRA_SOCKS_PROXY_PORT = "org.torproject.android.intent.extra.SOCKS_PROXY_PORT"
    private const val STATUS_ON = "ON"

    public sealed interface Status {
        /** Tor is running; [socksPort] is on this device's loopback interface. */
        public data class On(val socksPort: Int) : Status
        public data object Starting : Status
        public data object Off : Status
    }

    public fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Asks Orbot to start Tor (if it is not running) and to report its status to this app. */
    public fun requestStart(context: Context) {
        context.sendBroadcast(Intent(ACTION_START).setPackage(PACKAGE).putExtra(EXTRA_PACKAGE_NAME, context.packageName))
    }

    /** Orbot's status reports, for as long as the flow is collected. */
    public fun status(context: Context): Flow<Status> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val port = intent.getIntExtra(EXTRA_SOCKS_PROXY_PORT, -1)
                trySend(
                    when (intent.getStringExtra(EXTRA_STATUS)) {
                        STATUS_ON -> if (port in 1..65535) Status.On(port) else Status.Starting
                        "STARTING" -> Status.Starting
                        else -> Status.Off
                    },
                )
            }
        }
        // The report comes from another app, so the receiver has to be exported.
        // It only reads a status word and a port number, and the port is probed before use.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(ACTION_STATUS), Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(ACTION_STATUS))
        }
        awaitClose { context.unregisterReceiver(receiver) }
    }

    /**
     * The Tor profile for [port] on loopback, or null if no SOCKS5 proxy answers
     * there. Anyone can send the status broadcast; this is what makes a port
     * number trustworthy enough to use, and a wrong one fails closed anyway
     * (a non-SOCKS listener cannot complete the handshake).
     */
    public suspend fun profile(port: Int, isolation: String?): NetworkProfile.Tor? = withContext(Dispatchers.IO) {
        val address = InetSocketAddress(InetAddress.getLoopbackAddress(), port)
        try {
            Socket().use { socket ->
                socket.connect(address, 2_000)
                socket.soTimeout = 2_000
                socket.getOutputStream().apply {
                    write(byteArrayOf(5, 1, 0)) // SOCKS5 greeting, "no authentication"
                    flush()
                }
                val reply = ByteArray(2)
                val read = socket.getInputStream().read(reply)
                if (read == 2 && reply[0].toInt() == 5) NetworkProfile.Tor(address, isolation) else null
            }
        } catch (e: IOException) {
            null
        }
    }
}
