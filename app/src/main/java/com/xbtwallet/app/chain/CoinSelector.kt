package com.xbtwallet.app.chain

/**
 * Automatic coin selection for the air-gapped send: given a wallet's coins, an
 * amount and a fee rate, which coins to spend.
 *
 * This is the piece the manual UTXO multi-select replaces with a user's own
 * choice, and it answers the same question the user would: *does some subset of
 * my coins pay exactly this amount (so the send needs no change output), and if
 * not, which coins cover the amount plus a change output?*
 *
 * **All fee and size arithmetic goes through [exactSendVsize]**, the same
 * ceiling-rounded function [planSend] charges with. Coin selection and the
 * planner must agree to the satoshi, or the selector can offer a set the planner
 * then refuses to pay for — the 140-vs-141 vB bug class, one layer up.
 *
 * **Deterministic, always.** Every coin list is ordered by value (largest
 * first) and then by outpoint, so an equal-value pair never selects in an
 * order the JVM happened to hand it. That is what lets a persisted "select the
 * coins automatically" send re-derive the same selection after process death.
 *
 * Like everything in `chain/`, this is Android-free and JVM-tested.
 */

/**
 * The scriptPubKey size of a P2WPKH output — `OP_0 PUSH20 <20-byte program>`:
 * one opcode, one push length, twenty bytes of program.
 *
 * A useful default for both halves of a send here: every coin this wallet
 * derives is P2WPKH, so its change output is one, and the recipient of a
 * normal payment is one too.
 */
const val P2WPKH_SCRIPT_SIZE: Int = 22

/**
 * One coin a selection may spend: what an outpoint is worth, and nothing else.
 *
 * Deliberately narrower than [ChainUtxo] — a selector has no use for an address
 * or a height, and taking only what it needs is what makes it trivial to test
 * with hand-written coins.
 */
data class SelectableUtxo(
    val txidHex: String,
    val vout: Int,
    val satoshis: Long,
) {
    /** `txid:vout`, the outpoint that identifies this coin on chain. */
    val outpoint: String get() = "$txidHex:$vout"
}

/** A chain UTXO as the selector sees it. */
fun ChainUtxo.toSelectable(): SelectableUtxo = SelectableUtxo(txHash, txPos, satoshis)

/**
 * What a selection resolved to: the coins to spend, the fee that set costs, and
 * whether the total matched the amount plus fee *exactly* — the no-change case.
 */
class CoinSelection(
    val inputs: List<SelectableUtxo>,
    val feeSatoshis: Long,
    val isExactMatch: Boolean,
) {
    /** How many inputs the transaction will carry. */
    val inputCount: Int get() = inputs.size

    /** The selected outpoints, in selection order. */
    val outpoints: List<String> get() = inputs.map { it.outpoint }

    /** What the selected coins are worth in total. */
    val totalSatoshis: Long get() = inputs.sumOf { it.satoshis }
}

/**
 * The coins cannot cover the amount plus the fee the send needs.
 *
 * Carries both numbers so a caller can say *how* short the wallet is rather than
 * only that it is short.
 */
class InsufficientFunds(
    val availableSatoshis: Long,
    val requiredSatoshis: Long,
) : IllegalArgumentException(
    "insufficient funds: $availableSatoshis sat available, $requiredSatoshis sat required",
)

/**
 * Chooses the coins to spend for [amountSatoshis] at [feeRateSatPerVb].
 *
 * Two passes, in the order a good manual selector would try them:
 *
 * 1. **Exact match.** A subset whose total is exactly the amount plus the fee of
 *    a transaction with *no* change output ([exactSendVsize] with the recipient
 *    output alone). This is the cheapest and cleanest send — no change to pay
 *    for, no change to come back. It is searched by a bounded branch-and-bound
 *    ([EXACT_MATCH_ITERATION_CAP] nodes), and the search is over the
 *    value-ordered list, so its answer is reproducible. Exhausting the cap is
 *    not an error: it simply falls through to the accumulation below.
 * 2. **Largest-first accumulation.** Add coins from the largest down until the
 *    total covers the amount plus the fee of a transaction *with* a change
 *    output, **recomputing that fee after every addition** — the fee grows with
 *    the input count, so pricing it once and adding coins against the stale
 *    number is exactly how a wallet comes up a few sats short.
 *
 * @param recipientScriptSize the recipient output's scriptPubKey size (22 for
 *   P2WPKH, 34 for P2TR), so the send is priced for the output it will build.
 * @param changeScriptSize this wallet's own change output size.
 * @throws InsufficientFunds when no combination of the coins covers the send.
 */
