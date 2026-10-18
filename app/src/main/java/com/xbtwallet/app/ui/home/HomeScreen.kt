package com.xbtwallet.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.data.KeyValueStore
import com.xbtwallet.app.data.ServerSettings
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerStore
import com.xbtwallet.app.electrum.ElectrumClient
import com.xbtwallet.app.electrum.ElectrumProtocol
import com.xbtwallet.app.electrum.ElectrumServer
import com.xbtwallet.app.ui.theme.XbtSuccessDark
import com.xbtwallet.app.ui.theme.XbtWalletTheme
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** How the server indicator under the home screen's Server button reads. */
enum class ServerStatus {
    /** No endpoint saved yet — the screen shows the "connect your own server" hint. */
    NOT_CONFIGURED,

    /** A check is in flight and there is no answer to show yet. */
    CHECKING,

    /** The last check got an answer from the server. */
    CONNECTED,

    /** The last check could not reach the server. */
    DISCONNECTED,
}

/**
 * State behind the Home screen: the saved signers and whether the private
 * Electrum server is reachable.
 *
 * This is the home screen's whole state machine, and it is deliberately plain
 * Kotlin: [SignerStore] and [ServerSettings] own persistence, this class owns
 * the snapshot Compose observes, and [HomeScreen] only renders. That split keeps
 * the screen a pure function of the state (easy to preview) and keeps the
 * "how many signers" and "is the server up" rules testable without a UI.
 *
 * There is deliberately **no active-signer selection** here: signers are just a
 * list, and the rules that act on one signer live on that signer's own screen.
 *
 * Nothing writes either store except through this class, so the in-memory
 * snapshot cannot drift from what is on disk.
 *
 * The server check itself lives in [ElectrumClient] and is Android-free; the
 * only Android involvement is the caller that starts and stops
 * [runHeartbeat] while the home screen is on display (see `XbtWalletApp`).
 *
 * @param signerStore persistence for the signer list.
 * @param serverSettings read access to the saved Electrum endpoint.
 * @param ping one liveness check against an endpoint; injected so the rules
 *   above can be tested on the JVM without a socket.
 */
@Stable
class HomeScreenState(
    private val signerStore: SignerStore,
    private val serverSettings: ServerSettings,
    private val ping: suspend (ElectrumServer) -> Boolean = { server ->
        // The heartbeat must never write to the Server screen's log, so the
        // client is built with a no-op logger.
        ElectrumClient(server = server, log = {}).ping()
    },
) {

    /** Every saved signer, oldest first. */
    var signers by mutableStateOf(signerStore.list())
        private set

    /**
     * Re-reads the store. Called after a signer is added or removed on another
     * screen, so this screen reflects writes made elsewhere.
     */
    fun refresh() {
        signers = signerStore.list()
    }

    /**
     * Whether the saved server answered the last check.
     *
     * Seeded from the store rather than left at a neutral value, so the first
     * frame after the app starts already reads correctly: nothing saved shows
     * the helper hint, and a saved endpoint shows "Checking…" while the first
     * heartbeat runs — never a red "disconnected" before anything was tried.
     */
    var serverStatus by mutableStateOf(
        if (configuredServer() == null) ServerStatus.NOT_CONFIGURED else ServerStatus.CHECKING,
    )
        private set

    /** The saved endpoint as `host:port` for the status line, or null when none is saved. */
    val serverLabel: String?
        get() = configuredServer()?.let { "${it.host}:${it.port}" }

    /**
     * Identity of the saved endpoint. The home screen keys its heartbeat on
     * this, so editing the server on the Server screen and coming back re-checks
     * with the new settings instead of waiting out the old loop's next tick.
     */
    val serverKey: String
        get() = configuredServer()?.let { "${it.host}:${it.port}:${it.protocol.name}" }.orEmpty()

    /**
     * Runs one check and reports the result.
     *
     * [showChecking] is true for the first check of a screen visit: the neutral
     * "Checking…" replaces the previous answer, which is what stops the
     * indicator flashing red before the first reply. Later heartbeats pass
     * `false` and keep the last answer on screen while the next check is in
     * flight, so a 60-second poll does not make the label blink.
     */
    suspend fun checkServerStatus(showChecking: Boolean = true): ServerStatus {
        val server = configuredServer()
        if (server == null) {
            serverStatus = ServerStatus.NOT_CONFIGURED
            return serverStatus
        }
        if (showChecking) serverStatus = ServerStatus.CHECKING
        serverStatus = if (ping(server)) ServerStatus.CONNECTED else ServerStatus.DISCONNECTED
        return serverStatus
    }

    /**
     * The heartbeat: one check immediately, then another every [intervalMillis]
     * until the caller cancels this coroutine.
     *
     * The caller is the composable that shows the home screen (`XbtWalletApp`),
     * so the loop stops when the user leaves the screen or the process goes to
     * the background. There is no service, no wake lock and no new permission
     * behind this — deliberately, for a watch-only app.
     */
    suspend fun runHeartbeat(intervalMillis: Long = HEARTBEAT_INTERVAL_MILLIS) {
        var first = true
        while (currentCoroutineContext().isActive) {
            checkServerStatus(showChecking = first)
            first = false
            delay(intervalMillis)
        }
    }

    /** The saved endpoint, or null when the user has not entered one yet. */
    private fun configuredServer(): ElectrumServer? = serverSettings.load().takeIf { it.host.isNotBlank() }

    companion object {
        /** Gap between heartbeat checks while the home screen is on display. */
        const val HEARTBEAT_INTERVAL_MILLIS: Long = 60_000L
    }
}

