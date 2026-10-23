package com.xbtwallet.app.ui.hotwallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the locked copy of the hot-wallet screens, verbatim, plus the two pure
 * functions that turn a word count into a label and a route into a word count.
 *
 * The hot-wallet flow is UI scaffolding — none of these screens creates, imports
 * or stores key material — so its user-visible strings are the whole of what it
 * promises today. Asserted as literals, not through the constants, so an edit to
 * a constant fails a test instead of silently shipping.
 */
class HotWalletCopyTest {

    @Test
    fun `the create screen says where the entropy comes from`() {
        assertEquals("Create hot wallet", HOT_WALLET_CREATE_TITLE)
        assertEquals(
            "A new hot wallet is created from dice rolls: you roll a die and tap each " +
                "result, so the entropy comes from your hands \u2014 not from this phone " +
                "and not from anyone else.",
            HOT_WALLET_CREATE_COPY,
        )
    }

    @Test
    fun `the create screen offers exactly the two dice-roll word counts`() {
        assertEquals("Dice roll \u2014 12 words", HOT_WALLET_DICE_ROLL_12)
        assertEquals("Dice roll \u2014 24 words", HOT_WALLET_DICE_ROLL_24)

        assertEquals(HOT_WALLET_DICE_ROLL_12, diceRollLabel(12))
        assertEquals(HOT_WALLET_DICE_ROLL_24, diceRollLabel(24))
    }

    @Test
    fun `the dice screen states the roll counts and keeps the preview note`() {
        assertEquals(
            "Roll a die and tap the result. 50 rolls make 12 words, 99 rolls make 24 " +
                "\u2014 take your time, every roll adds entropy.",
            HOT_WALLET_DICE_COPY,
        )
        assertEquals("Undo", HOT_WALLET_DICE_UNDO)
        assertEquals("Create wallet", HOT_WALLET_DICE_CREATE_BUTTON)
        assertEquals(
            "Wallet creation is not wired up yet \u2014 this is a UI preview.",
            HOT_WALLET_CREATE_NOTE,
        )
    }

    @Test
    fun `the import screen says where the imported words live`() {
        assertEquals("Import hot wallet", HOT_WALLET_IMPORT_TITLE)
        assertEquals(
            "Import an existing seed phrase onto this device. The words are stored on " +
                "this phone only.",
            HOT_WALLET_IMPORT_COPY,
        )
        assertEquals("12 words", HOT_WALLET_IMPORT_WORDS_12)
        assertEquals("24 words", HOT_WALLET_IMPORT_WORDS_24)
    }

    @Test
    fun `the word-entry screen is worded for its word count`() {
        assertEquals("Import \u2014 12 words", HOT_WALLET_IMPORT_WORDS_TITLE_12)
        assertEquals("Import \u2014 24 words", HOT_WALLET_IMPORT_WORDS_TITLE_24)
        assertEquals(
            "Enter your 12 words, separated by spaces",
            HOT_WALLET_IMPORT_FIELD_12,
        )
        assertEquals(
            "Enter your 24 words, separated by spaces",
            HOT_WALLET_IMPORT_FIELD_24,
        )

        assertEquals(HOT_WALLET_IMPORT_WORDS_12, importWordCountLabel(12))
        assertEquals(HOT_WALLET_IMPORT_WORDS_24, importWordCountLabel(24))
        assertEquals(HOT_WALLET_IMPORT_WORDS_TITLE_12, importWordsTitle(12))
        assertEquals(HOT_WALLET_IMPORT_WORDS_TITLE_24, importWordsTitle(24))
        assertEquals(HOT_WALLET_IMPORT_FIELD_12, importFieldLabel(12))
        assertEquals(HOT_WALLET_IMPORT_FIELD_24, importFieldLabel(24))
    }

    @Test
    fun `the import screen keeps the preview note`() {
        assertEquals("Import wallet", HOT_WALLET_IMPORT_WALLET_BUTTON)
        assertEquals(
            "Import is not wired up yet \u2014 this is a UI preview.",
            HOT_WALLET_IMPORT_NOTE,
        )
    }

    @Test
    fun `the header's back label is the app's wording`() {
        assertEquals("\u2190 Back", HOT_WALLET_BACK_LABEL)
    }

    // --- the word count on the route -----------------------------------------

    @Test
    fun `only twelve and twenty-four ride a hot-wallet route`() {
        assertEquals(12, hotWalletWordCount("12"))
        assertEquals(24, hotWalletWordCount("24"))
    }

    @Test
    fun `anything else on the route is no word count at all`() {
        listOf(null, "", "  ", "0", "1", "13", "24 words", "48", "abc", "12.0").forEach { address ->
            assertNull("$address is not a word count", hotWalletWordCount(address))
        }
    }

    /**
     * The word counts the screens offer must be the ones the route guard admits:
     * if one moved without the other, a tap on "24 words" would land the user on
     * Home instead of on the screen they asked for.
     */
    @Test
    fun `the offered word counts are exactly the ones the guard admits`() {
        listOf(HOT_WALLET_IMPORT_WORDS_12, HOT_WALLET_IMPORT_WORDS_24).forEach { label ->
            val count = label.substringBefore(' ').toInt()
            assertEquals(count, hotWalletWordCount(count.toString()))
        }
        assertEquals("12 words".substringBefore(' ').toInt(), 12)
        assertTrue(DiceRollInput.targetRollsFor(12) > 0)
        assertTrue(DiceRollInput.targetRollsFor(24) > DiceRollInput.targetRollsFor(12))
    }
}
