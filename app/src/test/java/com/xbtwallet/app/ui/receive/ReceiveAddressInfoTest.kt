package com.xbtwallet.app.ui.receive

import com.xbtwallet.app.chain.AccountSnapshot
import com.xbtwallet.app.chain.AddressChain
import com.xbtwallet.app.chain.ChainUtxo
import com.xbtwallet.app.chain.HistoryEntry
import com.xbtwallet.app.chain.ScannedAddress
import com.xbtwallet.app.chain.ScannedUtxo
import com.xbtwallet.app.chain.ScripthashBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receive screen's warning input: what the scan knows about exactly one
 * address.
 *
 * The function is pure — a snapshot plus an address string — so it is pinned on
 * the JVM with no Android and no screen. The cases are the ones the screen
 * branches on: a used address with its own UTXOs, an unused one, a change-chain
 * address, an address the scan never visited, and no snapshot at all.
 */
class ReceiveAddressInfoTest {

    private fun address(
        chain: AddressChain,
        index: Int,
        used: Boolean,
        history: List<HistoryEntry> = emptyList(),
    ) = ScannedAddress(
        chain = chain,
        index = index,
        address = "bc1q-${chain.name.lowercase()}-$index",
        scripthash = "sh-${chain.name}-$index",
        used = used,
        history = history,
    )

    private fun utxo(chain: AddressChain, index: Int, txid: String, sats: Long, height: Int = 800_000) =
        ScannedUtxo(
            utxo = ChainUtxo(txHash = txid, txPos = 0, height = height, satoshis = sats),
            chain = chain,
            addressIndex = index,
        )

    private val snapshot = AccountSnapshot(
        receive = listOf(
            address(AddressChain.RECEIVE, 0, used = true, history = listOf(HistoryEntry("aa", 800_000))),
            address(AddressChain.RECEIVE, 1, used = false),
        ),
        change = listOf(address(AddressChain.CHANGE, 0, used = true, history = listOf(HistoryEntry("bb", 799_000)))),
        utxos = listOf(
            utxo(AddressChain.RECEIVE, 0, "aa", 12_345),
            utxo(AddressChain.RECEIVE, 0, "cc", 67_000, height = 0),
            utxo(AddressChain.CHANGE, 0, "bb", 42),
        ),
        balance = ScripthashBalance(confirmedSatoshis = 12_387, unconfirmedSatoshis = 67_000),
    )

    @Test
    fun `a used receive address is found, with only its own UTXOs`() {
        val info = receiveAddressInfo(snapshot, "bc1q-receive-0")

        assertEquals(AddressChain.RECEIVE, info?.chain)
        assertEquals(0, info?.index)
        assertTrue(info!!.used)
        // Both of receive #0's outputs, and neither of the change address's.
        assertEquals(listOf("aa", "cc"), info.utxos.map { it.utxo.txHash })
    }

    @Test
    fun `an unused address is found with no UTXOs`() {
        val info = receiveAddressInfo(snapshot, "bc1q-receive-1")

        assertEquals(AddressChain.RECEIVE, info?.chain)
        assertEquals(1, info?.index)
        assertFalse(info!!.used)
        assertTrue(info.utxos.isEmpty())
    }

    @Test
    fun `a change-chain address is found too`() {
        val info = receiveAddressInfo(snapshot, "bc1q-change-0")

        assertEquals(AddressChain.CHANGE, info?.chain)
        assertEquals(0, info?.index)
        assertTrue(info!!.used)
        assertEquals(listOf("bb"), info.utxos.map { it.utxo.txHash })
    }

    @Test
    fun `an address the scan never visited comes back null, not guessed`() {
        assertNull(receiveAddressInfo(snapshot, "bc1q-receive-99"))
    }

    @Test
    fun `no snapshot at all is the same as not knowing`() {
        assertNull(receiveAddressInfo(null, "bc1q-receive-0"))
    }
}
