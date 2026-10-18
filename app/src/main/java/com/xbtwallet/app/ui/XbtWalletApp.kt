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
import com.xbtwallet.app.data.Wallet
import com.xbtwallet.app.data.WalletStore
import com.xbtwallet.app.ui.addresses.AddressesScreen
import com.xbtwallet.app.ui.addresses.rememberAddressesScreenState
import com.xbtwallet.app.ui.home.HomeScreen
import com.xbtwallet.app.ui.home.XpubPickerScreen
import com.xbtwallet.app.ui.home.rememberHomeScreenState
import com.xbtwallet.app.ui.importwallet.ImportWalletScreen
import com.xbtwallet.app.ui.importwallet.rememberImportWalletScreenState
import com.xbtwallet.app.ui.server.ServerScreen
import com.xbtwallet.app.ui.server.rememberServerScreenState

/**
 * Screens the app can show.
 *
 * Still an enum and a `when`, not a navigation library: five destinations that
 * take no arguments (the active xpub is app state, not a route) do not justify a
 * dependency (PLAN.md §2.4).
 */
enum class Screen { HOME, SERVER, IMPORT_WALLET, SELECT_XPUB, ADDRESSES }

/**
 * The app's navigation and its shared stores.
 *
 * [rememberHomeScreenState] is held here rather than inside the home composable
 * because three screens agree on the same fact — which xpub is active. The
 * picker writes it, the import screen invalidates it, and Addresses reads it, so
 * one instance at the navigation level is the cheapest way to keep them
 * consistent. The `when` below only ever renders one screen at a time, so the
 * state is never observed from two places at once.
 */
@Composable
fun XbtWalletApp() {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerSettingsStore(context) }
    val walletStore = remember(context) { WalletStore(SharedPreferencesKeyValueStore(context)) }

    val homeState = rememberHomeScreenState(walletStore, serverStore)

    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

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
                onOpenImportXpub = { screen = Screen.IMPORT_WALLET },
                onSelectActiveXpub = { screen = Screen.SELECT_XPUB },
                onOpenAddresses = { screen = Screen.ADDRESSES },
            )
        }

        Screen.SERVER -> {
            val state = rememberServerScreenState(serverStore)
            ServerScreen(state = state, onBack = { screen = Screen.HOME })
        }

        Screen.IMPORT_WALLET -> {
            val state = rememberImportWalletScreenState(walletStore) { _: Wallet ->
                // Re-read through the store so Home shows the new xpub, then land
                // back on Home, where making it the active signer is.
                homeState.refresh()
                screen = Screen.HOME
            }
            ImportWalletScreen(state = state, onBack = { screen = Screen.HOME })
        }

        Screen.SELECT_XPUB -> XpubPickerScreen(
            xpubs = homeState.xpubs,
            activeId = homeState.activeId,
            onSelect = { wallet ->
                homeState.setActive(wallet)
                screen = Screen.HOME
            },
            onBack = { screen = Screen.HOME },
        )

        Screen.ADDRESSES -> {
            val active = homeState.active
            if (active == null) {
                // Not reachable through the UI — the Addresses button is disabled
                // without an active xpub — but if the selection disappeared while
                // the screen is open, go Home rather than derive for nothing.
                LaunchedEffect(Unit) { screen = Screen.HOME }
            } else {
                val state = rememberAddressesScreenState(active)
                AddressesScreen(
                    state = state,
                    onBack = { screen = Screen.HOME },
                )
            }
        }
    }
}
