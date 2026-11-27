package com.darkp2p.service

/** A file being sent. Opened when it is queued, so it stays readable even if the permission to its URI is withdrawn later. */
public interface FileSource {
    public val name: String
    public val size: Long
    public val mime: String

    /** [length] bytes at [offset]. Fewer bytes mean the file changed; an exception means it could not be read. */
    public suspend fun read(offset: Long, length: Int): ByteArray

    public fun close()
}

/** Where an incoming file is written. Nothing is visible to the user until [commit]. */
public interface FileSink {
    public suspend fun write(bytes: ByteArray)

    /** Complete and verified: make it durable and visible. Returns the name it was saved under. */
    public suspend fun commit(): String

    /** The transfer ended without a verified file: remove every trace of it. */
    public suspend fun discard()
}

public sealed interface SaveTarget {
    /** The device's Downloads collection, in a `DarkP2P` folder. */
    public data object Downloads : SaveTarget

    /** A document the user created with the system's "Save as" picker. */
    public data class Document(val uri: String) : SaveTarget
}

public interface FileStore {
    /** Bytes that can still be written to [target], or null if that cannot be known. */
    public suspend fun freeSpace(target: SaveTarget): Long?

    public suspend fun create(target: SaveTarget, name: String, mime: String, size: Long): FileSink
}

public object FileNames {
    public const val FALLBACK: String = "download.bin"
    private const val MAX_BYTES = 200

    /**
     * A name that is safe to show and to hand to the system as a file name.
     *
     * The sender chooses the name, so it is treated as hostile: no path
     * separators, no control or invisible formatting characters (a right-to-left
     * override can make `exe.pdf` out of `fdp.exe`), no leading dots (hidden
     * files, `..`), and a bounded length that keeps the extension.
     */
    public fun safe(name: String): String {
        val cleaned = StringBuilder()
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            val drop = type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() ||
                type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
            when {
                // Before the control characters: a tab or line break separates words.
                Character.isWhitespace(cp) || type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt() -> cleaned.append(' ')
                drop -> Unit
                cp == '/'.code || cp == '\\'.code || cp == ':'.code || cp == '*'.code || cp == '?'.code || cp == '"'.code ||
                    cp == '<'.code || cp == '>'.code || cp == '|'.code -> cleaned.append('_')
                else -> cleaned.appendCodePoint(cp)
            }
        }
        var result = cleaned.toString().replace(Regex(" {2,}"), " ").trimStart('.', ' ').trimEnd('.', ' ')
        if (result.isEmpty()) return FALLBACK

        // Shorten in the middle of the base name, never inside a character, keeping the extension.
        if (result.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            val dot = result.lastIndexOf('.')
            val ext = if (dot > 0 && result.length - dot <= 16) result.substring(dot) else ""
            var base = result.substring(0, result.length - ext.length)
            while ((base + ext).toByteArray(Charsets.UTF_8).size > MAX_BYTES && base.isNotEmpty()) {
                base = base.substring(0, base.offsetByCodePoints(base.length, -1))
            }
            result = (base.trimEnd() + ext).ifEmpty { FALLBACK }
        }
        return result
    }

    private val MIME = Regex("^[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,63}/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,63}$")

    /** The announced type if it is a plain `type/subtype`, else a neutral one. */
    public fun safeMime(mime: String?): String = mime?.takeIf(MIME::matches)?.lowercase() ?: "application/octet-stream"
}
