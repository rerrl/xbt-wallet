package com.xbtwallet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xbtwallet.app.ui.home.HomeScreen
import com.xbtwallet.app.ui.theme.XbtWalletTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            XbtWalletTheme {
                HomeScreen()
            }
        }
    }
}
