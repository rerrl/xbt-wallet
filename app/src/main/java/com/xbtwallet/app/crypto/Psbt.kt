package com.xbtwallet.app.crypto

import java.io.ByteArrayOutputStream

/**
 * BIP-174 v0 PSBT: the format the wallet hands to a signer and takes back.
 *
 * A PSBT is a set of key-value maps — one global map carrying the unsigned
 * transaction, one per input, one per output — serialised as
 * `magic || global || inputs… || outputs…`, with a zero-length key as the
 * terminator of each map. This file implements exactly the subset the wallet
 * needs, from the specification:
 *
 * - **global** `0x00` — the unsigned transaction, as raw bytes;
 * - **per input** `0x01` witness_utxo (the amount as **8 little-endian bytes**,
 *   then the scriptPubKey's compact-size length and the script), `0x03` sighash
 *   type (uint32 LE), `0x06` BIP32 derivation (key = `0x06 || <33-byte pubkey>`,
 *   value = 4-byte fingerprint then the absolute path as uint32 LE per step),
 *   `0x02` partial signature (key = `0x02 || <pubkey>`, value = the signature);
 * - **per output** — nothing yet (the map is just its terminator).
 *
 * Those encodings are the part of this file that a test can only pin by *bytes*:
 * a field written and read the same wrong way round-trips perfectly, so the
 * tests carry BIP-174's own vectors and assert the extracted values, not just
 * `parse(build(x)) == x`.
 *
 * **The one field this wallet stamps on every input it builds is the sighash
 * byte `0x21`** — `SIGHASH_ALL | SIGHASH_UNIFIED` — and [finalizePsbt] refuses
 * any signature that does not carry it. That is XBT's replay protection: an
 * un-unified signature is exactly the thing the flag exists to prevent, so a
 * signed PSBT coming back from a signer with `0x01` on it is rejected rather
 * than broadcast (PLAN.md §5, §8.1 of this iteration's card).
 *
 * **Android-free on purpose.** Everything here is byte arithmetic over the
 * app's own [Hashes], so a whole build → parse → finalize cycle is a JVM unit
 * test with no device, no signer and no socket.
 */

/** Raised when a PSBT cannot be built, read, or finalized. */
class PsbtException(message: String) : IllegalArgumentException(message)

/** BIP-174's magic prefix, `psbt\xff`. */
private val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())

/**
 * `SIGHASH_ALL | SIGHASH_UNIFIED` — the hashtype byte every signature this
 * wallet accepts must end with. See the file doc comment.
 */
const val SIGHASH_UNIFIED: Long = 0x21L

/** A `witness_utxo`: the amount and the scriptPubKey of the output being spent. */
class WitnessUtxo(
    val satoshis: Long,
    val scriptPubKey: ByteArray,
)

/** A `PSBT_IN_BIP32_DERIVATION`: the master fingerprint and the absolute path. */
class Bip32Derivation(
    val masterFingerprint: ByteArray,
    val path: List<Long>,
)

/** A `PSBT_IN_PARTIAL_SIG`: the signing key and its signature (with the sighash byte). */
class PartialSig(
    val pubKey: ByteArray,
    val signature: ByteArray,
)

/** One raw key-value of a PSBT map, preserved as read so a round-trip is byte-exact. */
class PsbtKeyValue(
    val key: ByteArray,
    val value: ByteArray,
)

/**
 * What the **builder** needs for one input — the fields a signer requires to
 * produce a signature and that the wallet can supply from its own derivation:
 * the output being spent, the BIP32 origin of the key, and the key itself.
 */
class PsbtInput(
    val witnessUtxoScriptPubKey: ByteArray,
    val witnessUtxoSatoshis: Long,
    /** The master key fingerprint, four bytes (the first four of the key's HASH160). */
    val masterFingerprint: ByteArray,
    /** The absolute derivation path, each step with the hardened flag in bit 31. */
    val derivationPath: List<Long>,
    /** The 33-byte compressed public key this input's signature will come from. */
    val pubKey: ByteArray,
)

/** One parsed input map, with the field types the finalizer works in. */
class ParsedPsbtInput(
    val keyValues: List<PsbtKeyValue>,
    val witnessUtxo: WitnessUtxo?,
    val sighashType: Long?,
    /** Every `0x06` in the map, in the order read — an input may name several keys. */
    val derivations: List<Bip32Derivation>,
    val partialSigs: List<PartialSig>,
)

