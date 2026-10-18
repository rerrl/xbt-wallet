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
}
