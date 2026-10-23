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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 * **Create hot wallet** — the first step of the dice-roll path.
 *
 * The screen offers exactly one way to create a hot wallet: dice rolls, at
 * either word count. That is the ratified decision, not a gap — the entropy comes
 * from the user's own hand on a physical die, so the screen deliberately has no
 * "generate for me" option, no password field and no passphrase shortcut. Twelve
 * or twenty-four words is the only choice on offer.
 *
 * **UI scaffolding only.** Nothing on this screen creates, derives or stores key
 * material; the steps after it are the dice input screen and, eventually, the
 * hot-wallet phase itself. The buttons here navigate, and that is all they do.
 */

/** The screen title. */
internal const val HOT_WALLET_CREATE_TITLE = "Create hot wallet"

/** The screen's one paragraph: where the entropy comes from, and from where not. */
internal const val HOT_WALLET_CREATE_COPY =
    "A new hot wallet is created from dice rolls: you roll a die and tap each " +
        "result, so the entropy comes from your hands \u2014 not from this phone " +
        "and not from anyone else."

/** The twelve-word dice-roll option; it is also the dice screen's title. */
internal const val HOT_WALLET_DICE_ROLL_12 = "Dice roll \u2014 12 words"

/** The twenty-four-word dice-roll option; it is also the dice screen's title. */
internal const val HOT_WALLET_DICE_ROLL_24 = "Dice roll \u2014 24 words"

/**
 * The dice-roll option (and dice screen title) for a word count.
 *
 * Only 12 and 24 reach this — the navigation guard admits nothing else — and 24
 * is tested first so an unexpected value falls to the twelve-word label rather
 * than to a twenty-four-word one that would promise more entropy than the user
 * entered.
 */
internal fun diceRollLabel(wordCount: Int): String =
    if (wordCount == 24) HOT_WALLET_DICE_ROLL_24 else HOT_WALLET_DICE_ROLL_12

/**
 * The word count a hot-wallet route carries, or null when it carries none.
 *
 * The word count travels in the route's free-form `address` slot as `"12"` or
 * `"24"` — the same segment Send uses for its selected outpoints — which is why
 * nothing here parses a route codec of its own. Only those two values are
 * accepted: anything else (an empty slot, a stack saved by a build that had no
 * such screens) is `null`, and the caller restarts at Home rather than opening a
 * dice screen that does not know how long the seed should be.
 *
 * A pure function so a JVM test can pin the accepted values without an activity.
 */
internal fun hotWalletWordCount(address: String?): Int? =
    address?.toIntOrNull()?.takeIf { it == 12 || it == 24 }

/**
 * The `← Back` + title header the hot-wallet screens share, so the three of them
 * cannot drift apart on something as small as a spacer.
 */
@Composable
internal fun HotWalletHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text(HOT_WALLET_BACK_LABEL) }
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = title, style = MaterialTheme.typography.titleLarge)
    }
}

/** The header's back affordance, the app's shared wording for it. */
internal const val HOT_WALLET_BACK_LABEL = "\u2190 Back"

@Composable
fun HotWalletCreateScreen(
    onDiceRoll: (wordCount: Int) -> Unit,
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
            HotWalletHeader(title = HOT_WALLET_CREATE_TITLE, onBack = onBack)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = HOT_WALLET_CREATE_COPY,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onDiceRoll(12) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(HOT_WALLET_DICE_ROLL_12)
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { onDiceRoll(24) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(HOT_WALLET_DICE_ROLL_24)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun HotWalletCreateScreenPreview() {
    XbtWalletTheme {
        HotWalletCreateScreen(onDiceRoll = {}, onBack = {})
    }
}
