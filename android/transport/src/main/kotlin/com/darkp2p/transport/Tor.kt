package com.darkp2p.transport

import okhttp3.Dns
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * "Resolves" every name to a placeholder that only carries the name itself.
 *
 * In Tor mode no name may reach a resolver on this device: not the system's,
 * not a DNS-over-TLS one. OkHttp insists on an address before it connects, so
 * it gets `0.0.0.0` labelled with the host name, and [Socks5hSocket] hands that
 * name to the proxy, which resolves it at the far end of the circuit.
 */
internal object TorDns : Dns {
    private val PLACEHOLDER = byteArrayOf(0, 0, 0, 0)

    override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByAddress(hostname, PLACEHOLDER))
}

/**
 * Sockets that reach their destination through a SOCKS5 proxy and always name
 * it by host name (address type 3, "SOCKS5h"), never by an address.
 *
 * @param isolation with Tor's default `IsolateSOCKSAuth`, connections with
 *   different credentials use different circuits. One value per room keeps
 *   rooms from being linked by their exit or rendezvous circuit.
 */
internal class Socks5hSocketFactory(private val proxy: InetSocketAddress, private val isolation: String?) : SocketFactory() {
    override fun createSocket(): Socket = Socks5hSocket(proxy, isolation)

    // OkHttp only uses the unconnected form. The others would need a resolved
    // address, which is exactly what this factory exists to avoid.
    override fun createSocket(host: String?, port: Int): Socket = throw IOException("unsupported")

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw IOException("unsupported")

    override fun createSocket(host: InetAddress?, port: Int): Socket = throw IOException("unsupported")

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = throw IOException("unsupported")
}

internal class Socks5hSocket(private val proxy: InetSocketAddress, private val isolation: String?) : Socket() {
    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        val target = endpoint as? InetSocketAddress ?: throw IOException("unsupported address")
        // hostString never triggers a lookup. A bare address here would mean something
        // resolved the name locally: refuse instead of connecting to it.
        val host = target.hostString
        if (host.isNullOrEmpty() || host.length > 255 || isAddressLiteral(host)) throw IOException("Tor mode connects by host name only")

        super.connect(proxy, timeout)
        val previousTimeout = soTimeout
        try {
            soTimeout = if (timeout > 0) timeout else HANDSHAKE_TIMEOUT_MS
            handshake(host, target.port)
            soTimeout = previousTimeout
        } catch (e: IOException) {
            runCatching { close() }
            throw e
        }
    }

    private fun handshake(host: String, port: Int) {
        val input = getInputStream()
        val output = getOutputStream()

        // Greeting: version 5; "no authentication", plus username/password when isolating.
        output.write(if (isolation != null) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        output.flush()
        val choice = input.readExactly(2)
        if (choice[0].toInt() != 5) throw IOException("not a SOCKS5 proxy")
        when (choice[1].toInt()) {
            0 -> Unit
            2 -> {
                val user = (isolation ?: throw IOException("proxy asked for credentials")).toByteArray(Charsets.US_ASCII)
                require(user.size in 1..255)
                // RFC 1929. Tor accepts any credentials and uses them only to pick the circuit.
                output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(1, '0'.code.toByte()))
                output.flush()
                val auth = input.readExactly(2)
                if (auth[1].toInt() != 0) throw IOException("SOCKS5 authentication refused")
            }
            else -> throw IOException("SOCKS5 proxy offers no usable authentication")
        }

        // CONNECT to a domain name (ATYP 3): the proxy does the resolving.
        val name = host.toByteArray(Charsets.US_ASCII)
        output.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port ushr 8).toByte(), port.toByte()))
        output.flush()
        val reply = input.readExactly(4)
        if (reply[0].toInt() != 5) throw IOException("bad SOCKS5 reply")
        if (reply[1].toInt() != 0) throw IOException("SOCKS5 connect failed (${reply[1].toInt() and 0xff})")
        // The bound address is of no use here; read past it.
        val skip = when (reply[3].toInt()) {
            1 -> 4
            4 -> 16
            3 -> input.readExactly(1)[0].toInt() and 0xff
            else -> throw IOException("bad SOCKS5 reply")
        }
        input.readExactly(skip + 2)
    }

    private fun InputStream.readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = read(out, read, n - read)
            if (r < 0) throw EOFException("SOCKS5 proxy closed the connection")
            read += r
        }
        return out
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 60_000

        val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        fun isAddressLiteral(host: String): Boolean = IPV4.matches(host) || host.contains(':')
    }
}
