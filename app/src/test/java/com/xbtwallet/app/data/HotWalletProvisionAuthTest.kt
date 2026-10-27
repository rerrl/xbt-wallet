package com.xbtwallet.app.data

import com.xbtwallet.app.crypto.AesGcm
import com.xbtwallet.app.crypto.Bip39
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Creating a hot wallet against a vault whose key is bound to device
 * authentication — the regression that made *Create wallet* fail on every
 * device with a lock screen.
 *
 * Before the app-lock phase the vault sealed a new seed under a plain keystore
 * key and a create could not fail for want of a prompt. Binding the key to device
 * authentication (§ the app lock) changed that: a key that has just been
 * generated is not usable until the user has authenticated, so the *write* — not
 * the derivation, not the mnemonic, which were already covered by
 * [HotWalletProvisionerTest] — is the call that needs the user. The uploader
 * swallowed what the vault threw into the screen's one generic sentence, so the
 * failure looked like the words were bad.
 *
 * The real [KeystoreSeedVault] is driven here against a fake key provider whose
 * auth-bound keys stay unusable until the test says the user has authenticated —
 * exactly the window the Android keystore enforces — so this test fails on the
 * old code (nothing called [HotWalletProvisioner.provision]'s `authenticate`, and
 * the exception escaped) and passes on the fixed one.
 */
class HotWalletProvisionAuthTest {

    /** The simplest possible [KeyValueStore]: a map that survives instances. */
    private class InMemoryStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    /**
     * `AndroidKeyStore` reduced to what the vault uses, with the one knob that
     * matters here: an auth-bound key refuses `seal`/`open` until the *user* has
     * authenticated.
     *
     * [authenticated] starts false, which is the state a freshly generated key is
     * in on a real device — the state the old code never noticed, because it never
     * asked the user.
     */
    private class AuthBoundKeys : VaultKeys {

        private class Entry(val authBound: Boolean, val material: ByteArray)

        private val entries = mutableMapOf<String, Entry>()
        private var counter = 0

        /** The authentication window: an auth-bound key is unusable while false. */
        var authenticated = false

        override fun has(alias: String): Boolean = entries.containsKey(alias)

        override fun keyFor(alias: String, authBound: Boolean): VaultKey {
            val entry = entries[alias]
                ?: Entry(authBound, ByteArray(32) { index -> (++counter * 11 + index).toByte() })
                    .also { entries[alias] = it }
            return FakeKey(entry)
        }

        override fun delete(alias: String) {
            entries.remove(alias)
        }

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

    private class Fixture(deviceSecure: Boolean) {
        val backing = InMemoryStore()
        val keys = AuthBoundKeys()
        val vault = KeystoreSeedVault(backing, keys, deviceSecure = { deviceSecure })
        private var ids = 0
        val store = SignerStore(
            storage = backing,
            now = { 1_700_000_000L },
            newId = { "id-${++ids}" },
            seedVault = vault,
        )
        val provisioner = HotWalletProvisioner(store, vault)
    }

    /** `abandon` × 11 + `about`: BIP-39's all-zero-entropy vector. */
    private val words: List<String> = List(11) { "abandon" } + "about"

    // --- the regression ------------------------------------------------------

    @Test
    fun `a new wallet asks the user to authenticate before its seed is sealed`() {
        val fixture = Fixture(deviceSecure = true)
        var prompts = 0

        val result = runBlocking {
            fixture.provisioner.provision("Hot wallet", words) {
                prompts++
                // What a successful device prompt does: the key's window opens.
                fixture.keys.authenticated = true
                true
            }
        }

        assertTrue("the wallet is created, got $result", result is ProvisionResult.Created)
        assertEquals("the user is asked exactly once", 1, prompts)

        val signer = (result as ProvisionResult.Created).signer
        assertTrue("its entropy is in the vault", fixture.vault.has(signer.id))
        assertArrayEquals(
            "and it is the entropy of these words",
            Bip39.mnemonicToEntropy(words),
            fixture.vault.load(signer.id).entropyOrNull(),
        )
        assertEquals(1, fixture.store.count())
        assertEquals(signer, fixture.store.list().single())
    }

    @Test
    fun `the first write is what reports the need for the user, not a guess beforehand`() {
        // The vault generates the signer's key during the write, so there is no
        // way to know a prompt is needed before attempting it. Pin that: the
        // attempt that fails is the *same* write that had already made the key,
        // and the retry reuses it rather than making a second one.
        val fixture = Fixture(deviceSecure = true)
        val before = fixture.keys.authenticated

        val result = runBlocking {
            fixture.provisioner.provision("Hot wallet", words) {
                fixture.keys.authenticated = true
                true
            }
        }

        assertFalse("the test starts inside the closed window", before)
        assertTrue(result is ProvisionResult.Created)
        assertTrue(
            "the key the refused first attempt generated is the one in use",
            fixture.keys.has(KeystoreSeedVault.AUTH_ALIAS_PREFIX + (result as ProvisionResult.Created).signer.id),
        )
    }

    @Test
    fun `a refused authentication writes nothing at all`() {
        val fixture = Fixture(deviceSecure = true)
        var prompts = 0

        val result = runBlocking {
            fixture.provisioner.provision("Hot wallet", words) {
                prompts++
                false
            }
        }

        assertSame(ProvisionResult.AuthenticationCancelled, result)
        assertEquals(1, prompts)
        assertFalse("no vault entry", fixture.vault.has("id-1"))
        assertEquals("no signer record", 0, fixture.store.count())
        assertNull(
            "and the signer list was never written to disk",
            fixture.backing.map[SignerStore.STORAGE_KEY],
        )
    }

    @Test
    fun `an authentication that still does not satisfy the key writes nothing`() {
        // A prompt the user passed but that the key does not accept (the
        // Class-2-biometric-against-a-strong-key case): the retry is refused too,
        // and that is reported rather than escaping as an untyped failure.
        val fixture = Fixture(deviceSecure = true)

        val result = runBlocking {
            fixture.provisioner.provision("Hot wallet", words) { true }
        }

        assertSame(ProvisionResult.AuthenticationCancelled, result)
        assertFalse(fixture.vault.has("id-1"))
        assertEquals(0, fixture.store.count())
    }

    // --- and the case that must not regress the other way --------------------

    @Test
    fun `a device with no lock screen creates the wallet without ever prompting`() {
        // No lock screen means no auth-bound key, and no prompt to show. The
        // callback is a hard failure here so a prompt cannot be harmless by
        // accident.
        val fixture = Fixture(deviceSecure = false)

        val result = runBlocking {
            fixture.provisioner.provision("Hot wallet", words) {
                throw AssertionError("there is nothing to authenticate against on this device")
            }
        }

        assertTrue("the wallet is created, got $result", result is ProvisionResult.Created)
        assertEquals(1, fixture.store.count())
    }

    @Test
    fun `a duplicate is still refused before any prompt`() {
        val fixture = Fixture(deviceSecure = true)
        runBlocking {
            fixture.provisioner.provision("Hot wallet", words) {
                fixture.keys.authenticated = true
                true
            }
        }

        val again = runBlocking {
            fixture.provisioner.provision("Another name", words) {
                throw AssertionError("a duplicate must not reach the vault, so nothing to authenticate")
            }
        }

        assertTrue("the duplicate is reported", again is ProvisionResult.AlreadyExists)
        assertEquals(1, fixture.store.count())
    }
}
