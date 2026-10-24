package com.xbtwallet.app.chain

import com.xbtwallet.app.crypto.Base58Check
import com.xbtwallet.app.crypto.SegwitAddress

/**
 * One transaction as the detail screen reads it: the whole transaction parsed
 * once, its outputs labelled with an address where one can be rendered, its
 * inputs shown with a value only when that value is actually known.
 *
 * **Why this is a separate type from [TransactionRow].** A row is a summary
 * computed during the scan and persisted in the snapshot: net effect and
 * confirmation count, nothing else. The detail screen needs the structure — the
 * individual outputs and the outpoints an input spends — and the only way to get
 * that is to fetch the transaction itself. The row stays the cheap, cached
 * summary; this is the one-transaction view.
 *
 * **Nothing here is guessed.** An input's [TransactionDetailInput.satoshis] is
 * null unless the previous transaction's output value was supplied from the
 * wallet's own history or fetched on demand. The [feeSatoshis] is null unless
 * *every* input's value is known. A screen that showed "0" for an unknown fee
 * would be telling the user the transaction was free.
 *
 * **Addresses.** The wallet only ever creates P2WPKH scripts, but a transaction
 * can pay any standard script. [addressFromScriptPubKeyHex] renders the witness
 * programs and the two Base58 forms; anything else stays null and the screen
 * prints "Unknown script" rather than a fabricated address.
 */

/** One output of the transaction, as the detail screen lists it. */
data class TransactionDetailOutput(
    /**
     * The output's address, or null when its script is not a standard one this
     * wallet can render. Null is deliberate: "Unknown script" is a fact, a made
     * up address would not be.
     */
    val address: String?,
    val satoshis: Long,
    /** True when the scriptPubKey is one of this wallet's own scanned scripts. */
    val isOurs: Boolean,
)

/** One input of the transaction: the outpoint it spends and, when known, its value. */
data class TransactionDetailInput(
    val previousTxHash: String,
    val previousOutputIndex: Int,
    /**
     * The value of the output being spent, or null when the previous
     * transaction has not been read. Never a guess — the fee cannot be computed
     * from an unknown input, and neither can "you" be stamped on it.
     */
    val satoshis: Long?,
    /**
     * True when the spent outpoint belongs to this wallet: its parent
     * transaction is one the account's history already knows, or (after an
     * on-demand fetch) the parent's output at this index pays one of our
     * scripts.
     */
    val isOurs: Boolean,
) {
    /** `txid:vout`, the identifier a person can compare against another view. */
    val outpoint: String get() = "$previousTxHash:$previousOutputIndex"
}

/**
 * A value already known for a spent outpoint: what the on-demand fee fetch
 * learns about a parent transaction's output, or what the wallet's own history
 * already established.
 */
data class KnownInput(
    val satoshis: Long,
    /** True when the parent output's script is one of this wallet's scripts. */
    val isOurs: Boolean,
)

/**
 * Everything the transaction detail screen renders, fetched and parsed in one
 * pass over an already-open connection.
 *
 * [height] and [confirmations] are null only in the fallback case where the
 * transaction is not in the current snapshot and its height cannot be recovered
 * from the snapshot's address histories. A null there means "unknown", and the
 * screen says so; 0 means "in the mempool" and is a real answer.
 *
 * [netSatoshis] is the wallet's net effect when it can be computed honestly —
 * i.e. when every input that belongs to us has a known value — and null when it
 * cannot. The screen prefers the row's already-computed net amount when it has
 * one; this field exists for the fresh-lookup fallback.
 */
