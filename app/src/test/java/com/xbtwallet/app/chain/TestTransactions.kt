package com.xbtwallet.app.chain

import java.io.ByteArrayOutputStream

/**
 * Builds raw transactions for the chain-layer tests.
 *
 * The parser itself is pinned against a real mainnet transaction in
 * `TransactionParsingTest`; this builder exists so the *net-amount* tests can
 * describe a wallet's story ("a funding transaction, then a spend with change")
 * without pasting megabytes of hex. It writes a legacy (non-segwit)
 * serialisation, which is still perfectly valid and exercises the same
 * version/inputs/outputs/locktime layout.
 */
internal object TestTransactions {

    /** A P2WPKH scriptPubKey (`0014<20-byte program>`) from a program's hex. */
    fun p2wpkhSpk(programHex: String): String {
        require(programHex.length == 40) { "a P2WPKH program is 20 bytes, got '${programHex}'" }
        return "0014$programHex"
    }

    /**
     * One legacy transaction. [inputs] are `(previous txid, previous output
     * index)` in display txid order; [outputs] are `(satoshis, scriptPubKey hex)`.
     */
    fun build(
        inputs: List<Pair<String, Int>>,
        outputs: List<Pair<Long, String>>,
    ): String {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x02, 0x00, 0x00, 0x00)) // version 2
        writeVarInt(out, inputs.size)
        for ((previousTxHash, previousIndex) in inputs) {
            // The wire stores the previous txid little-endian.
            out.write(hexBytes(previousTxHash).reversedArray())
            out.write(littleEndian32(previousIndex))
            out.write(0x00) // empty scriptSig
            out.write(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
        }
        writeVarInt(out, outputs.size)
        for ((value, scriptHex) in outputs) {
            out.write(littleEndian64(value))
            val script = hexBytes(scriptHex)
            writeVarInt(out, script.size)
            out.write(script)
        }
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00)) // locktime 0
        return out.toByteArray().joinToString("") { "%02x".format(it) }
    }

    /** The txid a built transaction will be known by. */
    fun txid(hex: String): String = TransactionParser.parse(hex).txHash

    /** Writes the transaction and returns `(txid, hex)`, the shape the fixtures use. */
    fun buildWithId(
        inputs: List<Pair<String, Int>>,
        outputs: List<Pair<Long, String>>,
    ): Pair<String, String> {
        val hex = build(inputs, outputs)
        return txid(hex) to hex
    }

    private fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
        when {
            value < 0xfd -> out.write(value)
            value <= 0xffff -> {
                out.write(0xfd)
                out.write(value and 0xff)
                out.write((value shr 8) and 0xff)
            }
            else -> {
                out.write(0xfe)
                out.write(littleEndian32(value))
            }
        }
    }

    private fun littleEndian32(value: Int): ByteArray =
        ByteArray(4) { index -> ((value shr (8 * index)) and 0xff).toByte() }

    private fun littleEndian64(value: Long): ByteArray =
        ByteArray(8) { index -> ((value shr (8 * index)) and 0xff).toByte() }

    private fun hexBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length: '${hex.length}'" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(2 * index, 2 * index + 2).toInt(16).toByte()
        }
    }
}
