package com.xbtwallet.app.electrum

import com.xbtwallet.app.chain.ChainQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Transport used to reach a private Electrum server. */
enum class ElectrumProtocol(val defaultPort: Int, val label: String) {
    /** TLS on the Electrum convention port 50002. */
    SSL(defaultPort = 50002, label = "SSL"),

    /** Plain TCP on the Electrum convention port 50001. Traffic is unencrypted. */
    TCP(defaultPort = 50001, label = "TCP"),
    ;

    companion object {
        /** Returns the protocol stored under this name, or null when unknown. */
        fun fromNameOrNull(name: String?): ElectrumProtocol? = entries.firstOrNull { it.name == name }
    }
}

/** A user-entered private Electrum server endpoint. */
data class ElectrumServer(
    val host: String,
    val port: Int,
    val protocol: ElectrumProtocol,
)

/** Raised when the server answers with a JSON-RPC `error` object. */
class ElectrumException(message: String) : Exception(message)

/**
 * Clean-room Electrum JSON-RPC client over a raw socket.
 *
 * Written from the public Electrum protocol description: newline-delimited
 * JSON-RPC 2.0 requests carrying a monotonically increasing `id`, answered by
 * one JSON object per request. It speaks the three handshake methods the Server
 * screen uses (`server.version`, `server.features`, `server.banner`), the quiet
 * `server.ping` the home screen's heartbeat uses, and — through
 * [withChainSession] — the three `blockchain.scripthash.*` queries the signer
 * screen's balance and UTXO views are built on.
 *
 * The client is free of Android imports on purpose: it runs on the JVM and is
 * covered by unit tests that drive it against a mock Electrum responder
 * (`app/src/test/.../MockElectrumServer.kt`).
 *
 * **Transport.** Sockets are opened through an [ElectrumTransport]. The default,
 * [RoutingTransport], sends `.onion` hosts through the app's bundled Tor SOCKS
 * proxy and everything else directly (see [ElectrumRouting]); tests inject a
 * fake transport, so the handshake, the routing decision and the bootstrap
 * failure path are all exercised without a Tor daemon.
 *
 * Note for future work: this is the seed of the app's chain backend — keep
 * protocol concerns here and UI concerns out.
 *
 * @param server endpoint to reach.
 * @param timeoutMillis connect timeout and per-read timeout, in milliseconds.
 * @param log receives one human-readable line per step; called from an IO
 *   thread, so implementations must be thread-safe.
 * @param transport how the socket is opened. The default routes `.onion` hosts
 *   through the bundled Tor and everything else directly; tests inject a fake
 *   so the handshake and the routing decision can be exercised without a Tor
 *   daemon (and without a network beyond loopback).
 */
