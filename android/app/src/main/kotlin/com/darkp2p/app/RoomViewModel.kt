package com.darkp2p.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darkp2p.protocol.RoomLink
import com.darkp2p.service.DarkP2PService
import com.darkp2p.service.FileSource
import com.darkp2p.service.RoomController
import com.darkp2p.service.SaveTarget
import com.darkp2p.storage.ClaimEvent
import com.darkp2p.storage.ClaimWorker
import com.darkp2p.storage.StoredClaim
import com.darkp2p.transport.CheckoutResult
import com.darkp2p.transport.NetworkProfile
import com.darkp2p.transport.Orbot
import com.darkp2p.transport.ProTier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

enum class Screen { HOME, ROOM, SETTINGS, PRO, SCANNER }

/** The app's state above any one screen: which room is open, what is waiting to be sent, what the Pro screen shows. */
class RoomViewModel(app: Application) : AndroidViewModel(app) {
    val graph: AppGraph = (app as DarkP2PApp).graph
    private val context: Context get() = getApplication()

    var screen by mutableStateOf(Screen.HOME)

    /** A message for the user (a string resource), shown once. */
    var message by mutableStateOf<Int?>(null)

    /** A link to a server other than the default one, waiting for the user's yes. */
    var confirm by mutableStateOf<RoomLink?>(null)
        private set

    var link by mutableStateOf<RoomLink?>(null)
        private set

    private val _controller = MutableStateFlow<RoomController?>(null)
    val controller: StateFlow<RoomController?> get() = _controller

    /** What happened in the room, newest last: (text, file name or null). */
    val activity = mutableStateListOf<Pair<Int, String?>>()

    /** Files shared into the app before a room was open. Opened already, so they stay readable. */
    val waitingShares = mutableStateListOf<FileSource>()

    // ---- Pro ----
    var pendingClaim by mutableStateOf<StoredClaim?>(null)
        private set
    var savedToken by mutableStateOf<String?>(null)
        private set

    /** A payment link that could not be opened (Tor mode without Tor Browser), offered for copying. */
    var invoiceToCopy by mutableStateOf<String?>(null)

    // The bound service, for as long as a room is open. It is the app's own service and is
    // let go as soon as the room ends (onRoomGone, leaveRoom, onCleared), so nothing outlives it.
    @SuppressLint("StaticFieldLeak")
    private var service: DarkP2PService? = null
    private var connection: ServiceConnection? = null
    private var roomJobs: Job? = null
    private var joining = false

    init {
        viewModelScope.launch {
            graph.claimEvents.collect { event ->
                message = when (event) {
                    is ClaimEvent.TokenSaved -> R.string.pro_token_saved
                    ClaimEvent.PartiallyPaid -> R.string.pro_partial
                    is ClaimEvent.Expired -> if (event.hadPaymentId) R.string.pro_expired else null
                    ClaimEvent.NoCheckoutRecord -> R.string.pro_no_record
                }
                refreshPro()
            }
        }
        refreshPro()
        // A claim from an earlier run: poll while the app is open, as the web page does.
        viewModelScope.launch { graph.claims.pollInForeground() }
    }

    /** The server the Pro screen is about: the open room's, else the default one. */
    val proOrigin: String get() = link?.origin ?: DEFAULT_ORIGIN

    fun refreshPro() {
        viewModelScope.launch {
            pendingClaim = graph.vault.pendingClaim()
            savedToken = graph.vault.token(proOrigin)
        }
    }

    // ---- Links ----

    /** Acts on a link, a scanned code or pasted text. Returns false if it was none of those. */
    fun open(text: String?): Boolean {
        val intakes = LinkIntake.parse(text)
        for (intake in intakes) {
            when (intake) {
                is Intake.PaymentReturn -> onPaymentId(intake.paymentId)
                is Intake.Room -> if (intake.trusted) join(intake.link) else confirm = intake.link
                Intake.Nothing -> Unit
            }
        }
        return intakes.isNotEmpty()
    }

    fun confirmJoin(yes: Boolean) {
        val pending = confirm
        confirm = null
        if (yes && pending != null) join(pending)
    }

    fun createRoom() = join(RoomLink.generate(DEFAULT_ORIGIN, SecureRandom()))

    // ---- Room ----

    private suspend fun resolveProfile(target: RoomLink): NetworkProfile? {
        if (!graph.settings.prefs.value.tor && !target.isOnion) return NetworkProfile.Clearnet
        if (!Orbot.isInstalled(context)) {
            message = RoomTexts.orbotMissing(onionRoom = target.isOnion)
            return null
        }
        Orbot.requestStart(context)
        val on = withTimeoutOrNull(20_000) { Orbot.status(context).first { it is Orbot.Status.On } } as? Orbot.Status.On
        on?.let { graph.orbotPort = it.socksPort }
        // A fresh value per room: rooms do not share a Tor circuit.
        val isolation = ByteArray(12).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val profile = Orbot.profile(graph.orbotPort, isolation)
        if (profile == null) message = R.string.tor_unavailable
        return profile
    }

