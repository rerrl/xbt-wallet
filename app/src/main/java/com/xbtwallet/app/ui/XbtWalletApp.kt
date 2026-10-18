package com.xbtwallet.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.xbtwallet.app.ui.importwallet.ImportWalletScreen
import com.xbtwallet.app.ui.importwallet.rememberImportWalletScreenState
import com.xbtwallet.app.ui.server.ServerScreen
import com.xbtwallet.app.ui.server.rememberServerScreenState
import com.xbtwallet.app.ui.signers.SignerDetailScreen
import com.xbtwallet.app.ui.signers.SignersScreen

/**
 * Screens the app can show.
 *
 * Still an enum and a `when`, not a navigation library: a handful of
 * destinations do not justify a dependency (PLAN.md §2.4).
 *
 * **Argument passing.** A [SIGNER_DETAIL] screen is the one destination that
 * needs an argument — *which* signer. Rather than smuggle a class instance into
 * the enum (an enum is one value per name, not a route), the argument rides
 * beside it in [XbtWalletApp]'s `signerDetailId`, set at the moment of
 * navigation and read back by the detail branch. That is the whole pattern:
 * `Screen` says *where*, the id state says *which*, and both survive a
 * configuration change because both are `rememberSaveable`. When more
 * argument-taking screens arrive, this is the seam to generalise into a route
 * type with its own `Saver`.
 */
enum class Screen { HOME, SERVER, SIGNERS, ADD_SIGNER, SIGNER_DETAIL }

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
 * **Back handling:** every non-[Screen.HOME] destination returns to HOME. The
 * linear home → list → detail walk is short enough that a single back target is
 * predictable; if the graph grows a deeper stack, this is the one line to
 * change.
 */
@Composable
fun XbtWalletApp() {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerSettingsStore(context) }
    val signerStore = remember(context) { SignerStore(SharedPreferencesKeyValueStore(context)) }

    val homeState = rememberHomeScreenState(signerStore, serverStore)

    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

    // The argument for Screen.SIGNER_DETAIL (see the Screen doc comment). Null
    // when the detail screen is not the one being shown.
    var signerDetailId by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    when (screen) {
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
                onOpenServer = { screen = Screen.SERVER },
                onAddSigner = { screen = Screen.ADD_SIGNER },
                onOpenSigners = { screen = Screen.SIGNERS },
            )
        }

        Screen.SERVER -> {
            val state = rememberServerScreenState(serverStore)
            ServerScreen(state = state, onBack = { screen = Screen.HOME })
        }

        Screen.SIGNERS -> SignersScreen(
            signers = homeState.signers,
            onOpenSigner = { signer: Signer ->
                signerDetailId = signer.id
                screen = Screen.SIGNER_DETAIL
            },
            onBack = { screen = Screen.HOME },
        )

        Screen.ADD_SIGNER -> {
            val state = rememberImportWalletScreenState(signerStore) { _: Signer ->
                // Re-read through the store so Home and the Signers list show
                // the new signer, then land back on Home.
                homeState.refresh()
                screen = Screen.HOME
            }
            ImportWalletScreen(state = state, onBack = { screen = Screen.HOME })
        }

        Screen.SIGNER_DETAIL -> {
            val signer = signerDetailId?.let(signerStore::get)
            if (signer == null) {
                // The id no longer resolves (the signer was removed, or a saved
                // id went stale). Go Home rather than show an empty detail page.
                LaunchedEffect(signerDetailId) { screen = Screen.HOME }
            } else {
                SignerDetailScreen(signer = signer, onBack = { screen = Screen.HOME })
            }
        }
    }
}
