package com.xbtwallet.app.data

import com.xbtwallet.app.electrum.JsonParseException
import com.xbtwallet.app.electrum.MiniJson

/**
 * What kind of signer a [Signer] pairs with.
 *
 * Two live variants:
 *
 * - [WatchOnlyXpub] — the phone holds an account's extended public key and can
 *   watch it, and can never sign for it. This is what a text-QR paste and a
 *   signing-device (BC-UR) export both produce.
 * - [HotSeed] — a wallet whose seed *is* on this phone: the phone can watch it
 *   (the same passphrased account zpub), and it can also spend, because the
 *   seed's entropy sits in the [SeedVault] behind the Android keystore. It is
 *   watch-only *until it is asked to sign* — nothing about listing, scanning or
 *   receiving from it differs from an xpub-only wallet.
 *
 * The distinction is deliberately in the *type*, not in a flag: everything that
 * has to behave differently for a wallet that can spend — the tag it wears, the
 * delete-confirmation copy, `signingReady` — is a `when` over this sealed
 * interface, so a new kind of signer cannot be added without the compiler
 * asking every one of those places what it means.
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
     * [masterFingerprint] and [derivationPath] are optional metadata that arrives
     * **only** from a signing-device import. The master fingerprint cannot be
     * derived from the xpub, so it has to come off the device's BC-UR export, and
     * the later PSBT signing flow will want both to name the signer. A watch-only
     * text-QR import deliberately discards any such metadata it saw — a text
     * import must never become signing-ready — and a bare key or a `bitcoin:` URI
     * leaves them null; the defaults keep every pre-metadata record (and call
     * site) valid.
     */
    data class WatchOnlyXpub(
        val xpub: String,
        val masterFingerprint: String? = null,
        val derivationPath: String? = null,
    ) : SignerType

    /**
     * A hot wallet: this phone holds the seed.
     *
     * The record itself carries **no secret**. [xpub] is the account zpub of the
     * *passphrased* key — the wallet's public identity, and the same shape a
     * watch-only signer stores, so the whole watching half of the app works on a
     * hot wallet unchanged. The entropy that can spend it lives in the
     * [SeedVault], under the signer's id, and the passphrase to go with it exists
     * only in the user's head (and on their written-down paper).
     *
     * Both [xpub] and [masterFingerprint] are the **passphrased** values. That
     * is not a detail: it is how the passphrase is verified at spend time — the
     * UI re-derives from the vault's entropy plus the passphrase the user typed
     * and compares the account zpub ([HotWalletProvisioner.passphraseMatches]).
     * No passphrase hash is stored anywhere, so there is nothing extra to leak
     * and nothing to keep in sync.
     *
     * [masterFingerprint] is eight lowercase hex characters, the same format a
     * signing-device import stores on
     * [WatchOnlyXpub.masterFingerprint] — one fingerprint format across the app,
     * so a hot wallet and a hardware wallet of the same seed are recognisable as
     * the same key.
     */
    data class HotSeed(
        /** The **passphrased** account zpub — the wallet's public identity. */
        val xpub: String,
        /** Eight lowercase hex characters: the passphrased master key's fingerprint. */
        val masterFingerprint: String,
        /** Always the BIP-84 account path today; stored so the record is self-describing. */
        val derivationPath: String = DEFAULT_HOT_DERIVATION_PATH,
        /** Whether the user chose a BIP-39 passphrase when this wallet was created. */
        val hasPassphrase: Boolean,
    ) : SignerType

    companion object {
        /** The account path a hot wallet is derived at (BIP-84, first account, mainnet). */
        const val DEFAULT_HOT_DERIVATION_PATH = "84'/0'/0'"
    }
}

