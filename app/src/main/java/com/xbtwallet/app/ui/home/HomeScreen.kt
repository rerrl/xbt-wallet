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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.data.KeyValueStore
import com.xbtwallet.app.data.ServerSettings
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerStore
import com.xbtwallet.app.data.SignerType
import com.xbtwallet.app.electrum.ElectrumClient
import com.xbtwallet.app.electrum.ElectrumProtocol
import com.xbtwallet.app.electrum.ElectrumRouting
import com.xbtwallet.app.electrum.ElectrumServer
import com.xbtwallet.app.electrum.TorGateway
import com.xbtwallet.app.electrum.TorPhase
import com.xbtwallet.app.electrum.TorStatus
import com.xbtwallet.app.ui.signers.SignerRow
import com.xbtwallet.app.ui.theme.XbtSuccessDark
import com.xbtwallet.app.ui.theme.XbtWalletTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

/** How the server indicator under the home screen's Server button reads. */
enum class ServerStatus {
    /** No endpoint saved yet — the screen shows the "connect your own server" hint. */
    NOT_CONFIGURED,

    /** The saved server is an onion and Tor is still bootstrapping. */
    TOR_STARTING,

    /** The saved server is an onion and Tor gave up; there is nothing to ping. */
    TOR_FAILED,

    /** A check is in flight and there is no answer to show yet. */
    CONNECTING,

    /** The last check got an answer from a clearnet server. */
    CONNECTED,

    /** The last check got an answer from a server reached through Tor. */
    CONNECTED_VIA_TOR,

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
 * "how many wallets" and "is the server up" rules testable without a UI.
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
 * @param torStatus the current bundled-Tor state, injected for the same reason:
 *   the onion branch reads it instead of dialling, so a JVM test can drive it.
 * @param now the wall clock, in epoch milliseconds, used to timestamp a
 *   completed answer so a revisit can tell a fresh one from a stale one.
 *   Injected so the staleness rule is testable without real waiting.
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
    private val torStatus: () -> TorStatus = { TorGateway.status() },
    private val now: () -> Long = System::currentTimeMillis,
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
     * What the indicator under the Server button should read **right now**.
     *
     * This is a derived value: every tick recomputes it from current facts (see
     * [applyDisplay]) rather than the act of dialling setting it. The seed below
     * covers the first frame before any tick has run — nothing saved shows the
     * helper hint, and a saved endpoint shows "Connecting…" rather than a red
     * "disconnected" that nothing justified.
     *
     * The state lives at the navigation level and is not rebuilt on a revisit,
     * so a remembered answer renders instantly on return (see [stampedAnswer]).
     */
    var serverStatus by mutableStateOf(
        if (configuredServer() == null) ServerStatus.NOT_CONFIGURED else ServerStatus.CONNECTING,
    )
        private set

    /**
     * How far Tor has got, in percent, while [serverStatus] is
     * [ServerStatus.TOR_STARTING]. Zero in every other state.
     */
    var torProgressPercent by mutableStateOf(0)
        private set

    /**
     * The last completed dial's answer, or null before any dial has answered.
     * Paired with [lastAnswerAtMillis] and [lastAnswerServerKey] so a revisit can
     * render it instantly and decide whether it is still fresh.
     */
    private var lastAnswerStatus: ServerStatus? = null

    /**
     * When the last answer arrived (epoch millis), or null if no dial has
     * completed. Only a completed dial counts: [ServerStatus.TOR_STARTING] and
     * [ServerStatus.TOR_FAILED] are derived short-circuits, not answers, so they
     * never make a remembered answer look fresh.
     */
    private var lastAnswerAtMillis: Long? = null

    /**
     * The [serverKey] the last answer belongs to. A changed endpoint invalidates
     * the remembered answer — it was about a different server.
     */
    private var lastAnswerServerKey: String? = null

    /**
     * The dial currently in flight, or null when none is. A dial runs as a
     * supervised child of the heartbeat, so the tick loop never blocks on it and
     * this slot is what keeps a second dial from starting on top of the first.
     */
    private var dialJob: Job? = null

