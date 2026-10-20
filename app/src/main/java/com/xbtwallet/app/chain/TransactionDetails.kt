package com.xbtwallet.app.chain

/**
 * What one transaction did to *this wallet*, and how that row is bucketed.
 *
 * **The arithmetic.** For one transaction the wallet's net effect is
 *
 *     net = (sum of outputs paying our scripts) - (sum of our coins it spends)
 *
 * Change falls out of this for free: a send that returns 0.02 XBT to one of our
 * own change addresses has that output counted on the incoming side, and the
 * coin it spends counted on the outgoing side, so only the payment plus the fee
 * survives as a negative number. That is the whole reason this is computed from
 * the transaction and not from `listunspent`, which cannot tell a change output
 * from a payment.
 *
 * **What "our coins" means.** An input is ours when its outpoint (`txid:vout`)
 * matches an output, paying one of our scripts, of a transaction the account's
 * history reported. Every coin we can spend was created by such a transaction —
 * `get_history` is per address and returns that address's full history — so the
 * index built from the account's own transactions is complete for the coins the
 * account can spend.
 *
 * **Confirmations.** A row's confirmation count is `tip - height + 1` for a
 * mined transaction, and 0 for one the server reports at height <= 0 (mempool or
 * unconfirmed-but-seen). The tip height comes from one `blockchain.headers`
 * call per scan.
 */

/** Which way value moved, from the wallet's point of view. */
enum class TransactionDirection {
    /** More came in than went out. */
    IN,

    /** More went out than came in (the normal shape of a send, fee included). */
    OUT,

    /** Everything that moved was ours and the amounts cancel — e.g. a self-send. */
    NEUTRAL,
}

/**
 * One row of the signer screen's transaction list.
 *
 * [netSatoshis] is signed: positive means the account gained value, negative
 * means it lost value. [confirmations] is 0 until the transaction is mined.
 *
 * [blockTimestampEpochSeconds] is when the block that mined this transaction
 * was stamped — read from the block header (see
 * [blockTimestampFromHeaderHex]) — or null when the transaction is unconfirmed
 * or its header could not be fetched. It is deliberately nullable and defaults
 * to null, so a snapshot persisted before the field existed still loads.
 */
data class TransactionRow(
    val txHash: String,
    val height: Int,
    val confirmations: Int,
    val netSatoshis: Long,
    val blockTimestampEpochSeconds: Long? = null,
) {
    /** True when the server has this transaction in a block. */
    val confirmed: Boolean get() = height > 0

    /** Which way the row should read (and be coloured). */
    val direction: TransactionDirection
        get() = when {
            netSatoshis > 0 -> TransactionDirection.IN
            netSatoshis < 0 -> TransactionDirection.OUT
            else -> TransactionDirection.NEUTRAL
        }
}

/** How a row's confirmation count is coloured: settled, still shallow, or not mined. */
enum class ConfirmationBucket {
    /** 0 confirmations: in the mempool. */
    UNCONFIRMED,

    /** 1..5 confirmations: mined, but not yet the convention's "settled" depth. */
    SHALLOW,

    /** [SETTLED_CONFIRMATIONS] or more. */
    SETTLED,
}

/** The depth at which a transaction is treated as settled: 6 blocks. */
const val SETTLED_CONFIRMATIONS: Int = 6

/**
 * True when at least one confirmed row carries no date — what a list looks like
 * when a block header could not be read (see [buildTransactionRows]).
 *
 * The Transactions section shows one quiet line for this rather than an
 * explanation per row: the failure is per *block*, not per transaction, and
 * several rows mined in the same block share it. Unconfirmed rows are not
 * counted: they have no block to date them, which is normal, not a failure.
 */
fun List<TransactionRow>.hasUndatedConfirmedRows(): Boolean =
    any { row -> row.confirmed && row.blockTimestampEpochSeconds == null }

/**
 * Buckets a confirmation count. The boundaries are the card's, exactly:
 * 0 is unconfirmed, 1..5 is shallow, 6 and above is settled.
 */
fun confirmationBucket(confirmations: Int): ConfirmationBucket = when {
    confirmations <= 0 -> ConfirmationBucket.UNCONFIRMED
    confirmations < SETTLED_CONFIRMATIONS -> ConfirmationBucket.SHALLOW
    else -> ConfirmationBucket.SETTLED
}

/**
 * The largest confirmation count a row ever renders. Past this the badge reads
 * `100+ confs`: a four-digit badge breaks the row layout, and once a
 * transaction is this deep the exact depth stops being interesting — the same
 * reasoning that put the old cap at six.
 */
