package com.xbtwallet.app.electrum

import com.xbtwallet.app.chain.HistoryEntry
import com.xbtwallet.app.chain.Scripthash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The long-lived subscription session, end to end over a loopback socket.
 *
 * These tests are the wire-level half of the live-updates feature: a real
 * [ElectrumClient] opens a real [SubscriptionSession] against
 * [MockElectrumServer], and the session is driven the way the chain layer will
 * drive it — several requests in flight, notifications pushed in between, and a
 * peer that stops answering.
 *
 * The mock had to grow for this (additively): it can now hold a reply for the
 * test to send later ([MockElectrumServer.Reply.Defer]), push a notification
 * at any moment ([MockElectrumServer.pushNotification]) and hand the client a
 * line that is not JSON ([MockElectrumServer.Reply.Raw]). Those three are what
 * make interleaving and out-of-order replies deterministic instead of racy.
 */
class SubscriptionSessionTest {

    private fun client(port: Int) = ElectrumClient(
        server = ElectrumServer(host = "127.0.0.1", port = port, protocol = ElectrumProtocol.TCP),
        timeoutMillis = 5_000,
        log = {},
    )

    private fun open(mock: MockElectrumServer, recorder: Recorder = Recorder()): SubscriptionSession =
        runBlocking { client(mock.port).openSubscriptionSession(recorder::onNotification, recorder::onClosed) }

    /** A scripthash no fixture mentions: the address the server has never seen. */
    private val unusedScripthash = Scripthash.ofP2wpkh(ByteArray(20) { 0x99.toByte() })

    private val usedScripthash = Scripthash.ofP2wpkh(ByteArray(20) { 0x01 })

