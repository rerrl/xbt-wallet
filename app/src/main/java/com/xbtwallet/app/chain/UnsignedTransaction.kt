package com.xbtwallet.app.chain

import com.xbtwallet.app.crypto.Bech32
import com.xbtwallet.app.crypto.Hashes

/**
 * Building the unsigned transaction and planning what a send costs.
 *
 * This is the piece between the [SignerChainState]'s snapshot and the PSBT: it
 * turns "these coins, this recipient, this amount, this fee rate" into the
 * transaction bytes a signer will sign, and it decides what the change output
 * is — or that there is not one.
 *
 * **Android-free**, like everything in `chain/`: the fee arithmetic, the
 * transaction serialization and the dust rule are all plain functions, so the
 * whole plan is unit-tested on the JVM. The one thing it does reach for is the
 * `crypto/` package's [Bech32], to turn a recipient address into the
 * scriptPubKey a transaction output carries.
 *
 * **Fee estimation is exact, and rounded the way the network rounds.** Every
 * input this wallet spends is P2WPKH, so a spend's weight is known without
 * knowing anything about the actual UTXO: [exactSendVsize] derives the size from
 * the transaction's real structure and rounds the result **up** to whole virtual
 * bytes, which is how a node counts them and therefore how the minimum relay fee
 * is computed. The planner uses it with the outputs it is actually building, so
 * the fee it charges is the fee that transaction needs — not a truncated
 * approximation of it.
 *
 * The Send form's preview is a *different* question — "what is the most this
 * send could cost?" — and it answers it with the worst-case output shape
 * ([estimateSendVsize]), so the amount a user is allowed to type can never be
 * more than the planner can pay for.
 */

/**
 * One output's worth of dust, in satoshis: 546 is the classic P2WPKH dust
 * threshold, and it is a **threshold, not a target** — change below it is not
 * worth an output, because the output would cost more to spend than it holds.
 * When change is under it the change output is dropped and the remainder simply
 * joins the fee, which is what every wallet does and what the summary then
 * reports honestly as the fee.
 */
const val DUST_THRESHOLD_SATOSHIS: Long = 546L

/** One input of the transaction being built: an outpoint, and the script it spends. */
class SendInput(
    /** The previous transaction's id, in **display** (big-endian) hex — as the chain reports it. */
    val txidHex: String,
    val vout: Int,
    val satoshis: Long,
    /** The scriptPubKey of the output being spent, needed by the PSBT's witness_utxo. */
    val scriptPubKey: ByteArray,
)

/**
 * What a send resolves to: the inputs, the outputs (the recipient's, and the
 * change output when there is one), and the numbers the summary shows.
 *
 * [feeSatoshis] is the **actual** fee, after the dust rule has folded any tiny
 * change into it — so a plan whose change was dropped reports a fee larger than
 * the estimate, which is the truth and not an error.
 */
class SendPlan(
    val inputs: List<SendInput>,
    val outputs: List<TxOutput>,
    val feeSatoshis: Long,
    val amountSatoshis: Long,
    val changeSatoshis: Long,
) {
    /** True when there is a change output paying back to this wallet. */
    val hasChange: Boolean get() = changeSatoshis > 0
}

/**
 * Plans a send: the fee, the change, and whether there is a change output at all.
 *
 * @param inputs the selected coins, each with the script it sits at.
 * @param amountSatoshis what the recipient receives.
 * @param feeRateSatPerVb the fee rate from the form, in whole sat/vB.
 * @param recipientScriptPubKey the recipient address as a script (v0 or v1).
 * @param changeScriptPubKey this wallet's own change address as a script.
 */
fun planSend(
    inputs: List<SendInput>,
    amountSatoshis: Long,
    feeRateSatPerVb: Long,
    recipientScriptPubKey: ByteArray,
    changeScriptPubKey: ByteArray,
): SendPlan {
    require(inputs.isNotEmpty()) { "a send spends at least one input" }
    require(amountSatoshis > 0) { "an amount of at least 1 sat is required" }
    require(feeRateSatPerVb >= 1) { "a fee rate of at least 1 sat/vB is required" }

    val total = inputs.sumOf { it.satoshis }

    // Price the send as it will actually be built: the recipient output, and
    // provisionally a change output. The change output pays for its own bytes
    // (well, for its own share of them) — and if the remainder cannot cover
    // them it is dropped just below, and the plan reports what it really pays.
    val feeWithChange = exactSendVsize(
        inputCount = inputs.size,
        outputScriptSizes = listOf(recipientScriptPubKey.size, changeScriptPubKey.size),
    ).toLong() * feeRateSatPerVb
    val change = total - amountSatoshis - feeWithChange

    val outputs = mutableListOf(TxOutput(amountSatoshis, Scripthash.toHex(recipientScriptPubKey)))
    return if (change >= DUST_THRESHOLD_SATOSHIS) {
        outputs.add(TxOutput(change, Scripthash.toHex(changeScriptPubKey)))
        SendPlan(inputs, outputs, feeWithChange, amountSatoshis, change)
    } else {
        // The change was too small to be worth an output: it joins the fee. The
        // plan reports the fee it actually pays, not the one it estimated.
        SendPlan(inputs, outputs, total - amountSatoshis, amountSatoshis, 0)
    }
}

