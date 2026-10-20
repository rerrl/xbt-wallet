package com.xbtwallet.app.ui.signers

import androidx.compose.runtime.Stable
import com.xbtwallet.app.data.ServerSettings
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the per-signer [SignerChainState]s and the scans running on them, for the
 * app's lifetime.
 *
 * **Why a store at all.** A signer's sync used to live in the screen that showed
 * it (`remember(signerId)` plus a composable-scoped `LaunchedEffect`), so walking
 * detail → UTXOs → Home → detail discarded the state and cancelled the scan
 * mid-flight — and re-entering started the whole Electrum rescan over. Andrew:
 * *"every time I enter a specific signer's screen, it kicks off the sync, even if
 * it was just already syncing."* Hoisting the states out of composition and
 * launching their scans in an app-scoped [scope] fixes both halves: a sync in
 * flight keeps running wherever the user navigates (while the process lives), and
 * re-entering begins a sync only when none is running — see
 * [SignerChainState.load] for the two-minute freshness rule that governs the rest.
 *
 * **Why no eviction.** The number of signers is small (a handful, hand-entered)
 * and each state is a few hundred kilobytes of scan results — not worth the
 * bookkeeping of an LRU, and evicting a state would throw away a scan in flight.
 * Deletion is the only removal path ([remove]), wired into the signer delete path
 * in `XbtWalletApp` alongside the snapshot and signer stores.
 *
 * **Threading.** All methods are main-thread: the store lives in composition and
 * [scope] is `Dispatchers.Main.immediate`, so [stateFor], [sync] and [remove] are
 * safe to call straight from a composable and never block.
 *
 * @param serverSettings read access to the saved Electrum endpoint, shared by
 *   every state this store creates.
 * @param snapshotStore the persisted per-signer scan, or null; shared by every
 *   state so each seeds from (and saves to) its own signer's cache.
 * @param scope the app-scoped coroutine scope the scans run in. It must outlive
 *   every screen — cancelling it (root dispose) cancels the scans, which is the
 *   intended teardown.
 * @param now the read clock in epoch seconds, handed to each state; injectable
 *   for tests.
 * @param openSubscription how each state opens its live subscription connection;
 *   defaults to a real one. Injectable so the store's own behaviour — the scope
 *   a subscription runs in, and its cancellation on [remove] — can be exercised
 *   without a socket.
 * @param newSubscriptionEngine how each state builds its live-updates engine;
 *   defaults to the real [com.xbtwallet.app.chain.SubscriptionManager].
 * @param scan the whole-account scan handed to each state it creates; defaults
 *   to the real Electrum scan. Injectable so the store's own behaviour — one
 *   scan per signer, cancellation on [remove] — can be exercised without a
 *   socket.
 */
@Stable
class SignerChainStateStore(
    private val serverSettings: ServerSettings,
    private val snapshotStore: SnapshotStore?,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val openSubscription: SubscriptionOpener = ::openLiveSubscription,
    private val newSubscriptionEngine: SubscriptionEngineFactory = ::managerEngine,
    private val scan: ChainScan = ::scanOverElectrum,
) {

    /** One state per signer id, for the store's lifetime. */
    private val states = HashMap<String, SignerChainState>()

    /** The scan currently launched for a signer, if any. */
    private val jobs = HashMap<String, Job>()

    /**
     * The state for [signer], created on first request and reused thereafter.
     *
     * Keyed by [Signer.id]: the same signer always resolves to the same instance,
     * so its scan and its result survive navigation, and the screen that shows it
     * reads the one shared state — the header spinner on detail, UTXOs and
     * Addresses is therefore the same spinner.
     */
    fun stateFor(signer: Signer): SignerChainState =
        states.getOrPut(signer.id) {
            SignerChainState(
                signer = signer,
                serverSettings = serverSettings,
                snapshotStore = snapshotStore,
                now = now,
                // The live subscription runs in the same app-scoped scope the
                // scans do, and is cancelled by [SignerChainState.stopSubscriptions]
                // when the signer's screen leaves — not by the scope's own
                // lifetime, which is the process's.
                subscriptionScope = scope,
                openSubscription = openSubscription,
                newSubscriptionEngine = newSubscriptionEngine,
                scan = scan,
            )
        }

    /**
     * Begins a sync for [signer] if one is not already running.
     *
     * Non-suspend: it launches [SignerChainState.load] in [scope] and remembers
     * the job. Called when a signer's screen opens (and on every re-entry), so
     * concurrent calls for the same signer must produce exactly one scan — the
     * active-job check below guarantees it even before the launched coroutine has
     * started. Whether a scan is actually needed (in flight, or fresh enough) is
     * [SignerChainState.load]'s decision.
     */
    fun sync(signer: Signer) {
        val id = signer.id
        if (jobs[id]?.isActive == true) return
        jobs[id] = scope.launch { stateFor(signer).load() }
    }

    /**
     * Forgets [signerId]: cancels any scan in flight, closes its live
     * subscription, and drops its state, so a later signer that happens to reuse
     * the id can never open on this one's balance, UTXOs or addresses — or on a
     * socket still carrying the deleted signer's notifications.
     *
     * Called from the signer delete path in `XbtWalletApp`, next to
     * `SignerStore.delete` and `SnapshotStore.clear` (clearing the persisted
     * snapshot stays at that call site). The screen's own `onDispose` closes the
     * subscription when the user navigates away; this is the belt-and-braces for
     * the one path that removes a signer while its state exists.
     */
    fun remove(signerId: String) {
        jobs.remove(signerId)?.cancel()
        states.remove(signerId)?.stopSubscriptions()
    }
}
