package com.xbtwallet.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.ui.theme.XbtWalletTheme

/**
 * Home screen — the app's hub.
 *
 * Live actions: **Import xpub** (paste an account xpub), **Addresses** (browse
 * derived addresses; enabled once at least one xpub exists) and **Server**
 * (private Electrum endpoint). Receive and Send stay disabled placeholders: they
 * need the signer round-trip and chain data, neither of which exists yet.
 *
 * The screen is a pure function of [walletCount] plus callbacks — it owns no
 * state, so it can be previewed and reasoned about without a running app.
 */
@Composable
fun HomeScreen(
    walletCount: Int,
    onOpenImportWallet: () -> Unit,
    onOpenAddresses: () -> Unit,
    onOpenServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "XBT",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "SeedSigner coordinator \u2014 watch-only",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(40.dp))

            Button(
                onClick = onOpenImportWallet,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Import xpub")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Only meaningful once a public key has been imported, so it stays
            // disabled until then rather than opening an empty screen.
            Button(
                onClick = onOpenAddresses,
                enabled = walletCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Addresses")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when (walletCount) {
                    0 -> "No xpub imported yet \u2014 import an account xpub to begin."
                    1 -> "1 xpub saved."
                    else -> "$walletCount xpubs saved."
                },
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Placeholder actions: still disabled on purpose.
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Receive")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Send")
            }

            Spacer(modifier = Modifier.height(28.dp))

            OutlinedButton(
                onClick = onOpenServer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Server")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Connect to your own Electrum server",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun HomeScreenPreview() {
    XbtWalletTheme {
        HomeScreen(
            walletCount = 2,
            onOpenImportWallet = {},
            onOpenAddresses = {},
            onOpenServer = {},
        )
    }
}
