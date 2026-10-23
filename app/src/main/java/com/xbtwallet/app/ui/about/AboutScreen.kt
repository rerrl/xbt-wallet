package com.xbtwallet.app.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.ui.theme.XbtWalletTheme

/**
 * **About** — what this app is, in one short read.
 *
 * Reached from the About button at the top of the home landing page. It repeats
 * the app's own title and tagline (the same words Home shows) and then says the
 * one thing a watch-only coordinator has to say for itself: this phone holds
 * public keys only, so it can track balances and prepare transactions while the
 * seed stays on the air-gapped signer.
 *
 * The body copy is a placeholder — the final about text lands in a later pass —
 * and is kept verbatim in the composable so the wording is exactly what was
 * agreed.
 *
 * The screen owns no state and takes no state: it renders the copy and forwards
 * the back tap.
 *
 * @param onBack called when the user taps the back affordance.
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("\u2190 Back") }
                Spacer(modifier = Modifier.width(4.dp))
                Text("About", style = MaterialTheme.typography.titleLarge)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("XBT Wallet", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Simple, sovereign, for the people.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "XBT Wallet is a watch-only coordinator for your air-gapped " +
                    "signer: it holds public keys only, so this phone can track " +
                    "balances and prepare transactions while your seed stays " +
                    "offline.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun AboutScreenPreview() {
    XbtWalletTheme {
        AboutScreen(onBack = {})
    }
}
