package com.xbtwallet.app.data

import com.xbtwallet.app.electrum.ElectrumProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SavedServersStore tests.
 *
 * The store talks to a [KeyValueStore] rather than to SharedPreferences, so the
 * whole thing runs on the JVM with an in-memory map — no Android framework, no
 * emulator.
 *
 * Three things are pinned here: the round trip (every field of a preset survives
 * a rebuild of the store, for both protocols), the list rules (an overwrite
 * keeps its position instead of duplicating, a delete removes only what it
 * names), and the forgiving reader (an absent key, a corrupt blob and one
 * unreadable element all degrade without throwing and without taking their
 * neighbours down with them).
 */
class SavedServersStoreTest {

    /** The simplest possible [KeyValueStore]: a map that survives instances. */
    private class InMemoryStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    private fun preset(
        name: String = "Home",
        host: String = "example.invalid",
        port: Int = 50002,
        protocol: ElectrumProtocol = ElectrumProtocol.SSL,
    ) = SavedServer(name = name, host = host, port = port, protocol = protocol)

    @Test
    fun `saves and reads back every field of both protocols`() {
        val backing = InMemoryStore()
        val store = SavedServersStore(backing)

        val ssl = preset(name = "Home", host = "host-a.example", port = 50002, protocol = ElectrumProtocol.SSL)
        val tcp = preset(name = "Away", host = "host-b.example", port = 50001, protocol = ElectrumProtocol.TCP)
        store.save(ssl)
        store.save(tcp)

        // A second store over the same backing map is the "reopen the app" case.
        assertEquals(listOf(ssl, tcp), SavedServersStore(backing).list())
    }

    @Test
    fun `overwriting the same name keeps its position and does not duplicate`() {
        val store = SavedServersStore(InMemoryStore())
        store.save(preset(name = "One"))
        store.save(preset(name = "Two"))
        store.save(preset(name = "Three"))

        store.save(preset(name = "Two", host = "changed.example", port = 50001, protocol = ElectrumProtocol.TCP))

        val list = store.list()
        assertEquals(3, list.size)
        assertEquals(listOf("One", "Two", "Three"), list.map { it.name })
        assertEquals("changed.example", list[1].host)
        assertEquals(ElectrumProtocol.TCP, list[1].protocol)
    }

    @Test
    fun `a name is matched exactly, not case-insensitively`() {
        val store = SavedServersStore(InMemoryStore())
        store.save(preset(name = "Home", host = "first.example"))
        store.save(preset(name = "home", host = "second.example"))

        val list = store.list()
        assertEquals(2, list.size)
        assertEquals(listOf("Home", "home"), list.map { it.name })
    }

    @Test
    fun `delete removes only the named preset`() {
        val store = SavedServersStore(InMemoryStore())
        store.save(preset(name = "Keep-A"))
        store.save(preset(name = "Drop"))
        store.save(preset(name = "Keep-B"))

        store.delete("Drop")

        assertEquals(listOf("Keep-A", "Keep-B"), store.list().map { it.name })
    }

    @Test
    fun `deleting a name that is not there changes nothing`() {
        val store = SavedServersStore(InMemoryStore())
        store.save(preset(name = "Only"))

        store.delete("Missing")

        assertEquals(listOf("Only"), store.list().map { it.name })
    }

    @Test
    fun `an absent key reads as an empty list`() {
        assertTrue(SavedServersStore(InMemoryStore()).list().isEmpty())
    }

    @Test
    fun `corrupt JSON under the key reads as an empty list and does not throw`() {
        val backing = InMemoryStore()
        backing.map[SavedServersStore.STORAGE_KEY] = "{\"not\":\"an array\", oops"

        assertTrue(SavedServersStore(backing).list().isEmpty())
    }

    @Test
    fun `valid JSON that is not an array reads as an empty list`() {
        val backing = InMemoryStore()
        backing.map[SavedServersStore.STORAGE_KEY] = "{\"name\":\"Home\"}"

        assertTrue(SavedServersStore(backing).list().isEmpty())
    }

    @Test
    fun `an element with an unknown protocol is skipped and its neighbours survive`() {
        val backing = InMemoryStore()
        backing.map[SavedServersStore.STORAGE_KEY] = """
            [
              {"name":"Good-A","host":"host-a.example","port":50002,"protocol":"SSL"},
              {"name":"Unknown","host":"host-x.example","port":9999,"protocol":"WIREGUARD"},
              {"name":"Good-B","host":"host-b.example","port":50001,"protocol":"TCP"}
            ]
        """.trimIndent()

        val list = SavedServersStore(backing).list()

        assertEquals(listOf("Good-A", "Good-B"), list.map { it.name })
        assertEquals(ElectrumProtocol.SSL, list[0].protocol)
        assertEquals(ElectrumProtocol.TCP, list[1].protocol)
    }

    @Test
    fun `an element missing a field is skipped and its neighbours survive`() {
        val backing = InMemoryStore()
        backing.map[SavedServersStore.STORAGE_KEY] = """
            [
              {"name":"No-Port","host":"host-a.example","protocol":"SSL"},
              {"name":"Good","host":"host-b.example","port":50002,"protocol":"SSL"}
            ]
        """.trimIndent()

        assertEquals(listOf("Good"), SavedServersStore(backing).list().map { it.name })
    }

    @Test
    fun `names with commas, unicode and quotes survive the round trip`() {
        val backing = InMemoryStore()
        val store = SavedServersStore(backing)

        val names = listOf(
            "Home, office",
            "Rémy's node",
            "say \"hello\"",
            "家のサーバー",
            "tab\tand newline\n",
        )
        names.forEach { name -> store.save(preset(name = name)) }

        assertEquals(names, SavedServersStore(backing).list().map { it.name })
    }
}
