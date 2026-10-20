package com.xbtwallet.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SignerStore tests.
 *
 * The store talks to a [KeyValueStore] rather than to SharedPreferences
 * directly, so the whole thing is exercised on the JVM with an in-memory map —
 * no Robolectric, no Android framework, no emulator.
 *
 * Two things are being pinned down here. First, the signer model: a signer is a
 * name plus a type, there is no "active" signer, and a watch-only xpub is the
 * only kind that exists. Second, the *migration*: an install that predates the
 * rename has its xpubs under the old `wallets` blob, and none of them may be
 * lost when the app first reads them back as signers.
 */
class SignerStoreTest {

    /** The simplest possible [KeyValueStore]: a map that survives instances. */
    private class InMemoryStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    private var clock = 1_700_000_000L
    private var idCounter = 0

    private fun newStore(backing: InMemoryStore) = SignerStore(
        storage = backing,
        now = { ++clock },
        newId = { "id-${++idCounter}" },
    )

    // --- the signer model --------------------------------------------------

    @Test
    fun `a fresh store is empty`() {
        val store = SignerStore(InMemoryStore())
        assertTrue(store.list().isEmpty())
        assertEquals(0, store.count())
    }

    @Test
    fun `add stores a watch-only signer and returns it`() {
        val store = SignerStore(InMemoryStore(), now = { 42L }, newId = { "abc" })
        val signer = store.add(name = "Cold storage", xpub = "zpub6xyz")

        assertEquals("abc", signer.id)
        assertEquals("Cold storage", signer.name)
        assertEquals(SignerType.WatchOnlyXpub("zpub6xyz"), signer.type)
        assertEquals(42L, signer.createdAtEpochSeconds)
        assertEquals(listOf(signer), store.list())
    }

    @Test
    fun `a watch-only signer's type badge reads watch-only`() {
        val signer = Signer(
            id = "a",
            name = "Cold storage",
            type = SignerType.WatchOnlyXpub("zpub6xyz"),
            createdAtEpochSeconds = 0L,
        )
        assertEquals("watch-only", signer.typeLabel)
    }

    @Test
    fun `name and xpub are trimmed on the way in`() {
        val store = SignerStore(InMemoryStore(), now = { 1L }, newId = { "a" })
        val signer = store.add(name = "  Cold storage  ", xpub = "  zpub6xyz\n")
        assertEquals("Cold storage", signer.name)
        assertEquals(SignerType.WatchOnlyXpub("zpub6xyz"), signer.type)
    }

    @Test
    fun `signers survive a new store instance over the same storage`() {
        val backing = InMemoryStore()
        newStore(backing).add("First", "xpub1")
        newStore(backing).add("Second", "xpub2")

        val reloaded = SignerStore(backing)
        assertEquals(2, reloaded.count())
        assertEquals(listOf("First", "Second"), reloaded.list().map { it.name })
        assertEquals(
            listOf("xpub1", "xpub2"),
            reloaded.list().map { (it.type as SignerType.WatchOnlyXpub).xpub },
        )
    }

    @Test
    fun `the store is a list keyed by id, not a single slot`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        val a = store.add("A", "xpubA")
        val b = store.add("B", "xpubB")

