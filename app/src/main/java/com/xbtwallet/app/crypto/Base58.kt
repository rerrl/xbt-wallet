package com.xbtwallet.app.crypto

import java.math.BigInteger

/** Raised when a Base58 string contains a character outside the alphabet. */
class Base58Exception(message: String) : IllegalArgumentException(message)

/** Raised when a Base58Check payload's trailing checksum does not verify. */
class Base58CheckException(message: String) : IllegalArgumentException(message)

/**
 * Base58 and Base58Check, from the public Bitcoin specification.
 *
 * Base58Check is `base58(payload || firstFourBytes(sha256(sha256(payload))))`
 * — the encoding Bitcoin uses for addresses and extended keys. Both directions
 * are implemented with [BigInteger] base conversion plus explicit handling of
 * leading zero bytes (which map to leading '1' characters), which is the
 * textbook definition and easy to audit.
 */
object Base58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEX = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }
    private val FIFTY_EIGHT = BigInteger.valueOf(58)

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""

        // Leading zero bytes are not representable in base conversion, so they
        // are counted out and re-emitted as '1' characters.
        var leadingZeros = 0
        while (leadingZeros < input.size && input[leadingZeros] == 0.toByte()) leadingZeros++

        var value = BigInteger(1, input)
        val builder = StringBuilder()
        while (value > BigInteger.ZERO) {
            val (quotient, remainder) = value.divideAndRemainder(FIFTY_EIGHT)
            builder.append(ALPHABET[remainder.toInt()])
            value = quotient
        }
        repeat(leadingZeros) { builder.append(ALPHABET[0]) }
        return builder.reverse().toString()
    }

    /** Throws [Base58Exception] on any character outside the alphabet. */
    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)

        var leadingOnes = 0
        while (leadingOnes < input.length && input[leadingOnes] == '1') leadingOnes++

        var value = BigInteger.ZERO
        for (char in input) {
            val digit = if (char.code < INDEX.size) INDEX[char.code] else -1
            if (digit < 0) throw Base58Exception("'$char' is not a Base58 character")
            value = value.multiply(FIFTY_EIGHT).add(BigInteger.valueOf(digit.toLong()))
        }

        val magnitude = if (value.signum() == 0) {
            ByteArray(0)
        } else {
            value.toByteArray().let {
                // BigInteger.toByteArray() prepends a sign byte when the top bit
                // is set; strip it so the byte count matches the input.
                if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
            }
        }
        return ByteArray(leadingOnes) + magnitude
    }
}

/** Base58Check: Base58 with a 4-byte double-SHA256 checksum. */
object Base58Check {

    private const val CHECKSUM_LENGTH = 4

    fun encode(payload: ByteArray): String = Base58.encode(payload + checksum(payload))

    /**
     * Decodes and verifies, returning the payload without the checksum.
     *
     * Throws [Base58Exception] for a malformed alphabet, [Base58CheckException]
     * when the checksum does not match or the input is too short to carry one.
     */
    fun decode(input: String): ByteArray {
        val decoded = Base58.decode(input)
        if (decoded.size <= CHECKSUM_LENGTH) {
            throw Base58CheckException("input is too short to contain a checksum")
        }
        val payload = decoded.copyOfRange(0, decoded.size - CHECKSUM_LENGTH)
        val expected = checksum(payload)
        val actual = decoded.copyOfRange(decoded.size - CHECKSUM_LENGTH, decoded.size)
        if (!expected.contentEquals(actual)) {
            throw Base58CheckException("checksum mismatch")
        }
        return payload
    }

    private fun checksum(payload: ByteArray): ByteArray =
        Hashes.sha256(Hashes.sha256(payload)).copyOfRange(0, CHECKSUM_LENGTH)
}
