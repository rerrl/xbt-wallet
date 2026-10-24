package com.xbtwallet.app.ui.hotwallet

import com.xbtwallet.app.crypto.Bip39
import java.text.Normalizer

/**
 * What one slot of the entered phrase holds.
 *
 * A slot is a position in the phrase the user is expected to hand over, not a
 * position in what they have typed so far: for a twelve-word import the state
 * describes twelve slots, and the ones the user has not reached yet are
 * [EMPTY_SLOT]. That is what makes the status list a description of the whole
 * phrase rather than of a prefix of it.
 */
enum class ImportWordStatus {
    /** A word in the BIP-39 English wordlist. */
    VALID,

    /** Something was typed in this slot, and it is not a BIP-39 word. */
    INVALID,

    /** Nothing has been typed in this slot yet. */
    EMPTY_SLOT,
}

/**
 * Why a phrase is not (yet) importable — the aggregate verdict [ImportWordsParser.validate]
 * returns, with the detail a message needs.
 *
 * The variants are spelled the way the screen's copy names them, deliberately:
 * a state and the sentence it produces are the same fact seen twice, and a
 * rename here should be a rename there.
 */
sealed interface ImportWordsReason {
    /** Fewer words than the phrase needs. Not an error — the user is still typing. */
    data object INCOMPLETE : ImportWordsReason

    /**
     * More words than the phrase needs.
     *
     * @property entered how many were entered, so a message can say so without
     *   recounting the input.
     */
    data class WRONG_COUNT(val entered: Int) : ImportWordsReason

    /**
     * The right number of words, at least one of which is not in the wordlist.
     *
     * @property positions the offending slots, **one-based** — the numbering the
     *   user's own list uses ("word 7"), and the numbering the message prints.
     */
    data class UNKNOWN_WORDS(val positions: List<Int>) : ImportWordsReason

    /** Every word is spelled right and the count is right, but the checksum is not. */
    data object CHECKSUM_FAIL : ImportWordsReason

    /** A well-formed, correctly checksummed phrase of the expected length. */
    data object OK : ImportWordsReason
}

/**
 * One reading of the word field: the words it holds, one status per slot, and
 * why it is not importable yet.
 *
 * @property words the normalised words that were entered, in order. This is what
 *   goes into the shared flow holder on a successful import — the *normalised*
 *   words, so a phrase pasted in mixed case or with a stray full stop still
 *   reaches [Bip39.mnemonicToSeed] as the phrase the user meant.
 * @property statuses one entry per slot (see [ImportWordStatus]); its length is
 *   the larger of the words entered and the words expected.
 * @property reason the aggregate verdict.
 */
data class ImportWordsState(
    val words: List<String>,
    val statuses: List<ImportWordStatus>,
    val reason: ImportWordsReason,
) {
    /** How many words have been entered — the `N` in `N of 12`. */
    val count: Int get() = words.size

    /**
     * Whether the phrase can be imported: right length, every word known, and
     * the checksum agrees. True for [ImportWordsReason.OK] and nothing else.
     */
    val isValid: Boolean get() = reason == ImportWordsReason.OK

    /**
     * The first slot that holds something which is not a BIP-39 word, one-based,
     * or null when there is none.
     */
    val firstUnknownWord: Int?
        get() = statuses.indexOfFirst { it == ImportWordStatus.INVALID }
            .takeIf { it >= 0 }
            ?.plus(1)
}

/**
 * The parsing behind the word-entry screen: typing assistance, normalisation,
 * and the verdict on what has been typed.
 *
 * Pure Kotlin over [Bip39]'s wordlist — no Compose, no Android — so the whole of
 * the screen's *logic* is a JVM unit test and the composable is left with nothing
 * to decide. [ImportWordsParserTest] pins it, and the suggestion strip, the
 * progress line, the per-word complaint and the enabled state of the import
 * button are all read off one [ImportWordsState].
 *
 * Nothing here holds, logs or copies anything secret beyond the words the caller
 * handed in; the returned state is the same words, normalised.
 */
object ImportWordsParser {

    /** How many suggestions a strip shows when the caller does not say. */
    const val DEFAULT_SUGGESTION_LIMIT = 5

