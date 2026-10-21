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
    // Exact virtual size
    // ---------------------------------------------------------------------

    /**
     * The pins. Each number below was computed by hand from the transaction's
     * structure — base bytes at 4 weight units each, witness bytes at 1 — and
     * then ceiling-divided, because that is exactly what a node does before it
     * compares the fee to `minrelaytxfee * vsize`.
     */
    @Test
    fun `the on-device failure case — 1-in 2-out P2WPKH — is exactly 141 vB`() {
        // Andrew's rejected broadcast: "min relay fee not met, 140 < 141". The
        // old `10 + 68n + 62` said 140; the transaction is 140.75 vB and a node
        // rounds that up. This assertion is the regression pin.
        assertEquals(141, exactSendVsize(1, listOf(22, 22)))
    }

    @Test
    fun `a 1-in 1-out max send — no change output — is 110 vB`() {
        // Dropping the change output removes 8 + 1 + 22 = 31 non-witness bytes,
        // i.e. 124 weight units: 563 - 124 = 439, ceil(439/4) = 110.
        assertEquals(110, exactSendVsize(1, listOf(22)))
    }

    @Test
    fun `each P2WPKH input costs 41 base bytes and 109 witness bytes`() {
        // 2 inputs, 2 outputs: 4*154 + 220 = 836 weight units, exactly 209 vB.
        assertEquals(209, exactSendVsize(2, listOf(22, 22)))
        // 3 inputs is where the per-input constant would drift: 4*195 + 329 =
        // 1109, ceil(1109/4) = 278, not 209 + 68 = 277.
        assertEquals(278, exactSendVsize(3, listOf(22, 22)))
        // 41 non-witness bytes cost 4 WU each, plus the 109-byte witness.
        assertEquals(273, 4 * 41 + 109)
    }

    @Test
    fun `a taproot recipient is counted as a 43-byte output`() {
        // 8-byte value + 1-byte length + 34-byte scriptPubKey = 43 bytes, 12 more
        // than P2WPKH's 31 — 48 weight units, so 153 vB against 141.
        assertEquals(153, exactSendVsize(1, listOf(34, 22)))
        assertEquals(12, exactSendVsize(1, listOf(34)) - exactSendVsize(1, listOf(22)))
    }

    @Test
    fun `an input count past the CompactSize boundary pays for its extra count bytes`() {
        // Below 253 the input count is a single byte; at 253 it becomes a
        // 0xFD-prefixed two-byte value — two bytes more, 8 more weight units, so
        // 2 more vB on top of the 68 a P2WPKH input adds.
        assertEquals(68, exactSendVsize(252, listOf(22)) - exactSendVsize(251, listOf(22)))
        assertEquals(70, exactSendVsize(253, listOf(22)) - exactSendVsize(252, listOf(22)))
    }

    @Test
    fun `the arithmetic is structural — an input-less, output-less send is still sized`() {
        // 4 version + 1 input count + 1 output count + 4 locktime = 10 base
        // bytes, and nothing but the witness marker and flag.
        assertEquals(11, exactSendVsize(0, emptyList()))
    }

    // ---------------------------------------------------------------------
    // The fee and the change
    // ---------------------------------------------------------------------

    private fun input(satoshis: Long) =
        SendInput(fixtureTxidHex, 0, satoshis, Scripthash.p2wpkhScript(ByteArray(20) { 0xAA.toByte() }))

    private fun p2trScript(): ByteArray = byteArrayOf(0x51, 0x20) + ByteArray(32) { index -> index.toByte() }

    /** The exact vsize of a plan, derived from the outputs it actually carries. */
    private fun SendPlan.actualVsize(): Int =
        exactSendVsize(inputs.size, outputs.map { it.scriptPubKeyHex.length / 2 })

    @Test
    fun `the fee is the exact vsize for the planned outputs at the given rate`() {
        val plan = planSend(
            inputs = listOf(input(100_000L)),
            amountSatoshis = 50_000L,
            feeRateSatPerVb = 1L,
            recipientScriptPubKey = hexToBytes(recipientScriptHex),
            changeScriptPubKey = hexToBytes(changeScriptHex),
        )
        // 1-in/2-out P2WPKH: 141 vB at 1 sat/vB is 141 sats — the on-device case,
        // through the planner this time.
        assertEquals(141L, plan.feeSatoshis)
        assertEquals(141L, plan.actualVsize().toLong() * 1L)
        assertEquals(2, plan.outputs.size)
        assertTrue(plan.hasChange)
        assertEquals(100_000L - 50_000L - plan.feeSatoshis, plan.changeSatoshis)
    }

    @Test
    fun `a taproot recipient is charged for its 43-byte output`() {
        val plan = planSend(
            inputs = listOf(input(100_000L)),
            amountSatoshis = 50_000L,
            feeRateSatPerVb = 1L,
            recipientScriptPubKey = p2trScript(),
            changeScriptPubKey = hexToBytes(changeScriptHex),
        )
        assertEquals(153L, plan.feeSatoshis)
        assertEquals(plan.actualVsize().toLong(), plan.feeSatoshis)
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
        // 2-in/2-out P2WPKH is 209 vB; at 2 sat/vB that is 418 sats.
        assertEquals(418L, plan.feeSatoshis)
        assertEquals(plan.actualVsize().toLong() * 2L, plan.feeSatoshis)
    }

    @Test
    fun `the planned fee always covers the planned transaction at the planned rate`() {
        // The relay-minimum invariant, swept: whatever the shape, the fee the
        // planner charges is at least `vsize * feeRate` for the transaction it
        // built — the exact test a node applies (`140 < 141` was this failing).
        val recipientScripts = listOf(
            "p2wpkh" to hexToBytes(recipientScriptHex),
            "p2tr" to p2trScript(),
        )
        for (inputCount in 1..20) {
            for ((name, recipientScript) in recipientScripts) {
                val plan = planSend(
                    inputs = List(inputCount) { input(100_000L) },
                    amountSatoshis = 50_000L * inputCount,
                    feeRateSatPerVb = 1L,
                    recipientScriptPubKey = recipientScript,
                    changeScriptPubKey = hexToBytes(changeScriptHex),
                )
                val vsize = plan.actualVsize().toLong()
                assertTrue(
                    "$inputCount inputs, $name: fee ${plan.feeSatoshis} < vsize $vsize",
                    plan.feeSatoshis >= vsize * 1L,
                )
                assertEquals("$inputCount inputs, $name", vsize * 1L, plan.feeSatoshis)
            }
        }
    }

    @Test
    fun `change below the dust threshold is dropped and joins the fee`() {
        // total - amount - fee = 1000 - 500 - 141 = 359, under 546.
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
        // The fee it actually pays absorbs the unusable change …
        assertEquals(1_000L - 500L, plan.feeSatoshis)
        assertEquals(500L, plan.outputs.single().satoshis)
        // … and still covers the single-output transaction it built (110 vB).
        assertEquals(110L, plan.actualVsize().toLong())
        assertTrue(plan.feeSatoshis >= plan.actualVsize().toLong())
    }

    @Test
    fun `the form's max send creates no change output and still pays for what it builds`() {
        // "Max" allows total - estimateSendVsize(1), the worst case (two
        // 43-byte outputs). At 1 sat/vB that leaves 165 sats of fee for a
        // transaction that only needs 110 — over-provided, which is the safe
        // direction for the invariant above.
        val feeForAMaxSend = estimateSendVsize(1).toLong()
        val plan = planSend(
            inputs = listOf(input(50_000L + feeForAMaxSend)),
            amountSatoshis = 50_000L,
            feeRateSatPerVb = 1L,
            recipientScriptPubKey = hexToBytes(recipientScriptHex),
            changeScriptPubKey = hexToBytes(changeScriptHex),
        )
        assertEquals(1, plan.outputs.size)
        assertFalse(plan.hasChange)
        assertEquals(feeForAMaxSend, plan.feeSatoshis)
        assertTrue(plan.feeSatoshis >= plan.actualVsize().toLong())
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
