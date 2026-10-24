package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.chain.AddressChain
import com.xbtwallet.app.chain.ChainUtxo
import com.xbtwallet.app.chain.ScannedUtxo
import com.xbtwallet.app.chain.exactSendVsize
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerOrigin
import com.xbtwallet.app.data.SignerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic send's screen state, pinned without a UI.
 *
 * These tests drive the same pure function the Send form calls: given a
 * snapshot's coins, an amount and a fee rate, what the form displays and what it
 * will build. They also pin the rule that decides *which wallets* ever reach
 * this path — a signing device only.
 */
class AutoSendSelectionTest {

    /** The BIP-84 account zpub used across the suite. */
    private val bip84Zpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868" +
            "RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    private fun snapshotCoin(txid: String, satoshis: Long) =
        ScannedUtxo(ChainUtxo(txid, 0, height = 100, satoshis = satoshis), AddressChain.RECEIVE, 0)

    private fun signingDevice() = Signer(
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

    private fun textQrWatchOnly() = Signer(
        id = "text-1",
        name = "Cold storage",
        type = SignerType.WatchOnlyXpub(bip84Zpub),
        createdAtEpochSeconds = 0L,
        importedVia = SignerOrigin.TEXT_QR,
    )

    private fun hotWallet() = Signer(
        id = "hot-1",
        name = "Hot wallet",
        type = SignerType.HotSeed(xpub = bip84Zpub, masterFingerprint = "73c5da0a", hasPassphrase = false),
        createdAtEpochSeconds = 0L,
        importedVia = SignerOrigin.HOT,
    )

    // --- the auto path populates from the snapshot -------------------------

    @Test
    fun `the auto path selects from the snapshot's coins`() {
        val coins = listOf(snapshotCoin("aa", 100_000), snapshotCoin("bb", 100_000))

        val outcome = autoSelect(coins, amountSatoshis = 150_000, feeRateSatPerVb = 1, max = false)

        val selection = outcome as AutoSendOutcome.Selection
        assertEquals("both coins are needed", 2, selection.inputs.size)
        assertEquals(150_000L, selection.amountSatoshis)
        assertEquals(
            exactSendVsize(2, listOf(22, 22)).toLong(),
            selection.feeSatoshis,
        )
        assertEquals("Coins selected automatically (2 inputs)", autoSummaryFor(outcome))
    }

    @Test
    fun `before an amount is priced the form says only that coins are automatic`() {
        assertEquals(AUTO_SELECTION_PENDING, autoSummaryFor(null))
    }

    // --- insufficient funds --------------------------------------------------

    @Test
    fun `an amount the coins cannot cover is an insufficient-funds state`() {
        val coins = listOf(snapshotCoin("aa", 100_000))

        val outcome = autoSelect(coins, amountSatoshis = 1_000_000, feeRateSatPerVb = 1, max = false)

        assertTrue(outcome is AutoSendOutcome.Insufficient)
        assertEquals(
            "Insufficient funds: 100,000 sats available, 1,000,141 sats needed.",
            autoErrorFor(outcome),
        )
        assertEquals("no priced summary is shown", AUTO_SELECTION_PENDING, autoSummaryFor(outcome))
    }

    @Test
    fun `an empty wallet is an insufficient-funds state, not a crash`() {
        val outcome = autoSelect(emptyList(), amountSatoshis = 50_000, feeRateSatPerVb = 1, max = false)

        assertTrue(outcome is AutoSendOutcome.Insufficient)
        assertEquals(0L, (outcome as AutoSendOutcome.Insufficient).availableSatoshis)
    }

    // --- the exact-match line ------------------------------------------------

    @Test
    fun `an exact spend shows the no-change line`() {
        // 100_000 + exactSendVsize(1, [22]) = 100_000 + 110.
        val coins = listOf(snapshotCoin("aa", 100_110))

        val outcome = autoSelect(coins, amountSatoshis = 100_000, feeRateSatPerVb = 1, max = false)

        assertTrue("the exact pass must fire", autoIsExact(outcome))
        assertEquals("Exact spend \u2014 no change output.", EXACT_SPEND_LINE)
    }

    @Test
    fun `an ordinary send hides the no-change line`() {
        val coins = listOf(snapshotCoin("aa", 200_000))

        val outcome = autoSelect(coins, amountSatoshis = 100_000, feeRateSatPerVb = 1, max = false)

        assertFalse(autoIsExact(outcome))
        assertFalse("a failure never claims exactness", autoIsExact(null))
    }

    // --- max send ------------------------------------------------------------

    @Test
    fun `a max send spends everything and leaves the fee behind`() {
        val coins = listOf(snapshotCoin("aa", 100_000), snapshotCoin("bb", 40_000))

        val outcome = autoSelect(coins, amountSatoshis = 1, feeRateSatPerVb = 1, max = true)

        val selection = outcome as AutoSendOutcome.Selection
        assertEquals(2, selection.inputs.size)
        assertEquals(140_000L, selection.amountSatoshis + selection.feeSatoshis)
        assertFalse(selection.exact)
    }

    // --- the manual link -----------------------------------------------------

    @Test
    fun `the automatic path offers a way back to the manual picker`() {
        assertEquals("Choose manually", CHOOSE_MANUALLY_LABEL)
    }

    // --- which wallets may reach the auto path ------------------------------

    @Test
    fun `a signing device is allowed to send`() {
        assertTrue(sendSelectedEnabled(signingDevice()))
    }

    @Test
    fun `a watch-only import is not allowed to send`() {
        assertFalse(sendSelectedEnabled(textQrWatchOnly()))
    }

    @Test
    fun `a hot wallet stays disabled on the automatic path`() {
        assertTrue("a hot wallet is signing-ready by construction", hotWallet().signingReady)
        assertFalse("but its send is a later phase, so it stays disabled", sendSelectedEnabled(hotWallet()))
        assertEquals(
            "Send comes with the air-gapped signing flow \u2014 coming soon.",
            detailSendNote(hotWallet()),
        )
    }

    @Test
    fun `a disabled watch-only wallet is pointed at the fix`() {
        assertEquals(WATCH_ONLY_CANNOT_SPEND_HINT, detailSendNote(textQrWatchOnly()))
    }
}
