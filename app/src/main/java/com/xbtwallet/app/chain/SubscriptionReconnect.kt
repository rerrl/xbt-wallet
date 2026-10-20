package com.xbtwallet.app.chain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlin.random.Random

/**
 * The live-updates engine, as the reconnect loop drives it: subscribe a
 * connection, report what the server pushes, and hand back the account state it
 * has merged into.
 *
 * This is `SubscriptionManager`, narrowed to the members the loop needs — and
 * *only* those, so a test can drive the loop with a double instead of a real
 * manager, a real worker thread and real sockets. `chain/` declares it, the
 * manager implements it, and the UI's own engine seam is a sub-interface of it
 * (`SignerSubscriptionEngine`), which is what lets the loop run against either
 * without either layer importing the other.
 */
interface SubscriptionEngine : AutoCloseable {

    /** The manager's event stream. Collected once at a time — never two collectors. */
    val events: Flow<ChainEvent>

    /** The account state as the manager last left it; current at [ChainEvent.Resynced]. */
    val snapshot: AccountSnapshot

    /**
     * Subscribes [session] to every address in [snapshot] and diffs what the
     * server reports against the statuses last seen — the same code path a live
     * notification takes, which is what makes a reconnect's gap a catch-up
     * rather than a rescan.
     *
     * Calling it again with a new session after the old one died is the whole
     * reconnect mechanism.
     */
    fun start(session: ChainSubscriptionSession, snapshot: AccountSnapshot)

    /** Adopts a snapshot produced elsewhere (a rescan), so two writers cannot fight. */
    fun updateSnapshot(snapshot: AccountSnapshot)
}

/**
 * How a dropped live connection is retried.
 *
 * **Exponential backoff with jitter.** Attempt *n* waits
 * `initialDelayMillis * 2^(n-1)`, capped at [maxDelayMillis], then multiplied by a
 * random factor in `[1 - jitterFraction, 1 + jitterFraction]`. The cap keeps a
 * dead server from being hammered; the jitter keeps two surfaces that happen to
 * reconnect at the same moment (the signer screen and a future one) from
 * retrying in lockstep, and keeps a Tor circuit that just failed from being
 * re-dialled on the same beat that broke it.
 *
 * **Give up, don't loop forever.** After [maxAttempts] consecutive failed
 * reconnects the loop stops and the caller falls back to the pull path (a
 * Refresh/Retry button, which is always there). Six attempts at the default
 * window means roughly two minutes of forgiving behaviour — long enough to ride
 * out a sleeping device or a circuit being rebuilt, short enough that a server
 * that is simply down is not re-dialled until the heat death of the universe.
 *
 * **A session that lived a while is a new episode.** The attempt counter is only
 * reset by a session that stayed connected for at least
 * [stableSessionMillis] — not by a reconnect that merely *opened* a socket and
 * then dropped it again. Without that rule a server that accepts every
 * connection and closes it immediately would be re-dialled forever, which is
 * exactly the reconnect storm the budget exists to prevent.
 *
 * [random] is injectable so the schedule is exactly reproducible in a test;
 * production uses `kotlin.random.Random` and never looks at it.
 */
class ReconnectPolicy(
    /** The wait before the first reconnect attempt (2s by default). */
    val initialDelayMillis: Long = 2_000L,
    /** The longest wait between attempts, before jitter (60s by default). */
    val maxDelayMillis: Long = 60_000L,
    /** How many consecutive failed reconnects are tolerated before giving up. */
    val maxAttempts: Int = 6,
    /** How much the wait may vary, as a fraction of the backoff: 0.2 = ±20%. */
    val jitterFraction: Double = 0.2,
    /** How long a connection must survive to count as a fresh episode, not a flap. */
    val stableSessionMillis: Long = 60_000L,
    /** The jitter source: `[0, 1)`, one draw per attempt. Injectable for tests. */
    private val random: () -> Double = { Random.nextDouble() },
) {

    init {
        require(initialDelayMillis > 0) { "the first reconnect wait must be positive" }
        require(maxDelayMillis >= initialDelayMillis) { "the cap cannot be below the first wait" }
        require(maxAttempts >= 1) { "there must be at least one reconnect attempt" }
        require(jitterFraction >= 0.0 && jitterFraction <= 1.0) { "jitter is a fraction of the wait" }
        require(stableSessionMillis >= 0) { "a session cannot be stable for a negative time" }
    }

    /**
     * How long to wait before reconnect attempt [attempt] (1-based): the
     * exponential backoff, capped, with jitter applied.
     */
    fun delayMillis(attempt: Int): Long {
        require(attempt >= 1) { "reconnect attempts are numbered from 1, got $attempt" }
        var base = initialDelayMillis
        repeat(attempt - 1) { base = minOf(base * 2, maxDelayMillis) }
        if (jitterFraction == 0.0) return base
        val factor = 1.0 - jitterFraction + 2.0 * jitterFraction * random()
        return (base * factor).toLong().coerceAtLeast(0L)
    }

    /** The whole schedule, in order — the attempts this policy allows. */
    val delaysMillis: List<Long> get() = (1..maxAttempts).map { delayMillis(it) }
}

