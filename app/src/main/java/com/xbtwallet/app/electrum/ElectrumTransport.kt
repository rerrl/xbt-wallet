package com.xbtwallet.app.electrum

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * How the Electrum client reaches a server, and where Tor fits in.
 *
 * **The rule.** A host that ends in `.onion` can only be resolved inside Tor, so
 * it *implies* Tor: the client routes it through the app's bundled Tor SOCKS
 * proxy with no toggle for the user to forget. Every other host keeps the direct
 * connection it has always had. Nothing about the protocol, the port or the
 * handshake changes — Tor is a transport detail underneath the same JSON-RPC
 * conversation, which is what makes "Test connection" behave identically on
 * both routes.
 *
 * **Why bundled, not Orbot.** Tor lives inside the app (see
 * `build.gradle.kts`, `info.guardianproject:tor-android`) rather than being
 * borrowed from Orbot: no second app to install, no `ACTION_START` intent that
 * silently does nothing when Orbot is absent, and no cross-app trust. The
 * Android-specific plumbing is [TorBootstrap]'s Android implementation; this
 * file stays free of Android imports so the routing decision is unit-testable.
 */

/** Where an endpoint is reached from. */
enum class ElectrumRoute {
    /** A normal TCP/TLS connection to the host. */
    DIRECT,

    /** Through the bundled Tor SOCKS proxy — required for `.onion`. */
    TOR,
}

/** The `.onion` suffix, and the routing rules built on it. */
object ElectrumRouting {

    /** Tor's hidden-service top-level domain. */
    const val ONION_SUFFIX: String = ".onion"

    /**
     * True when [host] is an onion address. Case-insensitive, tolerant of the
     * trailing dot an absolute DNS name may carry, and of surrounding spaces a
     * user pasted in. `example.onion` is an onion; `onion.example` and a bare
     * `.onion` are not.
     */
    fun isOnionHost(host: String): Boolean {
        val normalized = host.trim().trimEnd('.').lowercase()
        return normalized.length > ONION_SUFFIX.length && normalized.endsWith(ONION_SUFFIX)
    }

    /** The route an endpoint must take: `.onion` implies Tor, everything else is direct. */
    fun route(host: String): ElectrumRoute =
        if (isOnionHost(host)) ElectrumRoute.TOR else ElectrumRoute.DIRECT

    /**
     * The port used when the user has not chosen one, for a protocol.
     *
     * An onion endpoint listens on the same Electrum convention ports as a
     * clearnet one — a hidden service forwards 50001/50002 just as a public
     * server does — so the `.onion` suffix does not change the default. Tor's
     * own SOCKS port (9050 by default, and whatever the bundled daemon reports)
     * is a different number and is never offered as an Electrum port.
     */
    fun defaultPort(protocol: ElectrumProtocol): Int = protocol.defaultPort

    /** A one-line description for the connection log. */
    fun describe(host: String): String =
        if (isOnionHost(host)) {
            "Tor (onion address, via the bundled Tor SOCKS proxy)"
        } else {
            "direct (clearnet host)"
        }
}

/** The local SOCKS endpoint of a running Tor daemon. */
data class TorProxy(val host: String, val port: Int)

/** Raised when the bundled Tor cannot be started or is not configured. */
class TorBootstrapException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Brings the bundled Tor daemon up and reports where its SOCKS proxy is
 * listening.
 *
 * This is the seam that keeps Tor out of the JVM tests: the client needs only
 * *a proxy*, and a test injects a fake [ElectrumTransport] instead of a
 * bootstrap. The Android implementation lives in
 * `com.xbtwallet.app.electrum.tor.AndroidTorBootstrap`.
 *
 * [start] is expected to be idempotent — once Tor is running it should return
 * the same endpoint immediately — and to report progress through [log] so the
 * Server screen's log shows a bootstrap that is taking its time.
 */
interface TorBootstrap {
    suspend fun start(log: (String) -> Unit): TorProxy
}

