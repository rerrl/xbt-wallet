package com.xbtwallet.app.ui.hotwallet

import org.junit.Assert.assertFalse
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
}
