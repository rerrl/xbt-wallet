package com.xbtwallet.app.electrum

import com.xbtwallet.app.electrum.MockElectrumServer.Reply
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections

/**
 * Drives [ElectrumClient] against a mock Electrum responder on loopback.
 *
 * These cover the paths the Server screen shows in its log: a successful
 * handshake, a JSON-RPC error, a peer that closes the connection mid-handshake
 * (the TLS-against-plain-TCP case), a dead port and an unresolvable host.
 *
 * The `ping()` probe behind the home screen's heartbeat is covered as well —
 * success, refused connection, RPC error and a peer that hangs up — and every
 * one of those tests asserts that nothing reached the client's log, because a
 * per-minute heartbeat must stay silent.
 */
class ElectrumClientTest {

    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    private fun client(port: Int, protocol: ElectrumProtocol = ElectrumProtocol.TCP, timeoutMillis: Int = 5_000) =
        ElectrumClient(
            server = ElectrumServer(host = "127.0.0.1", port = port, protocol = protocol),
            timeoutMillis = timeoutMillis,
            log = { logs += it },
        )

    private fun logText(): String = logs.joinToString(separator = "\n")

    @Test
    fun `handshake runs server version features and banner and succeeds`() {
        MockElectrumServer { method, _, _ ->
            when (method) {
                "server.version" -> Reply.Result(listOf("electrs 0.10.0", "1.4"))
                "server.features" -> Reply.Result(
                    linkedMapOf<String, Any?>(
                        "genesis_hash" to GENESIS_HASH,
                        "protocol_min" to "1.4",
                        "protocol_max" to "1.4",
                        "server_version" to "electrs 0.10.0",
                        "hosts" to emptyMap<String, Any?>(),
                    ),
                )
                "server.banner" -> Reply.Result("Welcome to the mock server\nsecond line")
                else -> Reply.Error(-32601, "unknown method $method")
            }
        }.use { mock ->
            val connected = runBlocking { client(mock.port).testConnection() }
            val text = logText()

            assertTrue("expected a successful handshake, log was:\n$text", connected)
            assertNull("mock server failed: ${mock.failure}", mock.failure)
            assertEquals(
                listOf("server.version", "server.features", "server.banner"),
                mock.requests.map { it["method"] },
            )
            assertEquals(listOf("xbt-wallet 0.0.1", "1.4"), mock.requests.first()["params"])

            assertTrue(text, text.contains("plain tcp"))
            assertTrue(text, text.contains("> {\"id\":1,\"method\":\"server.version\""))
            assertTrue(text, text.contains("electrs 0.10.0"))
            assertTrue(text, text.contains(GENESIS_HASH))
            assertTrue(text, text.contains("second line"))
            assertTrue(text, text.contains("connection test succeeded"))

            // Printed so the full handshake log can be read straight out of the
            // test report — this is the same text the Server screen shows.
            println("[mock electrs connection log]\n$text\n[/mock electrs connection log]")
        }
    }

    @Test
    fun `json rpc error is reported and the test fails`() {
        MockElectrumServer { method, _, _ ->
            if (method == "server.version") {
                Reply.Result(listOf("electrs 0.10.0", "1.4"))
            } else {
                Reply.Error(-32601, "unknown method $method")
            }
        }.use { mock ->
            val connected = runBlocking { client(mock.port).testConnection() }
            val text = logText()

            assertFalse(text, connected)
            assertTrue(text, text.contains("'server.features' returned an error"))
            assertTrue(text, text.contains("-32601"))
            assertTrue(text, text.contains("connection test failed"))
        }
    }

    @Test
    fun `tls against a plain tcp peer is reported as a handshake failure`() {
        // The peer answers the ClientHello with plain text, so the JSSE client
        // fails the handshake the same way it would against a bad certificate.
        MockElectrumServer(greeting = "this peer does not speak TLS\r\n") { _, _, _ -> Reply.HangUp }.use { mock ->
            val connected = runBlocking { client(mock.port, ElectrumProtocol.SSL, timeoutMillis = 2_000).testConnection() }
            val text = logText()

            assertFalse(text, connected)
            assertTrue(text, text.contains("TLS handshake failed"))
        }
    }

    @Test
    fun `peer closing without a reply is reported as a failure`() {
        MockElectrumServer { _, _, _ -> Reply.HangUp }.use { mock ->
            val connected = runBlocking { client(mock.port).testConnection() }
            val text = logText()

            assertFalse(text, connected)
            assertTrue(text, text.contains("peer closed the connection"))
        }
    }

