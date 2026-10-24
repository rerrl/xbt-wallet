package com.xbtwallet.app.ui.hotwallet

import com.xbtwallet.app.ui.signers.HOT_WALLET_CANNOT_SPEND_HINT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the locked copy of the hot-wallet screens, verbatim, plus the pure
 * functions that turn a word count into a label and a route into a word count.
 *
 * The create path (dice → backup → finish) provisions real wallets now, and so
 * does the import path (word count → words → finish), so its user-visible strings
 * are the whole of what the user is promised while their seed is on screen.
 * Asserted as literals, not through the constants, so an edit to a
 * constant fails a test instead of silently shipping.
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

    /**
     * The create screen is a single option now: one *Dice roll* button, and no
     * word count on it. Twelve or twenty-four is chosen on the dice screen, so a
     * label promising one of them here would be a second, redundant choice.
     */
    @Test
    fun `the create screen offers the one dice-roll option`() {
        assertEquals("Dice roll", HOT_WALLET_DICE_BUTTON)
    }

    @Test
    fun `the dice screen states the roll counts and its terminal button`() {
        assertEquals("Dice roll", HOT_WALLET_DICE_TITLE)
        assertEquals(
            "Roll a die and tap the result. 50 rolls make 12 words, 99 rolls make 24 " +
                "\u2014 take your time, every roll adds entropy.",
            HOT_WALLET_DICE_COPY,
        )
        assertEquals("Undo", HOT_WALLET_DICE_UNDO)
        assertEquals("Create wallet", HOT_WALLET_DICE_CREATE_BUTTON)
    }

    /**
     * The dice screen's terminal button is live now, so the "not wired up yet"
     * note is gone from that screen — and gone as a symbol, not merely
     * unreferenced. Pinned by reflection on the compiled file classes, because a
     * deleted `const val` cannot be named in a test: neither the dice screen's
     * file class nor the two import screens' file classes carries a preview note,
     * because the import walk provisions real wallets now too.
     */
    @Test
    fun `no hot-wallet screen carries the preview note any more`() {
        val diceScreenFields = Class.forName("com.xbtwallet.app.ui.hotwallet.HotWalletDiceScreenKt")
            .declaredFields.map { it.name }
        val importWordsFields =
            Class.forName("com.xbtwallet.app.ui.hotwallet.HotWalletImportWordsScreenKt")
                .declaredFields.map { it.name }
        val importScreenFields = Class.forName("com.xbtwallet.app.ui.hotwallet.HotWalletImportScreenKt")
            .declaredFields.map { it.name }

        assertFalse(
            "wallet creation is wired up on this screen now: $diceScreenFields",
            diceScreenFields.contains("HOT_WALLET_CREATE_NOTE"),
        )
        assertFalse(
            "and import is wired up too: $importWordsFields / $importScreenFields",
            importWordsFields.contains("HOT_WALLET_IMPORT_NOTE") ||
                importScreenFields.contains("HOT_WALLET_IMPORT_NOTE"),
        )
    }

    /**
     * The dice screen's own word-count toggle: the labels are the numbers the
     * rest of the app uses for a word count, so the count the user picks is the
     * count [DiceRollInput.targetRollsFor] is asked for.
     */
    @Test
    fun `the dice screen carries the word-count toggle`() {
        assertEquals("12 words", HOT_WALLET_DICE_WORDS_12)
        assertEquals("24 words", HOT_WALLET_DICE_WORDS_24)

        listOf(HOT_WALLET_DICE_WORDS_12, HOT_WALLET_DICE_WORDS_24).forEach { label ->
            val count = label.substringBefore(' ').toInt()
            assertTrue(
                "the $label segment must name a supported word count",
                count == 12 || count == 24,
            )
            assertTrue(DiceRollInput.targetRollsFor(count) > 0)
        }
        assertTrue(DiceRollInput.targetRollsFor(24) > DiceRollInput.targetRollsFor(12))
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

    /**
     * The import walk's terminal wording, which is the same action as the create
     * walk's at the same screen: `Import wallet` on the word field's button and on
     * the finish screen's, under the title the finish screen wears for an import.
     * The duplicate sentence and its way out belong to that screen too — a phrase
     * this phone already holds is reported rather than retried.
     */
    @Test
    fun `the import walk says import from the word field to the finish screen`() {
        assertEquals("Import wallet", HOT_WALLET_IMPORT_WALLET_BUTTON)
        assertEquals("Import \u2014 final step", HOT_WALLET_FINISH_IMPORT_TITLE)
        assertEquals(
            HOT_WALLET_IMPORT_WALLET_BUTTON,
            finishSubmitLabel(isImport = true),
        )
        assertEquals(HOT_WALLET_FINISH_CREATE, finishSubmitLabel(isImport = false))

        assertEquals("Finish", HOT_WALLET_FINISH_TITLE)
        assertEquals(HOT_WALLET_FINISH_TITLE, finishTitle(isImport = false))
        assertEquals(HOT_WALLET_FINISH_IMPORT_TITLE, finishTitle(isImport = true))

        assertEquals("This wallet is already imported", HOT_WALLET_FINISH_DUPLICATE)
        assertEquals("Go to Home", HOT_WALLET_FINISH_HOME)
    }

    @Test
    fun `the header's back label is the app's wording`() {
        assertEquals("\u2190 Back", HOT_WALLET_BACK_LABEL)
    }

    // --- the two new create-flow screens -------------------------------------

    /**
     * The backup screen's warning, word for word. It is the sentence that has to
     * be read *before* the words are shown, so it is pinned here rather than
     * left to a reviewer's eye: it names the loss (the only backup), the method
     * (paper, in order), the risk (anyone who sees them) and the prohibition
     * (never photograph or screenshot).
     */
    @Test
    fun `the backup screen warns before it reveals`() {
        assertEquals("Recovery words", HOT_WALLET_BACKUP_TITLE)
        assertEquals(
            "Your recovery words are the only backup of this wallet. Write them down on " +
                "paper, in order, and keep them somewhere safe. Anyone who sees them can " +
                "take your coins \u2014 never photograph or screenshot them.",
            HOT_WALLET_BACKUP_COPY,
        )
    }

    @Test
    fun `the backup screen reveals on a tap and confirms in the user's words`() {
        assertEquals("Tap to reveal your recovery words", HOT_WALLET_BACKUP_REVEAL)
        assertEquals("I've written them down", HOT_WALLET_BACKUP_CONFIRM)
    }

    @Test
    fun `the finish screen names the wallet and explains the passphrase`() {
        assertEquals("Finish", HOT_WALLET_FINISH_TITLE)
        assertEquals("Wallet name", HOT_WALLET_FINISH_NAME_LABEL)
        assertEquals("Hot wallet", HOT_WALLET_FINISH_NAME_DEFAULT)
        assertEquals(
            "Optional: a passphrase adds a second secret on top of your words. It is " +
                "never saved \u2014 you will enter it each time you spend. If you forget " +
                "it, no one can recover your coins. Leave empty to skip.",
            HOT_WALLET_FINISH_PASSPHRASE_EXPLAINER,
        )
    }

    @Test
    fun `the finish screen's passphrase fields and its terminal button`() {
        assertEquals("Passphrase (optional)", HOT_WALLET_FINISH_PASSPHRASE_LABEL)
        assertEquals("Repeat passphrase", HOT_WALLET_FINISH_PASSPHRASE_REPEAT_LABEL)
        assertEquals("Passphrases don't match", HOT_WALLET_PASSPHRASE_MISMATCH)
        assertEquals("Create wallet", HOT_WALLET_FINISH_CREATE)
    }

    /** The error state re-uses the same pinned button with a retry label. */
    @Test
    fun `a failed attempt offers a retry`() {
        assertEquals("Retry", HOT_WALLET_FINISH_RETRY)
        assertEquals("Couldn't create the wallet. Try again.", HOT_WALLET_FINISH_ERROR)
    }

    /**
     * The one button label the two create-path screens share: the dice screen
     * and the finish screen are the same action at two points in the walk, and
     * they must not drift apart.
     */
    @Test
    fun `both create-path screens say the same thing on their terminal button`() {
        assertEquals(HOT_WALLET_DICE_CREATE_BUTTON, HOT_WALLET_FINISH_CREATE)
    }

    // --- the hot wallet's send hint ------------------------------------------

    /**
     * The sentence under the UTXOs screen's disabled "Send selected" bar for a
     * hot wallet, verbatim.
     *
     * A hot wallet *can* sign — its seed is on this phone — but not through the
     * air-gapped QR dance that screen starts, so the hint has to say what is
     * coming rather than what is missing. Pinned as a literal so a wording edit
     * fails a test instead of shipping.
     */
    @Test
    fun `the hot wallet's disabled send names the internal signing flow`() {
        assertEquals(
            "Sending from a hot wallet comes with the internal signing flow \u2014 coming soon.",
            HOT_WALLET_CANNOT_SPEND_HINT,
        )
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
     * The word counts the import screen offers must be the ones the route guard
     * admits: if one moved without the other, a tap on "24 words" would land the
     * user on Home instead of on the screen they asked for. The dice screen's
     * toggle carries the same two counts without a route of its own.
     */
    @Test
    fun `the offered word counts are exactly the ones the guard admits`() {
        listOf(
            HOT_WALLET_IMPORT_WORDS_12,
            HOT_WALLET_IMPORT_WORDS_24,
            HOT_WALLET_DICE_WORDS_12,
            HOT_WALLET_DICE_WORDS_24,
        ).forEach { label ->
            val count = label.substringBefore(' ').toInt()
            assertEquals(count, hotWalletWordCount(count.toString()))
        }
        assertEquals("12 words".substringBefore(' ').toInt(), 12)
    }
}