    @Test
    fun `subscribeScripthash returns the initial status hash, and null for an address the server has not seen`() {
        MockElectrumServer.scripted(statusHashes = mapOf(usedScripthash to STATUS)).use { mock ->
            val session = open(mock)
            try {
                assertEquals(STATUS, session.subscribeScripthash(usedScripthash))
                // Null is an answer, not a failure: Fulcrum sends it for an
                // address with no history, and the chain layer diffs on it.
                assertNull(session.subscribeScripthash(unusedScripthash))

                assertEquals(
                    listOf(
                        "blockchain.scripthash.subscribe",
                        "blockchain.scripthash.subscribe",
                    ),
                    mock.requests.map { it["method"] },
                )
                assertEquals(listOf(usedScripthash), mock.requests.first()["params"])
                // One socket for the whole session, however many requests it carries.
                assertEquals(1, mock.connections)
                assertFalse(session.isClosed)

                // The whole point of a subscription socket: silence is normal.
                // The short-lived sessions' 10s read timeout would tear a healthy
                // idle connection down, so this one waits forever.
                assertEquals(0, session.readTimeoutMillis)
                assertEquals(SubscriptionSession.NO_READ_TIMEOUT_MILLIS, session.readTimeoutMillis)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `subscribeHeaders returns the tip and a pushed block reaches the notification handler`() {
        MockElectrumServer.scripted(tipHeight = 800_000).use { mock ->
            val recorder = Recorder()
            val session = open(mock, recorder)
            try {
                val tip = session.subscribeHeaders()
                assertEquals(800_000, tip.height)
                assertEquals("00", tip.hex)
                assertEquals(listOf("blockchain.headers.subscribe"), mock.requests.map { it["method"] })

                // A new block, pushed while nothing is in flight.
                mock.pushNotification(
                    "blockchain.headers.subscribe",
                    listOf<Any?>(linkedMapOf<String, Any?>("height" to 800_001, "hex" to "ab")),
                )

                assertTrue("the notification never arrived: ${recorder.events}", waitUntil { recorder.events.isNotEmpty() })
                val (method, params) = recorder.events.single()
                assertEquals("blockchain.headers.subscribe", method)
                val header = (params as List<*>).single() as Map<*, *>
                assertEquals(800_001, (header["height"] as Number).toInt())
                assertEquals("ab", header["hex"])
                assertTrue("a notification is not a failure", recorder.closed.get() == null)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `a pushed scripthash notification reaches the handler while another request is in flight`() {
        // Interleaving: the history call is held unanswered by the mock, the
        // server pushes a subscription update, and only then does the reply
        // arrive. The pushed line must go to the handler — not be mistaken for
        // the reply, and not be dropped while a request is outstanding.
        MockElectrumServer { method, _, _ ->
            when (method) {
                "blockchain.scripthash.get_history" -> MockElectrumServer.Reply.Defer
                else -> MockElectrumServer.Reply.Error(-32601, "unknown method $method")
            }
        }.use { mock ->
            val recorder = Recorder()
            val session = open(mock, recorder)
            val pool = Executors.newFixedThreadPool(1)
            try {
                val inFlight = pool.submit(Callable { session.getHistory(usedScripthash) })
                assertTrue("the request never arrived", mock.awaitRequests(1))

                mock.pushNotification(
                    "blockchain.scripthash.subscribe",
                    listOf<Any?>(usedScripthash, STATUS),
                )

                assertTrue("the notification never arrived: ${recorder.events}", waitUntil { recorder.events.isNotEmpty() })
                val (method, params) = recorder.events.single()
                assertEquals("blockchain.scripthash.subscribe", method)
                assertEquals(listOf<Any?>(usedScripthash, STATUS), params)

                // The held request is answered afterwards, and gets its own
                // reply — the notification did not desynchronise the session.
                mock.replyTo(
                    mock.requests.single()["id"],
                    listOf<Any?>(MockElectrumServer.historyEntry(TXID, 800_000)),
                )
                val history = inFlight.get(5, TimeUnit.SECONDS)
                assertEquals(TXID, history.single().txHash)
                assertFalse(session.isClosed)
            } finally {
                pool.shutdownNow()
                session.close()
            }
        }
    }

    @Test
    fun `concurrent requests are answered out of order and each reply reaches its own caller`() {
        val otherScripthash = Scripthash.ofP2wpkh(ByteArray(20) { 0x02 })
        MockElectrumServer { _, _, _ -> MockElectrumServer.Reply.Defer }.use { mock ->
            val session = open(mock)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit(Callable { session.getHistory(usedScripthash) })
                val second = pool.submit(Callable { session.getHistory(otherScripthash) })
                assertTrue("both requests never arrived", mock.awaitRequests(2))

                // Distinguish the two by the scripthash actually sent, not by
                // arrival order — the threads race.
                val idOf = { scripthash: String ->
                    mock.requests.first { (it["params"] as List<*>).firstOrNull() == scripthash }["id"]
                }
                // The second request is answered *first*, and with a distinct
                // result, so a mis-routed reply cannot pass by luck.
                mock.replyTo(idOf(otherScripthash), listOf<Any?>(MockElectrumServer.historyEntry(TXID, 800_000)))
                mock.replyTo(idOf(usedScripthash), emptyList<Any?>())

                assertEquals(TXID, second.get(5, TimeUnit.SECONDS).single().txHash)
                assertEquals(emptyList<HistoryEntry>(), first.get(5, TimeUnit.SECONDS))
                assertEquals(2, mock.requests.size)
            } finally {
                pool.shutdownNow()
                session.close()
            }
        }
    }

    @Test
    fun `a peer that hangs up fails the in-flight call and reports one terminal error`() {
        MockElectrumServer { _, _, _ -> MockElectrumServer.Reply.HangUp }.use { mock ->
            val recorder = Recorder()
            val session = open(mock, recorder)
            val pool = Executors.newFixedThreadPool(1)
            try {
                val inFlight = pool.submit(Callable { session.getHistory(usedScripthash) })

                assertTrue("onClosed was never called", recorder.awaitClosed())
                val reported = recorder.closed.get()
                assertTrue("expected a dead-connection error, got $reported", reported is SubscriptionSessionException)

                // The call that was waiting on the socket fails with the *same*
                // error — the death is one event, reported to everyone.
                val thrown = try {
                    inFlight.get(5, TimeUnit.SECONDS)
                    null
                } catch (failure: ExecutionException) {
                    failure.cause
                }
                assertTrue("expected the in-flight call to fail, got $thrown", thrown is SubscriptionSessionException)
                assertTrue("every waiter hears the same reason", thrown === reported)

                assertTrue(session.isClosed)

                // ... and a caller arriving after the death fails immediately
                // rather than waiting on a socket that will never answer.
                assertThrows(SubscriptionSessionException::class.java) { session.getHistory(usedScripthash) }
                assertEquals("the death is reported exactly once", 1, recorder.closedCount.get())
            } finally {
                pool.shutdownNow()
                session.close()
            }
        }
    }

    @Test
    fun `a malformed line is a terminal failure, not something to skip`() {
        MockElectrumServer { _, _, _ -> MockElectrumServer.Reply.Raw("{this is not json") }.use { mock ->
            val recorder = Recorder()
            val session = open(mock, recorder)
            val pool = Executors.newFixedThreadPool(1)
            try {
                val inFlight = pool.submit(Callable { session.getHistory(usedScripthash) })

                assertTrue("onClosed was never called", recorder.awaitClosed())
                assertTrue(
                    "the error should name the malformed JSON: ${recorder.closed.get()?.message}",
                    recorder.closed.get().orEmptyMessage().contains("malformed"),
                )

                val thrown = try {
                    inFlight.get(5, TimeUnit.SECONDS)
                    null
                } catch (failure: ExecutionException) {
                    failure.cause
                }
                assertTrue("expected the in-flight call to fail, got $thrown", thrown is SubscriptionSessionException)
            } finally {
                pool.shutdownNow()
                session.close()
            }
        }
    }

    @Test
    fun `a json-rpc error is reported to its own caller and leaves the session alive`() {
        MockElectrumServer { method, params, _ ->
            when {
                method == "blockchain.scripthash.get_history" && params.firstOrNull() == usedScripthash ->
                    MockElectrumServer.Reply.Error(-32601, "no history for you")
                method == "blockchain.scripthash.get_history" -> MockElectrumServer.Reply.Result(emptyList<Any?>())
                else -> MockElectrumServer.Reply.Error(-32601, "unknown method $method")
            }
        }.use { mock ->
            val recorder = Recorder()
            val session = open(mock, recorder)
            try {
                val error = assertThrows(ElectrumException::class.java) { session.getHistory(usedScripthash) }
                assertTrue(error.message.orEmpty().contains("get_history"))

                // One request's error is not the connection's death: the next
                // request goes out on the same socket and works.
                assertEquals(emptyList<HistoryEntry>(), session.getHistory(unusedScripthash))
                assertFalse(session.isClosed)
                assertNull(recorder.closed.get())
                assertEquals(1, mock.connections)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `the chain queries run over the same session as the subscriptions`() {
        // The targeted refresh of card 2 reuses exactly these calls on exactly
        // this socket, so they have to work here — one connection, not one per
        // refresh.
        MockElectrumServer.scripted(
            balances = mapOf(usedScripthash to (1_500L to 42L)),
            unspent = mapOf(
                usedScripthash to listOf<Any?>(
                    MockElectrumServer.unspentEntry(TXID, txPos = 0, height = 800_000, value = 1_234),
                ),
            ),
            tipHeight = 800_000,
            statusHashes = mapOf(usedScripthash to STATUS),
        ).use { mock ->
            val session = open(mock)
            try {
                assertEquals(STATUS, session.subscribeScripthash(usedScripthash))
                assertEquals(1_542L, session.getBalance(usedScripthash).totalSatoshis)
                assertEquals(1_234L, session.listUnspent(usedScripthash).single().satoshis)
                assertEquals(800_000, session.getTipHeight())

                assertEquals(1, mock.connections)
                assertFalse(session.isClosed)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `close is idempotent and does not report a failure`() {
        MockElectrumServer.scripted().use { mock ->
            val recorder = Recorder()
            val session = open(mock, recorder)
            session.close()
            session.close()

            assertTrue(session.isClosed)
            // Closing is the caller's decision, not the connection's death.
            assertNull(recorder.closed.get())
            assertThrows(SubscriptionSessionException::class.java) { session.getHistory(usedScripthash) }
        }
    }

    @Test
    fun `calls racing a close all finish — none is left waiting on a dead socket`() {
        // The narrow window this guards: a request that registers itself just as
        // the session dies must still be woken, rather than blocked forever on a
        // reply that can never come.
        MockElectrumServer { _, _, _ -> MockElectrumServer.Reply.Defer }.use { mock ->
            val session = open(mock)
            val pool = Executors.newFixedThreadPool(8)
            try {
                val calls = (1..8).map { pool.submit(Callable { session.getHistory(usedScripthash) }) }
                // Let some calls get in flight and some arrive after the close.
                Thread.sleep(20)
                session.close()

                calls.forEach { call ->
                    val outcome = try {
                        // The mock deferred every request, so returning normally
                        // would itself be a bug: it would mean a phantom reply.
                        call.get(5, TimeUnit.SECONDS)
                        "a request the server never answered returned normally"
                    } catch (thrown: ExecutionException) {
                        thrown.cause
                    }
                    assertTrue(
                        "a call outlived the dead session: $outcome",
                        outcome is SubscriptionSessionException,
                    )
                }
            } finally {
                pool.shutdownNow()
            }
        }
    }

    /** What the session's two callbacks recorded, plus the latch the tests wait on. */
    private class Recorder {
        val events = CopyOnWriteArrayList<Pair<String, Any?>>()
        val closed = AtomicReference<Throwable?>()
        val closedCount = AtomicInteger()
        private val closedLatch = CountDownLatch(1)

        fun onNotification(method: String, params: Any?) {
            events += method to params
        }

        fun onClosed(error: Throwable) {
            closedCount.incrementAndGet()
            closed.set(error)
            closedLatch.countDown()
        }

        fun awaitClosed(seconds: Long = 5): Boolean = closedLatch.await(seconds, TimeUnit.SECONDS)
    }

    private fun Throwable?.orEmptyMessage(): String = this?.message.orEmpty()

    private companion object {
        const val TXID = "1111111111111111111111111111111111111111111111111111111111111111"

        /** The status hash a server sends for an address whose history it has. */
        const val STATUS = "b1e6f2e0c1f1a0a4a2f1b0a1c9e0a2f0d1b0c2e3f4a5b6c7d8e9f0a1b2c3d4e5"

        /**
         * Waits for a condition the callbacks set, so the tests never race the
         * reader thread. 5 seconds is already far past a loopback round trip;
         * the ceiling exists so a broken session fails the test instead of
         * hanging the suite.
         */
        fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (System.nanoTime() < deadline) {
                if (condition()) return true
                Thread.sleep(5)
            }
            return condition()
        }
    }
}
