package com.xbtwallet.app.data

import com.xbtwallet.app.crypto.AesGcm
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vault's app-lock rules, with the keystore faked.
 *
 * [KeystoreSeedVault] reaches the platform through [VaultKeys], so a map of
 * aliases to in-memory AES-GCM keys stands in for `AndroidKeyStore` and the whole
 * thing runs on the JVM: a new entry is sealed under the auth-bound alias, a
 * pre-upgrade entry is migrated to one on its first read, a read whose key needs
 * the user comes back as a typed challenge, and a failure in the middle leaves
 * the old data exactly where it was.
 *
 * The fake's one knob is the authentication window: an auth-bound key refuses
 * `seal`/`open` while [FakeVaultKeys.authenticated] is false, which is what the
 * real keystore does when 30 seconds have passed.
 */
class SeedVaultMigrationTest {

    private class InMemory : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    private class FakeVaultKeys : VaultKeys {

        private class Entry(val authBound: Boolean, val material: ByteArray)

        private val entries = mutableMapOf<String, Entry>()
        private var counter = 0

        /** The 30-second window: an auth-bound key refuses to be used while false. */
        var authenticated = true

        /** When true, generating a *new* auth-bound key fails (a keystore refusal). */
        var failAuthGeneration = false

        val deleted = mutableListOf<String>()

        override fun has(alias: String): Boolean = entries.containsKey(alias)

        override fun keyFor(alias: String, authBound: Boolean): VaultKey {
            val existing = entries[alias]
            if (existing == null && authBound && failAuthGeneration) {
                throw IllegalStateException("the keystore refused to make an auth-bound key")
            }
            val entry = existing ?: Entry(authBound, ByteArray(32) { index -> (++counter * 7 + index).toByte() })
                .also { entries[alias] = it }
            return FakeKey(entry)
        }

        override fun delete(alias: String) {
            entries.remove(alias)
            deleted += alias
        }

        /** What [KeystoreSeedVault] would have written for [entropy] under [alias]. */
        fun sealUnder(alias: String, authBound: Boolean, entropy: ByteArray): String =
            Base64.getEncoder().encodeToString(keyFor(alias, authBound).seal(entropy))

        private inner class FakeKey(private val entry: Entry) : VaultKey {
            private fun requireAuth() {
                if (entry.authBound && !authenticated) throw VaultAuthenticationRequiredException()
            }

            override fun seal(plaintext: ByteArray): ByteArray {
                requireAuth()
                return AesGcm.encrypt(entry.material, plaintext)
            }

            override fun open(blob: ByteArray): ByteArray {
                requireAuth()
                return AesGcm.decrypt(entry.material, blob)
            }
        }
    }

    private val id = "hot-1"
    private val entropy = ByteArray(16) { index -> (index + 1).toByte() }

    private fun vault(
        backing: InMemory,
        keys: FakeVaultKeys,
        deviceSecure: Boolean = true,
    ) = KeystoreSeedVault(backing, keys, deviceSecure = { deviceSecure })

    private fun legacyAlias() = KeystoreSeedVault.LEGACY_ALIAS_PREFIX + id
    private fun authAlias() = KeystoreSeedVault.AUTH_ALIAS_PREFIX + id
    private fun prefKey() = KeystoreSeedVault.prefKey(id)

    // --- a fresh entry -------------------------------------------------------

    @Test
    fun `a new entry is sealed under the auth-bound alias and reads back`() {
        val backing = InMemory()
        val keys = FakeVaultKeys()

        vault(backing, keys).store(id, entropy)

        assertTrue("the app-lock key is the one in use", keys.has(authAlias()))
        assertFalse("nothing is left under the pre-upgrade alias", keys.has(legacyAlias()))
        val loaded = vault(backing, keys).load(id)
        assertArrayEquals(entropy, requireNotNull(loaded.entropyOrNull()))
    }

    @Test
    fun `a device with no lock screen keeps the legacy alias`() {
        val backing = InMemory()
        val keys = FakeVaultKeys()

        vault(backing, keys, deviceSecure = false).store(id, entropy)

        assertTrue(keys.has(legacyAlias()))
        assertFalse("there is nothing to bind an auth-bound key to", keys.has(authAlias()))
        assertArrayEquals(entropy, vault(backing, keys, deviceSecure = false).load(id).entropyOrNull())
    }

    // --- the migration -------------------------------------------------------

    @Test
    fun `a pre-upgrade entry is migrated to an auth-bound key on its first read`() {
        val backing = InMemory()
        val keys = FakeVaultKeys()
        // What the build before this phase wrote: a blob under the legacy alias.
        backing.map[prefKey()] = keys.sealUnder(legacyAlias(), authBound = false, entropy = entropy)
        val before = backing.map[prefKey()]

        val loaded = vault(backing, keys).load(id)

        assertArrayEquals("the read still succeeds", entropy, requireNotNull(loaded.entropyOrNull()))
        assertTrue("the entry now has an app-lock key", keys.has(authAlias()))
        assertFalse("and the old key is gone", keys.has(legacyAlias()))
        assertTrue("the old alias was deleted, not merely abandoned", legacyAlias() in keys.deleted)
        assertTrue("the blob was re-sealed", backing.map[prefKey()] != before)
    }

