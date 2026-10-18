package com.xbtwallet.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WalletStore tests.
 *
 * The store talks to a [KeyValueStore] rather than to SharedPreferences
 * directly, so the whole thing is exercised on the JVM with an in-memory map --
 * no Robolectric, no Android framework, no emulator.
 */
class WalletStoreTest {

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

    private fun newStore(backing: InMemoryStore) = WalletStore(
        storage = backing,
        now = { ++clock },
        newId = { "id-${++idCounter}" },
    )

    @Test
    fun `a fresh store is empty`() {
        val store = WalletStore(InMemoryStore())
        assertTrue(store.list().isEmpty())
        assertEquals(0, store.count())
    }

    @Test
    fun `add stores a wallet and returns it`() {
        val store = WalletStore(InMemoryStore(), now = { 42L }, newId = { "abc" })
        val wallet = store.add(name = "Cold storage", xpub = "zpub6xyz")

        assertEquals("abc", wallet.id)
        assertEquals("Cold storage", wallet.name)
        assertEquals("zpub6xyz", wallet.xpub)
        assertEquals(42L, wallet.createdAtEpochSeconds)
        assertEquals(listOf(wallet), store.list())
    }

    @Test
    fun `wallets survive a new store instance over the same storage`() {
        val backing = InMemoryStore()
        newStore(backing).add("First", "xpub1")
        newStore(backing).add("Second", "xpub2")

        val reloaded = WalletStore(backing)
        assertEquals(2, reloaded.count())
        assertEquals(listOf("First", "Second"), reloaded.list().map { it.name })
        assertEquals(listOf("xpub1", "xpub2"), reloaded.list().map { it.xpub })
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
    fun `delete removes only the requested wallet`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        val a = store.add("A", "xpubA")
        val b = store.add("B", "xpubB")

        assertTrue(store.delete(a.id))
        assertEquals(listOf(b), store.list())
        assertTrue(!store.delete(a.id))
    }

    @Test
    fun `corrupt stored JSON degrades to an empty list instead of crashing`() {
        val backing = InMemoryStore()
        backing.putString(WalletStore.STORAGE_KEY, "{not json at all")
        assertEquals(emptyList<Wallet>(), WalletStore(backing).list())
    }

    @Test
    fun `entries missing required fields are skipped`() {
        val backing = InMemoryStore()
        backing.putString(
            WalletStore.STORAGE_KEY,
            """{"wallets":[{"id":"a","name":"ok","xpub":"xpub1","createdAt":1},{"name":"no id"}]}""",
        )
        assertEquals(listOf("ok"), WalletStore(backing).list().map { it.name })
    }

    @Test
    fun `names may repeat but ids never do`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        val a = store.add("Same", "xpubA")
        val b = store.add("Same", "xpubB")
        assertTrue(a.id != b.id)
    }

    // --- active xpub -------------------------------------------------------
    //
    // The active signer is the home screen's whole state machine: exactly one
    // saved xpub may be active, and "none" is a first-class value rather than a
    // dangling id pointing at something that is not there any more.

    @Test
    fun `a fresh store has no active xpub`() {
        val store = WalletStore(InMemoryStore())
        assertNull(store.activeId())
        assertNull(store.active())
    }

    @Test
    fun `setting an active xpub persists across a new store instance`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        val a = store.add("Cold storage", "zpub6xyz")
        store.setActive(a.id)

        val reloaded = WalletStore(backing)
        assertEquals(a.id, reloaded.activeId())
        assertEquals("Cold storage", reloaded.active()?.name)
    }

    @Test
    fun `selecting a second xpub replaces the active one`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        val a = store.add("A", "xpubA")
        val b = store.add("B", "xpubB")

        store.setActive(a.id)
        store.setActive(b.id)

        assertEquals(b.id, store.activeId())
        assertEquals("B", WalletStore(backing).active()?.name)
    }

    @Test
    fun `clearing the active xpub leaves nothing selected`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        val a = store.add("A", "xpubA")
        store.setActive(a.id)

        store.setActive(null)

        assertNull(store.activeId())
        assertNull(WalletStore(backing).activeId())
    }

    @Test
    fun `an active id that is not stored reads back as nothing selected`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        store.add("A", "xpubA")
        store.setActive("id-that-does-not-exist")

        assertNull(store.activeId())
        assertNull(store.active())
        // And it stays unset: a stale id is never handed back after a restart.
        assertNull(WalletStore(backing).activeId())
    }

    @Test
    fun `deleting the active xpub unsets the selection`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        val a = store.add("A", "xpubA")
        val b = store.add("B", "xpubB")
        store.setActive(a.id)

        assertTrue(store.delete(a.id))

        assertNull(store.activeId())
        assertNull(WalletStore(backing).activeId())
        // The other xpub is untouched by the deletion.
        assertEquals(listOf(b), store.list())
    }

    @Test
    fun `deleting another xpub leaves the active one selected`() {
        val backing = InMemoryStore()
        val store = newStore(backing)
        val a = store.add("A", "xpubA")
        val b = store.add("B", "xpubB")
        store.setActive(b.id)

        assertTrue(store.delete(a.id))

        assertEquals(b.id, store.activeId())
    }
}
