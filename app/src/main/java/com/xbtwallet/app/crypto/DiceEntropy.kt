package com.xbtwallet.app.crypto

/**
 * Dice rolls -> entropy -> mnemonic, in the "hash the roll string" convention.
 *
 * The algorithm is the one SeedSigner uses (`helpers/mnemonic_generation.py`)
 * and the one iancoleman's BIP-39 tool and bitcoiner.guide/seed describe:
 *
 * 1. the roll string is the rolls concatenated as ASCII digits, each `1`-`6`;
 * 2. `entropy = SHA-256(rollString)`, truncated to 16 bytes for 12 words and
 *    kept whole (32 bytes) for 24 words;
 * 3. the entropy goes through [Bip39.entropyToMnemonic].
 *
 * Exactly 50 rolls produce 12 words and exactly 99 produce 24, because
 * `log(6^50)/log(2) ~= 129` bits and `log(6^99)/log(2) ~= 256` bits of dice
 * entropy need at least a 128- and a 256-bit seed respectively.
 *
 * This is deliberately **not** iancoleman's "Dice" mode, which converts the
 * rolls base-6: the hash convention above is what a signer re-deriving the
 * mnemonic from the same rolls will agree with.
 *
 * Android-free and stateless: a pure function from a string to bytes.
 */
object DiceEntropy {

    /** How many rolls a 12-word mnemonic needs. */
    const val ROLLS_12_WORDS = 50

    /** How many rolls a 24-word mnemonic needs. */
    const val ROLLS_24_WORDS = 99

    /**
     * Turns a roll string into entropy: SHA-256 of the ASCII rolls, truncated to
     * the entropy length the roll count implies.
     *
     * Throws [IllegalArgumentException] unless the string is exactly 50 or 99
     * characters long and every character is a digit `1`-`6`.
     */
    fun rollsToEntropy(rolls: String): ByteArray {
        val entropyLength = when (rolls.length) {
            ROLLS_12_WORDS -> 16
            ROLLS_24_WORDS -> 32
            else -> throw IllegalArgumentException(
                "dice entropy needs exactly $ROLLS_12_WORDS rolls (12 words) or " +
                    "$ROLLS_24_WORDS rolls (24 words), got ${rolls.length}",
            )
        }
        rolls.forEach { roll ->
            if (roll < '1' || roll > '6') {
                throw IllegalArgumentException("a dice roll is a digit 1-6, got '$roll'")
            }
        }

        val digest = Hashes.sha256(rolls.toByteArray(Charsets.US_ASCII))
        return digest.copyOf(entropyLength)
    }

    /**
     * Turns a roll string straight into its mnemonic.
     *
     * Throws [IllegalArgumentException] for the same reasons [rollsToEntropy]
     * does.
     */
    fun rollsToMnemonic(rolls: String): List<String> = Bip39.entropyToMnemonic(rollsToEntropy(rolls))
}
