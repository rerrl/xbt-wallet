package com.xbtwallet.app.ui.signers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact strings the transaction detail screen prints for the on-chain facts
 * it can and cannot know.
 *
 * The rule under test throughout: a value that is genuinely unknown is the em
 * dash, never a zero and never a placeholder. It matters most for the block
 * height — an unconfirmed (mempool) transaction reports height `0` or `-1`, and
 * rendering that would print "#0"/"#-1", a block that does not exist.
 */
class TransactionDetailPresentationTest {

    @Test
    fun `an unconfirmed transaction has no block height`() {
        // Mempool transactions carry height 0 or -1; neither is a real block.
        assertEquals(UNKNOWN_VALUE, formatBlockHeight(0))
        assertEquals(UNKNOWN_VALUE, formatBlockHeight(-1))
    }

    @Test
    fun `an unknown block height is the em dash`() {
        assertEquals(UNKNOWN_VALUE, formatBlockHeight(null))
        assertEquals("\u2014", formatBlockHeight(0))
    }

    @Test
    fun `a mined transaction prints its block height, grouped`() {
        assertEquals("#1", formatBlockHeight(1))
        assertEquals("#850,000", formatBlockHeight(850_000))
        assertEquals("#919,231", formatBlockHeight(919_231))
    }

    @Test
    fun `unknown confirmations and status are the em dash and the word Unknown`() {
        assertEquals(UNKNOWN_VALUE, exactConfirmations(null))
        assertEquals("100", exactConfirmations(100))
        assertEquals("Unknown", statusLabel(null))
        assertEquals("Unconfirmed (in mempool)", statusLabel(false))
        assertEquals("Confirmed", statusLabel(true))
    }

    @Test
    fun `an unknown net amount is the em dash, never a zero`() {
        assertEquals(UNKNOWN_VALUE, detailAmountLabel(null))
        assertEquals("+0.00000001 XBT in", detailAmountLabel(1))
    }
}
