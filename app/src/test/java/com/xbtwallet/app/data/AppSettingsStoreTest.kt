package com.xbtwallet.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppSettingsStore tests.
 *
 * The store talks to a [KeyValueStore] rather than to SharedPreferences, so the
 * whole thing runs on the JVM with an in-memory map — no Android framework.
 *
 * Three rules matter and are pinned here: the round trip (a value written is the
 * value read back, across instances), the default (an absent key reads as *on* —
 * the behaviour that keeps the screen from sleeping while the app is open), and
 * the frozen key name (the preference the new semantics read is physically the
 * same stored key an older build wrote, so an existing choice carries over).
 */
class AppSettingsStoreTest {

    /** The simplest possible [KeyValueStore]: a map that survives instances. */
    private class InMemoryStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun `defaults to keeping the screen on when no key is stored`() {
        val store = AppSettingsStore(InMemoryStore())

        assertTrue(
            "a first launch must keep the screen on while the app is open",
            store.keepScreenOnWhileOpen,
        )
    }

    @Test
    fun `writes persist across instances`() {
        val backing = InMemoryStore()

        AppSettingsStore(backing).keepScreenOnWhileOpen = false

        assertFalse(
            "off must survive a rebuild of the store",
            AppSettingsStore(backing).keepScreenOnWhileOpen,
        )
    }

    @Test
    fun `round-trips both values`() {
        val backing = InMemoryStore()

        AppSettingsStore(backing).keepScreenOnWhileOpen = true
        assertTrue(AppSettingsStore(backing).keepScreenOnWhileOpen)

        AppSettingsStore(backing).keepScreenOnWhileOpen = false
        assertFalse(AppSettingsStore(backing).keepScreenOnWhileOpen)
    }

    @Test
    fun `uses the documented key`() {
        val backing = InMemoryStore()

        AppSettingsStore(backing).keepScreenOnWhileOpen = false

        assertEquals("false", backing.map[AppSettingsStore.KEY_KEEP_SCREEN_ON])
    }

    /**
     * The one thing a rename must not break: an older build stored the user's
     * choice under the *literal* string `keepScreenAwakeWhileSyncing`, and the
     * preference kept that name when its semantics changed. A raw write under
     * that literal is read back through the property, and the property writes
     * back under it — proving no silent reset on upgrade.
     */
    @Test
    fun `reads and writes the frozen legacy key string`() {
        val backing = InMemoryStore()
        backing.map["keepScreenAwakeWhileSyncing"] = "false"

        val store = AppSettingsStore(backing)

        assertFalse(
            "a choice stored by the old build must carry over",
            store.keepScreenOnWhileOpen,
        )
        assertEquals(
            "the constant must still be the literal the old build wrote",
            "keepScreenAwakeWhileSyncing",
            AppSettingsStore.KEY_KEEP_SCREEN_ON,
        )

        store.keepScreenOnWhileOpen = true
        assertEquals("true", backing.map["keepScreenAwakeWhileSyncing"])
    }

    @Test
    fun `the default is still true`() {
        assertEquals(true, AppSettingsStore.DEFAULT_KEEP_SCREEN_ON)
    }

    @Test
    fun `an unreadable value reads as the default rather than throwing`() {
        val backing = InMemoryStore()
        backing.map[AppSettingsStore.KEY_KEEP_SCREEN_ON] = "not-a-boolean"

        assertTrue(
            "a corrupt preference must fall back to the default, never throw",
            AppSettingsStore(backing).keepScreenOnWhileOpen,
        )
    }
}
