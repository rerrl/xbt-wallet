package com.xbtwallet.app.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Net-effect and row tests: the arithmetic the signer screen's `+X in` /
 * `-X out` rows depend on, and the confirmation bucketing behind their badges.
 *
 * The fixtures are built with [TestTransactions], so each test can describe a
 * wallet's story directly:
 *
 *  - `fund` pays us 100 000 sats at receive address 0;
 *  - `spend` spends that coin, paying 30 000 to a stranger and 69 000 back to
 *    our own change address — a send that must read as "out 31 000" (the
 *    payment plus the fee), never as "in 69 000" for the change.
 */
class TransactionDetailsTest {

    // Two of this wallet's addresses, as scriptPubKeys.
    private val receiveScript = TestTransactions.p2wpkhSpk("aa".repeat(20))
    private val changeScript = TestTransactions.p2wpkhSpk("bb".repeat(20))
    private val strangerScript = TestTransactions.p2wpkhSpk("cc".repeat(20))
    private val walletScripts = setOf(receiveScript, changeScript)

    private val foreignOutpoint = "ff".repeat(32) to 0

    /** A funding transaction paying 100 000 sats to our receive address. */
    private val fund: Pair<String, String> = TestTransactions.buildWithId(
        inputs = listOf(foreignOutpoint),
        outputs = listOf(100_000L to receiveScript),
    )

    /** A spend of that coin: 30 000 to a stranger, 69 000 back to our change address. */
    private val spend: Pair<String, String> = TestTransactions.buildWithId(
        inputs = listOf(fund.first to 0),
        outputs = listOf(30_000L to strangerScript, 69_000L to changeScript),
    )

    // --- net effect -------------------------------------------------------

    @Test
    fun `a transaction that only pays us is entirely incoming`() {
        val net = netEffect(
            transaction = TransactionParser.parse(fund.second),
            walletScripts = walletScripts,
            walletOutputs = emptyMap(),
        )
        assertEquals(100_000L, net)
        assertEquals(TransactionDirection.IN, rowFor(net).direction)
    }

    @Test
    fun `a send with change is out by the payment plus the fee, not by the change`() {
        // Our coin was created by `fund`, so its outpoint is ours and its value
        // is known; the 69 000 change output comes back to us and cancels out,
        // leaving -(30 000 + 1 000 fee).
        val walletOutputs = mapOf("${fund.first}:0" to 100_000L)

        val net = netEffect(
            transaction = TransactionParser.parse(spend.second),
            walletScripts = walletScripts,
            walletOutputs = walletOutputs,
        )

        assertEquals(-31_000L, net)
        assertEquals(TransactionDirection.OUT, rowFor(net).direction)
    }

    @Test
    fun `a spend whose funding transaction we do not know is not guessed at`() {
        // Without the funding output in the index the input cannot be valued, so
        // only the change output counts. (In a real scan this cannot happen: the
        // coin's funding transaction is in the address's history.)
        val net = netEffect(
            transaction = TransactionParser.parse(spend.second),
            walletScripts = walletScripts,
            walletOutputs = emptyMap(),
        )
        assertEquals(69_000L, net)
    }

    @Test
    fun `a self-send nets to the fee only`() {
        val selfSend = TestTransactions.build(
            inputs = listOf(fund.first to 0),
            outputs = listOf(99_000L to changeScript),
        )
        val net = netEffect(
            transaction = TransactionParser.parse(selfSend),
            walletScripts = walletScripts,
            walletOutputs = mapOf("${fund.first}:0" to 100_000L),
        )
        assertEquals(-1_000L, net)
        assertEquals(TransactionDirection.OUT, rowFor(net).direction)
    }

    @Test
    fun `outputs to both chains are summed`() {
        val bothChains = TestTransactions.build(
            inputs = listOf(foreignOutpoint),
            outputs = listOf(40_000L to receiveScript, 2_500L to changeScript, 9_999L to strangerScript),
        )
        val net = netEffect(
            transaction = TransactionParser.parse(bothChains),
            walletScripts = walletScripts,
            walletOutputs = emptyMap(),
        )
        assertEquals(42_500L, net)
    }

