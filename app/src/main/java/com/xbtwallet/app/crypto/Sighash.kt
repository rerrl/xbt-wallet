package com.xbtwallet.app.crypto

import java.io.ByteArrayOutputStream

/**
 * The signature hashes this wallet can produce, for the one script type it
 * builds: native P2WPKH (segwit v0).
 *
 * There are two of them, and which one applies is decided entirely by the
 * hashtype byte the signature will carry:
 *
 * - **`SIGHASH_UNIFIED` unset** — the plain BIP-143 segwit v0 digest, the
 *   algorithm Bitcoin itself defines. [p2wpkhPreimage] and
 *   [p2wpkhLegacyDigest] are that algorithm, pinned against BIP-143's own
 *   vector.
 * - **`SIGHASH_UNIFIED` set** — the *unified* signature hash (Knots PR 357,
 *   `doc/unified-sighash.md`): one BIP-341-shaped message, tagged
 *   `"UnifiedSighash"`, which commits to every input's amount and scriptPubKey
 *   and is therefore not valid on any chain that does not implement it. That
 *   distinct message is XBT's replay protection; the extra bit in the hashtype
 *   byte is only how a spender says "sign this one". [unifiedPreimage] and
 *   [unifiedDigest] are that algorithm, pinned against the specification's own
 *   `unified_sighash.json` vectors.
 *
 * **Why the distinction matters.** Setting bit `0x20` does *not* mean "BIP-143
 * with a different last byte". A signer that computes a BIP-143 digest and
 * appends `0x21` produces a signature over a message no opted-in node ever
 * computes, so every such spend fails script verification —
 * `mempool-script-verify-flag-failed (Signature must be zero for failed
 * CHECK(MULTI)SIG operation)`. The message is what changes; the byte only
 * selects it.
 *
 * Both algorithms are Android-free and byte-oriented, so the whole signing path
 * is pinned by JVM vectors.
 */
object Sighash {

    /** `SIGHASH_ALL` — the plain, non-replay-protected hashtype. */
    const val SIGHASH_ALL: Long = 0x01L

    /** `SIGHASH_NONE` — signs no outputs (unified message only). */
    const val SIGHASH_NONE: Long = 0x02L

    /** `SIGHASH_SINGLE` — signs the output at the input's index (unified only). */
    const val SIGHASH_SINGLE: Long = 0x03L

    /**
     * `SIGHASH_UNIFIED` — the opt-in **bit** that selects the unified signature
     * hash. The wallet signs with `SIGHASH_ALL or SIGHASH_UNIFIED_BIT` = `0x21`
     * ([com.xbtwallet.app.crypto.SIGHASH_UNIFIED], the byte a signature carries).
     */
    const val SIGHASH_UNIFIED_BIT: Long = 0x20L

    /** `SIGHASH_ANYONECANPAY`. */
    const val SIGHASH_ANYONECANPAY: Long = 0x80L

    /** The bits that carry the base output type. */
    private const val BASE_MASK: Long = 0x1FL

    /** BIP340's tag string for the unified message. It cannot change. */
    private const val UNIFIED_TAG = "UnifiedSighash"

    /** The spec's script-type byte for a bare or P2SH input. */
    private const val SCRIPT_TYPE_BARE_OR_P2SH = 0x00

    /** The spec's script-type byte for a segwit v0 (P2WPKH) input. */
    private const val SCRIPT_TYPE_P2WPKH = 0x01

    /** The spec's epoch byte. BIP341's, kept for a later revision's room. */
    private const val EPOCH = 0x00

    // -----------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------

    /**
     * The digest `Ecdsa.sign` consumes for input [inputIndex] of [tx], under
     * [hashType], spending [spentOutputs] (one per input, in input order).
     *
     * Dispatches on the opt-in bit: with `SIGHASH_UNIFIED` set this is
     * [unifiedDigest], otherwise the BIP-143 [p2wpkhLegacyDigest].
     *
     * @param spentOutputs every input's witness utxo. BIP-143 needs only the
     *   signed input's; the unified message commits to all of them, which is why
     *   the list is required rather than a single utxo.
     */
    fun p2wpkhDigest(
        tx: DecodedTransaction,
        inputIndex: Int,
        spentOutputs: List<WitnessUtxo>,
        hashType: Long = SIGHASH_ALL,
    ): ByteArray =
        if (hashType and SIGHASH_UNIFIED_BIT != 0L) {
            unifiedDigest(tx, inputIndex, spentOutputs, hashType)
        } else {
            p2wpkhLegacyDigest(tx, inputIndex, signedUtxo(inputIndex, spentOutputs, tx), hashType)
        }

