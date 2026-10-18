package com.xbtwallet.app.crypto

/** Raised when a string is not a valid bech32/bech32m-encoded value. */
class Bech32Exception(message: String) : IllegalArgumentException(message)

/** A decoded bech32 string: the human-readable part and the 5-bit data groups. */
class Bech32Data(val hrp: String, val data: ByteArray)

/**
 * Bech32 and bech32m (BIP-173 / BIP-350), implemented from the specifications.
 *
 * Bech32 is the address encoding used by native segwit: `bc1q...` strings are
 * bech32, and the checksum constant changes for witness versions above 0
 * (bech32m). The implementation follows the BIP's reference pseudocode closely
 * — a bit-conversion helper, an HRP expansion, a BCH residue check — because
 * that is the version a reviewer can check line by line against the spec.
 *
 * Everything here is Android-free so JVM unit tests can drive it.
 */
object Bech32 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private val CHARSET_REVERSE = IntArray(128) { -1 }.also { table ->
        CHARSET.forEachIndexed { index, char -> table[char.code] = index }
    }

    /** The five odd coefficients of the bech32 BCH generator polynomial. */
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    /** Checksum constant for witness version 0 (BIP-173 "bech32"). */
    const val BECH32_CONSTANT = 1

    /** Checksum constant for witness versions 1..16 (BIP-350 "bech32m"). */
    const val BECH32M_CONSTANT = 0x2bc830a3

    /**
     * Encodes 5-bit [data] groups under [hrp] with the bech32 checksum.
     */
    fun encode(hrp: String, data: ByteArray): String = encode(hrp, data, BECH32_CONSTANT)

    /** Encodes with an explicit checksum constant ([BECH32_CONSTANT] or [BECH32M_CONSTANT]). */
    fun encode(hrp: String, data: ByteArray, checksumConstant: Int): String {
        require(hrp.isNotEmpty()) { "hrp must not be empty" }
        require(hrp.all { it.code in 33..126 }) { "hrp contains a character outside 33..126" }

        val values = hrpExpand(hrp) + data.map { it.toInt() } + List(6) { 0 }
        val polymod = polymod(values) xor checksumConstant

        val builder = StringBuilder(hrp).append('1')
        data.forEach { builder.append(CHARSET[it.toInt()]) }
        for (i in 0 until 6) {
            builder.append(CHARSET[(polymod ushr (5 * (5 - i))) and 31])
        }
        return builder.toString()
    }

    /**
     * Decodes and verifies a bech32 or bech32m string, returning the HRP and the
     * data groups with the checksum removed.
     *
     * Throws [Bech32Exception] for anything that does not verify: out-of-range
     * or mixed-case characters, a missing/empty HRP, an over-long string, a
     * character outside the charset, or a failed checksum.
     */
    fun decode(input: String): Bech32Data {
        if (input.length !in 8..90) throw Bech32Exception("length ${input.length} outside 8..90")
        if (input.any { it.code < 33 || it.code > 126 }) {
            throw Bech32Exception("character outside printable range 33..126")
        }
        if (input != input.lowercase() && input != input.uppercase()) {
            throw Bech32Exception("mixed case")
        }
        val value = input.lowercase()

        val separator = value.lastIndexOf('1')
        if (separator < 1 || separator + 7 > value.length) {
            throw Bech32Exception("missing or misplaced '1' separator")
        }

        val hrp = value.substring(0, separator)
        val encoded = value.substring(separator + 1)
        val data = IntArray(encoded.length) { index ->
            val char = encoded[index]
            val digit = if (char.code < CHARSET_REVERSE.size) CHARSET_REVERSE[char.code] else -1
            if (digit < 0) throw Bech32Exception("'$char' is not a bech32 character")
            digit
        }

        val polymod = polymod(hrpExpand(hrp) + data.toList())
        if (polymod != BECH32_CONSTANT && polymod != BECH32M_CONSTANT) {
            throw Bech32Exception("checksum failed")
        }

        val payload = ByteArray(data.size - 6) { data[it].toByte() }
        return Bech32Data(hrp, payload)
    }

    /** BIP-173 HRP expansion: high bits, a zero separator, then low bits. */
    internal fun hrpExpand(hrp: String): IntArray {
        val expanded = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            expanded[i] = hrp[i].code ushr 5
            expanded[hrp.length + 1 + i] = hrp[i].code and 31
        }
        return expanded
    }

    /** BCH residue of the expanded HRP plus the data groups. */
    internal fun polymod(values: IntArray): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (i in 0 until 5) {
                if ((top ushr i) and 1 == 1) checksum = checksum xor GENERATOR[i]
            }
        }
        return checksum
    }

    /**
     * Regroups [data] from [fromBits]-bit to [toBits]-bit units, per the BIP-173
     * reference implementation. With `pad = false` it additionally rejects
     * non-zero padding and over-long values (used when converting an address's
     * 5-bit groups back into an 8-bit program).
     */
    fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var accumulator = 0
        var bitCount = 0
        val maxValue = (1 shl toBits) - 1
        val out = ArrayList<Byte>()

        for (value in data) {
            val unsigned = value.toInt() and 0xFF
            if (unsigned ushr fromBits != 0) {
                throw Bech32Exception("value $unsigned does not fit in $fromBits bits")
            }
            accumulator = (accumulator shl fromBits) or unsigned
            bitCount += fromBits
            while (bitCount >= toBits) {
                bitCount -= toBits
                out.add(((accumulator ushr bitCount) and maxValue).toByte())
            }
        }

        if (pad) {
            if (bitCount > 0) {
                out.add(((accumulator shl (toBits - bitCount)) and maxValue).toByte())
            }
        } else {
            if (bitCount >= fromBits || ((accumulator shl (toBits - bitCount)) and maxValue) != 0) {
                throw Bech32Exception("invalid padding")
            }
        }
        return out.toByteArray()
    }
}
