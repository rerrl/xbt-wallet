package com.xbtwallet.app.ui.hotwallet

import com.xbtwallet.app.crypto.Bip39
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word-entry screen's logic, pinned on the JVM.
 *
 * Everything the screen decides — which chips the suggestion strip holds, when
 * it is hidden, what the progress line says, whether the import button is
 * enabled — is read off [ImportWordsParser], so the rules are tested here rather
 * than through an activity. The BIP-39 vectors are the published ones, and the
 * failure cases are the four ways a phrase can be wrong: short, long, misspelt,
 * and mis-checksummed.
 */
class ImportWordsParserTest {

    /** BIP-39's all-zero-entropy vector: `abandon` × 11 + `about`. */
    private val twelveWords: List<String> = List(11) { "abandon" } + "about"

    /** …and its 256-bit sibling: `abandon` × 23 + `art`. */
    private val twentyFourWords: List<String> = List(23) { "abandon" } + "art"

    private fun validate(input: String, expected: Int = 12) = ImportWordsParser.validate(input, expected)

    // --- suggestions --------------------------------------------------------

    @Test
    fun `a prefix suggests the words that start with it, in wordlist order`() {
        assertEquals(
            listOf("abandon", "ability", "able"),
            ImportWordsParser.suggestions("ab", limit = 3),
        )
        assertEquals(
            listOf("abandon", "ability", "able", "about", "above"),
            ImportWordsParser.suggestions("ab"),
        )
        assertEquals(listOf("cream", "credit", "creek", "crew"), ImportWordsParser.suggestions("cre"))
    }

    /**
     * The strip is driven by a half-typed token, so a prefix is normalised the
     * same way a word is: case and a stray trailing character cannot make the
     * suggestion list empty.
     */
    @Test
    fun `a prefix is normalised before it is searched for`() {
        assertEquals(listOf("abandon"), ImportWordsParser.suggestions("ABAND"))
        assertEquals(listOf("abandon"), ImportWordsParser.suggestions("aband."))
        assertEquals(listOf("abandon"), ImportWordsParser.suggestions("  aband "))
    }

    @Test
    fun `an empty prefix suggests nothing`() {
        listOf("", " ", "   ", "\n", ",", "1").forEach { prefix ->
            assertTrue("`$prefix` is not a prefix yet", ImportWordsParser.suggestions(prefix).isEmpty())
        }
    }

    @Test
    fun `a prefix no word starts with suggests nothing`() {
        listOf("zzz", "abandone", "qq", "abandonx").forEach { prefix ->
            assertTrue("no word starts with `$prefix`", ImportWordsParser.suggestions(prefix).isEmpty())
        }
    }

    @Test
    fun `a whole word suggests itself and nothing else`() {
        assertEquals(listOf("abandon"), ImportWordsParser.suggestions("abandon"))
        assertEquals(listOf("zoo"), ImportWordsParser.suggestions("zoo"))
    }

    @Test
    fun `four letters identify a word, for every word long enough to have four`() {
        // BIP-39 §Wordlist: each word is uniquely identified by its first four
        // letters. Anything else would make the suggestion strip ambiguous, so
        // the whole list is walked rather than a sample of it — the three-letter
        // words are left out, because a three-letter probe is the whole word and
        // is legitimately a prefix of longer ones (`act` before `action`).
        Bip39.wordlist.filter { it.length >= 4 }.forEach { word ->
            assertEquals(
                "`${word.take(4)}` must identify `$word` and nothing else",
                listOf(word),
                ImportWordsParser.suggestions(word.take(4)),
            )
        }
    }

    @Test
    fun `a limit of zero suggests nothing`() {
        assertTrue(ImportWordsParser.suggestions("ab", limit = 0).isEmpty())
        assertTrue(ImportWordsParser.suggestions("ab", limit = -1).isEmpty())
    }

    // --- tokenize -----------------------------------------------------------

    @Test
    fun `words are split on any run of whitespace`() {
        assertEquals(listOf("abandon", "ability"), ImportWordsParser.tokenize("  abandon   ability  "))
        assertEquals(listOf("abandon", "ability", "able"), ImportWordsParser.tokenize("abandon\nability\r\nable"))
        assertEquals(listOf("abandon", "about"), ImportWordsParser.tokenize("abandon\tabout"))
    }

    @Test
    fun `a trailing newline is not a word`() {
        assertEquals(listOf("abandon", "ability"), ImportWordsParser.tokenize("abandon ability\n"))
        assertEquals(12, ImportWordsParser.tokenize(twelveWords.joinToString(" ") + "\n").size)
    }

    @Test
    fun `words are lowercased`() {
        assertEquals(listOf("abandon", "ability"), ImportWordsParser.tokenize("Abandon ABILITY"))
    }

    @Test
    fun `everything that is not a letter is dropped and empty segments vanish`() {
        assertEquals(listOf("abandon", "ability"), ImportWordsParser.tokenize("abandon, ability."))
        assertEquals(listOf("word"), ImportWordsParser.tokenize("word1"))
        assertEquals(emptyList<String>(), ImportWordsParser.tokenize(""))
        assertEquals(emptyList<String>(), ImportWordsParser.tokenize("   \n\t "))
        assertEquals(emptyList<String>(), ImportWordsParser.tokenize(",.,, ..."))
    }

    /**
     * NFKD (BIP-39's normal form, and what the seed step itself applies) turns an
     * `ó` into an `o` and a combining accent, and the accent is then dropped — so
     * an accented paste still reads as the plain word, which is what the wordlist
     * holds.
     */
    @Test
    fun `an accented paste reads as the plain word`() {
        assertEquals(listOf("abandon"), ImportWordsParser.tokenize("abando\u0301n"))
        assertEquals(listOf("creme"), ImportWordsParser.tokenize("cr\u00e8me"))
    }