fun selectCoins(
    utxos: List<SelectableUtxo>,
    amountSatoshis: Long,
    feeRateSatPerVb: Long,
    recipientScriptSize: Int,
    changeScriptSize: Int,
): CoinSelection {
    require(amountSatoshis > 0) { "an amount of at least 1 sat is required" }
    require(feeRateSatPerVb >= 1) { "a fee rate of at least 1 sat/vB is required" }
    require(recipientScriptSize > 0) { "a recipient script size is required" }
    require(changeScriptSize > 0) { "a change script size is required" }

    val sorted = utxos.sortedWith(utxoOrder)

    exactMatch(sorted, amountSatoshis, feeRateSatPerVb, recipientScriptSize)?.let { exact ->
        val fee = feeFor(exact.size, feeRateSatPerVb, listOf(recipientScriptSize))
        return CoinSelection(exact, fee, isExactMatch = true)
    }

    val chosen = ArrayList<SelectableUtxo>()
    var total = 0L
    var feeWithChange = 0L
    for (utxo in sorted) {
        chosen.add(utxo)
        total += utxo.satoshis
        feeWithChange = feeFor(chosen.size, feeRateSatPerVb, listOf(recipientScriptSize, changeScriptSize))
        if (total >= amountSatoshis + feeWithChange) {
            return CoinSelection(chosen.toList(), feeWithChange, isExactMatch = false)
        }
    }
    throw InsufficientFunds(
        availableSatoshis = sorted.sumOf { it.satoshis },
        requiredSatoshis = amountSatoshis + feeWithChange,
    )
}

/**
 * The whole balance, spent: every coin, the fee taken out, and no change output.
 *
 * "Max" is a different question from an ordinary send. An ordinary send spends
 * *some* coins and pays the remainder back as change; a max send spends *all* of
 * them and keeps nothing, so the fee is priced without a change output and the
 * amount is whatever is left. The result is not a "match" in the exact-pass
 * sense — it is the deliberate no-change case — so [CoinSelection.isExactMatch]
 * stays false, but a caller that plans this selection gets a transaction with no
 * change, exactly as [planSend]'s dust rule would fold it anyway.
 *
 * @throws InsufficientFunds when the fee would take the whole balance (there is
 *   not even 1 sat left to send).
 */
fun selectMax(
    utxos: List<SelectableUtxo>,
    feeRateSatPerVb: Long,
    recipientScriptSize: Int,
): CoinSelection {
    require(feeRateSatPerVb >= 1) { "a fee rate of at least 1 sat/vB is required" }
    require(recipientScriptSize > 0) { "a recipient script size is required" }
    require(utxos.isNotEmpty()) { "a send spends at least one input" }

    val sorted = utxos.sortedWith(utxoOrder)
    val total = sorted.sumOf { it.satoshis }
    val fee = feeFor(sorted.size, feeRateSatPerVb, listOf(recipientScriptSize))
    if (total <= fee) throw InsufficientFunds(availableSatoshis = total, requiredSatoshis = fee + 1)
    return CoinSelection(sorted, fee, isExactMatch = false)
}

/**
 * Largest value first, then outpoint — the one order every pass uses.
 *
 * The value sort is what makes accumulation largest-first; the outpoint
 * tie-break is what makes two equal-value coins select in a stable, replayable
 * order instead of whichever one the list happened to hold first.
 */
private val utxoOrder: Comparator<SelectableUtxo> =
    compareByDescending<SelectableUtxo> { it.satoshis }.thenBy { it.outpoint }

/** The fee, in satoshis, of an [inputCount]-input send with [outputScriptSizes]. */
private fun feeFor(inputCount: Int, feeRateSatPerVb: Long, outputScriptSizes: List<Int>): Long =
    exactSendVsize(inputCount, outputScriptSizes).toLong() * feeRateSatPerVb

/**
 * How many nodes the exact-match search may visit before giving up.
 *
 * A wallet's coin count is small in the common case and a match is usually found
 * near the top of the value-ordered list, so a bounded walk over the promising
 * prefixes is enough; an adversary with hundreds of coins cannot make this hang.
 * Exceeding it falls through to the accumulation pass, never to an error.
 */
private const val EXACT_MATCH_ITERATION_CAP = 1000

/**
 * The first subset of [sorted] whose total equals the amount plus the fee of a
 * no-change send, or null when there is none within [EXACT_MATCH_ITERATION_CAP]
 * nodes.
 *
 * The walk adds coins in value order and visits each subset once; it is a plain
 * depth-first enumeration, made bounded by the cap rather than by cleverness —
 * the fee for a subset depends on its size, so a subset's total cannot be
 * pruned against a fixed target without that arithmetic.
 */
private fun exactMatch(
    sorted: List<SelectableUtxo>,
    amountSatoshis: Long,
    feeRateSatPerVb: Long,
    recipientScriptSize: Int,
): List<SelectableUtxo>? {
    val chosen = ArrayList<SelectableUtxo>()
    var iterations = 0

    fun search(start: Int, total: Long): List<SelectableUtxo>? {
        if (chosen.isNotEmpty()) {
            iterations++
            val target = amountSatoshis + feeFor(chosen.size, feeRateSatPerVb, listOf(recipientScriptSize))
            if (total == target) return chosen.toList()
        }
        if (iterations >= EXACT_MATCH_ITERATION_CAP) return null
        for (index in start until sorted.size) {
            chosen.add(sorted[index])
            val found = search(index + 1, total + sorted[index].satoshis)
            chosen.removeAt(chosen.size - 1)
            if (found != null) return found
            if (iterations >= EXACT_MATCH_ITERATION_CAP) return null
        }
        return null
    }

    return search(0, 0L)
}
