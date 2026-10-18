package com.xbtwallet.app.chain

/**
 * The three Electrum scripthash queries the wallet needs, and nothing else.
 *
 * This interface is the seam between "how to talk to a server" and "which
 * addresses to ask about". The real implementation is
 * `com.xbtwallet.app.electrum.ChainRpcSession`, which issues the JSON-RPC calls
 * over a socket; the tests drive [AccountScanner] with an in-memory fake, so the
 * gap-limit rules are pinned without a network, a socket or a coroutine.
 *
 * Implementations are **blocking on purpose**: they are called from a worker
 * thread that the client owns (`ElectrumClient.withChainSession` runs its block
 * on the IO dispatcher). There is no `suspend` here, which is exactly what keeps
 * the scanner a plain, testable function.
 *
 * Every method throws on a transport or protocol failure — the caller (the
 * signer screen's state) turns that into a visible error state.
 */
interface ChainQuery {

    /** Transactions that touched this scripthash; empty means the address is unused. */
    fun getHistory(scripthash: String): List<HistoryEntry>

    /** Confirmed + unconfirmed satoshis at this scripthash. */
    fun getBalance(scripthash: String): ScripthashBalance

    /** Spendable outputs at this scripthash. */
    fun listUnspent(scripthash: String): List<ChainUtxo>
}
