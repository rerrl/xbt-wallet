package com.xbtwallet.app.ui.home

import com.xbtwallet.app.data.KeyValueStore
import com.xbtwallet.app.data.WalletStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HomeScreenState tests.
 *
 * The class exists so the home screen's rule — "the active signer is exactly one
 * of the saved xpubs, or none" — is testable without a UI or an emulator. It is
 * plain Kotlin over a [WalletStore], so the same in-memory [KeyValueStore] the
 * store tests use drives it here.
 */
class HomeScreenStateTest {

    private class InMemoryStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    private class Fixture {
        val backing = InMemoryStore()
        private var counter = 0
        val store = WalletStore(
            storage = backing,
            now = { 0L },
            newId = { "id-${++counter}" },
        )
        val state = HomeScreenState(store)
    }

    @Test
    fun `a fresh state has no xpubs and no active signer`() {
        val fixture = Fixture()
        assertEquals(emptyList<String>(), fixture.state.xpubs)
        assertNull(fixture.state.activeId)
        assertNull(fixture.state.active)
        assertNull(fixture.state.activeSnippet)
    }

    @Test
    fun `setActive marks the xpub active and shows its snippet`() {
        val fixture = Fixture()
        val wallet = fixture.store.add("Cold storage", "zpub6rFR7y4Q2AijBEqTUquhVz")

        fixture.state.refresh()
        assertEquals(1, fixture.state.xpubs.size)
        assertNull(fixture.state.activeId)

        fixture.state.setActive(wallet)

        assertEquals(wallet.id, fixture.state.activeId)
        assertEquals("Cold storage", fixture.state.active?.name)
        assertEquals("zpub6rFR7y4Q2Aij\u2026", fixture.state.activeSnippet)
    }

    @Test
    fun `setActive replaces the previous selection`() {
        val fixture = Fixture()
        val a = fixture.store.add("A", "xpubA")
        val b = fixture.store.add("B", "xpubB")

        fixture.state.setActive(a)
        fixture.state.setActive(b)

        assertEquals(b.id, fixture.state.activeId)
        assertEquals("B", fixture.state.active?.name)
    }

    @Test
    fun `refresh picks up an xpub written through the store`() {
        val fixture = Fixture()
        fixture.store.add("Imported elsewhere", "zpub6xyz")

        assertNull(fixture.state.active)
        fixture.state.refresh()

        assertEquals(listOf("Imported elsewhere"), fixture.state.xpubs.map { it.name })
    }

    @Test
    fun `an xpub deleted behind the state's back clears the selection on refresh`() {
        val fixture = Fixture()
        val wallet = fixture.store.add("Temp", "zpub6xyz")
        fixture.state.setActive(wallet)
        assertEquals(wallet.id, fixture.state.activeId)

        fixture.store.delete(wallet.id)
        fixture.state.refresh()

        assertEquals(emptyList<String>(), fixture.state.xpubs)
        assertNull(fixture.state.activeId)
        assertNull(fixture.state.active)
    }

    @Test
    fun `a short xpub is not truncated`() {
        val fixture = Fixture()
        val wallet = fixture.store.add("Tiny", "xpub1")

        fixture.state.setActive(wallet)

        assertEquals("xpub1", fixture.state.activeSnippet)
    }
}
