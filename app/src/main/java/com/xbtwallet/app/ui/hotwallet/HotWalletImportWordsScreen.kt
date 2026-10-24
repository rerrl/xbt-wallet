package com.xbtwallet.app.ui.hotwallet

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.ui.theme.XbtWalletTheme

/** Shown when the phrase is the right length, spelled right, and still not a phrase. */
internal const val IMPORT_WORDS_CHECKSUM_FAIL =
    "Checksum invalid \u2014 check for a typo or wrong word order"

/**
 * One edit to the word field: the text after it, and where the cursor belongs in
 * that text.
 *
 * A plain pair rather than a `TextFieldValue`, so the rule that decides *what* the
 * edit is stays free of Compose and testable on the JVM; the composable turns it
 * into the field value.
 */
data class ImportWordsEdit(val text: String, val cursor: Int)

/**
 * The whole token [cursor] sits in or at the end of: the run of non-whitespace
 * characters around it, exactly as it was typed (not normalised).
 *
 * A cursor between two words — just after a space — is in no token at all, and
 * the empty string it returns is what hides the suggestion strip until the user
 * starts spelling something.
 */
internal fun tokenAtCursor(input: String, cursor: Int): String {
    val position = cursor.coerceIn(0, input.length)
    var start = position
    while (start > 0 && !input[start - 1].isWhitespace()) start--
    var end = position
    while (end < input.length && !input[end].isWhitespace()) end++
    return input.substring(start, end)
}

/**
 * The suggestions to show for the field's text with the cursor where it is, in
 * the order they are drawn.
 *
 * Empty — and the strip hidden — in three cases, and each of them is a decision:
 *
 *  - **the token is empty**, so there is nothing to complete (the usual state
 *    just after a word and its space);
 *  - **the token is already a complete BIP-39 word**, which needs no help and
 *    whose suggestions would only be noise under the cursor;
 *  - **no word starts with the token**, which is either a typo the user is about
 *    to fix or a word of their own — either way there is nothing to offer.
 *
 * The token is normalised before it is searched for, so a capital letter or a
 * stray character mid-word does not blank the strip.
 */
internal fun importWordsSuggestionStrip(input: String, cursor: Int): List<String> {
    val token = ImportWordsParser.tokenize(tokenAtCursor(input, cursor)).firstOrNull()
        ?: return emptyList()
    if (ImportWordsParser.isWord(token)) return emptyList()
    return ImportWordsParser.suggestions(token)
}

/**
 * The field's text and cursor after the user taps a suggestion.
 *
 * The half-typed token under the cursor is replaced by [word] and a space, which
 * is what leaves the cursor ready for the next word. Whitespace already sitting
 * after the token is absorbed into that one space — a chip tap never leaves the
 * user with a doubled separator to backspace over.
 */
internal fun applySuggestion(input: String, cursor: Int, word: String): ImportWordsEdit {
    val position = cursor.coerceIn(0, input.length)
    var start = position
    while (start > 0 && !input[start - 1].isWhitespace()) start--
    var end = position
    while (end < input.length && !input[end].isWhitespace()) end++
    var after = end
    while (after < input.length && input[after].isWhitespace()) after++

    val replacement = "$word "
    return ImportWordsEdit(
        text = input.substring(0, start) + replacement + input.substring(after),
        cursor = start + replacement.length,
    )
}

/** The progress line: `7 of 12`. */
internal fun importWordsProgress(entered: Int, expected: Int): String = "$entered of $expected"

/**
 * The complaint about one slot: `Word 7 is not in the BIP-39 wordlist`.
 *
 * [position] is one-based, the numbering of the words themselves.
 */
internal fun importWordsUnknownWordMessage(position: Int): String =
    "Word $position is not in the BIP-39 wordlist"

/**
 * What the line under the field says, or null when there is nothing to say.
 *
 * It is read straight off the state, so the line and the enabled button can never
 * disagree: while [ImportWordsParser.validate] is not [ImportWordsReason.OK]
 * there is exactly one thing worth telling the user, and when it *is* OK the
 * button says the rest.
 */
internal fun importWordsValidationLine(state: ImportWordsState, expectedCount: Int): String? =
    when (val reason = state.reason) {
        // Mid-typing, and one word over: both are answered by the count, which is
        // what the user is watching as they type.
        ImportWordsReason.INCOMPLETE, is ImportWordsReason.WRONG_COUNT ->
            importWordsProgress(state.count, expectedCount)
        // The first misspelt word only: fixing one at a time is how a phrase is
        // repaired, and a list of positions would be a wall of numbers.
        is ImportWordsReason.UNKNOWN_WORDS -> importWordsUnknownWordMessage(reason.positions.first())
        ImportWordsReason.CHECKSUM_FAIL -> IMPORT_WORDS_CHECKSUM_FAIL
        ImportWordsReason.OK -> null
    }

