package com.xbtwallet.app.ui.hotwallet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.ui.theme.XbtWalletTheme

/**
 * **Import — 24 words** — the word-entry screen behind *Import hot wallet*.
 *
 * A single multi-line field, backed by a plain remembered string: the user's
 * words, separated by spaces. **There is deliberately no validation and no
 * parsing** — no BIP-39 wordlist check, no checksum, no phoneme of derivation.
 * This screen is UI scaffolding for the hot-wallet phase, and it says so
 * underneath its disabled terminal button rather than pretending to check
 * anything it cannot yet check.
 *
 * Nothing typed here is stored, logged or sent anywhere: the string lives in
 * this composable's saved state and dies with the screen.
 *
 * @param wordCount 12 or 24, already validated by the navigation guard. It only
 *   picks the wording — the field itself is the same field either way.
 */
@Composable
fun HotWalletImportWordsScreen(
    wordCount: Int,
    onBack: () -> Unit,
) {
    var words by rememberSaveable { mutableStateOf("") }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            HotWalletHeader(title = importWordsTitle(wordCount), onBack = onBack)

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = words,
                onValueChange = { words = it },
                label = { Text(importFieldLabel(wordCount)) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Disabled on purpose: there is nothing behind it yet.
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(HOT_WALLET_IMPORT_WALLET_BUTTON)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = HOT_WALLET_IMPORT_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun HotWalletImportWordsScreenPreview() {
    XbtWalletTheme {
        HotWalletImportWordsScreen(wordCount = 12, onBack = {})
    }
}
