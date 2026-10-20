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
 * Two rules matter and are pinned here: the round trip (a value written is the
 * value read back, across instances) and the default (an absent key reads as
 * *on* — the behaviour that keeps a mid-sync screen from sleeping).
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
    fun `defaults to keeping the screen awake when no key is stored`() {
        val store = AppSettingsStore(InMemoryStore())

        assertTrue(
            "a first launch must keep the screen awake while syncing",
            store.keepScreenAwakeWhileSyncing,
        )
    }

    @Test
    fun `writes persist across instances`() {
        val backing = InMemoryStore()

        AppSettingsStore(backing).keepScreenAwakeWhileSyncing = false

        assertFalse(
            "off must survive a rebuild of the store",
            AppSettingsStore(backing).keepScreenAwakeWhileSyncing,
        )
    }

    @Test
    fun `round-trips both values`() {
        val backing = InMemoryStore()

        AppSettingsStore(backing).keepScreenAwakeWhileSyncing = true
        assertTrue(AppSettingsStore(backing).keepScreenAwakeWhileSyncing)

        AppSettingsStore(backing).keepScreenAwakeWhileSyncing = false
        assertFalse(AppSettingsStore(backing).keepScreenAwakeWhileSyncing)
    }

    @Test
    fun `uses the documented key`() {
        val backing = InMemoryStore()

        AppSettingsStore(backing).keepScreenAwakeWhileSyncing = false

        assertEquals("false", backing.map[AppSettingsStore.KEY_KEEP_SCREEN_AWAKE])
    }

    @Test
    fun `an unreadable value reads as the default rather than throwing`() {
        val backing = InMemoryStore()
        backing.map[AppSettingsStore.KEY_KEEP_SCREEN_AWAKE] = "not-a-boolean"

        assertTrue(
            "a corrupt preference must fall back to the default, never throw",
            AppSettingsStore(backing).keepScreenAwakeWhileSyncing,
        )
    }
}
