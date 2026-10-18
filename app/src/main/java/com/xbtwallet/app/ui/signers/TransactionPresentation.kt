package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.chain.TransactionDirection

/**
 * How a transaction's net effect is written on a row, and which colour it takes.
 *
 * Kept out of the composable for the same reason as [formatCoins]: the text a
 * wallet shows about an amount is logic, and it is pinned by a JVM test. The
 * conventions come from the balance card above it — coin amounts are always
 * eight decimals (`formatCoins`), and XBT is the unit this wallet names.
 *
 * The sign is the wallet's, not the transaction's: `+… in` when the account
 * gained value, `-… out` when it lost value (the fee included, because the fee
 * really did leave the wallet). A transaction whose outputs to us and inputs
 * from us cancel out — a self-send that only moved coins between our own
 * addresses — gets no sign, because claiming "in" or "out" would be a lie.
 */
fun transactionAmountLabel(netSatoshis: Long): String = when {
    netSatoshis > 0 -> "+${formatCoins(netSatoshis)} XBT in"
    netSatoshis < 0 -> "-${formatCoins(-netSatoshis)} XBT out"
    else -> "${formatCoins(0L)} XBT"
}

/** The direction a row's colour follows. */
fun transactionDirection(netSatoshis: Long): TransactionDirection = when {
    netSatoshis > 0 -> TransactionDirection.IN
    netSatoshis < 0 -> TransactionDirection.OUT
    else -> TransactionDirection.NEUTRAL
}