/**
 * **Import — 12/24 words** — the word-entry screen behind *Import hot wallet*.
 *
 * One multi-line field, and the four things that make typing a seed phrase
 * bearable and safe:
 *
 *  - **a suggestion strip** of wordlist completions for the token under the
 *    cursor (see [importWordsSuggestionStrip]), which is what makes four typed
 *    letters enough to be unambiguous — BIP-39's own property, and the reason a
 *    chip tap is a check rather than a convenience;
 *  - **a live line under the field** saying how far along the phrase is, which
 *    word is not a word, or that the checksum disagrees;
 *  - **an import button that is only ever enabled on a phrase that validates** —
 *    right length, every word known, checksum correct — so the app cannot start
 *    deriving a wallet from something that is not one;
 *  - **[WindowManager.LayoutParams.FLAG_SECURE] for as long as the screen is
 *    composed**, so a screenshot or a screen recording of the words is a blank
 *    frame. Cleared on dispose, like the recovery-words screen.
 *
 * **The words never leave memory.** They live in a plain `remember`, deliberately
 * **not** `rememberSaveable`: the navigation back stack is saved state, and a seed
 * phrase in saved state is a seed phrase written down by the system. A rotation
 * therefore loses what was typed — and a phrase the user has not finished
 * retyping is not yet a secret they were keeping anywhere. The holder they are
 * handed to on a successful import is the same in-memory-only
 * [HotWalletCreateFlow] the create walk uses, and it is wiped when the walk is
 * left unfinished.
 *
 * **What the keyboard is asked for, and what it cannot be asked.** The field
 * pins `autoCorrect = false` — a spelling corrector rewriting a seed phrase is
 * the one place its help is a hazard — and `KeyboardType.Text`, which suppresses
 * the capitalisation and (on most IMEs) the suggestion bar. The limit is worth
 * knowing: Compose's `KeyboardOptions` has no way to set
 * `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`, so an IME that keeps a
 * personal dictionary may still *learn* the words being typed here, and none of
 * the flags above reach that. Only the keyboard's own settings (or a platform
 * view's `setImeOptions`) can turn that off, and nothing in this app can do it on
 * the user's behalf.
 *
 * @param wordCount 12 or 24, already validated by the navigation guard. It picks
 *   the wording and the length the phrase is validated against.
 * @param flow the shared in-memory holder the finished words are handed to.
 * @param onWordsEntered called once the words are in the holder — the caller
 *   moves the walk on to the finish screen.
 * @param onBack the header's back affordance, one level up the walk.
 */
@Composable
fun HotWalletImportWordsScreen(
    wordCount: Int,
    flow: HotWalletCreateFlow,
    onWordsEntered: () -> Unit,
    onBack: () -> Unit,
) {
    // Plain remember, never rememberSaveable: these are the words of a seed
    // phrase, and saved state is written down by the system. See the class doc.
    var field by remember { mutableStateOf(TextFieldValue("")) }

    // What was typed, read once per recomposition: the state, the line under the
    // field and the button all come off this one value, so they cannot disagree.
    val state = ImportWordsParser.validate(field.text, wordCount)
    val validationLine = importWordsValidationLine(state, wordCount)
    val suggestions = importWordsSuggestionStrip(field.text, field.selection.end)

    // FLAG_SECURE for as long as this screen is composed: a seed phrase is being
    // typed into it. Cleared on the way out so the rest of the app is untouched.
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Button(
                    onClick = {
                        // The words, normalised — the same ones the validation
                        // just checked, so what is derived is what was seen.
                        flow.setMnemonic(state.words)
                        onWordsEntered()
                    },
                    enabled = state.isValid,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(HOT_WALLET_IMPORT_WALLET_BUTTON)
                }
            }
        },
    ) { innerPadding ->
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
                value = field,
                onValueChange = { field = it },
                label = { Text(importFieldLabel(wordCount)) },
                minLines = 4,
                keyboardOptions = KeyboardOptions(
                    // The non-deprecated spelling of the same flag: the older
                    // `autoCorrect` parameter still works but warns, and a wallet
                    // should not add build noise.
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                ),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )

            // The suggestion strip: directly under the field, where it sits above
            // the keyboard, and only when there is something to suggest.
            if (suggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                SuggestionStrip(
                    suggestions = suggestions,
                    onSuggestion = { word ->
                        val edit = applySuggestion(field.text, field.selection.end, word)
                        field = TextFieldValue(edit.text, TextRange(edit.cursor))
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val line = validationLine
            if (line != null) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * The completion chips: one tappable word each, scrolling sideways when there are
 * more than fit.
 *
 * A chip is a word the user is being offered, so it is set the way the words
 * themselves are — monospaced, so `l` and `1` and `rn` and `m` stay tellable
 * apart.
 */
@Composable
private fun SuggestionStrip(suggestions: List<String>, onSuggestion: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        suggestions.forEach { word ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .clickable { onSuggestion(word) }
                    .padding(end = 8.dp),
            ) {
                Text(
                    text = word,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun HotWalletImportWordsScreenPreview() {
    XbtWalletTheme {
        HotWalletImportWordsScreen(
            wordCount = 12,
            flow = HotWalletCreateFlow(),
            onWordsEntered = {},
            onBack = {},
        )
    }
}
