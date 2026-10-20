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
 * header, which the server hands back over [`blockchain.block.header`]: at
 * least 80 bytes, the fourth field of which is the miner's unix timestamp. This
 * file turns that hex into a number and that number into something a human
 * reads, so a transaction row can say *when* rather than only *how deep*.
 *
 * A header is *at least* 80 bytes: a chain may append its own extension after
 * those fields (the BLAKE2b hardfork the test server indexes does), which is why
 * the parser reads the 80-byte prefix rather than demanding one exact length —
 * see [blockTimestampFromHeaderHex].
 *
 * Both functions are pure and Android-free: the parsing is pinned against the
 * mainnet genesis header in `BlockTimestampTest`, and the formatting takes its
 * time zone (and locale) as parameters so its test does not depend on the
 * machine it runs on.
 */

/**
 * Byte offset of the timestamp field in a block header. After the 4-byte
 * version, the 32-byte previous-block hash and the 32-byte merkle root.
 *
 * Every chain that speaks this protocol keeps those six fields, and in that
 * order — a chain that extends its header appends bytes *after* them (see
 * [blockTimestampFromHeaderHex]) — so this offset is where the miner's clock
 * lives whatever else the header carries.
 */
internal const val BLOCK_HEADER_TIMESTAMP_OFFSET: Int = 68

/**
 * The shortest a block header can be: 80 bytes, 160 hex characters.
 *
 * A header may be longer. The Bitcoin Knots BLAKE2b hardfork — the chain
 * Andrew's test server indexes — switches to BLAKE2b proof-of-work at height
 * 961640 and carries an 84-byte PoW extension after the ordinary 80-byte
 * header, so its `blockchain.block.header` reply is 164 bytes (328 hex
 * characters) from that height on. Reading only the first 80 bytes is correct
 * for both shapes; demanding exactly 160 characters is what turned every recent
 * transaction into an undated row (see [buildTransactionRows]).
 */
internal const val MIN_BLOCK_HEADER_HEX_LENGTH: Int = 160

/** `19 Oct 2026 · 14:32` — day, abbreviated month, year, a middot, 24h time. */
private const val TIMESTAMP_PATTERN: String = "d MMM yyyy \u00b7 HH:mm"

/**
 * The unix timestamp (epoch **seconds**) of the block whose header is
 * [headerHex].
 *
 * The field is a little-endian `uint32`, like every other multi-byte integer in
 * the protocol, and it sits at [BLOCK_HEADER_TIMESTAMP_OFFSET] of the header's
 * ordinary 80-byte prefix. Only that prefix is read, so a header that carries
 * an extension after it is fine: the timestamp is in the same place either way.
 *
 * **Why the length check is "at least" rather than "exactly".** An 80-byte
 * header (160 hex characters) is the shortest one there is, and it is all an
 * ElectrumX/electrs server ever sends. The chain Andrew's test server indexes
 * is a Bitcoin Knots build that switched to BLAKE2b proof-of-work at height
 * 961640 and appends an 84-byte PoW extension to every header from there on, so
 * `blockchain.block.header` answers with 164 bytes (328 hex characters). The
 * first version of this function required exactly 160 characters, which threw
 * on every block at or above the fork height — and because the caller treats a
 * dating failure as "leave the date unknown", every recent transaction silently
 * lost its date line on the device.
 *
 * The string must still be hex and an even number of characters, so a truncated
 * line or a stray byte is an error rather than a plausible-looking number.
 *
 * @throws IllegalArgumentException when [headerHex] is shorter than 80 bytes,
 *   has an odd number of characters, or is not hex throughout.
 */
fun blockTimestampFromHeaderHex(headerHex: String): Long {
    require(headerHex.length >= MIN_BLOCK_HEADER_HEX_LENGTH && headerHex.length % 2 == 0) {
        "a block header is at least $MIN_BLOCK_HEADER_HEX_LENGTH hex characters " +
            "(an even number of them), got ${headerHex.length}"
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