    @Test
    fun `a transaction that only moves value between our own addresses nets to zero`() {
        // Balanced by construction: 100 000 in, 100 000 out to ourselves.
        val move = TestTransactions.build(
            inputs = listOf(fund.first to 0),
            outputs = listOf(50_000L to receiveScript, 50_000L to changeScript),
        )
        val net = netEffect(
            transaction = TransactionParser.parse(move),
            walletScripts = walletScripts,
            walletOutputs = mapOf("${fund.first}:0" to 100_000L),
        )
        assertEquals(0L, net)
        assertEquals(TransactionDirection.NEUTRAL, rowFor(net).direction)
    }

    // --- confirmations ----------------------------------------------------

    @Test
    fun `confirmation buckets have exactly the card's boundaries`() {
        assertEquals(ConfirmationBucket.UNCONFIRMED, confirmationBucket(0))
        assertEquals(ConfirmationBucket.UNCONFIRMED, confirmationBucket(-1))

        assertEquals(ConfirmationBucket.SHALLOW, confirmationBucket(1))
        assertEquals(ConfirmationBucket.SHALLOW, confirmationBucket(5))

        assertEquals(ConfirmationBucket.SETTLED, confirmationBucket(6))
        assertEquals(ConfirmationBucket.SETTLED, confirmationBucket(100))
    }

    @Test
    fun `confirmation labels read the way the row shows them`() {
        assertEquals("Unconfirmed", confirmationLabel(0))
        assertEquals("1 conf", confirmationLabel(1))
        assertEquals("2 confs", confirmationLabel(2))
        assertEquals("5 confs", confirmationLabel(5))
        assertEquals("6 confs", confirmationLabel(6))
        assertEquals("99 confs", confirmationLabel(99))
        assertEquals("100+ confs", confirmationLabel(100))
        assertEquals("100+ confs", confirmationLabel(101))
        assertEquals("100+ confs", confirmationLabel(1_000))
    }

    @Test
    fun `the rendered cap sits at a hundred confirmations`() {
        assertEquals(100, MAX_RENDERED_CONFIRMATIONS)
        assertEquals("100+ confs", confirmationLabel(MAX_RENDERED_CONFIRMATIONS))
        assertEquals("99 confs", confirmationLabel(MAX_RENDERED_CONFIRMATIONS - 1))
    }

    @Test
    fun `confirmations are counted back from the tip`() {
        assertEquals(0, confirmationsFor(height = 0, tipHeight = 800_000))
        assertEquals(0, confirmationsFor(height = -1, tipHeight = 800_000))
        assertEquals(1, confirmationsFor(height = 800_000, tipHeight = 800_000))
        assertEquals(6, confirmationsFor(height = 799_995, tipHeight = 800_000))
        // A lagging tip answer must not turn a mined transaction into 0 confs.
        assertEquals(1, confirmationsFor(height = 800_010, tipHeight = 800_000))
    }

    // --- row building -----------------------------------------------------

    @Test
    fun `rows keep the scan's order and carry net amounts and confirmations`() {
        val query = FakeTxQuery(
            transactions = mapOf(fund.first to fund.second, spend.first to spend.second),
            tipHeight = 800_010,
        )
        val rows = buildTransactionRows(
            transactions = listOf(
                ScannedTransaction(txHash = spend.first, height = 800_010),
                ScannedTransaction(txHash = fund.first, height = 800_005),
            ),
            addresses = listOf(
                address(AddressChain.RECEIVE, 0, receiveScript),
                address(AddressChain.CHANGE, 0, changeScript),
            ),
            query = query,
        )

        assertEquals(listOf(spend.first, fund.first), rows.map { it.txHash })
        assertEquals(-31_000L, rows[0].netSatoshis)
        assertEquals(1, rows[0].confirmations)
        assertEquals(100_000L, rows[1].netSatoshis)
        assertEquals(6, rows[1].confirmations)
        assertEquals("6 confs", confirmationLabel(rows[1].confirmations))
        assertEquals(TransactionDirection.IN, rows[1].direction)
    }

