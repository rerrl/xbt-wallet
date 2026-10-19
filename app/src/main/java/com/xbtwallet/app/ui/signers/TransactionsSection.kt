package com.xbtwallet.app.ui.signers

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.chain.ConfirmationBucket
import com.xbtwallet.app.chain.TransactionDirection
import com.xbtwallet.app.chain.TransactionRow
import com.xbtwallet.app.chain.confirmationBucket
import com.xbtwallet.app.chain.confirmationLabel
import com.xbtwallet.app.chain.formatBlockTimestamp
import com.xbtwallet.app.chain.hasUndatedConfirmedRows
import kotlinx.coroutines.launch

/**
 * The **Transactions** section on the signer detail screen.
 *
 * It renders the list [com.xbtwallet.app.chain.buildTransactionRows] built from
 * the same gap-limit scan the balance above it comes from — same server, same
 * connection, no second scan. Because the section is a subordinate panel inside
 * the scrollable detail screen, it does *not* use the full-screen [ScanStateBody]
 * gate: it mirrors the signer screen's phase machine compactly instead. Loading
 * is the scan's own progress, the empty case says so plainly, and a failed scan
 * shows the same kind of short error line the Retry pattern uses rather than
 * crashing or inventing an empty list.
 *
 * Each row is the wallet's net effect — value in minus value out, change
 * excluded from the "out" side — with a confirmation badge; see
 * [TransactionRowItem]. Rows are static: no tap target, no detail view.
 *
 * The list is capped at [MAX_TRANSACTIONS] rows, with a line saying so when the
 * account has more — a watch-only coordinator with years of history should not
 * ask a phone to lay out thousands of rows in one column.
 *
 * A row that could not be dated is explained once, above the list, rather than
 * row by row (see [hasUndatedConfirmedRows]).
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
            ChainPhase.READY -> TransactionsReady(state)
        }
    }
}

/**
 * The loaded state: the snapshot's rows, rendering straight from the snapshot.
 *
 * Because the screen opens on a cached snapshot, the list is simply what the
 * snapshot holds. A failed refresh shows its short line (with a Retry) above the
 * rows and the rows keep rendering throughout; the running-rescan signal lives in
 * the header's single spinner, not here.
 */
@Composable
private fun TransactionsReady(state: SignerChainState) {
    val scope = rememberCoroutineScope()
    if (state.syncFailed) {
        Text(
            text = "Couldn't refresh \u2014 showing the last synced values.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = { scope.launch { state.reload() } }) { Text("Retry") }
        Spacer(modifier = Modifier.height(6.dp))
    }
    val transactions = state.snapshot?.transactions.orEmpty()
    // One line for the whole list when a block header did not come back, so a
    // row with no date reads as a known gap rather than a rendering mistake. It
    // is said once, above the list — the failure is per block, and several rows
    // mined in the same block share it. See `hasUndatedConfirmedRows`.
    if (transactions.hasUndatedConfirmedRows()) {
        Text(
            text = "Some transaction dates couldn't be loaded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
    }
    TransactionsList(transactions)
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
private fun TransactionsList(transactions: List<TransactionRow>) {
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
        shown.forEach { transaction -> TransactionRowItem(transaction) }
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
 * One transaction row: the wallet's net effect on the left (green when value
 * came in, red when it went out), a confirmation badge on the right, and the
 * truncated txid underneath in monospace.
 *
 * The row is deliberately **not** interactive. The previous iteration made it
 * tap-to-copy; this one shows an amount, and a tap that copies a txid underneath
 * a number a user might be trying to read is a trap. There is no detail view to
 * expand into either — the card keeps rows at a glance.
 */
@Composable
private fun TransactionRowItem(transaction: TransactionRow) {
    val tint = when (transaction.direction) {
        TransactionDirection.IN -> IncomingGreen
        TransactionDirection.OUT -> OutgoingRed
        TransactionDirection.NEUTRAL -> NeutralGrey
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.10f), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = transactionAmountLabel(transaction.netSatoshis),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = tint,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            ConfirmationBadge(transaction.confirmations)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = shortenedMiddle(transaction.txHash),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // When the transaction happened: the block's own timestamp for a mined
        // row, the word "Unconfirmed" for one still in the mempool, and nothing
        // at all for a mined row whose header we could not read — a failure to
        // date a transaction must not put invented text on screen.
        transactionTimestampLabel(transaction)?.let { label ->
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The date line for one row, or null when there is nothing honest to say.
 *
 * An unmined transaction has no block to read a time from, so it reads
 * "Unconfirmed" — the same word the badge uses, in the date line's place. A
 * mined row with a known timestamp is formatted in the device's time zone; a
 * mined row whose header could not be fetched stays blank rather than guessing.
 */
private fun transactionTimestampLabel(transaction: TransactionRow): String? = when {
    transaction.confirmations <= 0 -> "Unconfirmed"
    transaction.blockTimestampEpochSeconds != null -> formatBlockTimestamp(transaction.blockTimestampEpochSeconds)
    else -> null
}

/**
 * The confirmation count as a small badge: red for unconfirmed, amber for the
 * shallow 1–5 range, green once a transaction is six blocks deep. The text comes
 * from [confirmationLabel] and carries the real depth up to 99, then caps at
 * `100+ confs`, because past that the exact count stops mattering (and a wider
 * badge would break the row).
 */
@Composable
private fun ConfirmationBadge(confirmations: Int) {
    val color = when (confirmationBucket(confirmations)) {
        ConfirmationBucket.UNCONFIRMED -> OutgoingRed
        ConfirmationBucket.SHALLOW -> PendingAmber
        ConfirmationBucket.SETTLED -> IncomingGreen
    }
    Text(
        text = confirmationLabel(confirmations),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** How many transactions the section lays out before it stops and says so. */
private const val MAX_TRANSACTIONS = 50

/** The row palette: in, out, and the amber of "mined but not settled". */
private val IncomingGreen = Color(0xFF4CAF50)
private val OutgoingRed = Color(0xFFFF6B6B)
private val PendingAmber = Color(0xFFFFC107)
private val NeutralGrey = Color(0xFFB6BCC4)
