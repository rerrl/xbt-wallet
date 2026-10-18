package com.xbtwallet.app.chain

/**
 * The chain-layer value types: what one Electrum scripthash query answers, and
 * what a whole-account scan adds up to.
 *
 * These are plain Kotlin data classes with no Android and no socket in sight.
 * Keeping them here (rather than nesting them inside the client) is what lets
 * the [AccountScanner] — the piece that decides *which* addresses to ask about —
 * be unit-tested against a fake [ChainQuery] with no server at all, while the
 * socket side of the same protocol lives in `com.xbtwallet.app.electrum`.
 */

/**
 * One entry of `blockchain.scripthash.get_history`.
 *
 * Only the fields the scanner uses are kept: the transaction id (for a future
 * history screen) and the height. `height` is 0 for a transaction still in the
 * mempool and -1 for one the server considers unconfirmed-but-seen; both mean
 * "unconfirmed" to this wallet.
 */
data class HistoryEntry(
    val txHash: String,
    val height: Int,
) {
    /** True when the server has this transaction in a block. */
    val confirmed: Boolean get() = height > 0
}

/**
 * The `confirmed` / `unconfirmed` split that `blockchain.scripthash.get_balance`
 * reports, in satoshis.
 *
 * [totalSatoshis] is what the signer screen shows: a wallet's headline balance
 * includes coins it has just been paid, which are unconfirmed until the next
 * block.
 */
data class ScripthashBalance(
    val confirmedSatoshis: Long,
    val unconfirmedSatoshis: Long,
) {
    /** Confirmed plus unconfirmed — the number the user thinks of as "balance". */
    val totalSatoshis: Long get() = confirmedSatoshis + unconfirmedSatoshis

    companion object {
        /** Nothing at this scripthash — the answer for every unused address. */
        val ZERO = ScripthashBalance(confirmedSatoshis = 0, unconfirmedSatoshis = 0)
    }
}

/**
 * One spendable output from `blockchain.scripthash.listunspent` — exactly the
 * fields the server reports, nothing stamped on.
 *
 * The RPC is per scripthash and its answer does not say which address the output
 * belongs to; that knowledge lives in the scanner, which pairs this with its
 * address in a [ScannedUtxo]. Keeping the two apart means the RPC decoding has
 * no "which address was this" placeholder field to get wrong.
 */
data class ChainUtxo(
    val txHash: String,
    val txPos: Int,
    val height: Int,
    val satoshis: Long,
) {
    /** `txid:vout`, the outpoint that identifies this output on chain. */
    val outpoint: String get() = "$txHash:$txPos"

    /** True when the server has this output in a block. */
    val confirmed: Boolean get() = height > 0
}

/**
 * A UTXO plus the address it sits at: what `listunspent` returned, paired with
 * the `chain`/`index` the scanner asked about.
 */
data class ScannedUtxo(
    val utxo: ChainUtxo,
    val chain: AddressChain,
    val addressIndex: Int,
)

/**
 * The two BIP-32 chains of a BIP-84 account: 0 is the external (receive) chain,
 * 1 the internal (change) chain.
 *
 * The numbers are what goes into the derivation path
 * `m/84'/0'/0'/<chain>/<index>`; the labels are what the tabs show. This lives
 * in the chain package rather than in the address screen because the *scanner*
 * is what walks these two chains — the UI only renders what it returns.
 */
enum class AddressChain(val index: Long, val label: String) {
    RECEIVE(0L, "Receive"),
    CHANGE(1L, "Change"),
}

/**
 * One address derived from an account key, with the raw material the chain
 * queries need.
 *
 * [scripthash] is the Electrum lookup key for this address (see
 * [Scripthash]) — computed once at derivation time and carried alongside the
 * address so neither the scanner nor the UI ever hashes anything twice.
 */
data class DerivedAddress(
    val chain: AddressChain,
    val index: Int,
    val address: String,
    val scripthash: String,
)

/**
 * One address the gap-limit scan visited, plus what the server said about it.
 */
data class ScannedAddress(
    val chain: AddressChain,
    val index: Int,
    val address: String,
    val scripthash: String,
    /** Non-empty history at this address: coins have moved through it. */
    val used: Boolean,
)

/**
 * The result of scanning one account: every address the scan visited (each
 * tagged used or not) and what its used addresses are holding.
 *
 * [balance] is the headline number and comes from a single source of truth, the
 * sum of `blockchain.scripthash.get_balance` over the used addresses — the UTXO
 * list is what that balance is *made of* for display, and is never summed to
 * produce it. That keeps the number the signer screen shows independent of the
 * viewer's rendering path.
 */
data class AccountSnapshot(
    val receive: List<ScannedAddress>,
    val change: List<ScannedAddress>,
    val utxos: List<ScannedUtxo>,
    val balance: ScripthashBalance,
) {
    /** Every address the scan visited, receive chain first. */
    val addresses: List<ScannedAddress> get() = receive + change

    /** Only the addresses that carry history — what the queries were run for. */
    val usedAddresses: List<ScannedAddress> get() = addresses.filter { it.used }

    /** The scanned addresses of one chain, in index order. */
    fun addresses(chain: AddressChain): List<ScannedAddress> =
        if (chain == AddressChain.RECEIVE) receive else change

    /** Confirmed plus unconfirmed satoshis, the number the screen formats. */
    val satoshis: Long get() = balance.totalSatoshis

    companion object {
        /** An account with nothing on chain and no scan performed. */
        val EMPTY = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance.ZERO)
    }
}
