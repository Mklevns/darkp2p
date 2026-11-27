package com.darkp2p.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Copies something that works like a password: marked sensitive, and removed again after a minute. */
object SensitiveClipboard {
    private const val CLEAR_AFTER_MS = 60_000L
    private const val LABEL = "DarkP2P"

    fun copy(context: Context, scope: CoroutineScope, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(LABEL, text).apply {
            // Android 13+: keeps the text out of the clipboard preview. Older versions ignore it.
            description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        clipboard.setPrimaryClip(clip)
        scope.launch {
            delay(CLEAR_AFTER_MS)
            // Only if it is still ours: never wipe something the user copied since. While the app
            // is in the background Android does not let it look (null): the link is then cleared
            // anyway, because leaving a room key in the clipboard is the worse mistake.
            val current = runCatching { clipboard.primaryClip }.getOrNull()
            val ours = current?.description?.label == LABEL && current.itemCount == 1 && current.getItemAt(0).text?.toString() == text
            if (ours || current == null) runCatching { clipboard.clearPrimaryClip() }
        }
    }
}
