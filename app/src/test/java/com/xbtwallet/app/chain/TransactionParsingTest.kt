package com.xbtwallet.app.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Transaction parser tests.
 *
 * The fixtures are real mainnet transactions (blocks 799 999 and 800 000), so
 * the numbers here are not this test's own arithmetic being read back to it: the
 * txids, the spent outpoint and the output values are what the network says they
 * are. Both are segwit transactions (marker/flag and a witness), the form every
 * modern wallet produces.
 *
 * The malformed cases pin the parser's strictness: a transaction this wallet
 * only half-understands must be an error, never a row with a guessed amount.
 */
class TransactionParsingTest {

    @Test
    fun `parses a real mainnet segwit transaction`() {
        val transaction = TransactionParser.parse(SPEND_TX_HEX)

        assertEquals(SPEND_TX_ID, transaction.txHash)

        // One input, spending output 1 of the funding transaction below.
        assertEquals(1, transaction.inputs.size)
        val input = transaction.inputs.single()
        assertEquals(FUNDING_TX_ID, input.previousTxHash)
        assertEquals(1, input.previousOutputIndex)
        assertEquals("$FUNDING_TX_ID:1", input.outpoint)

        // Two outputs: a taproot payment and a P2WPKH change output, both with
        // the values the chain reports.
        assertEquals(2, transaction.outputs.size)
        assertEquals(143_332L, transaction.outputs[0].satoshis)
        assertEquals(
            "51202d618c1f73d5133fdc97d545bfbf55b4cba2ab2a9d41e4596b1df6b8ea9d9348",
            transaction.outputs[0].scriptPubKeyHex,
        )
        assertEquals(291_851L, transaction.outputs[1].satoshis)
        assertEquals(
            "001464dbbc84f12f32699ca5010faa618d6a25559b6f",
            transaction.outputs[1].scriptPubKeyHex,
        )
    }

    @Test
    fun `parses a funding transaction with its change output`() {
        val transaction = TransactionParser.parse(FUNDING_TX_HEX)

        assertEquals(FUNDING_TX_ID, transaction.txHash)
        assertEquals(1, transaction.inputs.size)
        assertEquals(
            "9c01e763bd2e26d6acb4ebe79b2ffb7deed595c62d26b27bd554259a486a2afc",
            transaction.inputs[0].previousTxHash,
        )
        assertEquals(1, transaction.inputs[0].previousOutputIndex)

        assertEquals(2, transaction.outputs.size)
        assertEquals(204_752L, transaction.outputs[0].satoshis)
        assertEquals(604_308L, transaction.outputs[1].satoshis)
        assertEquals("001464dbbc84f12f32699ca5010faa618d6a25559b6f", transaction.outputs[1].scriptPubKeyHex)
    }

    @Test
    fun `a built transaction round-trips through the parser with its txid`() {
        val hex = TestTransactions.build(
            inputs = listOf(SPEND_TX_ID to 1),
            outputs = listOf(12_345L to "0014" + "11".repeat(20)),
        )

        val transaction = TransactionParser.parse(hex)

        assertEquals(SPEND_TX_ID, transaction.inputs.single().previousTxHash)
        assertEquals(1, transaction.inputs.single().previousOutputIndex)
        assertEquals(12_345L, transaction.outputs.single().satoshis)
        // The txid is derived from the bytes, so it must be a 32-byte hex string
        // and must be stable across parses.
        assertEquals(64, transaction.txHash.length)
        assertEquals(transaction.txHash, TransactionParser.parse(hex).txHash)
    }

    @Test
    fun `an odd-length hex string is rejected`() {
        val error = assertThrows(TransactionParseException::class.java) {
            TransactionParser.parse("020000000")
        }
        assertEquals(true, error.message!!.contains("odd length"))
    }

    @Test
    fun `a non-hex character is rejected`() {
        assertThrows(TransactionParseException::class.java) {
            TransactionParser.parse("02000000zz")
        }
    }

    @Test
    fun `a truncated transaction is rejected instead of guessed at`() {
        // A version, an input count of one, and then nothing.
        assertThrows(TransactionParseException::class.java) {
            TransactionParser.parse("0200000001")
        }
    }

    @Test
    fun `trailing bytes after the locktime are rejected`() {
        val hex = TestTransactions.build(
            inputs = listOf(SPEND_TX_ID to 0),
            outputs = listOf(1_000L to "0014" + "22".repeat(20)),
        )
        assertThrows(TransactionParseException::class.java) {
            TransactionParser.parse(hex + "00")
        }
    }

    private companion object {
        /**
         * Mainnet tx `d41f5de4…e9d5`: one input (a P2WPKH coin of 604 308 sats)
         * and two outputs — 143 332 to a taproot address and 291 851 back to the
         * same P2WPKH address as change. Mined in block 800 000.
         */
        const val SPEND_TX_ID = "d41f5de48325e79070ccd3a23005f7a3b405f3ce1faa4df09f6d71770497e9d5"
        const val SPEND_TX_HEX =
            "020000000001016504bf2c6f9736a3af23a9693e81ef69a70e974f91c6ef3d2e38b7bedddb92a90100000000ffffffff02e42f0200000000002251202d618c1f73d5133fdc97d545bfbf55b4cba2ab2a9d41e4596b1df6b8ea9d93480b7404000000000016001464dbbc84f12f32699ca5010faa618d6a25559b6f02483045022100f404e977e0a3dee1e9da7708db6ce6f3cbe80e6ffbbb6364bd2c725af200520a02201faca96001ac7f82fcea71e03b29deeaac6525c3bb8abe3b3c64544af16b69850121025b1b8e6cd2ebc837fc57928c688b9b4d192f9001d03d1831510a6e511ca3fa5e00000000"

        /** Mainnet tx `a992dbdd…0465`: the transaction that created the coin spent above. */
        const val FUNDING_TX_ID = "a992dbddbeb7382e3defc6914f970ea769ef813e69a923afa336976f2cbf0465"
        const val FUNDING_TX_HEX =
            "02000000000101fc2a6a489a2554d57bb2262dc695d5ee7dfb2f9be7ebb4acd6262ebd63e7019c0100000000ffffffff02d01f030000000000225120988f09c7fd446e5fa713321135947744dd1688a539b929b5a4423f8e82d02ff1943809000000000016001464dbbc84f12f32699ca5010faa618d6a25559b6f0247304402206b26526e4cb0681b082b79b3b7b2abbae1da7665f1b5c3858a80dc5067a1f59e0220645e12cf7d93a6501b31b11d81757d63ce13f3b4d9e307fd08d96c76b33b913a0121025b1b8e6cd2ebc837fc57928c688b9b4d192f9001d03d1831510a6e511ca3fa5e00000000"
    }
}
