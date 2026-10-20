package com.xbtwallet.app.crypto

import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoOutput
import com.sparrowwallet.hummingbird.registry.ScriptExpression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signing-device import: BC-UR `crypto-account` in, a watch-only signer out.
 *
 * Everything here is driven by a *vector* rather than by our own encoder —
 * [UrTestVectors] carries the BCR-2020-015 test vector (Sparrow's author, Craig
 * Raw, and Wolf McNally) and SeedSigner's own published frames, with their device
 * origin documented there. A round-trip through our encoder would pass even if we
 * misread the spec; decoding someone else's bytes cannot.
 *
 * The decode itself is plain Kotlin over hummingbird, so all of this runs on the
 * JVM: no camera, no emulator, no Android.
 */
class UrAccountImportTest {

    // --- the two shapes of export -----------------------------------------

    @Test
    fun `a static single-frame export decodes into the account`() {
        val outcome = UrAccountAssembler().receive(UrTestVectors.bcrStatic)

        val account = (outcome as? UrScanOutcome.Complete)?.account
        assertNotNull("a single-frame export must complete on the first frame", account)
        assertEquals(UrTestVectors.bcrZpub, account!!.xpub)
        assertEquals("37b5eed4", account.masterFingerprint)
        assertEquals("84'/0'/0'", account.derivationPath)
        assertTrue("a complete export has nothing to warn about", account.warnings.isEmpty())
    }

    @Test
    fun `an animated export assembles from its frames`() {
        val assembler = UrAccountAssembler()
        var completed: UrScanOutcome.Complete? = null
        for (frame in UrTestVectors.bcrAnimated) {
            val outcome = assembler.receive(frame)
            if (outcome is UrScanOutcome.Complete) completed = outcome
        }

        assertNotNull("the frames must add up to the whole export", completed)
        assertEquals(UrTestVectors.bcrZpub, completed!!.account.xpub)
        assertEquals("37b5eed4", completed.account.masterFingerprint)
        assertEquals("84'/0'/0'", completed.account.derivationPath)
    }

    /**
     * Out-of-order arrival is the *design* of a fountain code, not an edge case:
     * the signer loops its animation and the camera catches whichever frame is on
     * screen when it looks.
     */
    @Test
    fun `frames may arrive in any order`() {
        val assembler = UrAccountAssembler()
        val shuffled = listOf(5, 0, 7, 2, 1, 6, 3, 4).map { UrTestVectors.bcrAnimated[it] }

        var completed: UrScanOutcome.Complete? = null
        for (frame in shuffled) {
            val outcome = assembler.receive(frame)
            if (outcome is UrScanOutcome.Complete) completed = outcome
        }

        assertNotNull("any sufficiently large subset completes, whatever the order", completed)
        assertEquals(UrTestVectors.bcrZpub, completed!!.account.xpub)
    }

    @Test
    fun `completion is decided by the decoder, not by a part count`() {
        val assembler = UrAccountAssembler()

        // Seven of the eight fragments is one short, and the assembler must say
        // "progress" rather than guess the export is close enough. (These frames
        // are the eight simple parts of the message, so all eight are needed;
        // a longer animation would add mixed parts, which is what lets frames be
        // lost — the out-of-order and duplicate tests below cover that side.)
        for (frame in UrTestVectors.bcrAnimated.take(7)) {
            assertTrue(
                "an incomplete export must never report itself complete",
                assembler.receive(frame) is UrScanOutcome.Progress,
            )
        }

        // The last fragment is what finishes it.
        val last = assembler.receive(UrTestVectors.bcrAnimated[7])
        assertTrue("the final fragment completes the export", last is UrScanOutcome.Complete)
        assertEquals(UrTestVectors.bcrZpub, (last as UrScanOutcome.Complete).account.xpub)
    }

    @Test
    fun `duplicate frames do not disturb the assembly`() {
        val assembler = UrAccountAssembler()
        var completed: UrScanOutcome.Complete? = null
        // The camera sees the same frame several times in a row; that must be
        // harmless, not an error and not a reset.
        for (index in listOf(0, 0, 1, 1, 1, 2, 3, 4, 5, 6, 7)) {
            val outcome = assembler.receive(UrTestVectors.bcrAnimated[index])
            if (outcome is UrScanOutcome.Complete) completed = outcome
        }

        assertNotNull(completed)
        assertEquals(UrTestVectors.bcrZpub, completed!!.account.xpub)
    }

