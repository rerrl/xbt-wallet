package com.xbtwallet.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.xbtwallet.app.data.ServerSettingsStore
import com.xbtwallet.app.data.SharedPreferencesKeyValueStore
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerStore
import com.xbtwallet.app.ui.home.HomeScreen
import com.xbtwallet.app.ui.home.rememberHomeScreenState
import com.xbtwallet.app.ui.server.ServerScreen
import com.xbtwallet.app.ui.server.rememberServerScreenState
import com.xbtwallet.app.ui.addresses.AddressesScreen
import com.xbtwallet.app.ui.signers.AddSignerScreen
import com.xbtwallet.app.ui.signers.ImportXpubScreen
import com.xbtwallet.app.ui.signers.SignerChainState
import com.xbtwallet.app.ui.signers.SignerDetailScreen
import com.xbtwallet.app.ui.signers.SignersScreen
import com.xbtwallet.app.ui.signers.UtxosScreen
import com.xbtwallet.app.ui.signers.rememberImportXpubScreenState

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
enum class Screen { HOME, SERVER, SIGNERS, ADD_SIGNER, IMPORT_XPUB, SIGNER_DETAIL, SIGNER_UTXOS, SIGNER_ADDRESSES }

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
 * **Navigation is a back stack.** [NavStack] holds the destinations walked so
 * far, the app renders its [NavStack.current], and *navigate* is `push` while
 * *back* is `pop` — one rule for every screen, instead of a hand-written
 * per-screen back target. That is what makes the two obvious walks behave as a
 * user expects:
 *
 * - HOME → ADD_SIGNER → (the paste form) → back → ADD_SIGNER → back → HOME.
 * - a signer's detail → UTXOs (or Addresses) → back → that signer's detail.
 *
 * Android's back button is wired to `pop` through [BackHandler], *enabled* only
 * while there is something to pop ([NavStack.canGoBack]). At HOME the handler is
 * not installed at all, so back is the system's — leaving the app — rather than
 * a press that visibly does nothing. The stack survives a configuration change
 * ([NavStack.Saver]); each entry then carries its own signer id, so a rotation
 * cannot land the user on a different signer's screen.
 *
 * **The add-signer walk.** [Screen.ADD_SIGNER] is the chooser and its live branch
 * pushes [Screen.IMPORT_XPUB] for the form, so back from the form returns to the
 * chooser that sent you there. Saving resets the stack to HOME → [Screen.SIGNERS]
 * rather than pushing on top of the form: the new signer is immediately visible
 * where the add most often starts, and a back press from the list goes Home, not
 * back into a form that has already been submitted.
 */
@Composable
fun XbtWalletApp() {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerSettingsStore(context) }
    val signerStore = remember(context) { SignerStore(SharedPreferencesKeyValueStore(context)) }

    val homeState = rememberHomeScreenState(signerStore, serverStore)

    // The back stack, restored across configuration changes. `route` is its top:
    // the one destination the `when` below renders.
    val nav = rememberSaveable(saver = NavStack.Saver) { NavStack() }
    val route = nav.current

    // The signer the current screen is showing, if any. Read from the stack entry
    // rather than shared mutable state, so a screen's argument cannot be clobbered
    // by a later navigation.
    val openSigner = route.signerId?.let(signerStore::get)

    // The open signer and its chain state. Resolved here, at the navigation
    // level, for two reasons: the state must outlive a single screen (opening
    // UTXOs from the detail screen must not cancel the scan in flight), and
    // resolving through the store every recomposition is what makes a signer
    // deleted underneath us fall back Home instead of rendering stale data.
    // Keyed on the signer *id*, so walking detail → UTXOs → detail keeps the same
    // scan and its result.
    val chainState = remember(openSigner?.id, serverStore) {
        openSigner?.let { SignerChainState(signer = it, serverSettings = serverStore) }
    }

    // One scan per open signer. `load()` is a no-op once a scan has run or is
    // running, so recomposition and screen changes cost nothing; the effect is
    // re-created only when a different signer is opened (a new state object).
    LaunchedEffect(chainState) { chainState?.load() }

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
            val lifecycleOwner = LocalActivity.current as? LifecycleOwner
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
                onOpenServer = { nav.push(Route(Screen.SERVER)) },
                onAddSigner = { nav.push(Route(Screen.ADD_SIGNER)) },
                onOpenSigners = { nav.push(Route(Screen.SIGNERS)) },
            )
        }

        Screen.SERVER -> {
            val state = rememberServerScreenState(serverStore)
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
                homeState.refresh()
            },
            onBack = { nav.pop() },
        )

        Screen.ADD_SIGNER -> AddSignerScreen(
            onAddWatchOnly = { nav.push(Route(Screen.IMPORT_XPUB)) },
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
                    onBack = { nav.pop() },
                )
            }
        }

        Screen.SIGNER_UTXOS -> {
            if (chainState == null) {
                // Same rule as the detail screen: no signer, nothing to show.
                LaunchedEffect(route) { nav.reset() }
            } else {
                UtxosScreen(state = chainState, onBack = { nav.pop() })
            }
        }

        Screen.SIGNER_ADDRESSES -> {
            if (chainState == null) {
                LaunchedEffect(route) { nav.reset() }
            } else {
                AddressesScreen(state = chainState, onBack = { nav.pop() })
            }
        }
    }
}
