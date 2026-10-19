package com.xbtwallet.app.data

import com.xbtwallet.app.electrum.JsonParseException
import com.xbtwallet.app.electrum.MiniJson

/**
 * What kind of signer a [Signer] pairs with.
 *
 * Sealed, with exactly one live variant today: [WatchOnlyXpub]. A `HotSeed`
 * variant — a signer whose seed would live on this phone — is deliberately
 * **absent**, and reserved by name only: adding one would mean carrying private
 * key material, which is the one thing this app must never do (PLAN.md §2.1,
 * "keys never touch the phone"). There is no seed type, no seed storage and no
 * code path that could produce one, so a `when` over this type has nothing to
 * ignore.
 */
sealed interface SignerType {
    /**
     * A watch-only signer: the phone holds this account's extended public key
     * and nothing else, so it can watch the account and derive addresses but can
     * never sign for it.
     *
     * The xpub is stored exactly as the user entered it (trimmed); the
     * derivation code re-reads the version bytes on every use.
     *
     * [masterFingerprint] and [derivationPath] are optional metadata captured
     * from a BIP-32 key-expression QR (`[ab12cd34/84'/0'/0']zpub…`) — the master
     * fingerprint cannot be derived from the xpub, so it has to come off the QR,
     * and the later PSBT signing flow will want both to name the signer. A bare
     * key or a `bitcoin:` URI leaves them null, which is the common case; the
     * defaults keep every pre-metadata record (and call site) valid.
     */
    data class WatchOnlyXpub(
        val xpub: String,
        val masterFingerprint: String? = null,
        val derivationPath: String? = null,
    ) : SignerType
}

/**
 * One signer the app pairs with: a user-given [name] plus what kind of signer it
 * is.
 *
 * [name] is *this* app's label — it is never sent anywhere, and it is what makes
 * a list of signers legible. The container has no notion of "active": a signer
 * is just an entry in a list, and everything that acts on it happens inside that
 * signer's own screen.
 */
data class Signer(
    val id: String,
    val name: String,
    val type: SignerType,
    val createdAtEpochSeconds: Long,
) {
    /**
     * The short type badge shown next to a signer's name in lists.
     *
     * Derived from [type] rather than stored, so the label can never disagree
     * with the data.
     */
    val typeLabel: String
        get() = when (type) {
            is SignerType.WatchOnlyXpub -> WATCH_ONLY_LABEL
        }

    companion object {
        /** Badge for [SignerType.WatchOnlyXpub] — the only signer kind that exists. */
        const val WATCH_ONLY_LABEL = "watch-only"
    }
}

/**
 * The smallest storage abstraction the app needs: get/put one string.
 *
 * Keeping the store behind this interface rather than behind `Context` is what
 * makes [SignerStore] unit-testable on the JVM — the Android implementation is
 * a five-line shell ([SharedPreferencesKeyValueStore]) and the test one is a
 * map.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/**
 * Persists the list of signers as one JSON blob.
 *
 * Three deliberate choices, all from PLAN.md:
 *  - **A list keyed by id, not a single slot.** Multiple signers are assumed
 *    from day one (§3, "Persistence"), so a UI that showed one at a time was a
 *    limitation of the screens, never of the schema.
 *  - **JSON in SharedPreferences, hand-rolled.** One small record, no query
 *    needs, no new dependency. It reuses the app's own [MiniJson] rather than
 *    pulling in a JSON library for a few fields (§2.4).
 *  - **In-place migration of older data.** Signers used to be called wallets;
 *    an existing install has a `wallets` blob under the old key. On first read
 *    [read] converts that blob to signers and rewrites it under the new key, so
 *    nobody loses the xpubs they already imported (see [LEGACY_STORAGE_KEY]).
 *
 * The timestamp and id sources are injectable so tests do not depend on the
 * clock or on randomness.
 */
