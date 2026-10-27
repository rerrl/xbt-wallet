package com.xbtwallet.app.ui.hotwallet

import com.xbtwallet.app.data.HotWalletDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The finish screen's one validation rule, on its own.
 *
 * `createEnabled` decides whether *Create wallet* is tappable, and it is the
 * whole of the passphrase check the screen does — so it is a pure function
 * outside the composable, and pinned here on the JVM. The four cases are the
 * spec's: no passphrase at all is the ordinary path and is allowed; a repeat
 * that matches the passphrase is allowed; a repeat that differs is not; and a
 * passphrase with nothing repeated yet is not either — an incomplete answer must
 * not read as agreement, or a wallet could be saved under a passphrase the user
 * never actually typed twice.
 */
class HotWalletFinishTest {

    @Test
    fun `no passphrase at all is the ordinary case and is enabled`() {
        assertTrue(createEnabled(passphrase = "", repeat = ""))
    }

    @Test
    fun `a repeated passphrase that matches is enabled`() {
        assertTrue(createEnabled(passphrase = "correct horse", repeat = "correct horse"))
    }

    @Test
    fun `a repeat that differs is not enabled`() {
        assertFalse(createEnabled(passphrase = "correct horse", repeat = "correct hors"))
        assertFalse(createEnabled(passphrase = "x", repeat = "y"))
    }

    @Test
    fun `a passphrase with nothing repeated yet is not enabled`() {
        assertFalse(createEnabled(passphrase = "correct horse", repeat = ""))
    }

    /**
     * The rule is symmetric in the two fields — typing "x" beside "y" is as
     * inconsistent as typing "y" beside "x" — and it is case- and
     * whitespace-sensitive, because BIP-39 passphrases are.
     */
    @Test
    fun `the rule does not forgive case, spaces or reversed fields`() {
        assertFalse(createEnabled(passphrase = "TREZOR", repeat = "trezor"))
        assertFalse(createEnabled(passphrase = "word ", repeat = "word"))
        assertFalse(createEnabled(passphrase = "", repeat = "x"))
        assertTrue(createEnabled(passphrase = " ", repeat = " "))
    }

    // --- the live fingerprints ----------------------------------------------
    //
    // `walletFingerprint` is the whole of the screen's fingerprint logic (the
    // debounce and the background dispatch are wrappers around it, not part of
    // it), so the two lines the screen shows are pinned here without a
    // composition.

    /** `abandon` × 11 + `about`: BIP-39's all-zero-entropy vector. */
    private val bip84Words: List<String> = List(11) { "abandon" } + "about"

    /** That vector's master fingerprint, the value a signer shows for the same seed. */
    private val bip84MasterFingerprint = "73c5da0a"

    @Test
    fun `the no-passphrase fingerprint is the master fingerprint of the words alone`() {
        assertEquals(bip84MasterFingerprint, walletFingerprint(bip84Words, ""))
        assertEquals(
            "and it is exactly what the app's own derivation reports",
            HotWalletDerivation.derive(bip84Words).masterFingerprint,
            walletFingerprint(bip84Words, ""),
        )
    }

    @Test
    fun `a passphrase gives a different fingerprint, and the same one every time`() {
        val plain = walletFingerprint(bip84Words, "")
        val hidden = walletFingerprint(bip84Words, "TREZOR")

        assertNotEquals("a passphrase is a different wallet", plain, hidden)
        assertEquals("and re-deriving reproduces it", hidden, walletFingerprint(bip84Words, "TREZOR"))
        assertEquals(
            "the passphrase path is the app's derivation, not a second one",
            HotWalletDerivation.derive(bip84Words, "TREZOR").masterFingerprint,
            hidden,
        )
        assertNotEquals(
            "case matters, exactly as it does for the wallet itself",
            hidden,
            walletFingerprint(bip84Words, "trezor"),
        )
    }

    @Test
    fun `the fingerprint is the format the rest of the app shows`() {
        val fingerprint = walletFingerprint(bip84Words, "TREZOR")

        assertEquals(8, fingerprint?.length)
        assertTrue("eight lowercase hex digits", fingerprint!!.all { it in "0123456789abcdef" })
    }

    @Test
    fun `an unusable phrase yields no fingerprint rather than throwing`() {
        // The words are validated a step earlier, so this is a guard — but a
        // screen that crashed while deriving would be a worse failure than one
        // that shows nothing.
        assertNull(walletFingerprint(listOf("not", "a", "mnemonic"), ""))
        assertNull(walletFingerprint(List(12) { "abandon" }, ""))
    }
}
