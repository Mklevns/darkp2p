package com.darkp2p.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** What the system says about the network this app's connections use by default. */
object ActiveNetwork {
    /**
     * True if that network is a VPN. An app the user excluded in the VPN's
     * split-tunnel settings has the underlying network as its default, so this is
     * false for it, which is exactly when a direct connection can work again.
     */
    fun isVpn(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
}