/**
 * A parsed PSBT.
 *
 * [globalKeyValues], [inputs] and [outputKeyValues] keep the maps **as they were
 * read**, in order, so re-serialising an untouched PSBT reproduces the original
 * bytes exactly — which is what makes a spec-vector round-trip a real test.
 */
class Psbt(
    val unsignedTxBytes: ByteArray,
    val globalKeyValues: List<PsbtKeyValue>,
    val inputs: List<ParsedPsbtInput>,
    val outputKeyValues: List<List<PsbtKeyValue>>,
) {

    /** The parsed unsigned transaction, for the input/output counts and finalizing. */
    val transaction: DecodedTransaction get() = decodeTransaction(unsignedTxBytes)

    /** The PSBT back as bytes: magic, then each map in the order it was read. */
    fun toBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PSBT_MAGIC)
        writeMap(out, globalKeyValues)
        inputs.forEach { writeMap(out, it.keyValues) }
        outputKeyValues.forEach { writeMap(out, it) }
        return out.toByteArray()
    }
}

/**
 * Builds an unsigned, ready-to-sign PSBT for [unsignedTxBytes].
 *
 * The transaction bytes are embedded verbatim under the global `0x00` key; each
 * input carries its witness_utxo, its sighash type (`0x21`, always) and its
 * BIP32 derivation. The output maps are empty — the wallet requests no output
 * metadata — but one terminator per transaction output is written, because
 * BIP-174 requires the count to match.
 *
 * A PSBT that says nothing about an output is fine: this wallet's outputs are
 * paid to a scriptPubKey it already knows, and asking the signer to echo it back
 * would add bytes to the QR without adding safety. What matters is that the
 * **inputs** are complete, and they are: every one carries the full witness_utxo
 * the signer must verify against, the derivation path of the key that may sign,
 * and the sighash request.
 */
fun buildUnsignedPsbt(unsignedTxBytes: ByteArray, inputs: List<PsbtInput>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(PSBT_MAGIC)

    // --- global map ---
    writeKeyValue(out, byteArrayOf(0x00), unsignedTxBytes)
    out.write(0x00)

    // --- one input map per input ---
    inputs.forEach { input ->
        require(input.masterFingerprint.size == 4) {
            "a master fingerprint is four bytes, got ${input.masterFingerprint.size}"
        }
        require(input.pubKey.size == 33) {
            "a compressed public key is 33 bytes, got ${input.pubKey.size}"
        }

        // 0x01 witness_utxo: the amount as 8 little-endian bytes, then the
        // scriptPubKey's compact-size length and the script. BIP-174 defines the
        // amount as a 64-bit integer — a compact size here (which is what this
        // wallet used to write) is not what any other implementation reads.
        val witnessUtxo = ByteArrayOutputStream().apply {
            write(uint64Le(input.witnessUtxoSatoshis))
            writeCompactSize(input.witnessUtxoScriptPubKey.size.toLong())
            write(input.witnessUtxoScriptPubKey)
        }.toByteArray()
        writeKeyValue(out, byteArrayOf(0x01), witnessUtxo)

        // 0x03 sighash type: the unified hashtype, uint32 little-endian.
        writeKeyValue(out, byteArrayOf(0x03), uint32Le(SIGHASH_UNIFIED))

        // 0x06 BIP32 derivation: fingerprint then the absolute path.
        val derivation = ByteArrayOutputStream().apply {
            write(input.masterFingerprint)
            input.derivationPath.forEach { step -> write(uint32Le(step)) }
        }.toByteArray()
        writeKeyValue(out, byteArrayOf(0x06) + input.pubKey, derivation)

        out.write(0x00)
    }

    // --- one (empty) output map per transaction output ---
    val outputCount = decodeTransaction(unsignedTxBytes).outputs.size
    repeat(outputCount) { out.write(0x00) }

    return out.toByteArray()
}

/**
 * Parses a PSBT, keeping every key-value as read.
 *
 * Errors are specific and user-visible-worthy: a missing magic, a truncated
 * map, and a missing global unsigned transaction each get their own message
 * rather than one generic "unreadable".
 */