data class TransactionDetail(
    val txHash: String,
    val height: Int?,
    val confirmations: Int?,
    val blockTimestampEpochSeconds: Long?,
    val version: Int,
    val lockTime: Long,
    val vsize: Long,
    val weight: Long,
    val outputs: List<TransactionDetailOutput>,
    val inputs: List<TransactionDetailInput>,
    val feeSatoshis: Long?,
    val netSatoshis: Long?,
) {
    /** True when the transaction is mined; null when its height is unknown. */
    val confirmed: Boolean? get() = height?.let { it > 0 }

    val inputCount: Int get() = inputs.size
    val outputCount: Int get() = outputs.size

    /** The fee in satoshis per virtual byte, or null when the fee is unknown. */
    val feeRateSatPerVb: Double?
        get() {
            val fee = feeSatoshis ?: return null
            if (vsize <= 0) return null
            return fee.toDouble() / vsize.toDouble()
        }

    /**
     * Fills in input values learned after the initial load — the on-demand fee
     * fetch — and recomputes the fee and net effect from them.
     *
     * A value supplied for an outpoint replaces the input's unknown value and
     * sets its "ours" flag; an input whose outpoint is absent keeps its null.
     * The fee becomes known only when every input has a value after the merge,
     * which is the same "all or nothing" rule the initial load uses. No fetch
     * happens here: this is a pure re-projection of a detail already in hand.
     */
    fun withKnownInputs(known: Map<String, KnownInput>): TransactionDetail {
        val filled = inputs.map { input ->
            val value = known[input.outpoint]
            if (value == null) {
                input
            } else {
                input.copy(satoshis = value.satoshis, isOurs = input.isOurs || value.isOurs)
            }
        }
        return copy(
            inputs = filled,
            feeSatoshis = feeOrNull(filled, outputs),
            netSatoshis = netOrNull(filled, outputs),
        )
    }
}

/**
 * Fetches one transaction over [query], parses it, and labels its structure for
 * the detail screen.
 *
 * **One fetch.** [query.getTransaction] is called once, for the transaction
 * itself. No parent transaction is fetched here: an input's value stays null
 * unless the caller already knows it, and the screen offers an explicit "Load
 * fee" action for the rest. That is what keeps opening a detail screen cheap
 * over Tor.
 *
 * [knownHeight] and [knownBlockTimestampEpochSeconds] come from the row the
 * screen already has — the row was computed during the scan and persisted in
 * the snapshot, so the screen does not re-scan to learn where or when the
 * transaction was mined. [knownHeight] null means "not known"; 0 means "in the
 * mempool". When it is non-null and confirmed, the confirmation count is
 * recomputed from a fresh [ChainQuery.getTipHeight], because a snapshot's count
 * goes stale as blocks arrive.
 *
 * [knownInputValues] is an outpoint → value map for parent outputs the caller
 * already read. [walletTransactionIds] is the set of txids the account's own
 * history knows; an input whose parent is in that set is tagged as ours even
 * when its value is not yet known. Both default to empty, so the three-argument
 * call in the specification works as written.
 *
 * @throws IllegalArgumentException when the server's hex parses to a different
 *   txid than the one requested — a mismatch means the answer is not the
 *   transaction we asked for, and rendering it would be a lie.
 */
fun loadTransactionDetail(
    txHash: String,
    walletScripts: Set<String>,
    query: ChainQuery,
    knownHeight: Int? = null,
    knownBlockTimestampEpochSeconds: Long? = null,
    knownInputValues: Map<String, Long> = emptyMap(),
    walletTransactionIds: Set<String> = emptySet(),
): TransactionDetail {
    val parsed = TransactionParser.parse(query.getTransaction(txHash))
    require(parsed.txHash == txHash) {
        "server returned transaction ${parsed.txHash} for requested $txHash"
    }

    val confirmations = knownHeight?.let { height ->
        if (height <= 0) 0 else confirmationsFor(height, query.getTipHeight())
    }

    val outputs = parsed.outputs.map { output ->
        TransactionDetailOutput(
            address = addressFromScriptPubKeyHex(output.scriptPubKeyHex),
            satoshis = output.satoshis,
            isOurs = output.scriptPubKeyHex in walletScripts,
        )
    }

    val inputs = parsed.inputs.map { input ->
        TransactionDetailInput(
            previousTxHash = input.previousTxHash,
            previousOutputIndex = input.previousOutputIndex,
            satoshis = knownInputValues[input.outpoint],
            // A parent the account's history already lists is ours by the same
            // rule the scan's net arithmetic uses; anything else is somebody
            // else's until a parent fetch proves otherwise.
            isOurs = input.previousTxHash in walletTransactionIds,
        )
    }

    return TransactionDetail(
        txHash = parsed.txHash,
        height = knownHeight,
        confirmations = confirmations,
        blockTimestampEpochSeconds = knownBlockTimestampEpochSeconds,
        version = parsed.version,
        lockTime = parsed.lockTime,
        vsize = parsed.vsize,
        weight = parsed.weight,
        outputs = outputs,
        inputs = inputs,
        feeSatoshis = feeOrNull(inputs, outputs),
        netSatoshis = netOrNull(inputs, outputs),
    )
}

