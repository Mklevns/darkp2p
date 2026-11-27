package com.darkp2p.app

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.darkp2p.protocol.LocalPolicy
import com.darkp2p.service.ContentFileStore
import com.darkp2p.storage.ClaimEvent
import com.darkp2p.storage.ClaimRuntime
import com.darkp2p.storage.ClaimWorker
import com.darkp2p.storage.Claims
import com.darkp2p.storage.KeystoreVaultCipher
import com.darkp2p.storage.Vault
import com.darkp2p.transport.HttpClients
import com.darkp2p.transport.NetworkProfile
import com.darkp2p.transport.Orbot
import com.darkp2p.transport.ServerApi
import com.darkp2p.transport.ServerEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

/** The server rooms are created on, and the only one joined without asking first. */
const val DEFAULT_ORIGIN = "https://darkp2p.com"

/** The user's choices. None of them is a secret; secrets live in the vault. */
data class Prefs(
    val tor: Boolean = false,
    val forceRelay: Boolean = false,
    val hideIp: Boolean = false,
    val chat: Boolean = true,
    val secure: Boolean = true,
    val lock: Boolean = false,
) {
    /** In Tor mode WebRTC does not exist, whatever the other switches say. */
    val policy: LocalPolicy get() = LocalPolicy(forceWsRelay = forceRelay, hideIp = hideIp, webRtcAvailable = !tor, chat = chat)
}

class Settings(context: Context) {
    private val store = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _prefs = MutableStateFlow(
        Prefs(
            tor = store.getBoolean("tor", false), forceRelay = store.getBoolean("forceRelay", false), hideIp = store.getBoolean("hideIp", false),
            chat = store.getBoolean("chat", true), secure = store.getBoolean("secure", true), lock = store.getBoolean("lock", false),
        ),
    )
    val prefs: StateFlow<Prefs> get() = _prefs

    fun update(change: (Prefs) -> Prefs) {
        val next = change(_prefs.value)
        _prefs.value = next
        store.edit {
            putBoolean("tor", next.tor).putBoolean("forceRelay", next.forceRelay).putBoolean("hideIp", next.hideIp)
            putBoolean("chat", next.chat).putBoolean("secure", next.secure).putBoolean("lock", next.lock)
        }
    }
}

/** What the whole app shares. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(app)
    val vault = Vault(File(app.filesDir, "vault.bin"), KeystoreVaultCipher())
    val store = ContentFileStore(app, vault)

    /** The SOCKS port Orbot last reported. */
    @Volatile var orbotPort: Int = Orbot.DEFAULT_SOCKS_PORT

    private val _claimEvents = MutableSharedFlow<ClaimEvent>(extraBufferCapacity = 16)
    val claimEvents: SharedFlow<ClaimEvent> get() = _claimEvents

    /**
     * How payment requests travel right now. In Tor mode this is always a Tor
     * profile: if Orbot is not running the request fails, it never goes out directly.
     */
    fun profileNow(): NetworkProfile =
        if (settings.prefs.value.tor) NetworkProfile.Tor(InetSocketAddress(InetAddress.getLoopbackAddress(), orbotPort)) else NetworkProfile.Clearnet

    val claims = Claims(
        vault,
        api = { origin ->
            // An onion server is only ever asked through Tor.
            val profile = if (ServerEndpoint(origin).isOnion) NetworkProfile.Tor(InetSocketAddress(InetAddress.getLoopbackAddress(), orbotPort)) else profileNow()
            ServerApi(HttpClients.create(profile), ServerEndpoint(origin))
        },
        onEvent = { _claimEvents.emit(it) },
    )
}

class DarkP2PApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        ClaimRuntime.claims = graph.claims
        // A checkout from an earlier run may still be waiting for its token.
        graph.scope.launch {
            if (graph.vault.pendingClaim()?.claim?.claimable == true) ClaimWorker.schedule(this@DarkP2PApp)
        }
    }
}
