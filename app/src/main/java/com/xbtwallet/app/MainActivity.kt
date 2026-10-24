package com.xbtwallet.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.xbtwallet.app.data.ServerSettingsStore
import com.xbtwallet.app.electrum.ElectrumRouting
import com.xbtwallet.app.electrum.TorGateway
import com.xbtwallet.app.electrum.tor.AndroidTorBootstrap
import com.xbtwallet.app.ui.XbtWalletApp
import com.xbtwallet.app.ui.theme.XbtWalletTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The app's one activity.
 *
 * A `FragmentActivity` rather than a plain `ComponentActivity` for exactly one
 * reason: `androidx.biometric.BiometricPrompt` — the app lock's prompt, and the
 * prompt a hot send uses when the vault key's 30-second window has lapsed —
 * requires one. `FragmentActivity` is a `ComponentActivity`, so `setContent`,
 * `enableEdgeToEdge` and the lifecycle this app already uses are unchanged.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The bundled Tor is process-wide state: install it before any screen can
        // dial an `.onion` server. A cold Tor bootstrap takes 30–60s, so when the
        // saved server *is* an onion address we start it here, at app open, in the
        // background — the first real dial then does not pay that cold-start cost
        // while the user waits. This is a no-op for a clearnet server, and a
        // failure here is deliberately swallowed: the screens read the shared Tor
        // status (see TorGateway.status) and show it; the connection path itself
        // retries, so an eager start that loses a race is not an error to report.
        // Cancellation is rethrown, never swallowed: this coroutine dies with the
        // activity, and a CancellationException caught and discarded here would
        // hide the fact that the bootstrap was torn down mid-flight.
        val bootstrap = AndroidTorBootstrap(applicationContext)
        TorGateway.install(bootstrap)
        val saved = ServerSettingsStore(applicationContext).load()
        if (ElectrumRouting.isOnionHost(saved.host)) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    bootstrap.start(log = {})
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Surfaced through the Tor status line; nothing to do here.
                }
            }
        }
        setContent {
            XbtWalletTheme {
                XbtWalletApp()
            }
        }
    }
}