    @Test
    fun `closed port is reported as connection refused`() {
        val port = closedLoopbackPort()

        val connected = runBlocking { client(port, timeoutMillis = 2_000).testConnection() }
        val text = logText()

        assertFalse(text, connected)
        assertTrue(text, text.contains("connection refused") || text.contains("timed out"))
    }

    // --- errno-aware wording -------------------------------------------------
    //
    // A raw-socket failure carries an errno in its message, and the errno says
    // which side of the connection is at fault. EACCES/EPERM is the OS refusing
    // the socket on the phone (GrapheneOS' Network permission, VPN lockdown, a
    // firewall app, Data Saver) — nothing to do with the server — and must not
    // be reported as "connection refused", which sends the user off to debug a
    // machine that never heard the request.

    /** A transport that fails the dial with [failure] instead of opening a socket. */
    private class FailingTransport(private val failure: Throwable) : ElectrumTransport {
        override fun describe(host: String): String = "direct (clearnet host)"

        override suspend fun connect(host: String, port: Int, timeoutMillis: Int, log: (String) -> Unit): Socket =
            throw failure
    }

    private fun failingClient(failure: Throwable, host: String = "192.168.1.244", port: Int = 50001) =
        ElectrumClient(
            server = ElectrumServer(host = host, port = port, protocol = ElectrumProtocol.TCP),
            timeoutMillis = 2_000,
            log = { logs += it },
            transport = FailingTransport(failure),
        )

    @Test
    fun `an EACCES dial failure names the on-device causes, not the server`() {
        // The message a GrapheneOS/VPN-lockdown denial actually produces.
        val eacces = ConnectException(
            "failed to connect to /192.168.1.244 (port 50001) from /192.168.1.5 after 2000ms: " +
                "isConnected failed: EACCES (Permission denied)",
        )

        val connected = runBlocking { failingClient(eacces).testConnection() }
        val text = logText()

        assertFalse(text, connected)
        assertTrue(text, text.contains("EACCES"))
        assertTrue(text, text.contains("Network permission"))
        assertTrue(text, text.contains("VPN lockdown"))
        assertTrue(text, text.contains("firewall"))
        assertTrue(text, text.contains("Data Saver"))
        assertFalse(
            "the OS denied the socket; the server never refused anything",
            text.contains("connection refused"),
        )
    }

    @Test
    fun `an EPERM dial failure is a permission denial too`() {
        val eperm = SocketException("socket failed: EPERM (Operation not permitted)")

        val connected = runBlocking { failingClient(eperm, port = 50001).testConnection() }
        val text = logText()

        assertFalse(text, connected)
        assertTrue(text, text.contains("EPERM"))
        assertTrue(text, text.contains("Network permission"))
        assertFalse(text, text.contains("connection refused"))
    }

    @Test
    fun `an errno in the cause chain is found`() {
        val wrapper = ConnectException("failed to connect to /10.0.0.5 (port 50001)")
        wrapper.initCause(SocketException("connect failed: ENETUNREACH (Network is unreachable)"))

        val connected = runBlocking { failingClient(wrapper, host = "10.0.0.5").testConnection() }
        val text = logText()

        assertFalse(text, connected)
        assertTrue(text, text.contains("network unreachable"))
        assertFalse(text, text.contains("connection refused"))
    }

    @Test
    fun `an EHOSTUNREACH dial failure is a host-unreachable, not a refusal`() {
        val unreachable = ConnectException("connect failed: EHOSTUNREACH (No route to host)")

        val connected = runBlocking { failingClient(unreachable).testConnection() }
        val text = logText()

        assertFalse(text, connected)
        assertTrue(text, text.contains("host unreachable"))
        assertTrue(text, text.contains("EHOSTUNREACH"))
        assertFalse(text, text.contains("network unreachable"))
        assertFalse(text, text.contains("connection refused"))
    }

    @Test
    fun `an ECONNREFUSED dial failure stays connection refused`() {
        val refused = ConnectException("connect failed: ECONNREFUSED (Connection refused)")

        val connected = runBlocking { failingClient(refused).testConnection() }
        val text = logText()

        assertFalse(text, connected)
        assertTrue(text, text.contains("connection refused"))
    }

