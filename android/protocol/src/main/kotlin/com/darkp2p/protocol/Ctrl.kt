package com.darkp2p.protocol

public enum class AbortReason(public val wire: String) {
    USER("user"),
    INTEGRITY("integrity"),
    DISK("disk"),
    TIMEOUT("timeout"),
    ;

    public companion object {
        public fun fromWire(wire: String?): AbortReason? = entries.firstOrNull { it.wire == wire }
    }
}

public enum class DeclineReason(public val wire: String) {
    USER("user"),
    SIZE("size"),
    BUSY("busy"),
    ;

    public companion object {
        public fun fromWire(wire: String?): DeclineReason? = entries.firstOrNull { it.wire == wire }
    }
}

/** A violation of the transfer protocol. [reason] is the abort reason to send to the peer. */
public class ProtocolError(public val reason: AbortReason, message: String) : Exception(message)

/** File metadata after validation ([Ctrl.parseMeta]). */
public data class ValidMeta(val name: String, val size: Long, val mime: String, val chunks: Long)

/**
 * A control message as the receiver understands it.
 *
 * The web client inspects the parsed JSON field by field; the decoding here
 * makes the same decisions, so anything it would refuse arrives as a value
 * that the state machines refuse too ([Unknown], a null field, [Ack.valid]).
 */
public sealed interface Ctrl {
    /**
     * [nonce] is null when the field is missing or not text. [caps] are the optional
     * features the peer offers (see [Capabilities]); the web client sends none and
     * ignores the field.
     */
    public data class Hello(val versionOk: Boolean, val nonce: String?, val caps: Set<String> = emptySet()) : Ctrl
    public data object HelloAck : Ctrl

    /** Not yet validated: see [parseMeta]. */
    public data class Meta(val raw: JsonValue.Obj) : Ctrl

    /** A field is null unless it is an integer JSON number. */
    public data class Done(val chunks: Long?, val size: Long?) : Ctrl
    public data object Accept : Ctrl
    public data class Decline(val reason: String?) : Ctrl

    /** [valid] is false for a `seq` that is not a safe integer or a `done` that is present but not `true`. */
    public data class Ack(val seq: Long, val done: Boolean, val valid: Boolean) : Ctrl
    public data object Complete : Ctrl
    public data class Abort(val reason: String?) : Ctrl

    /** A chat message (Android extension). [text] is null unless the field is text. */
    public class Chat(public val text: String?) : Ctrl {
        override fun toString(): String = "Chat(***)"
    }

    /** Receipt for every chat message up to [seq] of the peer's chat stream; null unless an integer. */
    public data class ChatAck(val seq: Long?) : Ctrl

    /** Any other JSON object or array. */
    public data object Unknown : Ctrl

