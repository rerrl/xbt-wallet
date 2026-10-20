package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.chain.AccountSnapshot
import com.xbtwallet.app.chain.ChainEvent
import com.xbtwallet.app.chain.ChainTip
import com.xbtwallet.app.chain.ScripthashBalance
import com.xbtwallet.app.chain.TransactionRow
import com.xbtwallet.app.data.KeyValueStore
import com.xbtwallet.app.data.ServerSettings
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerType
import com.xbtwallet.app.data.SnapshotStore
import com.xbtwallet.app.electrum.ElectrumException
import com.xbtwallet.app.electrum.ElectrumProtocol
import com.xbtwallet.app.electrum.ElectrumServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * SignerChainState tests — the answers the signer screen can give, cold and warm.
 *
 * The scan itself is injected, so the rules under test are the screen's, not the
 * network's: *no server means the connect-a-server hint and no socket opened*,
 * *a scan that returns means ready*, *a scan that throws means an error with a
 * sentence a user can act on*, and — with a [SnapshotStore] — *a cached snapshot
 * means instant READY*, *a background refresh reports itself through
 * `isSyncing`/`syncFailed` and never blanks the screen*, and *a successful scan
 * is persisted*. `load()` being one-shot is pinned too, because the composable
 * that calls it may re-run for reasons that have nothing to do with this signer.
 */
class SignerChainStateTest {

    private val accountZpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    private fun signer(xpub: String = accountZpub) =
        Signer(id = "id-1", name = "Cold storage", type = SignerType.WatchOnlyXpub(xpub), createdAtEpochSeconds = 0L)

    /** The simplest possible [KeyValueStore]: a map that survives instances. */
    private class InMemoryStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    /** A settable in-memory stand-in for the saved endpoint; null host = none saved. */
    private class FakeServerSettings(var server: ElectrumServer? = null) : ServerSettings {
        override fun load(): ElectrumServer = server ?: ElectrumServer(
            host = "",
            port = ElectrumProtocol.SSL.defaultPort,
            protocol = ElectrumProtocol.SSL,
        )
    }

    @Test
    fun `with no server saved the screen asks for one and opens no connection`() {
        var scans = 0
        val state = SignerChainState(signer(), FakeServerSettings(null)) { _, _, _, _ ->
            scans++
            AccountSnapshot.EMPTY
        }

        runBlocking { state.load() }

        assertEquals(ChainPhase.NO_SERVER, state.phase)
        assertEquals("no scan may run without an endpoint", 0, scans)
        assertNull(state.errorMessage)
        assertNull(state.snapshot)
    }

    @Test
    fun `a completed scan lands ready with its snapshot`() {
        val snapshot = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(1_234, 5))
        val state = SignerChainState(signer(), FakeServerSettings(SERVER)) { server, _, cached, onProgress ->
            assertEquals("the saved endpoint is what is scanned", SERVER, server)
            assertNull("a first scan has nothing to build on", cached)
            onProgress(40)
            snapshot
        }

        runBlocking { state.load() }

