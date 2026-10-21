package com.xbtwallet.app.chain

import com.xbtwallet.app.crypto.Hashes
import com.xbtwallet.app.crypto.SegwitAddress
import com.xbtwallet.app.crypto.transactionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Building the unsigned transaction, planning a send, and turning an address
 * into the script an output pays.
 *
 * The serialization vector and the txid are the same independently generated
 * fixture the PSBT tests use (a 2-output transaction spending one P2WPKH coin),
 * so the transaction the planner builds and the PSBT that carries it are pinned
 * to one another.
 */
class UnsignedTransactionTest {

    private val fixtureTxidHex = "1111111111111111111111111111111111111111111111111111111111111111"
    private val recipientScriptHex = "00142222222222222222222222222222222222222222"
    private val changeScriptHex = "00143333333333333333333333333333333333333333"

    private val fixtureUnsignedTxHex =
        "020000000111111111111111111111111111111111111111111111111111111111111111110000000000" +
            "ffffffff0250c30000000000001600142222222222222222222222222222222222222222409c0000" +
            "00000000160014333333333333333333333333333333333333333300000000"

    private val fixtureTxid = "55b09bb1e99a690cf0a7fba3d27cd0ce19483a6df3c6179a86f994f438b94754"

    // ---------------------------------------------------------------------
    // Serialization
    // ---------------------------------------------------------------------

    @Test
    fun `buildUnsignedTransaction reproduces the reference fixture byte for byte`() {
        val built = buildUnsignedTransaction(
            inputs = listOf(TxInput(fixtureTxidHex, 0)),
            outputs = listOf(
                TxOutput(50_000L, recipientScriptHex),
                TxOutput(40_000L, changeScriptHex),
            ),
        )
        assertEquals(fixtureUnsignedTxHex, Scripthash.toHex(built))
    }

    @Test
    fun `the transaction's id is the non-witness double-SHA256, reversed`() {
        val built = buildUnsignedTransaction(
            inputs = listOf(TxInput(fixtureTxidHex, 0)),
            outputs = listOf(
                TxOutput(50_000L, recipientScriptHex),
                TxOutput(40_000L, changeScriptHex),
            ),
        )
        assertEquals(fixtureTxid, transactionId(built))
    }

    @Test
    fun `outpoint ids are written byte-reversed and sequences are final`() {
        val displayOrderTxid = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val built = buildUnsignedTransaction(
            inputs = listOf(TxInput(displayOrderTxid, 7)),
            outputs = listOf(TxOutput(1L, recipientScriptHex)),
        )
        val hex = Scripthash.toHex(built)

        // version 2, one input, the outpoint with its bytes reversed …
        assertTrue(
            hex,
            hex.startsWith(
                "0200000001" +
                    "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100",
            ),
        )
        // … then vout 7, an empty scriptSig, and the final sequence.
        assertTrue(hex, hex.contains("0700000000ffffffff"))
    }

    // ---------------------------------------------------------------------
    // The fee and the change
    // ---------------------------------------------------------------------

    private fun input(satoshis: Long) =
        SendInput(fixtureTxidHex, 0, satoshis, Scripthash.p2wpkhScript(ByteArray(20) { 0xAA.toByte() }))

    @Test
    fun `the fee is the estimate for the inputs at the given rate`() {
        val plan = planSend(
            inputs = listOf(input(100_000L)),
            amountSatoshis = 50_000L,
            feeRateSatPerVb = 1L,
            recipientScriptPubKey = hexToBytes(recipientScriptHex),
            changeScriptPubKey = hexToBytes(changeScriptHex),
        )
        assertEquals(estimateSendVsize(1).toLong(), plan.feeSatoshis)
        assertEquals(100_000L - 50_000L - plan.feeSatoshis, plan.changeSatoshis)
        assertTrue(plan.hasChange)
        assertEquals(2, plan.outputs.size)
    }

    @Test
    fun `the fee scales with the number of inputs`() {
        val plan = planSend(
            inputs = listOf(input(100_000L), input(100_000L)),
            amountSatoshis = 50_000L,
            feeRateSatPerVb = 2L,
            recipientScriptPubKey = hexToBytes(recipientScriptHex),
            changeScriptPubKey = hexToBytes(changeScriptHex),
        )
        assertEquals(estimateSendVsize(2).toLong() * 2L, plan.feeSatoshis)
    }

    @Test
    fun `change below the dust threshold is dropped and joins the fee`() {
        // total - amount - estimate = 1000 - 500 - 140 = 360, under 546.
        val plan = planSend(
            inputs = listOf(input(1_000L)),
            amountSatoshis = 500L,
            feeRateSatPerVb = 1L,
            recipientScriptPubKey = hexToBytes(recipientScriptHex),
            changeScriptPubKey = hexToBytes(changeScriptHex),
        )
        assertEquals(1, plan.outputs.size)
        assertFalse(plan.hasChange)
        assertEquals(0L, plan.changeSatoshis)
        // The fee it actually pays absorbs the unusable change.
        assertEquals(1_000L - 500L, plan.feeSatoshis)
        assertEquals(500L, plan.outputs.single().satoshis)
    }

    @Test
    fun `a max send leaves exactly the fee behind and creates no change`() {
        val fee = estimateSendVsize(1).toLong()
        val total = 50_000L + fee
        val plan = planSend(
            inputs = listOf(input(total)),
            amountSatoshis = 50_000L,
            feeRateSatPerVb = 1L,
            recipientScriptPubKey = hexToBytes(recipientScriptHex),
            changeScriptPubKey = hexToBytes(changeScriptHex),
        )
        assertEquals(1, plan.outputs.size)
        assertFalse(plan.hasChange)
        assertEquals(fee, plan.feeSatoshis)
        assertEquals(50_000L, plan.outputs.single().satoshis)
    }

    // ---------------------------------------------------------------------
    // Addresses as scripts
    // ---------------------------------------------------------------------

    @Test
    fun `a version 0 address becomes its P2WPKH script`() {
        // The canonical BIP-173 vector: its program is 751e76e8199196d454941c45d1b3a323f1433bd6.
        val script = scriptPubKeyFromAddress("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
        assertEquals("0014751e76e8199196d454941c45d1b3a323f1433bd6", Scripthash.toHex(script))
    }

    @Test
    fun `a version 1 address becomes its taproot script`() {
        val program = ByteArray(32) { index -> (index + 1).toByte() }
        val address = SegwitAddress.encode(SegwitAddress.MAINNET_HRP, 1, program)
        val script = scriptPubKeyFromAddress(address)
        assertEquals("5120" + Scripthash.toHex(program), Scripthash.toHex(script))
    }

    @Test
    fun `an address built from a public key pays the wallet's own script shape`() {
        val pubKey = ByteArray(33) { index -> (index + 3).toByte() }
        val script = p2wpkhScriptPubKey(pubKey)
        assertEquals("0014" + Scripthash.toHex(Hashes.hash160(pubKey)), Scripthash.toHex(script))
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }
}
