package com.xbtwallet.app.ui.signers

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * How a satoshi balance is turned into the four strings the signer screen shows.
 *
 * These are plain Kotlin on purpose — no Compose, no `String.format` locale
 * surprises — so a JVM test pins the exact text a user sees. A wallet that
 * shows a comma in the wrong place is a wallet that shows the wrong amount to
 * somebody, so the formatting is treated as logic, not as a rendering detail.
 *
 * The amounts:
 *  - **sats** is the primary number (`1,234,567 sats`): it is the unit this
 *    wallet actually stores and the only one with no rounding.
 *  - **XBT** is the same balance in whole coins (8 decimals, `1 XBT = 100 000 000
 *    sats`).
 *  - the two fiat lines are the placeholder conversions from
 *    [com.xbtwallet.app.PlaceholderPrices] — BTC and XBT share the sat unit, so
 *    both lines describe the *same* coin amount at two made-up rates.
 */

/** `1,234,567` — the sat count with thousands separators, no unit. */
fun formatSats(sats: Long): String = groupThousands(sats.toString())

/** `0.01234567` — the coin amount, always 8 decimals. */
fun formatCoins(sats: Long): String =
    BigDecimal.valueOf(sats, COIN_DECIMALS).setScale(COIN_DECIMALS).toPlainString()

/**
 * The USD value of a coin amount at a placeholder rate, e.g. `$5.21`.
 *
 * The rate is a `Double` only because that is how the placeholder constants are
 * written; it is converted through its decimal *text* so `420.69` does not
 * arrive as `420.689999…`, and rounded to cents at the end. Nothing here is a
 * real quote — see [com.xbtwallet.app.PlaceholderPrices].
 */
fun formatUsd(coins: BigDecimal, usdPerCoin: Double): String {
    val usd = coins.multiply(BigDecimal.valueOf(usdPerCoin)).setScale(2, RoundingMode.HALF_UP)
    return "$" + groupThousands(usd.toPlainString())
}

/** Decimal places in a coin amount: a satoshi is 1e-8 of a coin. */
const val COIN_DECIMALS: Int = 8

/**
 * Inserts a comma every three digits, in the integer part only.
 *
 * Written out rather than delegated to `String.format`, whose output depends on
 * the device locale: a wallet's balance should read the same on every phone.
 * A leading minus is preserved for completeness (a balance cannot be negative,
 * but a diff or a fee might be printed through here later).
 */
internal fun groupThousands(value: String): String {
    val negative = value.startsWith("-")
    val digits = if (negative) value.substring(1) else value
    val dot = digits.indexOf('.')
    val integerPart = if (dot >= 0) digits.substring(0, dot) else digits
    val rest = if (dot >= 0) digits.substring(dot) else ""

    val grouped = StringBuilder()
    for ((position, digit) in integerPart.withIndex()) {
        if (position > 0 && (integerPart.length - position) % 3 == 0) grouped.append(',')
        grouped.append(digit)
    }
    return (if (negative) "-" else "") + grouped + rest
}