/**
 * Fetches the parent transactions whose output values this detail is missing,
 * and returns the values it learned — the work behind the screen's "Load fee"
 * action.
 *
 * **Sequential on purpose.** Every parent is one `blockchain.transaction.get`
 * over the same connection, and they are issued one at a time: a phone on a Tor
 * circuit does not benefit from a burst, and a cancelled screen must not leave
 * several calls in flight. [onProgress] is called after each parent returns,
 * with the number fetched so far and the total distinct parents needed, so the
 * screen can say "Fetching N parent transactions…" and count down.
 *
 * **All or nothing.** A parent that cannot be fetched or parsed throws: the
 * caller keeps its previous detail and shows the failure with a Retry. Partial
 * results are never returned, because a fee computed from some of the inputs is
 * a wrong fee wearing a right fee's clothes.
 *
 * Only inputs whose value is still null are resolved. An outpoint the detail
 * already knows is not fetched again — reopening the screen after a successful
 * fee load costs nothing.
 *
 * @return outpoint → [KnownInput] for every input that was missing a value.
 * @throws IllegalArgumentException when a parent transaction does not have the
 *   output index the spending input names.
 */
fun resolveUnknownInputValues(
    detail: TransactionDetail,
    walletScripts: Set<String>,
    query: ChainQuery,
    onProgress: (fetched: Int, total: Int) -> Unit = { _, _ -> },
): Map<String, KnownInput> {
    val missing = detail.inputs.filter { it.satoshis == null }
    if (missing.isEmpty()) return emptyMap()

    val parents = missing.map { it.previousTxHash }.distinct()
    val known = LinkedHashMap<String, KnownInput>()
    parents.forEachIndexed { index, parentTxHash ->
        val parent = TransactionParser.parse(query.getTransaction(parentTxHash))
        require(parent.txHash == parentTxHash) {
            "server returned transaction ${parent.txHash} for requested parent $parentTxHash"
        }
        for (input in missing.filter { it.previousTxHash == parentTxHash }) {
            val output = parent.outputs.getOrNull(input.previousOutputIndex)
                ?: throw IllegalArgumentException(
                    "parent $parentTxHash has no output ${input.previousOutputIndex}",
                )
            known[input.outpoint] = KnownInput(
                satoshis = output.satoshis,
                isOurs = output.scriptPubKeyHex in walletScripts,
            )
        }
        onProgress(index + 1, parents.size)
    }
    return known
}

/**
 * The fee, or null when any input's value is unknown.
 *
 * A transaction's fee is `sum(inputs) - sum(outputs)`; both sums are over the
 * whole transaction, not just the wallet's part. An unknown input makes the
 * difference unknowable, so the answer is null rather than a lower bound.
 */
internal fun feeOrNull(
    inputs: List<TransactionDetailInput>,
    outputs: List<TransactionDetailOutput>,
): Long? {
    if (inputs.isEmpty()) return null
    var totalIn = 0L
    for (input in inputs) totalIn += input.satoshis ?: return null
    val totalOut = outputs.sumOf { it.satoshis }
    return totalIn - totalOut
}

