package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.data.SignedSend
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerOrigin
import com.xbtwallet.app.data.SignerType
import com.xbtwallet.app.electrum.ElectrumException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hot-wallet send's screen state, driven without a UI.
 *
 * These tests pin the four behaviours the flow exists to get right, each of which
 * is a different *shape* from the air-gapped walk: the empty passphrase is tried
 * silently, a mismatch asks for it by name, a refused broadcast keeps the signed
 * transaction so the retry cannot rebuild it, and the success state carries the
 * txid. Nothing here touches a device, a vault or a socket — the sign and
 * broadcast functions are scripted.
 */
class HotSendFlowTest {

    private val recipient = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"

    private fun flowThatSigns(passphrase: String = ""): HotSendFlowState = HotSendFlowState(
        sign = { typed ->
            if (typed == passphrase) {
                SignedSend.Signed(txHex = "00ff", txid = "tx-1")
            } else {
                SignedSend.WrongPassphrase
            }
        },
        broadcast = { "tx-1" },
    )

    // --- the entry state -----------------------------------------------------

    @Test
    fun `the review is the entry point`() {
        val flow = HotSendFlowState(sign = { SignedSend.WrongPassphrase }, broadcast = { it })

        assertEquals(HotSendStep.REVIEW, flow.step)
        assertFalse("nothing is in flight before an action", flow.busy)
        assertNull(flow.txid)
    }

    // --- what the review shows -----------------------------------------------

    /**
     * The review carries the QR summary's three numbers, so the two flows say the
     * same thing about the same transaction, and the change line is either the
     * no-change sentence the QR form uses or how much comes back.
     */
    @Test
    fun `the review names the amount, the fee, the recipient and the change`() {
        assertEquals(
            "50,000 sats \u2192 bc1qxy2k\u2026fjhx0wlh \u00b7 fee 141 sats",
            showQrSummary(amountSatoshis = 50_000, recipient = recipient, feeSatoshis = 141),
        )
        assertEquals("Exact spend \u2014 no change output.", hotChangeLine(0L))
        assertEquals(
            "Change: 12,345 sats back to this wallet.",
            hotChangeLine(12_345L),
        )
    }

    // --- the silent empty passphrase -----------------------------------------

    @Test
    fun `an empty passphrase signs and broadcasts without ever prompting`() = runBlocking {
        var broadcastHex: String? = null
        val flow = HotSendFlowState(
            sign = { passphrase ->
                assertEquals("the empty passphrase is what is tried first", "", passphrase)
                SignedSend.Signed(txHex = "00ff", txid = "tx-1")
            },
            broadcast = { hex ->
                broadcastHex = hex
                "tx-1"
            },
        )

        flow.signAndBroadcast()

        assertEquals(HotSendStep.SUCCESS, flow.step)
        assertEquals("tx-1", flow.txid)
        assertEquals("the signed bytes are what is broadcast", "00ff", broadcastHex)
        assertFalse(flow.busy)
    }

    @Test
    fun `the signed hex is dumped once, before the broadcast`() = runBlocking {
        val dumped = mutableListOf<String>()
        val flow = HotSendFlowState(
            sign = { SignedSend.Signed(txHex = "00ff", txid = "tx-1") },
            broadcast = { "tx-1" },
            dumpSignedTx = { dumped += it },
        )

        flow.signAndBroadcast()

        assertEquals(
            "exactly one dump, and it is the signed bytes (the witness a node rejection needs)",
            listOf("00ff"),
            dumped,
        )
    }

    @Test
    fun `a refused empty passphrase shows a clean prompt, not an error`() = runBlocking {
        val flow = flowThatSigns(passphrase = "correct horse")

        flow.signAndBroadcast()

        assertEquals(HotSendStep.PASSPHRASE, flow.step)
        assertNull("the silent probe must not accuse the user", flow.passphraseError)
        assertNull(flow.signedTxHex)
    }

    // --- the wrong-passphrase loop -------------------------------------------

    @Test
    fun `a wrong passphrase says so and the right one goes through`() = runBlocking {
        val flow = flowThatSigns(passphrase = "correct horse")

        flow.signAndBroadcast()
        assertEquals(HotSendStep.PASSPHRASE, flow.step)

        flow.submitPassphrase("wrong horse")
        assertEquals("still on the prompt after a mismatch", HotSendStep.PASSPHRASE, flow.step)
        assertEquals(
            "Passphrase doesn't match this wallet",
            flow.passphraseError,
        )
        assertEquals(PASSPHRASE_MISMATCH, flow.passphraseError)

        flow.submitPassphrase("correct horse")
        assertEquals(HotSendStep.SUCCESS, flow.step)
        assertEquals("tx-1", flow.txid)
        assertNull("the mismatch is cleared once it is right", flow.passphraseError)
    }

