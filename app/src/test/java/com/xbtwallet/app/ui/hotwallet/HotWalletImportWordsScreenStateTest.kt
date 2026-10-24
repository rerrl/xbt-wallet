package com.xbtwallet.app.ui.hotwallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The word-entry screen's state, exercised without any Compose UI.
 *
 * The field itself is verified by `assembleDebug`, but everything the screen
 * *decides* is a pure function over the text and the cursor: which chips the
 * suggestion strip holds (and when it is hidden at all), what the line under the
 * field says, and whether the import button is enabled. Those are the three
 * things a user acts on, so they are pinned here — the button against the same
 * [ImportWordsParser] state the composable reads.
 */
class HotWalletImportWordsScreenStateTest {

    /** BIP-39's all-zero-entropy vector. */
    private val twelveWords: List<String> = List(11) { "abandon" } + "about"

    private val phrase = twelveWords.joinToString(" ")

    // --- the suggestion strip ------------------------------------------------

    /**
     * The strip is hidden when the token under the cursor is empty — the state
     * right after a word and its space — because there is nothing to complete.
     */
    @Test
    fun `the strip is hidden on an empty token`() {
        assertTrue(importWordsSuggestionStrip("", 0).isEmpty())
        assertTrue(importWordsSuggestionStrip("   ", 3).isEmpty())
        assertTrue(importWordsSuggestionStrip("abandon ", 8).isEmpty())
        assertTrue(importWordsSuggestionStrip("abandon\n", 8).isEmpty())
    }

    @Test
    fun `the strip is hidden on a complete word`() {
        assertTrue(importWordsSuggestionStrip("abandon", 7).isEmpty())
        assertTrue(
            "…wherever the cursor sits inside it",
            importWordsSuggestionStrip("abandon", 3).isEmpty(),
        )
        assertTrue(importWordsSuggestionStrip("$phrase ", phrase.length + 1).isEmpty())
    }

    @Test
    fun `the strip offers the wordlist for the token being typed`() {
        assertEquals(listOf("abandon"), importWordsSuggestionStrip("aban", 4))

        val many = importWordsSuggestionStrip("ab", 2)
        assertEquals(ImportWordsParser.DEFAULT_SUGGESTION_LIMIT, many.size)
        assertEquals(listOf("abandon", "ability", "able", "about", "above"), many)
    }

    /**
     * The token under the cursor is the one being completed, not the last one in
     * the field: a user who goes back into the middle of the phrase gets the strip
     * for *that* word.
     */
    @Test
    fun `the strip follows the cursor, not the end of the field`() {
        val input = "aban about"

        assertEquals(listOf("abandon"), importWordsSuggestionStrip(input, 2))
        assertTrue(
            "`about` is a whole word — nothing to offer there",
            importWordsSuggestionStrip(input, 10).isEmpty(),
        )
    }

    @Test
    fun `the strip is hidden when no word starts with the token`() {
        assertTrue(importWordsSuggestionStrip("zzzz", 4).isEmpty())
        assertTrue(importWordsSuggestionStrip("abandone", 8).isEmpty())
    }

    /**
     * Normalisation reaches the strip too: a capital letter or a stray character
     * mid-word must not blank the suggestions for it.
     */
    @Test
    fun `the strip is not defeated by case or punctuation`() {
        assertEquals(listOf("abandon"), importWordsSuggestionStrip("ABAN", 4))
        assertEquals(listOf("abandon"), importWordsSuggestionStrip("aban.", 5))
    }

    // --- tapping a suggestion ------------------------------------------------

    @Test
    fun `a tap replaces the half-typed token and appends a space`() {
        val edit = applySuggestion("aban", 4, "abandon")

        assertEquals("abandon ", edit.text)
        assertEquals("and the cursor is ready for the next word", 8, edit.cursor)
    }

    @Test
    fun `a tap in the middle of a phrase leaves the words around it alone`() {
        val edit = applySuggestion("aban ability", 4, "abandon")

        assertEquals("abandon ability", edit.text)
        assertEquals(8, edit.cursor)
    }

    /**
     * Whitespace already after the token is absorbed into the single space the
     * tap adds, so a chip tap never leaves a doubled separator to backspace over.
     */
    @Test
    fun `a tap does not double the separator`() {
        assertEquals("abandon ability", applySuggestion("aban  ability", 4, "abandon").text)
        assertEquals("abandon ability", applySuggestion("aban\nability", 4, "abandon").text)
    }

    @Test
    fun `a tap on the last token leaves no trailing whitespace behind it`() {
        val edit = applySuggestion("aban  ", 4, "abandon")

        assertEquals("abandon ", edit.text)
        assertEquals(8, edit.cursor)
    }

    // --- the token under the cursor ------------------------------------------

