package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.chain.TransactionDetail
import com.xbtwallet.app.chain.TransactionDirection

/**
 * The strings the transaction detail screen puts around a fetched transaction.
 *
 * Kept out of the composable for the same reason as [transactionAmountLabel]:
 * a number a user reads about money is logic, and it is pinned by a JVM test.
 * Two rules run through all of it — unknown values are an em dash, never a zero,
 * and satoshis are grouped for reading without depending on the device locale,
 * because the same transaction must read the same on every phone.
 */

/** What the screen prints where a value is genuinely unknown. */
internal const val UNKNOWN_VALUE = "\u2014"

/** The word an output's address takes when its script cannot be rendered. */
internal const val UNKNOWN_SCRIPT = "Unknown script"

/**
 * The detail screen's fixed wording.
 *
 * Pinned here as `const val` so the screen and its tests read the same strings:
 * a label that drifts between the composable and the test is a test that no
 * longer describes the screen. Dynamic lines ("Fetching N parent transactions…",
 * the amount, the fee) are built from the helpers below.
 */
internal object TransactionDetailStrings {
    const val BACK = "\u2190 Back"
    const val TITLE = "Transaction"
    const val STATUS = "Status"
    const val CONFIRMATIONS = "Confirmations"
    const val BLOCK_HEIGHT = "Block height"
    const val BLOCK_TIME = "Block time"
    const val SIZE = "Size"
    const val INPUTS = "Inputs"
    const val OUTPUTS = "Outputs"
    const val FEE = "Fee"
    const val LOAD_FEE = "Load fee"
    const val RETRY = "Retry"
    const val TRANSACTION_ID = "Transaction ID"
    const val COPIED = "Copied"
    const val YOU = "you"
    const val LOADING = "Loading transaction\u2026"
    const val LOAD_FAILED = "Couldn't load this transaction."
    const val FEE_LOAD_FAILED = "Couldn't load the fee."

    /** "Fetching N parent transactions…" — [total] is the distinct parent count. */
    fun fetchingParents(total: Int): String = "Fetching $total parent transactions\u2026"
}

/** "919,231": a non-negative integer grouped with commas, no locale. */
internal fun groupedThousands(value: Long): String {
    if (value < 0) return "-" + groupedThousands(-value)
    val text = value.toString()
    if (text.length <= 3) return text
    val builder = StringBuilder(text.length + text.length / 3)
    for ((index, character) in text.withIndex()) {
        if (index > 0 && (text.length - index) % 3 == 0) builder.append(',')
        builder.append(character)
    }
    return builder.toString()
}

/**
 * "#919,231" — a block height as the details block prints it.
 *
 * A height of zero or less is not a block: an unconfirmed (mempool)
 * transaction reports height 0 or -1, which must read as the unknown em dash
 * rather than "#0"/"#-1" — a block that does not exist. Only a mined
 * transaction (height > 0) prints a height.
 */
internal fun formatBlockHeight(height: Int?): String =
    height?.takeIf { it > 0 }?.let { "#${groupedThousands(it.toLong())}" } ?: UNKNOWN_VALUE

/**
 * "12,345 sats" — an amount in satoshis with its unit.
 *
 * Deliberately *not* named `formatSats`: the balance presentation already owns
 * that name for the bare grouped number, and two same-signature functions with
 * different parameter names are a JVM clash in Kotlin. This one is the detail
 * screen's unit-suffixed variant.
 */
internal fun formatSatsWithUnit(satoshis: Long): String = "${groupedThousands(satoshis)} sats"

/** "140 vB (560 WU)" — the size line's two measures. */
internal fun formatSize(vsize: Long, weight: Long): String = "$vsize vB ($weight WU)"

/**
 * The confirmation count as the details block prints it: the exact number, with
 * no cap. The row's badge stops at 100+ because a row is narrow; this screen has
 * room to say how deep a transaction really is.
 */
internal fun exactConfirmations(confirmations: Int?): String =
    confirmations?.toString() ?: UNKNOWN_VALUE

/** "Confirmed" / "Unconfirmed (in mempool)" / "Unknown" — never a guess. */
internal fun statusLabel(confirmed: Boolean?): String = when (confirmed) {
    true -> "Confirmed"
    false -> "Unconfirmed (in mempool)"
    null -> "Unknown"
}

/**
 * The fee line, or null when the fee is not known.
 *
 * "1,234 sats (8.8 sat/vB)": satoshis grouped for reading and the rate in
 * satoshis per virtual byte, to one decimal, trimmed when whole. The rate is
 * computed with integer arithmetic — fee × 10 rounded to the nearest tenth —
 * so the printed number cannot wobble on a float.
 */
internal fun formatFee(feeSatoshis: Long?, vsize: Long): String? {
    val fee = feeSatoshis ?: return null
    return "${formatSatsWithUnit(fee)} (${feeRateLabel(fee, vsize)} sat/vB)"
}

/** "8.8" or "9" — satoshis per virtual byte, one decimal at most. */
internal fun feeRateLabel(feeSatoshis: Long, vsize: Long): String {
    if (vsize <= 0) return UNKNOWN_VALUE
    val tenths = (feeSatoshis * 10 + vsize / 2) / vsize
    val whole = tenths / 10
    val remainder = tenths % 10
    return if (remainder == 0L) whole.toString() else "$whole.$remainder"
}

/** The direction a detail's net amount should be coloured and signed by. */
internal fun detailDirection(netSatoshis: Long?): TransactionDirection = when {
    netSatoshis == null -> TransactionDirection.NEUTRAL
    netSatoshis > 0 -> TransactionDirection.IN
    netSatoshis < 0 -> TransactionDirection.OUT
    else -> TransactionDirection.NEUTRAL
}

/** "ab12cd34…ef01:3" — a spent outpoint shortened for a row. */
internal fun shortenedOutpoint(txHash: String, previousOutputIndex: Int): String =
    "${shortenedMiddle(txHash)}:$previousOutputIndex"

/** "ab12cd34…ef01" — an output address shortened for a row. */
internal fun shortenedAddress(address: String?): String =
    address?.let { shortenedMiddle(it) } ?: UNKNOWN_SCRIPT

/**
 * The net amount as the header prints it: [transactionAmountLabel]'s wording,
 * or the em dash when the net effect cannot be computed honestly.
 */
internal fun detailAmountLabel(netSatoshis: Long?): String =
    netSatoshis?.let { transactionAmountLabel(it) } ?: UNKNOWN_VALUE

/**
 * The number of distinct parent transactions a fee load still has to fetch, or
 * zero when every input already has a value.
 */
internal fun missingParentCount(detail: TransactionDetail): Int =
    detail.inputs.asSequence()
        .filter { it.satoshis == null }
        .map { it.previousTxHash }
        .distinct()
        .count()
