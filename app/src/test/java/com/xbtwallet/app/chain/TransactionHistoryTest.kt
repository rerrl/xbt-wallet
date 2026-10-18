package com.xbtwallet.app.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transaction-history aggregation tests — the pure rules behind the signer
 * screen's Transactions section.
 *
 * The scan already asks every visited address for its `get_history`; those
 * answers are what these tests feed in. The rules under test are exactly the
 * ones the card names: *aggregate across all scanned addresses and both chains*,
 * *dedupe by txid*, *sort unconfirmed-first then by height descending*, and
 * *an empty scan has no transactions*. No socket, no scanner, no UI: the input
 * is a hand-built list of [ScannedAddress] values.
 */
class TransactionHistoryTest {

    private fun address(
        chain: AddressChain,
        index: Int,
        vararg history: HistoryEntry,
    ) = ScannedAddress(
        chain = chain,
        index = index,
        address = "bc1q-${chain.label}-$index",
        scripthash = "sh-${chain.label}-$index",
        used = history.isNotEmpty(),
        history = history.toList(),
    )

    @Test
    fun `an empty scan has no transactions`() {
        // No address carried history: nothing to show.
        val nothing = aggregateTransactions(emptyList())
        assertTrue(nothing.isEmpty())

        // And a scan that visited addresses but found no history is the same.
        val visitedButEmpty = aggregateTransactions(
            listOf(
                address(AddressChain.RECEIVE, 0),
                address(AddressChain.CHANGE, 0),
            ),
        )
        assertTrue(visitedButEmpty.isEmpty())
    }

    @Test
    fun `a txid seen at several addresses is one transaction`() {
        // The same funding transaction touches two receive addresses and, after
        // a spend, a change address too. Dedupe by txid keeps a single row.
        val transactions = aggregateTransactions(
            listOf(
                address(AddressChain.RECEIVE, 0, HistoryEntry(TX_A, 800_000)),
                address(AddressChain.RECEIVE, 1, HistoryEntry(TX_A, 800_000)),
                address(AddressChain.CHANGE, 0, HistoryEntry(TX_A, 800_000)),
            ),
        )

        assertEquals(1, transactions.size)
        assertEquals(TX_A, transactions.single().txHash)
    }

    @Test
    fun `both chains contribute to the same history`() {
        val transactions = aggregateTransactions(
            listOf(
                address(AddressChain.RECEIVE, 0, HistoryEntry(TX_A, 800_000)),
                address(AddressChain.CHANGE, 0, HistoryEntry(TX_B, 799_000)),
            ),
        )

        assertEquals(listOf(TX_A, TX_B), transactions.map { it.txHash })
    }

    @Test
    fun `unconfirmed transactions sort first`() {
        val transactions = aggregateTransactions(
            listOf(
                address(
                    AddressChain.RECEIVE,
                    0,
                    HistoryEntry(TX_CONFIRMED_LOW, 799_000),
                    HistoryEntry(TX_MEMPOOL, 0),
                    HistoryEntry(TX_CONFIRMED_HIGH, 800_000),
                ),
            ),
        )

        assertEquals(listOf(TX_MEMPOOL, TX_CONFIRMED_HIGH, TX_CONFIRMED_LOW), transactions.map { it.txHash })
        assertFalse("mempool height 0 is the unconfirmed row", transactions[0].confirmed)
        assertTrue(transactions[1].confirmed)
    }

    @Test
    fun `confirmed transactions sort by height descending`() {
        val transactions = aggregateTransactions(
            listOf(
                address(
                    AddressChain.RECEIVE,
                    0,
                    HistoryEntry(TX_CONFIRMED_LOW, 799_000),
                    HistoryEntry(TX_CONFIRMED_HIGH, 800_000),
                    HistoryEntry(TX_MIDDLE, 799_500),
                ),
            ),
        )

        assertEquals(listOf(TX_CONFIRMED_HIGH, TX_MIDDLE, TX_CONFIRMED_LOW), transactions.map { it.txHash })
    }

    @Test
    fun `a txid confirmed at one address and unconfirmed at another stays confirmed`() {
        // The protocol can report the same txid with different heights per
        // scripthash; the highest (i.e. the mined) height is the truth.
        val transactions = aggregateTransactions(
            listOf(
                address(AddressChain.RECEIVE, 0, HistoryEntry(TX_A, 0)),
                address(AddressChain.CHANGE, 0, HistoryEntry(TX_A, 800_000)),
            ),
        )

        assertEquals(1, transactions.size)
        assertEquals(800_000, transactions.single().height)
        assertTrue(transactions.single().confirmed)
    }

    private companion object {
        const val TX_A = "00000000000000000000000000000000000000000000000000000000000000a1"
        const val TX_B = "00000000000000000000000000000000000000000000000000000000000000b2"
        const val TX_MEMPOOL = "00000000000000000000000000000000000000000000000000000000000000d0"
        const val TX_MIDDLE = "00000000000000000000000000000000000000000000000000000000000000c5"
        const val TX_CONFIRMED_LOW = "00000000000000000000000000000000000000000000000000000000000000e1"
        const val TX_CONFIRMED_HIGH = "00000000000000000000000000000000000000000000000000000000000000f2"
    }
}