    @Test
    fun `an ETIMEDOUT dial is a timed-out attempt, distinct from a read timeout`() {
        val timedOut = ConnectException("failed to connect to /203.0.113.7 (port 50001) after 10000ms: ETIMEDOUT")

        val connected = runBlocking { failingClient(timedOut, host = "203.0.113.7").testConnection() }
        val text = logText()

        assertFalse(text, connected)
        assertTrue(text, text.contains("the connection attempt timed out"))
        // The read/connect timeout the JVM itself raises keeps its own wording —
        // that is the one the 10s timeout explains.
        assertTrue(text, text.contains("ETIMEDOUT"))
        assertFalse(text, text.contains("connection refused"))
    }

    @Test
    fun `a timeout raised by the JVM still reports the timeout it waited`() {
        val timedOut = SocketTimeoutException("connect timed out")

        val connected = runBlocking { failingClient(timedOut).testConnection() }
        val text = logText()

        assertFalse(text, connected)
        assertTrue(text, text.contains("timed out after 2000ms"))
    }

    @Test
    fun `an errno-free ConnectException keeps the connection-refused wording`() {
        val refused = ConnectException("failed to connect to /127.0.0.1 (port 9)")

        val connected = runBlocking { failingClient(refused, port = 9).testConnection() }
        val text = logText()

        assertFalse(text, connected)
        assertTrue(text, text.contains("connection refused"))
    }

    @Test
    fun `a protocol error's text is not read as a socket errno`() {
        // The errno vocabulary is only for what the kernel returns from a
        // socket; a server's own error prose is not a socket failure, whatever
        // it happens to contain.
        val protocolError = ElectrumException("'server.features' returned an error: EACCES is in this text")

        val connected = runBlocking { failingClient(protocolError).testConnection() }
        val text = logText()

        assertFalse(text, connected)
        assertFalse("a server's prose is not a kernel errno", text.contains("Network permission"))
    }

    @Test
    fun `unresolvable host is reported as a failure without crashing`() {
        val client = ElectrumClient(
            server = ElectrumServer(host = "nonexistent.invalid", port = 50001, protocol = ElectrumProtocol.TCP),
            timeoutMillis = 2_000,
            log = { logs += it },
        )

        val connected = runBlocking { client.testConnection() }
        val text = logText()

        assertFalse(text, connected)
        assertTrue(text, text.contains("connection test failed"))
        assertTrue(text, text.contains("DNS lookup failed") || text.contains("timed out"))
    }

    @Test
    fun `invalid endpoint is rejected before any socket is opened`() {
        val result = runBlocking {
            ElectrumClient(
                server = ElectrumServer(host = " ", port = 50001, protocol = ElectrumProtocol.TCP),
                log = { logs += it },
            ).testConnection()
        }

        assertFalse(logText(), result)
        assertTrue(logText(), logText().contains("host is empty"))
    }

    @Test
    fun `ping succeeds on server ping and stays out of the log`() {
        MockElectrumServer { method, _, _ ->
            if (method == "server.ping") Reply.Result(null) else Reply.Error(-32601, "unknown method $method")
        }.use { mock ->
            val alive = runBlocking { client(mock.port).ping() }

            assertTrue("expected the ping to succeed", alive)
            assertNull("mock server failed: ${mock.failure}", mock.failure)
            // One request, no handshake, and no parameters — the cheap probe.
            assertEquals(listOf("server.ping"), mock.requests.map { it["method"] })
            assertEquals(emptyList<Any?>(), mock.requests.first()["params"])
            // The heartbeat fires every minute: nothing may reach the log.
            assertEquals(emptyList<String>(), logs)
        }
    }

    @Test
    fun `ping reports a closed port as false`() {
        val port = closedLoopbackPort()

        val alive = runBlocking { client(port, timeoutMillis = 2_000).ping() }

        assertFalse("expected a refused connection to read as not alive", alive)
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun `ping reports an rpc error as false`() {
        MockElectrumServer { _, _, _ -> Reply.Error(-32601, "unknown method") }.use { mock ->
            val alive = runBlocking { client(mock.port).ping() }

            assertFalse("expected an RPC error to read as not alive", alive)
            assertEquals(emptyList<String>(), logs)
        }
    }

    @Test
    fun `ping reports a peer that closes without a reply as false`() {
        MockElectrumServer { _, _, _ -> Reply.HangUp }.use { mock ->
            val alive = runBlocking { client(mock.port).ping() }

            assertFalse("expected a hang-up to read as not alive", alive)
            assertEquals(emptyList<String>(), logs)
        }
    }

    private companion object {
        const val GENESIS_HASH = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"
    }
}