/**
 * Remembers a [HomeScreenState] for the app's lifetime.
 *
 * Held at the navigation level rather than inside [HomeScreen] so it survives
 * leaving and returning to the screen — and so the Signers screen reads the same
 * list the home screen counts.
 */
@Composable
fun rememberHomeScreenState(
    signerStore: SignerStore,
    serverSettings: ServerSettings,
): HomeScreenState = remember(signerStore, serverSettings) {
    HomeScreenState(signerStore, serverSettings)
}

/**
 * Home screen — the app's hub, and its statement of what the app is: a
 * coordinator holding xpubs, not a wallet holding keys.
 *
 * Top to bottom: **Server** (private Electrum endpoint) with its live status
 * line, then the two things you do with signers — **Add signer** and
 * **Signers**. The old standalone "Import xpub", "Select active xpub" and
 * "Addresses" buttons are gone: xpubs are signers now, they live in a list
 * rather than behind an "active" selection, and everything that works on one
 * signer (addresses included) is reached by opening that signer. **Add signer**
 * now opens the signer chooser (watch-only xpub live, hot wallet disabled) and
 * **Signers** the list of what is saved; a signer's own detail screen is the one
 * screen still to come.
 *
 * Under the Server button the screen shows the live connection status: the
 * "connect your own server" hint until an endpoint is saved, then a dot with
 * *Connected to host:port* / *Server disconnected* — fed by the heartbeat the
 * navigation layer runs while this screen is on display (see `XbtWalletApp`).
 *
 * The screen owns no state: it renders [state] and forwards taps.
 */
