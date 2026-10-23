package com.xbtwallet.app.crypto

import java.io.ByteArrayOutputStream

/**
 * BIP-143 segwit v0 signature hashing, for the one script type this wallet
 * builds: native P2WPKH.
 *
 * The preimage is the BIP-143 `SigMsg` for `SIGHASH_ALL`-class semantics:
 *
 * ```
 * nVersion || hashPrevouts || hashSequence || outpoint || scriptCode ||
 * amount || nSequence || hashOutputs || nLocktime || nHashType
 * ```
 *
 * with each of `hashPrevouts`, `hashSequence`, `hashOutputs` a double-SHA256,
 * `scriptCode` the P2PKH script a P2WPKH input is spent as
 * (`0x19 76a914 <hash160(pubkey)> 88ac`), and `amount` the 8-byte little-endian
 * value from the input's [WitnessUtxo].
 *
 * **Only SIGHASH_ALL-class hashtypes are accepted.** The wallet signs with
 * `0x21` ([SIGHASH_UNIFIED]: `SIGHASH_ALL | SIGHASH_UNIFIED`), and understands
 * plain `0x01` so it can compute the BIP-143 reference digest. `NONE`, `SINGLE`
 * and `ANYONECANPAY` change what the preimage commits to, and none of them is
 * replay-protected — so the three fields this file refuses to fake (a different
 * `hashPrevouts`/`hashOutputs` rule, a partial output commitment) are exactly the
 * ones that hide an un-unified signature. Refusing is the point.
 *
 * Operates on [DecodedTransaction], the parse [decodeTransaction] already
 * produces, plus the input's [WitnessUtxo] as read by [parsePsbt]. Android-free.
 */
object Sighash {

    /**
     * The BIP-143 preimage for input [inputIndex] of [tx], spending
     * [witnessUtxo], with the given [hashType].
     *
     * Throws [IllegalArgumentException] when the hashtype is not `0x01`/`0x21`,
     * when [inputIndex] does not name an input, or when the witness utxo's
     * scriptPubKey is not a P2WPKH program.
     */
    fun p2wpkhPreimage(
        tx: DecodedTransaction,
        inputIndex: Int,
        witnessUtxo: WitnessUtxo,
        hashType: Long = SIGHASH_UNIFIED,
    ): ByteArray {
        require(hashType == SIGHASH_ALL || hashType == SIGHASH_UNIFIED) {
            "only SIGHASH_ALL-class hashtypes (0x01 and 0x21) are supported, got 0x${hashType.toString(16)}"
        }
        require(inputIndex in tx.inputs.indices) {
            "input index $inputIndex is out of range: the transaction has ${tx.inputs.size} input(s)"
        }

        val scriptPubKey = witnessUtxo.scriptPubKey
        require(isP2wpkh(scriptPubKey)) {
            "the input's witness utxo is not a P2WPKH scriptPubKey"
        }

        val input = tx.inputs[inputIndex]
        val out = ByteArrayOutputStream()

        // nVersion
        out.write(uint32Le(tx.version))

        // hashPrevouts = dSHA256(all outpoints)
        val prevouts = ByteArrayOutputStream().apply {
            tx.inputs.forEach { each ->
                write(each.previousTxid)
                write(uint32Le(each.outputIndex))
            }
        }.toByteArray()
        out.write(Hashes.sha256d(prevouts))

        // hashSequence = dSHA256(all nSequence)
        val sequences = ByteArrayOutputStream().apply {
            tx.inputs.forEach { each -> write(uint32Le(each.sequence)) }
        }.toByteArray()
        out.write(Hashes.sha256d(sequences))

        // outpoint of the input being signed
        out.write(input.previousTxid)
        out.write(uint32Le(input.outputIndex))

        // scriptCode: the P2PKH script the P2WPKH program is executed as.
        out.write(scriptCode(scriptPubKey))

        // amount (8-byte LE) and nSequence
        out.write(uint64Le(witnessUtxo.satoshis))
        out.write(uint32Le(input.sequence))

        // hashOutputs = dSHA256(all outputs)
        val outputs = ByteArrayOutputStream().apply {
            tx.outputs.forEach { each ->
                write(uint64Le(each.satoshis))
                writeCompactSize(each.scriptPubKey.size.toLong())
                write(each.scriptPubKey)
            }
        }.toByteArray()
        out.write(Hashes.sha256d(outputs))

        // nLocktime and the 4-byte nHashType
        out.write(uint32Le(tx.lockTime))
        out.write(uint32Le(hashType))

        return out.toByteArray()
    }

    /**
     * The digest [Ecdsa.sign] consumes for the same input: double-SHA256 of
     * [p2wpkhPreimage].
     */
    fun p2wpkhDigest(
        tx: DecodedTransaction,
        inputIndex: Int,
        witnessUtxo: WitnessUtxo,
        hashType: Long = SIGHASH_UNIFIED,
    ): ByteArray = Hashes.sha256d(p2wpkhPreimage(tx, inputIndex, witnessUtxo, hashType))

    /** `SIGHASH_ALL` — the plain, non-replay-protected hashtype, used only for BIP-143 vectors. */
    const val SIGHASH_ALL: Long = 0x01L

    /** A v0 witness program of exactly 20 bytes: `OP_0` `0x14` `<20 bytes>`. */
    private fun isP2wpkh(scriptPubKey: ByteArray): Boolean =
        scriptPubKey.size == 22 &&
            scriptPubKey[0] == 0x00.toByte() &&
            scriptPubKey[1] == 0x14.toByte()

    /** `0x19 76a914 <20-byte key hash> 88ac`, taken from the witness-utxo program. */
    private fun scriptCode(scriptPubKey: ByteArray): ByteArray =
        byteArrayOf(0x19, 0x76, 0xa9.toByte(), 0x14) +
            scriptPubKey.copyOfRange(2, 22) +
            byteArrayOf(0x88.toByte(), 0xac.toByte())

    private fun uint32Le(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun uint64Le(value: Long): ByteArray = ByteArray(8) { i -> ((value ushr (8 * i)) and 0xFF).toByte() }
}
