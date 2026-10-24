package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerOrigin
import com.xbtwallet.app.data.SignerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the list-row snippet rule.
 *
 * The Signers list shows a head…tail of each key so two signers are told apart
 * at a glance, and so the middle of an xpub never fills the row. This is pure
 * formatting, but it is the kind of formatting that quietly loses an end of the
 * key if it is wrong, so it is pinned here rather than left to the eye.
 */
class SignerPresentationTest {

    /** The BIP-84 account zpub used elsewhere in the suite — 111 characters. */
    private val bip84Zpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868" +
            "RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    @Test
    fun `a real key keeps its head and its tail`() {
        val shortened = shortenedXpub(bip84Zpub)

        assertEquals("zpub6rFR7y\u2026GutZYs", shortened)
        assertTrue("the head is preserved", shortened.startsWith(bip84Zpub.take(10)))
        assertTrue("the tail is preserved", shortened.endsWith(bip84Zpub.takeLast(6)))
        assertTrue("the middle is elided", shortened.length < bip84Zpub.length)
    }

    @Test
    fun `a key short enough to show whole is left whole`() {
        // No point replacing content with an ellipsis that is longer than what
        // it hides.
        assertEquals("zpub6xyz", shortenedXpub("zpub6xyz"))
    }

    @Test
    fun `the boundary is inclusive - one character over elides`() {
        // Exactly head + tail + 1 (17): shown whole, so the ellipsis would not
        // be longer than what it hides.
        val whole = "x".repeat(SHORTENED_HEAD_CHARS + SHORTENED_TAIL_CHARS + 1)
        assertEquals(whole, shortenedXpub(whole))

        // One character more (18) crosses into elision: 10 from the front, the
        // last 6 (five x's and the Z) from the back.
        val oneMore = whole + "Z"
        assertEquals("x".repeat(10) + "\u2026" + "xxxxxZ", shortenedXpub(oneMore))
    }

    @Test
    fun `an empty key stays empty`() {
        assertEquals("", shortenedXpub(""))
    }

    // --- which tag a signer wears -------------------------------------------
    //
    // The tag is the one place the import path is visible to the user, so the
    // rule lives here as plain Kotlin and is pinned without a UI: a key that came
    // off a SeedSigner wears "seedsigner" in orange, a key off any other signing
    // device wears "signing device" in the distinct blue, and a text xpub keeps
    // the original grey "watch-only" pill.

    private fun signer(
        importedVia: SignerOrigin = SignerOrigin.TEXT_QR,
    ) = Signer(
        id = "id-1",
        name = "Cold storage",
        type = SignerType.WatchOnlyXpub(bip84Zpub),
        createdAtEpochSeconds = 0L,
        importedVia = importedVia,
    )

    @Test
    fun `a text-qr signer wears the grey watch-only tag`() {
        val tag = signerTagFor(signer(SignerOrigin.TEXT_QR))

        assertEquals("watch-only", tag.label)
        assertEquals(SignerTagAccent.NEUTRAL, tag.accent)
    }

    @Test
    fun `a seedsigner signer wears the orange seedsigner tag`() {
        val tag = signerTagFor(signer(SignerOrigin.SEEDSIGNER_UR))

        assertEquals("seedsigner", tag.label)
        assertEquals(SignerTagAccent.ORANGE, tag.accent)
    }

    @Test
    fun `an other-device signer wears the distinct signing-device tag`() {
        val tag = signerTagFor(signer(SignerOrigin.SIGNING_DEVICE_UR))

        // Not "seedsigner" (the user did not say the device was one) and not the
        // grey of a text xpub (it did not arrive as text) — its own word, in its
        // own accent.
        assertEquals("signing device", tag.label)
        assertEquals(SignerTagAccent.DEVICE, tag.accent)
        assertTrue(
            "the signing-device accent must not be the grey of a text xpub",
            tag.accent != SignerTagAccent.NEUTRAL,
        )
    }

    /** Every origin answers with a tag, and no two accents collide. */
    @Test
    fun `each origin has its own tag and its own accent`() {
        val tags = SignerOrigin.entries.map { signerTagFor(signer(it)) }

        assertEquals(
            setOf("watch-only", "seedsigner", "signing device", "hot"),
            tags.map { it.label }.toSet(),
        )
        assertEquals(
            "one accent per origin, no sharing",
            SignerOrigin.entries.size,
            tags.map { it.accent }.toSet().size,
        )
    }

