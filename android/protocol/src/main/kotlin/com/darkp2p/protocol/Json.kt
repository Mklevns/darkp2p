package com.darkp2p.protocol

/**
 * A JSON value with JavaScript's view of it: numbers are doubles, an object
 * keeps the last of duplicate keys.
 *
 * Control frames and signaling messages are read by the web client with
 * `JSON.parse` and then inspected field by field. Reading them the same way
 * here (strict RFC 8259 grammar, then explicit checks) keeps the two clients
 * agreeing on which messages are valid.
 */
public sealed interface JsonValue {
    public data object Null : JsonValue
    public data class Bool(val value: Boolean) : JsonValue
    public data class Num(val value: Double) : JsonValue
    public data class Str(val value: String) : JsonValue
    public data class Arr(val items: List<JsonValue>) : JsonValue
    public data class Obj(val fields: Map<String, JsonValue>) : JsonValue {
        public operator fun get(key: String): JsonValue? = fields[key]
    }

    /** The string, if this is a string. */
    public val stringOrNull: String? get() = (this as? Str)?.value

    /** The number as an integer, if it is one JavaScript calls a safe integer. */
    public val safeIntegerOrNull: Long?
        get() {
            val d = (this as? Num)?.value ?: return null
            if (d.isNaN() || d.isInfinite() || d != Math.floor(d) || Math.abs(d) > MAX_SAFE_INTEGER) return null
            return d.toLong()
        }

    public companion object {
        public const val MAX_SAFE_INTEGER: Double = 9007199254740991.0
    }
}

public class JsonParseException(message: String) : Exception(message)

public object Json {
    private const val MAX_DEPTH = 64

    /** @throws JsonParseException where `JSON.parse` would throw, or beyond 64 levels of nesting. */
    public fun parse(text: String): JsonValue {
        val p = Parser(text)
        p.skipWhitespace()
        val value = p.value(0)
        p.skipWhitespace()
        if (p.pos != text.length) throw JsonParseException("Unexpected text after JSON value")
        return value
    }

    /** UTF-8 bytes as `TextDecoder` reads them: malformed input is replaced, a leading BOM dropped. */
    public fun parse(utf8: ByteArray): JsonValue {
        val text = String(utf8, Charsets.UTF_8)
        return parse(if (text.startsWith("﻿")) text.substring(1) else text)
    }

    /** Serializes like `JSON.stringify` (no spaces; fields in the given order). */
    public fun stringify(value: JsonValue): String = StringBuilder().also { write(it, value) }.toString()

    /** An object in field order. Null values are left out, as `JSON.stringify` leaves out `undefined`. */
    public fun obj(vararg fields: Pair<String, JsonValue?>): JsonValue.Obj {
        val map = LinkedHashMap<String, JsonValue>()
        for ((k, v) in fields) if (v != null) map[k] = v
        return JsonValue.Obj(map)
    }

    public fun str(value: String): JsonValue.Str = JsonValue.Str(value)

    public fun num(value: Long): JsonValue.Num = JsonValue.Num(value.toDouble())

    private fun write(out: StringBuilder, value: JsonValue) {
        when (value) {
            JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Num -> writeNumber(out, value.value)
            is JsonValue.Str -> writeString(out, value.value)
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    write(out, item)
                }
                out.append(']')
            }
            is JsonValue.Obj -> {
                out.append('{')
                var first = true
                for ((k, v) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k)
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
        }
    }

    private fun writeNumber(out: StringBuilder, d: Double) {
        when {
            d.isNaN() || d.isInfinite() -> out.append("null")
            d == Math.floor(d) && Math.abs(d) <= JsonValue.MAX_SAFE_INTEGER -> out.append(d.toLong())
            // Never produced by this client; shortest round-trip form.
            else -> out.append(d.toString())
        }
    }

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\u000c' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> escape(out, c)
                // Well-formed JSON.stringify: a surrogate without its pair is escaped.
                Character.isHighSurrogate(c) ->
                    if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                        out.append(c).append(s[i + 1])
                        i++
                    } else escape(out, c)
                Character.isLowSurrogate(c) -> escape(out, c)
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    private fun escape(out: StringBuilder, c: Char) {
        out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else break
            }
        }

        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw JsonParseException("JSON nested too deeply")
            if (pos >= s.length) throw JsonParseException("Unexpected end of JSON")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (c == '-' || c in '0'..'9') number() else throw JsonParseException("Unexpected character in JSON")
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!s.startsWith(word, pos)) throw JsonParseException("Unexpected token in JSON")
            pos += word.length
            return value
        }

        private fun obj(depth: Int): JsonValue {
            pos++
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw JsonParseException("Expected a property name")
                val key = string()
                skipWhitespace()
                if (peek() != ':') throw JsonParseException("Expected ':'")
                pos++
                skipWhitespace()
                // A repeated key keeps its first position and takes the last value, as in JS.
                fields[key] = value(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.Obj(fields)
                    }
                    else -> throw JsonParseException("Expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): JsonValue {
            pos++
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items.add(value(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.Arr(items)
                    }
                    else -> throw JsonParseException("Expected ',' or ']'")
                }
            }
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

        private fun string(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonParseException("Unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return out.toString()
                    c < ' ' -> throw JsonParseException("Control character in string")
                    c == '\\' -> {
                        if (pos >= s.length) throw JsonParseException("Unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonParseException("Bad unicode escape")
                                var code = 0
                                repeat(4) {
                                    val d = Character.digit(s[pos++], 16)
                                    if (d < 0) throw JsonParseException("Bad unicode escape")
                                    code = (code shl 4) or d
                                }
                                out.append(code.toChar())
                            }
                            else -> throw JsonParseException("Bad escape '\\$e'")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun digits(): Int {
            val start = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            return pos - start
        }

        private fun number(): JsonValue {
            val start = pos
            if (peek() == '-') pos++
            if (peek() == '0') pos++ else if (digits() == 0) throw JsonParseException("Bad number")
            if (peek() == '.') {
                pos++
                if (digits() == 0) throw JsonParseException("Bad number")
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                if (digits() == 0) throw JsonParseException("Bad number")
            }
            // Same correctly rounded decimal-to-double conversion as JavaScript.
            return JsonValue.Num(s.substring(start, pos).toDouble())
        }
    }
}