    public companion object {
        public const val DEFAULT_NAME: String = "download.bin"
        public const val DEFAULT_MIME: String = "application/octet-stream"

        /** @throws ProtocolError (integrity) if the payload is not a JSON object or array. */
        public fun decode(payload: ByteArray): Ctrl {
            val value = try {
                Json.parse(payload)
            } catch (e: JsonParseException) {
                throw ProtocolError(AbortReason.INTEGRITY, "Malformed control frame")
            }
            return when (value) {
                is JsonValue.Obj -> of(value)
                // typeof [] === 'object': the page lets it through, then finds no type.
                is JsonValue.Arr -> Unknown
                else -> throw ProtocolError(AbortReason.INTEGRITY, "Malformed control frame")
            }
        }

        public fun of(obj: JsonValue.Obj): Ctrl = when (obj["type"]?.stringOrNull) {
            "hello" -> Hello(
                (obj["v"] as? JsonValue.Num)?.value == WireConstants.PROTOCOL_VERSION.toDouble(),
                obj["nonce"]?.stringOrNull,
                // Anything but a list of strings offers nothing; unknown names are kept and ignored.
                (obj["caps"] as? JsonValue.Arr)?.items?.take(Capabilities.MAX_LISTED)?.mapNotNull { it.stringOrNull }?.toSet() ?: emptySet(),
            )
            "chat" -> Chat(obj["text"]?.stringOrNull)
            "chat-ack" -> ChatAck(obj["seq"]?.safeIntegerOrNull)
            "hello-ack" -> HelloAck
            "meta" -> Meta(obj)
            "done" -> Done(obj["chunks"]?.safeIntegerOrNull, obj["size"]?.safeIntegerOrNull)
            "accept" -> Accept
            "decline" -> Decline(obj["reason"]?.stringOrNull)
            "ack" -> {
                val seq = obj["seq"]?.safeIntegerOrNull
                val done = obj["done"]
                Ack(seq ?: -1, done == JsonValue.Bool(true), seq != null && (done == null || done == JsonValue.Bool(true)))
            }
            "complete" -> Complete
            "abort" -> Abort(obj["reason"]?.stringOrNull)
            else -> Unknown
        }

        /** Validates a decoded meta object and returns the normalized metadata. */
        public fun parseMeta(meta: Meta): ValidMeta {
            val raw = meta.raw
            val size = raw["size"]?.safeIntegerOrNull
            if (size == null || size < 0) throw ProtocolError(AbortReason.INTEGRITY, "Invalid file size in metadata")
            val chunks = raw["chunks"]?.safeIntegerOrNull
            if (chunks == null || chunks < 0 || chunks > WireConstants.MAX_FILE_CHUNKS ||
                (if (size == 0L) chunks != 0L else chunks < 1 || chunks > size)
            ) {
                throw ProtocolError(AbortReason.INTEGRITY, "Invalid chunk count in metadata")
            }
            val rawName = raw["name"]
            val name = (if (jsTruthy(rawName)) jsString(rawName!!) else DEFAULT_NAME).replace('\\', '_').replace('/', '_')
            val mime = raw["mime"]?.stringOrNull?.takeIf { it.isNotEmpty() } ?: DEFAULT_MIME
            return ValidMeta(name, size, mime, chunks)
        }

        // The page's `meta.name || 'download.bin'`.
        private fun jsTruthy(value: JsonValue?): Boolean = when (value) {
            null, JsonValue.Null -> false
            is JsonValue.Bool -> value.value
            is JsonValue.Num -> value.value != 0.0 && !value.value.isNaN()
            is JsonValue.Str -> value.value.isNotEmpty()
            is JsonValue.Arr, is JsonValue.Obj -> true
        }

        // The page's `String(value)`.
        private fun jsString(value: JsonValue): String = when (value) {
            JsonValue.Null -> "null"
            is JsonValue.Str -> value.value
            is JsonValue.Bool -> value.value.toString()
            is JsonValue.Num -> {
                val d = value.value
                if (d == Math.floor(d) && Math.abs(d) < 1e21) java.math.BigDecimal(d).toBigInteger().toString() else d.toString()
            }
            is JsonValue.Obj -> "[object Object]"
            is JsonValue.Arr -> value.items.joinToString(",") { if (it == JsonValue.Null) "" else jsString(it) }
        }

        // ---- Encoding: the exact JSON text the web client produces for each message. ----

        /** Without [caps] this is the page's hello byte for byte; with them, one more field the page never reads. */
        public fun hello(nonce: ByteArray, caps: List<String> = emptyList()): String = Json.stringify(
            Json.obj(
                "type" to Json.str("hello"), "v" to Json.num(3), "nonce" to Json.str(Base64Url.encode(nonce)),
                "caps" to caps.takeIf { it.isNotEmpty() }?.let { JsonValue.Arr(it.map(Json::str)) },
            ),
        )

        public fun chat(text: String): String = Json.stringify(Json.obj("type" to Json.str("chat"), "text" to Json.str(text)))

        public fun chatAck(seq: Long): String = """{"type":"chat-ack","seq":$seq}"""

        public fun helloAck(): String = Json.stringify(Json.obj("type" to Json.str("hello-ack"), "v" to Json.num(3)))

        public fun meta(name: String, size: Long, mime: String, chunks: Long): String = Json.stringify(
            Json.obj("type" to Json.str("meta"), "name" to Json.str(name), "size" to Json.num(size), "mime" to Json.str(mime), "chunks" to Json.num(chunks)),
        )

        public fun done(chunks: Long, size: Long): String =
            Json.stringify(Json.obj("type" to Json.str("done"), "chunks" to Json.num(chunks), "size" to Json.num(size)))

        public fun accept(): String = """{"type":"accept"}"""

        public fun decline(reason: DeclineReason): String = """{"type":"decline","reason":"${reason.wire}"}"""

        /** A final ack carries `"done":true`; any other ack has no `done` field at all. */
        public fun ack(seq: Long, final: Boolean = false): String =
            if (final) """{"type":"ack","seq":$seq,"done":true}""" else """{"type":"ack","seq":$seq}"""

        public fun complete(): String = """{"type":"complete"}"""

        public fun abort(reason: AbortReason): String = """{"type":"abort","reason":"${reason.wire}"}"""
    }
}

/**
 * Optional features a client offers in its `hello` (`"caps": [...]`).
 *
 * A feature is used only when both peers offer it. The web client offers none
 * and reads only `v` and `nonce` from a hello, so offering is invisible to it,
 * and nothing of an optional feature is ever sent to it.
 */
public object Capabilities {
    /** Text chat between two Android clients. */
    public const val CHAT: String = "chat"

    /** Names read from one hello; more are ignored. */
    public const val MAX_LISTED: Int = 16
}

/**
 * Chat (Android to Android only).
 *
 * Each side has one chat stream per session: a random transfer ID, with CTRL
 * frames numbered 0, 1, 2, ... under the session's transfer key. A frame is
 * either `{"type":"chat","text":...}` or `{"type":"chat-ack","seq":n}`, the
 * receipt for every message up to frame `n` of the other side's stream. The
 * receiver accepts exactly the next number, so the relay can neither replay,
 * reorder nor silently drop a message, and a new session starts new streams.
 */
public object ChatConstants {
    /** Longest message, in UTF-8 bytes. With the frame around it this stays far below the relay's 64 KiB limit. */
    public const val MAX_TEXT_BYTES: Int = 8 * 1024

    /**
     * Messages awaiting their receipt. The receiver holds control frames in a
     * queue of 32 while a file chunk is written; this keeps chat well inside it.
     */
    public const val WINDOW: Int = 8

    /** Relay budget a message must leave untouched, so chat cannot spend what the room needs to stay usable. */
    public const val RELAY_RESERVE: Long = WireConstants.RELAY_MIN_REMAINING
}
