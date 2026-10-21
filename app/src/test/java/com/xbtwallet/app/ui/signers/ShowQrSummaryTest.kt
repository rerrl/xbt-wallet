package com.xbtwallet.app.ui.signers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one line the SHOW_QR step shows above the QR.
 *
 * It is the only thing left of the old summary block, so it has to carry all
 * three numbers a user checks — the amount, the recipient and the fee — and it
 * has to carry the recipient in the head-and-tail form a person actually
 * compares against their signer's screen. Pinned here because it is text a
 * wallet shows about somebody's money, not a rendering detail.
 */
class ShowQrSummaryTest {

    private val recipient = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"

    @Test
    fun `names the amount, the edge-shortened recipient and the fee`() {
        assertEquals(
            "50,000 sats \u2192 bc1qxy2k\u2026fjhx0wlh \u00b7 fee 141 sats",
            showQrSummary(amountSatoshis = 50_000, recipient = recipient, feeSatoshis = 141),
        )
    }

    @Test
    fun `groups the amounts the way the rest of the wallet writes satoshis`() {
        assertEquals(
            "1,234,567 sats \u2192 bc1qxy2k\u2026fjhx0wlh \u00b7 fee 1,000 sats",
            showQrSummary(amountSatoshis = 1_234_567, recipient = recipient, feeSatoshis = 1_000),
        )
    }

    @Test
    fun `a recipient too short to elide is shown whole`() {
        // Sixteen characters is the shortest edge pair that does not overlap, so
        // anything at or below it comes back whole rather than elided.
        assertEquals(
            "21,000,000 sats \u2192 abcd1234abcd1234 \u00b7 fee 250 sats",
            showQrSummary(amountSatoshis = 21_000_000, recipient = "abcd1234abcd1234", feeSatoshis = 250),
        )
    }
}