fun parsePsbt(bytes: ByteArray): Psbt {
    if (bytes.size < PSBT_MAGIC.size || !bytes.copyOfRange(0, PSBT_MAGIC.size).contentEquals(PSBT_MAGIC)) {
        throw PsbtException("this is not a PSBT — it does not begin with the psbt magic")
    }

    val reader = PsbtReader(bytes, PSBT_MAGIC.size)
    val global = reader.readMap()

    val unsignedTx = global.firstOrNull { it.key.size == 1 && it.key[0] == 0x00.toByte() }?.value
        ?: throw PsbtException("the PSBT has no unsigned transaction")

    // The transaction's own shape is what says how many input and output maps
    // must follow; a PSBT whose maps disagree with its transaction is malformed.
    val decoded = decodeTransaction(unsignedTx)

    val inputs = (0 until decoded.inputs.size).map { readInput(reader.readMap()) }
    val outputs = (0 until decoded.outputs.size).map { reader.readMap() }

    return Psbt(
        unsignedTxBytes = unsignedTx,
        globalKeyValues = global,
        inputs = inputs,
        outputKeyValues = outputs,
    )
}

/**
 * Turns one input map into the typed view the finalizer reads.
 *
 * Each type is decoded to the shape BIP-174 defines for it, and a field whose
 * bytes do not match that shape is refused rather than reinterpreted — a value
 * this wallet cannot read exactly is a value it must not sign against:
 *
 * - `0x01` witness_utxo — `<64-bit LE amount> <compact size script len> <script>`,
 *   no key data;
 * - `0x03` sighash type — a 32-bit LE uint, no key data. (Not a compact size: the
 *   amount and the sighash are exactly the pair this wallet could confuse.)
 * - `0x06` BIP32 derivation — key `0x06 || <pubkey>`, value `<4-byte
 *   fingerprint> <32-bit LE path element>*`;
 * - `0x02` partial signature — key `0x02 || <pubkey>`, value the signature
 *   bytes, which stay opaque because nothing here interprets a signature.
 */
private fun readInput(keyValues: List<PsbtKeyValue>): ParsedPsbtInput {
    var witnessUtxo: WitnessUtxo? = null
    var sighashType: Long? = null
    val derivations = mutableListOf<Bip32Derivation>()
    val partialSigs = mutableListOf<PartialSig>()

    keyValues.forEach { kv ->
        if (kv.key.isEmpty()) return@forEach
        when (kv.key[0]) {
            0x01.toByte() -> {
                requireNoKeyData(kv, "the witness_utxo key (PSBT_IN_WITNESS_UTXO)")

                val reader = PsbtReader(kv.value, 0)
                // The amount is eight bytes, little-endian — always eight, whatever
                // its value. Reading it as a compact size is the bug this pins.
                val amount = reader.readUint64Le()
                val scriptLength = reader.readCompactSize()
                if (scriptLength > reader.remaining) {
                    throw PsbtException(
                        "the witness_utxo says its scriptPubKey is $scriptLength bytes but the " +
                            "field holds ${reader.remaining}",
                    )
                }
                val script = reader.readBytes(scriptLength.toInt())
                if (reader.remaining != 0) {
                    throw PsbtException(
                        "the witness_utxo has ${reader.remaining} bytes after its scriptPubKey",
                    )
                }
                witnessUtxo = WitnessUtxo(amount, script)
            }

            0x03.toByte() -> {
                requireNoKeyData(kv, "the sighash type key (PSBT_IN_SIGHASH_TYPE)")
                if (kv.value.size != 4) {
                    throw PsbtException(
                        "the input's sighash type is ${kv.value.size} bytes; BIP-174's is a 32-bit uint",
                    )
                }
                sighashType = readUint32Le(kv.value)
            }

            0x06.toByte() -> {
                // key = 0x06 || pubkey
                if (kv.key.size <= 1) {
                    throw PsbtException("a BIP32 derivation key must carry the public key")
                }
                val value = kv.value
                if (value.size < 4 || (value.size - 4) % 4 != 0) {
                    throw PsbtException(
                        "a BIP32 derivation is a 4-byte fingerprint plus whole 32-bit path " +
                            "steps; this one is ${value.size} bytes",
                    )
                }
                val path = ArrayList<Long>((value.size - 4) / 4)
                var i = 4
                while (i + 4 <= value.size) {
                    path.add(readUint32Le(value.copyOfRange(i, i + 4)))
                    i += 4
                }
                derivations.add(Bip32Derivation(value.copyOfRange(0, 4), path))
            }

            0x02.toByte() -> {
                // key = 0x02 || pubkey, value = signature
                if (kv.key.size <= 1) {
                    throw PsbtException("a partial signature key must carry the public key")
                }
                partialSigs.add(PartialSig(kv.key.copyOfRange(1, kv.key.size), kv.value))
            }
        }
    }

    return ParsedPsbtInput(keyValues, witnessUtxo, sighashType, derivations, partialSigs)
}

