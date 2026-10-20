package com.volna.player.net

import com.volna.player.util.DoH
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Крошечный локальный HTTP CONNECT-прокси перед ByeDPI.
 *
 * Зачем: SOCKS5-клиент (OkHttp) передаёт ByeDPI доменное имя, и тогда ByeDPI
 * резолвит его системным DNS, который в некоторых сетях подменяется. Этот
 * прокси сам резолвит домен через DoH и передаёт ByeDPI уже IP-адрес, а
 * ByeDPI по-прежнему применяет обход DPI к исходящему TCP-соединению.
 */
class LocalHttpProxy(private val upstreamSocksPort: Int) {

    private var server: ServerSocket? = null
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()

    val port: Int get() = server?.localPort ?: 0
    val isRunning: Boolean get() = server != null

    @Synchronized
    fun start(): Int {
        if (server != null) return port
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(name = "volna-http-proxy", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                sockets.add(client)
                thread(name = "volna-proxy-conn", isDaemon = true) {
                    try {
                        handle(client)
                    } catch (_: Exception) {
                    } finally {
                        runCatching { client.close() }
                        sockets.remove(client)
                    }
                }
            }
        }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        for (s in sockets) runCatching { s.close() }
        sockets.clear()
    }

    private fun handle(client: Socket) {
        val input = client.getInputStream()
        val header = readHeaders(input) ?: return
        val first = header.lineSequence().firstOrNull().orEmpty()
        if (!first.startsWith("CONNECT ", ignoreCase = true)) {
            client.getOutputStream().write(
                "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII),
            )
            return
        }
        val target = first.split(' ').getOrNull(1) ?: return
        val host = target.substringBeforeLast(':')
        val targetPort = target.substringAfterLast(':').toIntOrNull() ?: 443
        val address = resolve(host) ?: return

        val upstream = Socket()
        sockets.add(upstream)
        try {
            upstream.connect(InetSocketAddress("127.0.0.1", upstreamSocksPort), 6000)
            upstream.soTimeout = 15000
            socksConnect(upstream, address, targetPort)
            upstream.soTimeout = 0
            client.soTimeout = 0
            client.getOutputStream().write(
                "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII),
            )
            val clientOut = client.getOutputStream()
            val upstreamOut = upstream.getOutputStream()
            val a = thread(name = "volna-proxy-up", isDaemon = true) {
                copy(input, upstreamOut)
                runCatching { upstream.close() }
            }
            val b = thread(name = "volna-proxy-down", isDaemon = true) {
                copy(upstream.getInputStream(), clientOut)
                runCatching { client.close() }
            }
            a.join()
            b.join()
        } finally {
            runCatching { upstream.close() }
            sockets.remove(upstream)
        }
    }

    private fun socksConnect(socket: Socket, address: InetAddress, port: Int) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        out.write(byteArrayOf(5, 1, 0))
        out.flush()
        val greeting = ByteArray(2)
        if (input.read(greeting) != 2 || greeting[0] != 5.toByte() || greeting[1] != 0.toByte()) {
            throw IllegalStateException("SOCKS greeting failed")
        }
        val raw = address.address
        val atyp = if (raw.size == 16) 4 else 1
        val req = ArrayList<Byte>(6 + raw.size)
        req.add(5)
        req.add(1)
        req.add(0)
        req.add(atyp.toByte())
        for (b in raw) req.add(b)
        req.add(((port shr 8) and 0xff).toByte())
        req.add((port and 0xff).toByte())
        out.write(req.toByteArray())
        out.flush()
        val head = ByteArray(4)
        if (input.read(head) != 4 || head[0] != 5.toByte() || head[1] != 0.toByte()) {
            throw IllegalStateException("SOCKS connect failed")
        }
        when (head[3].toInt()) {
            1 -> readFully(input, 4)
            4 -> readFully(input, 16)
            3 -> {
                val len = input.read()
                if (len > 0) readFully(input, len)
            }
        }
        readFully(input, 2)
    }

    private fun resolve(host: String): InetAddress? {
        val addrs = runCatching { DoH.dns.lookup(host) }.getOrDefault(emptyList())
        addrs.firstOrNull { it.address.size == 4 }?.let { return it }
        addrs.firstOrNull()?.let { return it }
        return runCatching {
            InetAddress.getAllByName(host).firstOrNull { it.address.size == 4 }
                ?: InetAddress.getAllByName(host).firstOrNull()
        }.getOrNull()
    }

    private fun readHeaders(input: InputStream): String? {
        val buf = StringBuilder()
        var prev = -1
        var count = 0
        while (count < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            buf.append(b.toChar())
            count++
            if (prev == '\r'.code && b == '\n'.code && buf.endsWith("\r\n\r\n")) {
                return buf.toString()
            }
            prev = b
        }
        return null
    }

    private fun readFully(input: InputStream, size: Int) {
        val buf = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = input.read(buf, off, size - off)
            if (n < 0) break
            off += n
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
        }
    }
}
