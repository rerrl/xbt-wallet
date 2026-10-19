package com.xbtwallet.app.data

import com.xbtwallet.app.chain.AccountSnapshot
import com.xbtwallet.app.chain.AddressChain
import com.xbtwallet.app.chain.ChainUtxo
import com.xbtwallet.app.chain.HistoryEntry
import com.xbtwallet.app.chain.ScannedAddress
import com.xbtwallet.app.chain.ScannedUtxo
import com.xbtwallet.app.chain.ScripthashBalance
import com.xbtwallet.app.chain.TransactionRow
import com.xbtwallet.app.electrum.JsonParseException
import com.xbtwallet.app.electrum.MiniJson

/**
 * A completed account scan plus when it was taken.
 *
 * [scannedAtEpochSeconds] is the wall clock at save time, kept so a screen can
 * one day say how stale the numbers are. It is not used to decide anything yet.
 */
data class CachedAccountSnapshot(val snapshot: AccountSnapshot, val scannedAtEpochSeconds: Long)

/**
 * Persists one account's last completed scan, so a signer screen opens on its
 * balance immediately instead of blanking to "Loading…" on every visit.
 *
 * **Watch-only data only.** What is stored is exactly what a scan produced: the
 * derived addresses and their Electrum scripthashes, the history the server
 * reported for them, the UTXOs, the balance and the display rows. There are no
 * private keys here and there never can be — the app never holds any (see
 * [SignerType.WatchOnlyXpub]) — so this cache is no more sensitive than the
 * signer's own xpub, which is already on disk next to it.
 *
 * **Why per signer id.** One blob per signer (`snapshot_<id>`) means a scan of
 * one account can never be shown against another's xpub, and deleting a signer
 * can drop exactly its cache: [clear] is wired into the delete path in
 * `XbtWalletApp`, so removing a signer removes its addresses' history too.
 *
 * **Why a JSON blob in [KeyValueStore].** Same reasoning as [SignerStore]: one
 * small record, no queries, and the app's own [MiniJson] rather than a new
 * dependency. [KeyValueStore] has no `delete`, so [clear] writes a blank string,
 * which [load] reads as absent.
 *
 * **Forgiving by construction.** A watch-only cache has nothing to gain from
 * throwing: a corrupt blob, a missing key, a blank value or a blob written by a
 * future schema all read as `null`, and the screen simply does a full scan. That
 * keeps an unreadable cache a performance regression, never a crash.
 *
 * @param storage the same small key/value store the signers use (one prefs
 *   file, distinct keys).
 * @param now the save clock, injectable so tests do not depend on real time.
 */