/** BIP-174 gives these key types no key data at all, so the key is the type byte. */
private fun requireNoKeyData(kv: PsbtKeyValue, field: String) {
    if (kv.key.size != 1) {
        throw PsbtException(
            "$field takes no key data, but this map's key carries ${kv.key.size - 1} bytes of it",
        )
    }
}

/**
 * Extracts the final, signed transaction from a signed PSBT.
 *
 * One partial signature per input, P2WPKH single-sig — the only shape this
 * wallet builds. The witness stack is `[signature, publicKey]` and the
 * scriptSig stays empty.
 *
 * **Refusals, in the order they matter:**
 *
 * 1. an input with **no** partial signature — signing is not finished;
 * 2. an input with **more than one** — a single-sig input cannot honestly choose,
 *    and picking one would be guessing;
 * 3. a signature whose last byte is **not `0x21`** — the replay-protection rule.
 *    The last byte of a signature is the hashtype; anything else is un-unified;
 * 4. an input whose `0x03` sighash key says something other than `0x21`.
 *
 * @return the network-serialized (segwit) transaction, witness attached.
 */
fun finalizePsbt(psbt: Psbt): ByteArray {
    val tx = decodeTransaction(psbt.unsignedTxBytes)
    if (tx.inputs.size != psbt.inputs.size) {
        throw PsbtException("the PSBT has ${psbt.inputs.size} input maps but its transaction has ${tx.inputs.size} inputs")
    }

    val witnesses = psbt.inputs.mapIndexed { index, input ->
        val sigs = input.partialSigs
        if (sigs.isEmpty()) {
            throw PsbtException("input $index has no signature — the signer did not sign it")
        }
        if (sigs.size > 1) {
            throw PsbtException("input $index has more than one signature — refusing to choose one")
        }
        val sig = sigs[0]
        val sighashByte = sig.signature.lastOrNull()?.toInt()?.and(0xFF)
        if (sighashByte != SIGHASH_UNIFIED.toInt()) {
            throw PsbtException(
                "input $index's signature does not carry the unified sighash byte (0x21) — " +
                    "it is not replay-protected, so this wallet will not broadcast it",
            )
        }
        val declared = input.sighashType
        if (declared != null && declared != SIGHASH_UNIFIED) {
            throw PsbtException("input $index declares sighash 0x${declared.toString(16)}, not the unified 0x21")
        }
        listOf(sig.signature, sig.pubKey)
    }

    return serializeSignedTransaction(tx, witnesses)
}

/**
 * The transaction id of a serialized transaction: double-SHA256 of the
 * **non-witness** serialization, reversed, as lowercase hex.
 *
 * The witness is stripped before hashing, which is what makes this the value a
 * block explorer and the Electrum server both call the txid. It works on a
 * signed (segwit) or unsigned transaction, so the finalizer's output and the
 * transaction the coordinator built can be compared by id.
 */
fun transactionId(rawTransaction: ByteArray): String {
    val tx = decodeTransaction(rawTransaction)
    val digest = Hashes.sha256d(serializeNonWitness(tx))
    val hex = StringBuilder(digest.size * 2)
    for (i in digest.indices.reversed()) {
        val value = digest[i].toInt() and 0xFF
        hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
    }
    return hex.toString()
}

// ---------------------------------------------------------------------------
// The transaction model the PSBT carries
// ---------------------------------------------------------------------------

/** One transaction input, as parsed. */
class DecodedTransactionInput(
    /** The previous transaction's id, in **internal** (little-endian) byte order. */
    val previousTxid: ByteArray,
    val outputIndex: Long,
    val scriptSig: ByteArray,
    val sequence: Long,
)

