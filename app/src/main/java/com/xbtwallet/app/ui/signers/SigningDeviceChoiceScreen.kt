package com.xbtwallet.app.ui.signers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.ui.theme.XbtWalletTheme

/**
 * **Signing device** — the follow-up question the Add-wallet screen's first card
 * asks, and the only place the two animated-BC-UR rows live now.
 *
 * Both options open the *same* import form ([AddSigningDeviceScreen]): a
 * fountain-coded BC-UR `crypto-account` scan. What they cannot otherwise tell
 * apart is which device is in the user's hand, and that is the only thing that
 * differs — in the copy, and in the [com.xbtwallet.app.data.SignerOrigin] the
 * saved wallet is stamped with. Only the SeedSigner option earns the orange
 * *seedsigner* tag; the other one is deliberately neutral, because the app was
 * built around the SeedSigner and a Keystone or Jade export should not be
 * labelled as if it came off one.
 *
 * The mapping from option to destination-plus-origin is [routeFor], a pure
 * function pinned by a JVM test, so it cannot drift from what this screen draws.
 *
 * The screen owns no state; it renders the copy and forwards the tap as
 * *which option*, because that is exactly the fact the form behind it cannot
 * recover on its own.
 */

/** The screen title: the question this screen asks. */
internal const val SIGNING_DEVICE_CHOICE_TITLE = "Signing device"

@Composable
fun SigningDeviceChoiceScreen(
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
            SignerScreenHeader(title = SIGNING_DEVICE_CHOICE_TITLE, onBack = onBack)

            Spacer(modifier = Modifier.height(16.dp))

            // --- 1. The SeedSigner: the path this app is built around. --------
            AddWalletCard(
                title = AddWalletRow.SEEDSIGNER.label,
                copy = AddWalletRow.SEEDSIGNER.caption,
            ) {
                FilledTonalButton(
                    onClick = { onChoose(AddWalletRow.SEEDSIGNER) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(ADD_SIGNER_SELECT_BUTTON)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- 2. Any other signing device: the honest fallback. -----------
            AddWalletCard(
                title = AddWalletRow.OTHER_SIGNING_DEVICE.label,
                copy = AddWalletRow.OTHER_SIGNING_DEVICE.caption,
            ) {
                FilledTonalButton(
                    onClick = { onChoose(AddWalletRow.OTHER_SIGNING_DEVICE) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(ADD_SIGNER_SELECT_BUTTON)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun SigningDeviceChoiceScreenPreview() {
    XbtWalletTheme {
        SigningDeviceChoiceScreen(onChoose = {}, onBack = {})
    }
}
