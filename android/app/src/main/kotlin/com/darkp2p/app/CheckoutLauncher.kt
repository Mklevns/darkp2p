package com.darkp2p.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens the payment page. The only place a browser is started.
 *
 * Outside Tor mode that is a Custom Tab. In Tor mode a Custom Tab would load
 * the page over the device's normal connection, so the page goes to Tor
 * Browser instead, or nowhere: the caller then offers the link to copy.
 */
object CheckoutLauncher {
    private const val TOR_BROWSER = "org.torproject.torbrowser"

    /** False if nothing suitable could open [invoiceUrl]. */
    fun open(context: Context, invoiceUrl: String, torMode: Boolean): Boolean {
        val uri = invoiceUrl.toUri()
        if (uri.scheme != "https") return false
        return try {
            if (torMode) {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(TOR_BROWSER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                CustomTabsIntent.Builder().setShowTitle(true).build().apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.launchUrl(context, uri)
            }
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
