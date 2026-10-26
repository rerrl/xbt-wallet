package com.xbtwallet.app.ui.hotwallet

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.crypto.DiceEntropy
import com.xbtwallet.app.ui.OrangeOutlineButton
import com.xbtwallet.app.ui.theme.XbtWalletTheme

/** The screen's title. Static: the word-count toggle carries the count now. */
internal const val HOT_WALLET_DICE_TITLE = "Dice roll"

/** The screen's one paragraph: how the rolls become words. */
internal const val HOT_WALLET_DICE_COPY =
    "Roll a die and tap the result. 50 rolls make 12 words, 99 rolls make 24 " +
        "\u2014 take your time, every roll adds entropy."

/** The twelve-word segment of the word-count toggle. */
internal const val HOT_WALLET_DICE_WORDS_12 = "12 words"

/** The twenty-four-word segment of the word-count toggle. */
internal const val HOT_WALLET_DICE_WORDS_24 = "24 words"

/** The label of the button that removes the last roll. */
internal const val HOT_WALLET_DICE_UNDO = "Undo"

/** The terminal button: it derives the words and goes on to the backup step. */
internal const val HOT_WALLET_DICE_CREATE_BUTTON = "Create wallet"

/**
 * The readout's fixed height: enough for the longest readout this screen can
 * produce — 99 monospace digits of the 24-word walk, four lines at the text
 * style the readout uses — plus the box's own 12dp padding on both sides.
 *
 * A constant rather than a measurement on purpose: the whole point is that the
 * readout's size does not depend on what is in it.
 */
private val DICE_READOUT_HEIGHT = 120.dp

/**
 * **Dice roll** — the dice-entry screen behind *Create hot wallet*.
 *
 * The interaction is the one the ratified design calls for: the user rolls a
 * physical die, taps the face it landed on, and the digit is appended to a
 * growing string. The counter says how far along the roll is (50 rolls make 12
 * words, 99 make 24 — see [DiceRollInput.targetRollsFor]), the readout shows the
 * digits so far, and *Undo* removes the last one.
 *
 * This screen also owns the word count the create screen used to ask for: the
 * 12/24 toggle sits directly under the copy, and switching it rebuilds the input
 * for that many rolls. The toggle is **disabled as soon as any roll has been
 * entered** (`enabled = rolls.isEmpty()`), so an accidental tap cannot throw
 * away entropy already committed — there is no confirm dialog, the choice is
 * simply frozen once rolling has begun.
 *
 * The digits are held only as the user's typed input. Nothing is rolled by the
 * phone and nothing is stored: the rules of the input live in [DiceRollInput]
 * (plain Kotlin, pinned by a JVM test), while the screen itself is stateless
 * beyond that one remembered helper.
 *
 * The terminal *Create wallet* button is live exactly when the input is full
 * ([DiceRollInput.isFull] — 50 rolls for 12 words, 99 for 24). Tapping it turns
 * the rolls into the recovery words and hands them to the shared
 * [HotWalletCreateFlow], then goes on to the backup step that shows them. The
 * words never touch the route: see [HotWalletCreateFlow] for why.
 *
 * @param flow the walk's in-memory holder for the words it derives.
 * @param onCreateWallet navigation: on to the backup step. The caller pushes it;
 *   this screen only says when.
 * @param onBack the header's back affordance, one level up the walk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotWalletDiceScreen(
    flow: HotWalletCreateFlow,
    onCreateWallet: () -> Unit,
    onBack: () -> Unit,
) {
    // The count is this screen's own state now: 12 is the default, and the
    // toggle below is the only thing that changes it. It may only change while
    // nothing has been rolled, because the input is rebuilt with the new target.
    var wordCount by remember { mutableStateOf(12) }

    val targetRolls = DiceRollInput.targetRollsFor(wordCount)
    // The helper is the rule-keeper (what a legal roll is, when it is full);
    // `rolls` is the copy of its result Compose can observe, re-read after every
    // edit. The helper itself is not observable state, on purpose — it stays a
    // plain object a JVM test can drive. Both are keyed on the word count, so a
    // switch starts a fresh, empty input rather than one sized for the old count.
    val input = remember(wordCount) { DiceRollInput(targetRolls) }
    var rolls by remember(wordCount) { mutableStateOf(input.rolls) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // The keypad, Undo and Create live in the bottom bar, not at the end of
        // the scrolling column. That is the fix for the mid-tap shift: the
        // readout's height is no longer able to push them, because they are not
        // above it in the first place — they are pinned to the bottom of the
        // window, in a bar sized by its own content.
        bottomBar = {
            DiceControls(
                undoEnabled = rolls.isNotEmpty(),
                createEnabled = input.isFull,
                onDigit = { digit ->
                    input.append(digit)
                    rolls = input.rolls
                },
                onUndo = {
                    input.backspace()
                    rolls = input.rolls
                },
                onCreate = {
                    // Live once every roll is in: it turns the rolls into the
                    // recovery words and hands them to the flow (never onto the
                    // route — the back stack is saved state), then the caller
                    // walks on to the backup step.
                    flow.setMnemonic(DiceEntropy.rollsToMnemonic(input.rolls))
                    onCreateWallet()
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                // Still scrollable: on a short window the header, copy, toggle
                // and readout can want more room than there is, and scrolling is
                // the honest answer. The controls are not in here, so this scroll
                // can never move them.
                .verticalScroll(rememberScrollState()),
        ) {
            HotWalletHeader(title = HOT_WALLET_DICE_TITLE, onBack = onBack)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = HOT_WALLET_DICE_COPY,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // How long the seed will be. Frozen once rolling starts: no way to
            // lose entropy that is already in, and nothing to confirm.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = wordCount == 12,
                    onClick = { wordCount = 12 },
                    enabled = rolls.isEmpty(),
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    label = { Text(HOT_WALLET_DICE_WORDS_12) },
                )
                SegmentedButton(
                    selected = wordCount == 24,
                    onClick = { wordCount = 24 },
                    enabled = rolls.isEmpty(),
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    label = { Text(HOT_WALLET_DICE_WORDS_24) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // How far along the roll is: "3 / 50 rolls".
            Text(
                text = "${rolls.length} / $targetRolls rolls",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // The digits themselves, in a box of a fixed height. This is the other
            // half of the layout fix: the readout cannot grow line-by-line as
            // digits arrive, so nothing anywhere is pushed by a roll.
            RollsReadout(rolls = rolls)

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * The digits entered so far, in a fixed-height box that scrolls inside itself.
 *
 * The height is sized for the longest target this screen can ask for — 99
 * monospace digits of the 24-word walk — so the full readout is visible without
 * scrolling on an ordinary phone, and scrolls internally rather than reflowing
 * the screen on a narrow or landscape one. Either way it never changes size, and
 * that is the point: the controls are in the bottom bar and this can no longer
 * affect them, but a stable readout also keeps the counter and the toggle above
 * it still.
 */
