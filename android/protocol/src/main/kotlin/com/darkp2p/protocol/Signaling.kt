package com.darkp2p.protocol

public data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

/** A JSON text message this client sends to the signaling server. */
public sealed interface ClientMessage {
    public fun toJson(): String

    /** [token] is the Pro token, or empty. Only the room ID goes to the server, never the key. */
    public data class Join(val room: String, val token: String) : ClientMessage {
        override fun toJson(): String = Json.stringify(Json.obj("type" to Json.str("join"), "room" to Json.str(room), "token" to Json.str(token)))

        override fun toString(): String = "Join(***)"
    }

    public data class Offer(val sdp: String) : ClientMessage {
        override fun toJson(): String = Json.stringify(
            Json.obj("type" to Json.str("offer"), "offer" to Json.obj("type" to Json.str("offer"), "sdp" to Json.str(sdp))),
        )
    }

    public data class Answer(val sdp: String) : ClientMessage {
        override fun toJson(): String = Json.stringify(
            Json.obj("type" to Json.str("answer"), "answer" to Json.obj("type" to Json.str("answer"), "sdp" to Json.str(sdp))),
        )
    }

    public data class Candidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?) : ClientMessage {
        override fun toJson(): String = Json.stringify(
            Json.obj(
                "type" to Json.str("candidate"),
                "candidate" to Json.obj(
                    "candidate" to Json.str(candidate),
                    "sdpMid" to (sdpMid?.let(Json::str) ?: JsonValue.Null),
                    "sdpMLineIndex" to (sdpMLineIndex?.let { Json.num(it.toLong()) } ?: JsonValue.Null),
                    "usernameFragment" to JsonValue.Null,
                ),
            ),
        )
    }

    public data object WsFallback : ClientMessage {
        override fun toJson(): String = """{"type":"ws-fallback"}"""
    }
}

/** A JSON text message from the signaling server (its own, or the peer's forwarded by it). */
public sealed interface ServerMessage {
    /**
     * Both peers are in the room. [wsRelayCap] and [wsRelayRemaining] are null
     * from a server too old to send them.
     */
    public data class Ready(
        val initiator: Boolean,
        val iceServers: List<IceServer>,
        val turnEnabled: Boolean,
        val wsRelayCap: Long?,
        val wsRelayRemaining: Long?,
    ) : ServerMessage

    public data object Full : ServerMessage
    public data object PeerLeft : ServerMessage
    public data class Error(val reason: String?) : ServerMessage
    public data class Offer(val sdp: String) : ServerMessage
    public data class Answer(val sdp: String) : ServerMessage
    public data class Candidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?) : ServerMessage
    public data object WsFallback : ServerMessage

    public companion object {
        public const val RELAY_LIMIT_EXCEEDED: String = "relay-limit-exceeded"
        public const val RELAY_BUSY: String = "relay-busy"
        public const val RELAY_IDLE: String = "relay-idle"
        public const val INVALID_ROOM: String = "invalid-room"

        /** Null for anything the web client would ignore. */
        public fun parse(text: String): ServerMessage? {
            val msg = try {
                Json.parse(text) as? JsonValue.Obj
            } catch (e: JsonParseException) {
                null
            } ?: return null
            return when (msg["type"]?.stringOrNull) {
                "ready" -> Ready(
                    initiator = truthy(msg["initiator"]),
                    iceServers = (msg["iceServers"] as? JsonValue.Arr)?.items?.mapNotNull(::iceServer) ?: emptyList(),
                    // relayEnabled is the older name of turnEnabled; the page reads it.
                    turnEnabled = truthy(msg["relayEnabled"]) || truthy(msg["turnEnabled"]),
                    wsRelayCap = msg["wsRelayCap"]?.safeIntegerOrNull,
                    wsRelayRemaining = msg["wsRelayRemaining"]?.safeIntegerOrNull,
                )
                "full" -> Full
                "peer-left" -> PeerLeft
                "error" -> Error(msg["reason"]?.stringOrNull)
                "offer" -> (msg["offer"] as? JsonValue.Obj)?.get("sdp")?.stringOrNull?.let(::Offer)
                "answer" -> (msg["answer"] as? JsonValue.Obj)?.get("sdp")?.stringOrNull?.let(::Answer)
                "candidate" -> (msg["candidate"] as? JsonValue.Obj)?.let { c ->
                    val line = c["candidate"]?.stringOrNull ?: return null
                    Candidate(line, c["sdpMid"]?.stringOrNull, c["sdpMLineIndex"]?.safeIntegerOrNull?.toInt())
                }
                "ws-fallback" -> WsFallback
                else -> null
            }
        }

        private fun truthy(v: JsonValue?): Boolean = when (v) {
            null, JsonValue.Null -> false
            is JsonValue.Bool -> v.value
            is JsonValue.Num -> v.value != 0.0 && !v.value.isNaN()
            is JsonValue.Str -> v.value.isNotEmpty()
            else -> true
        }

        private fun iceServer(v: JsonValue): IceServer? {
            val o = v as? JsonValue.Obj ?: return null
            val urls = when (val u = o["urls"]) {
                is JsonValue.Str -> listOf(u.value)
                is JsonValue.Arr -> u.items.mapNotNull { it.stringOrNull }
                else -> emptyList()
            }
            if (urls.isEmpty()) return null
            return IceServer(urls, o["username"]?.stringOrNull, o["credential"]?.stringOrNull)
        }
    }
}