/**
 * How a signer's account reached this phone.
 *
 * This is a *provenance* field, not a security property: every path ends by
 * storing the very same [SignerType.WatchOnlyXpub], and none of them gives the
 * app anything it could sign with. What differs is only where the user got the
 * key — typed/pasted or scanned as text ([TEXT_QR]), read off a SeedSigner's
 * crypto-account export over animated BC-UR ([SEEDSIGNER_UR]), or read off some
 * *other* signing device's export the same way ([SIGNING_DEVICE_UR]) — and the
 * one place that matters today is the tag the UI shows.
 *
 * The three paths are also the three choices of the Add-wallet flow, which is
 * what makes the origin *declared* rather than inferred from the payload: the
 * user says which device they are holding, and that answer is what is recorded.
 *
 * [storageValue] is the wire form written into the JSON blob. It is spelled out
 * rather than reusing [name] so a rename of the Kotlin constant can never
 * silently invalidate data already on someone's phone.
 */
enum class SignerOrigin(val storageValue: String) {
    /** The key was scanned or pasted as a plain text xpub — the original path. */
    TEXT_QR("text-qr"),

    /**
     * The key came off a **SeedSigner's** crypto-account export, over animated
     * BC-UR. This is the path the chooser's first row leads to, and the one that
     * wears the orange "seedsigner" tag.
     *
     * Its wire value is deliberately the one this field has written for a BC-UR
     * import from the beginning: before the origin was split in two, *every*
     * signing-device import was recorded as `signing-device-ur`. Because the
     * SeedSigner path is the only one that existed — and the only BC-UR signer
     * this app was built around — reading a stored `signing-device-ur` back as
     * [SEEDSIGNER_UR] *is* the migration. Nothing already on disk has to be
     * rewritten, and a record Andrew imported before this build keeps exactly the
     * tag it is showing today.
     */
    SEEDSIGNER_UR("signing-device-ur"),

    /**
     * The key came off some **other** signing device's crypto-account export
     * (Keystone, Jade), over animated BC-UR.
     *
     * A wire value of its own, because the historical `signing-device-ur` now
     * means SeedSigner: only this build writes this one, so no stored record
     * needs a rule for it. It wears a neutral-but-distinct "signing device" tag,
     * because the orange one would claim more than the user told us.
     */
    SIGNING_DEVICE_UR("other-signing-device-ur"),

    /**
     * The wallet's seed was generated or imported **on this phone** — a hot
     * wallet. This is the only origin that describes a signer the app can sign
     * with, and the only one whose record has an entry in the [SeedVault].
     *
     * Its wire value is spelled out for the same reason as the others: it is
     * written into the blob and read back by name, so renaming the Kotlin
     * constant can never invalidate data already on someone's phone. It is a
     * value no earlier build wrote, so — unlike the historical
     * `signing-device-ur` — there is nothing on disk for it to be confused with.
     */
    HOT("hot"),
    ;

