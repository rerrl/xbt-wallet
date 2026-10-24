package com.xbtwallet.app.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-transaction view's arithmetic and labelling.
 *
 * Every fixture is built by [TestTransactions], so the transactions are valid
 * serialisations whose txids are derived from their own bytes. The tests pin the
 * rules the detail screen depends on: which outputs and inputs are ours, when a
 * fee is knowable (all inputs known) versus unknowable (any input missing), how a
 * confirmation count is recomputed against a fresh tip, and how an unconfirmed
 * transaction reads.
 */
class TransactionDetailTest {

    private val ourScript = TestTransactions.p2wpkhSpk("11".repeat(20))
    private val ourSecondScript = TestTransactions.p2wpkhSpk("33".repeat(20))
    private val otherScript = TestTransactions.p2wpkhSpk("22".repeat(20))

    private val fundingTx = TestTransactions.buildWithId(
        inputs = listOf(FAKE_PARENT_TX_ID to 0),
        outputs = listOf(
            100_000L to ourScript,
            50_000L to otherScript,
        ),
    )

    /**
     * A second funding transaction that is NOT in the wallet's history, so the
     * input spending it is somebody else's coin even though the spending
     * transaction also touches ours. This is what makes the "net effect" test
     * honest: the unknown value on that input must not block the calculation,
     * because that input is not ours.
     */
    private val otherFundingTx = TestTransactions.buildWithId(
        inputs = listOf(FAKE_PARENT_TX_ID to 1),
        outputs = listOf(
            50_000L to otherScript,
        ),
    )

    private val spendTx = TestTransactions.buildWithId(
        inputs = listOf(
            fundingTx.first to 0, // our coin
            otherFundingTx.first to 0, // somebody else's coin
        ),
        outputs = listOf(
            120_000L to otherScript,
            20_000L to ourSecondScript,
        ),
    )

    /** A fake [ChainQuery] holding raw transactions by txid and a settable tip. */
    private class FakeQuery(
        private val transactions: Map<String, String>,
        var tip: Int = 800_000,
    ) : ChainQuery {
        override fun getHistory(scripthash: String): List<HistoryEntry> = emptyList()
        override fun getBalance(scripthash: String): ScripthashBalance = ScripthashBalance.ZERO
        override fun listUnspent(scripthash: String): List<ChainUtxo> = emptyList()
        override fun getTransaction(txHash: String): String =
            transactions[txHash] ?: error("unknown transaction $txHash")
        override fun getTipHeight(): Int = tip
        override fun blockHeader(height: Int): String = error("block header not stubbed")
        override fun broadcast(txHex: String): String = error("broadcast not stubbed")
    }

    private fun query(tip: Int = 800_000): FakeQuery = FakeQuery(
        mapOf(
            fundingTx.first to fundingTx.second,
            otherFundingTx.first to otherFundingTx.second,
            spendTx.first to spendTx.second,
        ),
        tip,
    )

    @Test
    fun `outputs paying our scripts are ours and others are not`() {
        val detail = loadTransactionDetail(
            txHash = spendTx.first,
            walletScripts = setOf(ourScript, ourSecondScript),
            query = query(),
            knownHeight = 799_990,
            walletTransactionIds = setOf(fundingTx.first),
        )

        assertEquals(2, detail.outputs.size)
        assertFalse("the payment output is not ours", detail.outputs[0].isOurs)
        assertTrue("the change output is ours", detail.outputs[1].isOurs)
        assertEquals(120_000L, detail.outputs[0].satoshis)
        assertEquals(20_000L, detail.outputs[1].satoshis)
    }

    @Test
    fun `an input whose parent is in the wallet history is ours`() {
        val detail = loadTransactionDetail(
            txHash = spendTx.first,
            walletScripts = setOf(ourScript),
            query = query(),
            knownHeight = 799_990,
            walletTransactionIds = setOf(fundingTx.first),
        )

        assertEquals(2, detail.inputs.size)
        assertTrue("the first input spends our funding tx", detail.inputs[0].isOurs)
        assertFalse("the second input's parent is not in the history", detail.inputs[1].isOurs)
        assertEquals("${fundingTx.first}:0", detail.inputs[0].outpoint)
        assertEquals("${otherFundingTx.first}:0", detail.inputs[1].outpoint)
    }