class SignerStore(
    private val storage: KeyValueStore,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {

    /** Every stored signer, oldest first. */
    fun list(): List<Signer> = read()

    fun count(): Int = read().size

    fun get(id: String): Signer? = read().firstOrNull { it.id == id }

    /** Creates and persists a signer, returning it so the caller has the new id. */
    fun add(
        name: String,
        xpub: String,
        masterFingerprint: String? = null,
        derivationPath: String? = null,
    ): Signer {
        val signer = Signer(
            id = newId(),
            name = name.trim(),
            type = SignerType.WatchOnlyXpub(
                xpub = xpub.trim(),
                masterFingerprint = masterFingerprint,
                derivationPath = derivationPath,
            ),
            createdAtEpochSeconds = now(),
        )
        saveAll(read() + signer)
        return signer
    }

    /** Removes one signer; returns false when the id was not present. */
    fun delete(id: String): Boolean {
        val current = read()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        saveAll(remaining)
        return true
    }

    /** Overwrites the whole list — the one write path, so there is one JSON shape. */
    fun saveAll(signers: List<Signer>) {
        val json = MiniJson.encode(
            mapOf(
                "signers" to signers.map { signer -> encodeSigner(signer) },
            ),
        )
        storage.putString(STORAGE_KEY, json)
    }

    /**
     * Reads the stored list, migrating legacy wallet data on the way.
     *
     * Order matters. The new key wins whenever it is present, so a store that
     * has already been written to (including one that was deliberately emptied)
     * is never re-seeded from the old blob. Only when the new key is absent —
     * an install that predates the rename — is the legacy `wallets` blob read,
     * converted, and written forward.
     *
     * Anything unreadable degrades to "no signers" rather than throwing: a
     * watch-only store has nothing to lose by being forgiving, whereas an
     * exception in the middle of composition would take the whole app down.
     */
    private fun read(): List<Signer> {
        storage.getString(STORAGE_KEY)?.let { raw -> return parseSigners(raw) }

        val legacy = storage.getString(LEGACY_STORAGE_KEY) ?: return emptyList()
        val migrated = parseLegacyWallets(legacy)
        // Only write when there was something to migrate — a corrupt or empty
        // legacy blob should not create a new key that then blocks a later,
        // genuine migration (there is no key removal on [KeyValueStore]).
        if (migrated.isNotEmpty()) saveAll(migrated)
        return migrated
    }

    private fun encodeSigner(signer: Signer): Map<String, Any?> = when (val type = signer.type) {
        is SignerType.WatchOnlyXpub -> buildMap {
            put("id", signer.id)
            put("name", signer.name)
            put("type", Signer.WATCH_ONLY_LABEL)
            put("xpub", type.xpub)
            // Written only when present, so a bare-key signer's blob keeps the
            // exact shape it had before these fields existed — and a reader that
            // does not know them still parses the record.
            type.masterFingerprint?.let { put("masterFingerprint", it) }
            type.derivationPath?.let { put("derivationPath", it) }
            put("createdAt", signer.createdAtEpochSeconds)
        }
    }

    /** Parses the current (`signers`) shape; unreadable entries are skipped. */
    private fun parseSigners(raw: String): List<Signer> {
        val entries = try {
            val root = MiniJson.parse(raw) as? Map<*, *> ?: return emptyList()
            root["signers"] as? List<*> ?: return emptyList()
        } catch (e: JsonParseException) {
            return emptyList()
        }
        return entries.mapNotNull(::toSigner)
    }

    /**
     * Reads the pre-rename `wallets` shape into signers.
     *
     * Every legacy wallet was, by construction, a watch-only xpub — that was the
     * only thing it could store — so the conversion is total: id, name and
     * timestamp carry over unchanged and the xpub becomes the signer's
     * [SignerType.WatchOnlyXpub].
     */
    private fun parseLegacyWallets(raw: String): List<Signer> {
        val entries = try {
            val root = MiniJson.parse(raw) as? Map<*, *> ?: return emptyList()
            root["wallets"] as? List<*> ?: return emptyList()
        } catch (e: JsonParseException) {
            return emptyList()
        }
        return entries.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"] as? String ?: return@mapNotNull null
            val name = map["name"] as? String ?: return@mapNotNull null
            val xpub = map["xpub"] as? String ?: return@mapNotNull null
            Signer(
                id = id,
                name = name,
                type = SignerType.WatchOnlyXpub(xpub),
                createdAtEpochSeconds = (map["createdAt"] as? Number)?.toLong() ?: 0L,
            )
        }
    }

    private fun toSigner(entry: Any?): Signer? {
        val map = entry as? Map<*, *> ?: return null
        val id = map["id"] as? String ?: return null
        val name = map["name"] as? String ?: return null
        val createdAt = (map["createdAt"] as? Number)?.toLong() ?: 0L
        // The type discriminator decides how to read the rest of the record.
        // Unknown kinds (there are none today) are skipped rather than guessed
        // at: a signer this build cannot interpret must not be shown as if it
        // could.
        return when (map["type"] as? String) {
            Signer.WATCH_ONLY_LABEL -> {
                val xpub = map["xpub"] as? String ?: return null
                // Metadata keys are absent on every pre-metadata record; a
                // missing key reads as null rather than failing the entry.
                Signer(
                    id,
                    name,
                    SignerType.WatchOnlyXpub(
                        xpub = xpub,
                        masterFingerprint = map["masterFingerprint"] as? String,
                        derivationPath = map["derivationPath"] as? String,
                    ),
                    createdAt,
                )
            }
            else -> null
        }
    }

    companion object {
        /**
         * SharedPreferences key holding the current, signer-shaped list.
         *
         * This is the key [read] prefers; everything the store writes lands
         * here.
         */
        const val STORAGE_KEY = "signers"

        /**
         * The key the pre-rename [STORAGE_KEY] used, holding `{"wallets":[…]}`.
         *
         * Read once, converted, and then left alone: [KeyValueStore] cannot
         * delete, and leaving it costs one stale blob while making the migration
         * idempotent if the new key is ever cleared.
         */
        const val LEGACY_STORAGE_KEY = "wallets"
    }
}
