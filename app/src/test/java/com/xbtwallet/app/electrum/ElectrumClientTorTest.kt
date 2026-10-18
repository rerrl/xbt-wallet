package com.xbtwallet.app.electrum

import com.xbtwallet.app.electrum.MockElectrumServer.Reply
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections

/**
 * The Tor side of [ElectrumClient], with no Tor and no network: routing is
 * exercised through a recording fake transport, and "does the connection over
 * the SOCKS proxy actually work" is exercised by a fake bootstrap that points at
 * a closed loopback port.
 *
 * What the card asks for and where it is covered:
 *  - `.onion` implies Tor, clearnet stays direct → the first two tests;
 *  - Tor is a transport detail, so the handshake and its log lines are the same
 *    on both routes → the first test asserts the same success lines the
 *    clearnet tests assert;
 *  - an onion host with SSL still asks for TLS → [onion plus SSL attempts a TLS
 *    handshake through Tor];
 *  - bootstrap status is surfaced and a bootstrap failure is a clear error →
 *    the bootstrap tests;
 *  - background chain reads go over the same routed path → the last test.
 */
class ElectrumClientTorTest {

    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    private fun logText(): String = logs.joinToString(separator = "\n")

    /** A transport that records what it was asked to reach, then dials the mock. */
    private class RecordingTransport(
        private val label: String,
        private val connectToPort: Int,
    ) : ElectrumTransport {
        val requests = mutableListOf<Triple<String, Int, Int>>()

        override fun describe(host: String): String = label

        override suspend fun connect(host: String, port: Int, timeoutMillis: Int, log: (String) -> Unit): Socket {
            requests += Triple(host, port, timeoutMillis)
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", connectToPort), timeoutMillis)
            socket.soTimeout = timeoutMillis
            return socket
        }
    }

    /** A mock electrs that answers the three handshake methods. */
    private fun handshakeMock(greeting: String? = null): MockElectrumServer =
        MockElectrumServer(greeting = greeting) { method, _, _ ->
            when (method) {
                "server.version" -> Reply.Result(listOf("electrs 0.10.0", "1.4"))
                "server.features" -> Reply.Result(
                    linkedMapOf<String, Any?>(
                        "genesis_hash" to "00".repeat(32),
                        "server_version" to "electrs 0.10.0",
                    ),
                )
                "server.banner" -> Reply.Result("Welcome to the onion test")
                else -> Reply.Error(-32601, "unknown method $method")
            }
        }

    private fun client(
        server: ElectrumServer,
        transport: ElectrumTransport,
        timeoutMillis: Int = 5_000,
    ) = ElectrumClient(
        server = server,
        timeoutMillis = timeoutMillis,
        log = { logs += it },
        transport = transport,
    )

    @Test
    fun `an onion host goes through the tor transport and the handshake is unchanged`() {
        handshakeMock().use { mock ->
            val tor = RecordingTransport("Tor (via the bundled Tor SOCKS proxy)", mock.port)
            val direct = RecordingTransport("direct (clearnet host)", mock.port)
            val onion = ElectrumServer(host = "abcdefghijklmnop.onion", port = 50001, protocol = ElectrumProtocol.TCP)

            val connected = runBlocking {
                client(onion, RoutingTransport(direct = direct, tor = { tor })).testConnection()
            }
            val text = logText()

            assertTrue("the tor transport must have been used:\n$text", connected)
            assertEquals(listOf(Triple("abcdefghijklmnop.onion", 50001, 5_000)), tor.requests)
            assertTrue("the direct transport must not be used for an onion host", direct.requests.isEmpty())

            // Same route line, same handshake lines, same success line as clearnet.
            assertTrue(text, text.contains("> route: Tor"))
            assertTrue(text, text.contains("negotiated: electrs 0.10.0, protocol 1.4"))
            assertTrue(text, text.contains("Welcome to the onion test"))
            assertTrue(text, text.contains("connection test succeeded against abcdefghijklmnop.onion:50001"))
        }
    }

    @Test
    fun `a clearnet host is dialled directly`() {
        handshakeMock().use { mock ->
            val tor = RecordingTransport("tor", mock.port)
            val direct = RecordingTransport("direct (clearnet host)", mock.port)
            val clearnet = ElectrumServer(host = "127.0.0.1", port = mock.port, protocol = ElectrumProtocol.TCP)

            val connected = runBlocking {
                client(clearnet, RoutingTransport(direct = direct, tor = { tor })).testConnection()
            }

            assertTrue(connected)
            assertEquals(listOf(Triple("127.0.0.1", mock.port, 5_000)), direct.requests)
            assertTrue("Tor must not be started for a clearnet host", tor.requests.isEmpty())
            assertTrue(logText(), logText().contains("> route: direct"))
        }
    }

