package com.darkp2p.transport

import com.darkp2p.protocol.Notice
import com.darkp2p.protocol.RoomLink
import com.darkp2p.protocol.RoomState
import com.darkp2p.protocol.Transport
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A SOCKS5 proxy that records what each client asked for and connects every
 * request to one local port, as Tor would connect an onion name to its service.
 */
internal class RecordingSocksProxy(private val targetPort: Int, private val refuse: Boolean = false) : AutoCloseable {
    class Request(val methods: List<Int>, val user: String?, val addressType: Int, val host: String, val port: Int)

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val requests = CopyOnWriteArrayList<Request>()
    val address get() = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                thread(isDaemon = true) { runCatching { serve(client) } }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        check(input.read() == 5)
        val methods = List(input.read()) { input.read() }
        var user: String? = null
        if (2 in methods) {
            output.write(byteArrayOf(5, 2)); output.flush()
            check(input.read() == 1)
            user = String(ByteArray(input.read()).also(input::readFully))
            ByteArray(input.read()).also(input::readFully)
            output.write(byteArrayOf(1, 0)); output.flush()
        } else {
            output.write(byteArrayOf(5, 0)); output.flush()
        }
        check(input.read() == 5 && input.read() == 1 && input.read() == 0)
        val type = input.read()
        val host = when (type) {
            3 -> String(ByteArray(input.read()).also(input::readFully))
            1 -> ByteArray(4).also(input::readFully).joinToString(".") { (it.toInt() and 0xff).toString() }
            else -> ByteArray(16).also(input::readFully).let { "ipv6" }
        }
        val port = input.readUnsignedShort()
        requests += Request(methods, user, type, host, port)
        if (refuse) {
            output.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()
            client.close()
            return
        }
        val upstream = Socket(InetAddress.getLoopbackAddress(), targetPort)
        output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()
        val a = thread(isDaemon = true) { runCatching { client.getInputStream().copyTo(upstream.getOutputStream()) }; runCatching { upstream.close() } }
        runCatching { upstream.getInputStream().copyTo(output) }
        runCatching { client.close() }
        a.join()
    }

    override fun close() = server.close()
}

class TorTest {
    private val onionHost = "darkp2ptestonionaddressxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.onion"

    @Test
    fun `an onion room is reached by name through the proxy and nothing is resolved here`() = runBlocking<Unit> {
        assertEquals(62, onionHost.length)
        WebSide().use { web ->
            RecordingSocksProxy(web.port).use { proxy ->
                // The same room, named as the page names it and as an onion service names it.
                val local = web.newLink()
                val onion = RoomLink.parse("http://$onionHost:${web.port}/#${local.fragment}")!!
                assertTrue(onion.isOnion)
                val page = web.page("p", local)
                page.waitLog("^Connected to signaling broker")

                val client = HttpClients.create(NetworkProfile.Tor(proxy.address, isolation = "room-1"))
                AppPeer(onion, client).use { app ->
                    val ready = app.awaitReady()
                    assertEquals(Transport.WS_RELAY, ready.transport)
                    val data = fileBytes("tor", 300_000)
                    app.send("tor.bin", data); page.accept()
                    assertEquals(sha256(data) to data.size, page.download(0))
                    page.send("back.bin", "back", 90_000)
                    app.awaitNotice(Notice.SAVED)
                    assertContentEquals(fileBytes("back", 90_000), app.io.committed.single())
                }
                // Every connection named the onion host (address type 3) and carried the isolation value.
                assertTrue(proxy.requests.isNotEmpty())
                for (r in proxy.requests) {
                    assertEquals(3, r.addressType)
                    assertEquals(onionHost, r.host)
                    assertEquals(web.port, r.port)
                    assertEquals("room-1", r.user)
                }
                assertEquals(emptyList(), page.errors())
            }
        }
    }