    @Test
    fun `an unconfirmed transaction has no confirmations`() {
        val query = FakeTxQuery(transactions = mapOf(fund.first to fund.second), tipHeight = 800_000)
        val rows = buildTransactionRows(
            transactions = listOf(ScannedTransaction(txHash = fund.first, height = 0)),
            addresses = listOf(address(AddressChain.RECEIVE, 0, receiveScript)),
            query = query,
        )

        assertEquals(0, rows.single().confirmations)
        assertEquals("Unconfirmed", confirmationLabel(rows.single().confirmations))
    }

    @Test
    fun `a spend outside the display window still has its funding transaction fetched`() {
        // 55 transactions: the oldest 5 fall outside the 50-row cap. The newest
        // row spends a coin created by the very first one, so that funding
        // transaction must still be fetched — otherwise the spend would show as
        // an incoming change payment instead of an outgoing send.
        val funding = TestTransactions.buildWithId(
            inputs = listOf(foreignOutpoint),
            outputs = listOf(100_000L to receiveScript),
        )
        val filler = (0 until 54).map { index ->
            TestTransactions.buildWithId(
                inputs = listOf("11".repeat(31) + "%02x".format(index) to 0),
                outputs = listOf(1_000L + index to strangerScript),
            )
        }
        val spendAtTop = TestTransactions.buildWithId(
            inputs = listOf(funding.first to 0),
            outputs = listOf(30_000L to strangerScript, 69_000L to changeScript),
        )

        val displayOrder = listOf(spendAtTop) + filler.reversed() + listOf(funding)
        val transactions = displayOrder.mapIndexed { index, (txid, _) ->
            ScannedTransaction(txHash = txid, height = 800_000 - index)
        }
        val query = FakeTxQuery(
            transactions = (displayOrder + funding).associate { it },
            tipHeight = 800_010,
        )

        val rows = buildTransactionRows(
            transactions = transactions,
            addresses = listOf(
                address(AddressChain.RECEIVE, 0, receiveScript),
                address(AddressChain.CHANGE, 0, changeScript),
            ),
            query = query,
        )

        assertEquals(MAX_DETAILED_TRANSACTIONS, rows.size)
        assertEquals(-31_000L, rows.first().netSatoshis)
        assertTrue(
            "the funding transaction outside the window must have been fetched",
            query.fetched.contains(funding.first),
        )
    }

    @Test
    fun `an address set without scripts produces no rows rather than invented ones`() {
        val query = FakeTxQuery(transactions = mapOf(fund.first to fund.second), tipHeight = 800_000)
        val rows = buildTransactionRows(
            transactions = listOf(ScannedTransaction(txHash = fund.first, height = 800_000)),
            addresses = listOf(address(AddressChain.RECEIVE, 0, scriptPubKeyHex = "")),
            query = query,
        )

        assertTrue(rows.isEmpty())
        assertTrue("nothing should have been fetched", query.fetched.isEmpty())
    }

    // --- cached-row reuse (the incremental rescan's saving) ---------------

    @Test
    fun `a cached confirmed row is reused without fetching its transaction`() {
        // The fixture map is empty: any getTransaction would throw. A test that
        // passes therefore proves none was issued.
        val query = FakeTxQuery(transactions = emptyMap(), tipHeight = 800_010)
        val rows = buildTransactionRows(
            transactions = listOf(ScannedTransaction(txHash = fund.first, height = 800_005)),
            addresses = listOf(address(AddressChain.RECEIVE, 0, receiveScript)),
            query = query,
            cachedRows = mapOf(
                // Deliberately stale confirmations (1) — the fresh tip must win.
                fund.first to TransactionRow(txHash = fund.first, height = 800_005, confirmations = 1, netSatoshis = 100_000),
            ),
        )

        assertEquals(100_000L, rows.single().netSatoshis)
        assertEquals("confirmations are recomputed from the fresh tip", 6, rows.single().confirmations)
        assertTrue("no blockchain.transaction.get may be issued for a reused row", query.fetched.isEmpty())
    }

    @Test
    fun `an unconfirmed cached row is re-fetched rather than trusted`() {
        val query = FakeTxQuery(transactions = mapOf(fund.first to fund.second), tipHeight = 800_000)
        val rows = buildTransactionRows(
            transactions = listOf(ScannedTransaction(txHash = fund.first, height = 0)),
            addresses = listOf(address(AddressChain.RECEIVE, 0, receiveScript)),
            query = query,
            // A cached mempool row's amount is exactly what must not be trusted.
            cachedRows = mapOf(fund.first to TransactionRow(fund.first, height = 0, confirmations = 0, netSatoshis = 999)),
        )

        assertEquals("the fresh computation must win", 100_000L, rows.single().netSatoshis)
        assertEquals(0, rows.single().confirmations)
        assertEquals(listOf(fund.first), query.fetched)
    }

