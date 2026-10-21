package com.xbtwallet.app.chain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SubscriptionManager tests — the status-hash diff, the targeted refresh, the
 * coalescing and the failure path.
 *
 * The manager is driven by a fake [ChainSubscriptionSession] (the interface, not
 * the socket): the fake records every call it is handed, so a test can assert
 * not only what the account looks like afterwards but *what was asked of the
 * server* — which is the whole point of a targeted refresh. Notifications are
 * pushed by the test through the same `onNotification` the real reader thread
 * calls, and death through the same `onClosed`.
 *
 * There is no `kotlinx-coroutines-test` in this project, so the asynchronous
 * parts are awaited the plain way: events are awaited off the manager's own
 * [kotlinx.coroutines.flow.Flow] with a timeout, and call logs are polled with a
 * deadline. Anything a test asserts on has already happened by the time it
 * asserts it.
 */
class SubscriptionManagerTest {

    // ---- the in-memory doubles --------------------------------------------

    /** A [SubscriptionStateStore] over a map, recording what was written. */
    private class FakeStore(persisted: Map<String, String?> = emptyMap()) : SubscriptionStateStore {
        @Volatile
        var persisted: Map<String, String?> = persisted

        val statusWrites = CopyOnWriteArrayList<Map<String, String?>>()
        val snapshotWrites = CopyOnWriteArrayList<AccountSnapshot>()

        override fun loadStatusHashes(): Map<String, String?> = persisted

        override fun saveStatusHashes(statuses: Map<String, String?>) {
            persisted = statuses
            statusWrites += statuses
        }

        override fun saveSnapshot(snapshot: AccountSnapshot) {
            snapshotWrites += snapshot
        }
    }

    /**
     * A [ChainSubscriptionSession] with a scripted server behind it.
     *
     * Every query is appended to [calls] before it answers, so a test can count
     * exactly how many times one address was asked about. An unscripted history
     * is empty and an unscripted subscribe status is null — both are real
     * answers, not errors (null is what Fulcrum says about an address it has
     * never seen a transaction for).
     */
    private class FakeSession : ChainSubscriptionSession {
        val subscribeAnswers = HashMap<String, String?>()
        val histories = HashMap<String, List<HistoryEntry>>()
        val balances = HashMap<String, ScripthashBalance>()
        val unspents = HashMap<String, List<ChainUtxo>>()
        val transactions = HashMap<String, String>()
        val headers = HashMap<Int, String>()
        var tip: Int = 800_000
        var tipHeader: ChainTip = ChainTip(800_000, GENESIS_HEADER)

        /** Runs inside [subscribeScripthash] and [getHistory] — hooks for holding the worker still. */
        @Volatile
        var onSubscribe: (() -> Unit)? = null

        @Volatile
        var onHistory: (() -> Unit)? = null

        private val recorded = CopyOnWriteArrayList<String>()
        val calls: List<String> get() = recorded.toList()

        fun count(call: String): Int = recorded.count { it == call }

        @Volatile
        private var closedFlag = false

        override var onNotification: (String, Any?) -> Unit = { _, _ -> }

        override var onClosed: (Throwable) -> Unit = { }

        override val isClosed: Boolean get() = closedFlag

        override fun close() {
            closedFlag = true
        }

        fun notifyScripthash(scripthash: String, status: String?) =
            onNotification(SubscriptionManager.METHOD_SCRIPTHASH, listOf(scripthash, status))

        fun notifyHeaders(tip: ChainTip) = onNotification(
            SubscriptionManager.METHOD_HEADERS,
            listOf(mapOf("height" to tip.height, "hex" to tip.hex)),
        )

        /** The connection dying: the terminal callback, exactly once. */
        fun die(error: Throwable) {
            closedFlag = true
            onClosed(error)
        }

        override fun subscribeScripthash(scripthash: String): String? {
            recorded += "subscribe:$scripthash"
            onSubscribe?.invoke()
            return subscribeAnswers[scripthash]
        }

