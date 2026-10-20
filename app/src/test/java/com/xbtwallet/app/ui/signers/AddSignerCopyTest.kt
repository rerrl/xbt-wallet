package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.data.SignerOrigin
import com.xbtwallet.app.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the copy of the **Add wallet** chooser screen, verbatim.
 *
 * The screen is wallet-first and SeedSigner-first: the intro says what a wallet
 * on this phone is, the filled row leads with the SeedSigner export path, the
 * tonal row covers every other signing device, and the outlined row is the plain
 * text xpub. These strings are the screen's user-visible contract, so they are
 * asserted as literals here rather than through the constants, so that an edit to
 * a constant fails a test instead of silently shipping.
 *
 * The screen title is asserted literally too, because it is the rename this
 * screen exists to carry: the app's unit of organisation is a **wallet**; the
 * physical device is the **signer**.
 */
class AddSignerCopyTest {

    @Test
    fun `the title says wallet, not signer`() {
        assertEquals("\u2190 Back", ADD_SIGNER_BACK_LABEL)
        assertEquals("Add wallet", ADD_SIGNER_TITLE)
    }

    @Test
    fun `the intro says what a wallet is on this phone`() {
        assertEquals(
            "Add a wallet to watch. The phone holds public keys only \u2014 it can check " +
                "balances and prepare transactions, but it can never spend. Keys never " +
                "leave your signer.",
            ADD_SIGNER_INTRO,
        )
    }

    @Test
    fun `row one is the SeedSigner, walked through its own export menu`() {
        assertEquals("SeedSigner", ADD_SEEDSIGNER_BUTTON)
        assertEquals(
            "Export Xpub \u2192 single-sig \u2192 native segwit \u2192 Animated QR on your " +
                "SeedSigner, then scan it here.",
            ADD_SEEDSIGNER_CAPTION,
        )
    }

    @Test
    fun `row two is any other signing device`() {
        assertEquals("Other signing device", ADD_SIGNING_DEVICE_BUTTON)
        assertEquals(
            "Keystone, Jade \u2014 scan the device's animated BC-UR export QR.",
            ADD_SIGNING_DEVICE_CAPTION,
        )
    }

    @Test
    fun `row three is the watch-only xpub, and owns the Coldcard case`() {
        assertEquals("Watch-only xpub", ADD_WATCH_ONLY_BUTTON)
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
     * The one place "signer" survives on this screen, and why: it is the physical
     * device. Every user-facing string above that says "signer" must be talking
     * about the hardware, never about the thing the app stores.
     */
    @Test
    fun `signer survives only as the physical device`() {
        val copy = listOf(
            ADD_SIGNER_INTRO,
            ADD_SEEDSIGNER_CAPTION,
            ADD_SIGNING_DEVICE_CAPTION,
            ADD_WATCH_ONLY_CAPTION,
            ADD_SIGNER_FOOTER,
        ).joinToString(" ")

        // The device sense, spelled out: "your signer", the animated QR *it*
        // shows. Nothing here calls the saved record a signer.
        assertTrue("the device sense is kept", copy.contains("Keys never leave your signer"))
        assertTrue("and in the footer", copy.contains("round-trip to your signer"))
        assertTrue("but not about the record", !copy.contains("Add a signer"))
        assertTrue("nor about the record's name", !copy.contains("signer name"))
    }

    /**
     * The leftover single-button-era intro must not survive anywhere on the
     * screen: both of its sentences are gone, folded into [ADD_SIGNER_INTRO].
     */
    @Test
    fun `the old floating intro texts are gone`() {
        val copy = listOf(
            ADD_SIGNER_INTRO,
            ADD_SEEDSIGNER_CAPTION,
            ADD_SIGNING_DEVICE_CAPTION,
            ADD_WATCH_ONLY_CAPTION,
            ADD_SIGNER_FOOTER,
        ).joinToString(" ")
        assertTrue(!copy.contains("For SeedSigner users and watch-only wallets"))
        assertTrue(!copy.contains("a watch-only xpub is for use with your"))
    }

    /**
     * The old two-button chooser's labels are gone, and each old label is
     * accounted for by a row: the SeedSigner path is now its own row rather than
     * the whole of "Add signing device".
     */
    @Test
    fun `the rows are the carded three, in the carded order`() {
        assertEquals(
            listOf("SeedSigner", "Other signing device", "Watch-only xpub"),
            AddWalletRow.entries.map { it.label },
        )
        assertEquals(
            "and each row is drawn with its own constant",
            listOf(
                ADD_SEEDSIGNER_BUTTON to ADD_SEEDSIGNER_CAPTION,
                ADD_SIGNING_DEVICE_BUTTON to ADD_SIGNING_DEVICE_CAPTION,
                ADD_WATCH_ONLY_BUTTON to ADD_WATCH_ONLY_CAPTION,
            ),
            AddWalletRow.entries.map { it.label to it.caption },
        )
    }

    // --- where each row goes -------------------------------------------------
    //
    // The two animated rows open the *same* screen, so nothing about the screen
    // can tell them apart: what the user tapped is the only thing that says
    // whether the wallet is a SeedSigner's or some other device's. That mapping
    // is [routeFor], pinned here rather than left to the composable's wiring.

    @Test
    fun `the SeedSigner row opens the UR scanner, declaring a SeedSigner`() {
        val route = routeFor(AddWalletRow.SEEDSIGNER)

        assertEquals(Screen.ADD_SIGNING_DEVICE, route.screen)
        assertEquals(SignerOrigin.SEEDSIGNER_UR, route.origin)
    }

    @Test
    fun `the other-device row opens the same scanner, declaring a signing device`() {
        val route = routeFor(AddWalletRow.OTHER_SIGNING_DEVICE)

        assertEquals(
            "the same destination as the SeedSigner row — only the origin differs",
            routeFor(AddWalletRow.SEEDSIGNER).screen,
            route.screen,
        )
        assertEquals(SignerOrigin.SIGNING_DEVICE_UR, route.origin)
    }

    @Test
    fun `the watch-only row opens the text import, declaring a text xpub`() {
        val route = routeFor(AddWalletRow.WATCH_ONLY_XPUB)

        assertEquals(Screen.IMPORT_XPUB, route.screen)
        assertEquals(SignerOrigin.TEXT_QR, route.origin)
    }

    @Test
    fun `every row opens a form with a declared origin, and no two agree`() {
        val routes = AddWalletRow.entries.map(::routeFor)

        assertEquals("every row declares an origin", AddWalletRow.entries.size, routes.mapNotNull { it.origin }.size)
        assertEquals(
            "and no two rows declare the same one",
            AddWalletRow.entries.size,
            routes.map { it.origin }.toSet().size,
        )
    }

    @Test
    fun `the old two-button labels are replaced by the three rows`() {
        val rows = AddWalletRow.entries.map { it.label }

        assertTrue("the old filled label is gone", !rows.contains("Add signing device"))
        assertTrue("the old outlined label is gone", !rows.contains("Add watch-only xpub"))
    }
}
