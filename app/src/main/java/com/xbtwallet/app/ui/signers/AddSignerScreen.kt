package com.xbtwallet.app.ui.signers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.ui.theme.XbtWalletTheme

/**
 * **Add signer** — the one way a signer enters the app.
 *
 * Two buttons, one signer. The difference between them is only the QR they can
 * read, never the record they write: both end up calling `SignerStore.add` with a
 * watch-only xpub plus whatever fingerprint and derivation path the QR carried.
 * There is no second signer type, and no second kind of key.
 *
 *  - **Add signing device** → [AddSigningDeviceScreen]. BC-UR `crypto-account`,
 *    usually animated: SeedSigner's *Export Xpub → native segwit → Animated*,
 *    Keystone, Jade. The same account key, wrapped in the fountain-coded format
 *    those signers speak.
 *  - **Add watch-only xpub** → [ImportXpubScreen]. Text QR: a bare `xpub`/`ypub`/
 *    `zpub`, a `bitcoin:` URI, or a BIP-32 key expression
 *    (`[ab12cd34/84'/0'/0']zpub…`). This is the path for a coordinator's export,
 *    for a Coldcard's text/JSON export, and for anything that puts the key itself
 *    in the code — Coldcard is deliberately *not* promised on the signing-device
 *    button, because its classic exports are not BC-UR.
 *
 * Two buttons rather than one clever scanner because the two formats want
 * different things from the user: the animated path needs the camera held on a
 * moving code until the export is whole, and saying so up front is friendlier
 * than a generic scanner that guesses. Splitting them also keeps the text path
 * exactly as it was — a regression there would be invisible until someone's
 * export stopped importing.
 *
 * The hot-wallet path was removed rather than deferred. Generating a seed on
 * this phone is no longer a "not yet" — keeping the seed off the phone is a
 * stated product stance, and a watch-only app that offers to create a hot
 * wallet invites exactly the behaviour the product exists to prevent. Spending
 * stays a QR round-trip to the signer.
 *
 * The screen owns no state; it renders the description and forwards the taps.
 */
@Composable
fun AddSignerScreen(
    onAddWatchOnly: () -> Unit,
    onAddSigningDevice: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("\u2190 Back") }
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Add signer", style = MaterialTheme.typography.titleLarge)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // The screen's one message, above the single action it describes.
            Text(
                text = "For SeedSigner users and watch-only wallets. SeedSigner " +
                    "users can still use this to create transactions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Watch-only \u2014 a watch-only xpub is for use with your " +
                    "SeedSigner, or any wallet you want to keep an eye on, without " +
                    "the seed ever touching this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- The signing-device path: BC-UR, usually animated. -----------
            Button(
                onClick = onAddSigningDevice,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add signing device")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "SeedSigner, Keystone, Jade \u2014 scan the signer's animated " +
                    "export QR.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- The watch-only path: the key itself, in a text QR. ----------
            OutlinedButton(
                onClick = onAddWatchOnly,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add watch-only xpub")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Scan the xpub QR from your signer or coordinator \u2014 a bare " +
                    "xpub, a bitcoin: URI, or a [fingerprint/path]xpub key expression. " +
                    "This phone holds public keys only, so it can watch the money but " +
                    "can never spend it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(28.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Watching is enough to receive and to check your balance. " +
                        "Spending stays a QR round-trip to the signer, which is what " +
                        "keeps your keys off this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun AddSignerScreenPreview() {
    XbtWalletTheme {
        AddSignerScreen(onAddWatchOnly = {}, onAddSigningDevice = {}, onBack = {})
    }
}
