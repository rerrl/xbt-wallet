package com.xbtwallet.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Dice rolls -> entropy -> mnemonic.
 *
 * The four vectors are pinned verbatim. Each is the SHA-256 of the roll string,
 * truncated to the entropy length the roll count implies, fed to the BIP-39
 * wordlist — so a vector only matches if the hash convention, the truncation and
 * the wordlist all agree.
 *
 * The rejections pin the input grammar: exactly 50 or 99 rolls, every character
 * a digit `1`-`6`, nothing else.
 */
class DiceEntropyTest {

    private class Vector(val rolls: String, val entropy: String, val mnemonic: String)

    private val vectors = listOf(
        Vector(
            rolls = "1".repeat(50),
            entropy = "3dac51a65ec9fcfc409a1b5f1defe92b",
            mnemonic = "diet glad hat rural panther lawsuit act drop gallery urge where fit",
        ),
        Vector(
            rolls = "123456".repeat(8) + "12",
            entropy = "ee72ae915a4e6ea7ccbeb8e5e5eecef2",
            mnemonic = "unveil nice picture region tragic fault cream strike tourist control recipe tourist",
        ),
        Vector(
            rolls = "6".repeat(99),
            entropy = "7efb8e5d1353a90137755f711e1763fd7301a033fbb854889e127ff79c389131",
            mnemonic = "leave symbol nuclear chase dentist letter tape program illegal vague suggest " +
                "wife copy source soup jacket fee eager loyal zoo keep brisk dynamic cradle",
        ),
        Vector(
            rolls = "123456".repeat(16) + "123",
            entropy = "5588d3630bd19f6375b7bd922457af34ea9c74f00807566a1cf808e445dc8c20",
            mnemonic = "few educate sugar bless boring random strategy waste mutual cargo type hawk prefer " +
                "denial scan abstract filter extend dignity balcony dust unusual correct bubble",
        ),
    )

    @Test
    fun `the pinned vectors produce the pinned entropy and mnemonic`() {
        vectors.forEach { vector ->
            assertEquals(vector.entropy, toHex(DiceEntropy.rollsToEntropy(vector.rolls)))
            assertEquals(vector.mnemonic.split(" "), DiceEntropy.rollsToMnemonic(vector.rolls))
        }
    }

    @Test
    fun `50 rolls give 12 words and 99 give 24`() {
        assertEquals(50, DiceEntropy.ROLLS_12_WORDS)
        assertEquals(99, DiceEntropy.ROLLS_24_WORDS)
        assertEquals(16, DiceEntropy.rollsToEntropy("1".repeat(50)).size)
        assertEquals(32, DiceEntropy.rollsToEntropy("6".repeat(99)).size)
        assertEquals(12, DiceEntropy.rollsToMnemonic("3".repeat(50)).size)
        assertEquals(24, DiceEntropy.rollsToMnemonic("4".repeat(99)).size)
    }

    @Test
    fun `the mnemonic the rolls produce is a valid BIP-39 mnemonic`() {
        vectors.forEach { vector ->
            assertArrayEquals(
                DiceEntropy.rollsToEntropy(vector.rolls),
                Bip39.mnemonicToEntropy(vector.mnemonic.split(" ")),
            )
        }
    }

    @Test
    fun `a roll count that is neither 50 nor 99 is refused`() {
        listOf(0, 1, 49, 51, 98, 100, 150).forEach { count ->
            assertThrows(
                "expected $count rolls to be refused",
                IllegalArgumentException::class.java,
            ) { DiceEntropy.rollsToEntropy("3".repeat(count)) }
        }
        assertThrows(IllegalArgumentException::class.java) { DiceEntropy.rollsToEntropy("") }
        assertThrows(IllegalArgumentException::class.java) { DiceEntropy.rollsToMnemonic("3".repeat(51)) }
    }

    @Test
    fun `a digit outside 1-6 is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiceEntropy.rollsToEntropy("0" + "3".repeat(49))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiceEntropy.rollsToEntropy("7" + "3".repeat(49))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiceEntropy.rollsToEntropy("8".repeat(99))
        }
    }

    @Test
    fun `a non-digit character is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiceEntropy.rollsToEntropy("a" + "3".repeat(49))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiceEntropy.rollsToEntropy("3".repeat(98) + " ")
        }
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
