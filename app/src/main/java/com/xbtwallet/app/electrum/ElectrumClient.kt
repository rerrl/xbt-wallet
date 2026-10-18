package com.xbtwallet.app.electrum

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.InetSocketAddress
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
 * one JSON object per request. Only what this iteration needs is implemented —
 * `server.version`, `server.features` and `server.banner` — because the point
 * of this class is to prove the transport works against a private server
 * before it grows into the app's real chain backend.
 *
 * The client is free of Android imports on purpose: it runs on the JVM and is
 * covered by unit tests that drive it against a mock Electrum responder
 * (`app/src/test/.../MockElectrumServer.kt`).
 *
 * Note for future work: this is the seed of the app's chain backend — keep
 * protocol concerns here and UI concerns out.
 *
 * @param server endpoint to reach.
 * @param timeoutMillis connect timeout and per-read timeout, in milliseconds.
 * @param log receives one human-readable line per step; called from an IO
 *   thread, so implementations must be thread-safe.
 */
class ElectrumClient(
    private val server: ElectrumServer,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val log: (String) -> Unit,
) {

    /**
     * Connects, performs the JSON-RPC handshake and reports whether it worked.
     *
     * Never throws for network problems: DNS failures, refused connections,
     * timeouts, TLS handshake errors and malformed replies are all logged and
     * reported as `false` so a caller can simply show the log.
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) { runHandshake() }

    private fun runHandshake(): Boolean {
        var socket: Socket? = null
        try {
            require(server.host.isNotBlank()) { "host is empty" }
            require(server.port in 1..65535) { "invalid port ${server.port}" }

            log("> connecting to ${server.host}:${server.port} over ${server.protocol.label} (timeout ${timeoutMillis}ms)")
            val plain = Socket().also { socket = it }
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(server.host, server.port), timeoutMillis)
            plain.soTimeout = timeoutMillis
            log("< tcp connected: ${plain.localSocketAddress} -> ${plain.remoteSocketAddress}")

            val transport: Socket = if (server.protocol == ElectrumProtocol.SSL) startTls(plain) else plain
            if (transport is SSLSocket) {
                val session = transport.session
                log("< tls handshake ok: ${session.protocol}, ${session.cipherSuite}")
            } else {
                log("< plain tcp: traffic is NOT encrypted")
            }

            val reader = BufferedReader(InputStreamReader(transport.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(transport.getOutputStream(), Charsets.UTF_8))

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

    /** Sends one request and returns its `result`, logging both directions. */
    private fun rpc(
        id: Int,
        method: String,
        params: List<Any?>,
        reader: BufferedReader,
        writer: BufferedWriter,
    ): Any? {
        val request = MiniJson.encode(linkedMapOf<String, Any?>("id" to id, "method" to method, "params" to params))
        log("> $request")
        writer.write(request)
        writer.write("\n")
        writer.flush()

        val line = reader.readLine()
            ?: throw IOException("peer closed the connection while waiting for the '$method' reply")
        log("< $line")

        val parsed = try {
            MiniJson.parse(line)
        } catch (error: JsonParseException) {
            throw IOException("malformed JSON-RPC reply to '$method': ${error.message}")
        }
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
            is UnknownHostException -> "DNS lookup failed for '${server.host}'"
            is SocketTimeoutException -> "timed out after ${timeoutMillis}ms"
            is ConnectException -> "connection refused"
            is SSLException -> "TLS handshake failed (untrusted or self-signed certificates are rejected in this iteration)"
            else -> "unexpected failure"
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
