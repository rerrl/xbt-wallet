package com.xbtwallet.app.chain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The reconnect loop — the policy itself is [ReconnectPolicyTest]'s business;
 * this is what the loop *does* with it.
 *
 * The manager here is the real one (`SubscriptionManager` over a fake
 * [SubscriptionStateStore] and fake [ChainSubscriptionSession]s), because the
 * point of these tests is the thing this card is most likely to get subtly
 * wrong: that a reconnect is one code path with a live notification — subscribe,
 * status-hash diff, targeted refresh — and not a rescan, and that nothing about
 * it runs twice, forever, or after the caller has left.
 *
 * There is no `kotlinx-coroutines-test` in this project, so the loop runs in a
 * real coroutine scope and the asynchronous parts are awaited with a deadline.
 */
class ReconnectingSubscriptionTest {

    // ---- the doubles -------------------------------------------------------

    private class FakeStore(persisted: Map<String, String?> = emptyMap()) : SubscriptionStateStore {
        @Volatile
        var persisted: Map<String, String?> = persisted

        val statusWrites = CopyOnWriteArrayList<Map<String, String?>>()

        override fun loadStatusHashes(): Map<String, String?> = persisted

        override fun saveStatusHashes(statuses: Map<String, String?>) {
            persisted = statuses
            statusWrites += statuses
        }

        override fun saveSnapshot(snapshot: AccountSnapshot) = Unit
    }

    /** A scripted server behind one connection, recording what was asked of it. */
    private class FakeSession : ChainSubscriptionSession {
        val subscribeAnswers = HashMap<String, String?>()
        val histories = HashMap<String, List<HistoryEntry>>()
        val balances = HashMap<String, ScripthashBalance>()
        val unspents = HashMap<String, List<ChainUtxo>>()
        val transactions = HashMap<String, String>()
        val headers = HashMap<Int, String>()
        var tip: Int = 800_000

        private val recorded = CopyOnWriteArrayList<String>()
        val calls: List<String> get() = recorded.toList()

        fun count(call: String): Int = recorded.count { it == call }

        private val closes = AtomicInteger(0)

        /** How many times [close] was called — the loop owns the dead session's socket. */
        val closeCount: Int get() = closes.get()

        @Volatile
        private var closedFlag = false

        override var onNotification: (String, Any?) -> Unit = { _, _ -> }

        override var onClosed: (Throwable) -> Unit = { }

        override val isClosed: Boolean get() = closedFlag

        override fun close() {
            closedFlag = true
            closes.incrementAndGet()
        }

        /** The connection dying — the session's one terminal callback. */
        fun die(error: Throwable) {
            closedFlag = true
            onClosed(error)
        }

        override fun subscribeScripthash(scripthash: String): String? {
            recorded += "subscribe:$scripthash"
            return subscribeAnswers[scripthash]
        }

        override fun subscribeHeaders(): ChainTip {
            recorded += "subscribe-headers"
            return ChainTip(tip, GENESIS_HEADER)
        }

        override fun getHistory(scripthash: String): List<HistoryEntry> {
            recorded += "history:$scripthash"
            return histories[scripthash] ?: emptyList()
        }

        override fun getBalance(scripthash: String): ScripthashBalance {
            recorded += "balance:$scripthash"
            return balances[scripthash] ?: ScripthashBalance.ZERO
        }

        override fun listUnspent(scripthash: String): List<ChainUtxo> {
            recorded += "unspent:$scripthash"
            return unspents[scripthash] ?: emptyList()
        }

        override fun getTransaction(txHash: String): String {
            recorded += "tx:$txHash"
            return transactions[txHash] ?: throw IllegalStateException("no fixture for $txHash")
        }

        override fun getTipHeight(): Int {
            recorded += "tip"
            return tip
        }

        override fun blockHeader(height: Int): String {
            recorded += "header:$height"
            return headers[height] ?: throw IllegalStateException("no header fixture for height $height")
        }
    }