    @Test
    fun `a cached row whose height moved is re-fetched`() {
        val query = FakeTxQuery(transactions = mapOf(fund.first to fund.second), tipHeight = 800_010)
        val rows = buildTransactionRows(
            transactions = listOf(ScannedTransaction(txHash = fund.first, height = 800_010)),
            addresses = listOf(address(AddressChain.RECEIVE, 0, receiveScript)),
            query = query,
            cachedRows = mapOf(fund.first to TransactionRow(fund.first, height = 800_000, confirmations = 11, netSatoshis = 999)),
        )

        assertEquals(100_000L, rows.single().netSatoshis)
        assertEquals(listOf(fund.first), query.fetched)
    }

    @Test
    fun `a brand-new transaction fetches only itself and its known funding predecessor`() {
        // Only the spend is displayed (limit = 1); the funding transaction is in
        // the aggregated history, so it is fetched as the spend's predecessor —
        // and nothing else is.
        val query = FakeTxQuery(
            transactions = mapOf(fund.first to fund.second, spend.first to spend.second),
            tipHeight = 800_010,
        )
        val rows = buildTransactionRows(
            transactions = listOf(
                ScannedTransaction(txHash = spend.first, height = 800_010),
                ScannedTransaction(txHash = fund.first, height = 800_005),
            ),
            addresses = listOf(
                address(AddressChain.RECEIVE, 0, receiveScript),
                address(AddressChain.CHANGE, 0, changeScript),
            ),
            query = query,
            limit = 1,
        )

        assertEquals(1, rows.size)
        assertEquals(-31_000L, rows.single().netSatoshis)
        assertEquals(setOf(spend.first, fund.first), query.fetched.toSet())
        assertEquals("exactly the spend and its predecessor", 2, query.fetched.size)
    }

    @Test
    fun `a new spend of a reused funding transaction still nets out correctly`() {
        // The funding row is cached and unchanged, so it is not fetched for its
        // own sake — but the new spend's inputs point at it, so it is fetched as
        // a predecessor and its value is what makes the spend net out.
        val query = FakeTxQuery(
            transactions = mapOf(fund.first to fund.second, spend.first to spend.second),
            tipHeight = 800_010,
        )
        val rows = buildTransactionRows(
            transactions = listOf(
                ScannedTransaction(txHash = spend.first, height = 800_010),
                ScannedTransaction(txHash = fund.first, height = 800_005),
            ),
            addresses = listOf(
                address(AddressChain.RECEIVE, 0, receiveScript),
                address(AddressChain.CHANGE, 0, changeScript),
            ),
            query = query,
            cachedRows = mapOf(
                fund.first to TransactionRow(fund.first, height = 800_005, confirmations = 6, netSatoshis = 100_000),
                // Height moved, so this one is not reusable.
                spend.first to TransactionRow(spend.first, height = 800_009, confirmations = 1, netSatoshis = 5),
            ),
        )

        assertEquals(-31_000L, rows[0].netSatoshis)
        assertEquals("the unchanged funding row keeps its stored amount", 100_000L, rows[1].netSatoshis)
        assertTrue(query.fetched.contains(spend.first))
        assertTrue("the spend's funding predecessor must be fetched", query.fetched.contains(fund.first))
    }

    // --- block timestamps -------------------------------------------------

