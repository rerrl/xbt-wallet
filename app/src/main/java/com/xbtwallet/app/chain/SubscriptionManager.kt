package com.xbtwallet.app.chain

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * The chain tip `blockchain.headers.subscribe` reports: the height and the raw
 * header hex.
 *
 * This mirrors `com.xbtwallet.app.electrum.BlockHeaderTip`, and exists so this
 * file — and everything else in `chain/` — needs no import from `electrum/`
 * (the dependency between those packages runs the other way: `electrum` speaks
 * the protocol in terms of `chain`'s types, never the reverse).
 */
data class ChainTip(
    val height: Int,
    val hex: String,
)

/**
 * The narrow slice of a long-lived Electrum connection the [SubscriptionManager]
 * needs, and the seam its tests drive.
 *
 * The real implementation is `com.xbtwallet.app.electrum.SubscriptionSession`,
 * which already has every one of these members: it is a [ChainQuery] over the
 * subscribed socket, it subscribes scripthashes and headers, it hands the
 * server's pushed notifications to `onNotification`, it reports terminal death
 * once through `onClosed`, and it has `isClosed`/`close()`.
 *
 * The only member whose *signature* differs is [subscribeHeaders] — the session
 * answers with `BlockHeaderTip`, this interface asks for [ChainTip] — so the
 * wiring layer adapts it, in one line, with delegation:
 *
 * ```
 * object : ChainSubscriptionSession by session {
 *     override fun subscribeHeaders(): ChainTip =
 *         session.subscribeHeaders().let { ChainTip(it.height, it.hex) }
 * }
 * ```
 *
 * Everything else is delegated verbatim. Deciding that mapping here (rather
 * than importing `electrum` into `chain`) is what keeps the layering one-way.
 */
interface ChainSubscriptionSession : ChainQuery {

    /**
     * `blockchain.scripthash.subscribe`: subscribe to this address and return
     * the status hash the server currently holds for it.
     *
     * `null` is a real answer, not an error: Fulcrum answers null for an address
     * it has never seen a transaction for. Later changes arrive through
     * [onNotification].
     */
    fun subscribeScripthash(scripthash: String): String?

    /** `blockchain.headers.subscribe`: subscribe to new blocks and return the current tip. */
    fun subscribeHeaders(): ChainTip

    /**
     * Called for every notification the server pushes, with its raw params:
     * `[scripthash, statusHash-or-null]` for `blockchain.scripthash.subscribe`
     * and `[{height, hex}]` for `blockchain.headers.subscribe`.
     *
     * Runs on the connection's reader thread, so it must not block for long —
     * see the class doc of `SubscriptionSession`.
     */
    var onNotification: (method: String, params: Any?) -> Unit

    /** Called **once** when the connection dies, on the reader thread. */
    var onClosed: (error: Throwable) -> Unit

    /** True once the connection has died or the caller has closed it. */
    val isClosed: Boolean

    /** Closes the connection. Idempotent; does not itself report a failure. */
    fun close()
}

/**
 * Where a [SubscriptionManager] keeps what it learns, for one signer.
 *
 * This is the second seam: `chain/` must not import `data/` (the dependency runs
 * `data` -> `chain`, as in `SnapshotStore`), so the manager is handed the three
 * things it persists rather than a `SnapshotStore`. `SnapshotStore.subscriptionState`
 * hands back one of these bound to a signer id.
 *
 * Every method is expected to be forgiving rather than throwing: an unreadable
 * cache costs a rescan, it never crashes the app.
 */
interface SubscriptionStateStore {

    /**
     * The last status hash seen for every address this signer has ever
     * subscribed to, keyed by scripthash. A `null` *value* is meaningful: it is
     * Fulcrum's answer for an address it has never seen a transaction for, and
     * it is what makes an address that was paid while the app was closed a
     * mismatch on the next [SubscriptionManager.start].
     *
     * An *absent* key means there is no record at all — the address has never
     * been subscribed — and is treated as "nothing to compare", not as null.
     */
    fun loadStatusHashes(): Map<String, String?>

    /** Replaces the persisted status hashes for this signer. */
    fun saveStatusHashes(statuses: Map<String, String?>)

    /** Stores [snapshot] as this signer's cached account state. */
    fun saveSnapshot(snapshot: AccountSnapshot)
}

/**
 * What the manager has to say. Collected through [SubscriptionManager.events].
 */
sealed interface ChainEvent {

    /**
     * The server says this address's history changed — pushed live, or noticed
     * by the restart-time status diff. [newStatus] is the status hash the server
     * just reported (null for an address it has never seen a transaction for);
     * it is the address's *new* status, and the manager will have committed it
     * once the targeted refresh it queued has been applied.
     *
     * Emitted before the refresh runs: it is the server's statement, not the
     * manager's finished work. [ChainEvent.Resynced] is the "the snapshot now
     * reflects it" signal.
     */
    data class AddressChanged(val scripthash: String, val newStatus: String?) : ChainEvent

    /** A new block, so confirmation counts are counted from a new tip. */
    data class NewBlock(val tip: ChainTip) : ChainEvent

    /**
     * The connection died. The manager stops touching it: no further queries are
     * issued and no further notifications are acted on. Reconnecting is a policy
     * question this layer deliberately does not answer (see the class doc).
     */
    data class SessionLost(val error: Throwable) : ChainEvent

    /**
     * Everything the manager had queued has been applied and persisted, so
     * [SubscriptionManager.snapshot] is current. Emitted once per settling: at
     * the end of the initial catch-up after [SubscriptionManager.start] (even
     * when nothing had changed), and again after any later burst of work.
     */
    data object Resynced : ChainEvent
}

/**
 * The live-updates engine: it subscribes to an address set, notices which
 * addresses changed, and refreshes exactly those.
 *
 * **Why a status hash is enough.** `blockchain.scripthash.subscribe` answers —
 * and later pushes — a *status hash* per address, a value that changes whenever
 * that address's history changes. Comparing the value the server reports with
 * the one we last saw therefore says "something happened here", whether it
 * happened seconds ago or while the app was closed. That is the whole catch-up
 * mechanism: live notifications and the missed window are the *same* code path
 * (diff, then targeted refresh), and no polling is involved.
 *
 * **What "targeted" means.** A changed address costs one `get_history`, plus —
 * only when it is used — one `get_balance` and one `list_unspent`, and the
 * transaction fetches the row builder needs for that address's transactions. The
 * gap-limit walk is never repeated here: new addresses beyond the subscribed set
 * are the incremental resync's business (`AccountScanner.rescan`), which sits
 * underneath this and remains the backstop.
 *
 * **The balance, and why a refresh may read more than one address.** The scan's
 * rule is that the headline balance is the sum of `get_balance` over the used
 * addresses, and is never derived from the UTXO list (a UTXO sum cannot see a
 * mempool spend's negative half). To keep that rule after a refresh without
 * re-walking the account, the manager remembers each address's own
 * `get_balance` answer; the first refresh in a session fills that memory for the
 * used addresses it has not asked about yet, and every refresh after that is
 * the single changed address. [updateSnapshot] throws that memory away, because
 * a snapshot handed in from elsewhere carries fresh totals the per-address
 * answers can no longer be trusted against.
 *
 * **Coalescing.** Notifications for one scripthash while its refresh is in
 * flight collapse into exactly one follow-up refresh — not a queue of N — and
 * notifications for an address already queued collapse into the queued one. A
 * status hash reported by a notification is only *committed* (and persisted)
 * once the refresh that acted on it has been applied, so a session that dies
 * mid-refresh leaves the old status in place and the change is still a mismatch
 * next time rather than a silently swallowed one.
 *
 * **Threading.** All queries run on one private daemon worker thread, never on
 * the caller's and never on the session's reader thread (which is passed work
 * through a queue and returns immediately). [events] is a hot [Channel]-backed
 * [Flow] with an unbounded buffer: emission never blocks and never drops, so a
 * Compose collector may collect it on its own dispatcher. Collect it **once** —
 * like any channel, a second concurrent collector would take half the events.
 *
 * **Lifetime.** `start` may be called again with a new session after the old one
 * died: that re-subscribes and re-runs the status diff, which is exactly the
 * catch-up a reconnect needs (the diff sees everything that happened during the
 * gap). It only ever *subscribes*; deciding when to reconnect, with what backoff,
 * and whether the user is still on the screen is [ReconnectingSubscription]'s
 * business, driven by [ChainEvent.SessionLost]. [close] stops this manager; the
 * session's socket belongs to whoever opened it.
 *
 * It implements [SubscriptionEngine] — the seam the reconnect loop drives — with
 * no member of this class changed for it.
 */
class SubscriptionManager(
    private val store: SubscriptionStateStore,
    private val log: (String) -> Unit = {},
    /**
     * Passed to the row builder for a block header that could not be read while
     * dating a transaction: the same "reported, never swallowed" rule the scan
     * uses (see `buildTransactionRows`).
     */
    private val onTimestampFailure: (height: Int, error: Exception) -> Unit = { _, _ -> },
) : SubscriptionEngine {

    /** The buffer every event is written to; unbounded, so [emit] never blocks. */
    private val channel: Channel<ChainEvent> = Channel(Channel.UNLIMITED)

    /**
     * The manager's event stream. Collect it once, from whatever dispatcher the
     * consumer likes; events emitted before collection are buffered, not lost.
     */
    override val events: Flow<ChainEvent> = channel.receiveAsFlow()

    /** Everything that guards the mutable state below. Held only for bookkeeping, never across a query. */
    private val lock = Any()

    /** Keys waiting to be worked on. A key is a scripthash, or one of the two job keys. */
    private val jobs = LinkedBlockingQueue<String>()

    /** Queued *and* in-flight keys, so a repeat notification is dropped rather than queued. */
    private val pending = HashSet<String>()

    /** Keys being worked on right now. */
    private val inFlight = HashSet<String>()

    /** Keys notified while they were already in flight: one follow-up refresh each, at most. */
    private val deferred = HashSet<String>()

    /** The subscribed addresses, by scripthash, so a notification can be turned into a merge. */
    private val known = HashMap<String, ScannedAddress>()

    /** The last seen status per scripthash. A null value is a real answer (see [SubscriptionStateStore]). */
    private val statuses = HashMap<String, String?>()

    /**
     * A status hash a notification (or the initial diff) reported, waiting for
     * the refresh that acts on it. Committed to [statuses] only on success.
     */
    private val changed = HashMap<String, String?>()

    /** Each used address's own `get_balance` answer — see the class doc on the balance. */
    private val balances = HashMap<String, ScripthashBalance>()

    /** True between work being queued and the queue settling; what makes [ChainEvent.Resynced] fire once. */
    private var busy = false

    /** No session work may happen: the session died, or [close] ran. */
    private var stopped = false

    /** [close] ran: this manager is finished for good. */
    private var closed = false

    @Volatile
    private var attached: ChainSubscriptionSession? = null

    /** Bumped by [start]; a refresh only applies its result to the run it belongs to. */
    private val generation = AtomicInteger(0)

    @Volatile
    private var current: AccountSnapshot = AccountSnapshot.EMPTY

    /** The account state as the manager last left it. Updated before [ChainEvent.Resynced] is emitted. */
    override val snapshot: AccountSnapshot get() = current

    /** The one thread that runs every query. Daemon: it never keeps the process alive. */
    private val worker = Thread({ workerLoop() }, "electrum-subscriptions").apply {
        isDaemon = true
        start()
    }

    // ---- lifecycle ---------------------------------------------------------

    /**
     * Subscribes to every address in [snapshot] — the used ones *and* the cached
     * unused lookahead, because a payment into an address we have never touched
     * is precisely the push we must not miss — subscribes to blocks, and diffs
     * the status the server reports against the persisted ones.
     *
     * Returns immediately: the subscribing itself happens on the manager's worker
     * thread, and everything it finds arrives through [events] (an
     * [ChainEvent.AddressChanged] per mismatch, then [ChainEvent.Resynced] when
     * the queued refreshes have settled).
     *
     * Calling it again with a new session re-subscribes and re-diffs against the
     * last-seen statuses — the reconnect catch-up.
     */
    override fun start(session: ChainSubscriptionSession, snapshot: AccountSnapshot) {
        val run = generation.incrementAndGet()
        // Read outside the lock: the store may touch disk, and nothing here needs
        // to hold the bookkeeping lock across it.
        val persisted = store.loadStatusHashes()
        synchronized(lock) {
            check(!closed) { "this subscription manager has been closed" }
            attached = session
            current = snapshot
            known.clear()
            snapshot.addresses.forEach { known[it.scripthash] = it }
            balances.clear()
            // The last-seen statuses survive a re-start on purpose: they are what
            // the diff is against, and a reconnect must catch the gap.
            statuses.clear()
            statuses.putAll(persisted)
            pending.clear()
            deferred.clear()
            inFlight.clear()
            changed.clear()
            stopped = false
            busy = false
        }
        session.onNotification = { method, params -> onNotification(run, method, params) }
        session.onClosed = { error -> onClosed(run, error) }
        jobs.clear()
        enqueue(JOB_SUBSCRIBE)
    }

    /**
     * Adopts [snapshot] as the account state to merge into, and forgets the
     * per-address balances.
     *
     * The owner of the incremental resync calls this after a rescan: the rescan
     * has just produced fresher data than anything this manager holds, and two
     * writers to one snapshot must not fight. Dropping the balance memory is what
     * keeps the balance the sum of `get_balance` over the used addresses even
     * when the rescan changed an address this manager had not noticed.
     */
    override fun updateSnapshot(snapshot: AccountSnapshot) {
        synchronized(lock) {
            if (closed) return
            current = snapshot
            known.clear()
            snapshot.addresses.forEach { known[it.scripthash] = it }
            balances.clear()
        }
    }

    /**
     * Stops the manager: no further queries, no further events. Idempotent.
     *
     * The session's socket is deliberately **not** closed here — whoever opened
     * it owns it — but its callbacks are detached, so a session that outlives
     * this manager cannot call back into it.
     */
    override fun close() {
        val session = synchronized(lock) {
            if (closed) return
            closed = true
            stopped = true
            val currentSession = attached
            attached = null
            pending.clear()
            deferred.clear()
            inFlight.clear()
            busy = false
            currentSession
        }
        jobs.clear()
        session?.onNotification = { _, _ -> }
        session?.onClosed = { }
        worker.interrupt()
        channel.close()
    }

    // ---- the worker --------------------------------------------------------

    private fun workerLoop() {
        while (true) {
            val key = try {
                jobs.take()
            } catch (interrupted: InterruptedException) {
                return
            }
            try {
                runJob(key)
            } catch (interrupted: InterruptedException) {
                return
            } catch (failure: Throwable) {
                // One address (or one block) failing must not take the manager
                // down: a dead session reports itself through onClosed, and
                // anything else is that address's problem.
                log("subscription job '$key' failed: ${failure.message}")
            } finally {
                finishJob(key)
            }
        }
    }

    private fun runJob(key: String) {
        val state = synchronized(lock) {
            if (closed || stopped) return
            inFlight.add(key)
            attached to generation.get()
        }
        val session = state.first ?: return
        val run = state.second
        when (key) {
            JOB_SUBSCRIBE -> subscribeAndDiff(session, run)
            JOB_ROWS -> recomputeRows(session, run)
            else -> refreshAddress(key, session, run)
        }
    }

    private fun finishJob(key: String) {
        var followedUp = false
        var settled = false
        synchronized(lock) {
            inFlight.remove(key)
            pending.remove(key)
            if (deferred.remove(key)) {
                // Something was notified while this key was in flight: exactly one
                // follow-up, whatever has piled up meanwhile.
                pending.add(key)
                followedUp = true
            }
            settled = busy && pending.isEmpty() && deferred.isEmpty() && inFlight.isEmpty() &&
                !closed && !stopped
            if (settled) busy = false
        }
        if (followedUp) jobs.put(key)
        if (settled) emit(ChainEvent.Resynced)
    }

    /**
     * Queues one key, collapsing repeats: a key already in flight is remembered
     * as one follow-up, a key already queued is left alone.
     */
    private fun enqueue(key: String) {
        synchronized(lock) {
            if (closed || stopped) return
            if (key in inFlight) {
                deferred.add(key)
                return
            }
            if (!pending.add(key)) return
            busy = true
        }
        jobs.put(key)
    }

    // ---- subscribing and the initial diff ----------------------------------

    /**
     * The one path both a first start and a post-reconnect start take: subscribe
     * to everything, then compare what the server says with what we last saw.
     */
    private fun subscribeAndDiff(session: ChainSubscriptionSession, run: Int) {
        val addresses = current.addresses
        val fresh = LinkedHashMap<String, String?>(addresses.size)
        for (address in addresses) {
            if (session.isClosed || run != generation.get()) break
            fresh[address.scripthash] = try {
                session.subscribeScripthash(address.scripthash)
            } catch (failure: Exception) {
                // One address's subscription failing must not lose the others;
                // a dead session fails every call and is reported through onClosed.
                log("could not subscribe to ${address.scripthash}: ${failure.message}")
                continue
            }
        }
        try {
            if (!session.isClosed) session.subscribeHeaders()
        } catch (failure: Exception) {
            log("could not subscribe to block headers: ${failure.message}")
        }
        if (run != generation.get()) return

        // The catch-up itself: every address whose status the server reports
        // differently from the one we last saw has changed since then — while the
        // app was closed, or while the connection was down — and is refreshed
        // exactly like a live notification would have refreshed it.
        val mismatches = ArrayList<Pair<String, String?>>()
        synchronized(lock) {
            for ((scripthash, status) in fresh) {
                // No record at all means this address has never been subscribed:
                // there is nothing to have missed, and the snapshot was built by
                // a scan that is fresher than any status we could compare with.
                val changedSinceLastSeen = statuses.containsKey(scripthash) && statuses[scripthash] != status
                when {
                    !changedSinceLastSeen -> statuses[scripthash] = status
                    // A live notification may have arrived while the subscribes ran,
                    // and its status is newer than this one: that notification
                    // already reported the address and queued its refresh, so this
                    // diff neither overwrites its status nor reports it twice.
                    changed.containsKey(scripthash) -> Unit
                    else -> {
                        changed[scripthash] = status
                        mismatches += scripthash to status
                    }
                }
            }
        }
        persistStatuses()
        mismatches.forEach { (scripthash, status) ->
            emit(ChainEvent.AddressChanged(scripthash, status))
            enqueue(scripthash)
        }
    }

    // ---- notifications -----------------------------------------------------

    /**
     * Runs on the session's reader thread: parse, remember, queue, return. No
     * query is issued here and nothing is persisted here.
     */
    private fun onNotification(run: Int, method: String, params: Any?) {
        if (run != generation.get()) return
        when (method) {
            METHOD_SCRIPTHASH -> {
                val list = params as? List<*> ?: return
                val scripthash = list.getOrNull(0) as? String ?: return
                // A null status is Fulcrum's answer for an address it has never
                // seen a transaction for; it is an answer, not a missing one.
                val status = list.getOrNull(1) as? String
                addressChanged(scripthash, status)
            }
            METHOD_HEADERS -> {
                val tip = tipOf(params) ?: return
                emit(ChainEvent.NewBlock(tip))
                enqueue(JOB_ROWS)
            }
            else -> log("ignoring notification '$method'")
        }
    }

    private fun addressChanged(scripthash: String, status: String?) {
        val tracked = synchronized(lock) {
            if (closed || stopped) return
            if (scripthash !in known) {
                log("notification for an address outside the subscribed set: $scripthash")
                return
            }
            changed[scripthash] = status
            true
        }
        if (!tracked) return
        emit(ChainEvent.AddressChanged(scripthash, status))
        enqueue(scripthash)
    }

    /**
     * The session's one terminal failure. Report it, stop the queue, and touch
     * nothing further on the connection — reconnecting is the caller's policy
     * (see the class doc).
     */
    private fun onClosed(run: Int, error: Throwable) {
        val session = synchronized(lock) {
            if (closed || stopped || run != generation.get()) return
            stopped = true
            val currentSession = attached
            attached = null
            pending.clear()
            deferred.clear()
            inFlight.clear()
            busy = false
            currentSession
        }
        jobs.clear()
        session?.onNotification = { _, _ -> }
        session?.onClosed = { }
        emit(ChainEvent.SessionLost(error))
    }

    // ---- targeted refresh --------------------------------------------------

    /**
     * One address's data, and nothing else's: its history, and — only if it has
     * any — its balance and its UTXOs. Everything derived is then rebuilt the
     * same way the scan builds it, from the answers the scan's own helpers
     * work on ([aggregateTransactions], `buildTransactionRows`).
     */
    private fun refreshAddress(scripthash: String, session: ChainSubscriptionSession, run: Int) {
        val before = current
        before.addresses.firstOrNull { it.scripthash == scripthash } ?: return

        // The status this refresh is acting on. A notification that arrives while
        // we are on the wire replaces it, and that newer one is left for the
        // follow-up refresh rather than being committed against this data.
        val (actingOn, notified) = synchronized(lock) {
            if (changed.containsKey(scripthash)) changed[scripthash] to true else null to false
        }

        val history = session.getHistory(scripthash)
        val used = history.isNotEmpty()
        val balance = if (used) session.getBalance(scripthash) else ScripthashBalance.ZERO
        val utxos = if (used) session.listUnspent(scripthash) else emptyList()
        if (run != generation.get()) return

        // Merge into the *latest* snapshot, not into the one this refresh started
        // from: a rescan (through updateSnapshot) may have replaced it while we
        // were on the wire, and its other addresses' data is newer than ours.
        val latest = current
        val address = latest.addresses.firstOrNull { it.scripthash == scripthash } ?: return
        val updated = address.copy(used = used, history = history)
        synchronized(lock) {
            balances[scripthash] = balance
            known[scripthash] = updated
        }
        val merged = merge(latest, updated, utxos, session)
        if (run != generation.get()) return

        synchronized(lock) {
            current = merged
            if (notified && changed[scripthash] == actingOn) {
                // Commit the status now that the data it describes is in place.
                changed.remove(scripthash)
                statuses[scripthash] = actingOn
            }
        }
        persistStatuses()
        persistSnapshot(merged)
    }

    /**
     * Rebuilds the whole snapshot around one refreshed address: the address row,
     * the UTXO list, the balance and the transaction rows.
     *
     * One implementation of "what does this account look like" for the live path,
     * so a pushed payment can never produce a different balance or a differently
     * ordered UTXO list than a scan of the same data.
     */
    private fun merge(
        before: AccountSnapshot,
        updated: ScannedAddress,
        utxos: List<ChainUtxo>,
        session: ChainSubscriptionSession,
    ): AccountSnapshot {
        val receive = if (updated.chain == AddressChain.RECEIVE) before.receive.replacing(updated) else before.receive
        val change = if (updated.chain == AddressChain.CHANGE) before.change.replacing(updated) else before.change
        val base = before.copy(receive = receive, change = change)

        // The UTXO list is rebuilt in address order (receive chain first), the way
        // the scan produces it, so a live update cannot reorder the UTXO screen.
        val byAddress = base.utxos
            .filterNot { it.chain == updated.chain && it.addressIndex == updated.index }
            .groupBy { it.chain to it.addressIndex }
            .toMutableMap()
        byAddress[updated.chain to updated.index] = utxos.map { utxo ->
            ScannedUtxo(utxo = utxo, chain = updated.chain, addressIndex = updated.index)
        }
        val orderedUtxos = base.usedAddresses.flatMap { byAddress[it.chain to it.index] ?: emptyList() }

        return base.copy(
            utxos = orderedUtxos,
            balance = totalBalance(base.usedAddresses, session),
            transactions = buildRows(base, session),
        )
    }

    /**
     * The balance, exactly as the scan computes it: the sum of `get_balance` over
     * the used addresses. An address whose answer this manager has not read yet
     * is read once and remembered (see the class doc), which is what keeps every
     * later refresh to the single address that changed.
     */
    private fun totalBalance(used: List<ScannedAddress>, session: ChainSubscriptionSession): ScripthashBalance {
        var confirmed = 0L
        var unconfirmed = 0L
        for (address in used) {
            val balance = synchronized(lock) { balances[address.scripthash] }
                ?: session.getBalance(address.scripthash).also { fetched ->
                    synchronized(lock) { balances[address.scripthash] = fetched }
                }
            confirmed += balance.confirmedSatoshis
            unconfirmed += balance.unconfirmedSatoshis
        }
        return ScripthashBalance(confirmedSatoshis = confirmed, unconfirmedSatoshis = unconfirmed)
    }

    /** The display rows, built by the scan's own row builder (never a second implementation). */
    private fun buildRows(snapshot: AccountSnapshot, session: ChainSubscriptionSession): List<TransactionRow> =
        buildTransactionRows(
            transactions = aggregateTransactions(snapshot.addresses),
            addresses = snapshot.addresses,
            query = session,
            cachedRows = snapshot.transactions.associateBy { it.txHash },
            onTimestampFailure = onTimestampFailure,
        )

    /**
     * A new block: the confirmations of every mined transaction are counted from
     * the new tip, and an unconfirmed row is re-read because it may have been
     * mined — the same reuse rule the incremental resync applies
     * (`buildTransactionRows` reuses a cached row only while it is confirmed at
     * the same height, and re-fetches everything else).
     */
    private fun recomputeRows(session: ChainSubscriptionSession, run: Int) {
        val before = current
        val rows = buildRows(before, session)
        if (run != generation.get()) return
        // As with a targeted refresh: apply to whatever the snapshot is now.
        val merged = current.copy(transactions = rows)
        synchronized(lock) { current = merged }
        persistSnapshot(merged)
    }

    // ---- persistence -------------------------------------------------------

    private fun persistStatuses() {
        val copy = synchronized(lock) { HashMap(statuses) }
        try {
            store.saveStatusHashes(copy)
        } catch (failure: Throwable) {
            log("could not persist the status hashes: ${failure.message}")
        }
    }

    private fun persistSnapshot(snapshot: AccountSnapshot) {
        try {
            store.saveSnapshot(snapshot)
        } catch (failure: Throwable) {
            log("could not persist the account snapshot: ${failure.message}")
        }
    }

    // ---- small helpers -----------------------------------------------------

    private fun emit(event: ChainEvent) {
        channel.trySend(event)
    }

    private fun List<ScannedAddress>.replacing(address: ScannedAddress): List<ScannedAddress> =
        map { if (it.index == address.index) address else it }

    /** The tip out of a `blockchain.headers.subscribe` notification's params. */
    private fun tipOf(params: Any?): ChainTip? {
        val fields = when (params) {
            is List<*> -> params.firstOrNull() as? Map<*, *> ?: return null
            is Map<*, *> -> params
            else -> return null
        }
        val height = (fields["height"] as? Number)?.toInt() ?: return null
        return ChainTip(height = height, hex = fields["hex"] as? String ?: "")
    }

    companion object {
        /** `blockchain.scripthash.subscribe`: the notification that carries a status hash. */
        const val METHOD_SCRIPTHASH: String = "blockchain.scripthash.subscribe"

        /** `blockchain.headers.subscribe`: the notification that carries a new tip. */
        const val METHOD_HEADERS: String = "blockchain.headers.subscribe"

        /** The internal job key for "subscribe to everything and diff" (never a scripthash: those are hex). */
        private const val JOB_SUBSCRIBE: String = "job:subscribe"

        /** The internal job key for "recompute the rows against the new tip". */
        private const val JOB_ROWS: String = "job:rows"
    }
}