    // ---- fixtures ----------------------------------------------------------

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun address(chain: AddressChain, index: Int, used: Boolean = false, history: List<HistoryEntry> = emptyList()) =
        ScannedAddress(
            chain = chain,
            index = index,
            address = "bc1q-${chain.name.lowercase()}-$index",
            scripthash = sh(chain, index),
            used = used,
            history = history,
            scriptPubKeyHex = SPK_A,
        )

    /** One used address holding [FUNDING_TX], plus the unused lookahead. */
    private fun fundedSnapshot() = AccountSnapshot(
        receive = listOf(
            address(AddressChain.RECEIVE, 0, used = true, history = listOf(HistoryEntry(FUNDING_TX, 800_000))),
            address(AddressChain.RECEIVE, 1),
        ),
        change = emptyList(),
        utxos = listOf(ScannedUtxo(ChainUtxo(FUNDING_TX, 0, 800_000, 1_000), AddressChain.RECEIVE, 0)),
        balance = ScripthashBalance(confirmedSatoshis = 1_000, unconfirmedSatoshis = 0),
        transactions = listOf(TransactionRow(txHash = FUNDING_TX, height = 800_000, confirmations = 1, netSatoshis = 1_000)),
    )

    /** The connection a first dial would have produced: the used address at [status]. */
    private fun firstSession(status: String) = FakeSession().apply {
        subscribeAnswers[SH_A0] = status
        subscribeAnswers[SH_A1] = null
        histories[SH_A0] = listOf(HistoryEntry(FUNDING_TX, 800_000))
        balances[SH_A0] = ScripthashBalance(confirmedSatoshis = 1_000, unconfirmedSatoshis = 0)
        unspents[SH_A0] = listOf(ChainUtxo(FUNDING_TX, 0, 800_000, 1_000))
        transactions[FUNDING_TX] = FUNDING_HEX
        headers[800_000] = GENESIS_HEADER
    }

    /** The connection after the gap: the used address gained an unconfirmed payment. */
    private fun paidWhileDownSession(status: String) = FakeSession().apply {
        subscribeAnswers[SH_A0] = status
        subscribeAnswers[SH_A1] = null
        histories[SH_A0] = listOf(HistoryEntry(FUNDING_TX, 800_000), HistoryEntry(SECOND_TX, 0))
        balances[SH_A0] = ScripthashBalance(confirmedSatoshis = 1_000, unconfirmedSatoshis = 400)
        unspents[SH_A0] = listOf(
            ChainUtxo(FUNDING_TX, 0, 800_000, 1_000),
            ChainUtxo(SECOND_TX, 1, 0, 400),
        )
        transactions[FUNDING_TX] = FUNDING_HEX
        transactions[SECOND_TX] = SECOND_HEX
        headers[800_000] = GENESIS_HEADER
    }

    private fun policy(maxAttempts: Int = 6, stableSessionMillis: Long = 60_000L) = ReconnectPolicy(
        maxAttempts = maxAttempts,
        jitterFraction = 0.0,
        stableSessionMillis = stableSessionMillis,
    )

    // ---- reconnecting, resubscribing, catching up --------------------------