class SnapshotStore(
    private val storage: KeyValueStore,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /**
     * The last snapshot stored for [signerId], or null when there is none (or it
     * cannot be read). Never throws.
     */
    fun load(signerId: String): CachedAccountSnapshot? {
        val raw = storage.getString(key(signerId))?.takeIf { it.isNotBlank() } ?: return null
        val root = try {
            MiniJson.parse(raw) as? Map<*, *> ?: return null
        } catch (error: JsonParseException) {
            return null
        }
        // An unknown schema is a cache we do not understand — discard it rather
        // than guess at its fields.
        if ((root["version"] as? Number)?.toInt() != VERSION) return null

        val scannedAt = (root["scannedAt"] as? Number)?.toLong() ?: return null
        val receive = decodeAddresses(root["receive"], AddressChain.RECEIVE) ?: return null
        val change = decodeAddresses(root["change"], AddressChain.CHANGE) ?: return null
        val utxos = decodeUtxos(root["utxos"]) ?: return null
        val balance = decodeBalance(root["balance"]) ?: return null
        val transactions = decodeTransactions(root["transactions"]) ?: return null

        return CachedAccountSnapshot(
            snapshot = AccountSnapshot(
                receive = receive,
                change = change,
                utxos = utxos,
                balance = balance,
                transactions = transactions,
            ),
            scannedAtEpochSeconds = scannedAt,
        )
    }

    /** Stores [snapshot] as the cache for [signerId], stamping the save time. */
    fun save(signerId: String, snapshot: AccountSnapshot) {
        val root = mapOf(
            "version" to VERSION,
            "scannedAt" to now(),
            "receive" to snapshot.receive.map { encodeAddress(it) },
            "change" to snapshot.change.map { encodeAddress(it) },
            "utxos" to snapshot.utxos.map { encodeUtxo(it) },
            "balance" to mapOf(
                "confirmed" to snapshot.balance.confirmedSatoshis,
                "unconfirmed" to snapshot.balance.unconfirmedSatoshis,
            ),
            "transactions" to snapshot.transactions.map { encodeTransaction(it) },
        )
        storage.putString(key(signerId), MiniJson.encode(root))
    }

    /**
     * Drops the cache for [signerId] — called when the signer itself is deleted.
     *
     * A blank string rather than a real delete, because [KeyValueStore] has no
     * delete and adding one for this would widen an interface the Android shell
     * and every test fake implement. [load] treats blank as absent, so the effect
     * is the same.
     */
    fun clear(signerId: String) {
        storage.putString(key(signerId), "")
    }

    private fun encodeAddress(address: ScannedAddress): Map<String, Any?> = mapOf(
        "index" to address.index,
        "address" to address.address,
        "scripthash" to address.scripthash,
        "used" to address.used,
        "scriptPubKeyHex" to address.scriptPubKeyHex,
        "history" to address.history.map { entry ->
            mapOf("txHash" to entry.txHash, "height" to entry.height)
        },
    )

    private fun encodeUtxo(scanned: ScannedUtxo): Map<String, Any?> = mapOf(
        "txHash" to scanned.utxo.txHash,
        "txPos" to scanned.utxo.txPos,
        "height" to scanned.utxo.height,
        "satoshis" to scanned.utxo.satoshis,
        "chain" to scanned.chain.index,
        "addressIndex" to scanned.addressIndex,
    )

    private fun encodeTransaction(row: TransactionRow): Map<String, Any?> = mapOf(
        "txHash" to row.txHash,
        "height" to row.height,
        "confirmations" to row.confirmations,
        "netSatoshis" to row.netSatoshis,
    )

    /** Decodes one chain's address list; null on any entry that cannot be read. */
    private fun decodeAddresses(value: Any?, chain: AddressChain): List<ScannedAddress>? {
        val entries = value as? List<*> ?: return null
        val result = ArrayList<ScannedAddress>(entries.size)
        for (entry in entries) {
            result += decodeAddress(entry, chain) ?: return null
        }
        return result
    }

    private fun decodeAddress(entry: Any?, chain: AddressChain): ScannedAddress? {
        val map = entry as? Map<*, *> ?: return null
        val index = (map["index"] as? Number)?.toInt() ?: return null
        val address = map["address"] as? String ?: return null
        val scripthash = map["scripthash"] as? String ?: return null
        val used = map["used"] as? Boolean ?: return null
        val historyEntries = map["history"] as? List<*> ?: return null
        val history = ArrayList<HistoryEntry>(historyEntries.size)
        for (historyEntry in historyEntries) {
            val historyMap = historyEntry as? Map<*, *> ?: return null
            val txHash = historyMap["txHash"] as? String ?: return null
            val height = (historyMap["height"] as? Number)?.toInt() ?: return null
            history += HistoryEntry(txHash = txHash, height = height)
        }
        return ScannedAddress(
            chain = chain,
            index = index,
            address = address,
            scripthash = scripthash,
            used = used,
            history = history,
            scriptPubKeyHex = map["scriptPubKeyHex"] as? String ?: "",
        )
    }

    private fun decodeUtxos(value: Any?): List<ScannedUtxo>? {
        val entries = value as? List<*> ?: return null
        val result = ArrayList<ScannedUtxo>(entries.size)
        for (entry in entries) {
            val map = entry as? Map<*, *> ?: return null
            val txHash = map["txHash"] as? String ?: return null
            val txPos = (map["txPos"] as? Number)?.toInt() ?: return null
            val height = (map["height"] as? Number)?.toInt() ?: return null
            val satoshis = (map["satoshis"] as? Number)?.toLong() ?: return null
            val chainIndex = (map["chain"] as? Number)?.toLong() ?: return null
            val addressIndex = (map["addressIndex"] as? Number)?.toInt() ?: return null
            val chain = AddressChain.entries.firstOrNull { it.index == chainIndex } ?: return null
            result += ScannedUtxo(
                utxo = ChainUtxo(txHash = txHash, txPos = txPos, height = height, satoshis = satoshis),
                chain = chain,
                addressIndex = addressIndex,
            )
        }
        return result
    }

    private fun decodeBalance(value: Any?): ScripthashBalance? {
        val map = value as? Map<*, *> ?: return null
        val confirmed = (map["confirmed"] as? Number)?.toLong() ?: return null
        val unconfirmed = (map["unconfirmed"] as? Number)?.toLong() ?: return null
        return ScripthashBalance(confirmedSatoshis = confirmed, unconfirmedSatoshis = unconfirmed)
    }

    private fun decodeTransactions(value: Any?): List<TransactionRow>? {
        val entries = value as? List<*> ?: return null
        val result = ArrayList<TransactionRow>(entries.size)
        for (entry in entries) {
            val map = entry as? Map<*, *> ?: return null
            val txHash = map["txHash"] as? String ?: return null
            val height = (map["height"] as? Number)?.toInt() ?: return null
            val confirmations = (map["confirmations"] as? Number)?.toInt() ?: return null
            val netSatoshis = (map["netSatoshis"] as? Number)?.toLong() ?: return null
            result += TransactionRow(
                txHash = txHash,
                height = height,
                confirmations = confirmations,
                netSatoshis = netSatoshis,
            )
        }
        return result
    }

    private fun key(signerId: String): String = "$KEY_PREFIX$signerId"

    companion object {
        /** Schema version; a blob bearing any other value is discarded unread. */
        const val VERSION: Int = 1

        /**
         * Key prefix for one signer's cache. The signer id is appended, so each
         * signer gets its own blob in the same prefs file as the signer list.
         */
        const val KEY_PREFIX: String = "snapshot_"
    }
}
