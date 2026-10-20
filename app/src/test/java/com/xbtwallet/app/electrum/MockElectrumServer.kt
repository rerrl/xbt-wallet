package com.xbtwallet.app.electrum

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

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
        /**
         * `{"id":…,"result":<value>}`
         *
         * [precedingNotifications] are written as raw lines first — used to
         * simulate the unsolicited notifications a subscription RPC (such as
         * `blockchain.headers.subscribe`) produces, which a client must not
         * mistake for the reply.
         */
        data class Result(
            val value: Any?,
            val precedingNotifications: List<String> = emptyList(),
        ) : Reply

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

    /** How many connections have been accepted, for "one connection per scan" tests. */
    private val connectionCounter = AtomicInteger()

    /** Connections accepted so far. */
    val connections: Int get() = connectionCounter.get()

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
                connectionCounter.incrementAndGet()
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
                    is Reply.Result -> {
                        reply.precedingNotifications.forEach { line ->
                            writer.write(line)
                            writer.write("\n")
                        }
                        linkedMapOf<String, Any?>("id" to id, "result" to reply.value)
                    }
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

    companion object {

        /**
         * A mock that answers the three `blockchain.scripthash.*` RPCs from
         * scripted fixtures — the chain-layer counterpart to the handshake
         * lambdas the client tests pass in.
         *
         * The fixtures are keyed by Electrum scripthash, which is the identity
         * the protocol actually uses. Anything not in a fixture map is answered
         * the way a server answers an address it has never seen: empty history,
         * zero balance, no UTXOs. That single default is what makes the "empty
         * wallet" and "unused address" cases one line instead of a special case.
         *
         * @param history per-scripthash `get_history` entries (see [historyEntry]).
         * @param balances per-scripthash `(confirmed, unconfirmed)` satoshis.
         * @param unspent per-scripthash `listunspent` entries (see [unspentEntry]).
         * @param transactions raw transaction hex, by txid — what
         *   `blockchain.transaction.get` answers. A txid not listed is answered
         *   as an RPC error, which is what a server does for a transaction it
         *   does not know.
         * @param blockHeaders raw 80-byte headers by height — what
         *   `blockchain.block.header` answers. A height not listed is answered as
         *   an RPC error, the way a server answers a height it cannot serve.
         * @param tipHeight the height `blockchain.headers.subscribe` reports.
         */
        fun scripted(
            history: Map<String, List<Any?>> = emptyMap(),
            balances: Map<String, Pair<Long, Long>> = emptyMap(),
            unspent: Map<String, List<Any?>> = emptyMap(),
            transactions: Map<String, String> = emptyMap(),
            blockHeaders: Map<Int, String> = emptyMap(),
            tipHeight: Int = 800_000,
        ): MockElectrumServer = MockElectrumServer { method, params, _ ->
            val scripthash = params.firstOrNull() as? String ?: ""
            when (method) {
                "blockchain.scripthash.get_history" ->
                    Reply.Result(history[scripthash] ?: emptyList<Any?>())

                "blockchain.scripthash.get_balance" -> {
                    val (confirmed, unconfirmed) = balances[scripthash] ?: (0L to 0L)
                    Reply.Result(
                        linkedMapOf<String, Any?>("confirmed" to confirmed, "unconfirmed" to unconfirmed),
                    )
                }

                "blockchain.scripthash.listunspent" ->
                    Reply.Result(unspent[scripthash] ?: emptyList<Any?>())

                "blockchain.transaction.get" -> transactions[scripthash]?.let { Reply.Result(it) }
                    ?: Reply.Error(-32601, "unknown transaction $scripthash")

                "blockchain.headers.subscribe" ->
                    Reply.Result(linkedMapOf<String, Any?>("height" to tipHeight, "hex" to "00"))

                "blockchain.block.header" -> {
                    val height = (params.firstOrNull() as? Number)?.toInt()
                    blockHeaders[height]?.let { Reply.Result(it) }
                        ?: Reply.Error(-32601, "unknown height $height")
                }

                else -> Reply.Error(-32601, "unknown method $method")
            }
        }

        /** One `get_history` fixture entry. The scanner only cares that it is non-empty. */
        fun historyEntry(txHash: String, height: Int): Map<String, Any?> =
            linkedMapOf("tx_hash" to txHash, "height" to height)

        /** One `listunspent` fixture entry. */
        fun unspentEntry(txHash: String, txPos: Int, height: Int, value: Long): Map<String, Any?> =
            linkedMapOf("tx_hash" to txHash, "tx_pos" to txPos, "height" to height, "value" to value)

        /**
         * A block header as a chain that extends its header serves it: the
         * ordinary 80-byte header with the 84-byte proof-of-work extension
         * appended, i.e. 164 bytes / 328 hex characters.
         *
         * The Bitcoin Knots BLAKE2b hardfork — the chain the test server indexes
         * — switches to BLAKE2b proof-of-work at height 961640 and, from there
         * on, answers `blockchain.block.header` with this longer form. A client
         * that demanded exactly 160 hex characters threw on every such reply,
         * which is the regression these fixtures keep covered: the reply is
         * still a bare hex string (only its length is different), so the fix is
         * in how the header is *read*, not in how it is decoded.
         */
        fun postForkHeader(headerHex80: String, extensionHex: String = POST_FORK_HEADER_EXTENSION_HEX): String =
            headerHex80 + extensionHex

        /**
         * The 84 bytes a post-hardfork header carries after its 80-byte prefix.
         * Taken verbatim from a real reply (block 970000 of that chain — public
         * chain data); its contents do not matter to these tests, only its
         * length and that the prefix's timestamp is what gets read.
         */
        const val POST_FORK_HEADER_EXTENSION_HEX: String =
            "e361d50be798a06a00000000b1ccf00f010000000000000000000000" +
                "61000033024b85992956d664c320c43104b60c7510cd0e0000000000" +
                "00000000000000000000000000000000000000000000000000000000"
    }
}

/** A loopback port that is guaranteed to be closed, for "connection refused" tests. */
fun closedLoopbackPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
