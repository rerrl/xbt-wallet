package com.xbtwallet.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.xbtwallet.app.data.AppSettingsStore
import com.xbtwallet.app.data.SavedServersStore
import com.xbtwallet.app.data.ServerSettingsStore
import com.xbtwallet.app.data.SharedPreferencesKeyValueStore
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerOrigin
import com.xbtwallet.app.data.SignerStore
import com.xbtwallet.app.data.SnapshotStore
import com.xbtwallet.app.ui.home.HomeScreen
import com.xbtwallet.app.ui.home.rememberHomeScreenState
import com.xbtwallet.app.ui.server.ServerScreen
import com.xbtwallet.app.ui.server.rememberServerScreenState
import com.xbtwallet.app.ui.addresses.AddressesScreen
import com.xbtwallet.app.ui.receive.ReceiveScreen
import com.xbtwallet.app.ui.signers.AddSignerScreen
import com.xbtwallet.app.ui.signers.AddSigningDeviceScreen
import com.xbtwallet.app.ui.signers.ChainPhase
import com.xbtwallet.app.ui.signers.ImportXpubScreen
import com.xbtwallet.app.ui.signers.SignerChainStateStore
import com.xbtwallet.app.ui.signers.SignerDetailScreen
import com.xbtwallet.app.ui.signers.SendScreen
import com.xbtwallet.app.ui.signers.SignersScreen
import com.xbtwallet.app.ui.signers.UtxosScreen
import com.xbtwallet.app.ui.signers.rememberAddSigningDeviceScreenState
import com.xbtwallet.app.ui.signers.rememberImportXpubScreenState
import com.xbtwallet.app.ui.signers.routeFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Screens the app can show.
 *
 * Still an enum and a `when`, not a navigation library: a handful of
 * destinations do not justify a dependency (PLAN.md §2.4).
 *
 * **Where, not which.** A `Screen` says only *where* to go. *Which* signer a
 * per-signer screen is showing is not part of the enum (an enum is one value per
 * name, not a route); it rides in the [Route] that carries this screen onto the
 * back stack, so each level remembers its own argument. See [NavStack].
 */
enum class Screen { HOME, SERVER, SIGNERS, ADD_SIGNER, IMPORT_XPUB, ADD_SIGNING_DEVICE, SIGNER_DETAIL, SIGNER_UTXOS, SIGNER_ADDRESSES, RECEIVE, SEND }

/**
 * The app's navigation and its shared stores.
 *
 * [rememberHomeScreenState] is held here rather than inside the home composable
 * because two screens agree on the same fact — which signers are saved. The
 * Signers list reads the same snapshot the home screen counts, so one instance
 * at the navigation level is the cheapest way to keep them consistent. The
 * `when` below only ever renders one screen at a time, so the state is never
 * observed from two places at once.
 *
 * There is no active-signer state any more: signers are a plain list, and
 * anything that acts on one acts inside that signer's own screen.
 *
 * **Chain state outlives the screens.** Each signer's scan state lives in a
 * [SignerChainStateStore] created once here and driven by an app-scoped
 * coroutine scope — not in a `remember` inside the screen that shows it. A sync
 * in flight therefore keeps running while the user walks detail → UTXOs →
 * Addresses → Home → detail, and re-entering the signer reads the same shared
 * state rather than starting a second scan (see the store and
 * `SignerChainState.load` for the freshness rule). The scope is cancelled only
 * when this root composable leaves composition.
 *
 * **Navigation is a back stack.** [NavStack] holds the destinations walked so
 * far, the app renders its [NavStack.current], and *navigate* is `push` while
 * *back* is `pop` — one rule for every screen, instead of a hand-written
 * per-screen back target. That is what makes the two obvious walks behave as a
 * user expects:
 *
 * - HOME → ADD_SIGNER → (the paste form) → back → ADD_SIGNER → back → HOME.
 * - a signer's detail → UTXOs (or Addresses) → back → that signer's detail.
 * - a signer's detail → RECEIVE (the first unused address), or an Addresses row
 *   → RECEIVE (that row's address) → back → wherever it was opened from.
 *
 * Android's back button is wired to `pop` through [BackHandler], *enabled* only
 * while there is something to pop ([NavStack.canGoBack]). At HOME the handler is
 * not installed at all, so back is the system's — leaving the app — rather than
 * a press that visibly does nothing. The stack survives a configuration change
 * ([NavStack.Saver]); each entry then carries its own signer id, so a rotation
 * cannot land the user on a different signer's screen.
 *
 * **The add-wallet walk.** [Screen.ADD_SIGNER] is the chooser and its three rows
 * push a form: **SeedSigner** and **Other signing device** both push
 * [Screen.ADD_SIGNING_DEVICE] (declaring `SEEDSIGNER_UR` and
 * `SIGNING_DEVICE_UR` respectively — the origin rides on the route), and
 * **Watch-only xpub** pushes [Screen.IMPORT_XPUB]. So back from any form returns
 * to the chooser that sent you there. All three save into the same store through
 * the same call. Saving resets the stack to HOME → [Screen.SIGNERS]
 * rather than pushing on top of the form: the new wallet is immediately visible
 * where the add most often starts, and a back press from the list goes Home, not
 * back into a form that has already been submitted.
 */