    @Test
    fun `a dropped connection is re-dialled, resubscribed, and the missed window is caught up by the status diff`() {
        runBlocking {
            val store = FakeStore(mapOf(SH_A0 to "h-old"))
            val manager = SubscriptionManager(store)
            val first = firstSession("h1")
            val second = paidWhileDownSession("h2")
            val opened = AtomicInteger(0)
            val delays = CopyOnWriteArrayList<Long>()
            val sessions = CopyOnWriteArrayList<ChainSubscriptionSession>()
            val events = CopyOnWriteArrayList<ChainEvent>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val done = CompletableDeferred<SubscriptionOutcome>()

            scope.launch {
                done.complete(
                    ReconnectingSubscription(
                        engine = manager,
                        open = {
                            opened.incrementAndGet()
                            second
                        },
                        policy = policy(),
                        backoff = { delays += it },
                        onSession = { sessions += it },
                        onEvent = { events += it },
                    ).run(first, fundedSnapshot()),
                )
            }

            // The first connection subscribes to everything — the unused lookahead
            // included — and catches the *stored* status up: "h-old" to "h1" is a
            // mismatch, exactly as if it had been pushed.
            awaitUntil("the first connection to subscribe") { first.count("subscribe-headers") == 1 }
            awaitUntil("the first connection's catch-up") { store.persisted[SH_A0] == "h1" }
            assertEquals(listOf("subscribe:$SH_A0", "subscribe:$SH_A1"), first.calls.take(2))

            // Then the circuit drops.
            first.die(IOException("the Tor circuit dropped"))

            // One reconnect, after exactly the first backoff, and the replacement
            // is subscribed — every address and the headers — because that is what
            // the diff is computed from.
            awaitUntil("the reconnect") { second.count("subscribe:$SH_A0") == 1 }
            assertEquals("one dial, no storm", 1, opened.get())
            assertEquals(listOf(2_000L), delays.toList())
            assertEquals(1, second.count("subscribe:$SH_A1"))
            assertEquals(1, second.count("subscribe-headers"))

            // The gap is caught up through the *notification* path: a mismatch on
            // the re-subscribe is an AddressChanged plus a targeted refresh, not a
            // rescan and not a poll.
            awaitUntil("the gap's targeted refresh") { second.count("history:$SH_A0") == 1 }
            awaitUntil("the snapshot to reflect the payment") {
                manager.snapshot.addresses.first { it.scripthash == SH_A0 }.history.size == 2
            }

            assertEquals("the sum over the used addresses, the scan's own rule", 1_400L, manager.snapshot.balance.totalSatoshis)
            assertEquals(400L, manager.snapshot.balance.unconfirmedSatoshis)
            assertEquals("the unused lookahead is never fetched", 0, second.count("history:$SH_A1"))
            awaitUntil("the new status to be committed") { store.persisted[SH_A0] == "h2" }
            assertEquals(
                "the loop turns a loss into a reconnect, not into something the caller has to see",
                listOf(ChainEvent.AddressChanged(SH_A0, "h1"), ChainEvent.AddressChanged(SH_A0, "h2")),
                events.filterIsInstance<ChainEvent.AddressChanged>(),
            )
            assertEquals("the dead connection is closed by the loop", 1, first.closeCount)
            assertFalse("the live connection is left alone", second.isClosed)
            assertEquals("the loop reports every connection it runs on", listOf(first, second), sessions.toList())

            // Tearing the engine down ends the loop: there is nothing left to collect.
            manager.close()
            assertEquals(SubscriptionOutcome.Stopped, withTimeout(TIMEOUT_MILLIS) { done.await() })
            scope.cancel()
        }
    }

    // ---- giving up ---------------------------------------------------------

    @Test
    fun `reconnecting gives up after the budget and leaves the caller on the pull path`() {
        runBlocking {
            val store = FakeStore(mapOf(SH_A0 to "h0"))
            val manager = SubscriptionManager(store)
            val first = firstSession("h0")
            val opened = AtomicInteger(0)
            val delays = CopyOnWriteArrayList<Long>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val done = CompletableDeferred<SubscriptionOutcome>()

            scope.launch {
                done.complete(
                    ReconnectingSubscription(
                        engine = manager,
                        open = {
                            opened.incrementAndGet()
                            throw ConnectException("Connection refused")
                        },
                        policy = policy(maxAttempts = 3),
                        backoff = { delays += it },
                    ).run(first, fundedSnapshot()),
                )
            }

            awaitUntil("the first connection to subscribe") { first.count("subscribe-headers") == 1 }

            val failure = IOException("the Tor circuit dropped")
            first.die(failure)

            // Three attempts, spaced by the policy's own schedule — and then it
            // stops: no fourth dial, no new manager, nothing else on the wire.
            val outcome = withTimeout(TIMEOUT_MILLIS) { done.await() }
            assertEquals(SubscriptionOutcome.GivenUp(attempts = 3, error = failure), outcome)
            assertEquals(listOf(2_000L, 4_000L, 8_000L), delays.toList())
            assertEquals(3, opened.get())
            Thread.sleep(50)
            assertEquals("giving up is not a licence to keep dialling", 3, opened.get())
            assertEquals("the dead connection is closed", 1, first.closeCount)

            // The manager is the caller's to close: the loop never closes it.
            manager.close()
            scope.cancel()
        }
    }

