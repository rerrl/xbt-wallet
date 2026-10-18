package com.xbtwallet.app.ui.signers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.chain.ScannedTransaction
import kotlinx.coroutines.launch

/**
 * The **Transactions** section on the signer detail screen.
 *
 * It renders the list [com.xbtwallet.app.chain.aggregateTransactions] built from
 * the same gap-limit scan the balance above it comes from — same server, same
 * connection, no extra RPC and no second scan. Because the section is a
 * subordinate panel inside the scrollable detail screen, it does *not* use the
 * full-screen [ScanStateBody] gate: it mirrors the signer screen's phase machine
 * compactly instead. Loading is the scan's own progress, the empty case says so
 * plainly, and a failed scan shows the same kind of short error line the Retry
 * pattern uses rather than crashing or inventing an empty list.
 *
 * The list is capped at [MAX_TRANSACTIONS] rows, with a line saying so when the
 * account has more — a watch-only coordinator with years of history should not
 * ask a phone to lay out thousands of rows in one column.
 */
@Composable
internal fun TransactionsSection(state: SignerChainState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Transactions",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))

        when (state.phase) {
            // The scan is the loading state: while the balance card says
            // "Loading balance", the list says the same thing about addresses.
            ChainPhase.IDLE, ChainPhase.LOADING -> TransactionsLoading(state)
            ChainPhase.NO_SERVER -> TransactionsNote(
                "Connect a server to see this account's transactions.",
            )
            ChainPhase.ERROR -> TransactionsFailed(state)
            ChainPhase.READY -> TransactionsList(state.snapshot?.transactions.orEmpty())
        }
    }
}

@Composable
private fun TransactionsLoading(state: SignerChainState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = if (state.scannedAddressCount > 0) {
                "Checking addresses\u2026 ${state.scannedAddressCount} so far"
            } else {
                "Checking addresses\u2026"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A quiet, non-error explanation (used when no endpoint is saved). */
@Composable
private fun TransactionsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The failed-scan state, in the signer screen's error pattern: a short line in
 * the error colour and the same Retry action. The long transport sentence lives
 * once, in the balance card above; repeating it here would print the same
 * paragraph twice on one screen.
 */
@Composable
private fun TransactionsFailed(state: SignerChainState) {
    val scope = rememberCoroutineScope()
    Text(
        text = "Transaction history unavailable.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    TextButton(onClick = { scope.launch { state.reload() } }) { Text("Retry") }
}

@Composable
private fun TransactionsList(transactions: List<ScannedTransaction>) {
    if (transactions.isEmpty()) {
        Text(
            text = "No transactions yet",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "None of this account's scanned addresses have seen a transaction.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val shown = transactions.take(MAX_TRANSACTIONS)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shown.forEach { transaction -> TransactionRow(transaction) }
        if (transactions.size > shown.size) {
            Text(
                text = "Showing the latest ${shown.size} of ${transactions.size} transactions.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One transaction: the truncated txid (monospace) with its confirmation under
 * it. Tapping copies the full txid — the same one-line platform clipboard call
 * the address viewer uses, no extra dependency.
 *
 * There is deliberately no incoming/outgoing arrow: neither `get_history` nor
 * `listunspent` can tell a send from a receive without fetching the transaction
 * (a send with change looks exactly like a receive in `listunspent`), and the
 * card says to omit an arrow that is not cheaply derivable. See
 * [com.xbtwallet.app.chain.aggregateTransactions].
 */
@Composable
private fun TransactionRow(transaction: ScannedTransaction) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                copyToClipboard(context, transaction.txHash)
                Toast.makeText(context, "Transaction id copied", Toast.LENGTH_SHORT).show()
            }
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = shortenedMiddle(transaction.txHash),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = buildString {
                append(if (transaction.confirmed) "height ${formatSats(transaction.height.toLong())}" else "unconfirmed")
                append("   \u00b7   tap to copy")
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How many transactions the section lays out before it stops and says so. */
private const val MAX_TRANSACTIONS = 50

/** Plain Android clipboard call — one line of platform, no extra Compose dependency. */
private fun copyToClipboard(context: Context, txHash: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Transaction id", txHash))
}