    @Test
    fun `an input whose parent is not in the history is not ours`() {
        val detail = loadTransactionDetail(
            txHash = spendTx.first,
            walletScripts = setOf(ourScript),
            query = query(),
            knownHeight = 799_990,
            walletTransactionIds = emptySet(),
        )

        assertFalse(detail.inputs[0].isOurs)
        assertFalse(detail.inputs[1].isOurs)
    }

    @Test
    fun `fee is null while any input value is unknown`() {
        val detail = loadTransactionDetail(
            txHash = spendTx.first,
            walletScripts = setOf(ourScript),
            query = query(),
            knownHeight = 799_990,
            knownInputValues = mapOf("${fundingTx.first}:0" to 100_000L),
        )

        assertNull("one input still has no value", detail.feeSatoshis)
        assertNull(detail.feeRateSatPerVb)
        assertTrue(detail.inputs[0].satoshis != null)
        assertNull(detail.inputs[1].satoshis)
    }

    @Test
    fun `fee is the difference between known inputs and outputs`() {
        val detail = loadTransactionDetail(
            txHash = spendTx.first,
            walletScripts = setOf(ourScript),
            query = query(),
            knownHeight = 799_990,
            knownInputValues = mapOf(
                "${fundingTx.first}:0" to 100_000L,
                "${otherFundingTx.first}:0" to 50_000L,
            ),
        )

        assertEquals(150_000L - 140_000L, detail.feeSatoshis)
        assertEquals(10_000.0 / detail.vsize, detail.feeRateSatPerVb!!, 0.0001)
    }

    @Test
    fun `withKnownInputs fills missing values and recomputes the fee`() {
        val unknown = loadTransactionDetail(
            txHash = spendTx.first,
            walletScripts = setOf(ourScript),
            query = query(),
            knownHeight = 799_990,
        )
        assertNull(unknown.feeSatoshis)

        val known = unknown.withKnownInputs(
            mapOf(
                "${fundingTx.first}:0" to KnownInput(100_000L, isOurs = true),
                "${otherFundingTx.first}:0" to KnownInput(50_000L, isOurs = false),
            ),
        )

        assertEquals(10_000L, known.feeSatoshis)
        assertTrue(known.inputs[0].isOurs)
        assertFalse(known.inputs[1].isOurs)
    }

    @Test
    fun `confirmations are recomputed against the fresh tip`() {
        val detail = loadTransactionDetail(
            txHash = spendTx.first,
            walletScripts = setOf(ourScript),
            query = query(tip = 800_010),
            knownHeight = 800_000,
        )

        assertEquals(800_000, detail.height)
        assertEquals(11, detail.confirmations)
    }

    @Test
    fun `an unconfirmed transaction has zero confirmations and no block time`() {
        val detail = loadTransactionDetail(
            txHash = spendTx.first,
            walletScripts = setOf(ourScript),
            query = query(),
            knownHeight = 0,
        )

        assertEquals(0, detail.height)
        assertEquals(0, detail.confirmations)
        assertEquals(false, detail.confirmed)
        assertNull(detail.blockTimestampEpochSeconds)
    }

    @Test
    fun `a null height means unknown rather than mempool`() {
        val detail = loadTransactionDetail(
            txHash = spendTx.first,
            walletScripts = setOf(ourScript),
            query = query(),
            knownHeight = null,
        )

        assertNull(detail.height)
        assertNull(detail.confirmations)
        assertNull(detail.confirmed)
    }

    @Test
    fun `a tip lagging behind the height still yields at least one confirmation`() {
        val detail = loadTransactionDetail(
            txHash = spendTx.first,
            walletScripts = setOf(ourScript),
            query = query(tip = 799_990),
            knownHeight = 800_000,
        )

        assertEquals(1, detail.confirmations)
    }

    @Test
    fun `net effect counts our outputs minus our inputs`() {
        val detail = loadTransactionDetail(
            txHash = spendTx.first,
            walletScripts = setOf(ourScript, ourSecondScript),
            query = query(),
            knownHeight = 799_990,
            knownInputValues = mapOf("${fundingTx.first}:0" to 100_000L),
            walletTransactionIds = setOf(fundingTx.first),
        )

        // Incoming 20 000 (our change); outgoing 100 000 (our first input).
        // The second input is not ours and its unknown value does not matter.
        assertEquals(20_000L - 100_000L, detail.netSatoshis)
    }