    private suspend fun bind(): DarkP2PService {
        service?.let { return it }
        val intent = Intent(context, DarkP2PService::class.java)
        context.startForegroundService(intent)
        val bound = CompletableDeferred<DarkP2PService>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bound.complete((binder as DarkP2PService.LocalBinder).service)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                onRoomGone()
            }
        }
        connection = conn
        context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        return bound.await().also { service = it }
    }

    fun join(target: RoomLink) {
        if (joining) return
        joining = true
        viewModelScope.launch {
            try {
                val profile = resolveProfile(target) ?: return@launch
                leaveRoom()
                val prefs = graph.settings.prefs.value
                val token = graph.vault.token(target.origin).orEmpty()
                val svc = bind()
                val room = svc.join(target, profile, prefs.policy, token, graph.vault) {
                    // Like the page: give a pending token claim a moment before joining, never long.
                    withTimeoutOrNull(5_000) { graph.claims.attemptOnce() }
                }
                link = target
                _controller.value = room
                activity.clear()
                screen = Screen.ROOM
                roomJobs = viewModelScope.launch {
                    launch {
                        room.notices.collect { event -> RoomTexts.notice(event)?.let { (text, named) -> log(text, event.detail.takeIf { named }) } }
                    }
                    launch { room.saved.collect { log(R.string.room_saved, it) } }
                    // The service left the room by itself (idle, or Android's time limit).
                    launch { svc.controller.first { it !== room }.let { onRoomGone() } }
                }
                waitingShares.toList().also { waitingShares.clear() }.forEach(room::enqueue)
                refreshPro()
            } finally {
                joining = false
            }
        }
    }

    private fun log(text: Int, name: String?) {
        activity += text to name
        while (activity.size > 100) activity.removeAt(0)
    }

    private fun onRoomGone() {
        roomJobs?.cancel()
        roomJobs = null
        _controller.value = null
        link = null
        service = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        if (screen == Screen.ROOM) screen = Screen.HOME
    }

    private fun leaveRoom() {
        roomJobs?.cancel()
        roomJobs = null
        _controller.value = null
        link = null
        service?.leave()
        service = null
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    fun leave() {
        leaveRoom()
        screen = Screen.HOME
    }

    /** Files picked or shared. Each is opened now; with no room open they wait for one. */
    fun send(uris: List<Uri>) {
        viewModelScope.launch {
            for (uri in uris) {
                val source = runCatching { graph.store.source(uri) }.getOrNull()
                if (source == null) {
                    log(R.string.notice_open_failed, uri.lastPathSegment?.takeLast(40))
                    continue
                }
                val room = _controller.value
                if (room != null) room.enqueue(source) else waitingShares += source
            }
        }
    }

    fun accept(target: SaveTarget) = _controller.value?.accept(target)

    /** Applies changed settings to the open room. */
    fun applyPrefs(change: (Prefs) -> Prefs) {
        graph.settings.update(change)
        _controller.value?.setPolicy(graph.settings.prefs.value.policy)
    }

    // ---- Pro ----

    fun checkout(tier: ProTier) {
        viewModelScope.launch {
            invoiceToCopy = null
            when (val result = graph.claims.checkout(proOrigin, tier)) {
                is CheckoutResult.Created -> {
                    ClaimWorker.schedule(context)
                    val tor = graph.settings.prefs.value.tor || link?.isOnion == true
                    if (!CheckoutLauncher.open(context, result.invoiceUrl, tor)) {
                        invoiceToCopy = result.invoiceUrl
                        message = R.string.pro_checkout_tor
                    }
                    viewModelScope.launch { graph.claims.pollInForeground() }
                }
                CheckoutResult.RateLimited -> message = R.string.pro_checkout_rate
                CheckoutResult.Unavailable -> message = R.string.pro_checkout_unavailable
            }
            refreshPro()
        }
    }

    /** A payment ID from the payment provider's return link, or typed in by the buyer. */
    fun onPaymentId(paymentId: String) {
        viewModelScope.launch {
            if (graph.claims.onPaymentReturn(paymentId.trim())) {
                ClaimWorker.schedule(context)
                screen = Screen.PRO
                launch { graph.claims.pollInForeground() }
            } else if (graph.vault.pendingClaim() != null) {
                message = R.string.pro_payment_id_bad
            }
            refreshPro()
        }
    }

    fun saveToken(token: String) {
        if (!RoomTexts.isTokenShaped(token)) {
            message = R.string.pro_token_bad
            return
        }
        viewModelScope.launch {
            graph.vault.setToken(proOrigin, token)
            _controller.value?.setToken(token.trim())
            refreshPro()
        }
    }

    /** Forgets the token saved for [proOrigin]; an open room stops offering it. */
    fun removeToken() {
        viewModelScope.launch {
            graph.vault.setToken(proOrigin, null)
            _controller.value?.setToken("")
            refreshPro()
        }
    }

    override fun onCleared() {
        // The room lives in the service and outlives this object; only the binding goes.
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
        waitingShares.forEach { runCatching { it.close() } }
    }
}
