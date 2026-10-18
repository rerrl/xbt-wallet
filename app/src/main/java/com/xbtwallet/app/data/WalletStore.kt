package com.xbtwallet.app.data

import com.xbtwallet.app.electrum.JsonParseException
import com.xbtwallet.app.electrum.MiniJson

/**
 * One watch-only wallet: a user-chosen label plus the account-level extended
 * public key it was imported from.
 *
 * Only public material lives here. The xpub is stored exactly as the user
 * entered it (trimmed), so re-opening the wallet shows them back what they
 * pasted; the derivation code re-reads the version bytes on every use.
 */
data class Wallet(
    val id: String,
    val name: String,
    val xpub: String,
    val createdAtEpochSeconds: Long,
)

/**
 * The smallest storage abstraction the app needs: get/put one string.
 *
 * Keeping the store behind this interface rather than behind `Context` is what
 * makes [WalletStore] unit-testable on the JVM — the Android implementation is
 * a five-line shell ([SharedPreferencesKeyValueStore]) and the test one is a
 * map.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/**
 * Persists the list of imported wallets as one JSON blob.
 *
 * Two deliberate choices, both from PLAN.md:
 *  - **A list keyed by id, not a single slot.** Multi-wallet is assumed from day
 *    one (§3, "Persistence"), so the v0.1.0 UI writing one wallet is a
 *    limitation of the screens, not of the schema.
 *  - **JSON in SharedPreferences, hand-rolled.** One small record, no query
 *    needs, no new dependency. It reuses the wallet's own [MiniJson] rather than
 *    pulling in a JSON library for four fields (§2.4).
 *
 * The timestamp and id sources are injectable so tests do not depend on the
 * clock or on randomness.
 */
class WalletStore(
    private val storage: KeyValueStore,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {

    /** Every stored wallet, oldest first. */
    fun list(): List<Wallet> = read()

    fun count(): Int = read().size

    fun get(id: String): Wallet? = read().firstOrNull { it.id == id }

    /** Creates and persists a wallet, returning it so the caller has the new id. */
    fun add(name: String, xpub: String): Wallet {
        val wallet = Wallet(
            id = newId(),
            name = name.trim(),
            xpub = xpub.trim(),
            createdAtEpochSeconds = now(),
        )
        saveAll(read() + wallet)
        return wallet
    }

    /** Removes one wallet; returns false when the id was not present. */
    fun delete(id: String): Boolean {
        val current = read()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        saveAll(remaining)
        return true
    }

    /** Overwrites the whole list — the one write path, so there is one JSON shape. */
    fun saveAll(wallets: List<Wallet>) {
        val json = MiniJson.encode(
            mapOf(
                "wallets" to wallets.map { wallet ->
                    mapOf(
                        "id" to wallet.id,
                        "name" to wallet.name,
                        "xpub" to wallet.xpub,
                        "createdAt" to wallet.createdAtEpochSeconds,
                    )
                },
            ),
        )
        storage.putString(STORAGE_KEY, json)
    }

    /**
     * Reads the stored list, degrading to "no wallets" for anything unreadable.
     *
     * A watch-only store has nothing to lose by being forgiving here: the worst
     * case is that the user re-imports a public key, whereas throwing inside
     * composition would take the whole app down. Individual entries that are
     * missing required fields are skipped for the same reason.
     */
    private fun read(): List<Wallet> {
        val raw = storage.getString(STORAGE_KEY) ?: return emptyList()
        val entries = try {
            val root = MiniJson.parse(raw) as? Map<*, *> ?: return emptyList()
            root["wallets"] as? List<*> ?: return emptyList()
        } catch (e: JsonParseException) {
            return emptyList()
        }
        return entries.mapNotNull(::toWallet)
    }

    private fun toWallet(entry: Any?): Wallet? {
        val map = entry as? Map<*, *> ?: return null
        return Wallet(
            id = map["id"] as? String ?: return null,
            name = map["name"] as? String ?: return null,
            xpub = map["xpub"] as? String ?: return null,
            createdAtEpochSeconds = (map["createdAt"] as? Number)?.toLong() ?: 0L,
        )
    }

    companion object {
        /** SharedPreferences key holding the whole wallet list. */
        const val STORAGE_KEY = "wallets"
    }
}
