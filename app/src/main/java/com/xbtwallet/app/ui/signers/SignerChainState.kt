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
 * The screen has three states that matter to a user: *we are asking your server*
 * ([LOADING]), *here is what it said* ([READY]), and *we could not ask* — either
 * because no server is configured ([NO_SERVER], the "connect a server" case) or
 * because the one that is configured did not answer ([ERROR]).
 *
 * [IDLE] is the brief instant before the scan starts. It renders as loading, so
 * there is never a frame showing a made-up zero balance.
 *
 * **Threading.** [load] and [reload] are `suspend` and are called from a
 * `LaunchedEffect`; the scan itself runs on the IO dispatcher inside
 * [ElectrumClient.withChainSession]. Nothing here touches the UI thread, and the
 * only thing that happens while the socket is busy is a counter ticking up.
 *
 * **Testability.** The scan is injected, so this class can be exercised on the
 * JVM — including the failure paths — with no server, no socket and no Android.
 *
 * @param signer the signer being shown. Read once: the navigation layer is what
 *   notices a signer being deleted, and sends the user Home instead.
 * @param serverSettings read access to the saved Electrum endpoint.
 * @param scan one whole-account scan; defaults to a real one over the socket.
 */
@Stable
class SignerChainState(
    val signer: Signer,
    private val serverSettings: ServerSettings,
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

    /** The last completed scan, or null while none has completed. */
    var snapshot by mutableStateOf<AccountSnapshot?>(null)
        private set

    /** A human-readable reason, set only in [ChainPhase.ERROR]. */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /**
     * Addresses the server has answered about so far in the running scan.
     *
     * Bound (40 for an empty account: one batch per chain), but visible: it is
     * what turns an apparently frozen "Loading…" into "checked 120 addresses".
     */
    var scannedAddressCount by mutableStateOf(0)
        private set

    /** The saved endpoint this scan will use, as `host:port`, or null when none is saved. */
    val serverLabel: String?
        get() = configuredServer()?.let { "${it.host}:${it.port}" }

    /**
     * Runs the first scan for this signer, once.
     *
     * Called from a composable-scoped `LaunchedEffect`, which may re-run when
     * the screen recomposes or the signer changes; a state that has already
     * scanned (or is scanning) does nothing, so one visit costs one scan.
     */
    suspend fun load() {
        if (phase != ChainPhase.IDLE) return
        scanNow()
    }

    /** Re-runs the scan, whatever the current phase — the Retry button. */
    suspend fun reload() = scanNow()

    private suspend fun scanNow() {
        val scanner = scanner
        if (scanner == null) {
            // The stored xpub is unreadable. There is nothing to scan and no
            // network to blame, so say exactly that.
            snapshot = null
            errorMessage = "This signer's key could not be read. Delete it and import the xpub again."
            phase = ChainPhase.ERROR
            return
        }

        val server = configuredServer()
        if (server == null) {
            // No endpoint saved: the "Connect a server" case, not a failure.
            snapshot = null
            errorMessage = null
            phase = ChainPhase.NO_SERVER
            return
        }

        phase = ChainPhase.LOADING
        errorMessage = null
        scannedAddressCount = 0
        try {
            val result = scan(server, scanner) { checked -> scannedAddressCount = checked }
            snapshot = result
            phase = ChainPhase.READY
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
    /** Nothing scanned yet; renders as loading. */
    IDLE,

    /** A scan is in flight. */
    LOADING,

    /** A scan completed; [SignerChainState.snapshot] is set. */
    READY,

    /** No Electrum endpoint is saved — the "connect a server" case. */
    NO_SERVER,

    /** A scan was attempted and failed; [SignerChainState.errorMessage] says why. */
    ERROR,
}

/**
 * One whole-account scan, as the state class sees it: a server, a scanner, and a
 * progress callback.
 *
 * A type alias rather than an interface so the production default is a plain
 * function reference (`::scanOverElectrum`) and a test's fake is a lambda.
 */
typealias ChainScan = suspend (ElectrumServer, AccountScanner, (Int) -> Unit) -> AccountSnapshot

/**
 * The real scan: open one connection to the user's server and run the
 * gap-limit walk over it.
 *
 * The client is built with a no-op logger — this is a background read, and the
 * Server screen's log is for connection tests, not for every balance refresh.
 */
private suspend fun scanOverElectrum(
    server: ElectrumServer,
    scanner: AccountScanner,
    onScanned: (Int) -> Unit,
): AccountSnapshot =
    ElectrumClient(server = server, log = {}).withChainSession { query ->
        scanner.scan(query, onScanned)
    }
