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
    // Metadata is a signing-device concern now. It is written only when present,
    // so a bare-key blob keeps its exact old shape and every blob an earlier build
    // wrote still loads — but a text-QR record is read back with its fingerprint
    // and path dropped, so a watch-only import can never carry signing metadata,
    // whatever an older blob on disk happens to hold.

    @Test
    fun `add stores the captured fingerprint and derivation path`() {
        val backing = InMemoryStore()
        val store = SignerStore(backing, now = { 42L }, newId = { "abc" })
        val signer = store.add(
            name = "Cold storage",
            xpub = "zpub6xyz",
            masterFingerprint = "ab12cd34",
            derivationPath = "84'/0'/0'",
            importedVia = SignerOrigin.SIGNING_DEVICE_UR,
        )

        assertEquals(
            SignerType.WatchOnlyXpub("zpub6xyz", masterFingerprint = "ab12cd34", derivationPath = "84'/0'/0'"),
            signer.type,
        )
    }

    @Test
    fun `a signing-device import keeps its fingerprint and path through storage`() {
        val backing = InMemoryStore()
        newStore(backing).add(
            "Cold",
            "zpub6xyz",
            masterFingerprint = "ab12cd34",
            derivationPath = "84'/0'/0'",
            importedVia = SignerOrigin.SEEDSIGNER_UR,
        )

        val reloaded = SignerStore(backing).list().single()
        val type = reloaded.type as SignerType.WatchOnlyXpub
        assertEquals("ab12cd34", type.masterFingerprint)
        assertEquals("84'/0'/0'", type.derivationPath)
        assertTrue("a device import with both fields is signing-ready", reloaded.signingReady)
    }

    /**
     * The ruling at the store seam: a text-QR record is watch-only no matter what
     * the blob holds. An install that imported a key-expression QR before this rule
     * has its fingerprint and path dropped the first time the record is read — in
     * memory, without rewriting the blob — so it can never be signing-ready.
     */
    @Test
    fun `a text-qr blob that holds metadata reads back with both fields dropped`() {
        val backing = InMemoryStore()
        backing.putString(
            SignerStore.STORAGE_KEY,
            "{\"signers\":[{\"id\":\"a\",\"name\":\"Cold\",\"type\":\"watch-only\",\"xpub\":\"zpub6x\"," +
                "\"masterFingerprint\":\"ab12cd34\",\"derivationPath\":\"84'/0'/0'\",\"createdAt\":7}]}",
        )

        // The origin key is absent, so [SignerOrigin.fromStorage] reads this as the
        // TEXT_QR default — exactly the shape an old text import has.
        val signer = SignerStore(backing).list().single()
        val type = signer.type as SignerType.WatchOnlyXpub
        assertEquals("zpub6x", type.xpub)
        assertNull("a text import keeps no fingerprint", type.masterFingerprint)
        assertNull("a text import keeps no path", type.derivationPath)
        assertFalse("a text-qr import is never signing-ready", signer.signingReady)

        // And the drop is read-only: the stored blob is left exactly as it was.
        val stored = backing.map[SignerStore.STORAGE_KEY].orEmpty()
        assertTrue("the blob is not rewritten", stored.contains("\"masterFingerprint\":\"ab12cd34\""))
        assertTrue("the blob is not rewritten", stored.contains("\"derivationPath\":\"84'/0'/0'\""))
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
    fun `a text-qr record drops even a lone fingerprint on read`() {
        val backing = InMemoryStore()
        newStore(backing).add("Cold", "zpub6xyz", masterFingerprint = "ab12cd34")

        val signer = SignerStore(backing).list().single()
        val type = signer.type as SignerType.WatchOnlyXpub
        assertNull("a text import keeps no fingerprint", type.masterFingerprint)
        assertNull(type.derivationPath)
        assertFalse(signer.signingReady)
    }

    /**
     * The other half of the ruling: a signing-device import keeps what it exported,
     * and both BC-UR origins stay signing-ready.
     */
    @Test
    fun `a signing-device blob keeps its metadata and stays signing-ready`() {
        SignerOrigin.entries
            .filter { it != SignerOrigin.TEXT_QR }
            .forEach { origin ->
                val backing = InMemoryStore()
                backing.putString(
                    SignerStore.STORAGE_KEY,
                    "{\"signers\":[{\"id\":\"a\",\"name\":\"Device\",\"type\":\"watch-only\"," +
                        "\"xpub\":\"zpub6x\",\"masterFingerprint\":\"ab12cd34\"," +
                        "\"derivationPath\":\"84'/0'/0'\",\"importedVia\":\"${origin.storageValue}\"," +
                        "\"createdAt\":7}]}",
                )

                val signer = SignerStore(backing).list().single()
                val type = signer.type as SignerType.WatchOnlyXpub
                assertEquals(origin.name, "ab12cd34", type.masterFingerprint)
                assertEquals(origin.name, "84'/0'/0'", type.derivationPath)
                assertTrue("${origin.name} must stay signing-ready", signer.signingReady)
            }
    }

    // --- signing readiness --------------------------------------------------
    //
    // signingReady is computed from the record, never stored, and it is what the
    // Send action gates on. It needs a fingerprint AND a path AND a signing-device
    // origin: a text-QR import is never signing-ready, no matter what metadata a
    // stale blob happens to hold.

    @Test
    fun `a signing-device signer with a fingerprint and a path is signing-ready`() {
        val signer = Signer(
            id = "a",
            name = "Cold",
            type = SignerType.WatchOnlyXpub(
                "zpub6xyz",
                masterFingerprint = "ab12cd34",
                derivationPath = "84'/0'/0'",
            ),
            createdAtEpochSeconds = 0L,
            importedVia = SignerOrigin.SEEDSIGNER_UR,
        )
        assertTrue(signer.signingReady)
    }

    // These three keep the origin out of the way — a signing device — so they pin
    // the metadata half of the rule rather than the origin gate.

    @Test
    fun `a fingerprint without a path is not signing-ready`() {
        val signer = Signer(
            id = "a",
            name = "Cold",
            type = SignerType.WatchOnlyXpub("zpub6xyz", masterFingerprint = "ab12cd34"),
            createdAtEpochSeconds = 0L,
            importedVia = SignerOrigin.SIGNING_DEVICE_UR,
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
            importedVia = SignerOrigin.SIGNING_DEVICE_UR,
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
            importedVia = SignerOrigin.SEEDSIGNER_UR,
        )
        assertFalse(signer.signingReady)
    }

    @Test
    fun `a text-qr import is never signing-ready, even when it holds metadata`() {
        // The two records differ only in their origin — same key, same fingerprint,
        // same path — and origin is now the deciding half of the rule: metadata may
        // only sign when it came off a signing device.
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
        assertFalse("a text-qr import must never become signing-ready", textImport.signingReady)
        assertTrue("the same record off a signing device is signing-ready", deviceImport.signingReady)
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

    // --- hot wallets ---------------------------------------------------------
    //
    // The second kind of signer: the phone holds the seed. Two new jobs fall out
    // of that — writing and reading a `hot` record, and taking the seed out of
    // the vault when the wallet is deleted — and both are additive. Everything
    // above this line must keep behaving exactly as it did, so the watch-only
    // cases are re-run beside a hot record rather than replaced by one.

    /** A store wired to a vault, with a deterministic id and clock. */
    private fun hotStore(
        backing: InMemoryStore,
        vault: SeedVault,
        id: String = "hot-1",
    ) = SignerStore(
        storage = backing,
        now = { 1_700_000_000L },
        newId = { id },
        seedVault = vault,
    )

    @Test
    fun `addHot writes a hot record with the origin stamped`() {
        val backing = InMemoryStore()
        val signer = hotStore(backing, FakeSeedVault()).addHot(
            name = "Hot wallet",
            xpub = "zpub6hot",
            masterFingerprint = "73c5da0a",
            hasPassphrase = true,
        )

        assertEquals(
            SignerType.HotSeed(
                xpub = "zpub6hot",
                masterFingerprint = "73c5da0a",
                derivationPath = "84'/0'/0'",
                hasPassphrase = true,
            ),
            signer.type,
        )
        assertEquals(SignerOrigin.HOT, signer.importedVia)
        assertEquals("hot", signer.typeLabel)
    }

    @Test
    fun `a hot record round-trips through storage`() {
        val backing = InMemoryStore()
        hotStore(backing, FakeSeedVault()).addHot(
            name = "Hot wallet",
            xpub = "zpub6hot",
            masterFingerprint = "73c5da0a",
            hasPassphrase = false,
        )

        // A fresh store — over the same bytes, with no vault at all — reads it
        // back as itself: the record is self-describing, so nothing but the
        // delete path ever needs the vault.
        val reloaded = SignerStore(backing).list().single()
        assertEquals(
            SignerType.HotSeed("zpub6hot", "73c5da0a", "84'/0'/0'", hasPassphrase = false),
            reloaded.type,
        )
        assertEquals(SignerOrigin.HOT, reloaded.importedVia)
        assertEquals("hot", reloaded.typeLabel)
    }

    @Test
    fun `the hot blob spells out every field, including the origin`() {
        val backing = InMemoryStore()
        hotStore(backing, FakeSeedVault()).addHot("Hot wallet", "zpub6hot", "73c5da0a", hasPassphrase = true)

        val json = backing.map[SignerStore.STORAGE_KEY].orEmpty()
        assertTrue(json.contains("\"type\":\"hot\""))
        assertTrue(json.contains("\"masterFingerprint\":\"73c5da0a\""))
        assertTrue(json.contains("\"derivationPath\":\"84'/0'/0'\""))
        assertTrue(json.contains("\"hasPassphrase\":true"))
        // Unlike a text-QR record, a hot one writes its origin even though the
        // writer would normally omit a value it considers the default: "hot" is
        // not the default, and the blob should say what it is rather than leave
        // the reader to infer it from the type tag.
        assertTrue(json.contains("\"importedVia\":\"hot\""))
    }

    @Test
    fun `addHot trims the name and the key material and defaults the path`() {
        val backing = InMemoryStore()
        val signer = hotStore(backing, FakeSeedVault()).addHot(
            name = "  Hot wallet  ",
            xpub = "  zpub6hot\n",
            masterFingerprint = " 73c5da0a ",
            hasPassphrase = false,
        )

        assertEquals("Hot wallet", signer.name)
        val type = signer.type as SignerType.HotSeed
        assertEquals("zpub6hot", type.xpub)
        assertEquals("73c5da0a", type.masterFingerprint)
        assertEquals("84'/0'/0'", type.derivationPath)
    }

    @Test
    fun `a hot signer is signing-ready by construction`() {
        val backing = InMemoryStore()
        val signer = hotStore(backing, FakeSeedVault()).addHot("Hot", "zpub6hot", "73c5da0a", hasPassphrase = true)

        // Nothing is asked of a hot record before it can sign: the seed is in the
        // vault by the time the record exists (see provisioning), so a fingerprint
        // and a path are not what decides it here.
        assertTrue(signer.signingReady)
        assertTrue(SignerStore(backing).list().single().signingReady)

        // Even the degenerate case — a hot record whose origin says something
        // else — is signing-ready, because the *type* is what says the seed is
        // here. Such a record cannot be produced by this build.
        val odd = Signer(
            id = "a",
            name = "Hot",
            type = SignerType.HotSeed("zpub6hot", "73c5da0a", hasPassphrase = true),
            createdAtEpochSeconds = 0L,
            importedVia = SignerOrigin.TEXT_QR,
        )
        assertTrue(odd.signingReady)
    }

    /** An old watch-only blob — no metadata, no origin — beside a new hot one. */
    @Test
    fun `a pre-existing watch-only record is unchanged by a hot neighbour`() {
        val backing = InMemoryStore()
        backing.putString(
            SignerStore.STORAGE_KEY,
            """{"signers":[{"id":"old","name":"Cold","type":"watch-only","xpub":"zpub6old","createdAt":7}]}""",
        )

        val store = hotStore(backing, FakeSeedVault(), id = "hot-1")
        val before = store.list().single()
        store.addHot("Hot wallet", "zpub6hot", "73c5da0a", hasPassphrase = false)

        val reloaded = SignerStore(backing).list()
        assertEquals(listOf("Cold", "Hot wallet"), reloaded.map { it.name })
        // The old record is exactly what it was: same type, no metadata invented,
        // and still not signing-ready.
        assertEquals(before, reloaded.first())
        assertNull((reloaded.first().type as SignerType.WatchOnlyXpub).masterFingerprint)
        assertFalse(reloaded.first().signingReady)
        assertEquals(SignerOrigin.TEXT_QR, reloaded.first().importedVia)
    }

    /**
     * The skip rule, restated for the new shape: a `hot` record that does not
     * name a key is skipped rather than loaded half-formed, exactly as a
     * watch-only record without an xpub is.
     */
    @Test
    fun `a hot record without a fingerprint is skipped, not guessed at`() {
        val backing = InMemoryStore()
        backing.putString(
            SignerStore.STORAGE_KEY,
            """{"signers":[{"id":"a","name":"no fingerprint","type":"hot","xpub":"zpub6hot","createdAt":1},""" +
                """{"id":"b","name":"no xpub","type":"hot","masterFingerprint":"73c5da0a","createdAt":1},""" +
                """{"id":"c","name":"good","type":"hot","xpub":"zpub6good","masterFingerprint":"73c5da0a","createdAt":1}]}""",
        )

        // Only the record that names a key survives; the two half-formed ones are
        // dropped rather than loaded with a null where an xpub should be.
        val signers = SignerStore(backing).list()
        assertEquals(listOf("good"), signers.map { it.name })
    }

    @Test
    fun `delete purges the vault for a hot signer`() {
        val backing = InMemoryStore()
        val vault = FakeSeedVault()
        val store = hotStore(backing, vault)
        val hot = store.addHot("Hot wallet", "zpub6hot", "73c5da0a", hasPassphrase = false)
        vault.store(hot.id, ByteArray(16) { 7 })

        assertTrue(store.delete(hot.id))

        // The seed goes with the record: this is the destructive half of the
        // delete, and the confirmation copy for a hot wallet says so.
        assertEquals(listOf(hot.id), vault.deleted)
        assertFalse(vault.has(hot.id))
        assertNull(vault.load(hot.id))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `delete touches the vault not at all for a watch-only signer`() {
        val backing = InMemoryStore()
        val vault = FakeSeedVault()
        val store = hotStore(backing, vault)
        val cold = store.add("Cold storage", "zpub6cold")

        assertTrue(store.delete(cold.id))

        // "Nothing is deleted on the signer itself" is only true if nothing was
        // touched here — and a call the vault never received is the proof.
        assertTrue(vault.deleted.isEmpty())
        assertTrue(vault.stores.isEmpty())
    }

    @Test
    fun `a store with no vault still deletes a hot signer`() {
        // The vault is optional so that every watch-only test can stay a store
        // over a map; a hot record reaching such a store must not crash.
        val backing = InMemoryStore()
        backing.putString(
            SignerStore.STORAGE_KEY,
            """{"signers":[{"id":"hot-1","name":"Hot","type":"hot","xpub":"zpub6hot","masterFingerprint":"73c5da0a","derivationPath":"84'/0'/0'","hasPassphrase":false,"importedVia":"hot","createdAt":1}]}""",
        )

        assertTrue(SignerStore(backing).delete("hot-1"))
        assertTrue(SignerStore(backing).list().isEmpty())
    }

    @Test
    fun `the hot origin reads back from its stored value`() {
        assertEquals(SignerOrigin.HOT, SignerOrigin.fromStorage("hot"))
        assertEquals("hot", SignerOrigin.HOT.storageValue)
        // And it is not the default an unknown value falls back to.
        assertTrue(SignerOrigin.HOT != SignerOrigin.TEXT_QR)
        assertEquals(SignerOrigin.TEXT_QR, SignerOrigin.fromStorage("something-new"))
    }
}