/**
 * Process-wide holder for the bundled Tor.
 *
 * The app installs one Android implementation at startup, and every component
 * that might dial an onion address (the Server screen's connection test, the
 * signer screen's scan, the home screen's heartbeat) gets it without threading a
 * `Context` through the chain layer. A build that never installs one fails
 * loudly on an onion address instead of silently dialling DNS.
 */
object TorGateway {

    @Volatile
    private var bootstrap: TorBootstrap? = null

    /** Installs the process-wide Tor bootstrap (called once, from the app). */
    fun install(bootstrap: TorBootstrap) {
        this.bootstrap = bootstrap
    }

    /** True when a bootstrap has been installed. */
    fun isInstalled(): Boolean = bootstrap != null

    /** The installed bootstrap. */
    fun require(): TorBootstrap = bootstrap
        ?: throw TorBootstrapException("Tor is not available in this build, so .onion servers cannot be reached")
}

/**
 * Opens the raw socket an Electrum connection runs on.
 *
 * Implementations may suspend: bringing Tor up is slow (tens of seconds on a
 * cold start) and happens before the socket exists. Everything above this
 * interface — the handshake, the log lines, the scripthash session — is shared
 * between routes on purpose.
 */
interface ElectrumTransport {

    /** One line for the connection log, describing how this host will be reached. */
    fun describe(host: String): String

    /**
     * Connects to [host]:[port] and returns the open, unencrypted socket. TLS,
     * when the protocol asks for it, is layered on top by the client.
     */
    suspend fun connect(host: String, port: Int, timeoutMillis: Int, log: (String) -> Unit): Socket
}

/** A plain TCP connection: today's behaviour for every clearnet host. */
class DirectTransport : ElectrumTransport {

    override fun describe(host: String): String = "direct (clearnet host)"

    override suspend fun connect(host: String, port: Int, timeoutMillis: Int, log: (String) -> Unit): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), timeoutMillis)
        socket.soTimeout = timeoutMillis
        return socket
    }
}

/**
 * A connection through Tor's SOCKS proxy.
 *
 * The proxy is obtained from [bootstrap] before the socket is created; from
 * there on the socket is ordinary — `java.net.Socket` with a SOCKS proxy does
 * the handshake for us, and Tor does the DNS resolution for the onion name,
 * which is the whole point (an onion address has no DNS record to look up).
 */
class TorTransport(private val bootstrap: TorBootstrap) : ElectrumTransport {

    override fun describe(host: String): String = "Tor (via the bundled Tor SOCKS proxy)"

    override suspend fun connect(host: String, port: Int, timeoutMillis: Int, log: (String) -> Unit): Socket {
        val proxy = bootstrap.start(log)
        log("< tor: socks proxy ${proxy.host}:${proxy.port}, dialling $host:$port through it")
        val socket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxy.host, proxy.port)))
        socket.tcpNoDelay = true
        // Unresolved on purpose: the host name goes to the SOCKS proxy, so Tor
        // does the lookup. That is what makes an onion name work at all, and it
        // is also what keeps a clearnet lookup from leaking past Tor.
        socket.connect(InetSocketAddress.createUnresolved(host, port), timeoutMillis)
        socket.soTimeout = timeoutMillis
        return socket
    }
}

/**
 * The default transport: picks Tor for `.onion` hosts and direct otherwise.
 *
 * The Tor transport is created lazily, so a wallet that never enters an onion
 * address never touches the Tor bootstrap at all.
 */
class RoutingTransport(
    private val direct: ElectrumTransport = DirectTransport(),
    private val tor: () -> ElectrumTransport = { TorTransport(TorGateway.require()) },
) : ElectrumTransport {

    override fun describe(host: String): String = ElectrumRouting.describe(host)

    override suspend fun connect(host: String, port: Int, timeoutMillis: Int, log: (String) -> Unit): Socket {
        val transport = when (ElectrumRouting.route(host)) {
            ElectrumRoute.TOR -> tor()
            ElectrumRoute.DIRECT -> direct
        }
        return transport.connect(host, port, timeoutMillis, log)
    }
}
