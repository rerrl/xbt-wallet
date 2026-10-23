package com.xbtwallet.app.ui.hotwallet

/**
 * The digit string the dice-roll screen has accumulated, as plain Kotlin.
 *
 * Deliberately free of Android and Compose imports: the rules of "what the user
 * has entered so far" are the only part of the dice screen that can be checked
 * without a device, so they live here and are pinned by
 * [com.xbtwallet.app.ui.hotwallet.DiceRollInputTest] rather than inside a
 * composable where a JVM test could not reach them.
 *
 * The class holds **entropy-bearing input** in the narrow sense only: digits the
 * user typed while reading a physical die. It performs no randomness, no hashing
 * and no key derivation — nothing here creates or stores key material. That work
 * belongs to the (not yet written) hot-wallet phase, and the screen that uses
 * this helper says so: its terminal button is disabled.
 *
 * @property targetRolls how many rolls the current word count needs; the input
 *   accepts digits up to this many and refuses more.
 */
class DiceRollInput(val targetRolls: Int) {

    private val digits = StringBuilder()

    /** The digits entered so far, as an immutable snapshot. */
    val rolls: String get() = digits.toString()

    /** How many rolls are in so far. */
    val count: Int get() = digits.length

    /** True once as many rolls are in as the word count needs. */
    val isFull: Boolean get() = digits.length >= targetRolls

    /**
     * Record one die result.
     *
     * A die has six faces and nothing else, so anything outside 1..6 is not a
     * roll and is ignored rather than coerced — a wrong digit must never quietly
     * become entropy. Once [isFull], further rolls are ignored too: the input is
     * exactly as long as the word count was built for.
     */
    fun append(digit: Int) {
        if (digit !in MIN_FACE..MAX_FACE) return
        if (isFull) return
        digits.append(digit)
    }

    /** Drop the last roll, or do nothing when there is none. */
    fun backspace() {
        if (digits.isNotEmpty()) digits.deleteCharAt(digits.length - 1)
    }

    companion object {
        /** The lowest face on a die. */
        private const val MIN_FACE = 1

        /** The highest face on a die. */
        private const val MAX_FACE = 6

        /**
         * How many die rolls the word count needs.
         *
         * The counts are the accepted BIP-39 dice-roll numbers for a
         * hand-rolled seed: 50 rolls carry enough base-6 entropy for 12 words,
         * 99 for 24. Only the two word counts the app offers are mapped; any
         * other value is a programming error rather than a user input, because
         * the navigation guard admits only `12` and `24` ahead of this call.
         */
        fun targetRollsFor(wordCount: Int): Int = when (wordCount) {
            12 -> 50
            24 -> 99
            else -> throw IllegalArgumentException(
                "no dice-roll count for $wordCount words: the app offers 12 and 24 only",
            )
        }
    }
}
