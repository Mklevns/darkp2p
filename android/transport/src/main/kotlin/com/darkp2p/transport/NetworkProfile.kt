package com.darkp2p.transport

import com.darkp2p.protocol.RoomLink
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** How this device reaches the server. */
public sealed interface NetworkProfile {
    /** Directly, with the device's own DNS. */
    public data object Clearnet : NetworkProfile

    /**
     * Only through a Tor SOCKS5 proxy (Orbot or an embedded Tor), naming every
     * destination by host name. WebRTC does not exist in this profile.
     *
     * @param isolation an opaque value, different per room, that keeps rooms on separate circuits.
     */
    public data class Tor(val socks: InetSocketAddress, val isolation: String? = null) : NetworkProfile {
        override fun toString(): String = "Tor(${socks.port})"
    }
}

/** The URLs and the Origin header for one server, from the origin part of a room link. */
public class ServerEndpoint(public val origin: String) {
    init {
        require(RoomLink.normalizeOrigin(origin) == origin) { "not a normalized origin" }
    }

    public val isOnion: Boolean get() = RoomLink.isOnionOrigin(origin)

    /** The page's `${proto}//${location.host}`: `/` of the same host, `wss` under https. */
    public val webSocketUrl: String get() = "${if (origin.startsWith("https://")) "wss" else "ws"}://${origin.substringAfter("://")}/"

    public fun url(pathAndQuery: String): String = "$origin$pathAndQuery"

    /** Whether [profile] may be used to reach this server: an onion service only through Tor. */
    public fun allows(profile: NetworkProfile): Boolean = !isOnion || profile is NetworkProfile.Tor
}

public object HttpClients {
    /**
     * Client-side WebSocket ping interval. The server stops reading a relay
     * sender while it throttles it or waits for the receiver, for up to
     * `RELAY_STALL_MS` (60 s); a ping sent then gets no pong in time. Anything
     * much shorter than this would cut healthy transfers.
     */
    public const val PING_INTERVAL_S: Long = 90

    /** No OkHttp or OS version: one value for every install. */
    public const val USER_AGENT: String = "darkp2p-android"

    /** One client per profile. A Tor client can reach nothing except through its proxy. */
    public fun create(profile: NetworkProfile): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
            .connectTimeout(if (profile is NetworkProfile.Tor) 90 else 20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSockets idle legitimately; the ping decides liveness
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false) // nothing here redirects; a redirect would be someone else's choice of host
            .followSslRedirects(false)
            // The socket factory below is the whole route. No system proxy, no proxy selector.
            .proxy(Proxy.NO_PROXY)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
        when (profile) {
            NetworkProfile.Clearnet -> builder.dns(ClearnetDns)
            is NetworkProfile.Tor -> builder.dns(TorDns).socketFactory(Socks5hSocketFactory(profile.socks, profile.isolation))
        }
        return builder.build()
    }
}
