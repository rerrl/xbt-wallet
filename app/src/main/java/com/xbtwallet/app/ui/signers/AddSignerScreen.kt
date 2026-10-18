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
 * It is deliberately a fork and not a form: there are exactly two ways to pair
 * with a signer, and both are named up front, in plain language, before either
 * button. Naming them together is what makes the choice legible instead of
 * implied — the screen opens by saying what each option is *for*, not by talking
 * the user out of one of them.
 *
 * 1. **Add watch-only xpub** — the live path. It hands off to
 *    [ImportXpubScreen], which takes a name and an account xpub and stores a
 *    watch-only signer. Only a public key ever crosses this boundary.
 * 2. **Create hot wallet** — **not available yet.** Generating a seed on this
 *    phone is a capability this build does not have, so the button is disabled
 *    and carries a small *not available yet* tag. It is presented as the
 *    convenience it will be (a hot wallet is cash in your pocket for small,
 *    everyday amounts), not as something to be talked out of — and there is no
 *    seed generation code anywhere behind the button.
 *
 * The screen owns no state; it renders two choices and forwards taps.
 *
 * A code note for the reader: **QR scanning is a later feature.** The primary
 * import path in the plan is a SeedSigner `crypto-account` QR export (PLAN.md
 * §4). Until that lands, [ImportXpubScreen] is the manual fallback — paste the
 * key — and this screen only advertises the manual path.
 */
@Composable
fun AddSignerScreen(
    onAddWatchOnly: () -> Unit,
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

            // The intro sits above both options: what each one is for, in the
            // order the buttons below appear. No avoidance language — a hot
            // wallet is a real thing this app intends to do, it just cannot do
            // it yet.
            Text(
                text = "Two ways to pair this phone with your keys.",
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

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Hot wallet \u2014 like cash in your pocket. Generate a seed " +
                    "on this device for small, everyday amounts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 1. The live path: a watch-only signer, keyed by its xpub.
            Button(
                onClick = onAddWatchOnly,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add watch-only xpub")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Paste the account-level xpub from your air-gapped signer. " +
                    "This phone holds public keys only, so it can watch the money " +
                    "but can never spend it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 2. The convenience path, not yet built. There is no seed code
            //    behind this button — the tag says why, and nothing more.
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Create hot wallet")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                NotAvailableTag()
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Seed generation on this phone is not available yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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

/**
 * A small capability badge for an option this build cannot do yet.
 *
 * It is a plain chip rather than a sentence: the fact belongs next to the thing
 * it qualifies, and a tag can be read at a glance where a paragraph cannot.
 */
@Composable
private fun NotAvailableTag(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            text = "Not available yet",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun AddSignerScreenPreview() {
    XbtWalletTheme {
        AddSignerScreen(onAddWatchOnly = {}, onBack = {})
    }
}
