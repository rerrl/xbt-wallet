package com.xbtwallet.app.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic selector's rules, pinned without a UI.
 *
 * Every expected fee below is derived from [exactSendVsize] — the same function
 * the planner charges with — so these tests fail if the selector and the planner
 * ever disagree about what a send costs. A couple of the numbers are also
 * written out literally (110 vB for 1-in/1-out, 141 for 1-in/2-out, 209 for
 * 2-in/2-out) so a change to the size arithmetic has to be deliberate.
 */
class CoinSelectorTest {

    private fun coin(txid: String, vout: Int, satoshis: Long) = SelectableUtxo(txid, vout, satoshis)

    private val recipient = 22
    private val change = 22

    // --- the exact-match pass ------------------------------------------------

    @Test
    fun `a single coin that pays the amount plus its own fee exactly needs no change`() {
        // 100_000 + exactSendVsize(1, [22]) * 1 = 100_000 + 110.
        val selection = selectCoins(
            utxos = listOf(coin("aa", 0, 100_110)),
            amountSatoshis = 100_000,
            feeRateSatPerVb = 1,
            recipientScriptSize = recipient,
            changeScriptSize = change,
        )

        assertTrue("the exact pass must claim the match", selection.isExactMatch)
        assertEquals(listOf("aa:0"), selection.outpoints)
        assertEquals(110L, selection.feeSatoshis)
        assertEquals(110L, exactSendVsize(1, listOf(recipient)).toLong())
    }

    @Test
    fun `the exact pass finds the smallest-kept subset, not just any coin`() {
        // Only the 100_110 coin matches; the others are decoys that do not.
        val selection = selectCoins(
            utxos = listOf(
                coin("aa", 0, 5_000),
                coin("bb", 0, 100_110),
                coin("cc", 0, 250_000),
            ),
            amountSatoshis = 100_000,
            feeRateSatPerVb = 1,
            recipientScriptSize = recipient,
            changeScriptSize = change,
        )

        assertTrue(selection.isExactMatch)
        assertEquals(listOf("bb:0"), selection.outpoints)
    }

    @Test
    fun `an amount with no exact subset falls through to largest-first`() {
        val selection = selectCoins(
            utxos = listOf(coin("aa", 0, 200_000)),
            amountSatoshis = 100_000,
            feeRateSatPerVb = 1,
            recipientScriptSize = recipient,
            changeScriptSize = change,
        )

        assertFalse(selection.isExactMatch)
        assertEquals(1, selection.inputCount)
        assertEquals("1-in/2-out is 141 vB", 141L, selection.feeSatoshis)
    }

    // --- largest-first accumulation, with the fee recomputed ---------------

    @Test
    fun `the fee is repriced after every coin added, not fixed at one input`() {
        // One 60_000 coin cannot cover 100_000 + 141. Two can, but the send is
        // now 2-in/2-out: 209 vB, not 141 — the whole point of recomputing.
        val selection = selectCoins(
            utxos = listOf(coin("aa", 0, 60_000), coin("bb", 0, 60_000)),
            amountSatoshis = 100_000,
            feeRateSatPerVb = 1,
            recipientScriptSize = recipient,
            changeScriptSize = change,
        )

        assertFalse(selection.isExactMatch)
        assertEquals(2, selection.inputCount)
        assertEquals(209L, selection.feeSatoshis)
        assertEquals(
            "the fee must match the size of the transaction actually built",
            exactSendVsize(2, listOf(recipient, change)).toLong(),
            selection.feeSatoshis,
        )
    }

    @Test
    fun `largest coins are taken first`() {
        val selection = selectCoins(
            utxos = listOf(coin("small", 0, 10_000), coin("big", 0, 500_000)),
            amountSatoshis = 100_000,
            feeRateSatPerVb = 1,
            recipientScriptSize = recipient,
            changeScriptSize = change,
        )

        assertEquals(listOf("big:0"), selection.outpoints)
    }

    // --- the bounded exact search -------------------------------------------