const val MAX_RENDERED_CONFIRMATIONS: Int = 100

/**
 * The text of a row's confirmation badge: `Unconfirmed`, `1 conf`, the real
 * count as `N confs` up to [MAX_RENDERED_CONFIRMATIONS] - 1, and `100+ confs`
 * from there on. The colour is a separate concern (see [confirmationBucket]).
 */
fun confirmationLabel(confirmations: Int): String = when {
    confirmations <= 0 -> "Unconfirmed"
    confirmations >= MAX_RENDERED_CONFIRMATIONS -> "$MAX_RENDERED_CONFIRMATIONS+ confs"
    confirmations == 1 -> "1 conf"
    else -> "$confirmations confs"
}

/**
 * Confirmations for a transaction reported at [height] when the chain tip is at
 * [tipHeight]. An unconfirmed transaction (height <= 0) has none; a mined one has
 * at least one, whatever the two heights say (a server whose tip answer lags its
 * own history must not produce a row claiming a mined transaction has 0).
 */
fun confirmationsFor(height: Int, tipHeight: Int): Int =
    if (height <= 0) 0 else (tipHeight - height + 1).coerceAtLeast(1)

/** How many rows the signer screen shows, and therefore how many it fetches. */
const val MAX_DETAILED_TRANSACTIONS: Int = 50

/**
 * Turns the scan's aggregated history into display rows, fetching the
 * transactions it needs over the already-open connection.
 *
 * The fetch is bounded: at most [limit] display transactions are fetched, plus
 * one funding transaction for any input of theirs whose `txid` the account's
 * history already knows about (see the file comment). Nothing is fetched for an
 * input the account has never seen — that coin is somebody else's.
 *
 * Each row is then dated by one `blockchain.block.header` call per *distinct*
 * confirmed height among the shown rows (rows mined in the same block share one
 * call), and never for an unconfirmed row. A header that cannot be read leaves
 * its rows' [TransactionRow.blockTimestampEpochSeconds] null: dating a row is
 * best-effort and must never fail a scan — but it must never be *silent*
 * either, so every failure is handed to [onTimestampFailure] (the Android side
 * logs it, and the Transactions section says one line about it). A server whose
 * header is longer than 80 bytes is not a failure: the parser reads the 80-byte
 * prefix (see [blockTimestampFromHeaderHex]).
 *
 * **Reuse.** [cachedRows] is the previous scan's rows, keyed by txid. A shown
 * entry whose cached row is *confirmed and still at the same height* is reused
 * verbatim — only its confirmation count is recomputed from the fresh tip —
 * and its transaction is never fetched. This is what makes a rescan cheap over
 * Tor: `blockchain.transaction.get` returns a whole transaction, so skipping the
 * fetch for every unchanged confirmed row removes the expensive call from the
 * warm path. An unconfirmed cached row, or one whose height has moved (a
 * reorg, or a mempool transaction that just got mined), is re-fetched: the old
 * amount is exactly what we must not trust there.
 *
 * [addresses] supplies the wallet's scriptPubKeys; a scan whose addresses carry
 * no script (a test fixture, or a pre-P2WPKH record) yields no rows rather than
 * an invented direction.
 *
 * Throws whatever the transport throws: a transaction that cannot be read is an
 * error state on the screen, not a row with a guessed amount.
 *
 * @param onTimestampFailure called once per *distinct height* whose block header
 *   could not be fetched or read, with the height and the exception — never for
 *   an unconfirmed row, and never for a header that merely came back larger than
 *   80 bytes. The default does nothing, which is what the JVM tests and any
 *   caller with nowhere to report to want; the app wires it to `Log.w` and the
 *   Transactions section renders one line about it.
 */
