package com.darkp2p.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.darkp2p.app.ui.AppUi
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val vm: RoomViewModel by viewModels()

    /** True until the user has passed the screen lock (when the app lock is on). */
    private var locked by mutableStateOf(false)

    /** Whether the device has a screen lock to ask for. It can be set or removed while the app is in the background. */
    private var lockAvailable by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        lockAvailable = canLock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The window's own theme is dark, so the status bar drew white icons over the
        // light colours the UI uses by day. This picks the icon colour from day or night.
        enableEdgeToEdge()
        lockAvailable = canLock()
        locked = vm.graph.settings.prefs.value.lock
        lifecycleScope.launch {
            vm.graph.settings.prefs.collect { applySecure(it.secure) }
        }
        setContent {
            val prefs by vm.graph.settings.prefs.collectAsState()
            AppUi(vm, prefs, locked = locked && prefs.lock, onUnlock = ::unlock, canLock = lockAvailable)
        }
        if (savedInstanceState == null) handle(intent)
        if (locked) unlock()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app locks it again; a room in the service carries on.
        if (!isChangingConfigurations && vm.graph.settings.prefs.value.lock) locked = true
    }

    private fun applySecure(secure: Boolean) {
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!secure)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                // The link holds the room key. Use it, then drop it from the intent this
                // activity keeps, so it is not handed back later or shown in a system dump.
                vm.open(intent.dataString)
                setIntent(Intent(Intent.ACTION_MAIN).setClass(this, MainActivity::class.java))
            }
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                uri?.let { vm.send(listOf(it)) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                uris?.let { vm.send(it.take(MAX_SHARED_FILES)) }
            }
        }
    }

    private val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private fun canLock(): Boolean = BiometricManager.from(this).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    private fun unlock() {
        if (!canLock()) {
            // The device's screen lock was removed since: there is nothing to ask for.
            locked = false
            return
        }
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked = false
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.lock_title)).setAllowedAuthenticators(authenticators).build(),
        )
    }

    private companion object {
        const val MAX_SHARED_FILES = 50
    }
}
