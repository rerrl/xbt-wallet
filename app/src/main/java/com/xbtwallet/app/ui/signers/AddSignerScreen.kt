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
import androidx.compose.material3.FilledTonalButton
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
import com.xbtwallet.app.data.SignerOrigin
import com.xbtwallet.app.ui.Route
import com.xbtwallet.app.ui.Screen
import com.xbtwallet.app.ui.theme.XbtWalletTheme

/**
 * **Add wallet** — the one way a wallet enters the app.
 *
 * Three rows, one wallet. The difference between them is only the QR they can
 * read and the origin they declare, never the record they write: all three end up
 * calling `SignerStore.add` with a watch-only xpub plus whatever fingerprint and
 * derivation path the QR carried. There is no second wallet type, and no second
 * kind of key.
 *
 *  1. **SeedSigner** (filled) → [AddSigningDeviceScreen], declaring
 *     [SignerOrigin.SEEDSIGNER_UR]. The SeedSigner *Export Xpub → single-sig →
 *     native segwit → Animated* path: the account key wrapped in the
 *     fountain-coded BC-UR format a SeedSigner speaks.
 *  2. **Other signing device** (tonal) → the same [AddSigningDeviceScreen],
 *     declaring [SignerOrigin.SIGNING_DEVICE_UR]. Keystone, Jade — the same
 *     animated BC-UR wrapper, a different device.
 *  3. **Watch-only xpub** (outlined) → [ImportXpubScreen], declaring
 *     [SignerOrigin.TEXT_QR]. A bare `xpub`/`ypub`/`zpub`, a `bitcoin:` URI, or a
 *     BIP-32 key expression (`[ab12cd34/84'/0'/0']zpub…`). This is the path for
 *     a coordinator's export, for a Coldcard's text/JSON export, and for anything
 *     that puts the key itself in the code — Coldcard is deliberately *not*
 *     promised on the signing-device rows, because its classic exports are not
 *     BC-UR.
 *
 * Three rows rather than one clever scanner because the formats want different
 * things from the user: the animated path needs the camera held on a moving code
 * until the export is whole, and saying so up front is friendlier than a generic
 * scanner that guesses. Splitting the two animated rows is not about the QR —
 * both read the same BC-UR payload — but about what the app is allowed to claim
 * afterwards: only the SeedSigner row earns the orange *seedsigner* tag. Splitting
 * them also keeps the text path exactly as it was — a regression there would be
 * invisible until someone's export stopped importing.
 *
 * The hot-wallet path was removed rather than deferred. Generating a seed on
 * this phone is no longer a "not yet" — keeping the seed off the phone is a
 * stated product stance, and a watch-only app that offers to create a hot
 * wallet invites exactly the behaviour the product exists to prevent. Spending
 * stays a QR round-trip to the signer.
 *
 * The screen owns no state; it renders the copy and forwards the taps — one
 * [onChoose] carrying *which row* was tapped, because that is exactly the fact
 * the two animated rows cannot otherwise tell apart. What a row opens is
 * [routeFor], a pure function, so the mapping from "the user tapped SeedSigner"
 * to "the record will say SeedSigner" is pinned by a JVM test rather than living
 * only in this composable's wiring.
 *
 * The copy lives in the constants below, free of Compose imports, so
 * [AddSignerCopyTest] can pin it as Kotlin rather than a rendering detail a JVM
 * test cannot reach.
 */

/** The header's back affordance, unchanged. */
internal const val ADD_SIGNER_BACK_LABEL = "\u2190 Back"

/** The screen title, in the app's user-facing word for the unit: a wallet. */
internal const val ADD_SIGNER_TITLE = "Add wallet"

/**
 * The one intro above the three rows — it says what a wallet *is* on this phone
 * before offering the ways to add one.
 */
internal const val ADD_SIGNER_INTRO =
    "Add a wallet to watch. The phone holds public keys only \u2014 it can check " +
        "balances and prepare transactions, but it can never spend. Keys never " +
        "leave your signer."

/** The first row's label: the SeedSigner's own animated BC-UR export. */
internal const val ADD_SEEDSIGNER_BUTTON = "SeedSigner"

/** The caption under the first row, walking the user through the SeedSigner's menu. */
internal const val ADD_SEEDSIGNER_CAPTION =
    "Export Xpub \u2192 single-sig \u2192 native segwit \u2192 Animated QR on your " +
        "SeedSigner, then scan it here."

/** The second row's label: any other signing device's animated BC-UR export. */
internal const val ADD_SIGNING_DEVICE_BUTTON = "Other signing device"