/** One transaction output. */
class DecodedTransactionOutput(
    val satoshis: Long,
    val scriptPubKey: ByteArray,
)

/** A transaction, parsed: enough of it to re-serialize either way. */
class DecodedTransaction(
    val version: Long,
    val inputs: List<DecodedTransactionInput>,
    val outputs: List<DecodedTransactionOutput>,
    val lockTime: Long,
    val segwit: Boolean,
    val witnesses: List<List<ByteArray>>,
)

/** Parses a legacy or segwit transaction from its bytes. */
fun decodeTransaction(bytes: ByteArray): DecodedTransaction {
    val reader = PsbtReader(bytes, 0)
    val version = reader.readUint32Le()

    var segwit = false
    if (reader.peek() == 0x00 && reader.peek(1) != 0x00) {
        segwit = true
        reader.skip(2)
    }

    val inputCount = reader.readCompactSize().toInt()
    val inputs = (0 until inputCount).map {
        val txid = reader.readBytes(32)
        val vout = reader.readUint32Le()
        val scriptSig = reader.readVarBytes()
        val sequence = reader.readUint32Le()
        DecodedTransactionInput(txid, vout, scriptSig, sequence)
    }

    val outputCount = reader.readCompactSize().toInt()
    val outputs = (0 until outputCount).map {
        val satoshis = reader.readUint64Le()
        val script = reader.readVarBytes()
        DecodedTransactionOutput(satoshis, script)
    }

    val witnesses = if (segwit) {
        (0 until inputCount).map { reader.readWitnessStack() }
    } else {
        emptyList()
    }

    val lockTime = reader.readUint32Le()
    return DecodedTransaction(version, inputs, outputs, lockTime, segwit, witnesses)
}

/** The non-witness serialization — what a txid is hashed from. */
internal fun serializeNonWitness(tx: DecodedTransaction): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(uint32Le(tx.version))
    out.writeCompactSize(tx.inputs.size.toLong())
    tx.inputs.forEach { input ->
        out.write(input.previousTxid)
        out.write(uint32Le(input.outputIndex))
        out.writeCompactSize(input.scriptSig.size.toLong())
        out.write(input.scriptSig)
        out.write(uint32Le(input.sequence))
    }
    out.writeCompactSize(tx.outputs.size.toLong())
    tx.outputs.forEach { output ->
        out.write(uint64Le(output.satoshis))
        out.writeCompactSize(output.scriptPubKey.size.toLong())
        out.write(output.scriptPubKey)
    }
    out.write(uint32Le(tx.lockTime))
    return out.toByteArray()
}

/** The segwit serialization, with [witnesses] — one stack per input, in order. */
internal fun serializeSignedTransaction(tx: DecodedTransaction, witnesses: List<List<ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(uint32Le(tx.version))
    out.write(0x00)
    out.write(0x01)
    out.writeCompactSize(tx.inputs.size.toLong())
    tx.inputs.forEach { input ->
        out.write(input.previousTxid)
        out.write(uint32Le(input.outputIndex))
        out.writeCompactSize(input.scriptSig.size.toLong())
        out.write(input.scriptSig)
        out.write(uint32Le(input.sequence))
    }
    out.writeCompactSize(tx.outputs.size.toLong())
    tx.outputs.forEach { output ->
        out.write(uint64Le(output.satoshis))
        out.writeCompactSize(output.scriptPubKey.size.toLong())
        out.write(output.scriptPubKey)
    }
    witnesses.forEach { stack ->
        out.writeCompactSize(stack.size.toLong())
        stack.forEach { item ->
            out.writeCompactSize(item.size.toLong())
            out.write(item)
        }
    }
    out.write(uint32Le(tx.lockTime))
    return out.toByteArray()
}

// ---------------------------------------------------------------------------
// The wire helpers
// ---------------------------------------------------------------------------

private const val HEX = "0123456789abcdef"

/** Small big-endian-agnostic reader over a byte array. */
internal class PsbtReader(private val bytes: ByteArray, private var offset: Int) {

    fun peek(ahead: Int = 0): Int {
        val index = offset + ahead
        if (index >= bytes.size) throw PsbtException("the PSBT ended unexpectedly")
        return bytes[index].toInt() and 0xFF
    }

    fun skip(count: Int) {
        if (offset + count > bytes.size) throw PsbtException("the PSBT ended unexpectedly")
        offset += count
    }