/** Why a [ReconnectingSubscription.run] ended. */
sealed interface SubscriptionOutcome {

    /**
     * The reconnect budget was spent. The caller's fallback — the pull path,
     * with its own retry and the Refresh/Retry button — is what runs from here,
     * and nothing about this is an error the user has to see.
     *
     * [attempts] is how many reconnects failed in a row, [error] the failure that
     * ended the *last* session.
     */
    data class GivenUp(val attempts: Int, val error: Throwable) : SubscriptionOutcome

    /**
     * The event stream ended because the engine was closed — the caller tore the
     * subscription down (navigated away, switched signer, process death).
     */
    data object Stopped : SubscriptionOutcome
}

/**
 * Runs one live subscription for as long as it is wanted: the first session, and
 * then every reconnect, as a single cancellable loop.
 *
 * **One subscription, one retry loop.** A caller creates one of these per
 * subscription job and hands it one [SubscriptionEngine]; the loop reuses that
 * same engine for every reconnect (`start` with a new session, which re-runs the
 * subscribe-and-diff path). Nothing here builds a second manager, a second
 * worker thread or a second collector, so a dropped connection cannot turn into
 * two subscriptions, and cancelling the coroutine this runs in stops everything —
 * including a backoff it is currently sleeping out, because the wait is a
 * cancellable `delay`.
 *
 * **What a reconnect is.** [open] dials a fresh connection exactly the way the
 * first one was dialled — the same transport, and therefore the same Tor
 * bootstrap, whose probe-and-re-bootstrap-on-reuse is what heals a Tor circuit
 * that died with the socket (`AndroidTorBootstrap.start` does that per dial;
 * nothing here needs its own Tor handling). The new session is then handed to
 * `engine.start` with the engine's *current* snapshot, which subscribes every
 * address and diffs the server's status hashes against the ones last seen: an
 * address paid, spent or mined while the connection was down is a mismatch, and
 * a mismatch is refreshed through the same targeted-refresh path a live
 * notification takes. The gap and the live notification are one code path — no
 * polling, and nothing to reconcile afterwards.
 *
 * **What a give-up is.** When the budget is spent, [run] returns
 * [SubscriptionOutcome.GivenUp] and touches nothing else: the engine is left open
 * (the caller owns it), the dead session is already closed, and the caller's
 * pull path — which has been there all along — is what the signer screen falls
 * back to. Opening the screen again tries again with a fresh budget.
 *
 * **Threading.** [run] suspends; every member of the engine it calls is
 * non-blocking (the manager does its subscribing and querying on its own worker
 * thread). Collect the engine's events **only here** — it is a channel-backed
 * flow, and a second concurrent collector would take half the events.
 *
 * @param engine the live-updates engine this loop drives, reused for every
 *   reconnect.
 * @param open opens one long-lived connection to the same server. Called only
 *   for reconnects, with the attempt number (1-based) for a caller that wants to
 *   say so in a log line.
 * @param policy the backoff schedule, the attempt budget and the stable-session
 *   rule.
 * @param log where the loop's diagnostics go: it is deliberately chatty, because
 *   a silent reconnect that never succeeds is the one thing a bug report cannot
 *   reconstruct later.
 * @param backoff the wait between attempts. Defaults to a cancellable [delay];
 *   injectable so a test does not spend two minutes of real time on a policy it
 *   is asserting about.
 * @param onSession called with each connection the loop runs on — the one it was
 *   given and every replacement — so the owner of the socket can close the live
 *   one when the user navigates away.
 * @param onEvent every event that is *not* a session loss, for a caller that
 *   mirrors the snapshot. A [ChainEvent.SessionLost] is consumed here: the loop
 *   is what turns it into a reconnect, and the caller hears about it only when
 *   the budget is spent (through [SubscriptionOutcome.GivenUp]).
 * @param now the clock the stable-session rule reads. Injectable for tests.
 */
