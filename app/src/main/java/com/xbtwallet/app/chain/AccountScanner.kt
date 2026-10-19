package com.xbtwallet.app.chain

import com.xbtwallet.app.crypto.ExtendedPublicKey
import com.xbtwallet.app.crypto.Hashes
import com.xbtwallet.app.crypto.SegwitAddress

/**
 * The gap-limit scan: the piece that turns an account xpub plus an Electrum
 * server into "what does this account hold".
 *
 * **Why a scan at all.** An xpub describes a whole space of addresses, and a
 * watch-only wallet has no record of which of them were ever handed out. The
 * only way to learn is to ask the server about them, one scripthash at a time.
 * The *gap limit* is the convention (BIP-44) that bounds that search: wallets
 * stop handing out an address once they have [gapLimit] consecutive unused ones
 * ahead of the last used one. So this scanner walks each BIP-32 chain — receive
 * (0) and change (1) — in batches of [gapLimit], and stops as soon as a whole
 * batch comes back unused. That is the same rule every other wallet uses, which
 * matters: a scan that stops early would silently hide coins.
 *
 * **The stop rule, precisely.** A batch of 20 is queried; if *any* address in it
 * has history, the next batch is queried too; the scan ends after the first
 * batch in which none does. With 25 used receive addresses (indices 0..24) that
 * is three batches — 0..39 has used addresses in it, so 40..59 is consulted and
 * is the batch that ends the walk. (A batch that is *partly* used does not end
 * the scan: the used address in it may be followed by more used ones, which is
 * exactly the "silently hide coins" case the rule guards against.)
 *
 * **What it costs.** One `get_history` per visited address, plus one
 * `get_balance` and one `listunspent` per *used* address. The nominal case — an
 * empty account — is 40 history calls (20 per chain) and nothing else. An
 * account with history additionally costs one `blockchain.transaction.get` per
 * displayed transaction (capped at [MAX_DETAILED_TRANSACTIONS]) plus one
 * `blockchain.headers.subscribe` for the tip — see [buildTransactionRows].
 *
 * **Rescanning.** [scan] is the cold path: it knows nothing and walks from
 * index 0. [rescan] is the warm path a persisted snapshot enables: it re-checks
 * the addresses a previous scan already recorded and then continues the gap walk
 * from where that scan stopped, so a second visit re-derives nothing and
 * re-fetches none of the expensive transaction details. See [rescan].
 *
 * **Balance.** The headline balance is the sum of `get_balance` over the used
 * addresses (confirmed + unconfirmed). The UTXO list is the same coins itemised
 * for display, and is deliberately *not* summed to produce the balance — one
 * source of truth, so the number can never disagree with itself.
 *
 * **Blocking.** Every [ChainQuery] call blocks on a socket. [scan] is therefore
 * called from the client's IO dispatcher (see
 * `ElectrumClient.withChainSession`); nothing here touches a UI framework.
 *
 * @param accountKey the signer's account-level extended public key.
 * @param gapLimit consecutive unused addresses that end the walk; 20 is the
 *   BIP-44 convention and the batch size.
 */
