package com.xbtwallet.app.chain

import com.xbtwallet.app.crypto.ExtendedPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AccountScanner tests — the gap-limit walk, the balance, and the UTXO mapping.
 *
 * The scanner is driven by an in-memory [ChainQuery], so these tests describe an
 * account ("addresses 0..24 of the receive chain were used, address 0 holds
 * 1 000 sats") and assert what the scan makes of it. No socket, no coroutine,
 * no server: the *protocol* is covered separately by `ChainRpcSessionTest`, and
 * the *hashing* by `ScripthashTest`.
 *
 * The fake answers by scripthash, because that is the protocol's own identity;
 * it maps a scripthash back to a chain and index through the scanner under test.
 * That keeps the fixture readable — "used = a set of indices" — at the cost of
 * the fake and the scanner sharing a derivation, which is why the derivation
 * itself is pinned against the BIP-84 vector in its own test below.
 */
class AccountScannerTest {

    /** The BIP-84 account key `ExtendedPublicKeyTest` pins the addresses of. */
    private val accountZpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    private fun scanner(gapLimit: Int = AccountScanner.DEFAULT_GAP_LIMIT) =
        AccountScanner(ExtendedPublicKey.parse(accountZpub), gapLimit = gapLimit)

    /**
     * A [ChainQuery] with a scripted account.
     *
     * Every index up to [LOOKUP_RANGE] is derivable, which is more than the
     * largest scan under test visits. Calls are recorded so a test can assert
     * *what was asked*, not only what came back.
     */
    private class FakeChainQuery(
        private val scanner: AccountScanner,
        private val usedReceive: Set<Int> = emptySet(),
        private val usedChange: Set<Int> = emptySet(),
        private val balances: Map<Pair<AddressChain, Int>, ScripthashBalance> = emptyMap(),
        private val utxos: Map<Pair<AddressChain, Int>, List<ChainUtxo>> = emptyMap(),
    ) : ChainQuery {

        val historyCalls = mutableListOf<Pair<AddressChain, Int>>()
        val balanceCalls = mutableListOf<Pair<AddressChain, Int>>()
        val unspentCalls = mutableListOf<Pair<AddressChain, Int>>()

        private val lookup: Map<String, Pair<AddressChain, Int>> = buildMap {
            for (chain in AddressChain.entries) {
                for (index in 0..LOOKUP_RANGE) {
                    put(scanner.derive(chain, index).scripthash, chain to index)
                }
            }
        }

        private fun key(scripthash: String): Pair<AddressChain, Int> =
            lookup.getValue(scripthash)

        private fun isUsed(address: Pair<AddressChain, Int>): Boolean =
            if (address.first == AddressChain.RECEIVE) address.second in usedReceive else address.second in usedChange

        override fun getHistory(scripthash: String): List<HistoryEntry> {
            val address = key(scripthash)
            historyCalls += address
            return if (isUsed(address)) listOf(HistoryEntry(txHash = TXID, height = 800_000)) else emptyList()
        }

        override fun getBalance(scripthash: String): ScripthashBalance {
            val address = key(scripthash)
            balanceCalls += address
            return balances[address] ?: ScripthashBalance.ZERO
        }

        override fun listUnspent(scripthash: String): List<ChainUtxo> {
            val address = key(scripthash)
            unspentCalls += address
            return utxos[address] ?: emptyList()
        }
    }

    @Test
    fun `an empty account scans one batch per chain and stops`() {
        val scanner = scanner()
        val query = FakeChainQuery(scanner)

        val snapshot = scanner.scan(query)

        // 20 receive addresses, then 20 change addresses — the first batch of
        // each chain is entirely unused, so the walk ends there.
        assertEquals(20, snapshot.receive.size)
        assertEquals(20, snapshot.change.size)
        assertEquals(
            (0 until 20).map { AddressChain.RECEIVE to it },
            query.historyCalls.filter { it.first == AddressChain.RECEIVE },
        )
        assertEquals(
            (0 until 20).map { AddressChain.CHANGE to it },
            query.historyCalls.filter { it.first == AddressChain.CHANGE },
        )
        // Nothing is used, so nothing is asked about beyond history.
        assertTrue(query.balanceCalls.isEmpty())
        assertTrue(query.unspentCalls.isEmpty())
        assertEquals(0L, snapshot.satoshis)
        assertEquals(emptyList<ScannedUtxo>(), snapshot.utxos)
        assertTrue(snapshot.usedAddresses.isEmpty())
    }

    @Test
    fun `the scan stops after the first fully unused batch`() {
        val scanner = scanner()
        // 20 used receive addresses: batch 1 (0..19) is not unused, so batch 2
        // (20..39) is queried — and *is* unused, which ends the walk.
        val query = FakeChainQuery(scanner, usedReceive = (0 until 20).toSet())

        val snapshot = scanner.scan(query)

        assertEquals(40, snapshot.receive.size)
        assertEquals(20, snapshot.receive.count { it.used })
        assertFalse("batch 2 must be the unused one", snapshot.receive[20].used)
        assertEquals(40, query.historyCalls.count { it.first == AddressChain.RECEIVE })
        // And the change chain still gets its own, separate walk.
        assertEquals(20, snapshot.change.size)
    }

    @Test
    fun `a partly used batch does not end the scan`() {
        val scanner = scanner()
        // 25 used receive addresses (0..24). Batch 2 (20..39) *does* contain used
        // addresses, so it is not "a full batch of 20 consecutive unused": the
        // walk continues into batch 3 (40..59), which is unused and ends it.
        // Three batches, not two — a scan that stopped at batch 2 would hide
        // addresses 20..24, which is exactly the bug the rule prevents.
        val query = FakeChainQuery(scanner, usedReceive = (0 until 25).toSet())

        val snapshot = scanner.scan(query)

        assertEquals(60, snapshot.receive.size)
        assertEquals(25, snapshot.receive.count { it.used })
        assertFalse("batch 3 is the unused one", snapshot.receive[40].used)
        assertEquals(60, query.historyCalls.count { it.first == AddressChain.RECEIVE })
    }

    @Test
    fun `balance is the sum of get_balance over the used addresses only`() {
        val scanner = scanner()
        val query = FakeChainQuery(
            scanner,
            usedReceive = setOf(0, 1, 4),
            usedChange = setOf(0),
            balances = mapOf(
                (AddressChain.RECEIVE to 0) to ScripthashBalance(confirmedSatoshis = 1_000, unconfirmedSatoshis = 0),
                // Used, spent its confirmed coins, has an unconfirmed payment.
                (AddressChain.RECEIVE to 1) to ScripthashBalance(confirmedSatoshis = 0, unconfirmedSatoshis = 500),
                // Used long ago, empty now: contributes zero but is still "used".
                (AddressChain.RECEIVE to 4) to ScripthashBalance.ZERO,
                (AddressChain.CHANGE to 0) to ScripthashBalance(confirmedSatoshis = 250_000, unconfirmedSatoshis = 0),
            ),
        )

        val snapshot = scanner.scan(query)

        assertEquals(ScripthashBalance(confirmedSatoshis = 251_000, unconfirmedSatoshis = 500), snapshot.balance)
        assertEquals(251_500L, snapshot.satoshis)
        assertEquals(4, query.balanceCalls.size)
        assertFalse("unused addresses must not be asked about", query.balanceCalls.contains(AddressChain.RECEIVE to 2))
    }

    @Test
    fun `UTXOs carry the address they were found at`() {
        val scanner = scanner()
        val query = FakeChainQuery(
            scanner,
            usedReceive = setOf(0, 3),
            usedChange = setOf(1),
            utxos = mapOf(
                (AddressChain.RECEIVE to 0) to listOf(ChainUtxo(TX_A, txPos = 0, height = 800_000, satoshis = 12_345)),
                (AddressChain.RECEIVE to 3) to listOf(ChainUtxo(TX_B, txPos = 2, height = 0, satoshis = 67_000)),
                (AddressChain.CHANGE to 1) to listOf(ChainUtxo(TX_C, txPos = 1, height = 799_999, satoshis = 42)),
            ),
        )

        val snapshot = scanner.scan(query)

        assertEquals(3, snapshot.utxos.size)

        assertEquals("$TX_A:0", snapshot.utxos[0].utxo.outpoint)
        assertEquals(AddressChain.RECEIVE, snapshot.utxos[0].chain)
        assertEquals(0, snapshot.utxos[0].addressIndex)
        assertEquals(12_345L, snapshot.utxos[0].utxo.satoshis)
        assertTrue(snapshot.utxos[0].utxo.confirmed)

        // Height 0 is the mempool: an unconfirmed output, listed and labelled.
        assertEquals(0, snapshot.utxos[1].utxo.height)
        assertFalse(snapshot.utxos[1].utxo.confirmed)

        // Change-chain output, at its own index.
        assertEquals(AddressChain.CHANGE, snapshot.utxos[2].chain)
        assertEquals(1, snapshot.utxos[2].addressIndex)
    }

    @Test
    fun `derivation matches the BIP-84 vector, addresses and scripthashes alike`() {
        val scanner = scanner()

        val firstReceive = scanner.derive(AddressChain.RECEIVE, 0)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", firstReceive.address)
        assertEquals("6e4f16236139f15046b38f399a683fb2aa8edf5fd128b3e5db017fb0ac74078a", firstReceive.scripthash)

        // The change chain is index 1 of the same account, so 1/0 is its start.
        assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el", scanner.derive(AddressChain.CHANGE, 0).address)
    }

    @Test
    fun `the batch size is the gap limit`() {
        // A wallet configured with a smaller gap limit must walk smaller batches
        // and stop sooner — the rule is the same, the number is a parameter.
        val scanner = scanner(gapLimit = 5)
        val query = FakeChainQuery(scanner)

        val snapshot = scanner.scan(query)

        assertEquals(5, snapshot.receive.size)
        assertEquals(5, snapshot.change.size)
        assertEquals((0 until 5).map { it }, snapshot.receive.map { it.index })
    }

    @Test
    fun `progress is reported once per address visited`() {
        val scanner = scanner()
        val query = FakeChainQuery(scanner)
        val counts = mutableListOf<Int>()

        scanner.scan(query, onScanned = { counts += it })

        assertEquals((1..40).toList(), counts)
    }

    private companion object {
        /** How far the fake can map a scripthash back to an index. */
        const val LOOKUP_RANGE = 200

        const val TXID = "00000000000000000000000000000000000000000000000000000000000000aa"
        const val TX_A = "00000000000000000000000000000000000000000000000000000000000000a1"
        const val TX_B = "00000000000000000000000000000000000000000000000000000000000000b2"
        const val TX_C = "00000000000000000000000000000000000000000000000000000000000000c3"
    }
}
