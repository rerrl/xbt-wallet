package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.chain.AccountSnapshot
import com.xbtwallet.app.chain.ChainEvent
import com.xbtwallet.app.chain.ChainSubscriptionSession
import com.xbtwallet.app.chain.ChainTip
import com.xbtwallet.app.chain.ChainUtxo
import com.xbtwallet.app.chain.HistoryEntry
import com.xbtwallet.app.chain.ScripthashBalance
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The doubles the live-subscription tests are driven with, shared by
 * `SignerChainStateTest` and `SignerChainStateStoreTest`.
 *
 * They sit on the seam rather than on the socket on purpose: what is under test
 * here is the *wiring* — when a connection is opened and closed, which snapshot
 * the screen ends up showing, and what the manager is told about a rescan — not
 * the protocol. The protocol has its own tests in `electrum/` and `chain/`,
 * driven against a mock server and a fake session respectively.
 */

/**
 * A [SignerSubscriptionEngine] whose events the test pushes itself and whose
 * snapshot it sets directly — standing in for the manager's worker thread.
 *
 * A second copy of the channel's contents would be a bug, not a feature: this
 * one is the same unbounded buffer the real manager uses, so an event pushed
 * before the state starts collecting is still delivered.
 */
internal class FakeSubscriptionEngine : SignerSubscriptionEngine {

    private val channel = Channel<ChainEvent>(Channel.UNLIMITED)

    override val events: Flow<ChainEvent> = channel.receiveAsFlow()

    /** The account state the manager would currently report. */
    @Volatile
    var current: AccountSnapshot = AccountSnapshot.EMPTY

    override val snapshot: AccountSnapshot get() = current

    /** The session and seed snapshot [start] was called with, or null if it never was. */
    @Volatile
    var startedWith: Pair<ChainSubscriptionSession, AccountSnapshot>? = null

    /** Every snapshot handed to [updateSnapshot], in order — the rescan hand-offs. */
    val adopted: MutableList<AccountSnapshot> = CopyOnWriteArrayList()

    /**
     * How many times this engine was actually closed — the real manager's
     * `close()` is idempotent, and this counts the first call only, so a test can
     * assert that navigating away closes exactly once even though both the state
     * and the job's own cleanup call it.
     */
    private val closes = AtomicInteger(0)

    val closeCount: Int get() = closes.get()

    @Volatile
    private var closedFlag = false

    val isClosed: Boolean get() = closedFlag

    override fun start(session: ChainSubscriptionSession, snapshot: AccountSnapshot) {
        startedWith = session to snapshot
    }

    override fun updateSnapshot(snapshot: AccountSnapshot) {
        adopted += snapshot
        current = snapshot
    }

    override fun close() {
        // Closing the channel is what ends the consumer's `collect`, exactly as
        // the real manager's `close()` does — and `close()` on a channel tells
        // the caller whether this was the call that closed it, which is how the
        // idempotency of the real manager is mirrored here.
        if (channel.close()) {
            closedFlag = true
            closes.incrementAndGet()
        }
    }

    /** Pushes one event at the state, from whichever thread the test is on. */
    fun emit(event: ChainEvent) {
        channel.trySend(event)
    }
}

/**
 * A [ChainSubscriptionSession] that answers nothing and records only whether it
 * was closed: the state hands it to the manager and never queries it itself, so
 * a test that asserts on its answers would be testing the wrong layer.
 */
internal class FakeSubscriptionSession : ChainSubscriptionSession {

    @Volatile
    override var onNotification: (method: String, params: Any?) -> Unit = { _, _ -> }

    @Volatile
    override var onClosed: (error: Throwable) -> Unit = { }

    @Volatile
    private var closedFlag = false

    override val isClosed: Boolean get() = closedFlag

    override fun close() {
        closedFlag = true
    }

    override fun subscribeScripthash(scripthash: String): String? = null

    override fun subscribeHeaders(): ChainTip = ChainTip(height = 0, hex = "")

    override fun getHistory(scripthash: String): List<HistoryEntry> = emptyList()

    override fun getBalance(scripthash: String): ScripthashBalance = ScripthashBalance.ZERO

    override fun listUnspent(scripthash: String): List<ChainUtxo> = emptyList()

    override fun getTransaction(txHash: String): String = ""

    override fun getTipHeight(): Int = 0

    override fun blockHeader(height: Int): String = ""
}

/**
 * Waits for [condition] to hold, polling, and fails with [what] if it never
 * does.
 *
 * There is no `kotlinx-coroutines-test` in this project (the same constraint the
 * chain-layer tests work under), so the asynchronous parts of these tests are
 * awaited with a real deadline instead of virtual time. Everything a test
 * asserts on has therefore already happened when it asserts it.
 */
internal fun awaitUntil(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(5)
    }
    throw AssertionError("timed out waiting for $what")
}
