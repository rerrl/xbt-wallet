package com.xbtwallet.app.ui.hotwallet

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.ui.OrangeOutlineButton
import com.xbtwallet.app.ui.theme.XbtWalletTheme

/** The screen's title, in the header. */
internal const val HOT_WALLET_BACKUP_TITLE = "Recovery words"

/**
 * The warning shown *instead of* the words until the user asks for them.
 *
 * Hidden behind a deliberate tap rather than shown on arrival: a seed on screen
 * the moment the screen opens is a seed in whatever the phone is pointed at.
 */
internal const val HOT_WALLET_BACKUP_COPY =
    "Your recovery words are the only backup of this wallet. Write them down on " +
        "paper, in order, and keep them somewhere safe. Anyone who sees them can " +
        "take your coins \u2014 never photograph or screenshot them."

/** The button that reveals the words. */
internal const val HOT_WALLET_BACKUP_REVEAL = "Tap to reveal your recovery words"

/** The button that accepts the words have been written down. */
internal const val HOT_WALLET_BACKUP_CONFIRM = "I've written them down"

/**
 * **Recovery words** — the write-it-down step of the create-hot-wallet walk.
 *
 * The words come in from the shared [HotWalletCreateFlow], never off the route:
 * see that class for why a seed cannot ride the back stack. The screen only
 * *displays* them, and only after an explicit tap — until then it shows the
 * warning instead, so a glance at the phone on arrival reveals nothing.
 *
 * **Screenshots are blocked while the words are on screen.** The activity window
 * is flagged [WindowManager.LayoutParams.FLAG_SECURE] from the moment of the
 * reveal and the flag is cleared in [DisposableEffect] when this screen leaves
 * composition, so a screenshot or a screen recording of the words yields a blank
 * frame — and, just as importantly, the rest of the app is not left
 * screenshot-proof afterwards.
 *
 * The confirm button is pinned to the bottom and stays disabled until the words
 * have been revealed: there is nothing to have written down before that.
 *
 * @param words the recovery words, in order. Never empty: the navigation guard
 *   ahead of this screen restarts at Home when there is no mnemonic.
 * @param onConfirm go on to name the wallet; only reachable once revealed.
 * @param onBack the header's back affordance, one level up the walk.
 */
@Composable
fun HotWalletBackupScreen(
    words: List<String>,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }

    // FLAG_SECURE for exactly as long as the words are both on screen and this
    // screen is composed. Keyed on nothing (`Unit`): the effect is created when
    // the reveal flips it into composition and disposed when the screen is left,
    // which is precisely the window the flag belongs in.
    if (revealed) {
        val activity = LocalActivity.current
        DisposableEffect(Unit) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Button(
                    onClick = onConfirm,
                    enabled = revealed,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(HOT_WALLET_BACKUP_CONFIRM)
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
            HotWalletHeader(title = HOT_WALLET_BACKUP_TITLE, onBack = onBack)

            Spacer(modifier = Modifier.height(16.dp))

            if (revealed) {
                RecoveryWordGrid(words = words)
            } else {
                Text(
                    text = HOT_WALLET_BACKUP_COPY,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(16.dp))

                OrangeOutlineButton(
                    onClick = { revealed = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(HOT_WALLET_BACKUP_REVEAL)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * The words themselves: numbered, monospaced, and in two columns.
 *
 * The number is not decoration. Writing a seed down is a transcription job, and
 * the order is the whole of the backup — so every word carries the position it
 * belongs in, and the two columns read down-then-across the way the list does
 * (1–6 beside 7–12, or 1–12 beside 13–24). Monospace so a column of words lines
 * up and an `l`/`1` confusion is at least visible.
 */
@Composable
private fun RecoveryWordGrid(words: List<String>) {
    // The left column takes the extra word when the count is odd, so a 24-word
    // seed is 12 + 12 and a 12-word seed is 6 + 6.
    val leftCount = (words.size + 1) / 2
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            words.take(leftCount).forEachIndexed { index, word ->
                RecoveryWordRow(number = index + 1, word = word)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            words.drop(leftCount).forEachIndexed { index, word ->
                RecoveryWordRow(number = leftCount + index + 1, word = word)
            }
        }
    }
}

/** One numbered word, the number dimmed so the words stay the thing being read. */
@Composable
private fun RecoveryWordRow(number: Int, word: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = word,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    Spacer(modifier = Modifier.height(6.dp))
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun HotWalletBackupScreenPreview() {
    XbtWalletTheme {
        HotWalletBackupScreen(
            words = List(12) { "word$it" },
            onConfirm = {},
            onBack = {},
        )
    }
}
