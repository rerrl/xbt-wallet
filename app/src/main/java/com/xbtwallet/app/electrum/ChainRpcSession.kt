package com.xbtwallet.app.electrum

import com.xbtwallet.app.chain.ChainQuery
import com.xbtwallet.app.chain.ChainUtxo
import com.xbtwallet.app.chain.HistoryEntry
import com.xbtwallet.app.chain.ScripthashBalance

/**
 * The three Electrum scripthash RPCs, decoded into the chain layer's types,
 * plus the two the transaction list needs: `blockchain.transaction.get` (the
 * raw transaction whose net effect a row shows) and `blockchain.headers.subscribe`
 * (the tip height confirmations are counted from).
 *
 * One instance is bound to one open connection: the constructor takes the
 * "send a request and give me its `result`" callback, which
 * [ElectrumClient.withChainSession] builds over a live socket. That is what lets
 * a whole scan — dozens of requests — run over a single connection instead of
 * reconnecting per address.
 *
 * The decoding here is deliberately forgiving about *number* types (`Int` or
 * `Long`, whichever [MiniJson] produced) and strict about *structure*: a reply
 * that is not the shape the protocol promises throws, rather than being guessed
 * at. A wrong balance is worse than an error message.
 *
 * This class carries no Android and no socket of its own — it only knows how to
 * turn JSON into values — so the JVM tests drive it through
 * [MockElectrumServer](../../../../../test/java/com/xbtwallet/app/electrum/MockElectrumServer.kt).
 */
class ChainRpcSession internal constructor(
    private val call: (method: String, params: List<Any?>) -> Any?,
) : ChainQuery {

    override fun getHistory(scripthash: String): List<HistoryEntry> {
        val result = call("blockchain.scripthash.get_history", listOf(scripthash))
        val entries = result as? List<*>
            ?: throw ElectrumException("get_history did not answer with a list: ${MiniJson.encode(result)}")
        return entries.map { entry ->
            val fields = entry as? Map<*, *>
                ?: throw ElectrumException("get_history entry is not an object: ${MiniJson.encode(entry)}")
            HistoryEntry(
                txHash = fields.string("tx_hash"),
                height = fields.int("height"),
            )
        }
    }

    override fun getBalance(scripthash: String): ScripthashBalance {
        val result = call("blockchain.scripthash.get_balance", listOf(scripthash))
        val fields = result as? Map<*, *>
            ?: throw ElectrumException("get_balance did not answer with an object: ${MiniJson.encode(result)}")
        return ScripthashBalance(
            confirmedSatoshis = fields.long("confirmed"),
            unconfirmedSatoshis = fields.long("unconfirmed"),
        )
    }

    override fun listUnspent(scripthash: String): List<ChainUtxo> {
        val result = call("blockchain.scripthash.listunspent", listOf(scripthash))
        val entries = result as? List<*>
            ?: throw ElectrumException("listunspent did not answer with a list: ${MiniJson.encode(result)}")
        return entries.map { entry ->
            val fields = entry as? Map<*, *>
                ?: throw ElectrumException("listunspent entry is not an object: ${MiniJson.encode(entry)}")
            ChainUtxo(
                txHash = fields.string("tx_hash"),
                txPos = fields.int("tx_pos"),
                height = fields.int("height"),
                satoshis = fields.long("value"),
            )
        }
    }

    override fun getTransaction(txHash: String): String {
        val result = call("blockchain.transaction.get", listOf(txHash))
        return result as? String
            ?: throw ElectrumException("transaction.get did not answer with hex: ${MiniJson.encode(result)}")
    }

    override fun getTipHeight(): Int {
        val result = call("blockchain.headers.subscribe", emptyList())
        val fields = result as? Map<*, *>
            ?: throw ElectrumException("headers.subscribe did not answer with an object: ${MiniJson.encode(result)}")
        return fields.int("height")
    }

    private fun Map<*, *>.string(key: String): String = this[key] as? String
        ?: throw ElectrumException("reply field '$key' is missing or not a string: ${MiniJson.encode(this)}")

    private fun Map<*, *>.int(key: String): Int = (this[key] as? Number)?.toInt()
        ?: throw ElectrumException("reply field '$key' is missing or not a number: ${MiniJson.encode(this)}")

    private fun Map<*, *>.long(key: String): Long = (this[key] as? Number)?.toLong()
        ?: throw ElectrumException("reply field '$key' is missing or not a number: ${MiniJson.encode(this)}")
}