    @Test
    fun `a Tor client never asks the device's resolver and cannot connect by address`() {
        // The "address" OkHttp is given is a label, not a lookup result.
        val placeholder = TorDns.lookup("example.onion").single()
        assertEquals("example.onion", placeholder.hostName)
        assertContentEquals(byteArrayOf(0, 0, 0, 0), placeholder.address)
        // A name that does not exist resolves the same way: nothing was asked.
        assertEquals("no-such-host.invalid", TorDns.lookup("no-such-host.invalid").single().hostName)

        RecordingSocksProxy(1).use { proxy ->
            val factory = Socks5hSocketFactory(proxy.address, null)
            // An address instead of a name means something resolved locally: refused before any connection.
            assertFailsWith<IOException> { factory.createSocket().connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)), 80), 1000) }
            assertFailsWith<IOException> { factory.createSocket().connect(InetSocketAddress("2001:db8::1", 80), 1000) }
            assertFailsWith<IOException> { factory.createSocket("example.org", 80) }
            assertEquals(0, proxy.requests.size)
        }
        val tor = HttpClients.create(NetworkProfile.Tor(InetSocketAddress(InetAddress.getLoopbackAddress(), 9)))
        assertTrue(tor.dns === TorDns)
        assertFalse(HttpClients.create(NetworkProfile.Clearnet).dns === TorDns)
        assertEquals(java.net.Proxy.NO_PROXY, tor.proxy)
    }

    @Test
    fun `clearnet names also go to the proxy unresolved, and a proxy failure is an error, not a fallback`() {
        WebSide().use { web ->
            RecordingSocksProxy(web.port).use { proxy ->
                val client = HttpClients.create(NetworkProfile.Tor(proxy.address))
                client.newCall(Request.Builder().url("http://some-clearnet-site.example:${web.port}/health").build()).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("ok", response.body.string())
                }
                val r = proxy.requests.single()
                assertEquals(3, r.addressType)
                assertEquals("some-clearnet-site.example", r.host)
                assertEquals(null, r.user) // no isolation value: no credentials offered
                assertEquals(listOf(0), r.methods)
            }
            RecordingSocksProxy(web.port, refuse = true).use { proxy ->
                val client = HttpClients.create(NetworkProfile.Tor(proxy.address))
                assertFailsWith<IOException> { client.newCall(Request.Builder().url("http://127.0.0.1.example:${web.port}/health").build()).execute() }
                assertEquals(1, proxy.requests.size)
            }
            // No proxy listening at all: the request fails; it is not sent directly.
            val dead = ServerSocket(0).use { it.localPort }
            val client = HttpClients.create(NetworkProfile.Tor(InetSocketAddress(InetAddress.getLoopbackAddress(), dead)))
            assertFailsWith<IOException> { client.newCall(Request.Builder().url("http://localhost:${web.port}/health").build()).execute() }
        }
    }

    @Test
    fun `an Orbot port is used only if a SOCKS5 proxy answers on it`() = runBlocking<Unit> {
        WebSide().use { web ->
            RecordingSocksProxy(web.port).use { proxy ->
                val profile = Orbot.profile(proxy.address.port, isolation = "r")
                assertEquals(NetworkProfile.Tor(proxy.address, "r"), profile)
                assertTrue(profile!!.socks.address.isLoopbackAddress)
            }
            // Something else is listening there (here: the web server): not a proxy.
            assertEquals(null, Orbot.profile(web.port, null))
        }
        val dead = ServerSocket(0).use { it.localPort }
        assertEquals(null, Orbot.profile(dead, null))
        assertFalse("9050" in NetworkProfile.Tor(InetSocketAddress("127.0.0.1", 9050), "secret-isolation").toString().replace("Tor(9050)", ""))
    }

    @Test
    fun `an onion room refuses to start on a clearnet client`() {
        val link = RoomLink.parse("http://$onionHost/#${"ab".repeat(16)}:${"A".repeat(43)}")!!
        assertFailsWith<IllegalArgumentException> { AppPeer(link, HttpClients.create(NetworkProfile.Clearnet)) }
        val endpoint = ServerEndpoint(link.origin)
        assertEquals("ws://$onionHost/", endpoint.webSocketUrl)
        assertEquals("wss://darkp2p.com/", ServerEndpoint("https://darkp2p.com").webSocketUrl)
        assertTrue(ServerEndpoint("https://darkp2p.com").allows(NetworkProfile.Clearnet))
        assertFailsWith<IllegalArgumentException> { ServerEndpoint("https://darkp2p.com/") }
        assertIs<RoomState>(RoomState.WaitingForPeer)
    }
}