        assertEquals(ChainPhase.READY, state.phase)
        assertEquals(snapshot, state.snapshot)
        assertEquals(40, state.scannedAddressCount)
        assertEquals(1_239L, state.snapshot?.satoshis)
        assertNull(state.errorMessage)
    }

    @Test
    fun `a failing scan reports the endpoint and the reason`() {
        val state = SignerChainState(
            signer(),
            FakeServerSettings(SERVER),
            retryDelaysSeconds = emptyList(),
        ) { _, _, _, _ ->
            throw ConnectException("Connection refused")
        }

        runBlocking { state.load() }

        assertEquals(ChainPhase.ERROR, state.phase)
        assertNull("a failed scan must not leave a stale balance on screen", state.snapshot)
        val message = state.errorMessage.orEmpty()
        assertTrue(message, message.contains("192.168.1.10:50002"))
        assertTrue(message, message.contains("refused"))
    }

    @Test
    fun `the loading phase is visible while the cold scan runs`() {
        var phaseDuringScan: ChainPhase? = null
        lateinit var state: SignerChainState
        state = SignerChainState(signer(), FakeServerSettings(SERVER)) { _, _, _, _ ->
            phaseDuringScan = state.phase
            AccountSnapshot.EMPTY
        }

        runBlocking { state.load() }

        // The screen has something to render from the first frame: loading.
        assertEquals(ChainPhase.LOADING, phaseDuringScan)
        assertEquals(ChainPhase.READY, state.phase)
    }

    @Test
    fun `load scans once and reload scans again`() {
        var scans = 0
        val state = SignerChainState(signer(), FakeServerSettings(SERVER)) { _, _, _, _ ->
            scans++
            AccountSnapshot.EMPTY
        }

        runBlocking {
            // The LaunchedEffect that drives this may re-run on recomposition;
            // only the first call may actually scan.
            state.load()
            state.load()
            state.load()
        }
        assertEquals(1, scans)

        runBlocking { state.reload() }
        assertEquals("reload is the Refresh button", 2, scans)
    }

    @Test
    fun `an unreadable xpub is reported as a key problem, not a network one`() {
        var scans = 0
        val state = SignerChainState(signer(xpub = "not-an-xpub"), FakeServerSettings(SERVER)) { _, _, _, _ ->
            scans++
            AccountSnapshot.EMPTY
        }

        runBlocking { state.load() }

        assertEquals(ChainPhase.ERROR, state.phase)
        assertEquals(0, scans)
        assertTrue(state.errorMessage.orEmpty().contains("key"))
        assertNull(state.scanner)
    }

    @Test
    fun `a cancelled scan stays a cancellation and is not shown as a failure`() {
        val state = SignerChainState(signer(), FakeServerSettings(SERVER)) { _, _, _, _ ->
            // What leaving the screen while the scan is in flight does.
            throw CancellationException("screen left")
        }

        assertThrows(CancellationException::class.java) { runBlocking { state.load() } }
        assertFalse(state.phase == ChainPhase.ERROR)
    }

    @Test
    fun `retry after a failure can succeed`() {
        var attempt = 0
        val state = SignerChainState(
            signer(),
            FakeServerSettings(SERVER),
            // No automatic retry here: this test is about the *user's* retry
            // (reload) working after the cold path has given up.
            retryDelaysSeconds = emptyList(),
        ) { _, _, _, _ ->
            attempt++
            if (attempt == 1) throw ConnectException("Connection refused") else AccountSnapshot.EMPTY
        }

        runBlocking { state.load() }
        assertEquals(ChainPhase.ERROR, state.phase)

        runBlocking { state.reload() }
        assertEquals(ChainPhase.READY, state.phase)
        assertNull(state.errorMessage)
    }

    // --- transient-failure retries ------------------------------------------

    @Test
    fun `a cold scan retries a transient failure and lands ready without ever showing an error`() {
        var scans = 0
        val phasesDuringScan = mutableListOf<ChainPhase>()
        val expected = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(7, 0))
        lateinit var state: SignerChainState
        state = SignerChainState(
            signer(),
            FakeServerSettings(SERVER),
            retryDelaysSeconds = listOf(0L, 0L),
        ) { _, _, _, _ ->
            scans++
            phasesDuringScan += state.phase
            if (scans < 3) throw ConnectException("Connection refused") else expected
        }

        runBlocking { state.load() }

        assertEquals("two failures then a success is three attempts", 3, scans)
        assertEquals(ChainPhase.READY, state.phase)
        assertEquals(expected, state.snapshot)
        assertNull("a retried scan must never flash an error", state.errorMessage)
        assertEquals(
            "the screen stays on loading for every attempt",
            listOf(ChainPhase.LOADING, ChainPhase.LOADING, ChainPhase.LOADING),
            phasesDuringScan,
        )
    }

    @Test
    fun `a cold scan that keeps failing is an error only once the retry budget is spent`() {
        var scans = 0
        val state = SignerChainState(
            signer(),
            FakeServerSettings(SERVER),
            retryDelaysSeconds = listOf(0L, 0L),
        ) { _, _, _, _ ->
            scans++
            throw SocketTimeoutException("read timed out")
        }

        runBlocking { state.load() }

        assertEquals("one attempt plus one per retry delay", 3, scans)
        assertEquals(ChainPhase.ERROR, state.phase)
        assertNull("a cold failure leaves no snapshot", state.snapshot)
        assertTrue(
            state.errorMessage.orEmpty(),
            state.errorMessage.orEmpty().contains("did not answer in time"),
        )
    }

    @Test
    fun `a protocol error is not retried`() {
        var scans = 0
        val state = SignerChainState(
            signer(),
            FakeServerSettings(SERVER),
            retryDelaysSeconds = listOf(0L, 0L),
        ) { _, _, _, _ ->
            scans++
            throw ElectrumException("'blockchain.scripthash.listunspent' returned an error: bad request")
        }

        runBlocking { state.load() }

        assertEquals("an ElectrumException is an answer, not a transport blip", 1, scans)
        assertEquals(ChainPhase.ERROR, state.phase)
        assertTrue(state.errorMessage.orEmpty().contains("192.168.1.10:50002"))
    }

    @Test
    fun `a cancellation during the retry backoff is rethrown and leaves the state re-startable`() {
        var attempts = 0
        val state = SignerChainState(
            signer(),
            FakeServerSettings(SERVER),
            // Long enough that the first retry is still waiting when the scan is
            // cancelled; the short timeout below provides that cancellation.
            retryDelaysSeconds = listOf(60L),
        ) { _, _, _, _ ->
            attempts++
            if (attempts == 1) throw ConnectException("Connection refused") else AccountSnapshot.EMPTY
        }

        assertThrows(CancellationException::class.java) {
            runBlocking { withTimeout(50) { state.load() } }
        }

        assertEquals("the failed attempt must not have been retried after the cancel", 1, attempts)
        assertEquals("a cancelled scan must not sit on LOADING", ChainPhase.IDLE, state.phase)
        assertFalse(state.isSyncing)

        runBlocking { state.reload() }

        assertEquals(ChainPhase.READY, state.phase)
        assertEquals("the state must still be able to run a real scan", 2, attempts)
    }

    @Test
    fun `a warm refresh retries transient failures and only fails once the budget is spent`() {
        val snapshotStore = seededStore(ScripthashBalance(5_000, 0))
        var scans = 0
        var snapshotDuringScan: AccountSnapshot? = null
        lateinit var state: SignerChainState
        state = SignerChainState(
            signer(),
            FakeServerSettings(SERVER),
            scan = { _, _, _, _ ->
                scans++
                snapshotDuringScan = state.snapshot
                throw SocketTimeoutException("read timed out")
            },
            snapshotStore = snapshotStore,
            retryDelaysSeconds = listOf(0L, 0L),
        )

        runBlocking { state.load() }

        assertEquals(3, scans)
        assertEquals("a failed refresh must not blank the screen", ChainPhase.READY, state.phase)
        assertEquals(
            "the cached snapshot stays on screen for every attempt",
            5_000L,
            snapshotDuringScan?.satoshis,
        )
        assertEquals(5_000L, state.snapshot?.satoshis)
        assertTrue("spending the retry budget is what raises the failure", state.syncFailed)
        assertFalse(state.isSyncing)
    }

    @Test
    fun `a warm refresh that recovers on a retry publishes the fresh snapshot`() {
        val snapshotStore = seededStore(ScripthashBalance(5_000, 0))
        val fresh = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(6_000, 0))
        var attempts = 0
        val state = SignerChainState(
            signer(),
            FakeServerSettings(SERVER),
            scan = { _, _, _, _ ->
                attempts++
                if (attempts < 3) throw ConnectException("Connection refused") else fresh
            },
            snapshotStore = snapshotStore,
            retryDelaysSeconds = listOf(0L, 0L),
        )

        runBlocking { state.load() }

        assertEquals(3, attempts)
        assertEquals(fresh, state.snapshot)
        assertFalse("a refresh that recovered is not a failure", state.syncFailed)
        assertFalse(state.isSyncing)
        assertNull(state.errorMessage)
    }

    // --- the warm path: a persisted snapshot --------------------------------

    @Test
    fun `a populated store seeds READY before anything is scanned`() {
        val backing = InMemoryStore()
        val snapshotStore = SnapshotStore(backing, now = { 111L })
        val cached = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(5_000, 0))
        snapshotStore.save("id-1", cached)

        val state = SignerChainState(
            signer(),
            FakeServerSettings(SERVER),
            scan = { _, _, _, _ -> throw AssertionError("constructing the state must not scan") },
            snapshotStore = snapshotStore,
        )

        // No load() call: the seeded state is already ready, so the screen can
        // render the balance on its very first frame.
        assertEquals(ChainPhase.READY, state.phase)
        assertEquals(cached, state.snapshot)
    }

    @Test
    fun `a seeded state refreshes in the background, passing the cache to the scan`() {
        val snapshotStore = seededStore(ScripthashBalance(5_000, 0))
        val fresh = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(6_000, 0))
        var cachedSeen: AccountSnapshot? = null
        var syncingDuringScan: Boolean? = null
        lateinit var state: SignerChainState
        state = SignerChainState(signer(), FakeServerSettings(SERVER), scan = { _, _, cached, _ ->
            cachedSeen = cached
            syncingDuringScan = state.isSyncing
            fresh
        }, snapshotStore = snapshotStore)

        assertFalse("nothing is syncing before load()", state.isSyncing)
        runBlocking { state.load() }

        assertEquals("the seeded snapshot must be the cache basis", 5_000L, cachedSeen?.satoshis)
        assertTrue("the screen must say Syncing… while the refresh runs", syncingDuringScan == true)
        assertEquals(fresh, state.snapshot)
        assertFalse(state.isSyncing)
        assertFalse(state.syncFailed)
    }

    @Test
    fun `a failed background refresh keeps the cached snapshot and offers a retry`() {
        val cached = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(5_000, 0))
        val state = SignerChainState(
            signer(),
            FakeServerSettings(SERVER),
            scan = { _, _, _, _ -> throw ConnectException("Connection refused") },
            snapshotStore = seededStore(cached.balance),
            retryDelaysSeconds = emptyList(),
        )

        runBlocking { state.load() }

        assertEquals("a failed refresh must not blank the screen", ChainPhase.READY, state.phase)
        assertEquals(cached, state.snapshot)
        assertTrue(state.syncFailed)
        assertFalse(state.isSyncing)
        // The error sentence is for the cold path; here the numbers stay and the
        // screen shows its short "couldn't refresh" line instead.
        assertNull(state.errorMessage)
    }

    @Test
    fun `a successful scan is persisted for the next visit`() {
        val backing = InMemoryStore()
        val snapshotStore = SnapshotStore(backing, now = { 777L })
        val fresh = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(9_000, 1))
        val state = SignerChainState(signer(), FakeServerSettings(SERVER), scan = { _, _, _, _ -> fresh }, snapshotStore = snapshotStore)

        runBlocking { state.load() }

        val stored = snapshotStore.load("id-1")
        assertEquals(fresh, stored?.snapshot)
        assertEquals(777L, stored?.scannedAtEpochSeconds)
    }

    @Test
    fun `a warm refresh persists its fresh result over the old one`() {
        val snapshotStore = seededStore(ScripthashBalance(5_000, 0))
        val fresh = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(6_000, 0))
        val state = SignerChainState(signer(), FakeServerSettings(SERVER), scan = { _, _, _, _ -> fresh }, snapshotStore = snapshotStore)

        runBlocking { state.load() }

        assertEquals(fresh, snapshotStore.load("id-1")?.snapshot)
    }

    @Test
    fun `a seeded state with no server saved keeps its cache instead of blanking`() {
        val snapshotStore = seededStore(ScripthashBalance(5_000, 0))
        var scans = 0
        val state = SignerChainState(signer(), FakeServerSettings(null), scan = { _, _, _, _ ->
            scans++
            AccountSnapshot.EMPTY
        }, snapshotStore = snapshotStore)

        runBlocking { state.load() }

        // There is nothing to refresh against, but the cached screen stays.
        assertEquals(ChainPhase.READY, state.phase)
        assertEquals(5_000L, state.snapshot?.satoshis)
        assertEquals(0, scans)
    }

    // --- cancellation hardening ---------------------------------------------

    @Test
    fun `a cancelled cold scan leaves the state re-startable`() {
        // Leaving the signer screen while the cold scan is in flight cancels it.
        // The next visit must be able to scan again rather than sit on LOADING
        // forever (the one-visit flag has to be un-set by the cancellation).
        var attempt = 0
        val state = SignerChainState(signer(), FakeServerSettings(SERVER)) { _, _, _, _ ->
            attempt++
            if (attempt == 1) throw CancellationException("screen left") else AccountSnapshot.EMPTY
        }

        assertThrows(CancellationException::class.java) { runBlocking { state.load() } }
        assertEquals("a cancelled scan must not sit on LOADING", ChainPhase.IDLE, state.phase)

        runBlocking { state.load() }

        assertEquals(ChainPhase.READY, state.phase)
        assertEquals("the later visit must actually scan", 2, attempt)
    }

    @Test
    fun `a cancelled warm refresh keeps the cache and can re-sync`() {
        val snapshotStore = seededStore(ScripthashBalance(5_000, 0))
        val fresh = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(6_000, 0))
        var attempts = 0
        val state = SignerChainState(signer(), FakeServerSettings(SERVER), scan = { _, _, _, _ ->
            attempts++
            if (attempts == 1) throw CancellationException("screen left") else fresh
        }, snapshotStore = snapshotStore)

        assertThrows(CancellationException::class.java) { runBlocking { state.load() } }

        assertEquals("the cached snapshot must survive a cancellation", 5_000L, state.snapshot?.satoshis)
        assertEquals(ChainPhase.READY, state.phase)
        assertFalse("a cancelled refresh is not still syncing", state.isSyncing)
        assertFalse(state.syncFailed)

        runBlocking { state.load() }

        assertEquals("a later visit must re-sync", fresh, state.snapshot)
        assertEquals(2, attempts)
    }

    // --- staleness: sync only when in flight, or stale ----------------------

    @Test
    fun `a second load while a scan is in flight starts no second scan`() {
        // The user re-enters the signer while its scan is still running (the
        // whole point of the app-scoped store): the running scan must not be
        // restarted, and a second scan must not be queued behind it.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var scans = 0
        val state = SignerChainState(signer(), FakeServerSettings(SERVER)) { _, _, _, _ ->
            scans++
            entered.countDown()
            release.await()
            AccountSnapshot.EMPTY
        }

        val first = thread { runBlocking { state.load() } }
        assertTrue("the scan must have started", entered.await(5, TimeUnit.SECONDS))

        runBlocking { state.load() }
        assertEquals("an in-flight scan must not be restarted", 1, scans)

        release.countDown()
        first.join()
        assertEquals(1, scans)
        assertEquals(ChainPhase.READY, state.phase)
    }

    @Test
    fun `a completed scan is not repeated while it is fresh`() {
        var scans = 0
        val state = SignerChainState(signer(), FakeServerSettings(SERVER), now = { 1_000L }) { _, _, _, _ ->
            scans++
            AccountSnapshot.EMPTY
        }

        runBlocking { state.load() }
        assertEquals(1, scans)

        // Re-entering the screen straight away: the sync is younger than
        // STALE_AFTER_SECONDS, so nothing is rescanned.
        runBlocking { state.load() }
        assertEquals("a fresh sync must not be repeated", 1, scans)
    }

    @Test
    fun `a completed scan is repeated once it is stale`() {
        var clock = 1_000L
        var scans = 0
        val state = SignerChainState(signer(), FakeServerSettings(SERVER), now = { clock }) { _, _, _, _ ->
            scans++
            AccountSnapshot.EMPTY
        }

        runBlocking { state.load() }
        assertEquals(1, scans)

        clock += SignerChainState.STALE_AFTER_SECONDS + 1
        runBlocking { state.load() }
        assertEquals("a stale sync must be repeated", 2, scans)
    }

    @Test
    fun `a seeded cache younger than the staleness window is not rescanned`() {
        val clock = 10_000L
        val snapshotStore = SnapshotStore(InMemoryStore(), now = { clock - 10 })
        snapshotStore.save("id-1", AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(5_000, 0)))
        var scans = 0
        val state = SignerChainState(
            signer(),
            FakeServerSettings(SERVER),
            scan = { _, _, _, _ ->
                scans++
                AccountSnapshot.EMPTY
            },
            snapshotStore = snapshotStore,
            now = { clock },
        )

        runBlocking { state.load() }

        assertEquals("a 10-second-old cache must not be rescanned", 0, scans)
        assertEquals(5_000L, state.snapshot?.satoshis)
    }

    @Test
    fun `a seeded cache older than the staleness window is rescanned`() {
        val clock = 10_000L
        val snapshotStore = SnapshotStore(InMemoryStore(), now = { clock - 300 })
        snapshotStore.save("id-1", AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(5_000, 0)))
        val fresh = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(6_000, 0))
        var scans = 0
        val state = SignerChainState(
            signer(),
            FakeServerSettings(SERVER),
            scan = { _, _, _, _ ->
                scans++
                fresh
            },
            snapshotStore = snapshotStore,
            now = { clock },
        )

        runBlocking { state.load() }

        assertEquals("a 300-second-old cache must be rescanned", 1, scans)
        assertEquals(fresh, state.snapshot)
    }

    @Test
    fun `reload forces a scan even while the last one is fresh`() {
        var scans = 0
        val state = SignerChainState(signer(), FakeServerSettings(SERVER), now = { 1_000L }) { _, _, _, _ ->
            scans++
            AccountSnapshot.EMPTY
        }

        runBlocking { state.load() }
        assertEquals(1, scans)

        runBlocking { state.reload() }
        assertEquals("Refresh/Retry always rescans", 2, scans)
    }

    // --- the live subscription ----------------------------------------------
    //
    // These drive the seam rather than a socket: a fake engine whose events and
    // snapshot the test sets, and a fake session that only records its own
    // closing. What is pinned here is the wiring the card specifies — a push
    // reaches the screen, the rescan and the manager never both fetch the same
    // address, leaving the screen closes the connection, and a dead connection
    // falls back to the pull path without an error.

    @Test
    fun `a push updates the balance, the transactions and the confirmations the screen shows`() {
        val engine = FakeSubscriptionEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cached = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(5_000, 0))
        val mempool = cached.copy(
            balance = ScripthashBalance(5_000, 400),
            transactions = listOf(TransactionRow(txHash = "aa", height = 0, confirmations = 0, netSatoshis = 400)),
        )
        val state = stateWithSubscriptions(
            engine = engine,
            scope = scope,
            snapshotStore = seededStore(cached),
            scan = { _, _, _, _ -> cached },
        )

        // The seeded cache is what the screen opens on, before anything is live.
        assertEquals(5_000L, state.snapshot?.satoshis)

        state.startSubscriptions()
        awaitUntil("the manager to be handed the connection") { engine.startedWith != null }
        assertEquals("the manager is seeded with what is on screen", cached, engine.startedWith?.second)

        // A payment lands in the mempool: the manager's snapshot moves, and the
        // change arrives as a push.
        engine.current = mempool
        engine.emit(ChainEvent.AddressChanged("sh-0", "status-1"))
        awaitUntil("the mempool row on screen") { state.snapshot?.transactions?.size == 1 }

        assertEquals("an unconfirmed row arrives as an unconfirmed row", 0, state.snapshot?.transactions?.single()?.confirmations)
        assertEquals(5_400L, state.snapshot?.satoshis)
        assertEquals(400L, state.snapshot?.balance?.unconfirmedSatoshis)

        // The next block mines it: the conf count ticks up on the NewBlock push.
        engine.current = mempool.copy(
            balance = ScripthashBalance(5_400, 0),
            transactions = listOf(TransactionRow(txHash = "aa", height = 800_001, confirmations = 1, netSatoshis = 400)),
        )
        engine.emit(ChainEvent.NewBlock(ChainTip(800_001, "hex")))
        awaitUntil("the confirmation to tick") { state.snapshot?.transactions?.single()?.confirmations == 1 }

        assertEquals(5_400L, state.snapshot?.satoshis)
        scope.cancel()
    }

    @Test
    fun `a completed rescan is handed to the manager so one address is never refreshed twice`() {
        val engine = FakeSubscriptionEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cached = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(5_000, 0))
        val fresh = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(7_000, 0))
        var syncingDuringScan: Boolean? = null
        lateinit var state: SignerChainState
        state = stateWithSubscriptions(
            engine = engine,
            scope = scope,
            snapshotStore = seededStore(cached),
            scan = { _, _, _, _ ->
                syncingDuringScan = state.isSyncing
                fresh
            },
        )

        state.startSubscriptions()
        awaitUntil("the manager to be handed the connection") { engine.startedWith != null }

        runBlocking { state.load() }

        assertEquals("the rescan still runs and still lands", fresh, state.snapshot)
        assertTrue("the rescan still reports itself through isSyncing", syncingDuringScan == true)
        assertFalse("subscriptions are not a sync the user has to wait for", state.isSyncing)
        // The manager adopts the rescan's snapshot: it is the fresher writer, and
        // adopting it is what stops a notification for an address the rescan just
        // covered from being fetched a second time (see the manager's
        // updateSnapshot).
        awaitUntil("the rescan to reach the manager") { engine.adopted.contains(fresh) }
        assertEquals(fresh, engine.current)
        scope.cancel()
    }

    @Test
    fun `leaving the screen closes the connection and the manager`() {
        val engine = FakeSubscriptionEngine()
        val session = FakeSubscriptionSession()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cached = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(5_000, 0))
        val state = stateWithSubscriptions(
            engine = engine,
            session = session,
            scope = scope,
            snapshotStore = seededStore(cached),
            scan = { _, _, _, _ -> cached },
        )

        state.startSubscriptions()
        awaitUntil("the manager to be handed the connection") { engine.startedWith != null }

        state.stopSubscriptions()

        awaitUntil("the manager to close") { engine.isClosed }
        assertTrue("the connection is this state's to close", session.isClosed)
        assertEquals("and closing twice is a no-op", 1, engine.closeCount)

        // Nothing that arrives after the screen is gone may move the state.
        engine.emit(ChainEvent.Resynced)
        Thread.sleep(50)
        assertEquals(5_000L, state.snapshot?.satoshis)

        // The screen closing is idempotent too.
        state.stopSubscriptions()
        assertEquals(1, engine.closeCount)
        scope.cancel()
    }

    @Test
    fun `a dead connection falls back to the pull path silently and the rescan still works`() {
        val engine = FakeSubscriptionEngine()
        val session = FakeSubscriptionSession()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cached = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(5_000, 0))
        val fresh = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(6_000, 0))
        var scans = 0
        val state = stateWithSubscriptions(
            engine = engine,
            session = session,
            scope = scope,
            snapshotStore = seededStore(cached),
            scan = { _, _, _, _ ->
                scans++
                fresh
            },
        )

        state.startSubscriptions()
        awaitUntil("the manager to be handed the connection") { engine.startedWith != null }

        engine.emit(ChainEvent.SessionLost(IOException("the peer closed the connection")))

        // The fallback is silent: no error banner, no failed-sync line, and the
        // cached numbers stay exactly where they were.
        awaitUntil("the manager to be released") { engine.isClosed }
        assertNull(state.errorMessage)
        assertFalse(state.syncFailed)
        assertEquals(5_000L, state.snapshot?.satoshis)

        // And the existing refresh path is untouched by any of it.
        runBlocking { state.reload() }

        assertEquals(1, scans)
        assertEquals(fresh, state.snapshot)
        assertEquals(ChainPhase.READY, state.phase)
        assertFalse(state.syncFailed)
        scope.cancel()
    }

    @Test
    fun `opening the screen twice opens one connection`() {
        val engine = FakeSubscriptionEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opens = AtomicInteger(0)
        val cached = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(5_000, 0))
        val state = stateWithSubscriptions(
            engine = engine,
            scope = scope,
            snapshotStore = seededStore(cached),
            scan = { _, _, _, _ -> cached },
            onOpen = { opens.incrementAndGet() },
        )

        state.startSubscriptions()
        awaitUntil("the connection to open") { engine.startedWith != null }
        state.startSubscriptions()
        Thread.sleep(50)

        assertEquals("a recomposition is not a second connection", 1, opens.get())
        scope.cancel()
    }

    @Test
    fun `a cold screen starts its subscription once the first scan lands`() {
        val engine = FakeSubscriptionEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opens = AtomicInteger(0)
        val fresh = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(1_000, 0))
        val state = stateWithSubscriptions(
            engine = engine,
            scope = scope,
            snapshotStore = null,
            scan = { _, _, _, _ -> fresh },
            onOpen = { opens.incrementAndGet() },
        )

        // Nothing cached: there is no account to subscribe to yet, so asking must
        // not open a connection to nothing.
        state.startSubscriptions()
        Thread.sleep(50)
        assertEquals(0, opens.get())

        runBlocking { state.load() }

        awaitUntil("the subscription to open once the scan landed") { engine.startedWith != null }
        assertEquals("the manager is seeded with the fresh scan", fresh, engine.startedWith?.second)
        assertEquals(1, opens.get())
        scope.cancel()
    }

    @Test
    fun `a scan that lands after the screen closed opens no connection`() {
        val engine = FakeSubscriptionEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opens = AtomicInteger(0)
        val fresh = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(1_000, 0))
        val state = stateWithSubscriptions(
            engine = engine,
            scope = scope,
            snapshotStore = null,
            scan = { _, _, _, _ -> fresh },
            onOpen = { opens.incrementAndGet() },
        )

        // The screen opens and closes before the (cold) scan has answered — the
        // scan keeps running in the app scope, and must not resurrect a
        // connection for a screen the user has left.
        state.startSubscriptions()
        state.stopSubscriptions()
        runBlocking { state.load() }
        Thread.sleep(50)

        assertEquals(0, opens.get())
        assertNull(engine.startedWith)
        assertEquals(ChainPhase.READY, state.phase)
        scope.cancel()
    }

    @Test
    fun `no saved endpoint means no subscription and nothing else changes`() {
        val engine = FakeSubscriptionEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opens = AtomicInteger(0)
        val state = stateWithSubscriptions(
            engine = engine,
            scope = scope,
            snapshotStore = null,
            server = null,
            scan = { _, _, _, _ -> AccountSnapshot.EMPTY },
            onOpen = { opens.incrementAndGet() },
        )

        state.startSubscriptions()
        runBlocking { state.load() }
        Thread.sleep(50)

        assertEquals(ChainPhase.NO_SERVER, state.phase)
        assertEquals(0, opens.get())
        assertNull(state.errorMessage)
        scope.cancel()
    }

    @Test
    fun `a connection that cannot be opened leaves the pull path exactly as it was`() {
        val engine = FakeSubscriptionEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fresh = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(1_000, 0))
        val state = stateWithSubscriptions(
            engine = engine,
            scope = scope,
            snapshotStore = null,
            scan = { _, _, _, _ -> fresh },
            onOpen = { throw ConnectException("Connection refused") },
        )

        state.startSubscriptions()
        runBlocking { state.load() }

        // A cold scan that succeeded is READY, with no failure and no manager:
        // exactly master's behaviour.
        assertEquals(ChainPhase.READY, state.phase)
        assertEquals(fresh, state.snapshot)
        assertNull(state.errorMessage)
        assertFalse(state.syncFailed)
        assertNull("no engine is built when there is no connection", engine.startedWith)
        scope.cancel()
    }

    @Test
    fun `a state with no subscription scope never subscribes`() {
        // The default for every caller that only ever pulls — including a test —
        // and the reason adding the live path cannot change their behaviour.
        val state = SignerChainState(signer(), FakeServerSettings(SERVER), retryDelaysSeconds = emptyList()) { _, _, _, _ ->
            AccountSnapshot.EMPTY
        }

        state.startSubscriptions()
        runBlocking { state.load() }

        assertEquals(ChainPhase.READY, state.phase)
        assertFalse(state.isSyncing)
    }

    /**
     * A state wired for the live path: a fake engine, a fake connection, and a
     * scope the test cancels at the end.
     */
    private fun stateWithSubscriptions(
        engine: FakeSubscriptionEngine,
        scope: CoroutineScope,
        snapshotStore: SnapshotStore?,
        scan: ChainScan,
        session: FakeSubscriptionSession = FakeSubscriptionSession(),
        server: ElectrumServer? = SERVER,
        onOpen: () -> Unit = {},
    ) = SignerChainState(
        signer = signer(),
        serverSettings = FakeServerSettings(server),
        snapshotStore = snapshotStore,
        now = { 1_000L },
        retryDelaysSeconds = emptyList(),
        subscriptionScope = scope,
        openSubscription = {
            onOpen()
            session
        },
        newSubscriptionEngine = { engine },
        scan = scan,
    )

    /** A store seeded with a whole snapshot, old enough that `load` rescans it. */
    private fun seededStore(snapshot: AccountSnapshot): SnapshotStore =
        SnapshotStore(InMemoryStore(), now = { 1L }).apply { save("id-1", snapshot) }

    private fun seededStore(balance: ScripthashBalance): SnapshotStore {
        val store = SnapshotStore(InMemoryStore(), now = { 1L })
        store.save("id-1", AccountSnapshot(emptyList(), emptyList(), emptyList(), balance))
        return store
    }

    private companion object {
        val SERVER = ElectrumServer(host = "192.168.1.10", port = 50002, protocol = ElectrumProtocol.SSL)
    }
}
