package com.darkp2p.transport

import android.content.Context

/** WebRTC for this build: libwebrtc. Created on first use, never in Tor mode. */
public object PlatformWebRtc {
    public const val AVAILABLE: Boolean = true

    public fun factory(context: Context): WebRtcFactory? = LibWebRtcFactory(context.applicationContext)
}