    @Test
    fun `backing out of the prompt abandons the attempt and keeps the plan`() = runBlocking {
        val flow = flowThatSigns(passphrase = "correct horse")
        flow.signAndBroadcast()

        flow.backToReview()

        assertEquals(HotSendStep.REVIEW, flow.step)
        assertNull(flow.passphraseError)
        assertNull("nothing was signed", flow.signedTxHex)
    }

    // --- the device prompt ---------------------------------------------------

    /**
     * A cancelled device prompt is a decision, not a failure: the flow lands back
     * on the review with nothing signed, nothing broadcast, and no error — the
     * prepared transaction is exactly as it was, so the button can be pressed
     * again.
     */
    @Test
    fun `a cancelled authentication goes back to the review with the plan intact`() = runBlocking {
        val flow = HotSendFlowState(
            sign = { SignedSend.AuthenticationCancelled },
            broadcast = { error("nothing may be broadcast after a refusal") },
        )

        flow.signAndBroadcast()

        assertEquals("the review is where a refusal lands", HotSendStep.REVIEW, flow.step)
        assertNull("the user made a choice, not an error", flow.error)
        assertNull("nothing was signed", flow.signedTxHex)
        assertNull(flow.txid)
        assertFalse(flow.busy)
    }

    /** A refusal on the passphrase prompt's attempt also returns to the review. */
    @Test
    fun `a cancelled authentication on the prompt attempt returns to the review`() = runBlocking {
        val flow = HotSendFlowState(
            sign = { passphrase ->
                if (passphrase.isEmpty()) SignedSend.WrongPassphrase else SignedSend.AuthenticationCancelled
            },
            broadcast = { error("nothing may be broadcast after a refusal") },
        )

        flow.signAndBroadcast()
        assertEquals(HotSendStep.PASSPHRASE, flow.step)

        flow.submitPassphrase("correct horse")

        assertEquals(HotSendStep.REVIEW, flow.step)
        assertNull(flow.passphraseError)
        assertNull(flow.signedTxHex)
    }

    // --- a refused broadcast -------------------------------------------------

    @Test
    fun `a refused broadcast shows the server's reason and retries the same bytes`() = runBlocking {
        var attempts = 0
        val broadcastHexes = mutableListOf<String>()
        val flow = HotSendFlowState(
            sign = { SignedSend.Signed(txHex = "cafe", txid = "tx-9") },
            broadcast = { hex ->
                attempts++
                broadcastHexes += hex
                if (attempts == 1) throw ElectrumException("bad-txns-inputs-missingorspent")
                "tx-9"
            },
        )

        flow.signAndBroadcast()

        assertEquals("a refusal keeps the user on the review", HotSendStep.REVIEW, flow.step)
        assertEquals("the signed transaction is retained", "cafe", flow.signedTxHex)
        assertTrue(
            "the server's own reason must reach the user",
            flow.broadcastError.orEmpty().contains("bad-txns-inputs-missingorspent"),
        )

        // The retry never re-signs: the same bytes go back to the server.
        flow.signAndBroadcast()

        assertEquals(HotSendStep.SUCCESS, flow.step)
        assertEquals("tx-9", flow.txid)
        assertEquals(listOf("cafe", "cafe"), broadcastHexes)
        assertNull(flow.broadcastError)
    }

    // --- the success state ---------------------------------------------------

    @Test
    fun `the success screen carries the txid and the amount comes from the plan`() = runBlocking {
        val flow = flowThatSigns()

        flow.signAndBroadcast()

        assertEquals(HotSendStep.SUCCESS, flow.step)
        assertEquals("tx-1", flow.txid)
        assertEquals(HOT_SUCCESS_TITLE, "Sent")
        assertEquals(HOT_DONE_LABEL, "Done")
    }

    // --- which wallets may use this flow -------------------------------------

    @Test
    fun `a hot wallet may send and gets no disabled-feature hint`() {
        val hot = Signer(
            id = "hot-1",
            name = "Hot wallet",
            type = SignerType.HotSeed(
                xpub = "zpub-hot",
                masterFingerprint = "73c5da0a",
                hasPassphrase = false,
            ),
            createdAtEpochSeconds = 0L,
            importedVia = SignerOrigin.HOT,
        )

        assertTrue("a hot wallet is signing-ready by construction", hot.signingReady)
        assertTrue("…and its send is live now", sendSelectedEnabled(hot))
        assertNull("a live bar carries no hint", sendSelectedHint(hot))
    }
}