internal fun buildTransactionRows(
    transactions: List<ScannedTransaction>,
    addresses: List<ScannedAddress>,
    query: ChainQuery,
    limit: Int = MAX_DETAILED_TRANSACTIONS,
    cachedRows: Map<String, TransactionRow> = emptyMap(),
    onTimestampFailure: (height: Int, error: Exception) -> Unit = { _, _ -> },
): List<TransactionRow> {
    if (transactions.isEmpty()) return emptyList()
    val walletScripts = addresses.mapNotNullTo(HashSet()) {
        it.scriptPubKeyHex.takeIf { script -> script.isNotEmpty() }
    }
    if (walletScripts.isEmpty()) return emptyList()

    val shown = transactions.take(limit)
    val knownTxIds = transactions.mapTo(HashSet()) { it.txHash }
    val parsed = LinkedHashMap<String, ParsedTransaction>()

    fun fetch(txHash: String) {
        if (parsed.containsKey(txHash)) return
        parsed[txHash] = TransactionParser.parse(query.getTransaction(txHash))
    }

    // A cached row is reusable only when it describes the same, still-confirmed
    // transaction: same txid, mined, and at the height the fresh history reports.
    // Everything else is fetched as before.
    fun reusable(entry: ScannedTransaction): TransactionRow? {
        val cached = cachedRows[entry.txHash] ?: return null
        return cached.takeIf { it.confirmed && it.height == entry.height && entry.height > 0 }
    }

    shown.forEach { entry ->
        if (reusable(entry) == null) fetch(entry.txHash)
    }
    // A spend's inputs can point at a funding transaction that is in the history
    // but outside the displayed window; fetch those too so their value is known.
    // A funding transaction that was reused above is not in `parsed`, so a fetched
    // spend of it still triggers this fetch — and that is correct: the spend's
    // net effect needs the funding output's value.
    for (transaction in parsed.values.toList()) {
        for (input in transaction.inputs) {
            if (input.previousTxHash in knownTxIds) fetch(input.previousTxHash)
        }
    }

    // Outpoint -> value, for every output we own that any fetched transaction
    // created. This is the only thing that can turn an input into "our coin".
    val walletOutputs = HashMap<String, Long>()
    for ((txHash, transaction) in parsed) {
        transaction.outputs.forEachIndexed { index, output ->
            if (output.scriptPubKeyHex in walletScripts) walletOutputs["$txHash:$index"] = output.satoshis
        }
    }

    val tipHeight = query.getTipHeight()
    val rows = shown.map { entry ->
        // Reused rows keep their stored net amount; only the confirmation count
        // is recomputed from the fresh tip. Everything else is computed from the
        // transaction we fetched.
        val cached = reusable(entry)
        TransactionRow(
            txHash = entry.txHash,
            height = entry.height,
            confirmations = confirmationsFor(entry.height, tipHeight),
            netSatoshis = cached?.netSatoshis
                ?: netEffect(parsed.getValue(entry.txHash), walletScripts, walletOutputs),
            // A reused row keeps the date it already carried; a fresh one starts
            // null and is dated in the pass below.
            blockTimestampEpochSeconds = cached?.blockTimestampEpochSeconds,
        )
    }

    // Date the confirmed rows: one `block.header` call per DISTINCT height,
    // shared by every row mined in the same block. A header that cannot be read
    // leaves its rows' timestamps null rather than failing the scan — a date is
    // not worth losing a whole balance over — but the failure is *reported*
    // through onTimestampFailure instead of being swallowed: a row that is
    // silently undated is exactly the bug this callback exists to expose.
    // Unconfirmed rows stay null: there is no block to read.
    val heights = rows.asSequence().filter { it.confirmed }.map { it.height }.toSet()
    val timestamps = HashMap<Int, Long?>(heights.size)
    for (height in heights) {
        timestamps[height] = try {
            blockTimestampFromHeaderHex(query.blockHeader(height))
        } catch (error: Exception) {
            // The height exists (the history reported it) but its header did
            // not come back, or came back as something that is not a header.
            onTimestampFailure(height, error)
            null
        }
    }
    return rows.map { row ->
        if (!row.confirmed || row.blockTimestampEpochSeconds != null) {
            row
        } else {
            row.copy(blockTimestampEpochSeconds = timestamps[row.height])
        }
    }
}

/**
 * The wallet's net effect of one transaction: what it pays us, minus what it
 * spends of ours.
 *
 * [walletOutputs] maps `txid:vout` of *our* previously created outputs to their
 * value — the only way an input can be recognised as ours, because a transaction
 * input names a previous output and carries no amount. With change counted as an
 * incoming output and the coin it came from counted as an outgoing input, a send
 * nets out to `-(payment + fee)`, which is exactly what left the wallet.
 */
internal fun netEffect(
    transaction: ParsedTransaction,
    walletScripts: Set<String>,
    walletOutputs: Map<String, Long>,
): Long {
    val incoming = transaction.outputs.sumOf { output ->
        if (output.scriptPubKeyHex in walletScripts) output.satoshis else 0L
    }
    val outgoing = transaction.inputs.sumOf { input ->
        walletOutputs["${input.previousTxHash}:${input.previousOutputIndex}"] ?: 0L
    }
    return incoming - outgoing
}