class AccountScanner(
    private val accountKey: ExtendedPublicKey,
    val gapLimit: Int = DEFAULT_GAP_LIMIT,
) {

    init {
        require(gapLimit > 0) { "gap limit must be positive, got $gapLimit" }
    }

    /**
     * Derives one address of one chain.
     *
     * The path is `account/<chain>/<index>`: [accountKey] is already the account
     * (`m/84'/0'/0'`), so a child of the chain key is the address key. The
     * address is native segwit (P2WPKH) over HASH160 of the compressed public
     * key — the same construction the address viewer has always used, now in one
     * place so the scan, the viewer and the UTXO list can never derive
     * differently.
     */
    fun derive(chain: AddressChain, index: Int): DerivedAddress {
        require(index >= 0) { "address index must not be negative, got $index" }
        val chainKey = accountKey.deriveChild(chain.index)
        val addressKey = chainKey.deriveChild(index.toLong())
        val program = Hashes.hash160(addressKey.publicKey)
        return DerivedAddress(
            chain = chain,
            index = index,
            address = SegwitAddress.encodeP2wpkh(SegwitAddress.MAINNET_HRP, program),
            scripthash = Scripthash.ofP2wpkh(program),
            // Carried alongside so a transaction's outputs can be matched without
            // decoding a script back into an address.
            scriptPubKeyHex = Scripthash.toHex(Scripthash.p2wpkhScript(program)),
        )
    }

    /**
     * Walks both chains from scratch and reports everything the account holds.
     *
     * [onScanned] is called after every address the server answers about, with
     * the running count — the signer screen uses it to show that the scan is
     * making progress rather than hanging. It is called from the scanning thread.
     */
    fun scan(query: ChainQuery, onScanned: (Int) -> Unit = {}): AccountSnapshot {
        var checked = 0
        val count: () -> Unit = { checked++; onScanned(checked) }

        val receive = scanChain(query, AddressChain.RECEIVE, count)
        val change = scanChain(query, AddressChain.CHANGE, count)

        return assemble(receive, change, query)
    }

    /**
     * Refreshes a previously scanned account, doing only the work a refresh
     * needs.
     *
     * **Why every cached address is re-checked, not just the tail.** The gap
     * window is an assumption about *this* wallet's own address use, and it can
     * be wrong: a coordinator sharing the same xpub may hand out an address this
     * wallet previously saw as unused — one that sits in the middle of the cached
     * list, not at its end. Re-querying every cached address's `get_history` is
     * the only way to notice that address has since been paid, and it is cheap
     * (one history call each, the same call the cold scan already makes). The
     * derivation data (address, scripthash, scriptPubKey) is kept from the cache,
     * so nothing is re-derived and the fresh history simply replaces what was
     * stored.
     *
     * **What the warm path saves.** After re-checking the cached addresses the
     * walk continues the gap limit from `cached.size` and stops after the first
     * fully-unused batch, exactly as [scan] would — but because the previous scan
     * already stopped there, that continuation is usually a single batch per
     * chain. The real saving is in [buildTransactionRows]: cached *confirmed*
     * transactions are reused instead of re-fetched, and `transaction.get` is the
     * expensive call over Tor (a full transaction per row). So a refresh costs
     * roughly one history call per known address plus a handful of new ones,
     * against the cold scan's history calls *and* a transaction fetch per row.
     *
     * @param cached the snapshot a previous scan produced; its addresses are the
     *   re-check list and its rows are the reuse pool for the transaction list.
     */
    fun rescan(query: ChainQuery, cached: AccountSnapshot, onScanned: (Int) -> Unit = {}): AccountSnapshot {
        var checked = 0
        val count: () -> Unit = { checked++; onScanned(checked) }

        val receive = rescanChain(query, AddressChain.RECEIVE, cached.receive, count)
        val change = rescanChain(query, AddressChain.CHANGE, cached.change, count)

        return assemble(receive, change, query, cachedRows = cached.transactions.associateBy { it.txHash })
    }

    /**
     * Turns a pair of freshly scanned chains into a snapshot: the balance and
     * UTXO list over the used addresses, and the transaction rows.
     *
     * One implementation for both paths, so a refresh and a cold scan can never
     * disagree about how a balance or a row is computed. [cachedRows] is passed
     * through to [buildTransactionRows] so the warm path can reuse rows it
     * already has.
     */
    private fun assemble(
        receive: List<ScannedAddress>,
        change: List<ScannedAddress>,
        query: ChainQuery,
        cachedRows: Map<String, TransactionRow> = emptyMap(),
    ): AccountSnapshot {
        // Only addresses with history can hold anything, so only they are asked
        // for a balance and a UTXO list.
        val used = receive.filter { it.used } + change.filter { it.used }

        var balance = ScripthashBalance.ZERO
        val utxos = ArrayList<ScannedUtxo>()
        for (address in used) {
            balance = balance + query.getBalance(address.scripthash)
            for (utxo in query.listUnspent(address.scripthash)) {
                // The RPC answers per scripthash; pair each output with the
                // address it was found at so the UTXO screen can say which chain
                // and index it came from.
                utxos += ScannedUtxo(
                    utxo = utxo,
                    chain = address.chain,
                    addressIndex = address.index,
                )
            }
        }

        return AccountSnapshot(
            receive = receive,
            change = change,
            utxos = utxos,
            balance = balance,
            // The transaction list is folded from the *same* get_history
            // answers that decided which addresses were used, then the rows'
            // net amounts are computed by fetching those transactions over this
            // same connection (see [buildTransactionRows]).
            transactions = buildTransactionRows(
                transactions = aggregateTransactions(receive + change),
                addresses = receive + change,
                query = query,
                cachedRows = cachedRows,
            ),
        )
    }

    /**
     * One chain's cold walk, in batches of [gapLimit], stopping after the first
     * batch with no used address in it.
     *
     * [MAX_BATCHES] is a runaway guard, not a policy: a server that answered
     * "used" for every address forever would otherwise hang the scan. 100
     * batches is 2 000 addresses per chain, far past any gap limit in use.
     */
    private fun scanChain(query: ChainQuery, chain: AddressChain, onScanned: () -> Unit): List<ScannedAddress> {
        val scanned = ArrayList<ScannedAddress>()
        var batchStart = 0
        var batches = 0
        while (batches < MAX_BATCHES) {
            batches++
            var anyUsed = false
            for (index in batchStart until batchStart + gapLimit) {
                val derived = derive(chain, index)
                val history = query.getHistory(derived.scripthash)
                val used = history.isNotEmpty()
                if (used) anyUsed = true
                scanned += ScannedAddress(
                    chain = chain,
                    index = index,
                    address = derived.address,
                    scripthash = derived.scripthash,
                    used = used,
                    history = history,
                    scriptPubKeyHex = derived.scriptPubKeyHex,
                )
                onScanned()
            }
            // A full batch with nothing in it is the gap limit: the wallet
            // convention says no later address can have been handed out.
            if (!anyUsed) break
            batchStart += gapLimit
        }
        return scanned
    }

    /**
     * One chain's warm walk: re-check every cached address, then continue the
     * gap walk past the cached list.
     *
     * The cached addresses are re-queried (not trusted) because another
     * coordinator may have handed one out since that earlier scan saw it empty;
     * the continuation is an ordinary gap walk that starts at `cached.size`,
     * where the previous scan gave up, so the two together cover the same ground
     * a cold scan would — with the history calls for the known part being the
     * only repeated work.
     */
    private fun rescanChain(
        query: ChainQuery,
        chain: AddressChain,
        cachedAddresses: List<ScannedAddress>,
        onScanned: () -> Unit,
    ): List<ScannedAddress> {
        val scanned = ArrayList<ScannedAddress>()
        for (cached in cachedAddresses) {
            val history = query.getHistory(cached.scripthash)
            scanned += ScannedAddress(
                chain = chain,
                index = cached.index,
                // Derivation data comes from the cache: re-deriving it would
                // prove nothing, and the cached values are what the earlier scan
                // hashed, so the scripthash we re-query is the same one.
                address = cached.address,
                scripthash = cached.scripthash,
                used = history.isNotEmpty(),
                history = history,
                scriptPubKeyHex = cached.scriptPubKeyHex,
            )
            onScanned()
        }

        // Continue the gap walk from where the cached list ends, stopping on the
        // first fully-unused batch exactly as a cold scan would.
        var batchStart = cachedAddresses.size
        var batches = 0
        while (batches < MAX_BATCHES) {
            batches++
            var anyUsed = false
            for (index in batchStart until batchStart + gapLimit) {
                val derived = derive(chain, index)
                val history = query.getHistory(derived.scripthash)
                val used = history.isNotEmpty()
                if (used) anyUsed = true
                scanned += ScannedAddress(
                    chain = chain,
                    index = index,
                    address = derived.address,
                    scripthash = derived.scripthash,
                    used = used,
                    history = history,
                    scriptPubKeyHex = derived.scriptPubKeyHex,
                )
                onScanned()
            }
            if (!anyUsed) break
            batchStart += gapLimit
        }
        return scanned
    }

    private operator fun ScripthashBalance.plus(other: ScripthashBalance): ScripthashBalance =
        ScripthashBalance(
            confirmedSatoshis = confirmedSatoshis + other.confirmedSatoshis,
            unconfirmedSatoshis = unconfirmedSatoshis + other.unconfirmedSatoshis,
        )

    companion object {
        /** BIP-44's gap limit, and the scan's batch size. */
        const val DEFAULT_GAP_LIMIT: Int = 20

        /** Runaway guard: at most this many batches per chain (see [scanChain]). */
        const val MAX_BATCHES: Int = 100
    }
}
