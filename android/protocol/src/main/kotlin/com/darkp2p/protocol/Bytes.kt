package com.darkp2p.protocol

/** Byte-string helpers shared by the codecs. */
public object Bytes {
    private const val HEX = "0123456789abcdef"

    public fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    /** @throws IllegalArgumentException if [hex] is not an even number of hex digits. */
    public fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "not hex" }
            ((hi shl 4) or lo).toByte()
        }
    }

    /** Equality whose running time does not depend on where the inputs differ. */
    public fun constantTimeEquals(a: ByteArray?, b: ByteArray?): Boolean {
        if (a == null || b == null || a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

/**
 * Base64URL as the web client reads and writes it.
 *
 * Encoding has no padding. Decoding follows the page's `fromBase64Url` (which
 * hands the text to `atob`), so it also accepts the standard `+/` alphabet,
 * `=` padding and non-zero trailing bits. A link the page accepts must be
 * accepted here, or the two clients would disagree about which room it names.
 */
public object Base64Url {
    private const val URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    public fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            out.append(URL[b0 ushr 2])
            out.append(URL[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (i + 1 < bytes.size) out.append(URL[((b1 and 0x0f) shl 2) or (b2 ushr 6)])
            if (i + 2 < bytes.size) out.append(URL[b2 and 0x3f])
            i += 3
        }
        return out.toString()
    }

    /** Returns null where the page's decoder would throw. */
    public fun decode(text: String): ByteArray? {
        // fromBase64Url: '-' -> '+', '_' -> '/', then '=' up to a multiple of four.
        val padded = StringBuilder(text.length + 3)
        for (c in text) padded.append(if (c == '-') '+' else if (c == '_') '/' else c)
        while (padded.length % 4 != 0) padded.append('=')

        // atob: ASCII whitespace is dropped, then at most two '=' at the end of a
        // multiple-of-four string are padding.
        val data = StringBuilder(padded.length)
        for (c in padded) if (c != ' ' && c != '\t' && c != '\n' && c != '\u000c' && c != '\r') data.append(c)
        if (data.length % 4 == 0 && data.isNotEmpty() && data[data.length - 1] == '=') {
            data.setLength(data.length - 1)
            if (data.isNotEmpty() && data[data.length - 1] == '=') data.setLength(data.length - 1)
        }
        if (data.length % 4 == 1) return null

        val out = ByteArray(data.length * 6 / 8)
        var buffer = 0
        var bits = 0
        var n = 0
        for (c in data) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> return null
            }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[n++] = ((buffer ushr bits) and 0xff).toByte()
            }
        }
        return out
    }
}