class ElectrumClient(
    private val server: ElectrumServer,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val log: (String) -> Unit,
    private val transport: ElectrumTransport = RoutingTransport(),
) {

    /**
     * Connects, performs the JSON-RPC handshake and reports whether it worked.
     *
     * Never throws for network problems: DNS failures, refused connections,
     * timeouts, TLS handshake errors and malformed replies are all logged and
     * reported as `false` so a caller can simply show the log.
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) { runHandshake() }

    /**
     * A cheap liveness probe: connect, send one `server.ping` RPC and close.
     *
     * This is what the home screen's heartbeat calls once a minute, so it is
     * deliberately quieter than [testConnection]: one request instead of the
     * three-method handshake, and nothing at all is written to the injected
     * [log] — a per-minute poll must not fill a log the user is reading. The
     * answer is simply "did the server answer".
     *
     * Like [testConnection] it never throws for network problems: a refused
     * connection, a timeout, a TLS failure, a peer that hangs up and a JSON-RPC
     * `error` all come back as `false`.
     */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) { runPing() }

    /**
     * Opens one connection to the server and runs [block] against it, exposing
     * the scripthash queries as a [ChainQuery].
     *
     * This is the chain layer's entry point, and it exists for one reason: a
     * gap-limit scan issues dozens of requests (one history call per derived
     * address, a balance and a UTXO call per used one), and they all belong on
     * *one* connection. The alternative — a connect/TLS/close per address — would
     * make an ordinary account scan look like a port scan to the user's own
     * server.
     *
     * [block] runs on the IO dispatcher and is **blocking**: it may call the
     * [ChainQuery] methods, which block on the socket, and anything else it does
     * (deriving addresses, summing balances) is likewise off the UI thread.
     * Exceptions from the socket or the protocol propagate to the caller, which
     * is what lets the signer screen show a real error instead of a zero balance
     * that never loaded. The socket is closed when [block] returns or throws.
     *
     * No handshake is performed: the scripthash methods do not require one, and
     * a balance is not the place to negotiate a protocol version. Nothing is
     * written to [log] — this is a background read, not a user-visible
     * connection test.
     */
    suspend fun <T> withChainSession(block: (ChainQuery) -> T): T =
        withContext(Dispatchers.IO) { runChainSession(block) }

    /**
     * Opens one connection and hands it back as a long-lived
     * [SubscriptionSession] — the subscription counterpart of
     * [withChainSession].
     *
     * The socket is opened exactly as [runChainSession] opens it: through the
     * injected [ElectrumTransport] (so a `.onion` host goes through the bundled
     * Tor and everything else directly), with TLS layered on when the endpoint
     * asks for it. Unlike a chain session, it is *not* closed here: the caller
     * owns it, keeps it open, receives pushed notifications on it and calls
     * [SubscriptionSession.close] when done. The session runs its own reader
     * thread from the moment this returns.
     *
     * The returned session has **no read timeout** — an idle subscription is
     * silent for hours by design, and Fulcrum over Tor must not be timed out
     * mid-wait. See [SubscriptionSession]'s doc comment for the reasoning; the
     * connect timeout, and Tor's longer minimum for an onion rendezvous, still
     * apply to the dial that happens here.
     *
     * @param onNotification called on the reader thread for every pushed
     *   notification; see [SubscriptionSession.onNotification].
     * @param onClosed called once on the reader thread if the connection dies.
     */
    suspend fun openSubscriptionSession(
        onNotification: (method: String, params: Any?) -> Unit = { _, _ -> },
        onClosed: (error: Throwable) -> Unit = {},
    ): SubscriptionSession = withContext(Dispatchers.IO) {
        require(server.host.isNotBlank()) { "host is empty" }
        require(server.port in 1..65535) { "invalid port ${server.port}" }

        // A background connection: like a chain session, nothing about the
        // route or the bootstrap goes into the user-visible log.
        val plain = transport.connect(server.host, server.port, timeoutMillis, log = {})

        return@withContext try {
            val channel: Socket = if (server.protocol == ElectrumProtocol.SSL) startTls(plain) else plain
            // The one deliberate difference from every other session in this
            // class: no read timeout. `startTls` (and the transports) set the
            // handshake timeout on the socket, and a long-lived socket must not
            // inherit it — see SubscriptionSession's doc comment.
            channel.soTimeout = SubscriptionSession.NO_READ_TIMEOUT_MILLIS
            SubscriptionSession(
                channel = channel,
                reader = BufferedReader(InputStreamReader(channel.getInputStream(), Charsets.UTF_8)),
                writer = BufferedWriter(OutputStreamWriter(channel.getOutputStream(), Charsets.UTF_8)),
                log = {},
                notificationHandler = onNotification,
                closedHandler = onClosed,
            )
        } catch (error: Throwable) {
            // Nothing took ownership of the socket, so this is the last chance
            // to close it.
            runCatching { plain.close() }
            throw error
        }
    }

    private suspend fun <T> runChainSession(block: (ChainQuery) -> T): T {
        var socket: Socket? = null
        try {
            require(server.host.isNotBlank()) { "host is empty" }
            require(server.port in 1..65535) { "invalid port ${server.port}" }

            // A background read: nothing about the route or the bootstrap goes
            // into the user-visible log.
            val plain = transport.connect(server.host, server.port, timeoutMillis, log = {}).also { socket = it }

            val channel: Socket = if (server.protocol == ElectrumProtocol.SSL) startTls(plain) else plain
            val reader = BufferedReader(InputStreamReader(channel.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(channel.getOutputStream(), Charsets.UTF_8))

            // One id counter for the whole session, so a scan's requests are
            // numbered 1..n on this connection and each answer maps to its ask.
            var id = 0
            val session = ChainRpcSession { method, params ->
                rpc(++id, method, params, reader, writer, logger = {})
            }
            return block(session)
        } finally {
            runCatching { socket?.close() }
        }
    }

    private suspend fun runPing(): Boolean {
        var socket: Socket? = null
        try {
            require(server.host.isNotBlank()) { "host is empty" }
            require(server.port in 1..65535) { "invalid port ${server.port}" }

            // Silent, like the rest of the heartbeat.
            val plain = transport.connect(server.host, server.port, timeoutMillis, log = {}).also { socket = it }

            val channel: Socket = if (server.protocol == ElectrumProtocol.SSL) startTls(plain) else plain
            val reader = BufferedReader(InputStreamReader(channel.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(channel.getOutputStream(), Charsets.UTF_8))

            // `server.ping` takes no parameters and answers `result: null`, so
            // getting back here without an exception is the whole test.
            rpc(id = 1, method = "server.ping", params = emptyList(), reader = reader, writer = writer, logger = {})
            return true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return false
        } finally {
            runCatching { socket?.close() }
        }
    }

    private suspend fun runHandshake(): Boolean {
        var socket: Socket? = null
        try {
            require(server.host.isNotBlank()) { "host is empty" }
            require(server.port in 1..65535) { "invalid port ${server.port}" }

            log("> connecting to ${server.host}:${server.port} over ${server.protocol.label} (timeout ${timeoutMillis}ms)")
            // Where the connection is going: direct, or through the bundled Tor.
            // Tor also logs its own bootstrap progress from here on.
            log("> route: ${transport.describe(server.host)}")
            val plain = transport.connect(server.host, server.port, timeoutMillis, log).also { socket = it }
            log("< tcp connected: ${plain.localSocketAddress} -> ${plain.remoteSocketAddress}")

            val channel: Socket = if (server.protocol == ElectrumProtocol.SSL) startTls(plain) else plain
            if (channel is SSLSocket) {
                val session = channel.session
                log("< tls handshake ok: ${session.protocol}, ${session.cipherSuite}")
            } else {
                log("< plain tcp: traffic is NOT encrypted")
            }

            val reader = BufferedReader(InputStreamReader(channel.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(channel.getOutputStream(), Charsets.UTF_8))

            var id = 0

            val negotiated = rpc(++id, "server.version", listOf(CLIENT_NAME, PROTOCOL_VERSION), reader, writer)
            log("< negotiated: ${describeVersion(negotiated)}")

            val features = rpc(++id, "server.features", emptyList(), reader, writer)
            log("< features: ${describeFeatures(features)}")

            val banner = rpc(++id, "server.banner", emptyList(), reader, writer)
            if (banner is String && banner.isNotBlank()) {
                banner.lines().forEach { if (it.isNotBlank()) log("| ${it.trimEnd()}") }
            }

            log("\u2713 connection test succeeded against ${server.host}:${server.port}")
            return true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log("\u2717 connection test failed: ${describe(error)}")
            return false
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** Parses one JSON-RPC line, turning malformed JSON into an [IOException]. */
    private fun parseReply(line: String, method: String): Any? = try {
        MiniJson.parse(line)
    } catch (error: JsonParseException) {
        throw IOException("malformed JSON-RPC reply to '$method': ${error.message}")
    }

    /**
     * Sends one request and returns its `result`, logging both directions.
     *
     * [logger] is the client's own [log] by default; [runPing] passes a no-op
     * so the heartbeat stays out of the user-visible log.
     */
    private fun rpc(
        id: Int,
        method: String,
        params: List<Any?>,
        reader: BufferedReader,
        writer: BufferedWriter,
        logger: (String) -> Unit = log,
    ): Any? {
        val request = MiniJson.encode(linkedMapOf<String, Any?>("id" to id, "method" to method, "params" to params))
        logger("> $request")
        writer.write(request)
        writer.write("\n")
        writer.flush()

        // `blockchain.headers.subscribe` — the tip lookup the history rows use —
        // makes the server push a `blockchain.headers.subscribe` *notification*
        // whenever a block arrives. Notifications carry no `id`; reading one as
        // the answer to the next request would desynchronise the session, so
        // they are logged and skipped until the reply to this request arrives.
        var line = reader.readLine()
            ?: throw IOException("peer closed the connection while waiting for the '$method' reply")
        var parsed = parseReply(line, method)
        while (parsed is Map<*, *> && parsed["id"] == null && parsed["method"] != null) {
            logger("< (notification) $line")
            line = reader.readLine()
                ?: throw IOException("peer closed the connection while waiting for the '$method' reply")
            parsed = parseReply(line, method)
        }
        logger("< $line")

        val response = parsed as? Map<*, *>
            ?: throw IOException("unexpected JSON-RPC reply to '$method': $line")

        val rpcError = response["error"]
        if (rpcError != null) {
            throw ElectrumException("'$method' returned an error: ${MiniJson.encode(rpcError)}")
        }
        if (!response.containsKey("result")) {
            throw ElectrumException("'$method' returned neither result nor error: $line")
        }
        return response["result"]
    }

    /** Wraps the connected socket in TLS with hostname verification enabled. */
    private fun startTls(plain: Socket): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val tls = factory.createSocket(plain, server.host, server.port, true) as SSLSocket
        tls.soTimeout = timeoutMillis
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.startHandshake()
        return tls
    }

    private fun describeVersion(result: Any?): String = when (result) {
        is List<*> -> buildString {
            append(result.firstOrNull()?.toString() ?: "unknown server")
            val negotiated = result.getOrNull(1)
            if (negotiated != null) append(", protocol $negotiated")
        }
        is String -> result
        else -> MiniJson.encode(result)
    }

    private fun describeFeatures(result: Any?): String {
        val features = result as? Map<*, *> ?: return MiniJson.encode(result)
        val interesting = listOf("genesis_hash", "server_version", "protocol_min", "protocol_max")
            .mapNotNull { key -> features[key]?.let { "$key=${shorten(it.toString())}" } }
        val summary = if (interesting.isEmpty()) "${features.size} fields" else interesting.joinToString(", ")
        return "$summary (${features.size} fields)"
    }

    private fun shorten(value: String): String =
        if (value.length <= 20) value else value.take(20) + "\u2026"

    /**
     * The one-line reason a failed connection test ends with.
     *
     * The order is deliberate. The exception *type* answers first for the cases
     * it explains fully — Tor never came up, the name did not resolve, a dial
     * the JVM timed out itself, a TLS handshake the peer broke — and a socket
     * errno (see [SocketErrno]) answers next: `EACCES`/`EPERM` is the OS
     * rejecting the socket, which is a phone-side cause and must never be
     * reported as the server refusing; `EHOSTUNREACH`/`ENETUNREACH` are
     * unreachable, not refused; and `ETIMEDOUT` on a dial is a different story
     * from the read timeout the JVM raises. Only when neither says anything is
     * a [ConnectException] the plain refusal it has always been.
     */
    private fun describe(error: Throwable): String {
        val chain = buildString {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 4) {
                if (depth > 0) append(" <- ")
                append(current::class.java.simpleName).append(": ").append(current.message ?: "(no message)")
                current = current.cause
                depth++
            }
        }
        val hint = when (error) {
            is TorBootstrapException -> "Tor is unavailable — ${error.message}"
            is UnknownHostException -> "DNS lookup failed for '${server.host}'"
            is SocketTimeoutException -> "timed out after ${timeoutMillis}ms"
            is SSLException -> "TLS handshake failed (untrusted or self-signed certificates are rejected in this iteration)"
            else -> socketErrnoOf(error)?.let { socketErrnoReason(it) }
                ?: when (error) {
                    is ConnectException -> "connection refused"
                    else -> "unexpected failure"
                }
        }
        return "$hint — $chain"
    }

    companion object {
        /** Connect and read timeout for a connection test. */
        const val DEFAULT_TIMEOUT_MILLIS: Int = 10_000

        /** Client identity sent to the server in `server.version`. */
        const val CLIENT_NAME: String = "xbt-wallet 0.0.1"

        /** Electrum protocol version this client speaks. */
        const val PROTOCOL_VERSION: String = "1.4"
    }
}