    @Test
    fun `the token under the cursor is the run of non-space characters around it`() {
        assertEquals("aban", tokenAtCursor("aban", 4))
        assertEquals("aban", tokenAtCursor("aban", 2))
        assertEquals("aban", tokenAtCursor("aban about", 4))
        assertEquals("about", tokenAtCursor("aban about", 10))
        assertEquals(
            "a cursor inside a run of whitespace is between words, not in one",
            "",
            tokenAtCursor("aban  about", 5),
        )
    }

    @Test
    fun `a cursor outside the text is clamped to it`() {
        assertEquals("about", tokenAtCursor("about", 99))
        assertEquals("about", tokenAtCursor("about", -5))
    }

    // --- the line under the field --------------------------------------------

    @Test
    fun `a phrase being typed shows how far along it is`() {
        val state = ImportWordsParser.validate("abandon ability", 12)

        assertEquals("2 of 12", importWordsValidationLine(state, 12))
    }

    @Test
    fun `one word over the count still shows the count`() {
        val state = ImportWordsParser.validate("$phrase abandon", 12)

        assertEquals(ImportWordsReason.WRONG_COUNT(13), state.reason)
        assertEquals("13 of 12", importWordsValidationLine(state, 12))
    }

    @Test
    fun `a word that is not a word is named by its position`() {
        val words = twelveWords.toMutableList().also { it[6] = "zzzz" }
        val state = ImportWordsParser.validate(words.joinToString(" "), 12)

        assertEquals("Word 7 is not in the BIP-39 wordlist", importWordsValidationLine(state, 12))
        assertEquals("Word 7 is not in the BIP-39 wordlist", importWordsUnknownWordMessage(7))
    }

    @Test
    fun `a phrase of the right length with a bad checksum says so`() {
        val state = ImportWordsParser.validate(List(12) { "abandon" }.joinToString(" "), 12)

        assertEquals(
            "Checksum invalid \u2014 check for a typo or wrong word order",
            importWordsValidationLine(state, 12),
        )
    }

    @Test
    fun `a valid phrase says nothing at all — the button says the rest`() {
        val state = ImportWordsParser.validate(phrase, 12)

        assertTrue(state.isValid)
        assertNull(importWordsValidationLine(state, 12))
    }

    @Test
    fun `the progress wording is the count and the phrase's length`() {
        assertEquals("0 of 12", importWordsProgress(0, 12))
        assertEquals("7 of 12", importWordsProgress(7, 12))
        assertEquals("24 of 24", importWordsProgress(24, 24))
    }

    // --- the import button ---------------------------------------------------

    @Test
    fun `the button is enabled only for a phrase that validates`() {
        assertTrue("the published vector, exactly", ImportWordsParser.validate(phrase, 12).isValid)
        assertTrue(ImportWordsParser.validate(twelveWords.joinToString(" ").uppercase(), 12).isValid)
        assertTrue(
            "and with the noise a paste carries",
            ImportWordsParser.validate("  $phrase\n", 12).isValid,
        )
        assertTrue(
            "a 24-word phrase for a 24-word import",
            ImportWordsParser.validate((List(23) { "abandon" } + "art").joinToString(" "), 24).isValid,
        )
    }

    @Test
    fun `the button is disabled while the phrase is short, long, misspelt or mis-checksummed`() {
        assertFalse("nothing typed", ImportWordsParser.validate("", 12).isValid)
        assertFalse("mid-phrase", ImportWordsParser.validate("abandon ability", 12).isValid)
        assertFalse("one word over", ImportWordsParser.validate("$phrase abandon", 12).isValid)
        assertFalse(
            "a word that is not in the wordlist",
            ImportWordsParser.validate(twelveWords.toMutableList().also { it[0] = "zzzz" }.joinToString(" "), 12).isValid,
        )
        assertFalse(
            "every word right, checksum wrong",
            ImportWordsParser.validate(List(12) { "abandon" }.joinToString(" "), 12).isValid,
        )
        assertFalse(
            "and a twelve-word phrase cannot be imported as twenty-four",
            ImportWordsParser.validate(phrase, 24).isValid,
        )
    }

    // --- the copy ------------------------------------------------------------

    /**
     * The three strings this screen can put in front of the user, verbatim. The
     * button's own wording is pinned in [HotWalletCopyTest], beside the other
     * hot-wallet copy.
     */
    @Test
    fun `the screen's messages are pinned`() {
        assertEquals(
            "Checksum invalid \u2014 check for a typo or wrong word order",
            IMPORT_WORDS_CHECKSUM_FAIL,
        )
        assertEquals("Word 7 is not in the BIP-39 wordlist", importWordsUnknownWordMessage(7))
        assertEquals("1 of 12", importWordsProgress(1, 12))
    }
}
