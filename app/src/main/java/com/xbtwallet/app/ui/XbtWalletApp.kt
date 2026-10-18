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
import com.xbtwallet.app.ui.home.HomeScreen
import com.xbtwallet.app.ui.server.ServerScreen
import com.xbtwallet.app.ui.server.rememberServerScreenState

/** Screens the app can show. Deliberately tiny while the app has two of them. */
enum class Screen { HOME, SERVER }

/**
 * The app's navigation.
 *
 * A single activity with state-based navigation rather than a navigation
 * library: two screens do not justify a dependency (PLAN.md §2.4). Swap this for
 * a NavHost when the graph grows past a handful of destinations and deep links
 * become a requirement.
 */
@Composable
fun XbtWalletApp() {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerSettingsStore(context) }

    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    when (screen) {
        Screen.HOME -> HomeScreen(onOpenServer = { screen = Screen.SERVER })
        Screen.SERVER -> {
            val state = rememberServerScreenState(serverStore)
            ServerScreen(state = state, onBack = { screen = Screen.HOME })
        }
    }
}