    @Test
    fun `a connection that lived long enough restores the reconnect budget`() {
        runBlocking {
            val store = FakeStore(mapOf(SH_A0 to "h0"))
            val manager = SubscriptionManager(store)
            val first = firstSession("h0")
            val second = paidWhileDownSession("h1")
            val attempts = CopyOnWriteArrayList<Int>()
            val delays = CopyOnWriteArrayList<Long>()
            val clock = longArrayOf(1_000_000L)
            var dials = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val done = CompletableDeferred<SubscriptionOutcome>()

            scope.launch {
                done.complete(
                    ReconnectingSubscription(
                        engine = manager,
                        open = { attempt ->
                            attempts += attempt
                            dials++
                            // The first reconnect fails; the second works; the rest
                            // (a fresh episode's budget) fail again.
                            if (dials == 2) second else throw ConnectException("Connection refused")
                        },
                        policy = policy(maxAttempts = 2, stableSessionMillis = 60_000L),
                        backoff = { delays += it },
                        now = { clock[0] },
                    ).run(first, fundedSnapshot()),
                )
            }

            awaitUntil("the first connection to subscribe") { first.count("subscribe-headers") == 1 }
            first.die(IOException("the circuit dropped"))
            awaitUntil("the successful reconnect") { second.count("subscribe:$SH_A0") == 1 }

            // That connection then works for a while — and that is a new episode,
            // not a flap: a device that slept for an hour must not be left with a
            // spent budget for the rest of the day.
            clock[0] += 61_000L
            second.die(IOException("the circuit dropped again"))

            // The budget was restored, so a full two more attempts are made (and
            // this time the opener never recovers) before the loop gives up.
            val outcome = withTimeout(TIMEOUT_MILLIS) { done.await() }
            assertEquals(2, (outcome as SubscriptionOutcome.GivenUp).attempts)
            assertEquals(listOf(1, 2, 1, 2), attempts.toList())
            assertEquals(listOf(2_000L, 4_000L, 2_000L, 4_000L), delays.toList())
            manager.close()
            scope.cancel()
        }
    }

    @Test
    fun `a server that accepts every connection and drops it still gives up`() {
        runBlocking {
            // The reconnect-storm case: the dial *succeeds* every time and the
            // connection dies immediately after. Nothing about a socket being
            // opened counts as success — only a connection that stays up does.
            val store = FakeStore(mapOf(SH_A0 to "h0"))
            val manager = SubscriptionManager(store)
            val first = firstSession("h0")
            val flapping = (1..3).map { paidWhileDownSession("h$it") }
            val opened = AtomicInteger(0)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val done = CompletableDeferred<SubscriptionOutcome>()

            scope.launch {
                done.complete(
                    ReconnectingSubscription(
                        engine = manager,
                        open = {
                            opened.incrementAndGet()
                            flapping[opened.get() - 1]
                        },
                        policy = policy(maxAttempts = 3),
                        backoff = { },
                    ).run(first, fundedSnapshot()),
                )
            }

            awaitUntil("the first connection to subscribe") { first.count("subscribe-headers") == 1 }
            first.die(IOException("the circuit dropped"))

            // Every replacement dies as soon as it is resubscribed: the loop walks
            // its whole budget instead of re-dialling forever.
            awaitUntil("the first flap") { flapping[0].count("subscribe:$SH_A0") == 1 }
            flapping[0].die(IOException("dropped again"))
            awaitUntil("the second flap") { flapping[1].count("subscribe:$SH_A0") == 1 }
            flapping[1].die(IOException("dropped again"))
            awaitUntil("the third flap") { flapping[2].count("subscribe:$SH_A0") == 1 }
            flapping[2].die(IOException("dropped again"))

            val outcome = withTimeout(TIMEOUT_MILLIS) { done.await() }
            assertEquals(3, (outcome as SubscriptionOutcome.GivenUp).attempts)
            Thread.sleep(50)
            assertEquals("three dials and no more", 3, opened.get())
            manager.close()
            scope.cancel()
        }
    }