    // -----------------------------------------------------------------------
    // BIP-143 — the plain legacy segwit v0 algorithm
    // -----------------------------------------------------------------------

    /**
     * The BIP-143 preimage for input [inputIndex] of [tx], spending
     * [witnessUtxo], with [hashType] (which must be `SIGHASH_ALL`).
     *
     * The preimage is the BIP-143 `SigMsg`:
     *
     * ```
     * nVersion || hashPrevouts || hashSequence || outpoint || scriptCode ||
     * amount || nSequence || hashOutputs || nLocktime || nHashType
     * ```
     *
     * with each of `hashPrevouts`, `hashSequence`, `hashOutputs` a double-SHA256
     * and `scriptCode` the P2PKH script a P2WPKH input is spent as
     * (`0x19 76a914 <hash160(pubkey)> 88ac`).
     *
     * Throws [IllegalArgumentException] when [hashType] is not `SIGHASH_ALL`
     * (every other type — including one carrying the unified bit — is a
     * *different* message, computed by [unifiedPreimage]), when [inputIndex]
     * does not name an input, or when the witness utxo is not P2WPKH.
     */
    fun p2wpkhPreimage(
        tx: DecodedTransaction,
        inputIndex: Int,
        witnessUtxo: WitnessUtxo,
        hashType: Long = SIGHASH_ALL,
    ): ByteArray {
        require(hashType == SIGHASH_ALL) {
            "BIP-143 hashes only SIGHASH_ALL (0x01); hashtype 0x${hashType.toString(16)} " +
                "selects a different message"
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
        out.write(Hashes.sha256d(prevoutSerialization(tx)))

        // hashSequence = dSHA256(all nSequence)
        out.write(Hashes.sha256d(sequenceSerialization(tx)))

        // outpoint of the input being signed
        out.write(input.previousTxid)
        out.write(uint32Le(input.outputIndex))

        // scriptCode: the P2PKH script the P2WPKH program is executed as.
        out.write(scriptCode(scriptPubKey))

        // amount (8-byte LE) and nSequence
        out.write(uint64Le(witnessUtxo.satoshis))
        out.write(uint32Le(input.sequence))

        // hashOutputs = dSHA256(all outputs)
        out.write(Hashes.sha256d(outputSerialization(tx)))

        // nLocktime and the 4-byte nHashType
        out.write(uint32Le(tx.lockTime))
        out.write(uint32Le(hashType))

        return out.toByteArray()
    }

    /**
     * The BIP-143 digest for the same input: double-SHA256 of
     * [p2wpkhPreimage].
     */
    fun p2wpkhLegacyDigest(
        tx: DecodedTransaction,
        inputIndex: Int,
        witnessUtxo: WitnessUtxo,
        hashType: Long = SIGHASH_ALL,
    ): ByteArray = Hashes.sha256d(p2wpkhPreimage(tx, inputIndex, witnessUtxo, hashType))

    // -----------------------------------------------------------------------
    // The unified signature hash
    // -----------------------------------------------------------------------

    /**
     * The unified message for a **P2WPKH** input [inputIndex] of [tx], spending
     * [spentOutputs], under [hashType] (which must carry the opt-in bit).
     *
     * The script type byte is fixed at 1 (segwit v0) and the tail is the
     * BIP-143 implied P2PKH `scriptCode`, so this is the whole of what this
     * wallet needs — see [unifiedPreimageWithScript] for the general form the
     * specification's vectors drive.
     *
     * ```
     * epoch (0) || hashType (1 byte) || nVersion (4 LE) || nLocktime (5 LE)
     * || sha_prevouts || sha_amounts || sha_scripts || sha_sequences
     * || sha_outputs
     * || scriptType (1 = segwit v0) || inputIndex (4 LE)
     * || scriptCode (compact size + bytes)
     * ```
     *
     * Each aggregate is a **single** SHA256 (not the double one BIP-143 uses)
     * over the listed serializations; `amounts` and `scripts` are over every
     * *spent output*, which is what closes CVE-2020-14199. The signature hash is
     * `TaggedHash("UnifiedSighash", message)` — see [unifiedDigest].
     *
     * Throws [IllegalArgumentException] when the opt-in bit is not set, when
     * [spentOutputs] does not carry one entry per input, or when the signed
     * input's witness utxo is not a P2WPKH program.
     */
    fun unifiedPreimage(
        tx: DecodedTransaction,
        inputIndex: Int,
        spentOutputs: List<WitnessUtxo>,
        hashType: Long,
    ): ByteArray {
        val signed = signedUtxo(inputIndex, spentOutputs, tx)
        require(isP2wpkh(signed.scriptPubKey)) {
            "the input's witness utxo is not a P2WPKH scriptPubKey"
        }
        return unifiedPreimageWithScript(
            tx = tx,
            inputIndex = inputIndex,
            spentOutputs = spentOutputs,
            hashType = hashType,
            scriptType = SCRIPT_TYPE_P2WPKH,
            scriptCode = scriptCode(signed.scriptPubKey),
        )
    }

    /**
     * The unified message with the script type and `scriptCode` supplied
     * directly — the specification's own inputs, and what its vectors drive.
     *
     * For script type 1 `scriptCode` is the BIP-143 implied P2PKH script, which
     * [unifiedPreimage] derives from the witness utxo. For script type 0 it is
     * the scriptPubKey (with the signature removed by the interpreter, an
     * operation that does not apply to segwit v0). Script types 2 and 3 carry a
     * taproot tail and are not implemented: this wallet is native segwit only.
     */
    internal fun unifiedPreimageWithScript(
        tx: DecodedTransaction,
        inputIndex: Int,
        spentOutputs: List<WitnessUtxo>,
        hashType: Long,
        scriptType: Int,
        scriptCode: ByteArray,
    ): ByteArray {
        require(hashType and SIGHASH_UNIFIED_BIT != 0L) {
            "the unified message requires the SIGHASH_UNIFIED (0x20) bit; hashtype " +
                "0x${hashType.toString(16)} does not set it"
        }
        require(inputIndex in tx.inputs.indices) {
            "input index $inputIndex is out of range: the transaction has ${tx.inputs.size} input(s)"
        }
        require(spentOutputs.size == tx.inputs.size) {
            "the unified message commits to every spent output: the transaction has " +
                "${tx.inputs.size} input(s) but ${spentOutputs.size} spent output(s) were given"
        }
        require(scriptType == SCRIPT_TYPE_BARE_OR_P2SH || scriptType == SCRIPT_TYPE_P2WPKH) {
            "script type $scriptType is a taproot shape this wallet does not build; only 0 " +
                "(bare/P2SH) and 1 (segwit v0) are implemented"
        }

        val input = tx.inputs[inputIndex]
        val signed = spentOutputs[inputIndex]

        val anyOneCanPay = hashType and SIGHASH_ANYONECANPAY != 0L
        val base = hashType and BASE_MASK

        val out = ByteArrayOutputStream()

        // epoch, then the hash type byte and the transaction's own header.
        out.write(EPOCH)
        out.write((hashType and 0xFF).toInt())
        out.write(uint32Le(tx.version))
        out.write(uint40Le(tx.lockTime))

        if (!anyOneCanPay) {
            out.write(Hashes.sha256(prevoutSerialization(tx)))
            out.write(Hashes.sha256(amountsSerialization(spentOutputs)))
            out.write(Hashes.sha256(scriptsSerialization(spentOutputs)))
            out.write(Hashes.sha256(sequenceSerialization(tx)))
        }

        // Every value that is not SINGLE or NONE signs all outputs.
        if (base != SIGHASH_NONE && base != SIGHASH_SINGLE) {
            out.write(Hashes.sha256(outputSerialization(tx)))
        }

        // The byte that identifies the spend.
        out.write(scriptType)

        if (anyOneCanPay) {
            // The aggregates are absent, so the input's own data is committed to
            // directly and its position is not.
            out.write(input.previousTxid)
            out.write(uint32Le(input.outputIndex))
            out.write(uint64Le(signed.satoshis))
            out.write(compactString(signed.scriptPubKey))
            out.write(uint32Le(input.sequence))
        } else {
            out.write(uint32Le(inputIndex.toLong()))
        }

        // For script types 0 and 1 the tail is the scriptCode.
        out.write(compactString(scriptCode))

        if (base == SIGHASH_SINGLE) {
            require(inputIndex < tx.outputs.size) {
                "SIGHASH_SINGLE with no output at the input's index is invalid"
            }
            val output = tx.outputs[inputIndex]
            out.write(Hashes.sha256(uint64Le(output.satoshis) + compactString(output.scriptPubKey)))
        }

        return out.toByteArray()
    }

    /**
     * The unified signature hash: `TaggedHash("UnifiedSighash", message)` as
     * BIP340 defines it, i.e. `SHA256(SHA256(tag) || SHA256(tag) || message)`.
     */
    fun unifiedDigest(
        tx: DecodedTransaction,
        inputIndex: Int,
        spentOutputs: List<WitnessUtxo>,
        hashType: Long,
    ): ByteArray = taggedHash(UNIFIED_TAG, unifiedPreimage(tx, inputIndex, spentOutputs, hashType))

    /** The unified digest with an explicit script type and scriptCode (see [unifiedPreimageWithScript]). */
    internal fun unifiedDigestWithScript(
        tx: DecodedTransaction,
        inputIndex: Int,
        spentOutputs: List<WitnessUtxo>,
        hashType: Long,
        scriptType: Int,
        scriptCode: ByteArray,
    ): ByteArray = taggedHash(
        UNIFIED_TAG,
        unifiedPreimageWithScript(tx, inputIndex, spentOutputs, hashType, scriptType, scriptCode),
    )

    // -----------------------------------------------------------------------
    // The pieces both algorithms share
    // -----------------------------------------------------------------------

    /** The input's own witness utxo, with the range check BIP-143 does not do. */
    private fun signedUtxo(
        inputIndex: Int,
        spentOutputs: List<WitnessUtxo>,
        tx: DecodedTransaction,
    ): WitnessUtxo {
        require(inputIndex in tx.inputs.indices) {
            "input index $inputIndex is out of range: the transaction has ${tx.inputs.size} input(s)"
        }
        require(spentOutputs.size == tx.inputs.size) {
            "the transaction has ${tx.inputs.size} input(s) but ${spentOutputs.size} spent output(s) were given"
        }
        return spentOutputs[inputIndex]
    }

    /** All outpoints: `32-byte txid (internal order) || 4-byte index LE`. */
    private fun prevoutSerialization(tx: DecodedTransaction): ByteArray {
        val out = ByteArrayOutputStream()
        tx.inputs.forEach { each ->
            out.write(each.previousTxid)
            out.write(uint32Le(each.outputIndex))
        }
        return out.toByteArray()
    }

    /** Every other input's nSequence, 4 bytes LE each. */
    private fun sequenceSerialization(tx: DecodedTransaction): ByteArray {
        val out = ByteArrayOutputStream()
        tx.inputs.forEach { each -> out.write(uint32Le(each.sequence)) }
        return out.toByteArray()
    }

    /** Every output: 8-byte LE value, then the scriptPubKey's compact size and bytes. */
    private fun outputSerialization(tx: DecodedTransaction): ByteArray {
        val out = ByteArrayOutputStream()
        tx.outputs.forEach { each ->
            out.write(uint64Le(each.satoshis))
            out.write(compactString(each.scriptPubKey))
        }
        return out.toByteArray()
    }

    /** Every spent output's value, 8-byte LE each. */
    private fun amountsSerialization(spentOutputs: List<WitnessUtxo>): ByteArray {
        val out = ByteArrayOutputStream()
        spentOutputs.forEach { each -> out.write(uint64Le(each.satoshis)) }
        return out.toByteArray()
    }

    /** Every spent output's scriptPubKey, compact-size prefixed. */
    private fun scriptsSerialization(spentOutputs: List<WitnessUtxo>): ByteArray {
        val out = ByteArrayOutputStream()
        spentOutputs.forEach { each -> out.write(compactString(each.scriptPubKey)) }
        return out.toByteArray()
    }

    /** A byte string with Bitcoin's compact-size length prefix. */
    private fun compactString(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            writeCompactSize(bytes.size.toLong())
            write(bytes)
        }.toByteArray()

    /** BIP340's tagged hash. */
    private fun taggedHash(tag: String, message: ByteArray): ByteArray =
        Hashes.sha256(tag.toByteArray(Charsets.US_ASCII)).let { tagHash ->
            Hashes.sha256(tagHash + tagHash + message)
        }

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

    /**
     * The locktime as the unified message carries it: **five** bytes little
     * endian, zero-extended. Four run out on 2106-02-07, and a later hardfork
     * that widened the field would otherwise have to change this message.
     */
    private fun uint40Le(value: Long): ByteArray = ByteArray(5) { i -> ((value ushr (8 * i)) and 0xFF).toByte() }
}