    @Test
    fun `net effect is null when one of our inputs has no value`() {
        val detail = loadTransactionDetail(
            txHash = spendTx.first,
            walletScripts = setOf(ourScript),
            query = query(),
            knownHeight = 799_990,
            walletTransactionIds = setOf(fundingTx.first),
        )

        assertNull(detail.netSatoshis)
    }

    @Test
    fun `a mismatched txid is rejected`() {
        try {
            loadTransactionDetail(
                txHash = spendTx.first,
                walletScripts = setOf(ourScript),
                query = FakeQuery(mapOf(spendTx.first to fundingTx.second)),
                knownHeight = 799_990,
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("server returned transaction"))
        }
    }

    @Test
    fun `resolveUnknownInputValues reads each parent once and labels its output`() {
        val detail = loadTransactionDetail(
            txHash = spendTx.first,
            walletScripts = setOf(ourScript),
            query = query(),
            knownHeight = 799_990,
            walletTransactionIds = setOf(fundingTx.first),
        )
        var progress: Pair<Int, Int>? = null

        val known = resolveUnknownInputValues(detail, setOf(ourScript), query()) { fetched, total ->
            progress = fetched to total
        }

        assertEquals(2 to 2, progress)
        assertEquals(2, known.size)
        assertEquals(100_000L, known.getValue("${fundingTx.first}:0").satoshis)
        assertTrue(known.getValue("${fundingTx.first}:0").isOurs)
        assertEquals(50_000L, known.getValue("${otherFundingTx.first}:0").satoshis)
        assertFalse(known.getValue("${otherFundingTx.first}:0").isOurs)
    }

    @Test
    fun `resolveUnknownInputValues does nothing when every value is already known`() {
        val detail = loadTransactionDetail(
            txHash = spendTx.first,
            walletScripts = setOf(ourScript),
            query = query(),
            knownHeight = 799_990,
            knownInputValues = mapOf(
                "${fundingTx.first}:0" to 100_000L,
                "${otherFundingTx.first}:0" to 50_000L,
            ),
        )
        var calls = 0
        val known = resolveUnknownInputValues(detail, setOf(ourScript), query()) { _, _ -> calls++ }

        assertTrue(known.isEmpty())
        assertEquals(0, calls)
    }

    @Test
    fun `address rendering covers the standard scripts and refuses the rest`() {
        assertEquals("bc1q...", "bc1q...") // placeholder guard; real assertions below

        val p2wpkh = "0014" + "11".repeat(20)
        assertTrue(addressFromScriptPubKeyHex(p2wpkh)!!.startsWith("bc1q"))

        val p2wsh = "0020" + "22".repeat(32)
        assertTrue(addressFromScriptPubKeyHex(p2wsh)!!.startsWith("bc1q"))

        val p2tr = "5120" + "33".repeat(32)
        assertTrue(addressFromScriptPubKeyHex(p2tr)!!.startsWith("bc1p"))

        val p2pkh = "76a914" + "44".repeat(20) + "88ac"
        assertTrue(addressFromScriptPubKeyHex(p2pkh)!!.startsWith("1"))

        val p2sh = "a914" + "55".repeat(20) + "87"
        assertTrue(addressFromScriptPubKeyHex(p2sh)!!.startsWith("3"))

        assertNull("an OP_RETURN is not an address", addressFromScriptPubKeyHex("6a04deadbeef"))
        assertNull("garbage is not an address", addressFromScriptPubKeyHex("zz"))
    }

    @Test
    fun `parsed serialisation facts are exposed`() {
        val detail = loadTransactionDetail(
            txHash = spendTx.first,
            walletScripts = setOf(ourScript),
            query = query(),
            knownHeight = 799_990,
        )

        assertEquals(2, detail.version)
        assertEquals(0L, detail.lockTime)
        assertTrue("a legacy transaction weighs four times its size", detail.weight == detail.vsize * 4)
        assertEquals(detail.weight / 4, detail.vsize)
    }

    private companion object {
        /** A txid no fixture spends; only the spending tx's inputs point at the real funding tx. */
        const val FAKE_PARENT_TX_ID = "0000000000000000000000000000000000000000000000000000000000000001"
    }
}