    // ---- cancelling --------------------------------------------------------

    @Test
    fun `a teardown cancels the loop mid-backoff and never dials`() {
        runBlocking {
            val store = FakeStore(mapOf(SH_A0 to "h0"))
            val manager = SubscriptionManager(store)
            val first = firstSession("h0")
            val opened = AtomicInteger(0)
            val enteredBackoff = CompletableDeferred<Long>()
            var outcome: SubscriptionOutcome? = null
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            val job = scope.launch {
                outcome = ReconnectingSubscription(
                    engine = manager,
                    open = {
                        opened.incrementAndGet()
                        throw AssertionError("a torn-down subscription must never dial again")
                    },
                    policy = policy(),
                    backoff = { waitMillis ->
                        // A backoff the test holds open: this is where the loop
                        // spends its time, and where leaving the screen has to
                        // reach it.
                        enteredBackoff.complete(waitMillis)
                        CompletableDeferred<Unit>().await()
                    },
                ).run(first, fundedSnapshot())
            }

            awaitUntil("the first connection to subscribe") { first.count("subscribe-headers") == 1 }
            first.die(IOException("the circuit dropped"))

            val waitMillis = withTimeout(TIMEOUT_MILLIS) { enteredBackoff.await() }
            assertEquals("the wait is the policy's first one", 2_000L, waitMillis)
            assertEquals("the dead connection is closed before the wait", 1, first.closeCount)

            // Navigating away cancels the job, which is sleeping out that wait.
            job.cancel()
            withTimeout(TIMEOUT_MILLIS) { job.join() }

            assertTrue("the job ends cancelled, not in an error", job.isCancelled)
            assertNull("the loop never returned an outcome: it was cancelled mid-backoff", outcome)
            assertEquals("a cancelled loop dials nothing", 0, opened.get())
            assertTrue("and it spent the whole wait before giving up", enteredBackoff.isCompleted)
            manager.close()
            scope.cancel()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L

        /** The mainnet genesis header, the one timestamp beyond argument (1231006505). */
        const val GENESIS_HEADER =
            "0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a29ab5f49ffff001d1dac2b7c"

        const val PARENT_TX = "1111111111111111111111111111111111111111111111111111111111111111"

        val SPK_A: String = TestTransactions.p2wpkhSpk("ab".repeat(20))

        fun sh(chain: AddressChain, index: Int): String = "sh-${chain.name.lowercase()}-$index"

        /** A funding transaction paying the used address. */
        val FUNDING: Pair<String, String> = TestTransactions.buildWithId(listOf(PARENT_TX to 0), listOf(1_000L to SPK_A))

        /** A second transaction, also paying it: the payment that arrived while down. */
        val SECOND: Pair<String, String> = TestTransactions.buildWithId(listOf(PARENT_TX to 1), listOf(400L to SPK_A))

        val FUNDING_TX: String = FUNDING.first
        val FUNDING_HEX: String = FUNDING.second
        val SECOND_TX: String = SECOND.first
        val SECOND_HEX: String = SECOND.second

        val SH_A0: String = sh(AddressChain.RECEIVE, 0)
        val SH_A1: String = sh(AddressChain.RECEIVE, 1)
    }
}