@Composable
private fun RollsReadout(rolls: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .height(DICE_READOUT_HEIGHT),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                text = rolls,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * The pinned cluster: two rows of die faces, *Undo*, and the terminal *Create
 * wallet*.
 *
 * Everything here is always in the same place. The bar's own height is fixed by
 * its content — six 64dp faces, the two buttons and the gaps between them — and
 * none of it depends on how many rolls have been entered.
 */
@Composable
private fun DiceControls(
    undoEnabled: Boolean,
    createEnabled: Boolean,
    onDigit: (Int) -> Unit,
    onUndo: () -> Unit,
    onCreate: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            DiceKeyRow(digits = listOf(1, 2, 3), onDigit = onDigit)

            Spacer(modifier = Modifier.height(8.dp))

            DiceKeyRow(digits = listOf(4, 5, 6), onDigit = onDigit)

            Spacer(modifier = Modifier.height(8.dp))

            // The same full-width secondary style as the rest of the app, and
            // disabled rather than absent while there is nothing to undo.
            OrangeOutlineButton(
                onClick = onUndo,
                modifier = Modifier.fillMaxWidth(),
                enabled = undoEnabled,
            ) {
                Text(HOT_WALLET_DICE_UNDO)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onCreate,
                enabled = createEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(HOT_WALLET_DICE_CREATE_BUTTON)
            }
        }
    }
}

/**
 * One row of three die faces, drawn as equal-width outlined buttons.
 *
 * A helper rather than six hand-written buttons so the two rows cannot end up
 * different sizes; a die face is a die face. They are deliberately tall — 64dp,
 * with the digit at title size — because they are tapped fifty to ninety-nine
 * times in a row and want to be the easiest target on the screen.
 */
@Composable
private fun DiceKeyRow(digits: List<Int>, onDigit: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        digits.forEachIndexed { index, digit ->
            if (index > 0) Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = { onDigit(digit) },
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
            ) {
                Text(digit.toString(), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun HotWalletDiceScreenPreview() {
    XbtWalletTheme {
        HotWalletDiceScreen(flow = HotWalletCreateFlow(), onCreateWallet = {}, onBack = {})
    }
}