    // --- the hot wallet's tag ------------------------------------------------
    //
    // A hot wallet is the one signer whose spending power is on the phone, so its
    // tag has to be the one that reads as a warning — and it has to be the *only*
    // thing wearing that word.

    private fun hotSigner() = Signer(
        id = "hot-1",
        name = "Hot wallet",
        type = SignerType.HotSeed(
            xpub = bip84Zpub,
            masterFingerprint = "73c5da0a",
            hasPassphrase = false,
        ),
        createdAtEpochSeconds = 0L,
        importedVia = SignerOrigin.HOT,
    )

    @Test
    fun `a hot wallet wears the hot tag in its own accent`() {
        val tag = signerTagFor(hotSigner())

        assertEquals("hot", tag.label)
        assertEquals(SignerTagAccent.HOT, tag.accent)
        assertEquals("the label is the constant the screen renders", HOT_TAG_LABEL, tag.label)
        assertTrue(
            "the hot accent must not be the grey of a text xpub",
            tag.accent != SignerTagAccent.NEUTRAL,
        )
        assertTrue(
            "…nor the orange of a SeedSigner, nor the blue of another device",
            tag.accent != SignerTagAccent.ORANGE && tag.accent != SignerTagAccent.DEVICE,
        )
    }

    @Test
    fun `the hot wallet's amber is driven by its origin`() {
        // The word and the accent come from different halves of the record, and
        // this pins which is which: the label is the *type*'s, like every other
        // tag here, while the amber belongs to the origin — the wallet whose seed
        // arrived on this phone.
        val hot = signerTagFor(hotSigner())
        val readAsText = signerTagFor(hotSigner().copy(importedVia = SignerOrigin.TEXT_QR))

        assertEquals("hot", hot.label)
        assertEquals(SignerTagAccent.HOT, hot.accent)

        assertEquals("the word still comes from the type", HOT_TAG_LABEL, readAsText.label)
        assertEquals(
            "…but the amber does not survive a different origin",
            SignerTagAccent.NEUTRAL,
            readAsText.accent,
        )
    }

    @Test
    fun `a signer with no recorded origin wears the grey watch-only tag`() {
        // The default on [Signer] is the text-QR path, so a record that predates
        // the field (or a call site that does not set it) is tagged as before.
        val tag = signerTagFor(
            Signer(
                id = "id-1",
                name = "Cold storage",
                type = SignerType.WatchOnlyXpub(bip84Zpub),
                createdAtEpochSeconds = 0L,
            ),
        )

        assertEquals("watch-only", tag.label)
        assertEquals(SignerTagAccent.NEUTRAL, tag.accent)
    }

    // --- the "Send selected" gate -------------------------------------------
    //
    // Which wallets may start the UTXOs screen's send. Signing-readiness alone
    // is *not* the rule: a hot wallet is signing-ready by construction, but the
    // only send wired up there is the air-gapped QR dance, which no device can
    // sign for a hot wallet — so it is gated out under its own hint.

    private fun signingReadyWatchOnly() = Signer(
        id = "dev-1",
        name = "Signing device",
        type = SignerType.WatchOnlyXpub(
            bip84Zpub,
            masterFingerprint = "ab12cd34",
            derivationPath = "84'/0'/0'",
        ),
        createdAtEpochSeconds = 0L,
        importedVia = SignerOrigin.SEEDSIGNER_UR,
    )

    @Test
    fun `a signing-ready watch-only wallet may send`() {
        val device = signingReadyWatchOnly()

        assertTrue("a signing device is signing-ready", device.signingReady)
        assertTrue("…and this screen's send is open to it", sendSelectedEnabled(device))
        assertNull("no hint is shown for a live bar", sendSelectedHint(device))
    }

    @Test
    fun `a text-qr watch-only wallet stays disabled under the watch-only hint`() {
        val textQr = signer(SignerOrigin.TEXT_QR)

        assertFalse(sendSelectedEnabled(textQr))
        assertEquals(WATCH_ONLY_CANNOT_SPEND_HINT, sendSelectedHint(textQr))
    }

    @Test
    fun `a hot wallet may send from the bar now, and shows no hint`() {
        val hot = hotSigner()

        assertTrue("a hot wallet is signing-ready by construction", hot.signingReady)
        assertTrue("…and its own sign-and-broadcast flow is live", sendSelectedEnabled(hot))
        assertNull("a live bar carries no hint", sendSelectedHint(hot))
    }
}