        assertNotNull(store.get(a.id))
        assertNotNull(store.get(b.id))
        assertEquals("B", store.get(b.id)?.name)
        assertNull(store.get("nope"))
    }

    @Test
    fun `delete removes only the requested signer`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        val a = store.add("A", "xpubA")
        val b = store.add("B", "xpubB")

        assertTrue(store.delete(a.id))
        assertEquals(listOf(b), store.list())
        assertTrue(!store.delete(a.id))
    }

    // --- list ordering + delete --------------------------------------------
    //
    // The Signers list screen shows signers in whatever order the store hands
    // them back, so "oldest first, always" is a contract the UI leans on — and
    // it has to survive both a delete and a round-trip through storage.

    @Test
    fun `list keeps insertion order, oldest first`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        store.add("First", "xpub1")
        store.add("Second", "xpub2")
        store.add("Third", "xpub3")

        assertEquals(listOf("First", "Second", "Third"), store.list().map { it.name })
        // Order is a property of the stored list, not of the instance that
        // wrote it: a fresh store over the same storage reads it back the same.
        assertEquals(listOf("First", "Second", "Third"), SignerStore(backing).list().map { it.name })
    }

    @Test
    fun `delete keeps the order of the signers that remain`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        val a = store.add("A", "xpubA")
        store.add("B", "xpubB")
        val c = store.add("C", "xpubC")
        val d = store.add("D", "xpubD")

        // Remove from the middle, then from the front: what is left must still
        // read in the order it was added.
        assertTrue(store.delete(c.id))
        assertTrue(store.delete(a.id))
        assertEquals(listOf("B", "D"), store.list().map { it.name })
        assertEquals(listOf("B", "D"), SignerStore(backing).list().map { it.name })

        // And the surviving ids are the ones that were never touched.
        assertEquals(listOf("B", "D"), store.list().map { it.name })
        assertEquals(d.id, store.list().last().id)
    }

    @Test
    fun `delete is persisted, not just dropped from memory`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        val a = store.add("Keep", "xpubA")
        val b = store.add("Drop", "xpubB")

        assertTrue(store.delete(b.id))

        val reloaded = SignerStore(backing)
        assertEquals(listOf("Keep"), reloaded.list().map { it.name })
        assertNull(reloaded.get(b.id))
        assertEquals(a.id, reloaded.get(a.id)?.id)
    }

    @Test
    fun `deleting every signer leaves the list empty and stays that way`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        val a = store.add("A", "xpubA")
        val b = store.add("B", "xpubB")

        assertTrue(store.delete(a.id))
        assertTrue(store.delete(b.id))

        assertTrue(store.list().isEmpty())
        // An empty list is written, not skipped: a fresh store must not
        // resurrect anything.
        assertTrue(SignerStore(backing).list().isEmpty())
    }

    @Test
    fun `delete of an unknown id reports false and changes nothing`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        store.add("A", "xpubA")
        store.add("B", "xpubB")

        assertTrue(!store.delete("not-a-real-id"))
        assertEquals(listOf("A", "B"), store.list().map { it.name })
    }

    @Test
    fun `names may repeat but ids never do`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        val a = store.add("Same", "xpubA")
        val b = store.add("Same", "xpubB")
        assertTrue(a.id != b.id)
    }

    @Test
    fun `there is no active signer - the store exposes no selection at all`() {
        // A regression guard for the signer-first refactor: the API surface is
        // deliberately list-only, so this test only has to fail to compile if a
        // selection ever comes back. It documents the intent by asserting that
        // two signers simply coexist.
        val store = SignerStore(InMemoryStore(), now = { 1L }, newId = { "id" })
        store.add("A", "xpubA")
        store.add("B", "xpubB")
        assertEquals(2, store.list().size)
    }

    @Test
    fun `corrupt stored JSON degrades to an empty list instead of crashing`() {
        val backing = InMemoryStore()
        backing.putString(SignerStore.STORAGE_KEY, "{not json at all")
        assertEquals(emptyList<Signer>(), SignerStore(backing).list())
    }

    @Test
    fun `entries missing required fields are skipped`() {
        val backing = InMemoryStore()
        backing.putString(
            SignerStore.STORAGE_KEY,
            """{"signers":[{"id":"a","name":"ok","type":"watch-only","xpub":"xpub1","createdAt":1},{"name":"no id"}]}""",
        )
        assertEquals(listOf("ok"), SignerStore(backing).list().map { it.name })
    }

    @Test
    fun `an entry with an unknown signer type is skipped, not guessed at`() {
        val backing = InMemoryStore()
        backing.putString(
            SignerStore.STORAGE_KEY,
            """{"signers":[{"id":"a","name":"ok","type":"watch-only","xpub":"xpub1","createdAt":1},""" +
                """{"id":"b","name":"mystery","type":"hot-seed","xpub":"xpub2","createdAt":1}]}""",
        )
        assertEquals(listOf("ok"), SignerStore(backing).list().map { it.name })
    }

    @Test
    fun `a watch-only entry with no xpub is skipped`() {
        val backing = InMemoryStore()
        backing.putString(
            SignerStore.STORAGE_KEY,
            """{"signers":[{"id":"a","name":"ok","type":"watch-only","xpub":"xpub1","createdAt":1},""" +
                """{"id":"b","name":"keyless","type":"watch-only","createdAt":1}]}""",
        )
        assertEquals(listOf("ok"), SignerStore(backing).list().map { it.name })
    }

    // --- captured metadata: master fingerprint + derivation path -----------
    //
    // A BIP-32 key-expression QR carries a master fingerprint and a derivation
    // path that a bare key does not. They are additive: written only when
    // present, so a bare-key blob keeps its exact old shape and every blob an
    // earlier build wrote still loads.

    @Test
    fun `add stores the captured fingerprint and derivation path`() {
        val backing = InMemoryStore()
        val store = SignerStore(backing, now = { 42L }, newId = { "abc" })
        val signer = store.add(
            name = "Cold storage",
            xpub = "zpub6xyz",
            masterFingerprint = "ab12cd34",
            derivationPath = "84'/0'/0'",
        )

        assertEquals(
            SignerType.WatchOnlyXpub("zpub6xyz", masterFingerprint = "ab12cd34", derivationPath = "84'/0'/0'"),
            signer.type,
        )
    }

    @Test
    fun `fingerprint and path survive a round-trip through storage`() {
        val backing = InMemoryStore()
        newStore(backing).add(
            "Cold",
            "zpub6xyz",
            masterFingerprint = "ab12cd34",
            derivationPath = "84'/0'/0'",
        )

        val reloaded = SignerStore(backing).list().single()
        val type = reloaded.type as SignerType.WatchOnlyXpub
        assertEquals("ab12cd34", type.masterFingerprint)
        assertEquals("84'/0'/0'", type.derivationPath)
    }

    @Test
    fun `a bare-key signer writes neither metadata key`() {
        val backing = InMemoryStore()
        newStore(backing).add("Cold", "zpub6xyz")

        val json = backing.map[SignerStore.STORAGE_KEY].orEmpty()
        assertFalse("masterFingerprint must be absent", json.contains("masterFingerprint"))
        assertFalse("derivationPath must be absent", json.contains("derivationPath"))

        val type = SignerStore(backing).list().single().type as SignerType.WatchOnlyXpub
        assertNull(type.masterFingerprint)
        assertNull(type.derivationPath)
    }

    @Test
    fun `a fingerprint-only signer round-trips with a null path`() {
        val backing = InMemoryStore()
        newStore(backing).add("Cold", "zpub6xyz", masterFingerprint = "ab12cd34")

        val type = SignerStore(backing).list().single().type as SignerType.WatchOnlyXpub
        assertEquals("ab12cd34", type.masterFingerprint)
        assertNull(type.derivationPath)
    }

    // --- signing readiness --------------------------------------------------
    //
    // signingReady is computed from the record (fingerprint AND path present),
    // never stored: it is what the Send action gates on, and it must read the
    // type rather than the import origin (origin ≠ capability).

    @Test
    fun `a signer with a fingerprint and a path is signing-ready`() {
        val signer = Signer(
            id = "a",
            name = "Cold",
            type = SignerType.WatchOnlyXpub(
                "zpub6xyz",
                masterFingerprint = "ab12cd34",
                derivationPath = "84'/0'/0'",
            ),
            createdAtEpochSeconds = 0L,
        )
        assertTrue(signer.signingReady)
    }

    @Test
    fun `a fingerprint without a path is not signing-ready`() {
        val signer = Signer(
            id = "a",
            name = "Cold",
            type = SignerType.WatchOnlyXpub("zpub6xyz", masterFingerprint = "ab12cd34"),
            createdAtEpochSeconds = 0L,
        )
        assertFalse(signer.signingReady)
    }

    @Test
    fun `a path without a fingerprint is not signing-ready`() {
        val signer = Signer(
            id = "a",
            name = "Cold",
            type = SignerType.WatchOnlyXpub("zpub6xyz", derivationPath = "84'/0'/0'"),
            createdAtEpochSeconds = 0L,
        )
        assertFalse(signer.signingReady)
    }

    @Test
    fun `a bare-key signer is not signing-ready`() {
        val signer = Signer(
            id = "a",
            name = "Cold",
            type = SignerType.WatchOnlyXpub("zpub6xyz"),
            createdAtEpochSeconds = 0L,
        )
        assertFalse(signer.signingReady)
    }

    @Test
    fun `signing-ready reads the type, not the import origin`() {
        // A text-QR import that carried a key-expression is just as ready as one
        // off a hardware screen: origin is provenance, not capability.
        val textImport = Signer(
            id = "a",
            name = "Cold",
            type = SignerType.WatchOnlyXpub(
                "zpub6xyz",
                masterFingerprint = "ab12cd34",
                derivationPath = "84'/0'/0'",
            ),
            createdAtEpochSeconds = 0L,
            importedVia = SignerOrigin.TEXT_QR,
        )
        val deviceImport = textImport.copy(importedVia = SignerOrigin.SEEDSIGNER_UR)
        assertTrue(textImport.signingReady)
        assertTrue(deviceImport.signingReady)
    }

    /** The exact blob an earlier build wrote: no metadata keys at all. */
    @Test
    fun `a pre-metadata blob still loads, with nulls for the new fields`() {
        val backing = InMemoryStore()
        backing.putString(
            SignerStore.STORAGE_KEY,
            """{"signers":[{"id":"a","name":"Cold","type":"watch-only","xpub":"zpub6old","createdAt":7}]}""",
        )

        val type = SignerStore(backing).list().single().type as SignerType.WatchOnlyXpub
        assertEquals("zpub6old", type.xpub)
        assertNull(type.masterFingerprint)
        assertNull(type.derivationPath)
    }

    /** A JSON null (rather than an absent key) also reads as no metadata. */
    @Test
    fun `explicit nulls in a stored blob read as null metadata`() {
        val backing = InMemoryStore()
        backing.putString(
            SignerStore.STORAGE_KEY,
            """{"signers":[{"id":"a","name":"Cold","type":"watch-only","xpub":"zpub6x",""" +
                """"masterFingerprint":null,"derivationPath":null,"createdAt":7}]}""",
        )

        val type = SignerStore(backing).list().single().type as SignerType.WatchOnlyXpub
        assertNull(type.masterFingerprint)
        assertNull(type.derivationPath)
    }

    // --- the import origin --------------------------------------------------
    //
    // The origin is what tells a SeedSigner import, an other-device import and a
    // text xpub apart in the UI. It is additive: a blob written before it existed
    // must keep loading, and a text-QR signer — the default — must not grow the
    // key on the way out.

    @Test
    fun `a signing-device origin persists across a reload`() {
        val backing = InMemoryStore()
        newStore(backing).add(
            name = "Keystone",
            xpub = "zpub6xyz",
            importedVia = SignerOrigin.SIGNING_DEVICE_UR,
        )

        val reloaded = SignerStore(backing).list().single()
        assertEquals(SignerOrigin.SIGNING_DEVICE_UR, reloaded.importedVia)
    }

    @Test
    fun `a seedsigner origin persists across a reload`() {
        val backing = InMemoryStore()
        newStore(backing).add(
            name = "SeedSigner",
            xpub = "zpub6xyz",
            importedVia = SignerOrigin.SEEDSIGNER_UR,
        )

        val reloaded = SignerStore(backing).list().single()
        assertEquals(SignerOrigin.SEEDSIGNER_UR, reloaded.importedVia)
    }

    @Test
    fun `a seedsigner import writes the historical signing-device value`() {
        val backing = InMemoryStore()
        newStore(backing).add("SeedSigner", "zpub6xyz", importedVia = SignerOrigin.SEEDSIGNER_UR)

        // `signing-device-ur` is the value this field has written for a BC-UR
        // import since the origin existed; before the split it meant "a signing
        // device", which was always a SeedSigner. Reusing it is what makes the
        // migration free — and what keeps a blob written by this build readable
        // by the build before it.
        val json = backing.map[SignerStore.STORAGE_KEY].orEmpty()
        assertTrue(json.contains("\"importedVia\":\"signing-device-ur\""))
    }

    @Test
    fun `an other-device import writes a value of its own`() {
        val backing = InMemoryStore()
        newStore(backing).add("Keystone", "zpub6xyz", importedVia = SignerOrigin.SIGNING_DEVICE_UR)

        // It must not collide with the historical value: a stored
        // `signing-device-ur` now means SeedSigner, so the other-device path has
        // a wire value only this build writes.
        val json = backing.map[SignerStore.STORAGE_KEY].orEmpty()
        assertTrue(json.contains("\"importedVia\":\"other-signing-device-ur\""))
        assertFalse(json.contains("\"importedVia\":\"signing-device-ur\""))
    }

    /**
     * The migration, stated as the rule it is: every record an earlier build
     * wrote with the unsplit `SIGNING_DEVICE_UR` origin reads back as
     * [SignerOrigin.SEEDSIGNER_UR] — because the SeedSigner path was the only one
     * that existed, and Andrew's only BC-UR wallet is a SeedSigner.
     */
    @Test
    fun `the old signing-device-ur value reads back as the seedsigner origin`() {
        val backing = InMemoryStore()
        backing.putString(
            SignerStore.STORAGE_KEY,
            """{"signers":[{"id":"a","name":"SeedSigner","type":"watch-only","xpub":"zpub6old","importedVia":"signing-device-ur","createdAt":7}]}""",
        )

        assertEquals(SignerOrigin.SEEDSIGNER_UR, SignerStore(backing).list().single().importedVia)
    }

    @Test
    fun `a text-qr signer writes no origin key and stays the default`() {
        val backing = InMemoryStore()
        newStore(backing).add("Cold", "zpub6xyz", importedVia = SignerOrigin.TEXT_QR)

        // The default is not written, so a text-QR blob keeps the exact shape an
        // earlier build produced — nothing to migrate, nothing to misread.
        val json = backing.map[SignerStore.STORAGE_KEY].orEmpty()
        assertFalse("importedVia must be absent", json.contains(SignerStore.IMPORTED_VIA_KEY))

        assertEquals(SignerOrigin.TEXT_QR, SignerStore(backing).list().single().importedVia)
    }

    @Test
    fun `every origin survives a round-trip`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        SignerOrigin.entries.forEachIndexed { index, origin ->
            store.add("Wallet $index", "zpub6$index", importedVia = origin)
        }

        val reloaded = SignerStore(backing).list()
        assertEquals(
            "order and origin both survive storage",
            SignerOrigin.entries.toList(),
            reloaded.map { it.importedVia },
        )
    }

    /** The blob an earlier build wrote: no origin key at all. */
    @Test
    fun `a pre-origin blob loads as the text-qr default`() {
        val backing = InMemoryStore()
        backing.putString(
            SignerStore.STORAGE_KEY,
            """{"signers":[{"id":"a","name":"Cold","type":"watch-only","xpub":"zpub6old","createdAt":7}]}""",
        )

        assertEquals(SignerOrigin.TEXT_QR, SignerStore(backing).list().single().importedVia)
    }

    /** An origin this build does not know must not be guessed at as the device path. */
    @Test
    fun `an unrecognised origin value reads as the text-qr default`() {
        val backing = InMemoryStore()
        backing.putString(
            SignerStore.STORAGE_KEY,
            """{"signers":[{"id":"a","name":"Cold","type":"watch-only","xpub":"zpub6x","importedVia":"something-new","createdAt":7}]}""",
        )

        assertEquals(SignerOrigin.TEXT_QR, SignerStore(backing).list().single().importedVia)
    }

    // --- migration from the old `wallets` shape ----------------------------
    //
    // Before the rename, saved xpubs lived under the `wallets` key as
    // {"wallets":[{id,name,xpub,createdAt}]}. Every one of those was, by
    // construction, a watch-only xpub, so the migration is total: nothing a
    // user had imported may disappear.

    /** The exact shape the pre-rename WalletStore wrote. */
    private fun legacyBlob(vararg entries: String): String =
        """{"wallets":[${entries.joinToString(",")}]}"""

    private fun legacyEntry(id: String, name: String, xpub: String, createdAt: Long = 7L): String =
        """{"id":"$id","name":"$name","xpub":"$xpub","createdAt":$createdAt}"""

    @Test
    fun `legacy wallets load as watch-only signers`() {
        val backing = InMemoryStore()
        backing.putString(
            SignerStore.LEGACY_STORAGE_KEY,
            legacyBlob(
                legacyEntry("old-1", "Cold storage", "zpub6old1", createdAt = 111L),
                legacyEntry("old-2", "Savings", "zpub6old2", createdAt = 222L),
            ),
        )

        val signers = SignerStore(backing).list()

        assertEquals(listOf("old-1", "old-2"), signers.map { it.id })
        assertEquals(listOf("Cold storage", "Savings"), signers.map { it.name })
        assertEquals(
            listOf(SignerType.WatchOnlyXpub("zpub6old1"), SignerType.WatchOnlyXpub("zpub6old2")),
            signers.map { it.type },
        )
        assertEquals(listOf(111L, 222L), signers.map { it.createdAtEpochSeconds })
    }

    @Test
    fun `migration rewrites the data under the signer key`() {
        val backing = InMemoryStore()
        backing.putString(SignerStore.LEGACY_STORAGE_KEY, legacyBlob(legacyEntry("old-1", "Cold", "zpub6old")))

        // The read itself performs the migration.
        val migrated = SignerStore(backing).list()
        assertEquals(1, migrated.size)

        assertNotNull("the signer key should now hold the migrated list", backing.map[SignerStore.STORAGE_KEY])
        // And what was written forward is exactly what a fresh store reads back.
        val reread = SignerStore(backing).list()
        assertEquals(migrated, reread)
    }

    @Test
    fun `the new key wins over a lingering legacy blob`() {
        val backing = InMemoryStore()
        // A store that has already been migrated once, then the stale legacy
        // blob edited behind its back (as a half-finished reinstall might do).
        newStore(backing).add("Current", "zpub6new")
        backing.putString(SignerStore.LEGACY_STORAGE_KEY, legacyBlob(legacyEntry("old-1", "Stale", "zpub6old")))

        val signers = SignerStore(backing).list()

        assertEquals(listOf("Current"), signers.map { it.name })
    }

    @Test
    fun `an emptied signer list is not re-seeded from legacy data`() {
        val backing = InMemoryStore()
        newStore(backing) // writes nothing, but let us make the keys explicit
        // The new key exists and is empty; the legacy blob still has an entry.
        backing.putString(SignerStore.STORAGE_KEY, """{"signers":[]}""")
        backing.putString(SignerStore.LEGACY_STORAGE_KEY, legacyBlob(legacyEntry("old-1", "Stale", "zpub6old")))

        assertTrue(SignerStore(backing).list().isEmpty())
    }

    @Test
    fun `legacy entries missing required fields are skipped`() {
        val backing = InMemoryStore()
        backing.putString(
            SignerStore.LEGACY_STORAGE_KEY,
            legacyBlob(
                legacyEntry("ok", "Cold", "zpub6ok"),
                """{"id":"no-xpub","name":"Broken"}""",
            ),
        )
        assertEquals(listOf("Cold"), SignerStore(backing).list().map { it.name })
    }

    @Test
    fun `a corrupt legacy blob degrades to empty and writes nothing forward`() {
        val backing = InMemoryStore()
        backing.putString(SignerStore.LEGACY_STORAGE_KEY, "{not json at all")

        assertTrue(SignerStore(backing).list().isEmpty())
        // No signer key is created, so a later genuine migration is not blocked
        // by an empty list that would win the precedence check.
        assertNull(backing.map[SignerStore.STORAGE_KEY])
    }

    @Test
    fun `an empty legacy blob does not create the signer key`() {
        val backing = InMemoryStore()
        backing.putString(SignerStore.LEGACY_STORAGE_KEY, """{"wallets":[]}""")

        assertTrue(SignerStore(backing).list().isEmpty())
        assertNull(backing.map[SignerStore.STORAGE_KEY])
    }
}
