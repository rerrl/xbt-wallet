package com.xbtwallet.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sealed store: envelope round-trip, legacy-plaintext migration, and the
 * forgiving reads the rest of the app's caches already promise.
 *
 * The cipher is [AesGcmStoreCipher] — the project's own AES-256-GCM over a fixed
 * key — so what is exercised here is byte-for-byte the algorithm the device
 * runs, and the whole thing is a JVM test.
 */
class EncryptedKeyValueStoreTest {

    private class InMemory : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    private val key = ByteArray(32) { index -> index.toByte() }

    private fun sealedStore(backing: InMemory) =
        EncryptedKeyValueStore(backing, AesGcmStoreCipher(key))

    // --- the round trip ------------------------------------------------------

    @Test
    fun `a stored value reads back, and what is on disk is not the plaintext`() {
        val backing = InMemory()
        val store = sealedStore(backing)
        val plaintext = """{"signers":[{"id":"abc","name":"Cold storage"}]}"""

        store.putString("signers", plaintext)

        assertEquals(plaintext, store.getString("signers"))
        val raw = requireNotNull(backing.map["signers"])
        assertTrue("the value on disk is a versioned envelope", raw.startsWith(ENVELOPE_PREFIX))
        assertFalse("and it does not carry the plaintext", raw.contains("Cold storage"))
        assertFalse(raw.contains("signers"))
    }

    @Test
    fun `the same plaintext seals to two different blobs`() {
        val backing = InMemory()
        val store = sealedStore(backing)

        store.putString("a", "same")
        val first = backing.map["a"]
        store.putString("b", "same")
        val second = backing.map["b"]

        assertNotEquals("the IV is fresh every time", first, second)
    }

    @Test
    fun `a key that was never written reads as absent`() {
        val store = sealedStore(InMemory())

        assertNull(store.getString("nothing"))
    }

    @Test
    fun `the cleared marker passes through untouched`() {
        val backing = InMemory()
        val store = sealedStore(backing)

        // [SignerStore] and [SnapshotStore] both clear by writing a blank.
        backing.map["snapshot_x"] = ""

        assertEquals("", store.getString("snapshot_x"))
    }

    // --- the legacy migration ------------------------------------------------

    @Test
    fun `a plaintext value is migrated in place on its first read`() {
        val backing = InMemory()
        val store = sealedStore(backing)
        val legacy = """{"signers":[{"id":"old","name":"Legacy"}]}"""
        // What a pre-app-lock build left behind.
        backing.map["signers"] = legacy

        assertEquals("the read the user asked for still succeeds", legacy, store.getString("signers"))

        val migrated = requireNotNull(backing.map["signers"])
        assertTrue("the plaintext is replaced by its ciphertext", migrated.startsWith(ENVELOPE_PREFIX))
        assertFalse(migrated.contains("Legacy"))
        // And the sealed copy is what the next read returns.
        assertEquals(legacy, store.getString("signers"))
    }

    @Test
    fun `every legacy key is migrated on its own read`() {
        val backing = InMemory()
        val store = sealedStore(backing)
        backing.map["signers"] = "[]"
        backing.map["snapshot_1"] = "{}"
        backing.map["statuses_1"] = "{}"

        assertEquals("[]", store.getString("signers"))
        assertEquals("{}", store.getString("snapshot_1"))
        assertEquals("{}", store.getString("statuses_1"))

        for (name in listOf("signers", "snapshot_1", "statuses_1")) {
            assertTrue(name, requireNotNull(backing.map[name]).startsWith(ENVELOPE_PREFIX))
        }
    }

    // --- the forgiving reads -------------------------------------------------

    @Test
    fun `an envelope that will not decrypt reads as absent rather than throwing`() {
        val backing = InMemory()
        val store = sealedStore(backing)
        // A well-formed envelope sealed under a different key — a lost keystore
        // key, from this store's point of view.
        val otherKey = ByteArray(32) { (it + 1).toByte() }
        backing.map["signers"] = AesGcmStoreCipher(otherKey).seal("""{"signers":[]}""")

        assertNull(store.getString("signers"))
    }

    @Test
    fun `a truncated envelope reads as absent rather than throwing`() {
        val backing = InMemory()
        val store = sealedStore(backing)
        backing.map["signers"] = ENVELOPE_PREFIX + "AAAA"

        assertNull(store.getString("signers"))
    }

    // --- the cipher itself ---------------------------------------------------

    @Test
    fun `the cipher round-trips and refuses a foreign envelope`() {
        val cipher = AesGcmStoreCipher(key)

        val envelope = cipher.seal("hello")
        assertEquals("hello", cipher.open(envelope))
        assertTrue(envelope.startsWith(ENVELOPE_PREFIX))

        try {
            cipher.open("not an envelope")
            throw AssertionError("a non-envelope must be refused")
        } catch (expected: IllegalArgumentException) {
            // The only correct answer: refuse to guess at a value this cipher did
            // not produce.
        }
    }
}
