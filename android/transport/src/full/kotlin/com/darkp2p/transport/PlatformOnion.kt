package com.darkp2p.transport

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.freehaven.tor.control.RawEventListener
import net.freehaven.tor.control.TorControlConnection
import org.torproject.jni.TorService
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Onion services for this build: a Tor inside the app (the Guardian Project's tor-android). */
public object PlatformOnion {
    public const val AVAILABLE: Boolean = true

    public fun host(context: Context): OnionHost? = EmbeddedTorHost(context.applicationContext)
}

/**
 * The one Tor of this process.
 *
 * It is started the first time an onion service is wanted and then **never
 * stopped**: Tor's native code does not survive being shut down inside a
 * process that carries on (on a Pixel it aborted the whole app, with one of
 * its worker threads waiting on a lock the shutdown had destroyed). Between
 * uses it is told to go dormant instead, which closes its circuits and stops
 * its network activity, and it ends with the process.
 *
 * It serves onion services only. Rooms in Tor mode still go through Orbot.
 */
private object EmbeddedTor {
    private val lock = Any()
    private var bound: CompletableDeferred<TorService>? = null
    private var users = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(lock) { bound }?.complete((binder as TorService.LocalBinder).service)
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    /** Tor, running and with its first circuits built. Each call must be matched by one [release]. */
    suspend fun acquire(context: Context, onProgress: (Int) -> Unit): TorControlConnection {
        val service = synchronized(lock) {
            users += 1
            bound ?: CompletableDeferred<TorService>().also {
                bound = it
                if (!context.bindService(Intent(context, TorService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                    bound = null
                    users -= 1
                    throw IOException("Tor could not be started")
                }
            }
        }
        val tor = withTimeout(BIND_TIMEOUT_MS) { service.await() }
        return withTimeout(BOOTSTRAP_TIMEOUT_MS) { awaitBootstrap(tor, onProgress) }
    }

    private suspend fun awaitBootstrap(tor: TorService, onProgress: (Int) -> Unit): TorControlConnection {
        var woken = false
        while (true) {
            val control = tor.torControlConnection
            if (control != null) {
                if (!woken) {
                    // Wakes a Tor that was put to sleep after the last use; harmless otherwise.
                    signal(control, "ACTIVE")
                    woken = true
                }
                val phase = try {
                    control.getInfo("status/bootstrap-phase")
                } catch (e: IOException) {
                    null
                }
                val percent = PROGRESS.find(phase ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0
                onProgress(percent)
                if (percent >= 100) return control
            }
            delay(POLL_MS)
        }
    }

    /** One user less. With none left, Tor is put to sleep (not stopped: see the class comment). */
    fun release(control: TorControlConnection?) {
        val idle = synchronized(lock) {
            users = maxOf(0, users - 1)
            users == 0
        }
        if (idle && control != null) signal(control, "DORMANT")
    }

    private fun signal(control: TorControlConnection, name: String) {
        try {
            control.signal(name)
        } catch (e: IOException) {
            // An older Tor without this signal, or a control connection that went away: nothing depends on it.
        }
    }

    private const val BIND_TIMEOUT_MS = 20_000L
    private const val BOOTSTRAP_TIMEOUT_MS = 180_000L
    private const val POLL_MS = 500L
    private val PROGRESS = Regex("""PROGRESS=(\d+)""")
}

/**
 * One ephemeral onion service (Tor's `ADD_ONION` with `DiscardPK`): its key
 * exists only in Tor's memory, is never written to disk and cannot be brought
 * back, so every session gets an address that was never used before and never
 * will be again. Closing removes the service from Tor at once.
 */
internal class EmbeddedTorHost(private val context: Context) : OnionHost {
    private val closed = AtomicBoolean()
    private val released = AtomicBoolean()

    @Volatile private var control: TorControlConnection? = null

    @Volatile private var serviceId: String? = null

    @Volatile private var acquired = false

    override suspend fun publish(localPort: Int, onProgress: (Int) -> Unit): String = withContext(Dispatchers.IO) {
        try {
            acquired = true
            val tor = EmbeddedTor.acquire(context, onProgress)
            control = tor

            // An address nobody can look up yet is no use: wait until Tor has handed the
            // service's descriptor to at least one directory before saying it is there.
            val uploaded = CompletableDeferred<Unit>()
            val watcher = RawEventListener { keyword, data ->
                val id = serviceId
                if (keyword == "HS_DESC" && id != null && data.startsWith("UPLOADED $id")) uploaded.complete(Unit)
            }
            tor.addRawEventListener(watcher)
            try {
                // TorService itself listens for STATUS_CLIENT; setting events replaces the list.
                tor.setEvents(listOf("STATUS_CLIENT", "HS_DESC"))
                val reply = tor.addOnion("NEW:ED25519-V3", mapOf(ONION_PORT to "${FileDropServer.HOST}:$localPort"), listOf("DiscardPK"))
                val id = reply["onionAddress"]?.takeIf { ONION_ID.matches(it) } ?: throw IOException("Tor did not return an onion address")
                serviceId = id
                if (closed.get()) {
                    close()
                    throw IOException("stopped")
                }
                withTimeout(PUBLISH_TIMEOUT_MS) { uploaded.await() }
                "$id.onion"
            } finally {
                tor.removeRawEventListener(watcher)
            }
        } catch (e: TimeoutCancellationException) {
            close()
            throw IOException("Tor did not start, or could not announce the address, in time")
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    override fun close() {
        closed.set(true)
        val id = serviceId
        serviceId = null
        val tor = control
        val release = acquired && released.compareAndSet(false, true)
        if (id == null && !release) return
        // Off the caller's thread: the control connection is a socket.
        Thread({
            if (id != null) {
                try {
                    tor?.delOnion(id)
                } catch (e: IOException) {
                    // The control connection is gone, and Tor's memory of the service with it.
                }
            }
            if (release) EmbeddedTor.release(tor)
        }, "onion-close").apply { isDaemon = true }.start()
    }

    private companion object {
        const val ONION_PORT = 80
        const val PUBLISH_TIMEOUT_MS = 180_000L
        val ONION_ID = Regex("""[a-z2-7]{56}""")
    }
}