    @Test
    fun `a large coin set with no exact subset terminates and falls through`() {
        // Thirty equal coins and a 1-sat amount: no subset totals 1 + fee(n), so
        // the branch-and-bound would run to 2^30 without its cap. It must stop.
        val utxos = (0 until 30).map { index ->
            coin("aa%02d".format(index), 0, 100_000)
        }

        val selection = selectCoins(
            utxos = utxos,
            amountSatoshis = 1,
            feeRateSatPerVb = 1,
            recipientScriptSize = recipient,
            changeScriptSize = change,
        )

        assertFalse("no exact match exists, so the cap must fall through", selection.isExactMatch)
        assertEquals(1, selection.inputCount)
    }

    // --- failure -------------------------------------------------------------

    @Test
    fun `too few coins is a typed InsufficientFunds carrying both numbers`() {
        val failure = try {
            selectCoins(
                utxos = listOf(coin("aa", 0, 1_000)),
                amountSatoshis = 100_000,
                feeRateSatPerVb = 1,
                recipientScriptSize = recipient,
                changeScriptSize = change,
            )
            null
        } catch (insufficient: InsufficientFunds) {
            insufficient
        }

        assertEquals(1_000L, failure?.availableSatoshis)
        assertEquals("the required side is the amount plus the 1-in/2-out fee", 100_141L, failure?.requiredSatoshis)
    }

    // --- determinism ---------------------------------------------------------

    @Test
    fun `equal-value coins select in outpoint order whatever the input order`() {
        val a = coin("aa", 0, 50_000)
        val b = coin("bb", 0, 50_000)

        val forward = selectCoins(listOf(a, b), 40_000, 1, recipient, change)
        val backward = selectCoins(listOf(b, a), 40_000, 1, recipient, change)

        assertEquals(listOf("aa:0"), forward.outpoints)
        assertEquals("the same wallet must select the same coin twice", forward.outpoints, backward.outpoints)
    }

    @Test
    fun `a single-coin wallet spends that coin`() {
        val selection = selectCoins(
            utxos = listOf(coin("only", 0, 100_000)),
            amountSatoshis = 50_000,
            feeRateSatPerVb = 1,
            recipientScriptSize = recipient,
            changeScriptSize = change,
        )

        assertEquals(listOf("only:0"), selection.outpoints)
        assertEquals(141L, selection.feeSatoshis)
    }

    // --- max send ------------------------------------------------------------

    @Test
    fun `selectMax spends the whole balance and prices it without change`() {
        val selection = selectMax(
            utxos = listOf(coin("aa", 0, 100_000), coin("bb", 0, 40_000)),
            feeRateSatPerVb = 1,
            recipientScriptSize = recipient,
        )

        assertEquals(2, selection.inputCount)
        assertFalse("a max send is the deliberate no-change case, not a match", selection.isExactMatch)
        assertEquals(exactSendVsize(2, listOf(recipient)).toLong(), selection.feeSatoshis)
        assertEquals(140_000L, selection.totalSatoshis)
    }

    @Test
    fun `a max send planned through planSend has no change output and folds nothing`() {
        val coin = coin("aa", 0, 100_000)
        val selection = selectMax(listOf(coin), feeRateSatPerVb = 1, recipientScriptSize = recipient)
        val amount = selection.totalSatoshis - selection.feeSatoshis

        val plan = planSend(
            inputs = listOf(SendInput("aa", 0, coin.satoshis, byteArrayOf(0x00, 0x14) + ByteArray(20))),
            amountSatoshis = amount,
            feeRateSatPerVb = 1,
            recipientScriptPubKey = byteArrayOf(0x00, 0x14) + ByteArray(20),
            changeScriptPubKey = byteArrayOf(0x00, 0x14) + ByteArray(20) { 1 },
        )

        assertFalse("a max send builds no change output", plan.hasChange)
        assertEquals("the fee is the no-change fee the selector charged", selection.feeSatoshis, plan.feeSatoshis)
        assertEquals("every sat that is not the fee is sent", 100_000L, plan.amountSatoshis + plan.feeSatoshis)
    }

    @Test
    fun `selectMax refuses when the fee would take everything`() {
        val failure = try {
            selectMax(listOf(coin("dust", 0, 50)), feeRateSatPerVb = 1, recipientScriptSize = recipient)
            null
        } catch (insufficient: InsufficientFunds) {
            insufficient
        }

        assertEquals(50L, failure?.availableSatoshis)
    }
}
