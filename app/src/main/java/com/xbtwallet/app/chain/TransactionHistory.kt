package com.xbtwallet.app.chain

/**
 * The account's transaction history, folded out of the gap-limit scan's own
 * `blockchain.scripthash.get_history` answers.
 *
 * **Why it costs nothing extra.** The scan already asks every visited address
 * for its history — that is how it decides whether an address is *used*. Those
 * answers were thrown away once the used/unused bit was read. Keeping them and
 * aggregating them here gives a transaction list for the price of the scan that
 * was happening anyway: no new RPC, no second connection, no verbose
 * transaction fetch.
 *
 * **What it deliberately is not.** One transaction touches several of this
 * account's addresses (a payment in, then a spend of it with change), and the
 * same txid comes back from each of them, at the same height. [aggregateTransactions]
 * dedupes by txid so the user sees one row per transaction, not one per address.
 *
 * It is also not a *direction*: `get_history` says a transaction touched an
 * address, not whether value came in or went out. The only directional signal
 * available without fetching the transaction is `listunspent`, and that is not
 * enough to be honest with — a send that paid somebody and returned change to
 * one of our own addresses *also* shows up in `listunspent`, so an "incoming"
 * arrow derived from it would mislabel a spend. The card allows the arrow to be
 * omitted when it is not cheaply derivable, and it is not, so a row shows the
 * txid and its height and says nothing it cannot prove.
 */

/**
 * One transaction the scan saw, deduped across every address that carried it.
 *
 * [height] is the block the server reported the transaction in, or 0 / -1 when
 * it is still unconfirmed — the same convention [HistoryEntry] uses, carried
 * through unchanged.
 */
data class ScannedTransaction(
    val txHash: String,
    val height: Int,
) {
    /** True when the server has this transaction in a block. */
    val confirmed: Boolean get() = height > 0
}

/**
 * Folds the per-address history the scan collected into one ordered list.
 *
 * The rules, in the order they matter to the screen:
 *  - **Dedupe by txid.** A txid seen at any number of addresses (either chain)
 *    becomes a single entry. Where the server reports it at more than one
 *    height, the highest wins: a mined height beats a mempool 0, and a real
 *    height can never be beaten by an unconfirmed one.
 *  - **Unconfirmed first.** Anything not in a block (height <= 0) is what the
 *    user is waiting on, so it leads the list.
 *  - **Then by height descending.** Most recent block first, so the list reads
 *    like a wallet's activity feed.
 *
 * The sort is stable, so transactions the server reports at the same height —
 * the common case for a single block — keep the order they were discovered.
 */
internal fun aggregateTransactions(addresses: List<ScannedAddress>): List<ScannedTransaction> {
    // Insertion-ordered so equal-height transactions keep discovery order.
    val heightByTx = LinkedHashMap<String, Int>()
    for (address in addresses) {
        for (entry in address.history) {
            val known = heightByTx[entry.txHash]
            if (known == null || entry.height > known) heightByTx[entry.txHash] = entry.height
        }
    }

    return heightByTx
        .map { (txHash, height) -> ScannedTransaction(txHash = txHash, height = height) }
        .sortedWith(compareBy<ScannedTransaction> { it.confirmed }.thenByDescending { it.height })
}
