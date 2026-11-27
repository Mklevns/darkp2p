package com.darkp2p.transport

import android.content.Context

/**
 * WebRTC for this build. The `libre` flavor ships no prebuilt native code, so
 * it has none yet: rooms use the WebSocket relay (design doc §5.7).
 */
public object PlatformWebRtc {
    public const val AVAILABLE: Boolean = false

    @Suppress("UNUSED_PARAMETER")
    public fun factory(context: Context): WebRtcFactory? = null
}
