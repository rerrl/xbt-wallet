package com.xbtwallet.app.electrum

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A SOCKS5 proxy that answers CONNECT requests the way Tor does when it cannot
 * serve them: reply `0x01`, the "general failure" code, which `java.net` turns
 * into `SocketException: SOCKS server general failure`.
 *
 * It exists to exercise [TorTransport]'s retry loop without a Tor daemon: the
 * first [failures] CONNECTs are refused, and every one after that succeeds (by
 * keeping the connection open, which is all a transport test needs).
 */
internal class FakeSocksProxy(failures: Int = Int.MAX_VALUE) : AutoCloseable {

    private val remainingFailures = AtomicInteger(failures)

    private val serverSocket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())

    val port: Int get() = serverSocket.localPort

    /** Every CONNECT the proxy was asked to serve, in order. */
    val requests = CopyOnWriteArrayList<Pair<String, Int>>()

    /** How many connections it has accepted, refused or not. */
    val connectionCount: Int get() = requests.size

    private val sockets = CopyOnWriteArrayList<Socket>()

    private val worker = Thread({ acceptLoop() }, "fake-socks-proxy").apply {
        isDaemon = true
        start()
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val client = runCatching { serverSocket.accept() }.getOrNull() ?: return
            sockets += client
            Thread({ runCatching { serve(client) } }, "fake-socks-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(client: Socket) {
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()

        // Greeting: version 5, at least one method offered.
        input.readUnsignedByte()
        val methodCount = input.readUnsignedByte()
        repeat(methodCount) { input.readUnsignedByte() }
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        // Request: version, command, reserved, address type, address, port.
        input.readUnsignedByte()
        input.readUnsignedByte()
        input.readUnsignedByte()
        val addressType = input.readUnsignedByte()
        val host = when (addressType) {
            0x01 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 0xFF).toString() }
            0x03 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.US_ASCII)
            else -> ByteArray(16).also { input.readFully(it) }.joinToString(":") { "%02x".format(it) }
        }
        val port = (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        requests += host to port

        if (remainingFailures.getAndUpdate { if (it > 0) it - 1 else it } > 0) {
            // 0x01 = general failure, exactly what Tor sends for a stream it
            // will not (a v2 onion) or cannot yet (pre-descriptor) serve.
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            runCatching { client.close() }
            return
        }

        // Success: the "connection" stays open with no backend behind it.
        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    override fun close() {
        runCatching { serverSocket.close() }
        sockets.forEach { runCatching { it.close() } }
        worker.interrupt()
    }
}