    companion object {
        /**
         * Reads the stored form, defaulting to [TEXT_QR].
         *
         * Two cases land on that default and both want it: a blob written before
         * this field existed (no key at all) and a value this build does not
         * recognise. Guessing a device path would be the dangerous way to be
         * wrong — it would claim a key came off a hardware screen when nothing
         * says it did — whereas [TEXT_QR] is the conservative, "we only know it
         * is a key" answer.
         *
         * The one value that is *not* a fallback is the historical
         * `signing-device-ur`, which belongs to [SEEDSIGNER_UR] and is matched by
         * the ordinary lookup below — see that constant for why.
         */
        fun fromStorage(value: String?): SignerOrigin =
            SignerOrigin.entries.firstOrNull { it.storageValue == value } ?: TEXT_QR
    }
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
    /**
     * Which import path put this signer on the phone — see [SignerOrigin].
     *
     * Defaults to [SignerOrigin.TEXT_QR] so every record written before this
     * field existed (and every call site that does not care) stays valid and
     * keeps reading — and rendering — exactly as it did.
     */
    val importedVia: SignerOrigin = SignerOrigin.TEXT_QR,
) {
    /**
     * The label for this signer's *type* — [WATCH_ONLY_LABEL] for an xpub-only
     * wallet, [HOT_LABEL] for one whose seed is on this phone.
     *
     * Derived from [type] rather than stored, so the label can never disagree
     * with the data. It says nothing about [importedVia]: the tag a signer
     * actually wears is chosen by
     * [com.xbtwallet.app.ui.signers.signerTagFor], which pairs this label with
     * the origin's own label and accent.
     */
    val typeLabel: String
        get() = when (type) {
            is SignerType.WatchOnlyXpub -> WATCH_ONLY_LABEL
            is SignerType.HotSeed -> HOT_LABEL
        }

    /**
     * True when this signer can actually produce signatures — not merely watch the
     * account.
     *
     * Signing-readiness is **computed from the record, never stored**, and there
     * are two ways to earn it.
     *
     * A [SignerType.HotSeed] is signing-ready **by construction**: the record
     * only ever exists alongside its seed in the [SeedVault] (the vault write
     * happens first — see [HotWalletProvisioner.provision]), so the seed *is*
     * here. What the app still cannot do with it without asking is sign: the
     * passphrase may not be the empty one, and that is checked at spend time by
     * re-derivation, not by a flag on the record.
     *
     * A [SignerType.WatchOnlyXpub] must carry both
     * [SignerType.WatchOnlyXpub.masterFingerprint] and
     * [SignerType.WatchOnlyXpub.derivationPath] — exactly the fields a PSBT's
     * BIP32 derivation records require, neither of which can be derived from the
     * xpub after the fact — **and** the signer must have come off a **signing
     * device** ([SignerOrigin.SEEDSIGNER_UR] / [SignerOrigin.SIGNING_DEVICE_UR]).
     *
     * **A text-QR import is never signing-ready.** Metadata reaches this app only
     * through a signing-device (BC-UR) import; a text-QR record loads with both
     * fields already dropped (see [SignerStore.toSigner]), and this rule refuses
     * its origin outright on top of that, so a key that arrived as text can never
     * grow into a signing one.
     */
    val signingReady: Boolean
        get() = when (val kind = type) {
            is SignerType.HotSeed -> true
            is SignerType.WatchOnlyXpub ->
                importedVia != SignerOrigin.TEXT_QR &&
                    kind.masterFingerprint != null &&
                    kind.derivationPath != null
        }

    companion object {
        /** Badge for [SignerType.WatchOnlyXpub]: a wallet this phone can only watch. */
        const val WATCH_ONLY_LABEL = "watch-only"

        /**
         * Badge for [SignerType.HotSeed], and its `type` discriminator in the
         * stored JSON — the same word, deliberately: it is the word the record
         * is written and read under, and the word the tag shows.
         */
        const val HOT_LABEL = "hot"
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
    /**
     * The hot-wallet seed vault, when this store is wired to one.
     *
     * Optional, and null by default, because a store that never sees a hot
     * wallet should not have to have a vault: the signing-device and text-QR
     * paths pass nothing, and every test of them stays a store over a map. It is
     * used for exactly one thing — [delete] purges a removed
     * [SignerType.HotSeed]'s entry — so the store never reads seed material and
     * never writes it; the entropy goes in through [HotWalletProvisioner].
     */
    private val seedVault: SeedVault? = null,
) {

    /** Every stored signer, oldest first. */
    fun list(): List<Signer> = read()

    fun count(): Int = read().size

    fun get(id: String): Signer? = read().firstOrNull { it.id == id }

    /**
     * Creates and persists a signer, returning it so the caller has the new id.
     *
     * [importedVia] records *which path* the user took to get the key here — see
     * [SignerOrigin]. It defaults to [SignerOrigin.TEXT_QR], the original
     * scan/paste path, so existing callers keep compiling and keep meaning what
     * they meant. The origin is not just a tag: a [SignerOrigin.TEXT_QR] record is
     * read back with its fingerprint and path dropped (see [toSigner]), so a text
     * import is never signing-ready whatever is passed here. A signing-device
     * origin keeps the metadata it was given.
     */
    fun add(
        name: String,
        xpub: String,
        masterFingerprint: String? = null,
        derivationPath: String? = null,
        importedVia: SignerOrigin = SignerOrigin.TEXT_QR,
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
            importedVia = importedVia,
        )
        saveAll(read() + signer)
        return signer
    }

    /**
     * Creates and persists a **hot** signer, returning it so the caller has the
     * new id — and so it can be handed the seed that goes with it.
     *
     * The record it writes carries the passphrased account zpub and fingerprint
     * and no secret at all; [HotWalletProvisioner] is what puts the entropy in
     * the vault — before calling this, so the id has to be known first (see
     * [allocateId]). The origin is stamped [SignerOrigin.HOT] here rather than
     * taken as an argument: there is exactly one way a hot wallet arrives, so
     * there is nothing for a caller to get wrong.
     */
    fun addHot(
        name: String,
        xpub: String,
        masterFingerprint: String,
        derivationPath: String = SignerType.DEFAULT_HOT_DERIVATION_PATH,
        hasPassphrase: Boolean,
    ): Signer = addHotWithId(
        id = newId(),
        name = name,
        xpub = xpub,
        masterFingerprint = masterFingerprint,
        derivationPath = derivationPath,
        hasPassphrase = hasPassphrase,
    )

    /**
     * [addHot] under an id the caller already chose.
     *
     * This is the seam the vault ordering needs: [HotWalletProvisioner] has to
     * write the entropy under the signer's id, and it must do that *before* the
     * record exists — otherwise a crash in between would leave a wallet listed
     * on Home that can never spend. So it allocates the id first
     * ([allocateId]), fills the vault, and only then creates the record.
     */
    internal fun addHotWithId(
        id: String,
        name: String,
        xpub: String,
        masterFingerprint: String,
        derivationPath: String = SignerType.DEFAULT_HOT_DERIVATION_PATH,
        hasPassphrase: Boolean,
    ): Signer {
        val signer = Signer(
            id = id,
            name = name.trim(),
            type = SignerType.HotSeed(
                xpub = xpub.trim(),
                masterFingerprint = masterFingerprint.trim(),
                derivationPath = derivationPath,
                hasPassphrase = hasPassphrase,
            ),
            createdAtEpochSeconds = now(),
            importedVia = SignerOrigin.HOT,
        )
        saveAll(read() + signer)
        return signer
    }

    /**
     * A fresh signer id, with no record written for it.
     *
     * Exposed for one caller — see [addHotWithId] — because the hot-seed vault
     * is keyed by signer id and has to be written before the record that will
     * own it. Everything else gets its id from [add]/[addHot].
     */
    internal fun allocateId(): String = newId()

    /**
     * Removes one signer; returns false when the id was not present.
     *
     * A [SignerType.HotSeed] takes its secret with it: its [SeedVault] entry —
     * and the keystore key that decrypts it — is purged as well, so removing a
     * hot wallet is the destructive act the confirmation dialog says it is. A
     * watch-only signer never had a vault entry, so removing one does not touch
     * the vault at all: "nothing is deleted on the signer itself" stays true.
     *
     * Only the vault is handled here. The cached chain snapshot lives in
     * [SnapshotStore] and is cleared by the caller that owns both stores (the
     * wiring in `XbtWalletApp`), which is where the delete action already runs.
     */
    fun delete(id: String): Boolean {
        val current = read()
        val removed = current.firstOrNull { it.id == id } ?: return false
        saveAll(current.filterNot { it.id == id })
        // Purge after the record is gone: a signer no longer in the list cannot
        // be read by the UI, so the worst a failure here leaves behind is an
        // orphaned blob rather than a live wallet whose seed has been destroyed.
        if (removed.type is SignerType.HotSeed) seedVault?.delete(id)
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
            // Same rule for the origin: a text-QR import is the default the
            // reader already assumes, so writing it would grow every existing
            // blob by a key that carries no information. Only a signer that did
            // *not* come the usual way spells it out — which is also what keeps
            // blobs written by this build readable by the build before it.
            if (signer.importedVia != SignerOrigin.TEXT_QR) {
                put(IMPORTED_VIA_KEY, signer.importedVia.storageValue)
            }
            put("createdAt", signer.createdAtEpochSeconds)
        }
        // A hot wallet is a shape of its own, and every field of it is always
        // written: unlike the watch-only record there is no "absent means the old
        // default" story to preserve, because no earlier build wrote this type at
        // all. The origin is spelled out even though it is not the text-QR
        // default, so the blob says what it is rather than leaving the reader to
        // infer "hot" from the type tag.
        is SignerType.HotSeed -> buildMap {
            put("id", signer.id)
            put("name", signer.name)
            put("type", Signer.HOT_LABEL)
            put("xpub", type.xpub)
            put("masterFingerprint", type.masterFingerprint)
            put("derivationPath", type.derivationPath)
            put("hasPassphrase", type.hasPassphrase)
            put(IMPORTED_VIA_KEY, signer.importedVia.storageValue)
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
                // missing key reads as null rather than failing the entry. The
                // same goes for the origin: a blob from before the field existed
                // has no [IMPORTED_VIA_KEY], and [SignerOrigin.fromStorage]
                // reads that — and anything unrecognised — as the text-QR
                // default, so an old signer loads exactly as it always did.
                val importedVia = SignerOrigin.fromStorage(map[IMPORTED_VIA_KEY] as? String)
                // A text-QR import is watch-only by ruling, so its fingerprint and
                // path are dropped here — in memory, on every read — whatever the
                // blob happens to hold (an install that imported a key-expression
                // QR before this rule therefore becomes truly watch-only on its
                // next read). The stored blob is never rewritten: only a
                // signing-device (BC-UR) import keeps its metadata.
                val keepMetadata = importedVia != SignerOrigin.TEXT_QR
                Signer(
                    id,
                    name,
                    SignerType.WatchOnlyXpub(
                        xpub = xpub,
                        masterFingerprint = if (keepMetadata) map["masterFingerprint"] as? String else null,
                        derivationPath = if (keepMetadata) map["derivationPath"] as? String else null,
                    ),
                    createdAt,
                    importedVia = importedVia,
                )
            }
            // A hot wallet. It is read as it is written: the zpub and the
            // fingerprint are required — without them the record names no key —
            // while the derivation path and the passphrase flag have defaults, so
            // a blob missing them still parses rather than vanishing.
            //
            // The origin falls back to HOT when the key is absent, which is the
            // one place this reader does not use the TEXT_QR default: a record
            // whose own type tag says "hot" describes a wallet holding its seed
            // on this phone, and reading it as a text-xpub import would paint it
            // with the grey tag that means "nothing to lose here". This build
            // always writes the key, so the fallback is for a blob from somewhere
            // else — not a migration.
            Signer.HOT_LABEL -> {
                val xpub = map["xpub"] as? String ?: return null
                val fingerprint = map["masterFingerprint"] as? String ?: return null
                val path = map["derivationPath"] as? String
                    ?: SignerType.DEFAULT_HOT_DERIVATION_PATH
                val hasPassphrase = map["hasPassphrase"] as? Boolean ?: false
                val storedOrigin = map[IMPORTED_VIA_KEY] as? String
                    ?: SignerOrigin.HOT.storageValue
                Signer(
                    id,
                    name,
                    SignerType.HotSeed(
                        xpub = xpub,
                        masterFingerprint = fingerprint,
                        derivationPath = path,
                        hasPassphrase = hasPassphrase,
                    ),
                    createdAt,
                    importedVia = SignerOrigin.fromStorage(storedOrigin),
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
         * JSON key holding a signer's [SignerOrigin], written only when it is
         * not the [SignerOrigin.TEXT_QR] default.
         *
         * Absent on every record written before the origin existed, which is
         * exactly why the reader treats a missing key as the default rather than
         * as a malformed entry.
         */
        const val IMPORTED_VIA_KEY = "importedVia"

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
