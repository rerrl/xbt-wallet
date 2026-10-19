package com.xbtwallet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xbtwallet.app.electrum.TorGateway
import com.xbtwallet.app.electrum.tor.AndroidTorBootstrap
import com.xbtwallet.app.ui.XbtWalletApp
import com.xbtwallet.app.ui.theme.XbtWalletTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The bundled Tor is process-wide state: install it before any screen can
        // dial an `.onion` server. Tor itself is not started here — the first
        // onion connection starts it (see AndroidTorBootstrap).
        TorGateway.install(AndroidTorBootstrap(applicationContext))
        setContent {
            XbtWalletTheme {
                XbtWalletApp()
            }
        }
    }
}