    fun readBytes(count: Int): ByteArray {
        if (offset + count > bytes.size) throw PsbtException("the PSBT ended unexpectedly")
        val out = bytes.copyOfRange(offset, offset + count)
        offset += count
        return out
    }

    /** How many bytes are left, from the current offset. */
    val remaining: Int get() = bytes.size - offset

    fun readCompactSize(): Long = readCompactSize(this)

    fun readUint32Le(): Long {
        val b = readBytes(4)
        return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
    }

    fun readUint64Le(): Long {
        val b = readBytes(8)
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (b[i].toLong() and 0xFF)
        }
        return value
    }

    /** A compact-size-prefixed byte string. */
    fun readVarBytes(): ByteArray {
        val length = readCompactSize()
        if (length > Int.MAX_VALUE) throw PsbtException("a length in this PSBT is implausibly large")
        return readBytes(length.toInt())
    }

    /** One witness stack: a count then that many length-prefixed items. */
    fun readWitnessStack(): List<ByteArray> {
        val count = readCompactSize()
        if (count > Int.MAX_VALUE) throw PsbtException("a witness stack count is implausibly large")
        return (0 until count.toInt()).map { readVarBytes() }
    }

    /**
     * One PSBT map: key-values until a zero-length key. The terminator is
     * consumed; the key-values come back in the order they were read.
     */
    fun readMap(): List<PsbtKeyValue> {
        val entries = mutableListOf<PsbtKeyValue>()
        while (true) {
            val keyLength = readCompactSize()
            if (keyLength == 0L) return entries
            if (keyLength > Int.MAX_VALUE) throw PsbtException("a key length in this PSBT is implausibly large")
            val key = readBytes(keyLength.toInt())
            val value = readVarBytes()
            entries.add(PsbtKeyValue(key, value))
        }
    }
}

private fun readCompactSize(reader: PsbtReader): Long {
    val first = reader.peek()
    return when (first) {
        0xFD -> {
            reader.skip(1)
            val b = reader.readBytes(2)
            (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8)
        }

        0xFE -> {
            reader.skip(1)
            reader.readUint32Le()
        }

        0xFF -> {
            reader.skip(1)
            reader.readUint64Le()
        }

        else -> {
            reader.skip(1)
            first.toLong()
        }
    }
}

/** Writes one map: each key-value, then its zero-length terminator. */
private fun writeMap(out: ByteArrayOutputStream, entries: List<PsbtKeyValue>) {
    entries.forEach { entry -> writeKeyValue(out, entry.key, entry.value) }
    out.write(0x00)
}

private fun writeKeyValue(out: ByteArrayOutputStream, key: ByteArray, value: ByteArray) {
    out.writeCompactSize(key.size.toLong())
    out.write(key)
    out.writeCompactSize(value.size.toLong())
    out.write(value)
}

internal fun ByteArrayOutputStream.writeCompactSize(value: Long) {
    when {
        value < 0xFD -> write(value.toInt())
        value <= 0xFFFF -> {
            write(0xFD)
            write(value.toInt() and 0xFF)
            write((value.toInt() ushr 8) and 0xFF)
        }

        value <= 0xFFFFFFFFL -> {
            write(0xFE)
            for (i in 0 until 4) write(((value ushr (8 * i)) and 0xFF).toInt())
        }

        else -> {
            write(0xFF)
            for (i in 0 until 8) write(((value ushr (8 * i)) and 0xFF).toInt())
        }
    }
}

private fun uint32Le(value: Long): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value ushr 8) and 0xFF).toByte(),
    ((value ushr 16) and 0xFF).toByte(),
    ((value ushr 24) and 0xFF).toByte(),
)

private fun uint64Le(value: Long): ByteArray = ByteArray(8) { i -> ((value ushr (8 * i)) and 0xFF).toByte() }

/** Reads a 4-byte little-endian unsigned value. */
private fun readUint32Le(bytes: ByteArray): Long {
    require(bytes.size >= 4) { "a uint32 is four bytes, got ${bytes.size}" }
    return (bytes[0].toLong() and 0xFF) or ((bytes[1].toLong() and 0xFF) shl 8) or
        ((bytes[2].toLong() and 0xFF) shl 16) or ((bytes[3].toLong() and 0xFF) shl 24)
}