    @Test
    fun `progress is reported while the export is incomplete`() {
        val assembler = UrAccountAssembler()
        assertEquals("nothing seen yet", 0, assembler.percent)

        val first = assembler.receive(UrTestVectors.bcrAnimated.first())
        assertTrue("the first part is progress, not completion", first is UrScanOutcome.Progress)
        assertTrue("progress must move off zero once a part lands", assembler.percent > 0)
        assertTrue("and never overstate itself", assembler.percent <= 100)
    }

    @Test
    fun `a frame after completion is not an error`() {
        val assembler = UrAccountAssembler()
        assertTrue(assembler.receive(UrTestVectors.bcrStatic) is UrScanOutcome.Complete)

        // The camera may hand over one more frame before the panel closes.
        val extra = assembler.receive(UrTestVectors.bcrAnimated.first())
        assertTrue("a late frame must not turn a good scan into a bad one", extra is UrScanOutcome.Progress)
    }

    @Test
    fun `reset throws away a half-assembled export`() {
        val assembler = UrAccountAssembler()
        assembler.receive(UrTestVectors.bcrAnimated[0])
        assembler.receive(UrTestVectors.bcrAnimated[1])
        assertTrue("some progress was made", assembler.percent > 0)

        assembler.reset()
        assertEquals("a fresh scan starts from nothing", 0, assembler.percent)

        // And the frames of a new scan assemble cleanly afterwards.
        var completed: UrScanOutcome.Complete? = null
        for (frame in UrTestVectors.bcrAnimated) {
            val outcome = assembler.receive(frame)
            if (outcome is UrScanOutcome.Complete) completed = outcome
        }
        assertNotNull(completed)
    }

    // --- what is chosen out of the payload ---------------------------------

    @Test
    fun `the native segwit descriptor is chosen from several script variants`() {
        // The BCR vector carries pkh, sh(wpkh), wpkh, two multisig cosigners and
        // tr. The path proves which one was taken: only wpkh is m/84'.
        val account = completeAccount(UrTestVectors.bcrStatic)
        assertEquals("84'/0'/0'", account.derivationPath)
    }

    @Test
    fun `the rebuilt key is byte-identical to the spec's own xpub`() {
        val account = completeAccount(UrTestVectors.bcrStatic)

        // Re-read what we produced and re-encode it as an xpub: it must equal the
        // key BCR-2020-015 publishes for the wpkh descriptor, byte for byte. That
        // checks the key material, the chain code, the depth and the parent
        // fingerprint — everything a bare "it parsed" assertion would miss.
        val reparsed = ExtendedPublicKey.parse(account.xpub)
        assertEquals(UrTestVectors.bcrSpecXpub, reparsed.reencode(XpubVersion.XPUB))
    }

    @Test
    fun `a seedSigner multisig export has no native segwit account`() {
        val assembler = UrAccountAssembler()
        var rejected: UrScanOutcome.Rejected? = null
        // SeedSigner's own frames, out of order, exactly as a camera would see them.
        for (index in listOf(2, 0, 3, 1)) {
            val outcome = assembler.receive(UrTestVectors.seedSigner[index])
            if (outcome is UrScanOutcome.Rejected) rejected = outcome
        }

        assertNotNull("a wsh(cosigner…) account is not one this app can import", rejected)
        assertEquals(UrAccountAssembler.NO_NATIVE_SEGWIT_ACCOUNT, rejected!!.message)
    }

    @Test
    fun `an export with no key origin warns but still decodes`() {
        // A wpkh descriptor whose hdkey carries no origin: the key material is all
        // there, the derivation path is not. Matching the manual import's stance,
        // that is a warning, not a refusal.
        val hdKey = CryptoHDKey(
            false,
            hexToBytes("03fd433450b6924b4f7efdd5d1ed017d364be95ab2b592dc8bddb3b00c1c24f63f"),
            hexToBytes("72ede7334d5acf91c6fda622c205199c595a31f9218ed30792d301d5ee9e3a88"),
            null,
            null,
            null,
            null,
        )
        val output = CryptoOutput(listOf(ScriptExpression.WITNESS_PUBLIC_KEY_HASH), hdKey)
        val account = CryptoAccount(hexToBytes("37b5eed4"), listOf(output))
        val frame = UREncoder(account.toUR(), 100, 10, 0).nextPart()

        val decoded = (UrAccountAssembler().receive(frame) as? UrScanOutcome.Complete)?.account

        assertNotNull("a missing path must not stop the import", decoded)
        assertNull(decoded!!.derivationPath)
        assertEquals("the account fingerprint is the fallback", "37b5eed4", decoded.masterFingerprint)
        assertTrue(
            "and the user is told the path is missing",
            decoded.warnings.contains(UrAccountAssembler.NO_DERIVATION_PATH_WARNING),
        )
    }