/**
 * The size, in virtual bytes, of a send that spends [inputCount] P2WPKH inputs
 * and carries one output per entry of [outputScriptSizes], each entry the size
 * of that output's scriptPubKey — 22 for P2WPKH, 34 for P2TR.
 *
 * Derived from the transaction's actual structure rather than a per-input
 * constant, because the per-input constant is what got this wrong: `10 + 68·n +
 * 62` says a 1-in/2-out P2WPKH send is 140 vB when it is 140.75, and a node
 * rounds that up to 141 for the minimum relay fee. Paying 140 for a 141-vB
 * transaction is a "min relay fee not met, 140 < 141" rejection.
 *
 * The arithmetic mirrors [buildUnsignedTransaction]: the base (non-witness)
 * bytes are the version, the input and output counts, 41 bytes per input (a
 * 32-byte outpoint, a 4-byte index, an empty scriptSig's zero length, a 4-byte
 * sequence), then each output's 8-byte value, script length and script, then the
 * 4-byte locktime. The witness is the marker and flag, then per input a stack
 * with a signature and a public key.
 *
 * The signature is **sized at the 73-byte worst case** rather than the 71-72 a
 * low-R signature usually comes to. It is the same number every time and it is
 * an upper bound: an attacker cannot grind a signature to make the transaction
 * larger than this, so the fee is never short. (A transaction whose real sig is
 * 72 bytes is one vB smaller than this — the surplus sat is the price of not
 * having to guess.)
 *
 * The result is **ceiling-divided**: virtual bytes are `ceil(weight / 4)`
 * because a node's `GetVirtualTransactionSize` rounds up, and the fee must be
 * computed on the same integer the node checks against.
 */
fun exactSendVsize(inputCount: Int, outputScriptSizes: List<Int>): Int {
    require(inputCount >= 0) { "an input count cannot be negative" }

    val base = 4 +
        varintLen(inputCount) +
        41 * inputCount +
        varintLen(outputScriptSizes.size) +
        outputScriptSizes.sumOf { scriptSize -> 8 + varintLen(scriptSize) + scriptSize } +
        4

    // marker (1) + flag (1), then per input: one stack item for the signature
    // (length prefix + 73 bytes) and one for the public key (prefix + 33 bytes),
    // preceded by the stack size varint.
    val witness = 2 + inputCount * (1 + (1 + 73) + (1 + 33))

    // ceil(weight / 4): Bitcoin Core's GetVirtualTransactionSize rounds up, and
    // so must we, or the fee is computed against a smaller number than the
    // network will.
    return (4 * base + witness + 3) / 4
}

/**
 * The **worst-case** size, in virtual bytes, of a send that spends [inputCount]
 * P2WPKH inputs and carries two outputs: the recipient's and the change.
 *
 * This is the Send form's preview, not the planner's number — the planner knows
 * its actual outputs and uses [exactSendVsize]. The form does not yet: it shows
 * this before an address has been resolved to a script, so it must assume the
 * largest output this wallet can build for. That is a taproot output — an
 * 8-byte value, a 1-byte length and a 34-byte scriptPubKey, 43 bytes — and
 * assuming two of them can only over-estimate. Over-estimating costs the user a
 * little headroom; under-estimating would let "Max" promise a send the planner
 * cannot pay for.
 *
 * Lives here — rather than beside the Send form that displays it — because the
 * form's "Max" and the planner must be reasoned about together, and `chain/`
 * must not reach up into `ui/`.
 */
fun estimateSendVsize(inputCount: Int): Int = exactSendVsize(inputCount, WORST_CASE_OUTPUT_SCRIPT_SIZES)

/**
 * The scriptPubKey sizes of the two most expensive outputs this wallet can
 * build: [P2TR_SCRIPT_SIZE] twice — 43-byte outputs, the largest any accepted
 * address produces. Every other shape the Send form accepts is 31 bytes.
 */
private val WORST_CASE_OUTPUT_SCRIPT_SIZES = listOf(P2TR_SCRIPT_SIZE, P2TR_SCRIPT_SIZE)

/** A P2TR scriptPubKey: `OP_1 PUSH32 <32-byte program>`. */
private const val P2TR_SCRIPT_SIZE = 34

/**
 * The number of bytes a Bitcoin CompactSize integer takes: 1 below 0xFD, 3 up to
 * 0xFFFF, 5 above — the same rule [writeVarInt] serialises.
 */
private fun varintLen(value: Int): Int = when {
    value < 0xFD -> 1
    value <= 0xFFFF -> 3
    else -> 5
}