    /** Runs of whitespace — spaces, tabs, newlines — are all one separator. */
    private val WHITESPACE = Regex("\\s+")

    /**
     * Up to [limit] wordlist entries that start with [prefix], in wordlist order.
     *
     * The list is sorted, so the candidates are one contiguous run: binary search
     * for the prefix gives either the word itself or the insertion point, and the
     * run starts there. Empty for an empty prefix (nothing to suggest yet) and for
     * a prefix no word starts with.
     *
     * [prefix] is normalised the way [tokenize] normalises a word, so the strip
     * cannot be defeated by the case or the trailing punctuation of a half-typed
     * token.
     */
    fun suggestions(prefix: String, limit: Int = DEFAULT_SUGGESTION_LIMIT): List<String> {
        if (limit <= 0) return emptyList()
        val normalised = normalizeWord(prefix)
        if (normalised.isEmpty()) return emptyList()

        var index = Bip39.wordlist.binarySearch(normalised)
        if (index < 0) index = -(index + 1)

        val matches = ArrayList<String>(limit)
        while (index < Bip39.wordlist.size && matches.size < limit) {
            val word = Bip39.wordlist[index]
            if (!word.startsWith(normalised)) break
            matches += word
            index++
        }
        return matches
    }

    /**
     * The words in [input]: split on whitespace, each one lowercased, NFKD-
     * normalised and stripped of everything that is not `a`-`z`.
     *
     * Segments that normalise to nothing are dropped rather than kept as empty
     * words — extra spaces, a trailing newline, and a stray `,` between two words
     * are all the ordinary shapes of a pasted phrase, and none of them is a word.
     */
    fun tokenize(input: String): List<String> =
        input.split(WHITESPACE).map(::normalizeWord).filter { it.isNotEmpty() }

    /** Whether [word] is in the BIP-39 English wordlist. */
    fun isWord(word: String): Boolean = Bip39.wordlist.binarySearch(normalizeWord(word)) >= 0

    /**
     * Reads [input] as [expectedCount] words and says what is wrong with it, if
     * anything.
     *
     * The order of the checks is the order a person needs them in. A phrase that
     * is short is [ImportWordsReason.INCOMPLETE] — mid-typing is the normal case,
     * not a fault — and one that is long is [ImportWordsReason.WRONG_COUNT]. Only
     * at the right length does spelling matter ([ImportWordsReason.UNKNOWN_WORDS]),
     * because a word-by-word complaint about a phrase that is still being typed
     * would fire on every keystroke. The checksum is asked about last, and only
     * when the count is right and every word is known: [Bip39.isValid] would
     * reject either of those on its own, and reporting a checksum failure for a
     * misspelt word would send the user looking for a typo in the wrong place.
     */
    fun validate(input: String, expectedCount: Int): ImportWordsState {
        val words = tokenize(input)
        val slots = maxOf(words.size, expectedCount)
        val statuses = List(slots) { index ->
            when {
                index >= words.size -> ImportWordStatus.EMPTY_SLOT
                isWord(words[index]) -> ImportWordStatus.VALID
                else -> ImportWordStatus.INVALID
            }
        }

        val reason = when {
            words.size < expectedCount -> ImportWordsReason.INCOMPLETE
            words.size > expectedCount -> ImportWordsReason.WRONG_COUNT(words.size)
            else -> {
                val unknown = words.indices
                    .filter { statuses[it] == ImportWordStatus.INVALID }
                    .map { it + 1 }
                when {
                    unknown.isNotEmpty() -> ImportWordsReason.UNKNOWN_WORDS(unknown)
                    !Bip39.isValid(words) -> ImportWordsReason.CHECKSUM_FAIL
                    else -> ImportWordsReason.OK
                }
            }
        }

        return ImportWordsState(words = words, statuses = statuses, reason = reason)
    }

    /**
     * One segment of input as it would be compared against the wordlist: NFKD
     * (BIP-39's normal form, which is a no-op for plain English but not for an
     * accented paste), lowercased with the locale-independent rule, then reduced
     * to `a`-`z`.
     */
    private fun normalizeWord(raw: String): String {
        val normalised = Normalizer.normalize(raw, Normalizer.Form.NFKD).lowercase()
        return normalised.filter { it in 'a'..'z' }
    }
}