/**
 * The wallet's net effect, or null when it cannot be computed honestly.
 *
 * Incoming is every output paying one of our scripts; outgoing is every input
 * whose parent we know to be ours. If such an input's value is unknown the net
 * cannot be known — the honest answer is null, not "incoming only".
 */
internal fun netOrNull(
    inputs: List<TransactionDetailInput>,
    outputs: List<TransactionDetailOutput>,
): Long? {
    val incoming = outputs.sumOf { if (it.isOurs) it.satoshis else 0L }
    var outgoing = 0L
    for (input in inputs) {
        if (!input.isOurs) continue
        outgoing += input.satoshis ?: return null
    }
    return incoming - outgoing
}

/**
 * The address a scriptPubKey pays, or null when it is not a standard script
 * this wallet can render.
 *
 * Witness programs go through [SegwitAddress] — version 0 as bech32 and 1..16 as
 * bech32m — and the two Base58 forms through [Base58Check]. Anything else
 * (bare multisig, an OP_RETURN, a non-standard script) returns null so the
 * screen can print "Unknown script"; inventing an address for a script the
 * wallet cannot read would be worse than admitting it does not know.
 *
 * The mainnet human-readable part and version bytes are used: this wallet
 * derives `bc1q…` addresses and treats every imported key as mainnet native
 * segwit.
 */
fun addressFromScriptPubKeyHex(scriptPubKeyHex: String): String? {
    val bytes = try {
        TransactionParser.hexBytes(scriptPubKeyHex)
    } catch (_: Exception) {
        return null
    }
    return addressFromScript(bytes)
}

/** [addressFromScriptPubKeyHex] over already-decoded script bytes. */
internal fun addressFromScript(script: ByteArray): String? {
    // P2WPKH / P2WSH: OP_0 PUSH20/PUSH32 <program>.
    if (script.size >= 2 && script[0].toInt() == 0x00) {
        val length = script[1].toInt() and 0xFF
        if (script.size == 2 + length && length in 20..32) {
            val program = script.copyOfRange(2, script.size)
            return runCatching {
                SegwitAddress.encode(SegwitAddress.MAINNET_HRP, 0, program)
            }.getOrNull()
        }
    }
    // Witness versions 1..16: OP_1..OP_16 PUSH2..PUSH40 <program>.
    if (script.size >= 2) {
        val opcode = script[0].toInt() and 0xFF
        if (opcode in 0x51..0x60) {
            val length = script[1].toInt() and 0xFF
            if (script.size == 2 + length && length in 2..40) {
                val program = script.copyOfRange(2, script.size)
                return runCatching {
                    SegwitAddress.encode(SegwitAddress.MAINNET_HRP, opcode - 0x50, program)
                }.getOrNull()
            }
        }
    }
    // P2PKH: OP_DUP OP_HASH160 PUSH20 <hash160> OP_EQUALVERIFY OP_CHECKSIG.
    // Bytes above 0x7f are negative as Kotlin `Byte`, so every opcode comparison
    // masks to the unsigned value; a bare `toInt()` would never match 0xa9.
    if (script.size == 25 &&
        (script[0].toInt() and 0xFF) == 0x76 && (script[1].toInt() and 0xFF) == 0xa9 &&
        (script[2].toInt() and 0xFF) == 0x14 && (script[23].toInt() and 0xFF) == 0x88 &&
        (script[24].toInt() and 0xFF) == 0xac
    ) {
        return runCatching {
            Base58Check.encode(byteArrayOf(0x00) + script.copyOfRange(3, 23))
        }.getOrNull()
    }
    // P2SH: OP_HASH160 PUSH20 <hash160> OP_EQUAL.
    if (script.size == 23 &&
        (script[0].toInt() and 0xFF) == 0xa9 && (script[1].toInt() and 0xFF) == 0x14 &&
        (script[22].toInt() and 0xFF) == 0x87
    ) {
        return runCatching {
            Base58Check.encode(byteArrayOf(0x05) + script.copyOfRange(2, 22))
        }.getOrNull()
    }
    return null
}
