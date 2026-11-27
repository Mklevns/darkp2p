package com.darkp2p.transport

import okhttp3.Dns
import java.net.InetAddress

/**
 * The device's own resolver. The only place in the app that may resolve a name
 * locally (`checkForbiddenApis`), and never part of a Tor profile.
 */
internal object ClearnetDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname)
}