class ReconnectingSubscription(
    private val engine: SubscriptionEngine,
    private val open: suspend (attempt: Int) -> ChainSubscriptionSession,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val log: (String) -> Unit = {},
    private val backoff: suspend (Long) -> Unit = { delay(it) },
    private val onSession: (ChainSubscriptionSession) -> Unit = {},
    private val onEvent: (ChainEvent) -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Subscribes [session] to [snapshot] and keeps the subscription alive,
     * reconnecting with backoff until it is cancelled or the budget runs out.
     *
     * Returns only when the subscription is over: [SubscriptionOutcome.Stopped]
     * when the engine's stream ended (it was closed under us),
     * [SubscriptionOutcome.GivenUp] when reconnecting was given up on. A
     * cancelled coroutine (the user navigated away) throws
     * [CancellationException] instead — mid-backoff included — after closing the
     * connection it was running on.
     */
    suspend fun run(session: ChainSubscriptionSession, snapshot: AccountSnapshot): SubscriptionOutcome {
        var current: ChainSubscriptionSession? = session
        var connectedAtMillis = now()
        var attempts = 0
        try {
            engine.start(session, snapshot)
            onSession(session)

            while (true) {
                // Collect until the connection dies. `takeWhile` is what ends the
                // collection when it does; `onEach` is what remembers why, since
                // the event itself is consumed by the filter.
                var lost: Throwable? = null
                engine.events
                    .onEach { event -> if (event is ChainEvent.SessionLost) lost = event.error }
                    .takeWhile { event -> event !is ChainEvent.SessionLost }
                    .collect { event -> onEvent(event) }

                val error = lost ?: return SubscriptionOutcome.Stopped

                // A connection that lived long enough was a working subscription
                // that happened to drop, not a flapping one: this is a new
                // episode, so the budget starts over.
                val livedMillis = now() - connectedAtMillis
                if (livedMillis >= policy.stableSessionMillis) {
                    if (attempts > 0) log("the connection had been up for ${livedMillis / 1000}s: reconnect budget restored")
                    attempts = 0
                }
                // The dead connection's socket is nobody's any more: close it, and
                // forget it, so the only session left to clean up is a live one.
                runCatching { current?.close() }
                current = null

                var recovered: ChainSubscriptionSession? = null
                while (recovered == null && attempts < policy.maxAttempts) {
                    attempts++
                    val waitMillis = policy.delayMillis(attempts)
                    log(
                        "the live connection dropped (${error.message}); reconnecting in ${waitMillis}ms " +
                            "(attempt $attempts of ${policy.maxAttempts})",
                    )
                    // The one suspension point a teardown has to interrupt: a
                    // cancelled coroutine stops here and never dials.
                    backoff(waitMillis)

                    val next = try {
                        open(attempts)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        log("reconnect attempt $attempts could not open a connection: ${failure.message}")
                        null
                    } ?: continue

                    val resubscribed = try {
                        // The engine's own snapshot is what a reconnect is diffed
                        // against — it is the state every notification so far has
                        // been merged into, and a rescan that landed meanwhile
                        // reached it through updateSnapshot.
                        engine.start(next, engine.snapshot)
                        true
                    } catch (cancellation: CancellationException) {
                        runCatching { next.close() }
                        throw cancellation
                    } catch (failure: Exception) {
                        log("reconnect attempt $attempts could not resubscribe: ${failure.message}")
                        runCatching { next.close() }
                        false
                    }
                    if (resubscribed) recovered = next
                }

                val replacement = recovered ?: run {
                    log("giving up on the live connection after $attempts reconnects: staying on the pull path")
                    return SubscriptionOutcome.GivenUp(attempts, error)
                }
                current = replacement
                connectedAtMillis = now()
                onSession(replacement)
            }
        } finally {
            // Whatever ends this loop — the budget, a teardown, the engine being
            // closed — a connection it is still running on is not left open. Every
            // session here is idempotent about being closed.
            runCatching { current?.close() }
        }
    }
}
