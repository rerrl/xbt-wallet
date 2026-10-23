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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.ui.theme.XbtWalletTheme

/**
 * **Import hot wallet** — the first step of the import path, and the screen that
 * asks how long the seed phrase is.
 *
 * Two options, 12 words or 24, and nothing else: no passphrase, no derivation
 * path, no "paste it another way". Which one the user picks only chooses the
 * wording of the word-entry screen that follows ([HotWalletImportWordsScreen]).
 *
 * **UI scaffolding only.** Nothing here reads, validates or stores key material;
 * the button picks a word count and navigates.
 */

/** The screen title. */
internal const val HOT_WALLET_IMPORT_TITLE = "Import hot wallet"

/** The screen's one paragraph: what importing does, and where the words live. */
internal const val HOT_WALLET_IMPORT_COPY =
    "Import an existing seed phrase onto this device. The words are stored on " +
        "this phone only."

/** The twelve-word option. */
internal const val HOT_WALLET_IMPORT_WORDS_12 = "12 words"

/** The twenty-four-word option. */
internal const val HOT_WALLET_IMPORT_WORDS_24 = "24 words"

/** The word-entry screen's title, twelve words. */
internal const val HOT_WALLET_IMPORT_WORDS_TITLE_12 = "Import \u2014 12 words"

/** The word-entry screen's title, twenty-four words. */
internal const val HOT_WALLET_IMPORT_WORDS_TITLE_24 = "Import \u2014 24 words"

/** The word field's label, twelve words. */
internal const val HOT_WALLET_IMPORT_FIELD_12 = "Enter your 12 words, separated by spaces"

/** The word field's label, twenty-four words. */
internal const val HOT_WALLET_IMPORT_FIELD_24 = "Enter your 24 words, separated by spaces"

/** The terminal button the word-entry screen shows disabled. */
internal const val HOT_WALLET_IMPORT_WALLET_BUTTON = "Import wallet"

/** The note under it, saying why it is disabled. */
internal const val HOT_WALLET_IMPORT_NOTE =
    "Import is not wired up yet \u2014 this is a UI preview."

/** The word-count option label for a count: `12 words` / `24 words`. */
internal fun importWordCountLabel(wordCount: Int): String =
    if (wordCount == 24) HOT_WALLET_IMPORT_WORDS_24 else HOT_WALLET_IMPORT_WORDS_12

/** The word-entry screen's title for a count: `Import — 12/24 words`. */
internal fun importWordsTitle(wordCount: Int): String =
    if (wordCount == 24) HOT_WALLET_IMPORT_WORDS_TITLE_24 else HOT_WALLET_IMPORT_WORDS_TITLE_12

/** The word field's label for a count: `Enter your 12/24 words, separated by spaces`. */
internal fun importFieldLabel(wordCount: Int): String =
    if (wordCount == 24) HOT_WALLET_IMPORT_FIELD_24 else HOT_WALLET_IMPORT_FIELD_12

@Composable
fun HotWalletImportScreen(
    onChooseWords: (wordCount: Int) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            HotWalletHeader(title = HOT_WALLET_IMPORT_TITLE, onBack = onBack)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = HOT_WALLET_IMPORT_COPY,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onChooseWords(12) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(HOT_WALLET_IMPORT_WORDS_12)
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { onChooseWords(24) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(HOT_WALLET_IMPORT_WORDS_24)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun HotWalletImportScreenPreview() {
    XbtWalletTheme {
        HotWalletImportScreen(onChooseWords = {}, onBack = {})
    }
}
