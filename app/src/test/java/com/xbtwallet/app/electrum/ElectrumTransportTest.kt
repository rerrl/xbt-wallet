package com.xbtwallet.app.electrum

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections

/**
 * The Tor dialling rules: a Tor connect is given a long timeout, a SOCKS refusal
 * is retried a bounded number of times, and the failure that finally escapes is
 * the SOCKS error itself.
 *
 * Everything here runs against a fake SOCKS proxy on loopback — no Tor daemon
 * and no network. The bug this covers is `SocketException: SOCKS server general
 * failure`: Tor's reply 0x01 for a stream it will not serve.
 */
class ElectrumTransportTest {

    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    private fun logText(): String = logs.joinToString(separator = "\n")

    private fun bootstrapAt(port: Int) = object : TorBootstrap {
        override val status: TorStatus = TorStatus.OFF
        override suspend fun start(log: (String) -> Unit): TorProxy = TorProxy(host = "127.0.0.1", port = port)
    }

    @Test
    fun `a stream Tor refuses is attempted three times and the SOCKS failure is rethrown`() {
        FakeSocksProxy(failures = Int.MAX_VALUE).use { proxy ->
            val transport = TorTransport(bootstrapAt(proxy.port), retryDelayMillis = 25L)

            val failure = runCatching {
                runBlocking { transport.connect("example.onion", 50001, timeoutMillis = 5_000, log = { logs += it }) }
            }.exceptionOrNull()

            assertTrue(
                "the SOCKS error must reach the caller: ${failure?.javaClass?.name}: ${failure?.message}",
                failure is SocketException,
            )
            assertTrue(
                "the reported failure must be Tor's, not a generic one: ${failure?.message}",
                failure!!.message.orEmpty().contains("SOCKS"),
            )
            assertEquals("exactly three attempts", TorTransport.DEFAULT_MAX_ATTEMPTS, proxy.connectionCount)
            val text = logText()
            (1..TorTransport.DEFAULT_MAX_ATTEMPTS).forEach { attempt ->
                assertTrue(text, text.contains("< tor: SOCKS connect failed (attempt $attempt):"))
            }
            assertTrue("the retry count must not be exceeded:\n$text", !text.contains("(attempt 4)"))
        }
    }

    @Test
    fun `the default backoff is five seconds between attempts`() {
        // Deliberately no injected delay: this asserts the shipped constant, so
        // it takes ~10s (two pauses between three attempts).
        FakeSocksProxy(failures = Int.MAX_VALUE).use { proxy ->
            val transport = TorTransport(bootstrapAt(proxy.port))
            val started = System.nanoTime()

            runCatching {
                runBlocking { transport.connect("example.onion", 50001, timeoutMillis = 1_000, log = { logs += it }) }
            }

            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            val expected = TorTransport.DEFAULT_RETRY_DELAY_MILLIS * (TorTransport.DEFAULT_MAX_ATTEMPTS - 1)
            assertEquals("three attempts", TorTransport.DEFAULT_MAX_ATTEMPTS, proxy.connectionCount)
            assertEquals(5_000L, TorTransport.DEFAULT_RETRY_DELAY_MILLIS)
            assertTrue(
                "waited ${elapsedMillis}ms, expected at least ${expected}ms of backoff",
                elapsedMillis >= expected - 500,
            )
        }
    }

    @Test
    fun `a refusal followed by a successful dial returns a connected socket`() {
        FakeSocksProxy(failures = 1).use { proxy ->
            val transport = TorTransport(bootstrapAt(proxy.port), retryDelayMillis = 25L)

            val socket = runBlocking {
                transport.connect("example.onion", 50001, timeoutMillis = 5_000, log = { logs += it })
            }
            socket.use {
                assertTrue("the returned socket must be connected", it.isConnected)
                assertTrue("and must not be closed", !it.isClosed)
            }
            assertEquals("one refusal, one success", 2, proxy.connectionCount)
            assertEquals(listOf("example.onion" to 50001, "example.onion" to 50001), proxy.requests.toList())
            val text = logText()
            assertTrue(text, text.contains("< tor: SOCKS connect failed (attempt 1):"))
            assertTrue("the second attempt must not be reported as a failure:\n$text", !text.contains("(attempt 2)"))
        }
    }

    @Test
    fun `the tor route is given at least a ninety second timeout`() {
        ServerSocket(0).use { backend ->
            TestSocksProxy(backendPort = backend.localPort).use { socks ->
                val transport = TorTransport(bootstrapAt(socks.port))

                // A short caller timeout — the client's handshake patience — is
                // raised for the Tor route, because an onion rendezvous is slow.
                val short = runBlocking {
                    transport.connect("example.onion", 50001, timeoutMillis = 2_000, log = { logs += it })
                }
                short.use { assertEquals(TorTransport.MIN_TOR_TIMEOUT_MILLIS, it.soTimeout) }

                // A caller timeout longer than the floor is honoured unchanged.
                val long = runBlocking {
                    transport.connect("example.onion", 50001, timeoutMillis = 200_000, log = { logs += it })
                }
                long.use { assertEquals(200_000, it.soTimeout) }

                assertEquals(90_000, TorTransport.MIN_TOR_TIMEOUT_MILLIS)
                val text = logText()
                assertTrue(text, text.contains("timeout 90000ms for the TOR route"))
                assertTrue(text, text.contains("timeout 200000ms for the TOR route"))
            }
        }
    }

    @Test
    fun `a clearnet host through Tor still sends the name unresolved`() {
        // The SOCKS proxy must do the lookup: that is what makes an onion name
        // resolvable at all, and what stops a .onion dial from leaking DNS.
        FakeSocksProxy(failures = 0).use { proxy ->
            val transport = TorTransport(bootstrapAt(proxy.port))
            val socket: Socket = runBlocking {
                transport.connect("rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr.onion", 50001, 3_000) {
                    logs += it
                }
            }
            socket.close()
            assertEquals(
                "the host name must reach the proxy, not a resolved address",
                "rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr.onion" to 50001,
                proxy.requests.single(),
            )
        }
    }

    @Test
    fun `attempts must be at least one`() {
        FakeSocksProxy(failures = Int.MAX_VALUE).use { proxy ->
            val transport = TorTransport(bootstrapAt(proxy.port), maxAttempts = 1, retryDelayMillis = 25L)
            val failure = runCatching {
                runBlocking { transport.connect("example.onion", 50001, 1_000) { logs += it } }
            }.exceptionOrNull()
            if (failure !is SocketException) fail("expected a SocketException, got $failure")
            assertEquals("one attempt, no retry", 1, proxy.connectionCount)
        }
    }
}
