package com.darkp2p.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.darkp2p.service.FileNames
import com.darkp2p.transport.DropFile
import com.darkp2p.transport.FileDropServer
import com.darkp2p.transport.OnionHost
import com.darkp2p.transport.PlatformOnion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

/** Where an onion file drop stands. */
sealed interface DropState {
    data object Idle : DropState

    /** Tor is starting; [percent] is its own progress. */
    data class Starting(val name: String, val percent: Int) : DropState

    /** The link works (once Tor has announced the service, which takes a little while). */
    data class Ready(val url: String, val name: String, val size: Long, val sent: Long) : DropState

    /** One complete download happened and everything was taken down. */
    data class Done(val name: String) : DropState

    data class Failed(val reason: Int) : DropState
}

/**
 * Offers one file under a throwaway `.onion` address, for someone with Tor Browser.
 *
 * The file is read from where it is and streamed to whoever opens the link; it
 * is not copied anywhere. The link is `http://<onion>/<token>`: the onion
 * connection is encrypted end to end between Tor Browser and this phone, with
 * no server in between, and the token keeps out anyone who learns the address
 * alone. Both are made fresh for every drop and forgotten when it ends: after
 * one complete download, when the user stops it, or when the app goes away.
 */
class OnionDrop(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<DropState>(DropState.Idle)
    val state: StateFlow<DropState> get() = _state

    private var job: Job? = null
    private var server: FileDropServer? = null
    private var host: OnionHost? = null

    // The Tor of a finished drop, kept running for a moment (see onDelivered).
    @Volatile private var lingering: OnionHost? = null

    val active: Boolean get() = _state.value is DropState.Starting || _state.value is DropState.Ready

    fun start(uri: Uri) {
        if (active) return
        job = scope.launch {
            var name = ""
            try {
                val onion = PlatformOnion.host(context) ?: return@launch fail(R.string.drop_unavailable)
                val file = withContext(Dispatchers.IO) { describe(uri) }
                name = file.name
                _state.value = DropState.Starting(name, 0)

                val token = newToken()
                val listener = object : FileDropServer.Listener {
                    override fun onProgress(sent: Long) {
                        val now = _state.value
                        if (now is DropState.Ready && sent > now.sent) _state.value = now.copy(sent = sent)
                    }

                    override fun onDelivered() {
                        // No second download: the server goes now. Tor stays a little longer,
                        // so the last of the file, still on its way through the circuit, arrives.
                        server?.close()
                        server = null
                        _state.value = DropState.Done(file.name)
                        val last = host
                        host = null
                        lingering = last
                        scope.launch {
                            delay(LINGER_MS)
                            if (lingering === last) lingering = null
                            last?.close()
                        }
                    }
                }
                val files = FileDropServer(file, token, listener).also { server = it }
                files.start()
                host = onion
                val address = onion.publish(files.port) { percent -> _state.value = DropState.Starting(name, percent) }
                _state.value = DropState.Ready("http://$address/$token", file.name, file.size, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                fail(R.string.drop_failed_file)
            } catch (e: SecurityException) {
                fail(R.string.drop_failed_file)
            } catch (e: IOException) {
                fail(R.string.drop_failed_tor)
            }
        }
    }

    /** Takes the link down now. */
    fun stop() {
        job?.cancel()
        job = null
        tearDown()
        _state.value = DropState.Idle
    }

    private fun fail(reason: Int) {
        tearDown()
        _state.value = DropState.Failed(reason)
    }

    private fun tearDown() {
        server?.close()
        server = null
        host?.close()
        host = null
        lingering?.close()
        lingering = null
    }

    private fun describe(uri: Uri): DropFile {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        if (size < 0) size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
        if (size < 0) throw FileNotFoundException("size unknown")
        return DropFile(FileNames.safe(name ?: uri.lastPathSegment ?: ""), size) {
            resolver.openInputStream(uri) ?: throw FileNotFoundException("could not open the file")
        }
    }

    companion object {
        private const val LINGER_MS = 20_000L

        /** 256 random bits, URL-safe: what stands between someone who has the onion address and the file. */
        fun newToken(random: SecureRandom = SecureRandom()): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    }
}