    @Test
    fun `mined rows are dated once per distinct block, and an unmined row is not dated`() {
        val mempool = TestTransactions.buildWithId(
            inputs = listOf(foreignOutpoint),
            outputs = listOf(5_000L to receiveScript),
        )
        val query = FakeTxQuery(
            transactions = mapOf(fund.first to fund.second, spend.first to spend.second, mempool.first to mempool.second),
            tipHeight = 800_010,
            // One header per mined height; the same header value proves nothing
            // about the height, only that the timestamp is read off it.
            blockHeaders = mapOf(800_010 to genesisHeader, 800_005 to genesisHeader),
        )
        val rows = buildTransactionRows(
            transactions = listOf(
                ScannedTransaction(txHash = spend.first, height = 800_010),
                ScannedTransaction(txHash = fund.first, height = 800_005),
                ScannedTransaction(txHash = mempool.first, height = 0),
            ),
            addresses = listOf(
                address(AddressChain.RECEIVE, 0, receiveScript),
                address(AddressChain.CHANGE, 0, changeScript),
            ),
            query = query,
        )

        // Two distinct mined heights, one call each — and none for the mempool.
        assertEquals(setOf(800_005, 800_010), query.headerCalls.toSet())
        assertEquals(1231006505L, rows.first { it.txHash == spend.first }.blockTimestampEpochSeconds)
        assertEquals(1231006505L, rows.first { it.txHash == fund.first }.blockTimestampEpochSeconds)
        assertNull(rows.first { it.txHash == mempool.first }.blockTimestampEpochSeconds)
    }

    @Test
    fun `a mined row whose header cannot be read is left undated, not failed`() {
        val query = FakeTxQuery(transactions = mapOf(fund.first to fund.second), tipHeight = 800_000)
        val rows = buildTransactionRows(
            transactions = listOf(ScannedTransaction(txHash = fund.first, height = 800_000)),
            addresses = listOf(address(AddressChain.RECEIVE, 0, receiveScript)),
            query = query,
        )

        assertEquals(100_000L, rows.single().netSatoshis)
        assertNull("no header fixture: the date stays unknown", rows.single().blockTimestampEpochSeconds)
    }

    @Test
    fun `a header that cannot be read is reported to the caller, and its row stays undated`() {
        // The failure must never be silent: an undated row with no explanation
        // anywhere is exactly the bug this callback exists to expose. The fake
        // answers a height with no fixture with an exception, and the callback
        // must receive that height and that exception once.
        val failures = mutableListOf<Pair<Int, Exception>>()
        val query = FakeTxQuery(transactions = mapOf(fund.first to fund.second), tipHeight = 800_000)

        val rows = buildTransactionRows(
            transactions = listOf(ScannedTransaction(txHash = fund.first, height = 800_000)),
            addresses = listOf(address(AddressChain.RECEIVE, 0, receiveScript)),
            query = query,
            onTimestampFailure = { height, error -> failures += height to error },
        )

        assertNull(rows.single().blockTimestampEpochSeconds)
        assertEquals(listOf(800_000), failures.map { it.first })
        assertTrue(
            "the exception itself must travel with the height, got ${failures.single().second::class.java.simpleName}",
            failures.single().second is IllegalStateException,
        )
    }

    @Test
    fun `an unconfirmed row is not reported as a dating failure`() {
        // A mempool transaction has no block to read, which is normal — only a
        // confirmed row whose header failed is worth reporting.
        val failures = mutableListOf<Int>()
        val query = FakeTxQuery(transactions = mapOf(fund.first to fund.second), tipHeight = 800_000)

        val rows = buildTransactionRows(
            transactions = listOf(ScannedTransaction(txHash = fund.first, height = 0)),
            addresses = listOf(address(AddressChain.RECEIVE, 0, receiveScript)),
            query = query,
            onTimestampFailure = { height, _ -> failures += height },
        )

        assertNull(rows.single().blockTimestampEpochSeconds)
        assertTrue("no header call, no failure", failures.isEmpty())
        assertTrue("nothing was asked for either", query.headerCalls.isEmpty())
    }

    @Test
    fun `a header longer than eighty bytes dates a row without reporting a failure`() {
        // The real server's shape: 164 bytes. The date comes off the 80-byte
        // prefix, and the longer reply is not a failure.
        val failures = mutableListOf<Int>()
        val query = FakeTxQuery(
            transactions = mapOf(fund.first to fund.second),
            tipHeight = 800_000,
            blockHeaders = mapOf(800_000 to genesisHeader + "00".repeat(84)),
        )

        val rows = buildTransactionRows(
            transactions = listOf(ScannedTransaction(txHash = fund.first, height = 800_000)),
            addresses = listOf(address(AddressChain.RECEIVE, 0, receiveScript)),
            query = query,
            onTimestampFailure = { height, _ -> failures += height },
        )

        assertEquals(1_231_006_505L, rows.single().blockTimestampEpochSeconds)
        assertTrue("an extended header is not a failure: $failures", failures.isEmpty())
    }

