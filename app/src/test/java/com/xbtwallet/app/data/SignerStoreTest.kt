package com.xbtwallet.app.data

import org.junit.Assert.assertEquals
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