/**
 * Serialises the unsigned transaction: version 2, every sequence final, locktime
 * 0 — the shape a hardware signer expects from a normal payment.
 *
 * The input and output types are the same [TxInput]/[TxOutput] the transaction
 * *parser* produces (see `chain/TransactionParsing.kt`) — a transaction the
 * wallet builds and a transaction it reads are the same shape of thing, so they
 * share one vocabulary rather than a second set of near-identical classes.
 *
 * **Outpoint ids are byte-reversed.** The chain (and the server, and the user's
 * screen) reports a txid in display order, which is the reverse of the order a
 * transaction serialises it in. Getting that backwards is the classic
 * "signed, broadcast, rejected" bug, so it happens exactly once, here.
 */
fun buildUnsignedTransaction(inputs: List<TxInput>, outputs: List<TxOutput>): ByteArray {
    require(inputs.isNotEmpty()) { "a transaction spends at least one input" }
    require(outputs.isNotEmpty()) { "a transaction pays at least one output" }

    val out = java.io.ByteArrayOutputStream()
    out.writeUint32Le(TRANSACTION_VERSION)
    out.writeVarInt(inputs.size)
    inputs.forEach { input ->
        val txid = hexToBytes(input.previousTxHash)
        require(txid.size == 32) { "a txid is 32 bytes, got ${txid.size}" }
        out.write(txid.reversedArray())
        out.writeUint32Le(input.previousOutputIndex.toLong())
        // No scriptSig: this is a segwit spend, and the scriptSig stays empty.
        out.writeVarInt(0)
        out.writeUint32Le(SEQUENCE_FINAL)
    }
    out.writeVarInt(outputs.size)
    outputs.forEach { output ->
        require(output.satoshis > 0) { "an output must pay at least 1 sat" }
        val script = hexToBytes(output.scriptPubKeyHex)
        out.writeUint64Le(output.satoshis)
        out.writeVarInt(script.size)
        out.write(script)
    }
    out.writeUint32Le(0L)
    return out.toByteArray()
}

/** Serialises the transaction a [plan] resolved to. */
fun buildUnsignedTransaction(plan: SendPlan): ByteArray =
    buildUnsignedTransaction(
        plan.inputs.map { TxInput(it.txidHex, it.vout) },
        plan.outputs,
    )

/**
 * The scriptPubKey an address pays to — the script an output carrying that
 * address must hold.
 *
 * The recipient may be taproot (v1), so this handles both shapes the Send form
 * accepts: version 0 (20-byte P2WPKH, 32-byte P2WSH) is `OP_0 …`, and versions
 * 1..16 are `OP_1..OP_16 …` as a bech32m program. The witness version becomes
 * the opcode (`0x00`, or `0x50 + version`) and the program is pushed verbatim.
 *
 * @throws com.xbtwallet.app.crypto.Bech32Exception when the address is not a
 *   valid bech32/bech32m string.
 */
fun scriptPubKeyFromAddress(address: String): ByteArray {
    val decoded = Bech32.decode(address.trim())
    require(decoded.data.isNotEmpty()) { "an address carries a witness version" }
    val witnessVersion = decoded.data[0].toInt() and 0xFF
    val program = Bech32.convertBits(decoded.data.copyOfRange(1, decoded.data.size), 5, 8, false)

    val opcode = when (witnessVersion) {
        0 -> 0x00
        in 1..16 -> 0x50 + witnessVersion
        else -> throw IllegalArgumentException("witness version $witnessVersion is outside 0..16")
    }
    require(program.size in 2..40) { "a witness program is 2..40 bytes, got ${program.size}" }
    return byteArrayOf(opcode.toByte(), program.size.toByte()) + program
}

/**
 * The P2WPKH scriptPubKey for a compressed public key: `OP_0 PUSH20 HASH160(key)`.
 *
 * This wallet's own outputs are always native segwit, so a change address — and
 * the witness_utxo of every coin being spent — is built from the key that will
 * sign for it, never from a string.
 */
fun p2wpkhScriptPubKey(pubKey: ByteArray): ByteArray =
    Scripthash.p2wpkhScript(Hashes.hash160(pubKey))

/** BIP-68's "final" sequence: no relative locktime, no opt-in RBF. */
private const val SEQUENCE_FINAL: Long = 0xFFFFFFFFL

/** The transaction version this wallet builds. */
private const val TRANSACTION_VERSION: Long = 2L

private fun java.io.ByteArrayOutputStream.writeUint32Le(value: Long) {
    for (i in 0 until 4) write(((value ushr (8 * i)) and 0xFF).toInt())
}

private fun java.io.ByteArrayOutputStream.writeUint64Le(value: Long) {
    for (i in 0 until 8) write(((value ushr (8 * i)) and 0xFF).toInt())
}

private fun java.io.ByteArrayOutputStream.writeVarInt(value: Int) {
    when {
        value < 0xFD -> write(value)
        value <= 0xFFFF -> {
            write(0xFD); write(value and 0xFF); write((value ushr 8) and 0xFF)
        }

        else -> {
            write(0xFE)
            for (i in 0 until 4) write((value ushr (8 * i)) and 0xFF)
        }
    }
}

/** Lowercase hex → bytes; the reverse of [Scripthash.toHex]. */
internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex must have an even number of digits" }
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val high = Character.digit(hex[i * 2], 16)
        val low = Character.digit(hex[i * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "not a hex string: $hex" }
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}
