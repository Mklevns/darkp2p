package com.darkp2p.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The file drop's server on Android itself. The host-JVM tests cover what it
 * serves; this covers where it listens, which is the one thing that differed
 * on a device: on Android "the loopback address" is `::1`, while Tor forwards
 * an onion service to `127.0.0.1`. The first build listened on the wrong one
 * and every link was unreachable.
 */
@RunWith(AndroidJUnit4::class)
class FileDropOnDeviceTest {
    private val token = "A".repeat(43)

    @Test
    fun answersOnIpv4LoopbackWhereTorForwards() {
        val content = ByteArray(300_000) { (it * 7).toByte() }
        val delivered = CountDownLatch(1)
        val server = FileDropServer(
            DropFile("on-device.bin", content.size.toLong()) { ByteArrayInputStream(content) }, token,
            object : FileDropServer.Listener {
                override fun onProgress(sent: Long) = Unit

                override fun onDelivered() = delivered.countDown()
            },
        )
        server.start()
        server.use {
            // Exactly the address handed to Tor: FileDropServer.HOST, by its bytes, no lookup.
            assertEquals("127.0.0.1", FileDropServer.HOST)
            val v4 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            val reply = Socket().use { socket ->
                socket.connect(InetSocketAddress(v4, server.port), 5_000)
                socket.soTimeout = 20_000
                socket.getOutputStream().apply {
                    write("GET /$token/file HTTP/1.1\r\nHost: example.onion\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                socket.getInputStream().readBytes()
            }
            val text = String(reply, Charsets.ISO_8859_1)
            val end = text.indexOf("\r\n\r\n")
            assertTrue(text.startsWith("HTTP/1.1 200 OK"))
            assertArrayEquals(content, reply.copyOfRange(end + 4, reply.size))
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
        }
    }
}
