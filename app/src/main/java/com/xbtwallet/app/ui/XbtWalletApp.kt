package com.xbtwallet.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.xbtwallet.app.data.ServerSettingsStore
import com.xbtwallet.app.data.SharedPreferencesKeyValueStore
import com.xbtwallet.app.data.Wallet
import com.xbtwallet.app.data.WalletStore
import com.xbtwallet.app.ui.addresses.AddressesScreen
import com.xbtwallet.app.ui.addresses.WalletPickerScreen
import com.xbtwallet.app.ui.addresses.rememberAddressesScreenState
import com.xbtwallet.app.ui.home.HomeScreen
import com.xbtwallet.app.ui.importwallet.ImportWalletScreen
import com.xbtwallet.app.ui.importwallet.rememberImportWalletScreenState
import com.xbtwallet.app.ui.server.ServerScreen
import com.xbtwallet.app.ui.server.rememberServerScreenState

/**
 * Screens the app can show.
 *
 * Still an enum and a `when`, not a navigation library: four destinations that
 * take no arguments (the wallet being viewed is app state, not a route) do not
 * justify a dependency (PLAN.md §2.4).
 */
enum class Screen { HOME, SERVER, IMPORT_WALLET, ADDRESSES }

/**
 * The app's navigation and its shared stores.
 *
 * `wallets` is held here as snapshot state rather than read inside each screen:
 * importing a wallet has to make the Home screen's counter and the Addresses
 * button react immediately, and a single source of truth at the navigation level
 * is the cheapest way to get that. [refreshWallets] re-reads the store after a
 * write.
 */
@Composable
fun XbtWalletApp() {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerSettingsStore(context) }
    val walletStore = remember(context) { WalletStore(SharedPreferencesKeyValueStore(context)) }

    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var wallets by remember { mutableStateOf(walletStore.list()) }

    // Which wallet the Addresses screen is showing. Empty string means "not
    // chosen yet", which is only reachable when several wallets exist and the
    // user still has the picker on screen. (A nullable String is awkward with
    // rememberSaveable, so the sentinel avoids a custom Saver.)
    var selectedWalletId by rememberSaveable { mutableStateOf("") }

    fun openAddresses() {
        val current = walletStore.list()
        wallets = current
        selectedWalletId = if (current.size == 1) current.first().id else ""
        screen = Screen.ADDRESSES
    }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    when (screen) {
        Screen.HOME -> HomeScreen(
            walletCount = wallets.size,
            onOpenImportWallet = { screen = Screen.IMPORT_WALLET },
            onOpenAddresses = ::openAddresses,
            onOpenServer = { screen = Screen.SERVER },
        )

        Screen.SERVER -> {
            val state = rememberServerScreenState(serverStore)
            ServerScreen(state = state, onBack = { screen = Screen.HOME })
        }

        Screen.IMPORT_WALLET -> {
            val state = rememberImportWalletScreenState(walletStore) { _: Wallet ->
                // Re-read through the store so Home shows the new count, then
                // land back on Home where the next step (Addresses) is.
                wallets = walletStore.list()
                screen = Screen.HOME
            }
            ImportWalletScreen(state = state, onBack = { screen = Screen.HOME })
        }

        Screen.ADDRESSES -> {
            val selected = wallets.firstOrNull { it.id == selectedWalletId }
            if (selected == null) {
                WalletPickerScreen(
                    wallets = wallets,
                    onSelect = { wallet -> selectedWalletId = wallet.id },
                    onBack = { screen = Screen.HOME },
                )
            } else {
                val state = rememberAddressesScreenState(selected)
                AddressesScreen(
                    state = state,
                    onBack = { screen = Screen.HOME },
                    onChangeWallet = if (wallets.size > 1) {
                        { selectedWalletId = "" }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
