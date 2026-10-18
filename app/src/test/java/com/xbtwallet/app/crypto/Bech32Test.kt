package com.xbtwallet.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Bech32 (BIP-173) unit tests using the vectors published in BIP-173 itself.
 *
 * The valid/invalid string lists below are lifted verbatim from the BIP's test
 * vector section -- test data only, per PLAN.md §6.
 */
class Bech32Test {

    /** Valid bech32 strings from BIP-173. */
    private val valid = listOf(
        "A12UEL5L",
        "a12uel5l",
        "an83characterlonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1tt5tgs",
        "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw",
        "11qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqc8247j",
        "split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w",
        "?1ezyfcl",
    )

    /** Invalid bech32 strings from BIP-173 (bad HRP chars, length, checksum, ...). */
    private val invalid = listOf(
        "\u00201nwldj5", // HRP character out of range (0x20)
        "\u007f1axkwrx", // HRP character out of range (0x7F)
        "\u00801eym55h", // HRP character out of range
        "an84characterslonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1569pvx", // too long
        "pzry9x0s0muk", // no separator
        "1pzry9x0s0muk", // empty HRP
        "x1b4n0q5v", // invalid data character
        "li1dgmt3", // too short checksum
        "de1lg7wt\u00ff", // invalid character in checksum
        "A1G7SGD8", // checksum calculated with uppercased HRP
        "10a06t8", // empty HRP
        "1qzzfhee", // empty HRP
        "A12UEL5X", // invalid checksum
        "split1checkupstagehandshakeupstreamerranterredcaperred2y9e3x", // invalid checksum
    )

    @Test
    fun `BIP-173 valid strings decode`() {
        for (vector in valid) {
            val decoded = Bech32.decode(vector)
            assertTrue("expected a non-blank hrp for '$vector'", decoded.hrp.isNotEmpty())
        }
    }

    @Test
    fun `BIP-173 invalid strings are rejected`() {
        for (vector in invalid) {
            assertThrows(
                "expected '$vector' to be rejected",
                Bech32Exception::class.java,
            ) { Bech32.decode(vector) }
        }
    }

    @Test
    fun `decoded hrp for the split vector is split`() {
        assertEquals("split", Bech32.decode("split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w").hrp)
    }

    @Test
    fun `decode normalises an uppercase string to lowercase`() {
        assertEquals("a12uel5l", Bech32.encode(Bech32.decode("A12UEL5L").hrp, Bech32.decode("A12UEL5L").data))
    }

    @Test
    fun `encode round-trips the BIP-173 sample payload`() {
        val decoded = Bech32.decode("abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw")
        assertEquals("abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw", Bech32.encode(decoded.hrp, decoded.data))
    }

    @Test
    fun `mixed case is invalid`() {
        assertThrows(Bech32Exception::class.java) { Bech32.decode("A12uEL5l") }
    }
}
