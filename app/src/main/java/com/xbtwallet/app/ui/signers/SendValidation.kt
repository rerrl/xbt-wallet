package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.crypto.Bech32
import com.xbtwallet.app.crypto.Bech32Exception
import com.xbtwallet.app.crypto.SegwitAddress

/**
 * Recipient-address and fee-rate helpers for the Send screen.
 *
 * Deliberately Android-free and Compose-free: these are plain top-level
 * functions so they can be driven on the JVM with no device, no camera and no
 * composition — the same rule the `crypto/` and `chain/` packages follow. The
 * screen only renders what they return; every bit of the parsing and validation
 * reasoning lives here, where it is testable.
 */

/**
 * The address carried by [rawInput], stripped of the wrapping a QR or a paste
 * often brings.
 *
 * Three things are removed, in this order: surrounding whitespace; a leading
 * `bitcoin:` URI scheme, matched case-insensitively; and any `?…` query (a
 * BIP-21 URI's `amount`, `label`, `message`, … are not this wallet's business
 * yet). What is left is the bare address a validator can read. A bare address
 * passes through unchanged — the transformations are all no-ops on it.
 */
fun extractAddress(rawInput: String): String {
    var value = rawInput.trim()
    val scheme = "bitcoin:"
    if (value.length >= scheme.length && value.substring(0, scheme.length).equals(scheme, ignoreCase = true)) {
        value = value.substring(scheme.length)
    }
    val query = value.indexOf('?')
    if (query >= 0) value = value.substring(0, query)
    return value.trim()
}

/**
 * Validates a recipient address, returning `null` when it is one this wallet can
 * pay, or a user-facing error string otherwise.
 *
 * This is a **BIP-84 mainnet** app, so the rules are exactly that narrow:
 *
 * - empty (after [extractAddress]) → "Enter a recipient address.";
 * - anything [Bech32.decode] rejects (bad checksum, bad characters, not
 *   bech32/bech32m at all) → "Not a valid Bitcoin address.";
 * - an HRP other than `bc` — testnet's `tb`, regtest's `bcrt` — is a *valid*
 *   address for a network we do not run on, but from the user's point of view it
 *   is still not one they can be paid at, so it reads the same as any other
 *   unparseable input: "Not a valid Bitcoin address.";
 * - a witness version this wallet does not build for → "Unsupported address
 *   type." Version 0 must carry a 20-byte program (P2WPKH — the only script the
 *   wallet derives); version 1 must carry a 32-byte program (P2TR). Every other
 *   version, and a 0/1 with the wrong program length, is unsupported.
 */
fun validateRecipientAddress(input: String): String? {
    val address = extractAddress(input)
    if (address.isEmpty()) return "Enter a recipient address."

    val decoded = try {
        Bech32.decode(address)
    } catch (e: Bech32Exception) {
        return "Not a valid Bitcoin address."
    }

    if (decoded.hrp != SegwitAddress.MAINNET_HRP) return "Not a valid Bitcoin address."
    if (decoded.data.isEmpty()) return "Not a valid Bitcoin address."

    val witnessVersion = decoded.data[0].toInt() and 0xFF
    val program = try {
        SegwitAddress.convertBits(decoded.data.drop(1).toByteArray(), 5, 8, false)
    } catch (e: Bech32Exception) {
        return "Not a valid Bitcoin address."
    }

    return when (witnessVersion) {
        0 -> if (program.size == 20) null else "Unsupported address type."
        1 -> if (program.size == 32) null else "Unsupported address type."
        else -> "Unsupported address type."
    }
}

/**
 * Parses a fee rate in whole sat/vB, or null when [input] is not one.
 *
 * Only a whole number of at least 1 is accepted — a sub-1 fee rate is not a
 * thing a user would mean, and a fractional one is either a typo or a different
 * unit than this field's label claims. Leading and trailing whitespace is
 * tolerated (paste, autocorrect); a blank string is null, not zero.
 */
fun parseFeeRate(input: String): Long? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val value = trimmed.toLongOrNull() ?: return null
    return if (value >= 1) value else null
}

/**
 * The estimated size, in virtual bytes, of a send that spends [inputCount]
 * P2WPKH inputs and carries two outputs: the recipient and the change.
 *
 * Deliberately conservative, and hard-coded rather than derived: the wallet
 * only ever spends P2WPKH (BIP-84) inputs, so the weights are known without
 * knowing anything about the actual UTXOs. 10 vB of transaction overhead, 68 vB
 * per input, and 62 vB for the two outputs. Guessing high costs the user a few
 * sats of headroom; guessing low would hand them a fee estimate their wallet
 * cannot pay, which is the worse error.
 */
fun estimateSendVsize(inputCount: Int): Int = 10 + 68 * inputCount + 62

/**
 * The most that can be sent out of [totalSatoshis] selected coins without the
 * estimated fee eating into it, or 0 when the fee would take everything.
 *
 * This is recomputed whenever the fee rate changes — the max spendable is a
 * property of the fee, not a constant — and it is floored at 0 rather than
 * allowed to go negative: "you can send -40 sats" is not a sentence.
 */
fun maxSendableSatoshis(
    totalSatoshis: Long,
    inputCount: Int,
    feeRateSatPerVb: Long,
): Long {
    val estimatedFee = estimateSendVsize(inputCount).toLong() * feeRateSatPerVb
    return (totalSatoshis - estimatedFee).coerceAtLeast(0L)
}

/**
 * Parses an amount in whole satoshis, or null when [input] is not one.
 *
 * The same shape as [parseFeeRate], for the same reasons: whole numbers only
 * (the field is labelled in sats, so a fractional value is a different unit
 * than the label claims), at least 1 sat, leading and trailing whitespace
 * tolerated, and a blank string null rather than zero. [toLongOrNull] is what
 * keeps an amount too big for a `Long` null instead of silently wrapping to a
 * negative number.
 */
fun parseAmountSats(input: String): Long? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val value = trimmed.toLongOrNull() ?: return null
    return if (value >= 1) value else null
}

/**
 * Splits [address] into the three pieces the Send screen renders: the first 8
 * characters, everything in between, and the last 8.
 *
 * The edges are what a user actually checks against their own device, and
 * rendering them in an accent colour makes that comparison a glance rather
 * than a character count. An address of 16 characters or fewer cannot be split
 * without the edges overlapping, so it comes back whole in `first` with an
 * empty middle and last — one rendering of every character, never two.
 */
fun splitAddressEdges(address: String): Triple<String, String, String> {
    if (address.length <= 16) return Triple(address, "", "")
    return Triple(
        address.substring(0, 8),
        address.substring(8, address.length - 8),
        address.substring(address.length - 8),
    )
}
