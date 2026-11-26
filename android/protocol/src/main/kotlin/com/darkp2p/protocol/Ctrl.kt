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
    /** [nonce] is null when the field is missing or not text. */
    public data class Hello(val versionOk: Boolean, val nonce: String?) : Ctrl
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
            "hello" -> Hello((obj["v"] as? JsonValue.Num)?.value == WireConstants.PROTOCOL_VERSION.toDouble(), obj["nonce"]?.stringOrNull)
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

        public fun hello(nonce: ByteArray): String =
            Json.stringify(Json.obj("type" to Json.str("hello"), "v" to Json.num(3), "nonce" to Json.str(Base64Url.encode(nonce))))

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