/** The caption under the second row, naming the devices it covers. */
internal const val ADD_SIGNING_DEVICE_CAPTION =
    "Keystone, Jade \u2014 scan the device's animated BC-UR export QR."

/** The third row's label: a plain text xpub from any coordinator. */
internal const val ADD_WATCH_ONLY_BUTTON = "Watch-only xpub"

/** The caption under the third row, carrying the Coldcard case. */
internal const val ADD_WATCH_ONLY_CAPTION =
    "Scan a plain xpub QR from any coordinator \u2014 a bare xpub, a bitcoin: URI, " +
        "or a [fingerprint/path]xpub expression. Coldcard's text export lives here."

/** The footer panel, closing on why watching is enough. */
internal const val ADD_SIGNER_FOOTER =
    "Watching is enough to receive and check balances. Spending stays a QR " +
        "round-trip to your signer \u2014 which is how your keys stay off this phone."

/**
 * The three rows of the chooser, in the order they are shown.
 *
 * The order is the declaration order, because it is the product order: the
 * SeedSigner is what this app is built around, "some other device" is the honest
 * fallback for everyone else, and the plain text xpub is last. Each entry carries
 * the words the row is drawn with, so the row list and its copy cannot drift.
 *
 * Not `internal` like the copy constants beside it, because it is the argument
 * the public [AddSignerScreen] takes — the one thing the screen has to be told,
 * since it cannot know which physical device is in the user's hand.
 */
enum class AddWalletRow(val label: String, val caption: String) {
    SEEDSIGNER(ADD_SEEDSIGNER_BUTTON, ADD_SEEDSIGNER_CAPTION),
    OTHER_SIGNING_DEVICE(ADD_SIGNING_DEVICE_BUTTON, ADD_SIGNING_DEVICE_CAPTION),
    WATCH_ONLY_XPUB(ADD_WATCH_ONLY_BUTTON, ADD_WATCH_ONLY_CAPTION),
}

/**
 * What a row opens: the destination, plus the [SignerOrigin] the form behind it
 * will stamp on the wallet it saves.
 *
 * This is the whole point of the three-row chooser — the two animated rows open
 * the *same* screen, and only this mapping says which one the user meant — so it
 * is a pure function, testable on the JVM, and the wiring in `XbtWalletApp` is a
 * one-line `nav.push(routeFor(row))` that cannot quietly disagree with it.
 */
internal fun routeFor(row: AddWalletRow): Route = when (row) {
    AddWalletRow.SEEDSIGNER ->
        Route(Screen.ADD_SIGNING_DEVICE, origin = SignerOrigin.SEEDSIGNER_UR)

    AddWalletRow.OTHER_SIGNING_DEVICE ->
        Route(Screen.ADD_SIGNING_DEVICE, origin = SignerOrigin.SIGNING_DEVICE_UR)

    AddWalletRow.WATCH_ONLY_XPUB ->
        Route(Screen.IMPORT_XPUB, origin = SignerOrigin.TEXT_QR)
}

@Composable
fun AddSignerScreen(
    onChoose: (AddWalletRow) -> Unit,
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
                TextButton(onClick = onBack) { Text(ADD_SIGNER_BACK_LABEL) }
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = ADD_SIGNER_TITLE, style = MaterialTheme.typography.titleLarge)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // The screen's one message, above the three rows.
            Text(
                text = ADD_SIGNER_INTRO,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- 1. The SeedSigner path: filled, the screen's default. -------
            Button(
                onClick = { onChoose(AddWalletRow.SEEDSIGNER) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(AddWalletRow.SEEDSIGNER.label)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = AddWalletRow.SEEDSIGNER.caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- 2. Any other signing device: tonal, one step quieter. ------
            FilledTonalButton(
                onClick = { onChoose(AddWalletRow.OTHER_SIGNING_DEVICE) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(AddWalletRow.OTHER_SIGNING_DEVICE.label)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = AddWalletRow.OTHER_SIGNING_DEVICE.caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- 3. The watch-only path: the key itself, in a text QR. ------
            OutlinedButton(
                onClick = { onChoose(AddWalletRow.WATCH_ONLY_XPUB) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(AddWalletRow.WATCH_ONLY_XPUB.label)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = AddWalletRow.WATCH_ONLY_XPUB.caption,
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
                    text = ADD_SIGNER_FOOTER,
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
        AddSignerScreen(onChoose = {}, onBack = {})
    }
}
