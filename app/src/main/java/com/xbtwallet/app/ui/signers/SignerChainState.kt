package com.xbtwallet.app.ui.signers

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xbtwallet.app.chain.AccountScanner
import com.xbtwallet.app.chain.AccountSnapshot
import com.xbtwallet.app.crypto.ExtendedPublicKey
import com.xbtwallet.app.data.ServerSettings
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerType
import com.xbtwallet.app.data.SnapshotStore
import com.xbtwallet.app.electrum.ElectrumClient
import com.xbtwallet.app.electrum.ElectrumException
import com.xbtwallet.app.electrum.ElectrumServer
import kotlinx.coroutines.CancellationException
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
 * **Threading.** [load] and [reload] are `suspend` and are called from a
 * `LaunchedEffect`; the scan itself runs on the IO dispatcher inside
 * [ElectrumClient.withChainSession]. Nothing here touches the UI thread.
 *
 * **Testability.** The scan is injected, so this class can be exercised on the
 * JVM — including the failure paths — with no server, no socket and no Android.
 *
 * @param signer the signer being shown. Read once: the navigation layer is what
 *   notices a signer being deleted, and sends the user Home instead.
 * @param serverSettings read access to the saved Electrum endpoint.
 * @param snapshotStore the signer's persisted last scan, or null (as in tests,
 *   and any caller that wants the cold behaviour). When present it seeds
 *   [snapshot] and receives every completed scan.
 * @param scan one whole-account scan; defaults to a real one over the socket.
 *   Last so a test can pass it as a trailing lambda.
 */
@Stable
class SignerChainState(
    val signer: Signer,
    private val serverSettings: ServerSettings,
    private val snapshotStore: SnapshotStore? = null,
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
     * One visit's worth of work. Distinct from [phase] on purpose: with a cached
     * snapshot the phase can already be [ChainPhase.READY] before [load] runs,
     * so "has this state started?" is its own flag rather than a phase check.
     */
    private var started = false

    init {
        // Seed from the last saved scan, if there is one. The screen is READY
        // immediately; [load] then refreshes that snapshot in the background.
        snapshotStore?.load(signer.id)?.snapshot?.let { cached ->
            snapshot = cached
            phase = ChainPhase.READY
        }
    }

    /**
     * Runs the first scan for this signer, once.
     *
     * Called from a composable-scoped `LaunchedEffect`, which may re-run when
     * the screen recomposes or the signer changes; a state that has already
     * started does nothing, so one visit costs one scan. When a snapshot was
     * seeded at construction that scan is an incremental rescan of it.
     */
    suspend fun load() {
        if (started) return
        started = true
        scanNow(snapshot)
    }

    /** Re-runs the scan, whatever the current phase — the Refresh/Retry button. */
    suspend fun reload() = scanNow(snapshot)

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
            errorMessage = "This signer's key could not be read. Delete it and import the xpub again."
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
            val result = scan(server, scanner, null) { checked -> scannedAddressCount = checked }
            snapshot = result
            phase = ChainPhase.READY
            snapshotStore?.save(signer.id, result)
        } catch (cancellation: CancellationException) {
            // Leaving the screen while the scan is in flight must stay a
            // cancellation, not become an error message about the network.
            throw cancellation
        } catch (error: Exception) {
            snapshot = null
            errorMessage = describe(server, error)
            phase = ChainPhase.ERROR
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
            val result = scan(server, scanner, cached) { checked -> scannedAddressCount = checked }
            snapshot = result
            phase = ChainPhase.READY
            snapshotStore?.save(signer.id, result)
        } catch (cancellation: CancellationException) {
            // Cancelling (leaving the screen) is not a failure: the cached data
            // is untouched and a later visit rescans.
            throw cancellation
        } catch (error: Exception) {
            // Keep the cached snapshot: a refresh that failed must not blank a
            // screen that was showing real numbers a moment ago.
            syncFailed = true
            phase = ChainPhase.READY
        } finally {
            isSyncing = false
        }
    }

    /** The saved endpoint, or null when the user has not entered one. */
    private fun configuredServer(): ElectrumServer? = serverSettings.load().takeIf { it.host.isNotBlank() }

    /**
     * Turns a transport failure into a sentence worth reading.
     *
     * These are the same failures the Server screen's connection test reports;
     * the wording is repeated here rather than shared because this screen needs
     * it as a *message*, not as log lines.
     */
    private fun describe(server: ElectrumServer, error: Exception): String {
        val where = "${server.host}:${server.port}"
        val reason = when (error) {
            is UnknownHostException -> "that host name could not be resolved"
            is SocketTimeoutException -> "the server did not answer in time"
            is ConnectException -> "the connection was refused"
            is ElectrumException -> "the server rejected the request (${error.message})"
            is IOException -> "the connection failed (${error.message ?: "no details"})"
            else -> error.message ?: error::class.java.simpleName
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
 */
private suspend fun scanOverElectrum(
    server: ElectrumServer,
    scanner: AccountScanner,
    cached: AccountSnapshot?,
    onScanned: (Int) -> Unit,
): AccountSnapshot =
    ElectrumClient(server = server, log = {}).withChainSession { query ->
        if (cached == null) scanner.scan(query, onScanned) else scanner.rescan(query, cached, onScanned)
    }
