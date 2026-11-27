package com.darkp2p.protocol

import java.net.URI
import java.net.URISyntaxException
import java.security.SecureRandom

/**
 * A room: the server it lives on, its ID and its key.
 *
 * The share link is `<origin>/#<roomId>:<key>`. Everything after `#` stays on
 * the two devices: only [roomId] is ever sent to the server.
 */
public class RoomLink(
    /** `https://host[:port]` or `http://<name>.onion`: selects the server and the Origin header. */
    public val origin: String,
    public val roomId: String,
    public val key: RoomKey,
) {
    public val fragment: String get() = "$roomId:${key.toBase64Url()}"

    /** Built from the parts, never from a received URL: a query string (a payment return) never ends up in it. */
    public val shareUrl: String get() = "$origin/#$fragment"

    public val isOnion: Boolean get() = isOnionOrigin(origin)

    override fun toString(): String = "RoomLink(${roomId.take(8)}…)"

    public companion object {
        private val ROOM_ID = Regex("^[a-fA-F0-9]{16,64}$")
        private val HOST = Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$")

        /** A new room on [origin]: 16 random bytes of ID, 32 of key. */
        public fun generate(origin: String, random: SecureRandom): RoomLink {
            val id = ByteArray(16).also(random::nextBytes)
            val key = ByteArray(WireConstants.ROOM_KEY_LEN).also(random::nextBytes)
            return RoomLink(requireNotNull(normalizeOrigin(origin)) { "invalid origin" }, Bytes.toHex(id), RoomKey(key)).also { key.fill(0) }
        }

        /**
         * The room ID and key in the text after `#`, by the web client's rules:
         * exactly two `:`-separated parts, 16-64 hex digits, and at least 43
         * characters of Base64 that decode to exactly 32 bytes. Null otherwise.
         */
        public fun parseFragment(fragment: String): Pair<String, RoomKey>? {
            val parts = fragment.split(':')
            if (parts.size != 2 || !ROOM_ID.matches(parts[0]) || parts[1].length < 43) return null
            val key = Base64Url.decode(parts[1]) ?: return null
            if (key.size != WireConstants.ROOM_KEY_LEN) return null
            return Pair(parts[0], RoomKey(key)).also { key.fill(0) }
        }

        /** A full share link. Null unless it is http(s) with a host and a valid fragment. */
        public fun parse(url: String): RoomLink? {
            val hash = url.indexOf('#')
            if (hash == -1) return null
            val origin = normalizeOrigin(url.substring(0, hash)) ?: return null
            val (roomId, key) = parseFragment(url.substring(hash + 1)) ?: return null
            return RoomLink(origin, roomId, key)
        }

        /**
         * `scheme://host[:port]` of an http(s) URL, lower-cased, default port dropped.
         * Null for anything else, including cleartext http to a host that is neither `.onion` nor loopback.
         */
        public fun normalizeOrigin(url: String): String? {
            val uri = try {
                URI(url.trim())
            } catch (e: URISyntaxException) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (uri.rawUserInfo != null || !HOST.matches(host)) return null
            // Cleartext only where TLS adds nothing: onion services and this device itself.
            val cleartextOk = host.endsWith(".onion") || host == "localhost" || host == "127.0.0.1"
            if (scheme != "https" && !(scheme == "http" && cleartextOk)) return null
            val defaultPort = if (scheme == "https") 443 else 80
            val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
            return "$scheme://$host$port"
        }

        public fun isOnionOrigin(origin: String): Boolean = origin.substringAfter("://").substringBefore(':').endsWith(".onion")
    }
}