@Composable
fun XbtWalletApp() {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerSettingsStore(context) }
    val signerStore = remember(context) { SignerStore(SharedPreferencesKeyValueStore(context)) }
    // One snapshot store for the app: same prefs file as the signer list, its own
    // `snapshot_<id>` keys. Held here so the chain state below can both seed from
    // it and save back to it, and so the delete path can clear a signer's cache.
    val snapshotStore = remember(context) { SnapshotStore(SharedPreferencesKeyValueStore(context)) }
    // The app's own preferences (one flag today). Same prefs file and the same
    // KeyValueStore shell as the signer list, under its own key.
    val appSettings = remember(context) { AppSettingsStore(SharedPreferencesKeyValueStore(context)) }
    // Named server presets — the Server screen's picker. Same prefs file and the
    // same KeyValueStore shell as the stores above, under its own key; a preset
    // only refills the server form, it never dials.
    val savedServersStore = remember(context) {
        SavedServersStore(SharedPreferencesKeyValueStore(context))
    }

    val homeState = rememberHomeScreenState(signerStore, serverStore)

    // The back stack, restored across configuration changes. `route` is its top:
    // the one destination the `when` below renders.
    val nav = rememberSaveable(saver = NavStack.Saver) { NavStack() }
    val route = nav.current

    // The app-scoped scope the per-signer scans run in. Deliberately *not* the
    // composition's: a scan must keep running while the user navigates away from
    // the screen that started it, and is cancelled only when this root leaves
    // composition (the process going away).
    val appScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    DisposableEffect(Unit) { onDispose { appScope.cancel() } }

    // One store for the app's lifetime: it hands back the same SignerChainState
    // for a given signer id every time, so a scan (and its result) survives
    // navigation between that signer's screens.
    val chainStateStore = remember { SignerChainStateStore(serverStore, snapshotStore, appScope) }

    // The signer the current screen is showing, if any. Read from the stack entry
    // rather than shared mutable state, so a screen's argument cannot be clobbered
    // by a later navigation.
    val openSigner = route.signerId?.let(signerStore::get)

    // The open signer's chain state, resolved through the store at the navigation
    // level. The store keeps one instance per signer id, so walking
    // detail → UTXOs → Addresses → detail reads the same state throughout; and
    // resolving through the store every recomposition is what makes a signer
    // deleted underneath us fall back Home instead of rendering stale data.
    val chainState = remember(openSigner?.id) { openSigner?.let(chainStateStore::stateFor) }

    // --- One screen-on flag for the whole app --------------------------------
    //
    // The screen-on preference as Compose state, so flipping the switch on Home
    // recomposes the effect below at once; every change is written straight back
    // to the store.
    var keepScreenOn by remember { mutableStateOf(appSettings.keepScreenOnWhileOpen) }

    // While the preference is on, the app holds the display on for as long as it
    // is open in the foreground — so a sync cannot die with the screen, and the
    // user reading a balance is not interrupted by the idle timeout either.
    // `LocalView.keepScreenOn` is exactly the right scope for that: it holds only
    // while this app's window is visible, so a backgrounded app cannot keep the
    // screen awake — which is the wanted behaviour, not a limitation. No
    // wakelock (that would hold the device awake with the app hidden) and no
    // permission: the app deliberately has neither. One place, at the app root.
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // --- Auto-resume a failed sync on return to the foreground ---------------
    //
    // If a scan died while the app was backgrounded (or the screen was off,
    // leaving the signer on ERROR), coming back is the user's natural retry, so
    // reload once per ON_RESUME. `reload`, not `load`: the two-minute freshness
    // rule would otherwise suppress the rescan. Two guards keep a permanently
    // dead server from turning every wake into another round of dials: skip while
    // a scan is already in flight, and allow at most one auto-resume per
    // AUTO_RESUME_DEBOUNCE_MILLIS.
    val appLifecycleOwner = LocalActivity.current as? LifecycleOwner
    var lastAutoResumeAtMillis by rememberSaveable { mutableStateOf(0L) }
    DisposableEffect(appLifecycleOwner, chainState) {
        val owner = appLifecycleOwner
        if (owner == null || chainState == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME &&
                    !chainState.scanInFlight &&
                    chainState.phase == ChainPhase.ERROR
                ) {
                    val nowMillis = System.currentTimeMillis()
                    if (nowMillis - lastAutoResumeAtMillis >= AUTO_RESUME_DEBOUNCE_MILLIS) {
                        lastAutoResumeAtMillis = nowMillis
                        appScope.launch { chainState.reload() }
                    }
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    // Ask for a sync whenever a signer's screen opens. `sync()` starts a scan only
    // if none is in flight for that signer, and `load()` inside it skips the
    // rescan while the last completed sync is fresh — so recomposition and screen
    // changes cost nothing.
    LaunchedEffect(openSigner?.id) { openSigner?.let(chainStateStore::sync) }

    // Android back pops exactly one level. Disabled at HOME: the system finishes
    // the activity instead, which is the app-default we want there.
    BackHandler(enabled = nav.canGoBack) { nav.pop() }

    when (route.screen) {
        Screen.HOME -> {
            // The heartbeat is owned here, not inside HomeScreen: it has to run
            // only while the home screen is on display. Leaving HOME drops this
            // branch from composition and cancels the LaunchedEffect; going to
            // the background cancels it via repeatOnLifecycle. Re-keying on the
            // saved endpoint means editing the server and coming back re-checks
            // with the new settings straight away. No service, no wake lock and
            // no new permission is involved.
            val lifecycleOwner = appLifecycleOwner
            val serverKey = homeState.serverKey
            LaunchedEffect(lifecycleOwner, serverKey) {
                if (lifecycleOwner == null) {
                    // No activity (e.g. a preview): one check, no loop.
                    homeState.checkServerStatus()
                } else {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        homeState.runHeartbeat()
                    }
                }
            }

            HomeScreen(
                state = homeState,
                keepScreenOn = keepScreenOn,
                onKeepScreenOnChange = { checked ->
                    keepScreenOn = checked
                    appSettings.keepScreenOnWhileOpen = checked
                },
                onOpenServer = { nav.push(Route(Screen.SERVER)) },
                onAddSigner = { nav.push(Route(Screen.ADD_SIGNER)) },
                onOpenSigners = { nav.push(Route(Screen.SIGNERS)) },
            )
        }

        Screen.SERVER -> {
            val state = rememberServerScreenState(serverStore, savedServersStore)
            ServerScreen(state = state, onBack = { nav.pop() })
        }

        Screen.SIGNERS -> SignersScreen(
            signers = homeState.signers,
            onOpenSigner = { signer: Signer ->
                nav.push(Route(Screen.SIGNER_DETAIL, signerId = signer.id))
            },
            onAddSigner = { nav.push(Route(Screen.ADD_SIGNER)) },
            onDeleteSigner = { signer: Signer ->
                // The one place a signer is removed: the screen that owns the
                // store. Re-read through Home so the list (which renders
                // Home's snapshot) drops the row immediately, and any detail
                // entry still on the stack — should there be one — stops
                // resolving (that branch restarts at Home).
                signerStore.delete(signer.id)
                // Drop this signer's cached scan too, so a later signer that
                // happens to reuse the id can never open on another account's
                // addresses, UTXOs or balance.
                snapshotStore.clear(signer.id)
                // And forget its in-memory chain state: this cancels any scan
                // still in flight for it and drops the state, so a reused id
                // starts from scratch rather than inheriting the old scan.
                chainStateStore.remove(signer.id)
                homeState.refresh()
            },
            onBack = { nav.pop() },
        )

        Screen.ADD_SIGNER -> AddSignerScreen(
            // One handler for the three rows. What a row opens — destination *and*
            // the origin its form will record — is [routeFor], so the two animated
            // rows, which share a screen, cannot be wired to the same origin by
            // accident, and a JVM test pins the mapping.
            onChoose = { row -> nav.push(routeFor(row)) },
            onBack = { nav.pop() },
        )

        Screen.IMPORT_XPUB -> {
            val state = rememberImportXpubScreenState(signerStore) { _: Signer ->
                // Re-read through the store so Home and the Signers list show
                // the new signer, then land on the list the signer just joined —
                // which is where an add started from (the empty state's shortcut).
                // The stack is reset first so the submitted form is not left
                // underneath the list (see the class doc).
                homeState.refresh()
                nav.reset()
                nav.push(Route(Screen.SIGNERS))
            }
            ImportXpubScreen(state = state, onBack = { nav.pop() })
        }

        // The signing-device sibling of the form above: same name field, same
        // save, same signer record — a scanner that assembles animated BC-UR
        // `crypto-account` frames instead of reading one text QR. It lands the
        // user in the same place, for the same reason.
        //
        // Which chooser row opened it decides the origin the record is stamped
        // with. A route saved by a build that had only one animated row restored
        // with no origin at all, and that missing origin reads as the SeedSigner
        // path — which is exactly what the store's own migration does to the
        // records such a build wrote, so the two agree.
        Screen.ADD_SIGNING_DEVICE -> {
            val origin = route.origin ?: SignerOrigin.SEEDSIGNER_UR
            val state = rememberAddSigningDeviceScreenState(signerStore, origin) { _: Signer ->
                homeState.refresh()
                nav.reset()
                nav.push(Route(Screen.SIGNERS))
            }
            AddSigningDeviceScreen(state = state, onBack = { nav.pop() })
        }

        Screen.SIGNER_DETAIL -> {
            if (openSigner == null || chainState == null) {
                // The id no longer resolves (the signer was removed, or a saved
                // id went stale). Restart at Home rather than show an empty
                // detail page. Keyed on the route so this fires once per bad
                // entry.
                LaunchedEffect(route) { nav.reset() }
            } else {
                SignerDetailScreen(
                    signer = openSigner,
                    state = chainState,
                    onOpenUtxos = { nav.push(Route(Screen.SIGNER_UTXOS, signerId = openSigner.id)) },
                    onOpenAddresses = { nav.push(Route(Screen.SIGNER_ADDRESSES, signerId = openSigner.id)) },
                    // The address is resolved by the button before it navigates,
                    // so the receive screen opens with a QR already in hand.
                    onOpenReceive = { address ->
                        nav.push(Route(Screen.RECEIVE, signerId = openSigner.id, address = address))
                    },
                    onBack = { nav.pop() },
                )
            }
        }

        Screen.SIGNER_UTXOS -> {
            if (chainState == null) {
                // Same rule as the detail screen: no signer, nothing to show.
                LaunchedEffect(route) { nav.reset() }
            } else {
                UtxosScreen(
                    state = chainState,
                    // The selected outpoints ride on the Send route (comma-joined
                    // in the free-form address slot) rather than in shared state,
                    // so a rotation on the Send screen keeps the same coin
                    // selection. See [Route].
                    onSendSelected = { outpoints ->
                        nav.push(
                            Route(
                                Screen.SEND,
                                signerId = route.signerId,
                                address = outpoints.joinToString(","),
                            ),
                        )
                    },
                    onBack = { nav.pop() },
                )
            }
        }

        Screen.SEND -> {
            val signerId = route.signerId
            // The selection came in as `txid:vout,txid:vout,…`; blanks (a trailing
            // comma, an empty address) are dropped rather than becoming a phantom
            // outpoint. An empty result is the "nothing to send" case below.
            val outpoints = route.address.orEmpty()
                .split(",")
                .filter { it.isNotBlank() }
            if (chainState == null || signerId == null || outpoints.isEmpty()) {
                // No signer to send from, or no coins carried on the route (a
                // stack restored from a build that saved a Send route before the
                // selection was put on it). Restart at Home rather than show an
                // empty send form.
                LaunchedEffect(route) { nav.reset() }
            } else {
                SendScreen(
                    state = chainState,
                    outpoints = outpoints,
                    onBack = { nav.pop() },
                )
            }
        }

        Screen.SIGNER_ADDRESSES -> {
            val signerId = route.signerId
            if (chainState == null || signerId == null) {
                LaunchedEffect(route) { nav.reset() }
            } else {
                AddressesScreen(
                    state = chainState,
                    // The QR for a specific row: the address the user tapped
                    // rides on the route, so back/rotation return to *that*
                    // address rather than to the first unused one.
                    onShowQr = { address ->
                        nav.push(Route(Screen.RECEIVE, signerId = signerId, address = address))
                    },
                    onBack = { nav.pop() },
                )
            }
        }

        Screen.RECEIVE -> {
            val requestedAddress = route.address
            if (chainState == null || requestedAddress == null) {
                // Either the signer no longer resolves, or the route carries no
                // address — a stack restored from a build that saved a receive
                // route before the address was resolved pre-navigation. Restart
                // at Home rather than show a receive screen with nothing to
                // receive to.
                LaunchedEffect(route) { nav.reset() }
            } else {
                ReceiveScreen(
                    state = chainState,
                    // Always known: the signer's Receive button resolves it
                    // before pushing, and an addresses-list row passes its own.
                    address = requestedAddress,
                    onBack = { nav.pop() },
                )
            }
        }
    }
}

/**
 * How long to wait between auto-resumes of a failed sync.
 *
 * ON_RESUME reloads a signer left on ERROR, but a permanently dead server would
 * otherwise be re-dialled on every wake — so at most one auto-resume is allowed
 * per this window. 30s is short enough that a user who comes back and finds the
 * screen still failing can put the phone down and pick it up again, and long
 * enough that flicking the screen on and off is not a dial loop.
 */
private const val AUTO_RESUME_DEBOUNCE_MILLIS = 30_000L
