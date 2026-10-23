package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.data.SignerOrigin
import com.xbtwallet.app.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the copy of the **Add wallet** screen — now three cards — and of the
 * **Signing device** chooser behind its first card, verbatim.
 *
 * The screen is wallet-first and SeedSigner-first: the first card leads with the
 * external-signer family (SeedSigner or any other device that signs over QR), the
 * second is the hot wallet with its two ways in, and the third is watch-only.
 * These strings are the screen's user-visible contract, so they are asserted as
 * literals here rather than through the constants, so that an edit to a constant
 * fails a test instead of silently shipping.
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
    fun `card one is the external signer, and asks the next question elsewhere`() {
        assertEquals("SeedSigner / External signer", ADD_SIGNER_EXTERNAL_TITLE)
        assertEquals(
            "Select this if your wallet supports QR-based transaction signing.",
            ADD_SIGNER_EXTERNAL_COPY,
        )
        assertEquals("Select", ADD_SIGNER_SELECT_BUTTON)
    }

    @Test
    fun `card two is the hot wallet, with create and import`() {
        assertEquals("Hot wallet", ADD_SIGNER_HOT_TITLE)
        assertEquals(
            "Private keys live on this device. Create a new wallet with dice rolls, or " +
                "import an existing seed.",
            ADD_SIGNER_HOT_COPY,
        )
        assertEquals("Create", ADD_SIGNER_CREATE_BUTTON)
        assertEquals("Import", ADD_SIGNER_IMPORT_BUTTON)
    }

    @Test
    fun `card three is watch only, and says what it cannot do`() {
        assertEquals("Watch only", ADD_SIGNER_WATCH_TITLE)
        assertEquals(
            "The phone holds public keys only. Check balances, view UTXOs and receive/change " +
                "addresses, and share a receive address \u2014 but never spend or prepare a " +
                "transaction. Scan an xpub QR from any coordinator.",
            ADD_SIGNER_WATCH_COPY,
        )
    }

    /**
     * A watch-only wallet can neither spend nor prepare a transaction, and the
     * disabled-send hint on the UTXO list (and the stale one still wired into
     * the Send form) must say exactly that, from one shared constant, so the two
     * screens cannot drift apart or fall back to the retired "signing-ready"
     * wording.
     */
    @Test
    fun `the disabled-send hint says a watch-only wallet cannot spend`() {
        assertEquals(
            "A watch-only wallet holds public keys only -- it cannot spend. To send " +
                "from this account, add it as a signing device or hot wallet instead.",
            WATCH_ONLY_CANNOT_SPEND_HINT,
        )
    }

    @Test
    fun `watch copy no longer claims it can prepare transactions`() {
        assertTrue(
            "the watch card no longer offers transaction preparation",
            !ADD_SIGNER_WATCH_COPY.contains("prepare transactions"),
        )
        assertTrue(
            "and the hint no longer sends the user looking for a key-expression QR",
            !WATCH_ONLY_CANNOT_SPEND_HINT.contains("key-expression"),
        )
    }

    /**
     * The retired row-era copy is gone, and gone from the screen rather than
     * merely unreferenced: the intro paragraph and the footer panel were removed
     * with their constants, and so was the watch-only row's own wording.
     */
    @Test
    fun `the retired intro, footer and row copy do not survive`() {
        val copy = listOf(
            ADD_SIGNER_EXTERNAL_COPY,
            ADD_SIGNER_HOT_COPY,
            ADD_SIGNER_WATCH_COPY,
        ).joinToString(" ")

        assertTrue("no intro paragraph", !copy.contains("Add a wallet to watch."))
        assertTrue("no footer panel", !copy.contains("Watching is enough to receive"))
        assertTrue("no row-era watch-only copy", !copy.contains("Watch-only xpub"))
        assertTrue("no row-era signer talk", !copy.contains("Keys never leave your signer"))
    }

    /**
     * The chooser behind card one keeps both external-signer options, each drawn
     * with its own verbatim copy, and no third option: watch-only is a card of
     * its own now and needs no chooser.
     */
    @Test
    fun `the signing-device chooser offers the two external options, in order`() {
        assertEquals(
            listOf("SeedSigner", "Other signing device"),
            AddWalletRow.entries.map { it.label },
        )
        assertEquals(
            "and each option is drawn with its own constant",
            listOf(
                ADD_SEEDSIGNER_BUTTON to ADD_SEEDSIGNER_CAPTION,
                ADD_SIGNING_DEVICE_BUTTON to ADD_SIGNING_DEVICE_CAPTION,
            ),
            AddWalletRow.entries.map { it.label to it.caption },
        )
    }

    @Test
    fun `the SeedSigner option keeps its export-menu walkthrough`() {
        assertEquals(
            "Export Xpub \u2192 single-sig \u2192 native segwit \u2192 Animated QR on your " +
                "SeedSigner, then scan it here.",
            ADD_SEEDSIGNER_CAPTION,
        )
    }

    @Test
    fun `the other-device option keeps naming the devices it covers`() {
        assertEquals(
            "Keystone, Jade \u2014 scan the device's animated BC-UR export QR.",
            ADD_SIGNING_DEVICE_CAPTION,
        )
    }

    @Test
    fun `the chooser screen is titled by the question it asks`() {
        assertEquals("Signing device", SIGNING_DEVICE_CHOICE_TITLE)
    }

    // --- where each option goes ----------------------------------------------
    //
    // The two options open the *same* screen, so nothing about the screen can
    // tell them apart: what the user tapped is the only thing that says whether
    // the wallet is a SeedSigner's or some other device's. That mapping is
    // [routeFor], pinned here rather than left to the composable's wiring.

    @Test
    fun `the SeedSigner option opens the UR scanner, declaring a SeedSigner`() {
        val route = routeFor(AddWalletRow.SEEDSIGNER)

        assertEquals(Screen.ADD_SIGNING_DEVICE, route.screen)
        assertEquals(SignerOrigin.SEEDSIGNER_UR, route.origin)
    }

    @Test
    fun `the other-device option opens the same scanner, declaring a signing device`() {
        val route = routeFor(AddWalletRow.OTHER_SIGNING_DEVICE)

        assertEquals(
            "the same destination as the SeedSigner option — only the origin differs",
            routeFor(AddWalletRow.SEEDSIGNER).screen,
            route.screen,
        )
        assertEquals(SignerOrigin.SIGNING_DEVICE_UR, route.origin)
    }

    @Test
    fun `every option opens a form with a declared origin, and no two agree`() {
        val routes = AddWalletRow.entries.map(::routeFor)

        assertEquals("every option declares an origin", AddWalletRow.entries.size, routes.mapNotNull { it.origin }.size)
        assertEquals(
            "and no two declare the same one",
            AddWalletRow.entries.size,
            routes.map { it.origin }.toSet().size,
        )
    }

    /**
     * The watch-only row is gone, so its copy and its destination must not be
     * reachable through this chooser any more: watch-only is card three of
     * [AddSignerScreen], and its one import form declares
     * [SignerOrigin.TEXT_QR] there.
     */
    @Test
    fun `the chooser no longer offers a watch-only row`() {
        val labels = AddWalletRow.entries.map { it.label }

        assertTrue("the watch-only row is gone", !labels.contains("Watch-only xpub"))
        assertTrue(
            "and no option claims the text-xpub origin",
            AddWalletRow.entries.map(::routeFor).none { it.origin == SignerOrigin.TEXT_QR },
        )
    }
}