    // --- the states ---------------------------------------------------------

    @Test
    fun `a short phrase is incomplete, not wrong`() {
        val state = validate("abandon ability")

        assertEquals(ImportWordsReason.INCOMPLETE, state.reason)
        assertEquals(listOf("abandon", "ability"), state.words)
        assertEquals(2, state.count)
        assertFalse(state.isValid)
        assertNull(state.firstUnknownWord)
        assertEquals("twelve slots are described, not two", 12, state.statuses.size)
        assertEquals(ImportWordStatus.VALID, state.statuses[0])
        assertEquals(ImportWordStatus.VALID, state.statuses[1])
        assertEquals(ImportWordStatus.EMPTY_SLOT, state.statuses[2])
        assertEquals(ImportWordStatus.EMPTY_SLOT, state.statuses.last())
    }

    @Test
    fun `an empty field is incomplete and describes nothing but empty slots`() {
        val state = validate("", expected = 24)

        assertEquals(ImportWordsReason.INCOMPLETE, state.reason)
        assertEquals(0, state.count)
        assertEquals(24, state.statuses.size)
        assertTrue(state.statuses.all { it == ImportWordStatus.EMPTY_SLOT })
    }

    @Test
    fun `too many words is a wrong count, and says how many`() {
        val state = validate(twelveWords.joinToString(" ") + " abandon")

        assertEquals(ImportWordsReason.WRONG_COUNT(13), state.reason)
        assertEquals(13, state.count)
        assertFalse(state.isValid)
    }

    @Test
    fun `an unknown word is named by its position, one-based`() {
        val words = twelveWords.toMutableList().also { it[6] = "zzzz" }
        val state = validate(words.joinToString(" "))

        assertEquals(ImportWordsReason.UNKNOWN_WORDS(listOf(7)), state.reason)
        assertEquals(7, state.firstUnknownWord)
        assertEquals(ImportWordStatus.INVALID, state.statuses[6])
        assertFalse(state.isValid)
    }

    @Test
    fun `every unknown word is reported, in order`() {
        val words = twelveWords.toMutableList().also {
            it[1] = "zzzz"
            it[11] = "qqqq"
        }
        val state = validate(words.joinToString(" "))

        assertEquals(ImportWordsReason.UNKNOWN_WORDS(listOf(2, 12)), state.reason)
        assertEquals(2, state.firstUnknownWord)
    }

    /**
     * The precedence rule: a phrase that is both the wrong length and misspelt
     * complains about the length. A word-by-word complaint mid-typing would fire
     * on every keystroke.
     */
    @Test
    fun `a wrong count is reported before a misspelt word`() {
        val thirteen = twelveWords + listOf("nope")

        assertEquals(ImportWordsReason.WRONG_COUNT(13), validate(thirteen.joinToString(" ")).reason)
    }

    @Test
    fun `a phrase that is all words but not a phrase fails its checksum`() {
        val state = validate(List(12) { "abandon" }.joinToString(" "))

        assertEquals(ImportWordsReason.CHECKSUM_FAIL, state.reason)
        assertFalse(state.isValid)
        assertNull("every word is a word — the checksum is what is wrong", state.firstUnknownWord)
        assertEquals(12, state.statuses.size)
        assertTrue(state.statuses.all { it == ImportWordStatus.VALID })
    }

    @Test
    fun `one word changed in a real phrase fails its checksum`() {
        // The published vector with its last word swapped for another valid one:
        // the words are all real, the length is right, and the checksum is not.
        val tampered = twelveWords.toMutableList().also { it[11] = "ability" }

        assertEquals(ImportWordsReason.CHECKSUM_FAIL, validate(tampered.joinToString(" ")).reason)
        assertEquals(ImportWordsReason.CHECKSUM_FAIL, validate(twentyFourWords.dropLast(1).plus("ability").joinToString(" "), expected = 24).reason)
    }

    @Test
    fun `both published vectors validate`() {
        val twelve = validate(twelveWords.joinToString(" "))
        val twentyFour = validate(twentyFourWords.joinToString(" "), expected = 24)

        assertEquals(ImportWordsReason.OK, twelve.reason)
        assertEquals(ImportWordsReason.OK, twentyFour.reason)
        assertTrue(twelve.isValid)
        assertTrue(twentyFour.isValid)
        assertEquals(12, twelve.count)
        assertEquals(24, twentyFour.count)
        assertEquals(ImportWordStatus.VALID, twelve.statuses.first())
        assertEquals(ImportWordStatus.VALID, twentyFour.statuses.last())
    }

    @Test
    fun `a phrase pasted with noise still validates, and normalises`() {
        val noisy = "  Abandon\tabandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon About \n"

        val state = validate(noisy)

        assertEquals(ImportWordsReason.OK, state.reason)
        assertEquals("what is handed on is the normalised phrase", twelveWords, state.words)
    }

    @Test
    fun `the right count has no empty slots`() {
        val state = validate(twelveWords.joinToString(" "))

        assertEquals(12, state.statuses.size)
        assertTrue(state.statuses.none { it == ImportWordStatus.EMPTY_SLOT })
    }

    // --- isWord -------------------------------------------------------------

    @Test
    fun `isWord knows the list and is not fooled by case`() {
        assertTrue(ImportWordsParser.isWord("abandon"))
        assertTrue(ImportWordsParser.isWord("ABANDON"))
        assertFalse(ImportWordsParser.isWord("zoology"))
        assertFalse(ImportWordsParser.isWord("zzzz"))
        assertFalse("a prefix is not a word", ImportWordsParser.isWord("aband"))
        assertTrue(
            "punctuation is stripped first, so the word still reads as a word",
            ImportWordsParser.isWord("abandon!"),
        )
    }
}