    @Test
    fun `a migration that needs the user hands back a challenge and completes on read`() {
        val backing = InMemory()
        val keys = FakeVaultKeys()
        backing.map[prefKey()] = keys.sealUnder(legacyAlias(), authBound = false, entropy = entropy)
        val before = backing.map[prefKey()]
        // The 30-second window has closed before the first read after the upgrade.
        keys.authenticated = false

        val loaded = vault(backing, keys).load(id)

        val challenge = requireNotNull(loaded as? SeedLoad.AuthenticationRequired) {
            "a key that needs the user must come back as a challenge, not a throw"
        }
        assertNull("nothing is decrypted before the prompt", loaded.entropyOrNull())
        assertEquals("the legacy entry is untouched until the challenge runs", before, backing.map[prefKey()])
        assertTrue("and the legacy key is still what reads it", keys.has(legacyAlias()))

        keys.authenticated = true
        assertArrayEquals(entropy, challenge.operation.read())

        assertTrue("the migration finished", keys.has(authAlias()))
        assertFalse(keys.has(legacyAlias()))
    }

    @Test
    fun `a migration failure keeps the old data and the wallet still reads`() {
        val backing = InMemory()
        val keys = FakeVaultKeys()
        backing.map[prefKey()] = keys.sealUnder(legacyAlias(), authBound = false, entropy = entropy)
        keys.failAuthGeneration = true

        val loaded = vault(backing, keys).load(id)

        assertArrayEquals("the read is not lost to a failed migration", entropy, requireNotNull(loaded.entropyOrNull()))
        assertTrue("the legacy key is left in place", keys.has(legacyAlias()))
        assertTrue("and so is the legacy blob", backing.map[prefKey()]!!.isNotEmpty())
        assertFalse(keys.has(authAlias()))
    }

    @Test
    fun `a device with no lock screen never migrates`() {
        val backing = InMemory()
        val keys = FakeVaultKeys()
        backing.map[prefKey()] = keys.sealUnder(legacyAlias(), authBound = false, entropy = entropy)

        val loaded = vault(backing, keys, deviceSecure = false).load(id)

        assertArrayEquals(entropy, requireNotNull(loaded.entropyOrNull()))
        assertTrue(keys.has(legacyAlias()))
        assertFalse(keys.has(authAlias()))
    }

    // --- the authentication challenge on an ordinary read --------------------

    @Test
    fun `an auth-bound read that needs the user hands back a challenge`() {
        val backing = InMemory()
        val keys = FakeVaultKeys()
        vault(backing, keys).store(id, entropy)
        keys.authenticated = false

        val loaded = vault(backing, keys).load(id)

        val challenge = requireNotNull(loaded as? SeedLoad.AuthenticationRequired)
        assertNull(challenge.operation.cryptoObject)

        // Still locked: completing the read must refuse rather than return junk.
        try {
            challenge.operation.read()
            throw AssertionError("a read before authentication must not succeed")
        } catch (expected: VaultAuthenticationRequiredException) {
            // Correct: the key is still closed.
        }

        keys.authenticated = true
        assertArrayEquals(entropy, challenge.operation.read())
    }

    // --- the terminal states -------------------------------------------------

    @Test
    fun `a wallet with no entry reads as absent`() {
        assertEquals(SeedLoad.Absent, vault(InMemory(), FakeVaultKeys()).load(id))
        assertNull(vault(InMemory(), FakeVaultKeys()).load(id).entropyOrNull())
    }

    @Test
    fun `a lost keystore key throws rather than fabricating a seed`() {
        val backing = InMemory()
        val keys = FakeVaultKeys()
        vault(backing, keys).store(id, entropy)
        // The blob survives; the key that could decrypt it does not.
        keys.delete(authAlias())

        try {
            vault(backing, keys).load(id)
            throw AssertionError("an unreadable entry must not read as a seed")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("keystore key is gone"))
        }
    }

    @Test
    fun `delete purges the blob and both generations of alias`() {
        val backing = InMemory()
        val keys = FakeVaultKeys()
        vault(backing, keys).store(id, entropy)
        assertTrue(vault(backing, keys).has(id))

        vault(backing, keys).delete(id)

        assertFalse(vault(backing, keys).has(id))
        assertFalse(keys.has(authAlias()))
        assertFalse(keys.has(legacyAlias()))
        assertNull(vault(backing, keys).load(id).entropyOrNull())
    }

    @Test
    fun `the store never writes the entropy in the clear`() {
        val backing = InMemory()
        vault(backing, FakeVaultKeys()).store(id, entropy)

        val raw = requireNotNull(backing.map[prefKey()])
        val decoded = Base64.getDecoder().decode(raw)
        assertFalse("the plaintext must not be in the blob", decoded.contentEquals(entropy))
        assertTrue("the blob is IV ‖ ciphertext ‖ tag", decoded.size >= 12 + 16)
    }
}