    // --- what is refused, and how ------------------------------------------

    @Test
    fun `a text payload is refused as not a signing-device export`() {
        // The likeliest mistake on this screen: the watch-only path's QR.
        for (payload in listOf(
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADtwsVXhbp9BjJ9n8uMzXKK3dUY1qY2uJ3ok9dBHyPtJQ5",
            "bitcoin:zpub6rFR7y4Q2Aij?amount=1",
            "[ab12cd34/84'/0'/0']zpub6rFR7y4Q2Aij",
            "hello world",
            "",
        )) {
            val outcome = UrAccountAssembler().receive(payload)
            assertEquals(
                "a payload that is not a UR at all: $payload",
                UrAccountAssembler.NOT_A_SIGNING_DEVICE_EXPORT,
                (outcome as? UrScanOutcome.Rejected)?.message,
            )
        }
    }

    @Test
    fun `a text payload is never reported as a Base58 problem`() {
        val outcome = UrAccountAssembler().receive("zpub6rFR7y4Q2Aij")
        val message = (outcome as UrScanOutcome.Rejected).message

        assertFalse(
            "the user is on the signing-device screen; the QR is wrong, not the key",
            message.contains("Base58", ignoreCase = true),
        )
    }

    @Test
    fun `a transaction qr is refused as a transaction qr`() {
        val outcome = UrAccountAssembler().receive("ur:crypto-psbt/1-2/lpadaacskpc")

        assertEquals(
            UrAccountAssembler.TRANSACTION_QR_MESSAGE,
            (outcome as? UrScanOutcome.Rejected)?.message,
        )
    }

    @Test
    fun `a ur of another type names itself`() {
        val outcome = UrAccountAssembler().receive("ur:crypto-seed/1-2/lpadaacskpc")

        val message = (outcome as UrScanOutcome.Rejected).message
        assertTrue("the message should say what it actually is: $message", message.contains("crypto-seed"))
        assertFalse(message.contains("Base58", ignoreCase = true))
    }

    @Test
    fun `receive never throws on junk`() {
        val assembler = UrAccountAssembler()
        for (payload in listOf(
            "",
            "   ",
            "ur:",
            "ur:/",
            "ur:crypto-account",
            "ur:crypto-account/",
            "ur:crypto-account/1-2/",
            "ur:crypto-account/1-2/not-bytewords-at-all",
            "UR:CRYPTO-ACCOUNT/1-1/lpadaycfaxahcygmdavolkhdhs",
            "ur:crypto-account/99-99/lpadaycfaxahcygmdavolkhdhs",
            "\u0000\u0001\u0002",
        )) {
            // Whatever comes back, it comes back as an outcome: a torn read must
            // never take the screen down.
            assertNotNull("payload: $payload", assembler.receive(payload))
        }
    }

    @Test
    fun `the ur type is read off the text, case-insensitively`() {
        assertEquals("crypto-account", UrAccountAssembler.urTypeOf("ur:crypto-account/1-2/xx"))
        assertEquals("crypto-account", UrAccountAssembler.urTypeOf("UR:CRYPTO-ACCOUNT/1-2/xx"))
        assertEquals("crypto-psbt", UrAccountAssembler.urTypeOf(" ur:crypto-psbt/1-2/xx "))
        assertNull("a bare key is not a UR", UrAccountAssembler.urTypeOf("zpub6xyz"))
        assertNull("nor is a URI", UrAccountAssembler.urTypeOf("bitcoin:zpub6xyz?amount=1"))
    }

    private fun completeAccount(ur: String): UrAccount =
        (UrAccountAssembler().receive(ur) as UrScanOutcome.Complete).account

    /** `00ff10` → three bytes. Test-only; the app never parses hex keys. */
    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}
