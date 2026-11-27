package com.darkp2p.transport

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One file offered for download. [open] is called once per download and must return a fresh stream from the start. */
public class DropFile(public val name: String, public val size: Long, public val open: () -> InputStream)

/**
 * A one-file HTTP server for the onion file drop.
 *
 * It listens on the loopback interface only, on a port the system picks, so
 * nothing on the network can reach it: the only way in is the onion service
 * that forwards to this port. It answers two paths, both under a random token:
 * a small page and the file itself. Everything else is a plain 404 that says
 * nothing about what exists.
 *
 * The file is streamed from [DropFile.open] in small pieces and never copied
 * to disk or held in memory, so its size is not limited by this class. After
 * one complete download the file is gone (410) and [Listener.onDelivered] is
 * called, so the owner can take the whole thing down.
 */
public class FileDropServer(private val file: DropFile, private val token: String, private val listener: Listener) : Closeable {
    public interface Listener {
        /** Bytes of the file sent so far in the download that is furthest along. */
        public fun onProgress(sent: Long)

        /** A download received every byte. Called once. */
        public fun onDelivered()
    }

    // 127.0.0.1 by its bytes: the onion service forwards there, and on Android
    // "the loopback address" is the IPv6 one, where nothing would be listening.
    private val socket = ServerSocket(0, BACKLOG, InetAddress.getByAddress(LOOPBACK_V4))
    private val workers = Executors.newFixedThreadPool(MAX_CONNECTIONS) { r -> Thread(r, "file-drop").apply { isDaemon = true } }
    private val closed = AtomicBoolean()
    private val delivered = AtomicBoolean()

    /** The loopback port to forward the onion service to. */
    public val port: Int get() = socket.localPort

    init {
        require(token.length >= MIN_TOKEN_LENGTH && token.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "the token must be long and URL-safe" }
    }

    public fun start() {
        Thread({
            while (!closed.get()) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                try {
                    workers.execute { client.use(::serve) }
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    client.close()
                }
            }
        }, "file-drop-accept").apply { isDaemon = true }.start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            socket.close()
        } catch (e: IOException) {
            // Closing is all that matters.
        }
        workers.shutdownNow()
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = IO_TIMEOUT_MS
            val out = client.getOutputStream()
            val request = readRequestLine(BufferedInputStream(client.getInputStream())) ?: return respond(out, 400, "Bad Request", head = false)
            val (method, target) = request
            val head = method == "HEAD"
            if (method != "GET" && !head) return respond(out, 405, "Method Not Allowed", head = false)

            val segments = target.substringBefore('?').substringBefore('#').removePrefix("/").removeSuffix("/").split('/')
            val known = MessageDigest.isEqual(segments[0].toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
            when {
                !known -> respond(out, 404, "Not Found", head)
                delivered.get() -> respond(out, 410, "Gone", head)
                segments.size == 1 -> page(out, head)
                segments.size == 2 && segments[1] == "file" -> download(client, out, head)
                else -> respond(out, 404, "Not Found", head)
            }
        } catch (e: IOException) {
            // The receiver went away, or Tor closed the stream: the link stays good for another try.
        }
    }

    // Only the request line matters. The rest of the head is read and dropped, within a limit.
    private fun readRequestLine(input: InputStream): Pair<String, String>? {
        val head = StringBuilder()
        var matched = 0
        while (matched < 4) {
            if (head.length >= MAX_HEAD_BYTES) return null
            val b = input.read()
            if (b == -1) return null
            head.append(b.toChar())
            matched = when {
                b == END[matched].code -> matched + 1
                b == END[0].code -> 1
                else -> 0
            }
        }
        val parts = head.lineSequence().first().trim().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.") || !parts[1].startsWith("/")) return null
        return parts[0] to parts[1]
    }

    private fun page(out: OutputStream, head: Boolean) {
        val name = escapeHtml(file.name)
        val body = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>DarkP2P file</title>
            <style>body{font:16px/1.5 sans-serif;max-width:34em;margin:3em auto;padding:0 1em}a.b{display:inline-block;padding:.7em 1.4em;background:#00735a;color:#fff;text-decoration:none;border-radius:2em}small{color:#555}</style>
            </head><body>
            <h1>DarkP2P</h1>
            <p><strong>$name</strong><br><small>${humanSize(file.size)}</small></p>
            <p><a class="b" href="/$token/file" download>Download</a></p>
            <p><small>This file comes straight from the sender's phone over Tor. The link works once: after a complete download, or when the sender stops sharing, it is gone.</small></p>
            </body></html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        writeHead(out, 200, "OK", "text/html; charset=utf-8", body.size.toLong(), emptyList())
        if (!head) out.write(body)
        out.flush()
    }

    private fun download(client: Socket, out: OutputStream, head: Boolean) {
        val ascii = file.name.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
        val encoded = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        writeHead(
            out, 200, "OK", "application/octet-stream", file.size,
            listOf("Content-Disposition: attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"),
        )
        if (head) {
            out.flush()
            return
        }
        var sent = 0L
        var reported = 0L
        file.open().use { source ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (sent < file.size) {
                val n = source.read(buffer, 0, minOf(buffer.size.toLong(), file.size - sent).toInt())
                if (n == -1) throw IOException("the file ended early")
                out.write(buffer, 0, n)
                sent += n
                if (sent - reported >= PROGRESS_STEP_BYTES) {
                    reported = sent
                    listener.onProgress(sent)
                }
            }
        }
        out.flush()
        listener.onProgress(sent)
        // Written is not received: what reads this socket (Tor) may still hold megabytes
        // it has not passed on. Say we are done, then wait for it to hang up, which it
        // does only once everything has gone out. A receiver that gives up earlier
        // breaks the connection instead, and the write above or the read below throws.
        client.shutdownOutput()
        client.soTimeout = DRAIN_TIMEOUT_MS
        val rest = client.getInputStream()
        while (rest.read() != -1) {
            // Nothing is expected; anything sent is ignored.
        }
        if (delivered.compareAndSet(false, true)) listener.onDelivered()
    }

    private fun respond(out: OutputStream, status: Int, reason: String, head: Boolean) {
        val body = "$status $reason\n".toByteArray(Charsets.UTF_8)
        writeHead(out, status, reason, "text/plain; charset=utf-8", body.size.toLong(), emptyList())
        if (!head) out.write(body)
        out.flush()
    }

    private fun writeHead(out: OutputStream, status: Int, reason: String, type: String, length: Long, extra: List<String>) {
        val lines = listOf(
            "HTTP/1.1 $status $reason",
            "Content-Type: $type",
            "Content-Length: $length",
            "Connection: close",
            "Cache-Control: no-store",
            "X-Content-Type-Options: nosniff",
            "Referrer-Policy: no-referrer",
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'",
        ) + extra
        out.write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
    }

    public companion object {
        /** Tokens shorter than this are refused: the token is the only thing between the onion address and the file. */
        public const val MIN_TOKEN_LENGTH: Int = 32

        /** The address this server listens on, for whoever forwards to it. */
        public const val HOST: String = "127.0.0.1"

        private val LOOPBACK_V4 = byteArrayOf(127, 0, 0, 1)
        private const val BACKLOG = 8
        private const val MAX_CONNECTIONS = 4
        private const val IO_TIMEOUT_MS = 60_000
        private const val DRAIN_TIMEOUT_MS = 30 * 60_000
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val CHUNK_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256 * 1024L
        private const val END = "\r\n\r\n"

        internal fun escapeHtml(text: String): String = buildString {
            for (c in text) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> if (c.code >= 0x20) append(c)
                }
            }
        }

        internal fun humanSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
        }
    }
}