        override fun subscribeHeaders(): ChainTip {
            recorded += "subscribe-headers"
            return tipHeader
        }

        override fun getHistory(scripthash: String): List<HistoryEntry> {
            recorded += "history:$scripthash"
            onHistory?.invoke()
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
            return transactions[txHash] ?: FUNDING_HEX
        }

        override fun getTipHeight(): Int {
            recorded += "tip"
            return tip
        }

        override fun blockHeader(height: Int): String {
            recorded += "header:$height"
            return headers[height] ?: throw IllegalStateException("no header fixture for height $height")
        }

        override fun broadcast(txHex: String): String =
            throw UnsupportedOperationException("this fake does not broadcast")
    }

    // ---- waiting on the manager -------------------------------------------

    private fun awaitEvent(
        manager: SubscriptionManager,
        what: String,
        predicate: (ChainEvent) -> Boolean,
    ): ChainEvent = runBlocking {
        try {
            withTimeout(TIMEOUT_MILLIS) { manager.events.first(predicate) }
        } catch (timedOut: Exception) {
            throw AssertionError("timed out waiting for $what", timedOut)
        }
    }

    private fun awaitResynced(manager: SubscriptionManager) {
        awaitEvent(manager, "Resynced") { it is ChainEvent.Resynced }
    }

