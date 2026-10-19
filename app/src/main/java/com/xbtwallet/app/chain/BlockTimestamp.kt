package com.xbtwallet.app.chain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * When a block was mined, read off its own header.
 *
 * `blockchain.scripthash.get_history` reports the *height* a transaction was
 * mined at and nothing else — no wall-clock time. The time is in the block
 * header, which the server hands back over [`blockchain.block.header`]: 80
 * bytes, the fourth field of which is the miner's unix timestamp. This file
 * turns that hex into a number and that number into something a human reads, so
 * a transaction row can say *when* rather than only *how deep*.
 *
 * Both functions are pure and Android-free: the parsing is pinned against the
 * mainnet genesis header in `BlockTimestampTest`, and the formatting takes its
 * time zone (and locale) as parameters so its test does not depend on the
 * machine it runs on.
 */

/**
 * Byte offset of the timestamp field in an 80-byte block header. After the
 * 4-byte version, the 32-byte previous-block hash and the 32-byte merkle root.
 */
internal const val BLOCK_HEADER_TIMESTAMP_OFFSET: Int = 68

/** An 80-byte block header is 160 hex characters. */
private const val BLOCK_HEADER_HEX_LENGTH: Int = 160

/** `19 Oct 2026 · 14:32` — day, abbreviated month, year, a middot, 24h time. */
private const val TIMESTAMP_PATTERN: String = "d MMM yyyy \u00b7 HH:mm"

/**
 * The unix timestamp (epoch **seconds**) of the block whose 80-byte header is
 * [headerHex].
 *
 * The field is a little-endian `uint32`, like every other multi-byte integer in
 * the protocol.
 *
 * @throws IllegalArgumentException when [headerHex] is not 160 hex characters —
 *   a caller that passes the wrong bytes (a transaction, a truncated line) gets
 *   an error rather than a plausible-looking number.
 */
fun blockTimestampFromHeaderHex(headerHex: String): Long {
    require(headerHex.length == BLOCK_HEADER_HEX_LENGTH) {
        "a block header is $BLOCK_HEADER_HEX_LENGTH hex characters, got ${headerHex.length}"
    }
    // Validate the whole string: a header that is the right length but not hex
    // is still not a header, and reading only the timestamp's bytes would let it
    // through.
    for (index in headerHex.indices) hexNibble(headerHex[index])

    var seconds = 0L
    for (byteIndex in 0 until 4) {
        val charIndex = (BLOCK_HEADER_TIMESTAMP_OFFSET + byteIndex) * 2
        val byte = (hexNibble(headerHex[charIndex]) shl 4) or hexNibble(headerHex[charIndex + 1])
        // Little-endian: the first byte of the field is the least significant.
        seconds = seconds or (byte.toLong() shl (8 * byteIndex))
    }
    return seconds
}

/** One hex digit, or an error — no silent zero on a bad character. */
private fun hexNibble(character: Char): Int = when (character) {
    in '0'..'9' -> character - '0'
    in 'a'..'f' -> character - 'a' + 10
    in 'A'..'F' -> character - 'A' + 10
    else -> throw IllegalArgumentException("not a hex digit: '$character'")
}

/**
 * A block timestamp (epoch seconds) as a short, local-time string:
 * `19 Oct 2026 · 14:32`.
 *
 * The time zone and locale are parameters with the device defaults, so the app
 * reads in the user's own frame of reference while the test pins them and stays
 * machine-independent. This is a *display* helper only — nothing compares or
 * stores the formatted value.
 */
fun formatBlockTimestamp(
    epochSeconds: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val format = SimpleDateFormat(TIMESTAMP_PATTERN, locale)
    format.timeZone = timeZone
    return format.format(Date(epochSeconds * 1_000L))
}
