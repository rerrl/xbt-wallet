package com.xbtwallet.app.ui.signers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the copy of the **Add signer** chooser screen, verbatim.
 *
 * The screen is SeedSigner-first: the intro pairs the phone with a SeedSigner,
 * the filled button leads with the SeedSigner export path, and watch-only is the
 * outlined follow-up. These strings are the screen's user-visible contract, so
 * they are asserted as literals here rather than through the constants, so that
 * an edit to a constant fails a test instead of silently shipping.
 */
class AddSignerCopyTest {

    @Test
    fun `header is unchanged`() {
        assertEquals("\u2190 Back", ADD_SIGNER_BACK_LABEL)
        assertEquals("Add signer", ADD_SIGNER_TITLE)
    }

    @Test
    fun `intro pairs the phone with the signer and promises no spending`() {
        assertEquals(
            "Pair this phone with your SeedSigner. The phone holds public keys only \u2014 " +
                "it watches balances and prepares transactions, but it can never spend. " +
                "Keys never leave your signer.",
            ADD_SIGNER_INTRO,
        )
    }

    @Test
    fun `the primary action is the signing device, led by the SeedSigner path`() {
        assertEquals("Add signing device", ADD_SIGNING_DEVICE_BUTTON)
        assertEquals(
            "The SeedSigner path: Export Xpub \u2192 single-sig \u2192 native segwit \u2192 " +
                "Animated QR. Also works with Keystone and Jade exports.",
            ADD_SIGNING_DEVICE_CAPTION,
        )
    }

    @Test
    fun `the secondary action is the watch-only xpub, and owns the Coldcard case`() {
        assertEquals("Add watch-only xpub", ADD_WATCH_ONLY_BUTTON)
        assertEquals(
            "Scan a plain xpub QR from any coordinator \u2014 a bare xpub, a bitcoin: URI, " +
                "or a [fingerprint/path]xpub expression. Coldcard's text export lives here.",
            ADD_WATCH_ONLY_CAPTION,
        )
    }

    @Test
    fun `the footer explains why watching is enough`() {
        assertEquals(
            "Watching is enough to receive and check balances. Spending stays a QR " +
                "round-trip to your signer \u2014 which is how your keys stay off this phone.",
            ADD_SIGNER_FOOTER,
        )
    }

    /**
     * The leftover single-button-era intro must not survive anywhere on the
     * screen: both of its sentences are gone, folded into [ADD_SIGNER_INTRO].
     */
    @Test
    fun `the old floating intro texts are gone`() {
        val copy = listOf(
            ADD_SIGNER_INTRO,
            ADD_SIGNING_DEVICE_CAPTION,
            ADD_WATCH_ONLY_CAPTION,
            ADD_SIGNER_FOOTER,
        ).joinToString(" ")
        assertTrue(!copy.contains("For SeedSigner users and watch-only wallets"))
        assertTrue(!copy.contains("a watch-only xpub is for use with your"))
    }
}
