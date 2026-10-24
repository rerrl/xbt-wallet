package com.xbtwallet.app.ui.signers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.chain.ConfirmationBucket
import com.xbtwallet.app.chain.confirmationBucket
import com.xbtwallet.app.chain.confirmationLabel

/**
 * The transaction palette and the confirmation badge, shared by the row list and
 * the detail screen so one transaction reads the same colour in both places.
 *
 * These were private to [TransactionsSection] when the row was the only view.
 * The detail screen needs the same in/out/amber semantics and the same badge, so
 * they live here once rather than being copied into a second file that would
 * drift.
 */

/** Value arrived: the balance card's green. */
internal val IncomingGreen = Color(0xFF4CAF50)

/** Value left (the fee included): the row's red. */
internal val OutgoingRed = Color(0xFFFF6B6B)

/** Mined but not yet settled. */
internal val PendingAmber = Color(0xFFFFC107)

/** Everything moved was ours and the amounts cancel. */
internal val NeutralGrey = Color(0xFFB6BCC4)

/**
 * The confirmation count as a small badge: red for unconfirmed, amber for the
 * shallow 1–5 range, green once a transaction is six blocks deep. The text comes
 * from [confirmationLabel] and carries the real depth up to 99, then caps at
 * `100+ confs`, because past that the exact count stops mattering on a row (and a
 * wider badge would break the row).
 *
 * A null count means the confirmation state is genuinely unknown — the fallback
 * lookup found the transaction but no height — and the badge says so in neutral
 * grey rather than claiming "Unconfirmed", which would be a false statement
 * about the mempool.
 */
@Composable
internal fun ConfirmationBadge(confirmations: Int?) {
    if (confirmations == null) {
        Text(
            text = "Unknown",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = NeutralGrey,
            modifier = Modifier
                .background(NeutralGrey.copy(alpha = 0.14f), MaterialTheme.shapes.small)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        return
    }
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