    /**
     * Identity of the saved endpoint. The home screen keys its heartbeat on
     * this, so editing the server on the Server screen and coming back re-checks
     * with the new settings instead of waiting out the old loop's next tick.
     */
    val serverKey: String
        get() = configuredServer()?.let(::keyOf).orEmpty()

    /**
     * The heartbeat: a fixed [tickMillis] tick that recomputes the display from
     * current facts and, when a dial is due, starts one as a supervised child
     * coroutine.
     *
     * The loop itself **never blocks on a dial**. A Tor bootstrap can take a
     * minute, and a tick that waited on it would freeze the live "Tor starting…
     * N%" line on a stale "Connecting…" for the whole cold start — the regression
     * this loop exists to avoid. Because the dial is a child, a slow or wedged
     * one never stops the ticks behind it. [refreshOnVisible] runs before the
     * first tick on the same terms: it starts its dial as a child too.
     *
     * The caller is the composable that shows the home screen (`XbtWalletApp`),
     * so the loop stops when the user leaves the screen or the process goes to
     * the background. Cancelling it also cancels an in-flight dial: on an onion
     * server that resets Tor to [TorPhase.OFF] (its honest-cancellation rule), so
     * the next entry re-dials — but now with live progress shown the whole way,
     * which is the behaviour that was actually broken.
     *
     * There is no service, no wake lock and no new permission behind this —
     * deliberately, for a watch-only app.
     */
    suspend fun runHeartbeat(tickMillis: Long = TICK_INTERVAL_MILLIS) {
        supervisorScope {
            // 0. The entry probe: the loop is keyed on the screen being on
            //    display, so the moment it (re)starts is exactly the moment the
            //    home screen became visible again — returning from Server,
            //    Signers or a signer's detail screen, or coming back from the
            //    background. That is the instant the last verdict stops being
            //    trustworthy: the user may have just watched "Test connection"
            //    succeed on the Server screen, so a remembered red must not sit
            //    there until the next scheduled tick. One immediate check, no
            //    waiting.
            refreshOnVisible()
            while (currentCoroutineContext().isActive) {
                try {
                    applyDisplay()
                    if (shouldDial()) {
                        dialJob = launch { performDial() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A single failed tick must not kill the loop: the next tick
                    // recomputes the display and retries the dial when due.
                }
                delay(tickMillis)
            }
        }
    }

    /**
     * One-shot check: recompute the display and, when a dial is due, perform it
     * inline. This is the off-loop path (the preview branch in `XbtWalletApp`);
     * the heartbeat never calls it — it ticks and dials in a child instead.
     */
    suspend fun checkServerStatus(): ServerStatus {
        applyDisplay()
        if (shouldDial()) {
            performDial()
        }
        return serverStatus
    }

    /**
     * Recomputes [serverStatus] (and [torProgressPercent]) from the current
     * facts, in priority order:
     *
     * 1. nothing saved → [ServerStatus.NOT_CONFIGURED];
     * 2. onion && Tor bootstrapping → [ServerStatus.TOR_STARTING] with the live
     *    percentage;
     * 3. onion && Tor gave up → [ServerStatus.TOR_FAILED];
     * 4. a stamped answer for this endpoint → that answer;
     * 5. otherwise → [ServerStatus.CONNECTING] (whether or not a dial is in
     *    flight: the pre-dial instant and the in-flight stretch read the same).
     */
    private fun applyDisplay() {
        val server = configuredServer()
        if (server == null) {
            torProgressPercent = 0
            serverStatus = ServerStatus.NOT_CONFIGURED
            return
        }
        if (ElectrumRouting.isOnionHost(server.host)) {
            val tor = torStatus()
            when (tor.phase) {
                TorPhase.STARTING -> {
                    torProgressPercent = tor.progressPercent
                    serverStatus = ServerStatus.TOR_STARTING
                    return
                }
                TorPhase.FAILED -> {
                    torProgressPercent = tor.progressPercent
                    serverStatus = ServerStatus.TOR_FAILED
                    return
                }
                // OFF (nobody has started Tor yet) and READY both fall through to
                // the answer/Connecting branch: the dial starts Tor itself, so an
                // OFF status here is not a dead end.
                TorPhase.OFF, TorPhase.READY -> Unit
            }
        }
        torProgressPercent = 0
        serverStatus = stampedAnswer() ?: ServerStatus.CONNECTING
    }

    /**
     * The remembered answer, but only when it belongs to the current endpoint. A
     * changed endpoint invalidates it — the old answer was about a different
     * server, so the display goes back to "Connecting…" until the new one dials.
     */
    private fun stampedAnswer(): ServerStatus? {
        if (lastAnswerAtMillis == null) return null
        if (lastAnswerServerKey != serverKey) return null
        return lastAnswerStatus
    }

    /**
     * The one-shot probe that greets a return to the home screen, run before the
     * first tick of [runHeartbeat].
     *
     * Re-entering the screen is a claim on the user's attention that the last
     * verdict has expired: they navigated away and back, very often *because*
     * they just changed something on the Server screen. Waiting out the loop's
     * [STALE_AFTER_MILLIS] then shows them an answer they no longer believe, in
     * red, for up to a minute. So the loop starts with a check (see
     * [shouldProbeOnEntry]) and only skips it when a dial is already in flight or
     * the remembered answer is a *fresh, successful* one.
     *
     * While that probe is in flight the line must never read a stale red: a
     * remembered **failure** is forgotten first, so [applyDisplay] falls through
     * to the neutral "Connecting…" (or the live "Tor starting… N%") until the
     * probe answers. A remembered **success** is kept instead — an established
     * connection must not blink to "Connecting…" merely because a background
     * re-check started.
     *
     * Extracted as a [CoroutineScope] extension so the probe can be a child of
     * the heartbeat, exactly like a tick's dial: a slow one must not freeze the
     * live Tor percentage the loop renders.
     */
    private fun CoroutineScope.refreshOnVisible() {
        applyDisplay()
        if (!shouldProbeOnEntry()) return
        if (lastAnswerStatus == ServerStatus.DISCONNECTED) forgetAnswer()
        dialJob = launch { performDial() }
    }

    /**
     * Whether the entry probe is due, i.e. whether a return to the home screen
     * should check the server right now instead of waiting out a tick. All must
     * hold:
     *
     * - an endpoint is saved;
     * - no dial is in flight — a probe already running is reused, never doubled;
     * - the route can dial (the same Tor gate as [shouldDial] — an onion server
     *   is not dialled while Tor is bootstrapping or dead);
     * - the remembered answer is not a *very fresh success*: no answer yet, an
     *   answer about a different endpoint, one older than [FRESH_AFTER_MILLIS],
     *   or a failure — a failure is never fresh enough to keep for a return, and
     *   rendering it is precisely the misleading red this probe removes.
     */
    internal fun shouldProbeOnEntry(): Boolean {
        val server = configuredServer() ?: return false
        if (dialJob?.isActive == true) return false
        if (!canDialNow(server)) return false
        val answeredAt = lastAnswerAtMillis ?: return true
        if (lastAnswerServerKey != serverKey) return true
        if (now() - answeredAt >= FRESH_AFTER_MILLIS) return true
        return lastAnswerStatus != ServerStatus.CONNECTED &&
            lastAnswerStatus != ServerStatus.CONNECTED_VIA_TOR
    }

    /**
     * Drops the remembered answer, so [applyDisplay] falls back to the neutral
     * [ServerStatus.CONNECTING] while a fresh probe is in flight. Used only for a
     * remembered failure on re-entry — a remembered success stays put.
     */
    private fun forgetAnswer() {
        lastAnswerStatus = null
        lastAnswerAtMillis = null
        lastAnswerServerKey = null
    }

    /**
     * Whether a dial should start now. All must hold:
     *
     * - no dial is in flight;
     * - there is no answer yet, or it is older than [STALE_AFTER_MILLIS], or it
     *   belongs to a different endpoint;
     * - the route can dial: a clearnet server always can, an onion server only
     *   when Tor is [TorPhase.READY] or [TorPhase.OFF] — never while it is
     *   bootstrapping or failed. OFF is allowed on purpose: the connection path
     *   starts Tor itself, and the tick loop then renders live progress while
     *   this dial is blocked on the bootstrap.
     */
    internal fun shouldDial(): Boolean {
        val server = configuredServer() ?: return false
        if (dialJob?.isActive == true) return false
        if (!canDialNow(server)) return false
        val answeredAt = lastAnswerAtMillis ?: return true
        if (lastAnswerServerKey != serverKey) return true
        return now() - answeredAt >= STALE_AFTER_MILLIS
    }

    /**
     * Whether the current route may be dialled: always for clearnet, and for an
     * onion only once Tor is [TorPhase.READY] or [TorPhase.OFF]. OFF is allowed
     * on purpose — the connection path starts Tor itself, and the loop then
     * renders live progress while the dial blocks on the bootstrap.
     */
    private fun canDialNow(server: ElectrumServer): Boolean {
        if (!ElectrumRouting.isOnionHost(server.host)) return true
        val phase = torStatus().phase
        return phase == TorPhase.READY || phase == TorPhase.OFF
    }

    /**
     * Performs one dial and applies its answer. Success reads as
     * [ServerStatus.CONNECTED_VIA_TOR] on an onion route and
     * [ServerStatus.CONNECTED] on clearnet; a failure, an exception or the
     * [DIAL_TIMEOUT_MILLIS] cap reads as [ServerStatus.DISCONNECTED]. Either way
     * the answer is stamped with the endpoint it was about, so a revisit within
     * [STALE_AFTER_MILLIS] renders it without dialling again.
     *
     * Only [CancellationException] escapes (the heartbeat was cancelled, or the
     * app left the screen): a cancelled dial leaves the previous answer in place
     * and frees the dial slot, so the next due-check may start a fresh one.
     */
    private suspend fun performDial() {
        val server = configuredServer() ?: return
        val onion = ElectrumRouting.isOnionHost(server.host)
        val key = keyOf(server)
        try {
            val reachable = withTimeout(DIAL_TIMEOUT_MILLIS) { ping(server) }
            applyAnswer(
                if (!reachable) ServerStatus.DISCONNECTED
                else if (onion) ServerStatus.CONNECTED_VIA_TOR
                else ServerStatus.CONNECTED,
                key,
            )
        } catch (e: TimeoutCancellationException) {
            // Safely past Tor's 120s bootstrap cap: give up and free the slot so
            // the next due-check can try again.
            applyAnswer(ServerStatus.DISCONNECTED, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            applyAnswer(ServerStatus.DISCONNECTED, key)
        }
    }

    /**
     * Stamps a completed answer and renders it straight away, so a dial
     * finishing between ticks does not leave the old label up until the next one.
     */
    private fun applyAnswer(status: ServerStatus, key: String) {
        lastAnswerStatus = status
        lastAnswerServerKey = key
        lastAnswerAtMillis = now()
        applyDisplay()
    }

    /** The saved endpoint, or null when the user has not entered one yet. */
    private fun configuredServer(): ElectrumServer? = serverSettings.load().takeIf { it.host.isNotBlank() }

    /** A stable identity for an endpoint, used to tie an answer to its server. */
    private fun keyOf(server: ElectrumServer): String =
        "${server.host}:${server.port}:${server.protocol.name}"

    companion object {
        /** Heartbeat tick: the Tor poll cadence and the display refresh cadence. */
        const val TICK_INTERVAL_MILLIS: Long = 2_000L

        /**
         * How old a remembered answer may be before a return to home re-checks
         * it, and before the running loop re-dials. Younger than this, the answer
         * is rendered as-is and no dial is paid.
         */
        const val STALE_AFTER_MILLIS: Long = 60_000L

        /**
         * How fresh a **successful** answer must be for a return to the home
         * screen to reuse it instead of probing again (see
         * [HomeScreenState.shouldProbeOnEntry]). Deliberately much shorter than
         * [STALE_AFTER_MILLIS]: on the loop's own cadence one check a minute is
         * plenty, but a return to the screen is the user asking "is it up now?",
         * so anything older than a few seconds is worth one immediate check —
         * and a remembered *failure* is never reusable at all.
         */
        const val FRESH_AFTER_MILLIS: Long = 5_000L

        /**
         * Cap on a single dial. Safely past Tor's 120s bootstrap cap, so a dial
         * that never answers cannot hold the dial slot forever.
         */
        const val DIAL_TIMEOUT_MILLIS: Long = 150_000L
    }
}

/**
 * The body of the *Remove wallet?* confirmation, for the wallet being removed.
 *
 * The screen asks two different questions depending on what is actually on the
 * phone, and the difference is the whole point of the split:
 *
 * - A **watch-only or signing-device** wallet stores a public key and nothing
 *   else, so removing it costs the user a re-import and no coins. The copy says
 *   so, and has said so since the dialog existed.
 * - A **hot wallet** stores its seed here. The same sentence would be false for
 *   it, and dangerously so — the user would tap *Remove* believing the coins are
 *   safe on some device and only the watch-only convenience is being deleted,
 *   when in fact the wallet is being destroyed unless the words are written
 *   down. So a hot wallet gets its own sentence, and it does not soften it.
 *
 * Kept as a pure function of the record, out of the composable, so both strings
 * can be pinned verbatim by a JVM test rather than only by the eye. The strings
 * are Andrew's, locked: the hot one is reproduced character for character,
 * including the curly quotes around the name and the em dash in the watch-only
 * one.
 */
internal fun removeWalletConfirmText(signer: Signer): String = when (signer.type) {
    is SignerType.HotSeed ->
        "\u201c${signer.name}\u201d will be removed from this phone, including its seed. " +
            "Without your written-down recovery words the coins in this wallet are gone forever."

    is SignerType.WatchOnlyXpub ->
        "\u201c${signer.name}\u201d will be removed from this phone. " +
            "Only the public key is stored here, so nothing is deleted on " +
            "the signer itself \u2014 you can add it back any time."
}

/**
 * Remembers a [HomeScreenState] for the app's lifetime.
 *
 * Held at the navigation level rather than inside [HomeScreen] so it survives
 * leaving and returning to the screen — and so the wallet list rendered on Home
 * and the count above it read the same snapshot.
 */
@Composable
fun rememberHomeScreenState(
    signerStore: SignerStore,
    serverSettings: ServerSettings,
): HomeScreenState = remember(signerStore, serverSettings) {
    HomeScreenState(signerStore, serverSettings)
}

/**
 * Home screen — the app's landing page, and its statement of what the app is: a
 * coordinator holding xpubs, not a wallet holding keys.
 *
 * This screen is the app's home, and it reads top to bottom in the order the
 * user meets the app:
 *
 *  1. **About**, a text button at the very top, opening the app's own
 *     explanation;
 *  2. the app's name — *XBT Wallet*, in the largest bold type on the screen —
 *     with its tagline under it;
 *  3. a divider, closing the identity block;
 *  4. the live **server status** with a **Settings** button beside it — the way
 *     into the app-level preferences and the Server endpoint, both of which moved
 *     to Settings;
 *  5. the **Wallets** section: the heading, the count, and then the wallet list
 *     itself, inline;
 *  6. **Add wallet**, full-width and pinned to the bottom edge, in both the empty
 *     and the populated state.
 *
 * **The standalone Wallets page is gone.** The list lives here now: a wallet row
 * opens that wallet's own screen, and removing one lives on the row's ⋮ overflow
 * menu — *Remove wallet*, then a two-step confirm — because for a watch-only
 * wallet that xpub is the only record of the account on this phone, and for a
 * hot wallet the delete takes the seed with it, so a stray tap must not be able
 * to lose either. The old Home elements (the Preferences switch, the Server button,
 * the Wallets button and the footer tagline) are gone with it; the Preferences
 * switch and the Server entry point are on Settings now.
 *
 * Under the status row the screen shows the live connection status: the "connect
 * your own server" hint until an endpoint is saved, then a dot with *Connected*
 * / *Connected via Tor* / *Server disconnected* — fed by the heartbeat the
 * navigation layer runs while this screen is on display (see `XbtWalletApp`).
 * **No host is ever rendered**, in any state: which server the user chose is
 * theirs, not something to put in a screenshot. While an onion server's Tor is
 * coming up the line reads *Tor starting… N%* instead of guessing at a connection
 * it cannot make yet.
 *
 * The screen owns no state beyond the two bits of local interaction state below:
 * it renders [state] and forwards taps. The value of the keep-screen-on
 * preference is owned by the navigation layer, and persisted the moment the
 * switch is flipped on Settings.
 *
 * @param onOpenAbout called when the About button at the top is tapped.
 * @param onOpenSettings called when the Settings button beside the server status
 *   is tapped.
 * @param onOpenSigner called with the wallet a row was tapped on; opens that
 *   wallet's own screen.
 * @param onAddSigner called when the Add wallet button is tapped.
 * @param onDeleteSigner called with the wallet confirmed for removal; the caller
 *   owns the store.
 */
@Composable
fun HomeScreen(
    state: HomeScreenState,
    onOpenAbout: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSigner: (Signer) -> Unit,
    onAddSigner: () -> Unit,
    onDeleteSigner: (Signer) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which row's "⋮" menu is open, by signer id — one at a time. Local to the
    // interaction and never touching storage: removing a wallet is
    // [onDeleteSigner]'s job, on the screen that owns the store.
    var menuForId by remember { mutableStateOf<String?>(null) }

    // Which signer is awaiting confirmation, by signer id. Saved across
    // configuration changes so a rotation does not silently drop the question.
    var confirmingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            // 1. About at the very top, in the same header row the other screens
            //    use for "← Back": the same 12.dp top padding, the same row shape.
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenAbout) { Text("About") }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. The app's name, and the statement under it.
            Text(
                text = "XBT Wallet",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Simple, sovereign, for the people.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 3. The divider that closes the identity block.
            HorizontalDivider()

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Server status on the left, the way into Settings on the right.
            //    Live server status: the facts (configured? reachable?) are the
            //    state's, this only renders them.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    ServerStatusLine(
                        status = state.serverStatus,
                        torProgressPercent = state.torProgressPercent,
                    )
                }
                OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 5. Wallets: the heading and the count, then the list itself.
            Text("Wallets", style = MaterialTheme.typography.titleLarge)

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = when (state.signers.size) {
                    0 -> "No wallets yet."
                    1 -> "1 wallet"
                    else -> "${state.signers.size} wallets"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (state.signers.isEmpty()) {
                // The empty state does the explaining; the way out of itself is
                // the Add wallet button pinned to the bottom edge, so an empty
                // list is never a dead end.
                Text(
                    text = "Add a wallet to watch an account: scan the account xpub " +
                        "from your air-gapped signer and this phone can see it without " +
                        "ever holding its keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Flexible space, so the Add wallet button below still lands on
                // the bottom edge of the screen.
                Spacer(modifier = Modifier.weight(1f))
            } else {
                // The list starts right here and takes every remaining pixel, so
                // the first wallet renders directly under the count and the list
                // scrolls down from there.
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.signers, key = { it.id }) { signer ->
                        SignerRow(
                            signer = signer,
                            onClick = { onOpenSigner(signer) },
                            menuOpen = menuForId == signer.id,
                            onMenuOpenChange = { open -> menuForId = if (open) signer.id else null },
                            onRemoveClick = {
                                menuForId = null
                                confirmingDeleteId = signer.id
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 6. The primary action, and the last thing on the screen:
            //    full-width and pinned to the bottom edge in both states, where a
            //    thumb naturally lands.
            Button(
                onClick = onAddSigner,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add wallet")
            }
        }
    }

    // Resolve the pending id against the current list at render time: if the
    // signer went away underneath us, there is nothing to confirm and the dialog
    // simply is not shown.
    val pendingDelete = confirmingDeleteId?.let { id -> state.signers.firstOrNull { it.id == id } }
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmingDeleteId = null },
            title = { Text("Remove wallet?") },
            text = {
                // Two sentences, chosen by what is actually stored for this
                // wallet — a hot wallet's seed is on this phone and the dialog
                // must say so. See [removeWalletConfirmText].
                Text(removeWalletConfirmText(pendingDelete))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDeleteId = null
                        onDeleteSigner(pendingDelete)
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDeleteId = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The one line under the Server button: the helper hint before anything is
 * configured, otherwise a status dot and a short answer.
 *
 * Takes plain values rather than the state object so every state can be
 * previewed without a store or a network. It carries no host: the route-level
 * answer ("Connected" / "Connected via Tor") is the whole point — the saved
 * endpoint is never rendered, so it cannot leak into a screenshot.
 */
@Composable
private fun ServerStatusLine(
    status: ServerStatus,
    torProgressPercent: Int,
    modifier: Modifier = Modifier,
) {
    val (color, label) = when (status) {
        ServerStatus.NOT_CONFIGURED ->
            MaterialTheme.colorScheme.onSurfaceVariant to "Connect to your own Electrum server"
        ServerStatus.TOR_STARTING ->
            MaterialTheme.colorScheme.onSurfaceVariant to "Tor starting\u2026 $torProgressPercent%"
        ServerStatus.TOR_FAILED ->
            MaterialTheme.colorScheme.error to "Tor failed to start"
        ServerStatus.CONNECTING ->
            MaterialTheme.colorScheme.onSurfaceVariant to "Connecting\u2026"
        ServerStatus.CONNECTED ->
            XbtSuccessDark to "Connected"
        ServerStatus.CONNECTED_VIA_TOR ->
            XbtSuccessDark to "Connected via Tor"
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
            onOpenAbout = {},
            onOpenSettings = {},
            onOpenSigner = {},
            onAddSigner = {},
            onDeleteSigner = {},
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
            onOpenAbout = {},
            onOpenSettings = {},
            onOpenSigner = {},
            onAddSigner = {},
            onDeleteSigner = {},
        )
    }
}

/** All seven server-status renderings side by side, without a state or a socket. */
@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun ServerStatusLinePreview() {
    XbtWalletTheme {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ServerStatusLine(status = ServerStatus.NOT_CONFIGURED, torProgressPercent = 0)
            Spacer(modifier = Modifier.height(12.dp))
            ServerStatusLine(status = ServerStatus.TOR_STARTING, torProgressPercent = 40)
            Spacer(modifier = Modifier.height(12.dp))
            ServerStatusLine(status = ServerStatus.TOR_FAILED, torProgressPercent = 40)
            Spacer(modifier = Modifier.height(12.dp))
            ServerStatusLine(status = ServerStatus.CONNECTING, torProgressPercent = 0)
            Spacer(modifier = Modifier.height(12.dp))
            ServerStatusLine(status = ServerStatus.CONNECTED, torProgressPercent = 0)
            Spacer(modifier = Modifier.height(12.dp))
            ServerStatusLine(status = ServerStatus.CONNECTED_VIA_TOR, torProgressPercent = 100)
            Spacer(modifier = Modifier.height(12.dp))
            ServerStatusLine(status = ServerStatus.DISCONNECTED, torProgressPercent = 0)
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
