package com.xbtwallet.app.electrum

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny stand-in for electrs, used by the unit tests to drive
 * [ElectrumClient] without a real server.
 *
 * Speaks newline-delimited JSON-RPC over loopback TCP: every request line is
 * parsed, recorded and handed to [respond], whose answer is written back as
 * `{"id":…,"result":…}` or `{"id":…,"error":…}`.
 *
 * @param respond decides the reply for a request (method, params, id).
 * @param hangUpOnConnect close each connection as soon as it is accepted —
 *   used to simulate a peer that dies during the TLS handshake.
 * @param greeting if set, write this text to the socket immediately after
 *   accepting, before speaking JSON-RPC — used to make a TLS handshake fail
 *   deterministically ("not an SSL server").
 */
class MockElectrumServer(
    private val hangUpOnConnect: Boolean = false,
    private val greeting: String? = null,
    private val respond: (method: String, params: List<Any?>, id: Any?) -> Reply,
) : AutoCloseable {

    /** What the mock should answer with. */
    sealed interface Reply {
        /** `{"id":…,"result":<value>}` */
        data class Result(val value: Any?) : Reply

        /** `{"id":…,"error":{"code":…,"message":…}}` */
        data class Error(val code: Int, val message: String) : Reply

        /** Close the connection without answering. */
        data object HangUp : Reply
    }

    private val serverSocket = ServerSocket(0, 4, InetAddress.getLoopbackAddress())

    /** Port the mock is listening on. */
    val port: Int get() = serverSocket.localPort

    /** Every request the mock received, in arrival order. */
    val requests = CopyOnWriteArrayList<Map<String, Any?>>()

    /** Non-null when the mock's own worker thread blew up. */
    @Volatile
    var failure: Throwable? = null
        private set

    @Volatile
    private var clientSocket: Socket? = null

    private val worker = Thread({ serve() }, "mock-electrum").apply {
        isDaemon = true
        start()
    }

    private fun serve() {
        try {
            while (!serverSocket.isClosed) {
                val socket = serverSocket.accept()
                clientSocket = socket
                if (hangUpOnConnect) {
                    socket.close()
                    continue
                }
                greeting?.let { text ->
                    socket.getOutputStream().write(text.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                }
                handle(socket)
            }
        } catch (error: Throwable) {
            if (!serverSocket.isClosed) failure = error
        }
    }

    private fun handle(socket: Socket) {
        socket.use { connection ->
            val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.UTF_8))
            val writer = OutputStreamWriter(connection.getOutputStream(), Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: return
                val request = MiniJson.parse(line) as? Map<*, *> ?: return
                @Suppress("UNCHECKED_CAST")
                val fields = request as Map<String, Any?>
                requests += fields

                val id = fields["id"]
                val method = fields["method"] as? String ?: ""
                val params = (fields["params"] as? List<*>)?.toList<Any?>() ?: emptyList()
                val payload = when (val reply = respond(method, params, id)) {
                    is Reply.Result -> linkedMapOf<String, Any?>("id" to id, "result" to reply.value)
                    is Reply.Error -> linkedMapOf<String, Any?>(
                        "id" to id,
                        "error" to linkedMapOf<String, Any?>("code" to reply.code, "message" to reply.message),
                    )
                    Reply.HangUp -> return
                }
                writer.write(MiniJson.encode(payload))
                writer.write("\n")
                writer.flush()
            }
        }
    }

    override fun close() {
        runCatching { clientSocket?.close() }
        runCatching { serverSocket.close() }
        worker.interrupt()
    }
}

/** A loopback port that is guaranteed to be closed, for "connection refused" tests. */
fun closedLoopbackPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