    /** Everything the manager emitted before its next [ChainEvent.Resynced]. */
    private fun eventsUntilResynced(manager: SubscriptionManager): List<ChainEvent> = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            manager.events.takeWhile { it !is ChainEvent.Resynced }.toList()
        }
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $what")
    }

    // ---- fixtures ----------------------------------------------------------

    private fun address(
        chain: AddressChain,
        index: Int,
        used: Boolean = false,
        history: List<HistoryEntry> = emptyList(),
        script: String = SPK_A,
    ) = ScannedAddress(
        chain = chain,
        index = index,
        address = "bc1q-${chain.name.lowercase()}-$index",
        scripthash = sh(chain, index),
        used = used,
        history = history,
        scriptPubKeyHex = script,
    )

    private fun snapshot(
        receive: List<ScannedAddress> = emptyList(),
        change: List<ScannedAddress> = emptyList(),
        utxos: List<ScannedUtxo> = emptyList(),
        balance: ScripthashBalance = ScripthashBalance.ZERO,
        transactions: List<TransactionRow> = emptyList(),
    ) = AccountSnapshot(receive = receive, change = change, utxos = utxos, balance = balance, transactions = transactions)

    /** One confirmed address plus the unused lookahead the receive screen hands out from. */
    private fun fundedSnapshot() = snapshot(
        receive = listOf(
            address(AddressChain.RECEIVE, 0, used = true, history = listOf(HistoryEntry(FUNDING_TX, 800_000))),
            address(AddressChain.RECEIVE, 1, script = SPK_B),
        ),
        balance = ScripthashBalance(confirmedSatoshis = 1_000, unconfirmedSatoshis = 0),
        utxos = listOf(ScannedUtxo(ChainUtxo(FUNDING_TX, 0, 800_000, 1_000), AddressChain.RECEIVE, 0)),
        transactions = listOf(confirmedRow()),
    )

    /** Two used addresses, both holding the same confirmed transaction. */
    private fun twoUsedSnapshot() = snapshot(
        receive = listOf(
            address(AddressChain.RECEIVE, 0, used = true, history = listOf(HistoryEntry(FUNDING_TX, 800_000))),
            address(AddressChain.RECEIVE, 1, used = true, history = listOf(HistoryEntry(FUNDING_TX, 800_000)), script = SPK_B),
        ),
        balance = ScripthashBalance(confirmedSatoshis = 3_000, unconfirmedSatoshis = 0),
        transactions = listOf(confirmedRow()),
    )

    private fun confirmedRow() =
        TransactionRow(txHash = FUNDING_TX, height = 800_000, confirmations = 1, netSatoshis = 1_000)

    // ---- the initial diff --------------------------------------------------

    @Test
    fun `start subscribes to every address in the snapshot, lookahead included, and to block headers`() {
        val store = FakeStore()
        val session = FakeSession().apply {
            subscribeAnswers[SH_A0] = "h0"
            subscribeAnswers[SH_A1] = null // never used: Fulcrum answers null
            subscribeAnswers[SH_C0] = null
        }
        val manager = SubscriptionManager(store)
        val state = snapshot(
            receive = listOf(
                address(AddressChain.RECEIVE, 0, used = true, history = listOf(HistoryEntry(FUNDING_TX, 800_000))),
                address(AddressChain.RECEIVE, 1, script = SPK_B),
            ),
            change = listOf(address(AddressChain.CHANGE, 0, script = SPK_B)),
        )

        manager.start(session, state)
        awaitResynced(manager)

        assertEquals(
            listOf("subscribe:$SH_A0", "subscribe:$SH_A1", "subscribe:$SH_C0", "subscribe-headers"),
            session.calls,
        )
        // Nothing had been seen before, so there is nothing to catch up: the
        // snapshot came from a scan that is fresher than any status we hold.
        assertTrue("no address may be refreshed on a first subscribe: ${session.calls}", session.calls.none { it.startsWith("history:") })
        // The nulls are persisted as real answers, so the lookahead can be diffed later.
        assertEquals(mapOf(SH_A0 to "h0", SH_A1 to null, SH_C0 to null), store.statusWrites.last())
        manager.close()
    }

    @Test
    fun `the initial diff refreshes exactly the addresses whose status changed since it was last seen`() {
        // What the last session left behind: the used address had a status, the
        // lookahead address was null (never paid).
        val store = FakeStore(mapOf(SH_A0 to null, SH_A1 to "h-old"))
        val session = FakeSession().apply {
            subscribeAnswers[SH_A0] = null // unchanged
            subscribeAnswers[SH_A1] = "h1" // a payment arrived while the app was closed
            histories[SH_A1] = listOf(HistoryEntry(FUNDING_TX, 800_000), HistoryEntry(SECOND_TX, 0))
            balances[SH_A1] = ScripthashBalance(confirmedSatoshis = 1_000, unconfirmedSatoshis = 400)
            unspents[SH_A1] = listOf(
                ChainUtxo(FUNDING_TX, 0, 800_000, 1_000),
                ChainUtxo(SECOND_TX, 1, 0, 400),
            )
            transactions[SECOND_TX] = SECOND_HEX
            headers[800_000] = GENESIS_HEADER
        }
        val manager = SubscriptionManager(store)
        val state = snapshot(
            receive = listOf(
                address(AddressChain.RECEIVE, 0, script = SPK_B),
                address(AddressChain.RECEIVE, 1, used = true, history = listOf(HistoryEntry(FUNDING_TX, 800_000))),
            ),
            balance = ScripthashBalance(confirmedSatoshis = 1_000, unconfirmedSatoshis = 0),
            transactions = listOf(confirmedRow()),
        )

        manager.start(session, state)
        val events = eventsUntilResynced(manager)

        // The one address that changed was refreshed; the one that did not was
        // touched by nothing at all.
        assertEquals(listOf(ChainEvent.AddressChanged(SH_A1, "h1")), events)
        assertEquals(1, session.count("history:$SH_A1"))
        assertEquals(1, session.count("balance:$SH_A1"))
        assertEquals(1, session.count("unspent:$SH_A1"))
        assertEquals(0, session.count("history:$SH_A0"))
        assertEquals(0, session.count("balance:$SH_A0"))
        assertEquals(0, session.count("unspent:$SH_A0"))

        // The merged snapshot — not a guess at what the change was: the paid-in
        // transaction is there, unconfirmed, with its net amount read from the
        // transaction itself.
        val landed = manager.snapshot.transactions.first { it.txHash == SECOND_TX }
        assertEquals(0, landed.confirmations)
        assertEquals(400L, landed.netSatoshis)
        assertEquals(400L, manager.snapshot.balance.unconfirmedSatoshis)
        assertEquals(1_000L, manager.snapshot.balance.confirmedSatoshis)
        // ... and it was persisted, together with the status that produced it.
        assertEquals(400L, store.snapshotWrites.last().balance.unconfirmedSatoshis)
        assertEquals("h1", store.persisted[SH_A1])
        manager.close()
    }

    @Test
    fun `a null status for a never-used address is an answer, not a change`() {
        // A0 was seen with a null status (never used) and is still null; A1 has
        // never been subscribed at all.
        val store = FakeStore(mapOf(SH_A0 to null))
        val session = FakeSession().apply {
            subscribeAnswers[SH_A0] = null
            subscribeAnswers[SH_A1] = null
        }
        val manager = SubscriptionManager(store)
        val state = snapshot(
            receive = listOf(
                address(AddressChain.RECEIVE, 0),
                address(AddressChain.RECEIVE, 1, script = SPK_B),
            ),
        )

        manager.start(session, state)
        val events = eventsUntilResynced(manager)

        assertTrue(
            "a null status must not trigger a refresh: ${session.calls}",
            session.calls.none { it.startsWith("history:") },
        )
        assertTrue("and must not be reported as a change: $events", events.isEmpty())
        assertEquals(mapOf(SH_A0 to null, SH_A1 to null), store.persisted)
        manager.close()
    }

    @Test
    fun `a null status after a used one is a change, and the refresh empties the address`() {
        // Defensive: a server that reports no status for an address it once had a
        // history for is still reporting a *change* of status; the refresh is what
        // tells us the address is empty now.
        val store = FakeStore(mapOf(SH_A0 to "h0"))
        val session = FakeSession().apply { subscribeAnswers[SH_A0] = null }
        val manager = SubscriptionManager(store)
        val state = snapshot(
            receive = listOf(
                address(AddressChain.RECEIVE, 0, used = true, history = listOf(HistoryEntry(FUNDING_TX, 800_000))),
            ),
            transactions = listOf(confirmedRow()),
        )

        manager.start(session, state)
        awaitResynced(manager)

        assertEquals(1, session.count("history:$SH_A0"))
        assertFalse("the fresh, empty history wins", manager.snapshot.receive.single().used)
        assertTrue("the null status was committed", store.persisted.containsKey(SH_A0))
        assertNull(store.persisted[SH_A0])
        assertEquals(0L, manager.snapshot.balance.totalSatoshis)
        manager.close()
    }

    // ---- notifications -----------------------------------------------------

    @Test
    fun `a notification refreshes only the address it names`() {
        val store = FakeStore(mapOf(SH_A0 to "h0"))
        val session = FakeSession().apply {
            subscribeAnswers[SH_A0] = "h0"
            subscribeAnswers[SH_A1] = null
            histories[SH_A0] = listOf(HistoryEntry(FUNDING_TX, 800_000))
            balances[SH_A0] = ScripthashBalance(confirmedSatoshis = 1_000, unconfirmedSatoshis = 0)
            unspents[SH_A0] = listOf(ChainUtxo(FUNDING_TX, 0, 800_000, 1_000))
            headers[800_000] = GENESIS_HEADER
        }
        val manager = SubscriptionManager(store)
        manager.start(session, fundedSnapshot())
        awaitResynced(manager)

        session.notifyScripthash(SH_A0, "h0-live")
        val events = eventsUntilResynced(manager)

        assertEquals(listOf(ChainEvent.AddressChanged(SH_A0, "h0-live")), events)
        // Exactly one address's three queries. The unused lookahead was touched by
        // nothing, and the transaction list was rebuilt from the row it already
        // had: a confirmed row at an unchanged height is never re-fetched.
        assertEquals(1, session.count("history:$SH_A0"))
        assertEquals(1, session.count("balance:$SH_A0"))
        assertEquals(1, session.count("unspent:$SH_A0"))
        assertEquals(0, session.count("history:$SH_A1"))
        assertEquals(0, session.count("balance:$SH_A1"))
        assertEquals(0, session.count("tx:$FUNDING_TX"))
        assertEquals(1, store.snapshotWrites.size)
        assertEquals("h0-live", store.persisted[SH_A0])
        manager.close()
    }

    @Test
    fun `a refresh reads the other used addresses' balances once, and never again`() {
        // Two used addresses. The balance must stay the sum of get_balance over the
        // used addresses (the scan's rule — never a UTXO sum), so the first refresh
        // reads the address it does not know yet, once, and every later refresh is
        // the single address that changed.
        val store = FakeStore(mapOf(SH_A0 to "h0", SH_A1 to "h1"))
        val session = FakeSession().apply {
            subscribeAnswers[SH_A0] = "h0"
            subscribeAnswers[SH_A1] = "h1"
            histories[SH_A0] = listOf(HistoryEntry(FUNDING_TX, 800_000))
            histories[SH_A1] = listOf(HistoryEntry(FUNDING_TX, 800_000))
            balances[SH_A0] = ScripthashBalance(confirmedSatoshis = 1_000, unconfirmedSatoshis = 0)
            balances[SH_A1] = ScripthashBalance(confirmedSatoshis = 2_000, unconfirmedSatoshis = 0)
            headers[800_000] = GENESIS_HEADER
        }
        val manager = SubscriptionManager(store)
        manager.start(session, twoUsedSnapshot())
        awaitResynced(manager)

        session.notifyScripthash(SH_A0, "a")
        awaitResynced(manager)
        assertEquals("the other used address is read once, to seed the balance", 1, session.count("balance:$SH_A1"))
        assertEquals(3_000L, manager.snapshot.balance.confirmedSatoshis)

        session.notifyScripthash(SH_A1, "b")
        awaitResynced(manager)

        assertEquals("a balance that is already known is never re-read", 1, session.count("balance:$SH_A0"))
        assertEquals(2, session.count("balance:$SH_A1"))
        assertEquals(1, session.count("history:$SH_A0"))
        assertEquals(1, session.count("history:$SH_A1"))
        manager.close()
    }

    @Test
    fun `notifications arriving while a refresh is in flight collapse into one follow-up`() {
        val store = FakeStore(mapOf(SH_A0 to "h0"))
        val session = FakeSession().apply {
            subscribeAnswers[SH_A0] = "h0"
            histories[SH_A0] = listOf(HistoryEntry(FUNDING_TX, 800_000))
            balances[SH_A0] = ScripthashBalance(confirmedSatoshis = 1_000, unconfirmedSatoshis = 0)
            headers[800_000] = GENESIS_HEADER
        }
        val manager = SubscriptionManager(store)
        manager.start(session, fundedSnapshot())
        awaitResynced(manager)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        session.onHistory = {
            entered.countDown()
            release.await()
        }

        session.notifyScripthash(SH_A0, "s1")
        assertTrue("the first refresh must reach the server", entered.await(10, TimeUnit.SECONDS))

        // Four more notifications for the same address, all while its refresh is
        // on the wire: one follow-up refresh, not four.
        session.notifyScripthash(SH_A0, "s2")
        session.notifyScripthash(SH_A0, "s3")
        session.notifyScripthash(SH_A0, "s4")
        release.countDown()

        awaitUntil("the follow-up refresh") { session.count("history:$SH_A0") == 2 }
        awaitResynced(manager)
        Thread.sleep(50)

        assertEquals("2 refreshes: the one in flight, plus one follow-up", 2, session.count("history:$SH_A0"))
        // The last status the server reported is the one committed, because the
        // follow-up refresh is the one that fetched that status's data.
        assertEquals("s4", store.persisted[SH_A0])
        session.onHistory = null
        manager.close()
    }

    @Test
    fun `notifications for an address already queued are not queued twice`() {
        val store = FakeStore(mapOf(SH_A0 to "h0"))
        val session = FakeSession().apply {
            subscribeAnswers[SH_A0] = "h0"
            histories[SH_A0] = listOf(HistoryEntry(FUNDING_TX, 800_000))
            balances[SH_A0] = ScripthashBalance(confirmedSatoshis = 1_000, unconfirmedSatoshis = 0)
            headers[800_000] = GENESIS_HEADER
        }
        val manager = SubscriptionManager(store)

        // Hold the subscribing job still, so the three notifications below are all
        // queued before anything of theirs can run.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        session.onSubscribe = {
            entered.countDown()
            release.await()
        }
        manager.start(session, fundedSnapshot())
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        session.notifyScripthash(SH_A0, "s1")
        session.notifyScripthash(SH_A0, "s2")
        session.notifyScripthash(SH_A0, "s3")
        release.countDown()

        awaitResynced(manager)
        Thread.sleep(50)

        assertEquals("three notifications for one queued address: one refresh", 1, session.count("history:$SH_A0"))
        assertEquals("s3", store.persisted[SH_A0])
        session.onSubscribe = null
        manager.close()
    }

    @Test
    fun `a notification for an address outside the subscribed set is ignored`() {
        val store = FakeStore()
        val session = FakeSession()
        val manager = SubscriptionManager(store)
        manager.start(session, fundedSnapshot())
        awaitResynced(manager)

        session.notifyScripthash("not-our-address", "whatever")
        Thread.sleep(50)

        assertTrue(session.calls.none { it.contains("not-our-address") })
        manager.close()
    }

    // ---- new blocks --------------------------------------------------------

    @Test
    fun `a new block recomputes confirmations from the new tip without re-fetching mined transactions`() {
        val store = FakeStore(mapOf(SH_A0 to "h0"))
        val session = FakeSession().apply {
            subscribeAnswers[SH_A0] = "h0"
            histories[SH_A0] = listOf(HistoryEntry(FUNDING_TX, 800_000))
            balances[SH_A0] = ScripthashBalance(confirmedSatoshis = 1_000, unconfirmedSatoshis = 0)
            headers[800_000] = GENESIS_HEADER
        }
        val manager = SubscriptionManager(store)
        manager.start(session, fundedSnapshot())
        awaitResynced(manager)
        assertEquals(1, manager.snapshot.transactions.single().confirmations)

        // Five blocks later the row is six deep. The tip the notification carries
        // is reported, not used for the arithmetic: the row builder asks the server.
        session.tip = 800_005
        session.notifyHeaders(ChainTip(800_005, GENESIS_HEADER))
        val events = eventsUntilResynced(manager)

        assertEquals(listOf(ChainEvent.NewBlock(ChainTip(800_005, GENESIS_HEADER))), events)
        assertEquals(6, manager.snapshot.transactions.single().confirmations)
        assertEquals("a confirmed row at an unchanged height is reused", 0, session.count("tx:$FUNDING_TX"))
        assertEquals(1, session.count("header:800000"))
        assertTrue("the rows were persisted", store.snapshotWrites.isNotEmpty())
        manager.close()
    }

    @Test
    fun `a new block re-fetches a still-unconfirmed transaction`() {
        // A mempool row is exactly the one whose amount must not be trusted: it may
        // have been mined at any height and its confirmations just changed, so the
        // reuse rule the resync uses re-reads it (see buildTransactionRows).
        val store = FakeStore(mapOf(SH_A0 to "mempool"))
        val session = FakeSession().apply {
            subscribeAnswers[SH_A0] = "mempool"
            histories[SH_A0] = listOf(HistoryEntry(FUNDING_TX, 0))
            balances[SH_A0] = ScripthashBalance(confirmedSatoshis = 0, unconfirmedSatoshis = 1_000)
            transactions[FUNDING_TX] = FUNDING_HEX
        }
        val manager = SubscriptionManager(store)
        val state = snapshot(
            receive = listOf(address(AddressChain.RECEIVE, 0, used = true, history = listOf(HistoryEntry(FUNDING_TX, 0)))),
            balance = ScripthashBalance(confirmedSatoshis = 0, unconfirmedSatoshis = 1_000),
            transactions = listOf(TransactionRow(FUNDING_TX, height = 0, confirmations = 0, netSatoshis = 1_000)),
        )
        manager.start(session, state)
        awaitResynced(manager)

        session.tip = 800_001
        session.notifyHeaders(ChainTip(800_001, GENESIS_HEADER))
        awaitResynced(manager)

        assertEquals(1, session.count("tx:$FUNDING_TX"))
        assertEquals(0, manager.snapshot.transactions.single().confirmations)
        manager.close()
    }

    // ---- the session dying -------------------------------------------------

    @Test
    fun `SessionLost is reported and the dead session is not touched again`() {
        val store = FakeStore(mapOf(SH_A0 to "h0"))
        val session = FakeSession().apply {
            subscribeAnswers[SH_A0] = "h0"
            histories[SH_A0] = listOf(HistoryEntry(FUNDING_TX, 800_000))
            balances[SH_A0] = ScripthashBalance(confirmedSatoshis = 1_000, unconfirmedSatoshis = 0)
        }
        val manager = SubscriptionManager(store)
        manager.start(session, fundedSnapshot())
        awaitResynced(manager)

        val failure = IllegalStateException("the peer closed the connection")
        session.die(failure)
        assertEquals(ChainEvent.SessionLost(failure), awaitEvent(manager, "SessionLost") { it is ChainEvent.SessionLost })

        // A notification arriving after the death — the real session can still be
        // racing one out — must not put any work back on the wire.
        val before = session.calls.size
        session.notifyScripthash(SH_A0, "should be ignored")
        session.notifyHeaders(ChainTip(800_001, GENESIS_HEADER))
        Thread.sleep(50)

        assertEquals(before, session.calls.size)
        manager.close()
    }

    @Test
    fun `closing stops the manager and detaches the session's callbacks`() {
        val store = FakeStore(mapOf(SH_A0 to "h0"))
        val session = FakeSession().apply {
            subscribeAnswers[SH_A0] = "h0"
            histories[SH_A0] = listOf(HistoryEntry(FUNDING_TX, 800_000))
        }
        val manager = SubscriptionManager(store)
        manager.start(session, fundedSnapshot())
        awaitResynced(manager)

        manager.close()
        val before = session.calls.size
        session.notifyScripthash(SH_A0, "after close")
        session.notifyHeaders(ChainTip(800_001, GENESIS_HEADER))
        Thread.sleep(50)

        assertEquals(before, session.calls.size)
        // Idempotent.
        manager.close()
    }

    // ---- reconnecting ------------------------------------------------------

    @Test
    fun `starting again with a new session catches up everything that changed in the gap`() {
        val store = FakeStore(mapOf(SH_A0 to "h0"))
        val first = FakeSession().apply { subscribeAnswers[SH_A0] = "h0" }
        val manager = SubscriptionManager(store)
        val state = fundedSnapshot()
        manager.start(first, state)
        awaitResynced(manager)
        assertEquals("nothing changed, so nothing was refreshed", 0, first.count("history:$SH_A0"))

        // The connection dropped, and a payment arrived while it was down. A new
        // session re-subscribes; the status diff notices, with no polling and no
        // separate "reconnect" code path.
        val second = FakeSession().apply {
            subscribeAnswers[SH_A0] = "h1"
            histories[SH_A0] = listOf(HistoryEntry(FUNDING_TX, 800_000), HistoryEntry(SECOND_TX, 0))
            balances[SH_A0] = ScripthashBalance(confirmedSatoshis = 1_000, unconfirmedSatoshis = 400)
            unspents[SH_A0] = listOf(
                ChainUtxo(FUNDING_TX, 0, 800_000, 1_000),
                ChainUtxo(SECOND_TX, 1, 0, 400),
            )
            transactions[SECOND_TX] = SECOND_HEX
            headers[800_000] = GENESIS_HEADER
        }
        manager.start(second, state)
        val events = eventsUntilResynced(manager)

        assertEquals(listOf(ChainEvent.AddressChanged(SH_A0, "h1")), events)
        assertEquals(1, second.count("history:$SH_A0"))
        assertEquals(1, second.count("subscribe-headers"))
        assertEquals(400L, manager.snapshot.balance.unconfirmedSatoshis)
        assertEquals("h1", store.persisted[SH_A0])
        manager.close()
    }

    @Test
    fun `updateSnapshot adopts a rescan's snapshot and forgets the per-address balances`() {
        val store = FakeStore(mapOf(SH_A0 to "h0", SH_A1 to "h1"))
        val session = FakeSession().apply {
            subscribeAnswers[SH_A0] = "h0"
            subscribeAnswers[SH_A1] = "h1"
            histories[SH_A0] = listOf(HistoryEntry(FUNDING_TX, 800_000))
            histories[SH_A1] = listOf(HistoryEntry(FUNDING_TX, 800_000))
            balances[SH_A0] = ScripthashBalance(confirmedSatoshis = 1_000, unconfirmedSatoshis = 0)
            balances[SH_A1] = ScripthashBalance(confirmedSatoshis = 2_000, unconfirmedSatoshis = 0)
            headers[800_000] = GENESIS_HEADER
        }
        val manager = SubscriptionManager(store)
        val state = twoUsedSnapshot()
        manager.start(session, state)
        awaitResynced(manager)

        session.notifyScripthash(SH_A0, "a")
        awaitResynced(manager)
        assertEquals(1, session.count("balance:$SH_A1"))

        // A rescan has replaced the snapshot: its numbers are authoritative, and
        // the per-address answers this manager holds can no longer be trusted
        // against them, so they are dropped rather than merged.
        manager.updateSnapshot(state.copy(balance = ScripthashBalance(confirmedSatoshis = 7_000, unconfirmedSatoshis = 0)))
        assertEquals(7_000L, manager.snapshot.balance.confirmedSatoshis)

        session.notifyScripthash(SH_A1, "b")
        awaitResynced(manager)

        assertEquals("the balance memory was dropped, so the sums are read again", 2, session.count("balance:$SH_A0"))
        assertEquals(3_000L, manager.snapshot.balance.confirmedSatoshis)
        manager.close()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L

        /** The mainnet genesis header, the one timestamp beyond argument (1231006505). */
        const val GENESIS_HEADER =
            "0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a29ab5f49ffff001d1dac2b7c"

        const val SPK_A = "0014abababababababababababababababababababab"
        const val SPK_B = "0014cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"

        const val PARENT_TX = "1111111111111111111111111111111111111111111111111111111111111111"

        fun sh(chain: AddressChain, index: Int): String = "sh-${chain.name.lowercase()}-$index"

        /** A funding transaction paying [SPK_A] 1 000 satoshis. */
        val FUNDING: Pair<String, String> = TestTransactions.buildWithId(listOf(PARENT_TX to 0), listOf(1_000L to SPK_A))

        /** A second transaction, also paying [SPK_A]: the live payment. */
        val SECOND: Pair<String, String> = TestTransactions.buildWithId(listOf(PARENT_TX to 1), listOf(400L to SPK_A))

        val FUNDING_TX: String = FUNDING.first
        val FUNDING_HEX: String = FUNDING.second
        val SECOND_TX: String = SECOND.first
        val SECOND_HEX: String = SECOND.second
    }

    private val SH_A0 = sh(AddressChain.RECEIVE, 0)
    private val SH_A1 = sh(AddressChain.RECEIVE, 1)
    private val SH_C0 = sh(AddressChain.CHANGE, 0)
}
