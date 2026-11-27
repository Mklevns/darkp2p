package com.darkp2p.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileDropServerTest {
    private val token = "A".repeat(20) + "b-_" + "9".repeat(20)

    private class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private class Drop(val server: FileDropServer, val delivered: CountDownLatch, val deliveries: AtomicInteger, val progress: AtomicLong, val opens: AtomicInteger)

    private fun drop(name: String, content: ByteArray, size: Long = content.size.toLong(), open: (() -> InputStream)? = null): Drop {
        val delivered = CountDownLatch(1)
        val deliveries = AtomicInteger()
        val progress = AtomicLong()
        val opens = AtomicInteger()
        val file = DropFile(name, size) {
            opens.incrementAndGet()
            open?.invoke() ?: ByteArrayInputStream(content)
        }
        val server = FileDropServer(
            file, token,
            object : FileDropServer.Listener {
                override fun onProgress(sent: Long) {
                    progress.set(sent)
                }

                override fun onDelivered() {
                    deliveries.incrementAndGet()
                    delivered.countDown()
                }
            },
        )
        server.start()
        return Drop(server, delivered, deliveries, progress, opens)
    }

    // A bare socket, as Tor would connect: no HTTP client in between to tidy anything up.
    private fun request(port: Int, head: String): Reply = Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port).use { socket ->
        socket.soTimeout = 20_000
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            flush()
        }
        val all = socket.getInputStream().readBytes()
        val text = String(all, Charsets.ISO_8859_1)
        val end = text.indexOf("\r\n\r\n")
        check(end != -1) { "no response head in: ${text.take(80)}" }
        val lines = text.substring(0, end).split("\r\n")
        val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        Reply(lines[0].split(' ')[1].toInt(), headers, all.copyOfRange(end + 4, all.size))
    }

    private fun get(port: Int, path: String, method: String = "GET") = request(port, "$method $path HTTP/1.1\r\nHost: example.onion\r\nUser-Agent: test\r\n\r\n")

    @Test
    fun `the page and the file are served under the token, and one complete download ends it`() {
        val content = ByteArray(700_001).also { Random(7).nextBytes(it) }
        val d = drop("holiday photos.zip", content)
        d.server.use { server ->
            val page = get(server.port, "/$token")
            assertEquals(200, page.status)
            assertEquals("text/html; charset=utf-8", page.headers["content-type"])
            val html = String(page.body, Charsets.UTF_8)
            assertTrue("holiday photos.zip" in html && "/$token/file" in html && "683.6 KB" in html, html)
            // Nothing is loaded from anywhere else, and the page can run no script.
            assertFalse(Regex("""(src|href)\s*=\s*["']?(https?:)?//""").containsMatchIn(html))
            assertFalse("<script" in html)
            assertEquals("default-src 'none'; style-src 'unsafe-inline'", page.headers["content-security-policy"])
            assertEquals("no-store", page.headers["cache-control"])
            assertEquals(0, d.opens.get())

            // HEAD describes the file without sending or counting it.
            val head = get(server.port, "/$token/file", "HEAD")
            assertEquals(200, head.status)
            assertEquals(content.size.toString(), head.headers["content-length"])
            assertEquals(0, head.body.size)
            assertEquals(0, d.deliveries.get())

            val file = get(server.port, "/$token/file")
            assertEquals(200, file.status)
            assertEquals("application/octet-stream", file.headers["content-type"])
            assertEquals(content.size.toString(), file.headers["content-length"])
            assertEquals("attachment; filename=\"holiday photos.zip\"; filename*=UTF-8''holiday%20photos.zip", file.headers["content-disposition"])
            assertContentEquals(content, file.body)
            assertTrue(d.delivered.await(5, TimeUnit.SECONDS))
            assertEquals(content.size.toLong(), d.progress.get())

            // Once delivered, the link is spent: page and file alike.
            assertEquals(410, get(server.port, "/$token").status)
            assertEquals(410, get(server.port, "/$token/file").status)
            assertEquals(1, d.deliveries.get())
            assertEquals(1, d.opens.get())
        }
    }

    @Test
    fun `without the token there is nothing here, whatever is asked`() {
        val d = drop("secret.txt", "the secret".toByteArray())
        d.server.use { server ->
            val wrong = token.dropLast(1) + "x"
            for (path in listOf("/", "/file", "/$wrong", "/$wrong/file", "/${token.dropLast(1)}", "/${token}x", "/favicon.ico", "/$token/other", "/$token/file/more", "//$token")) {
                val reply = get(server.port, path)
                assertEquals(404, reply.status, path)
                assertFalse("secret" in String(reply.body, Charsets.UTF_8), path)
            }
            // A query string or a trailing slash does not turn the right token into a wrong one.
            assertEquals(200, get(server.port, "/$token/").status)
            assertEquals(200, get(server.port, "/$token?x=1").status)
            // Only reading is allowed, and a request that is not HTTP gets nothing.
            assertEquals(405, get(server.port, "/$token/file", "POST").status)
            assertEquals(405, get(server.port, "/$token", "DELETE").status)
            assertEquals(400, request(server.port, "hello\r\n\r\n").status)
            assertEquals(400, request(server.port, "GET $token HTTP/1.1\r\n\r\n").status)
            assertEquals(400, request(server.port, "GET /$token HTTP/1.1\r\n" + "X: ${"y".repeat(9000)}\r\n\r\n").status)
            assertEquals(0, d.opens.get())
            assertEquals(0, d.deliveries.get())
        }
    }

    @Test
    fun `a file name cannot break out of the page or the headers`() {
        val name = "<img src=x onerror=alert(1)>\"'&\r\nSet-Cookie: a=b; ü.txt"
        val d = drop(name, byteArrayOf(1, 2, 3))
        d.server.use { server ->
            val html = String(get(server.port, "/$token").body, Charsets.UTF_8)
            assertFalse("<img" in html)
            assertTrue("&lt;img src=x onerror=alert(1)&gt;&quot;&#39;&amp;" in html, html)
            val file = get(server.port, "/$token/file")
            assertEquals(null, file.headers["set-cookie"])
            val disposition = file.headers.getValue("content-disposition")
            assertFalse('\r' in disposition || '\n' in disposition)
            assertTrue(disposition.startsWith("attachment; filename=\"<img src=x onerror=alert(1)>_'&__Set-Cookie: a=b; _.txt\"; filename*=UTF-8''%3Cimg"), disposition)
            assertContentEquals(byteArrayOf(1, 2, 3), file.body)
        }
    }

    @Test
    fun `a large file is streamed piece by piece, never held whole`() {
        // 48 MB that exist nowhere: each read hands out the next piece of a repeating pattern.
        val size = 48L * 1024 * 1024
        val pattern = ByteArray(8191) { (it * 31).toByte() }
        var largestRead = 0
        val d = drop("big.bin", ByteArray(0), size) {
            object : InputStream() {
                var at = 0L

                override fun read(): Int = error("read one byte at a time")

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (at >= size) return -1
                    largestRead = maxOf(largestRead, len)
                    val n = minOf(len.toLong(), size - at, 5000L).toInt() // short reads, as a content provider may give
                    for (i in 0 until n) b[off + i] = pattern[((at + i) % pattern.size).toInt()]
                    at += n
                    return n
                }
            }
        }
        d.server.use { server ->
            val expected = MessageDigest.getInstance("SHA-256")
            var left = size
            var at = 0L
            val piece = ByteArray(65_536)
            while (left > 0) {
                val n = minOf(piece.size.toLong(), left).toInt()
                for (i in 0 until n) piece[i] = pattern[((at + i) % pattern.size).toInt()]
                expected.update(piece, 0, n)
                at += n
                left -= n
            }

            val actual = MessageDigest.getInstance("SHA-256")
            var received = 0L
            Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), server.port).use { socket ->
                socket.getOutputStream().apply {
                    write("GET /$token/file HTTP/1.1\r\n\r\n".toByteArray())
                    flush()
                }
                val input = socket.getInputStream()
                val head = ByteArrayOutputStream()
                while (!head.toString("ISO-8859-1").endsWith("\r\n\r\n")) head.write(input.read())
                val buffer = ByteArray(65_536)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    actual.update(buffer, 0, n)
                    received += n
                }
            }
            assertEquals(size, received)
            assertContentEquals(expected.digest(), actual.digest())
            assertTrue(largestRead <= 64 * 1024, "asked the source for $largestRead bytes at once")
            assertTrue(d.delivered.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a download cut short leaves the link good, and closing ends everything`() {
        val content = ByteArray(4 * 1024 * 1024) { it.toByte() }
        val d = drop("retry.bin", content)
        val port = d.server.port
        Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port).use { socket ->
            socket.getOutputStream().apply {
                write("GET /$token/file HTTP/1.1\r\n\r\n".toByteArray())
                flush()
            }
            socket.getInputStream().read(ByteArray(1000))
        } // the receiver goes away early
        assertFalse(d.delivered.await(300, TimeUnit.MILLISECONDS))
        assertContentEquals(content, get(port, "/$token/file").body)
        assertTrue(d.delivered.await(5, TimeUnit.SECONDS))
        assertEquals(2, d.opens.get())

        d.server.close()
        d.server.close() // twice is fine
        assertFailsWith<java.io.IOException> { get(port, "/$token") }
    }

    @Test
    fun `it listens on loopback only and refuses a weak token`() {
        val d = drop("a.txt", byteArrayOf(1))
        d.server.use { server ->
            assertTrue(server.port in 1024..65535)
            // Reachable on loopback; a connection to any other address of this machine is refused.
            assertEquals(200, get(server.port, "/$token").status)
            val other = java.net.NetworkInterface.getNetworkInterfaces().asSequence().flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            if (other != null) {
                assertFailsWith<java.io.IOException> { Socket().use { it.connect(java.net.InetSocketAddress(other, server.port), 2_000) } }
            }
        }
        val listener = object : FileDropServer.Listener {
            override fun onProgress(sent: Long) = Unit

            override fun onDelivered() = Unit
        }
        val file = DropFile("a", 1) { ByteArrayInputStream(byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { FileDropServer(file, "short", listener) }
        assertFailsWith<IllegalArgumentException> { FileDropServer(file, "a/".repeat(30), listener) }
    }
}
