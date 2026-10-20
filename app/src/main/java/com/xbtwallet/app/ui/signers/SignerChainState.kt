package com.xbtwallet.app.ui.signers

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xbtwallet.app.chain.AccountScanner
import com.xbtwallet.app.chain.AccountSnapshot
import com.xbtwallet.app.chain.ChainEvent
import com.xbtwallet.app.chain.ChainSubscriptionSession
import com.xbtwallet.app.chain.ChainTip
import com.xbtwallet.app.chain.ChainUtxo
import com.xbtwallet.app.chain.HistoryEntry
import com.xbtwallet.app.chain.ReconnectPolicy
import com.xbtwallet.app.chain.ReconnectingSubscription
import com.xbtwallet.app.chain.ScripthashBalance
import com.xbtwallet.app.chain.SubscriptionEngine
import com.xbtwallet.app.chain.SubscriptionManager
import com.xbtwallet.app.chain.SubscriptionStateStore
import com.xbtwallet.app.crypto.ExtendedPublicKey
import com.xbtwallet.app.data.ServerSettings
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerType
import com.xbtwallet.app.data.SnapshotStore
import com.xbtwallet.app.electrum.ElectrumClient
import com.xbtwallet.app.electrum.ElectrumException
import com.xbtwallet.app.electrum.ElectrumServer
import com.xbtwallet.app.electrum.SubscriptionSession
import com.xbtwallet.app.electrum.socketErrnoOf
import com.xbtwallet.app.electrum.socketErrnoReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * What the signer screen is currently showing, and where it is in the scan.
 *
 * The screen has states that matter to a user: *here is what your server said*
 * ([READY], which is now also the state a cached snapshot starts in), *we are
 * asking your server* ([LOADING], the cold scan with nothing to show yet), and
 * *we could not ask* — either because no server is configured ([NO_SERVER], the
 * "connect a server" case) or because the one that is configured did not answer
 * ([ERROR]).
 *
 * **Two ways to be READY.** With a [SnapshotStore] the state is seeded from the
 * signer's last saved scan the moment it is constructed, so opening a signer a
 * second time renders the balance, UTXOs, transactions and addresses from disk
 * with no wait at all. That cached READY is then refreshed in the background:
 * [isSyncing] is true while the rescan runs, and a refresh that fails keeps the
 * cached data on screen ([syncFailed] offers a Retry) instead of blanking it.
 * Without a seed, [IDLE] is the brief instant before a cold scan starts, and it
 * renders as loading so there is never a frame showing a made-up zero balance.
 *
 * **Threading.** [load] and [reload] are `suspend`; the scan itself runs on the
 * IO dispatcher inside [ElectrumClient.withChainSession]. Nothing here touches
 * the UI thread.
 *
 * **One instance per signer, for the app's lifetime.** A state is not owned by
 * the screen that shows it: [SignerChainStateStore] keeps one instance per
 * signer id for as long as the process lives, so walking detail → UTXOs →
 * Addresses → Home → detail neither cancels a scan in flight nor starts a second
 * one. [load] is therefore "sync if stale or never synced, never interrupt an
 * in-flight sync": it does nothing while a scan is running, and nothing while the
 * last completed sync is younger than [STALE_AFTER_SECONDS]. [reload] is the
 * manual Refresh/Retry and always forces a scan.
 *
 * **Testability.** The scan is injected, so this class can be exercised on the
 * JVM — including the failure paths — with no server, no socket and no Android.
 *
 * **Transient failures retry invisibly.** A scan that dies with a *transport*
 * error ([java.io.IOException] and its subclasses — a read timeout, a refused
 * connection, a socket dropped when the screen slept) is retried before it is
 * reported, at the intervals in [retryDelaysSeconds]. Only when those are
 * exhausted does the cold path show [ChainPhase.ERROR] or the warm path raise
 * [syncFailed]: the common real-world case — the device slept mid-sync and the
 * socket went with it — heals itself instead of greeting the user with a
 * failure. A protocol *answer* ([ElectrumException]) is not transient and is not
 * retried, and a [CancellationException] still rethrows immediately.
 *
 * **Live updates are an enhancement, never a requirement.** While the signer's
 * own screen is open, [startSubscriptions] opens one long-lived connection and
 * hands it to a [SubscriptionManager], which pushes address changes into
 * [snapshot] as they happen; [stopSubscriptions] closes it on the way out. Every
 * step of that is fallible and *silent*: no endpoint, no scope, an unreadable
 * key, a dial that fails, a manager that throws, or a push connection that later
 * dies all leave the pull path above exactly as it is — a rescan, its retry
 * budget, and the Refresh/Retry button. A subscription that cannot be opened is
 * not an error state and must never be shown as one.
 *
 * **A dropped connection is reconnected, not given up on.** Tor circuits die more
 * often than direct connections, so a [ChainEvent.SessionLost] is not the end of
 * the live path: [ReconnectingSubscription] re-dials the same endpoint with
 * exponential backoff and jitter (2s, doubling, capped at 60s) and resubscribes —
 * and because subscribing *diffs the status hashes the server reports against the
 * ones last seen*, whatever happened while the connection was down is refreshed
 * through the same targeted-refresh path a live notification takes. The gap and
 * the push are one code path, with no polling. Only when the reconnect budget is
 * spent (six consecutive attempts at the default policy, the flapping-server
 * case included) does the screen fall back to the pull path, silently, and only
 * until the signer is opened again — which starts a fresh connection and a fresh
 * budget. Reopening the screen is the user's retry; nothing is ever shown about
 * any of it.
 *
 * **One writer per snapshot, and the rescan wins.** A completed scan is handed
 * to the manager through `updateSnapshot`, so a live refresh and a rescan cannot
 * clobber each other and a live change to an address the rescan just covered is
 * never fetched twice. See [publishScan].
 *
 * @param signer the signer being shown. Read once: the navigation layer is what
 *   notices a signer being deleted, and sends the user Home instead.
 * @param serverSettings read access to the saved Electrum endpoint.
 * @param snapshotStore the signer's persisted last scan, or null (as in tests,
 *   and any caller that wants the cold behaviour). When present it seeds
 *   [snapshot] and receives every completed scan.
 * @param now the read clock in epoch seconds, injectable so the staleness rule
 *   can be tested without waiting (mirrors [SnapshotStore]'s save clock).
 * @param retryDelaysSeconds how long to wait before each *retry* of a scan that
 *   failed with a transient transport error. One entry per extra attempt, so the
 *   default `listOf(3, 10)` retries a dead scan at +3s and again at +10s, and
 *   only a third failure is reported. Empty means "never retry", which is what
 *   most tests want (and what makes them instant).
 * @param subscriptionScope the scope [startSubscriptions] runs the live
 *   connection in, or null for "this state never subscribes" — which is what a
 *   test that is not about subscriptions wants, and what keeps a caller that
 *   only ever pulls behaving exactly as before.
 * @param openSubscription opens one long-lived connection to the saved
 *   endpoint. Defaults to a real one over the transport (Tor for `.onion`);
 *   injectable so the subscription lifecycle can be exercised with no socket.
 * @param newSubscriptionEngine builds the live-updates engine around a
 *   [SubscriptionStateStore]. Defaults to the real [SubscriptionManager];
 *   injectable so a test can drive the manager's events by hand.
 * @param reconnectPolicy the backoff schedule, attempt budget and stable-session
 *   rule the live connection is kept alive with. See [ReconnectPolicy]; defaults
 *   to the production one, and a test substitutes a budget with no waiting.
 * @param reconnectBackoff the wait between reconnect attempts. Defaults to a
 *   cancellable `delay` in [subscriptionScope]; injectable so a test can assert
 *   the reconnect schedule without spending it in real time. Cancelling the job
 *   (navigating away) still interrupts it when it *is* a `delay`.
 * @param scan one whole-account scan; defaults to a real one over the socket.
 *   Last so a test can pass it as a trailing lambda.
 */
@Stable
class SignerChainState(
    val signer: Signer,
    private val serverSettings: ServerSettings,
    private val snapshotStore: SnapshotStore? = null,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val retryDelaysSeconds: List<Long> = DEFAULT_RETRY_DELAYS_SECONDS,
    private val subscriptionScope: CoroutineScope? = null,
    private val openSubscription: SubscriptionOpener = ::openLiveSubscription,
    private val newSubscriptionEngine: SubscriptionEngineFactory = ::managerEngine,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val reconnectBackoff: suspend (Long) -> Unit = { delay(it) },
    private val scan: ChainScan = ::scanOverElectrum,
) {

    /** The signer's account xpub, or null if this is not a watch-only signer. */
    private val xpub: String? = (signer.type as? SignerType.WatchOnlyXpub)?.xpub

    /** Parsed account key, or null when the stored xpub cannot be read. */
    val accountKey: ExtendedPublicKey? = xpub?.let { runCatching { ExtendedPublicKey.parse(it) }.getOrNull() }

    /** The scanner for this account, or null when the key is unreadable. */
    val scanner: AccountScanner? = accountKey?.let { AccountScanner(it) }

    /** Where the screen is in its scan. */
    var phase by mutableStateOf(ChainPhase.IDLE)
        private set

    /** The last completed scan, or null while none is known. */
    var snapshot by mutableStateOf<AccountSnapshot?>(null)
        private set

    /** A human-readable reason, set only in [ChainPhase.ERROR]. */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /**
     * Addresses the server has answered about so far in the running scan.
     *
     * Bound, but visible: it is what turns an apparently frozen "Loading…" into
     * "checked 120 addresses".
     */
    var scannedAddressCount by mutableStateOf(0)
        private set

    /**
     * True while a background refresh of an already-shown snapshot is running.
     *
     * The screen renders a "Syncing…" line off this and keeps showing the cached
     * values underneath — a rescan must never take the data away to update it.
     */
    var isSyncing by mutableStateOf(false)
        private set

    /**
     * True when the last *background* refresh failed while a snapshot was on
     * screen. The snapshot is kept, and the screen offers a Retry.
     */
    var syncFailed by mutableStateOf(false)
        private set

    /**
     * True while this state's scan is in flight — either the cold scan that owns
     * the LOADING/READY/ERROR machine, or a background refresh reported through
     * [isSyncing]. This is what [SignerChainStateStore.sync] and [load] consult to
     * guarantee that one signer never runs two scans at once.
     */
    val scanInFlight: Boolean get() = phase == ChainPhase.LOADING || isSyncing

    /**
     * True from the instant [load] starts a scan until that scan leaves — success,
     * failure, or cancellation. Belt-and-braces alongside [scanInFlight]: it is set
     * before the first suspension point, so two near-simultaneous [load] calls can
     * never both scan. Every exit clears it, so a state is never left wedged: a
     * cancelled scan is re-startable and a completed one is governed by
     * [lastCompletedSyncAt].
     */
    private var started = false

    /**
     * Epoch seconds of the last *successful* scan, or null when none is known.
     * Seeded from the persisted snapshot so the staleness rule survives process
     * death, and refreshed on every completed scan. [load] skips a rescan while
     * this is younger than [STALE_AFTER_SECONDS].
     */
    private var lastCompletedSyncAt: Long? = null

    // ---- the live subscription ---------------------------------------------

    /**
     * What the manager persists through: this signer's own snapshot and status
     * hashes. When there is no [SnapshotStore] (a cold caller, or a test that is
     * not about persistence) the values are kept for this state's lifetime
     * instead, so the live path still works — it just cannot remember anything
     * across a restart, which is exactly what "no cache" already means.
     */
    private val subscriptionStore: SubscriptionStateStore =
        snapshotStore?.subscriptionState(signer.id) ?: TransientSubscriptionStateStore()

    /** Guards the four fields below, which are written from the UI and the subscription job. */
    private val subscriptionLock = Any()

    /** The coroutine running the live connection, if one is. */
    private var subscriptionJob: Job? = null

    /** The live-updates engine, once it exists. */
    private var subscriptionEngine: SignerSubscriptionEngine? = null

    /** The long-lived connection the engine is attached to. This state owns it. */
    private var subscriptionSession: ChainSubscriptionSession? = null

    /**
     * True between [startSubscriptions] and [stopSubscriptions] — i.e. while the
     * signer's screen is open. It is what stops a rescan that finishes *after*
     * the user navigated away from opening a subscription behind their back.
     */
    private var subscriptionsWanted = false

    init {
        // Seed from the last saved scan, if there is one. The screen is READY
        // immediately; [load] then refreshes that snapshot in the background —
        // but only once it is older than [STALE_AFTER_SECONDS], so a fresh cache
        // means re-entering the screen costs nothing.
        snapshotStore?.load(signer.id)?.let { cached ->
            snapshot = cached.snapshot
            phase = ChainPhase.READY
            lastCompletedSyncAt = cached.scannedAtEpochSeconds
        }
    }

    /**
     * Syncs this signer if it is stale or has never been synced — never
     * interrupting a scan that is already in flight.
     *
     * Called through [SignerChainStateStore.sync] when the signer's screen opens
     * (and on every re-entry), so it must be cheap and idempotent: it does
     * nothing while [scanInFlight] (the sync that is already running keeps
     * running, wherever the user navigates), and nothing while the last completed
     * sync is younger than [STALE_AFTER_SECONDS]. When a snapshot was seeded at
     * construction the scan it may start is an incremental rescan of it.
     *
     * A scan cancelled mid-flight (teardown, or the signer being deleted) leaves
     * this state re-startable — the cancelled scan clears the in-flight flag it
     * set, so the next call runs a real scan instead of silently doing nothing.
     */
    suspend fun load() {
        if (scanInFlight || started) return
        val completedAt = lastCompletedSyncAt
        if (completedAt != null && now() - completedAt < STALE_AFTER_SECONDS) return
        started = true
        scanNow(snapshot)
    }

    /** Re-runs the scan, whatever the current phase — the Refresh/Retry button. */
    suspend fun reload() = scanNow(snapshot)

    // ---- the live subscription's lifecycle ---------------------------------

    /**
     * Opens this signer's live-update subscription, if there is one to open.
     *
     * Called when the signer's screen appears. It is idempotent (a second call
     * while a connection is already up does nothing) and it returns immediately:
     * the dial happens on [subscriptionScope], never on the caller, so a slow or
     * unresponsive server cannot delay the screen or the rescan that is starting
     * beside it.
     *
     * Nothing here is required for the screen to work. With no scope, no saved
     * endpoint, or an unreadable key there is simply no subscription; with no
     * snapshot yet it waits for the first completed scan (see
     * [maybeLaunchSubscriptions]) rather than subscribing to an account it has
     * not read.
     */
    fun startSubscriptions() {
        if (subscriptionScope == null) return
        synchronized(subscriptionLock) { subscriptionsWanted = true }
        maybeLaunchSubscriptions()
    }

    /**
     * Closes the live subscription and cancels the manager: what navigating away
     * from the signer's screen, switching signer, and process death all do.
     *
     * Idempotent, safe to call with nothing running, and synchronous — by the
     * time it returns the connection is closed and no further event can reach
     * [snapshot]. Reopening the signer starts a fresh connection; anything that
     * happened in the gap is found by the manager's status-hash diff on the next
     * [startSubscriptions], which is the same code path a live notification takes.
     */
    fun stopSubscriptions() {
        val job: Job?
        val engine: SignerSubscriptionEngine?
        val session: ChainSubscriptionSession?
        synchronized(subscriptionLock) {
            subscriptionsWanted = false
            job = subscriptionJob
            engine = subscriptionEngine
            session = subscriptionSession
            subscriptionEngine = null
            subscriptionSession = null
        }
        job?.cancel()
        // Both are idempotent, and the job's own `finally` closes them again on
        // its way out — whichever runs first wins and the other is a no-op.
        runCatching { engine?.close() }
        runCatching { session?.close() }
    }

    /**
     * Starts the live connection if the screen wants one and there is nothing to
     * build on yet stopping us.
     *
     * Called both by [startSubscriptions] (the screen opening) and by a completed
     * scan (a cold screen has no snapshot when it first asks). The
     * [subscriptionsWanted] check is what keeps the second caller honest: a scan
     * that lands after the user navigated away must not open a connection.
     */
    private fun maybeLaunchSubscriptions() {
        synchronized(subscriptionLock) {
            if (!subscriptionsWanted) return
            if (subscriptionJob?.isActive == true) return
            val scope = subscriptionScope ?: return
            val server = configuredServer() ?: return
            if (scanner == null) return
            // Nothing to subscribe to yet: the completed scan will ask again.
            if (snapshot == null) return
            subscriptionJob = scope.launch { runSubscriptions(server) }
        }
    }

    /**
     * The subscription's whole life: open, attach, keep alive, close.
     *
     * Every failure is swallowed on purpose. A subscription is an optimization
     * over the rescan, and the card's rule is that a signer screen behaves
     * exactly like the pull-only one when it cannot be had — so a dial that fails
     * or a manager that cannot be built ends here quietly, with today's
     * Refresh/Retry still working above. A connection that dies mid-flight is
     * reconnected and resubscribed instead (see [ReconnectingSubscription]), and
     * only a spent reconnect budget ends the live path — silently too.
     */
    private suspend fun runSubscriptions(server: ElectrumServer) {
        val session = try {
            openSubscription(server)
        } catch (cancellation: CancellationException) {
            // The user navigated away while the dial was in flight. (A socket
            // opened a moment before the cancellation is the one case this
            // cannot clean up — the session hands it back only on success — and
            // the process going away is the usual reason for being here.)
            throw cancellation
        } catch (failure: Exception) {
            Log.w(SUBSCRIPTION_LOG_TAG, "could not open the live connection", failure)
            return
        }

        val engine = try {
            newSubscriptionEngine(subscriptionStore)
        } catch (cancellation: CancellationException) {
            runCatching { session.close() }
            throw cancellation
        } catch (failure: Exception) {
            Log.w(SUBSCRIPTION_LOG_TAG, "could not start the live-updates manager", failure)
            runCatching { session.close() }
            return
        }

        // Re-read the snapshot *now*, not when the job was launched: a rescan may
        // have finished while the dial was in flight, and the manager must be
        // seeded with the freshest state we have or its first merge would put
        // stale numbers on screen.
        val initial = synchronized(subscriptionLock) {
            val current = snapshot
            if (!subscriptionsWanted || current == null) {
                // The screen went away (or the data did) while we were dialling:
                // nothing owns this connection any more, so it goes back.
                runCatching { engine.close() }
                runCatching { session.close() }
                return
            }
            subscriptionEngine = engine
            subscriptionSession = session
            current
        }

        try {
            // The whole subscription, reconnects included. A connection that dies
            // is re-dialled and resubscribed with backoff — and resubscribing is
            // what diffs the status hashes, so whatever happened while it was
            // down is refreshed through the same path a live push takes. The wait
            // between attempts runs in this job, so leaving the screen cancels it
            // mid-backoff.
            val outcome = ReconnectingSubscription(
                engine = engine,
                open = { openSubscription(server) },
                policy = reconnectPolicy,
                log = ::logSubscription,
                backoff = reconnectBackoff,
                onSession = { connected ->
                    synchronized(subscriptionLock) {
                        if (subscriptionsWanted) subscriptionSession = connected
                    }
                },
                onEvent = { publishSubscriptionState(engine) },
            ).run(session, initial)

            // The reconnect budget is spent (or the manager stopped): today's
            // behaviour — pull, Refresh, Retry — takes over from here, with
            // nothing said about it on screen. Reopening the signer opens a fresh
            // connection and a fresh budget.
            synchronized(subscriptionLock) { subscriptionsWanted = false }
            Log.i(SUBSCRIPTION_LOG_TAG, "the live connection is over: $outcome")
        } finally {
            val owned = synchronized(subscriptionLock) {
                if (subscriptionEngine === engine) subscriptionEngine = null
                // The live connection is whichever one the loop ended up on — a
                // reconnect replaces it — and either way it is this state's to
                // close (the loop closes it too; both are idempotent).
                subscriptionSession.also { subscriptionSession = null }
            }
            runCatching { engine.close() }
            runCatching { owned?.close() }
        }
    }

    /** Mirrors the manager's snapshot (and the fact that it is fresh) into this state. */
    private fun publishSubscriptionState(engine: SignerSubscriptionEngine) {
        val latest = try {
            engine.snapshot
        } catch (failure: Throwable) {
            return
        }
        if (snapshot == latest) return
        snapshot = latest
        // The data on screen came from the server a moment ago, so a line saying
        // "couldn't refresh" over it would be a lie. (Only reachable while
        // subscribed: the pull-only path never calls this.)
        syncFailed = false
    }

    /**
     * Hands a completed scan to the running manager, and lets a cold screen start
     * its subscription at last.
     *
     * The hand-off is the rule that keeps two writers to one snapshot from
     * fighting: the rescan has just produced fresher data than anything the
     * manager holds, so the manager adopts it (`updateSnapshot`) instead of
     * merging into its own copy — and a live notification for an address the
     * rescan just covered is therefore never fetched a second time.
     */
    private fun publishScan(result: AccountSnapshot) {
        synchronized(subscriptionLock) { subscriptionEngine }?.let { engine ->
            runCatching { engine.updateSnapshot(result) }
        }
        maybeLaunchSubscriptions()
    }

    companion object {
        /**
         * How long a completed sync stays "fresh". [load] skips a rescan while the
         * last completed sync is younger than this, so re-entering a signer screen
         * within two minutes does not kick off a rescan; past it, [load] resyncs
         * automatically. The manual Refresh/Retry ([reload]) ignores it.
         */
        const val STALE_AFTER_SECONDS: Long = 120L

        /**
         * The default retry schedule for a scan that fails with a transient
         * transport error: wait 3s, try again, wait 10s, try again, and only then
         * report the failure. Two extra attempts is enough to ride out a screen
         * that slept (the socket dies, then reconnects) without leaving a dead
         * server to hammer for long — see [SignerChainState.retryDelaysSeconds].
         */
        val DEFAULT_RETRY_DELAYS_SECONDS: List<Long> = listOf(3L, 10L)
    }

    /**
     * The one scan path, cold or warm.
     *
     * [cached] is the previous snapshot to build on, or null for a from-scratch
     * scan. The two branches differ only in what a user sees: the cold path owns
     * the LOADING/READY/ERROR machine (there is nothing to keep on screen), the
     * warm path keeps the cached snapshot visible throughout and reports itself
     * through [isSyncing]/[syncFailed] instead.
     */
    private suspend fun scanNow(cached: AccountSnapshot?) {
        val scanner = scanner
        if (scanner == null) {
            // The stored xpub is unreadable. There is nothing to scan and no
            // network to blame, so say exactly that.
            snapshot = null
            errorMessage = "This wallet's key could not be read. Delete it and import the xpub again."
            phase = ChainPhase.ERROR
            isSyncing = false
            syncFailed = false
            return
        }

        val server = configuredServer()
        if (server == null) {
            if (cached == null) {
                // No endpoint saved and nothing cached: the "Connect a server"
                // case, not a failure.
                snapshot = null
                errorMessage = null
                phase = ChainPhase.NO_SERVER
            } else {
                // No endpoint, but we have a snapshot to show. Keep it — the
                // screen's "Connect a server" text is only for the case where
                // there is nothing cached to display.
                phase = ChainPhase.READY
                isSyncing = false
            }
            return
        }

        if (cached == null) {
            coldScan(server, scanner)
        } else {
            warmScan(server, scanner, cached)
        }
    }

    /** A from-scratch scan: the screen is loading until it answers or fails. */
    private suspend fun coldScan(server: ElectrumServer, scanner: AccountScanner) {
        phase = ChainPhase.LOADING
        errorMessage = null
        scannedAddressCount = 0
        try {
            val result = scanWithRetries(server, scanner, null) { checked -> scannedAddressCount = checked }
            snapshot = result
            lastCompletedSyncAt = now()
            phase = ChainPhase.READY
            started = false
            snapshotStore?.save(signer.id, result)
            publishScan(result)
        } catch (cancellation: CancellationException) {
            // Teardown (the app scope being cancelled) or the signer being
            // deleted while the scan is in flight must stay a cancellation, not
            // become an error message about the network — but the state must also
            // be *re-startable*: a re-opened screen would otherwise sit on
            // LOADING forever, so drop back to IDLE and clear the in-flight flag
            // before rethrowing.
            phase = ChainPhase.IDLE
            started = false
            throw cancellation
        } catch (error: Exception) {
            snapshot = null
            errorMessage = describe(server, error)
            phase = ChainPhase.ERROR
            started = false
        }
    }

    /**
     * A refresh of an existing snapshot: the cached data stays on screen while
     * the rescan runs, and a failure leaves it there with a Retry.
     */
    private suspend fun warmScan(server: ElectrumServer, scanner: AccountScanner, cached: AccountSnapshot) {
        isSyncing = true
        syncFailed = false
        errorMessage = null
        scannedAddressCount = 0
        try {
            val result = scanWithRetries(server, scanner, cached) { checked -> scannedAddressCount = checked }
            snapshot = result
            lastCompletedSyncAt = now()
            phase = ChainPhase.READY
            snapshotStore?.save(signer.id, result)
            // A completed rescan is the freshest the account can be, so the live
            // manager adopts it rather than merging into its own copy — that is
            // what makes "a notification and a rescan both touching one address"
            // one refresh, not two.
            publishScan(result)
        } catch (cancellation: CancellationException) {
            // Cancelling (teardown, or the signer being deleted) is not a failure:
            // the cached data is untouched and a later visit rescans. Clear the
            // in-flight flag so that later visit can actually start (isSyncing is
            // reset in finally).
            started = false
            throw cancellation
        } catch (error: Exception) {
            // Keep the cached snapshot: a refresh that failed must not blank a
            // screen that was showing real numbers a moment ago.
            syncFailed = true
            phase = ChainPhase.READY
        } finally {
            isSyncing = false
            started = false
        }
    }

    /** The saved endpoint, or null when the user has not entered one. */
    private fun configuredServer(): ElectrumServer? = serverSettings.load().takeIf { it.host.isNotBlank() }

    /**
     * Runs one whole-account scan, retrying it after a *transient transport*
     * failure.
     *
     * The retry budget is [retryDelaysSeconds] — one entry per extra attempt, so
     * the default two entries mean three attempts in total. A failure that is not
     * a transport failure is not retried at all:
     *
     * - [CancellationException] rethrows on the spot (teardown, or the signer
     *   being deleted), and because the backoff below is a [delay] it stays
     *   cancellable *while waiting* — a scan sleeping out its retry is torn down
     *   the same as one blocked on the socket;
     * - [ElectrumException] (and anything else that is not an [IOException])
     *   propagates unchanged: the server answered, and the answer was an error,
     *   so asking again would only get the same answer.
     *
     * An [IOException] — a read timeout, a refused connection, a dropped socket,
     * an unresolvable host — is the case this exists for: the device slept or the
     * link blipped, and one more dial usually succeeds. [scannedAddressCount] is
     * deliberately **not** reset between attempts on the warm path (the real
     * scanner reports its own progress from the start anyway), so the count on
     * screen never lurches back to zero while a cached snapshot is showing.
     */
    private suspend fun scanWithRetries(
        server: ElectrumServer,
        scanner: AccountScanner,
        cached: AccountSnapshot?,
        onProgress: (Int) -> Unit,
    ): AccountSnapshot {
        var retries = 0
        while (true) {
            try {
                return scan(server, scanner, cached, onProgress)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (transport: IOException) {
                val delaySeconds = retryDelaysSeconds.getOrNull(retries) ?: throw transport
                retries++
                delay(delaySeconds * 1000L)
            }
        }
    }

    /**
     * Turns a transport failure into a sentence worth reading.
     *
     * These are the same failures the Server screen's connection test reports;
     * the wording is repeated here rather than shared because this screen needs
     * it as a *message*, not as log lines. The one thing genuinely shared is the
     * socket-errno vocabulary ([SocketErrno]): `EACCES`/`EPERM` is the OS
     * rejecting the socket — a phone-side cause — and saying "the connection
     * failed" (or worse, "refused") over it sends the user to debug a server
     * that never heard the request. A type the JVM explains fully keeps its own
     * wording first, exactly as on the Server screen, so the one deliberate
     * distinction is the timeout: a dial that reports `ETIMEDOUT` is a failed
     * connection attempt, while a [SocketTimeoutException] is the server not
     * answering.
     */
    private fun describe(server: ElectrumServer, error: Exception): String {
        val where = "${server.host}:${server.port}"
        val reason = when (error) {
            is UnknownHostException -> "that host name could not be resolved"
            is ElectrumException -> "the server rejected the request (${error.message})"
            is SocketTimeoutException -> "the server did not answer in time"
            else -> socketErrnoOf(error)?.let { socketErrnoReason(it) }
                ?: when (error) {
                    is ConnectException -> "the connection was refused"
                    is IOException -> "the connection failed (${error.message ?: "no details"})"
                    else -> error.message ?: error::class.java.simpleName
                }
        }
        return "Could not read the balance from $where — $reason."
    }
}

/** The states a signer screen cycles through. */
enum class ChainPhase {
    /** Nothing scanned yet and nothing cached; renders as loading. */
    IDLE,

    /** A cold scan is in flight. */
    LOADING,

    /** A scan completed (or a cache was seeded); [SignerChainState.snapshot] is set. */
    READY,

    /** No Electrum endpoint is saved and nothing is cached — the "connect a server" case. */
    NO_SERVER,

    /** A scan was attempted and failed with nothing cached; [SignerChainState.errorMessage] says why. */
    ERROR,
}

/**
 * One whole-account scan, as the state class sees it: a server, a scanner, the
 * cached snapshot to build on (null = a full scan), and a progress callback.
 *
 * A type alias rather than an interface so the production default is a plain
 * function reference (`::scanOverElectrum`) and a test's fake is a lambda. The
 * cached-snapshot parameter is what lets a warm rescan reuse prior work — the
 * real implementation routes it to [AccountScanner.rescan] and a full scan to
 * [AccountScanner.scan].
 */
typealias ChainScan = suspend (ElectrumServer, AccountScanner, AccountSnapshot?, (Int) -> Unit) -> AccountSnapshot

/**
 * The real scan: open one connection to the user's server and run the gap-limit
 * walk over it — a cold [AccountScanner.scan] when there is nothing cached, or
 * the incremental [AccountScanner.rescan] when there is.
 *
 * The client is built with a no-op logger — this is a background read, and the
 * Server screen's log is for connection tests, not for every balance refresh.
 *
 * The one thing this *does* report is a block header that could not be read: a
 * row that cannot be dated used to become a row with no date line and no
 * explanation anywhere (the failure was swallowed one layer down), which is
 * unfixable from a user's bug report. It goes to Logcat as a warning — height
 * only, never the server's identity or any address — and the Transactions
 * section says one line about it on screen.
 */
internal suspend fun scanOverElectrum(
    server: ElectrumServer,
    scanner: AccountScanner,
    cached: AccountSnapshot?,
    onScanned: (Int) -> Unit,
): AccountSnapshot =
    ElectrumClient(server = server, log = {}).withChainSession { query ->
        val onTimestampFailure: (Int, Exception) -> Unit = { height, error ->
            Log.w(SCAN_LOG_TAG, "block header unavailable for height $height", error)
        }
        if (cached == null) {
            scanner.scan(query, onScanned, onTimestampFailure)
        } else {
            scanner.rescan(query, cached, onScanned, onTimestampFailure)
        }
    }

/** Logcat tag for the scan's own diagnostics (see [scanOverElectrum]). */
private const val SCAN_LOG_TAG = "SignerChainScan"

/** Logcat tag for the live subscription's own diagnostics (see [SignerChainState.startSubscriptions]). */
private const val SUBSCRIPTION_LOG_TAG = "SignerSubscriptions"

/**
 * The live path's Logcat line, best-effort.
 *
 * The reconnect loop calls its logger constantly — every drop, every attempt,
 * every give-up — and a logger that can throw would turn "the connection
 * dropped" into "the subscription died with an exception", so this one swallows
 * its own failures. That is not hypothetical: the JVM unit tests run against a
 * stubbed `android.util.Log` that throws on every call, which would otherwise
 * make the reconnect loop untestable in this project's own test setup.
 *
 * Only the socket-level story is logged, never an address or a balance.
 */
private fun logSubscription(message: String) {
    runCatching { Log.i(SUBSCRIPTION_LOG_TAG, message) }
}

/**
 * The live-updates engine, as [SignerChainState] sees it: something it hands a
 * connection to, collects events from, mirrors a snapshot out of, and closes.
 *
 * This is a seam and nothing more — and it is the chain layer's
 * [SubscriptionEngine] with a UI-side name, so the reconnect loop
 * ([ReconnectingSubscription]) can drive the real manager *and* a test's double
 * without either layer importing the other. The real implementation is
 * [SubscriptionManager] in `chain/`, wrapped by [managerEngine] — a wrapper and
 * not a supertype on the manager itself, because `chain/` must not know about
 * the UI.
 */
interface SignerSubscriptionEngine : SubscriptionEngine

/** How a [SignerChainState] builds the engine for its signer. Injectable for tests. */
typealias SubscriptionEngineFactory = (SubscriptionStateStore) -> SignerSubscriptionEngine

/**
 * Opens one long-lived, subscribable connection to [server]. Injectable for
 * tests, which hand back a fake instead of dialling anything.
 */
typealias SubscriptionOpener = suspend (ElectrumServer) -> ChainSubscriptionSession

/** The real engine: the chain layer's [SubscriptionManager], narrowed to [SignerSubscriptionEngine]. */
internal fun managerEngine(store: SubscriptionStateStore): SignerSubscriptionEngine =
    ManagerSubscriptionEngine(SubscriptionManager(store))

/** Adapts a real [SubscriptionManager] to the one seam this package needs. */
private class ManagerSubscriptionEngine(private val manager: SubscriptionManager) : SignerSubscriptionEngine {
    override val events: Flow<ChainEvent> get() = manager.events

    override val snapshot: AccountSnapshot get() = manager.snapshot

    override fun start(session: ChainSubscriptionSession, snapshot: AccountSnapshot) =
        manager.start(session, snapshot)

    override fun updateSnapshot(snapshot: AccountSnapshot) = manager.updateSnapshot(snapshot)

    override fun close() = manager.close()
}

/**
 * The real live connection: one socket through the app's own transport (Tor for
 * a `.onion` host, see `ElectrumTransport`), with no read timeout — an idle
 * subscription is silent by design.
 *
 * [com.xbtwallet.app.electrum.SubscriptionSession] answers `subscribeHeaders`
 * with `BlockHeaderTip` while `chain/`'s interface asks for [ChainTip], and this
 * is the one line that bridges them: delegation for everything else, an explicit
 * override for the one method whose *type* differs. Doing it here — rather than
 * importing `electrum` into `chain` — is what keeps the layering one-way.
 */
internal suspend fun openLiveSubscription(server: ElectrumServer): ChainSubscriptionSession =
    LiveSubscriptionSession(ElectrumClient(server = server, log = {}).openSubscriptionSession())

/**
 * The session, seen as `chain/`'s [ChainSubscriptionSession].
 *
 * Every member is a straight pass-through to the real
 * [com.xbtwallet.app.electrum.SubscriptionSession] — the same socket, the same
 * queries, the same callbacks — with exactly one translation: the session's
 * `subscribeHeaders` answers with `BlockHeaderTip`, this interface with
 * [ChainTip]. Writing that once here is what keeps `chain/` from having to
 * import `electrum/` (the dependency runs the other way).
 */
private class LiveSubscriptionSession(
    private val session: SubscriptionSession,
) : ChainSubscriptionSession {

    override var onNotification: (method: String, params: Any?) -> Unit
        get() = session.onNotification
        set(value) {
            session.onNotification = value
        }

    override var onClosed: (error: Throwable) -> Unit
        get() = session.onClosed
        set(value) {
            session.onClosed = value
        }

    override val isClosed: Boolean get() = session.isClosed

    override fun subscribeScripthash(scripthash: String): String? = session.subscribeScripthash(scripthash)

    override fun subscribeHeaders(): ChainTip =
        session.subscribeHeaders().let { ChainTip(it.height, it.hex) }

    override fun getHistory(scripthash: String): List<HistoryEntry> = session.getHistory(scripthash)

    override fun getBalance(scripthash: String): ScripthashBalance = session.getBalance(scripthash)

    override fun listUnspent(scripthash: String): List<ChainUtxo> = session.listUnspent(scripthash)

    override fun getTransaction(txHash: String): String = session.getTransaction(txHash)

    override fun getTipHeight(): Int = session.getTipHeight()

    override fun blockHeader(height: Int): String = session.blockHeader(height)

    override fun close() = session.close()
}

/**
 * A [SubscriptionStateStore] for a state with no [SnapshotStore] to persist
 * through: it remembers the status hashes and the snapshot for as long as the
 * state lives, and forgets them with it.
 *
 * The manager's contract only asks that a store never throws (see
 * [SubscriptionStateStore]), and this one cannot — so the live path keeps
 * working for a caller that has no cache, exactly as the pull path does.
 */
private class TransientSubscriptionStateStore : SubscriptionStateStore {
    private var statuses: Map<String, String?> = emptyMap()

    override fun loadStatusHashes(): Map<String, String?> = statuses

    override fun saveStatusHashes(statuses: Map<String, String?>) {
        this.statuses = statuses
    }

    override fun saveSnapshot(snapshot: AccountSnapshot) {
        // Deliberately dropped: with no [SnapshotStore] there is nowhere to put
        // it, and the caller's own scan is what it opens on next time.
    }
}
