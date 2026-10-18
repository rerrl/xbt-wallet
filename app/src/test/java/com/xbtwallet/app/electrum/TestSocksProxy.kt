package com.xbtwallet.app.electrum

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A minimal SOCKS5 proxy for unit tests: just enough of RFC 1928 to prove that
 * [TorTransport] performs a real SOCKS handshake and hands the *host name* to the
 * proxy (ATYP 0x03) rather than resolving it locally.
 *
 * It accepts the "no authentication" method, records every CONNECT request, and
 * then relays bytes between the client and a fixed backend port — which is how a
 * test drives an Electrum handshake "through Tor" without a Tor daemon.
 *
 * Domain names are recorded and never resolved: a `.onion` name could not be.
 */
internal class TestSocksProxy(private val backendPort: Int) : AutoCloseable {

    /** One recorded CONNECT: the host the client asked for and the port. */
    data class Request(val host: String, val port: Int, val addressType: Int)

    private val serverSocket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())

    val port: Int get() = serverSocket.localPort

    val requests = CopyOnWriteArrayList<Request>()

    @Volatile
    var failure: Throwable? = null
        private set

    private val sockets = CopyOnWriteArrayList<Socket>()

    private val worker = Thread({ acceptLoop() }, "test-socks-proxy").apply {
        isDaemon = true
        start()
    }

    private fun acceptLoop() {
        try {
            while (!serverSocket.isClosed) {
                val client = serverSocket.accept()
                sockets += client
                Thread({ runCatching { serve(client) }.onFailure { failure = it } }, "test-socks-conn").apply {
                    isDaemon = true
                    start()
                }
            }
        } catch (error: Throwable) {
            if (!serverSocket.isClosed) failure = error
        }
    }

    private fun serve(client: Socket) {
        client.use { connection ->
            val input = DataInputStream(connection.getInputStream())
            val output = connection.getOutputStream()

            // Greeting: version 5, one method offered (0x00, no auth).
            val version = input.readUnsignedByte()
            require(version == 5) { "expected SOCKS version 5, got $version" }
            val methodCount = input.readUnsignedByte()
            repeat(methodCount) { input.readUnsignedByte() }
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // Request: version, command, reserved, address type.
            input.readUnsignedByte()
            val command = input.readUnsignedByte()
            input.readUnsignedByte()
            val addressType = input.readUnsignedByte()
            require(command == 1) { "expected CONNECT (1), got $command" }

            val host = when (addressType) {
                0x01 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 0xFF).toString() }
                0x03 -> {
                    val length = input.readUnsignedByte()
                    ByteArray(length).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                }
                0x04 -> ByteArray(16).also { input.readFully(it) }.joinToString(":") { "%02x".format(it) }
                else -> error("unsupported address type $addressType")
            }
            val requestedPort = (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
            requests += Request(host = host, port = requestedPort, addressType = addressType)

            // Success, bound address 0.0.0.0:0. The backend stands in for
            // "whatever the proxy resolved the name to".
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            Socket(InetAddress.getLoopbackAddress(), backendPort).use { backend ->
                sockets += backend
                val upstream = relay(connection.getInputStream(), backend.getOutputStream())
                val downstream = relay(backend.getInputStream(), connection.getOutputStream())
                upstream.join()
                downstream.join()
            }
        }
    }

    private fun relay(from: InputStream, to: OutputStream): Thread {
        val thread = Thread({
            val buffer = ByteArray(8 * 1024)
            runCatching {
                while (true) {
                    val read = from.read(buffer)
                    if (read < 0) break
                    to.write(buffer, 0, read)
                    to.flush()
                }
            }
            runCatching { to.close() }
        }, "test-socks-relay")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    override fun close() {
        runCatching { serverSocket.close() }
        sockets.forEach { runCatching { it.close() } }
        worker.interrupt()
    }
}
