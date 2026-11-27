package com.darkp2p.transport

import android.content.Context

/**
 * Onion services for this build. Publishing one needs a Tor that takes
 * commands; Orbot offers other apps none, and the `libre` flavor ships no
 * prebuilt native code, so it has no Tor of its own.
 */
public object PlatformOnion {
    public const val AVAILABLE: Boolean = false

    @Suppress("UNUSED_PARAMETER")
    public fun host(context: Context): OnionHost? = null
}