@Composable
fun HomeScreen(
    state: HomeScreenState,
    onOpenServer: () -> Unit,
    onAddSigner: () -> Unit,
    onOpenSigners: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 1. Server first: it is the one setup step that has nothing to do
            //    with keys, so the screen reads as "configure, then signers".
            OutlinedButton(
                onClick = onOpenServer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Server")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Live server status: the facts (configured? reachable?) are the
            // state's, this only renders them.
            ServerStatusLine(status = state.serverStatus, serverLabel = state.serverLabel)

            Spacer(modifier = Modifier.height(28.dp))

            // 2. Add a signer — the one way in.
            Button(
                onClick = onAddSigner,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add signer")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. The saved signers. Always tappable: the list screen is where an
            //    empty list explains itself.
            Button(
                onClick = onOpenSigners,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Signers")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when (state.signers.size) {
                    0 -> "No signer yet — add one to begin."
                    1 -> "1 signer."
                    else -> "${state.signers.size} signers."
                },
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "XBT / SeedSigner coordinator — watch-only",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The one line under the Server button: the helper hint before anything is
 * configured, otherwise a status dot and a short answer.
 *
 * Takes plain values rather than the state object so every state can be
 * previewed without a store or a network.
 */
@Composable
private fun ServerStatusLine(
    status: ServerStatus,
    serverLabel: String?,
    modifier: Modifier = Modifier,
) {
    val (color, label) = when (status) {
        ServerStatus.NOT_CONFIGURED ->
            MaterialTheme.colorScheme.onSurfaceVariant to "Connect to your own Electrum server"
        ServerStatus.CHECKING ->
            MaterialTheme.colorScheme.onSurfaceVariant to "Checking…"
        ServerStatus.CONNECTED ->
            XbtSuccessDark to "Connected to ${serverLabel.orEmpty()}"
        ServerStatus.DISCONNECTED ->
            MaterialTheme.colorScheme.error to "Server disconnected"
    }

    if (status == ServerStatus.NOT_CONFIGURED) {
        // Nothing saved yet: the original hint, unchanged and dot-free — there
        // is no connection state to report.
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = color,
            modifier = modifier,
        )
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = color, shape = CircleShape),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** In-memory storage, so the previews can show a populated home screen. */
private class PreviewKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

/** A host-less endpoint store, so a preview never tries to reach a server. */
private class PreviewServerSettings : ServerSettings {
    override fun load(): ElectrumServer = ElectrumServer(
        host = "",
        port = ElectrumProtocol.SSL.defaultPort,
        protocol = ElectrumProtocol.SSL,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun HomeScreenPreview() {
    val previewState = rememberHomeScreenState(
        remember { previewSignerStore(populated = true) },
        remember { PreviewServerSettings() },
    )
    XbtWalletTheme {
        HomeScreen(
            state = previewState,
            onOpenServer = {},
            onAddSigner = {},
            onOpenSigners = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun HomeScreenEmptyPreview() {
    val previewState = rememberHomeScreenState(
        remember { previewSignerStore(populated = false) },
        remember { PreviewServerSettings() },
    )
    XbtWalletTheme {
        HomeScreen(
            state = previewState,
            onOpenServer = {},
            onAddSigner = {},
            onOpenSigners = {},
        )
    }
}

/** All four server-status renderings side by side, without a state or a socket. */
@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun ServerStatusLinePreview() {
    XbtWalletTheme {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ServerStatusLine(status = ServerStatus.NOT_CONFIGURED, serverLabel = null)
            Spacer(modifier = Modifier.height(12.dp))
            ServerStatusLine(status = ServerStatus.CHECKING, serverLabel = "192.168.1.10:50002")
            Spacer(modifier = Modifier.height(12.dp))
            ServerStatusLine(status = ServerStatus.CONNECTED, serverLabel = "192.168.1.10:50002")
            Spacer(modifier = Modifier.height(12.dp))
            ServerStatusLine(status = ServerStatus.DISCONNECTED, serverLabel = null)
        }
    }
}

/** A throwaway store for the previews: two signers with deterministic ids. */
private fun previewSignerStore(populated: Boolean): SignerStore {
    var counter = 0
    val store = SignerStore(
        storage = PreviewKeyValueStore(),
        now = { 0L },
        newId = { "id-${++counter}" },
    )
    if (!populated) return store
    val vector =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868" +
            "RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
    store.add("Cold storage", vector)
    store.add("Savings", vector)
    return store
}