    @Test
    fun `a tor bootstrap failure is a clear error and the test fails`() {
        val failing = object : TorBootstrap {
            override suspend fun start(log: (String) -> Unit): TorProxy {
                log("< tor: bootstrapping 0% (starting)")
                throw TorBootstrapException("Tor did not finish bootstrapping within 120s (last status: bootstrap 40%)")
            }
        }
        val onion = ElectrumServer(host = "example.onion", port = 50001, protocol = ElectrumProtocol.TCP)
        val transport = RoutingTransport(direct = DirectTransport(), tor = { TorTransport(failing) })

        val connected = runBlocking { client(onion, transport, timeoutMillis = 2_000).testConnection() }
        val text = logText()

        assertFalse(text, connected)
        // The bootstrap's own progress reaches the log …
        assertTrue(text, text.contains("bootstrapping 0%"))
        // … and the failure says what went wrong rather than "unknown host".
        assertTrue(text, text.contains("Tor is unavailable"))
        assertTrue(text, text.contains("did not finish bootstrapping"))
        assertTrue(text, text.contains("connection test failed"))
    }

    @Test
    fun `tor bootstrap progress is logged before the socket is dialled`() {
        val proxy = TorProxy(host = "127.0.0.1", port = closedLoopbackPort())
        val started = object : TorBootstrap {
            override suspend fun start(log: (String) -> Unit): TorProxy {
                log("< tor: bootstrapping 50% (Loading relay descriptors)")
                log("< tor: bootstrapping 100% (Done)")
                log("< tor: ready, socks proxy on 127.0.0.1:${proxy.port}")
                return proxy
            }
        }
        val onion = ElectrumServer(host = "example.onion", port = 50001, protocol = ElectrumProtocol.TCP)

        val connected = runBlocking {
            client(onion, RoutingTransport(direct = DirectTransport(), tor = { TorTransport(started) }), timeoutMillis = 2_000)
                .testConnection()
        }
        val text = logText()

        assertFalse("nothing is listening on the fake SOCKS port, so this must fail", connected)
        assertTrue(text, text.contains("bootstrapping 50%"))
        assertTrue(text, text.contains("ready, socks proxy"))
        assertTrue(text, text.contains("socks proxy 127.0.0.1:${proxy.port}"))
        assertTrue(text, text.contains("connection test failed"))
    }

    @Test
    fun `onion plus SSL attempts a TLS handshake through Tor`() {
        // The mock speaks plain text, so the TLS handshake must fail — which is
        // exactly what proves the SSL path ran *over* the Tor transport rather
        // than being skipped or silently downgraded to TCP.
        MockElectrumServer(greeting = "this peer does not speak TLS\r\n") { _, _, _ -> Reply.HangUp }.use { mock ->
            val tor = RecordingTransport("Tor (via the bundled Tor SOCKS proxy)", mock.port)
            val onionSsl = ElectrumServer(host = "example.onion", port = 50002, protocol = ElectrumProtocol.SSL)

            val connected = runBlocking {
                client(onionSsl, RoutingTransport(direct = DirectTransport(), tor = { tor }), timeoutMillis = 2_000)
                    .testConnection()
            }
            val text = logText()

            assertFalse(text, connected)
            assertEquals(listOf(Triple("example.onion", 50002, 2_000)), tor.requests)
            assertTrue(text, text.contains("> route: Tor"))
            assertTrue(text, text.contains("over SSL"))
            assertTrue(text, text.contains("TLS handshake failed"))
        }
    }

    @Test
    fun `a real SOCKS5 handshake is performed and the onion name is sent unresolved`() {
        handshakeMock().use { mock ->
            TestSocksProxy(backendPort = mock.port).use { socks ->
                val bootstrap = object : TorBootstrap {
                    override suspend fun start(log: (String) -> Unit): TorProxy {
                        log("< tor: ready, socks proxy on 127.0.0.1:${socks.port}")
                        return TorProxy(host = "127.0.0.1", port = socks.port)
                    }
                }
                val onion = ElectrumServer(host = "abcdefghijklmnop.onion", port = 50001, protocol = ElectrumProtocol.TCP)

                val connected = runBlocking {
                    client(onion, TorTransport(bootstrap)).testConnection()
                }

                assertTrue("the handshake through the SOCKS proxy failed:\n${logText()}", connected)
                assertEquals(null, socks.failure)
                // The proxy saw the hidden-service name, not a resolved address:
                // that is what lets Tor resolve it, and what proves no DNS leak.
                val request = socks.requests.single()
                assertEquals("abcdefghijklmnop.onion", request.host)
                assertEquals(50001, request.port)
                assertEquals("the host name must be sent as a domain name", 0x03, request.addressType)
                assertTrue(logText(), logText().contains("connection test succeeded"))
            }
        }
    }

    @Test
    fun `background chain reads use the same routed transport`() {
        MockElectrumServer.scripted(tipHeight = 812_345).use { mock ->
            val tor = RecordingTransport("Tor (via the bundled Tor SOCKS proxy)", mock.port)
            val onion = ElectrumServer(host = "example.onion", port = 50001, protocol = ElectrumProtocol.TCP)

            val tip = runBlocking {
                client(onion, RoutingTransport(direct = DirectTransport(), tor = { tor }))
                    .withChainSession { query -> query.getTipHeight() }
            }

            assertEquals(812_345, tip)
            assertEquals(listOf(Triple("example.onion", 50001, 5_000)), tor.requests)
            // The background path stays quiet: no Tor or route lines in a log.
            assertTrue("background reads must not write to the log: ${logText()}", logs.isEmpty())
        }
    }
}