    // --- the undated-rows signal the UI renders one line for ---------------

    @Test
    fun `undated confirmed rows are detected as a whole-list condition`() {
        val dated = rowFor(netSatoshis = 1).copy(blockTimestampEpochSeconds = 1_700_000_000L)
        val undatedConfirmed = rowFor(netSatoshis = 1)
        val undatedUnconfirmed = rowFor(netSatoshis = 1).copy(height = 0, confirmations = 0)

        assertTrue(listOf(undatedConfirmed).hasUndatedConfirmedRows())
        assertTrue(listOf(dated, undatedConfirmed).hasUndatedConfirmedRows())
        assertTrue(
            "an unconfirmed row alone is not a dating failure",
            !listOf(undatedUnconfirmed).hasUndatedConfirmedRows(),
        )
        assertTrue("a fully dated list says nothing", !listOf(dated).hasUndatedConfirmedRows())
        assertTrue("an empty list says nothing", !emptyList<TransactionRow>().hasUndatedConfirmedRows())
    }

    @Test
    fun `a reused cached row keeps the date it already carried`() {
        val query = FakeTxQuery(transactions = emptyMap(), tipHeight = 800_010)
        val rows = buildTransactionRows(
            transactions = listOf(ScannedTransaction(txHash = fund.first, height = 800_005)),
            addresses = listOf(address(AddressChain.RECEIVE, 0, receiveScript)),
            query = query,
            cachedRows = mapOf(
                fund.first to TransactionRow(
                    txHash = fund.first,
                    height = 800_005,
                    confirmations = 1,
                    netSatoshis = 100_000,
                    blockTimestampEpochSeconds = 1_700_000_000L,
                ),
            ),
        )

        assertEquals(1_700_000_000L, rows.single().blockTimestampEpochSeconds)
        assertTrue("no transaction fetch for a reused row", query.fetched.isEmpty())
    }

    // --- helpers ----------------------------------------------------------

    /** The mainnet genesis header, whose timestamp is 1231006505. */
    private val genesisHeader =
        "0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a29ab5f49ffff001d1dac2b7c"

    private fun rowFor(netSatoshis: Long) = TransactionRow(
        txHash = "00".repeat(32),
        height = 800_000,
        confirmations = 1,
        netSatoshis = netSatoshis,
    )

    private fun address(chain: AddressChain, index: Int, scriptPubKeyHex: String) = ScannedAddress(
        chain = chain,
        index = index,
        address = "bc1q-test-$index",
        scripthash = "sh-test-$index",
        used = true,
        scriptPubKeyHex = scriptPubKeyHex,
    )

    /** A [ChainQuery] that serves transaction hex from a fixture map. */
    private class FakeTxQuery(
        private val transactions: Map<String, String>,
        private val tipHeight: Int,
        /**
         * Raw 80-byte headers by height. A height not listed answers as an error
         * — the row-dating path tolerates it and leaves the date null, so a test
         * scripts only the headers it asserts on.
         */
        private val blockHeaders: Map<Int, String> = emptyMap(),
    ) : ChainQuery {
        val fetched = mutableListOf<String>()
        val headerCalls = mutableListOf<Int>()

        override fun getHistory(scripthash: String): List<HistoryEntry> = emptyList()

        override fun getBalance(scripthash: String): ScripthashBalance = ScripthashBalance.ZERO

        override fun listUnspent(scripthash: String): List<ChainUtxo> = emptyList()

        override fun getTransaction(txHash: String): String {
            fetched += txHash
            return transactions[txHash] ?: throw IllegalStateException("no fixture for $txHash")
        }

        override fun getTipHeight(): Int = tipHeight

        override fun blockHeader(height: Int): String {
            headerCalls += height
            return blockHeaders[height] ?: throw IllegalStateException("no header fixture for height $height")
        }

        override fun broadcast(txHex: String): String =
            throw UnsupportedOperationException("this fake does not broadcast")
    }
}
