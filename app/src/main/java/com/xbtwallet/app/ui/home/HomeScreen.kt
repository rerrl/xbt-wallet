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
 * Home screen for the scaffold.
 *
 * The wallet actions (load a watch-only xpub, receive, send) are inert
 * placeholders. "Server" is the first real entry point: it opens the private
 * Electrum server screen.
 */
@Composable
fun HomeScreen(
    onOpenServer: () -> Unit = {},
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
                text = "xbt-wallet",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "SeedSigner-first XBT wallet \u2014 scaffold v0",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Placeholder actions: disabled on purpose, no functionality yet.
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Load Wallet")
            }

            Spacer(modifier = Modifier.height(12.dp))

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

            // The first working action: private Electrum server setup.
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
        HomeScreen()
    }
}
