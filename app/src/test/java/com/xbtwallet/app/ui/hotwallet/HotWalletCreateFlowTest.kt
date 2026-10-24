package com.xbtwallet.app.ui.hotwallet

import com.xbtwallet.app.crypto.DiceEntropy
import com.xbtwallet.app.data.FakeSeedVault
import com.xbtwallet.app.data.HotWalletProvisioner
import com.xbtwallet.app.data.KeyValueStore
import com.xbtwallet.app.data.ProvisionResult
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerStore
import com.xbtwallet.app.data.SignerType
import com.xbtwallet.app.data.entropyOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The create walk's in-memory secret holder, and the walk it exists for.
 *
 * Two halves. The first pins the holder's contract: what it holds, that it is
 * *all* it ever holds (no persistence surface at all), and that [clear] really
 * empties it — the wipe is the only thing standing between "the words are in
 * memory for the length of the walk" and "the words are in memory for the rest
 * of the session".
 *
 * The second runs the whole walk on the JVM as far as it can go: dice rolls →
 * recovery words (via [DiceEntropy], the same call the dice screen makes) → the
 * holder → [HotWalletProvisioner] over an in-memory vault → a hot signer record.
 * That is the phase's acceptance shape — the only step a unit test cannot cover
 * is the Android keystore behind the real vault, which the fake stands in for.
 */
class HotWalletCreateFlowTest {

    /** The simplest possible [KeyValueStore]: a map. */
    private class InMemoryStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    /** Fifty legal rolls, the twelve-word count the dice screen defaults to. */
    private val rolls = "13526241536252413562624153625241356262415362135262"

    // --- the holder itself ---------------------------------------------------

    @Test
    fun `a fresh holder holds neither secret`() {
        val flow = HotWalletCreateFlow()

        assertNull(flow.mnemonic)
        assertEquals("", flow.passphrase)
    }

    @Test
    fun `the recovery words are held once they are set`() {
        val flow = HotWalletCreateFlow()
        val words = listOf("alpha", "beta", "gamma")

        flow.setMnemonic(words)

        assertEquals(words, flow.mnemonic)
    }

    /**
     * The holder copies what it is given. A caller's `MutableList` (or a
     * `toMutableList` result) aliased into the holder would be words the caller
     * could still change from under it — and the words must be exactly the ones
     * the dice produced.
     */
    @Test
    fun `the holder does not alias the list it is given`() {
        val flow = HotWalletCreateFlow()
        val caller = mutableListOf("alpha", "beta")

        flow.setMnemonic(caller)
        caller += "gamma"

        assertEquals(listOf("alpha", "beta"), flow.mnemonic)
    }

    @Test
    fun `the passphrase is held once it is set`() {
        val flow = HotWalletCreateFlow()

        flow.setPassphrase("correct horse")

        assertEquals("correct horse", flow.passphrase)
    }

    @Test
    fun `clear drops both secrets`() {
        val flow = HotWalletCreateFlow()
        flow.setMnemonic(listOf("alpha"))
        flow.setPassphrase("hunter2")

        flow.clear()

        assertNull("no words survive the wipe", flow.mnemonic)
        assertEquals("and no passphrase either", "", flow.passphrase)
    }

    /**
     * `clear` runs twice on the success path — once in the finish screen, once
     * when the navigation reset leaves the flow — and on a holder that never
     * held anything when the user backs straight out of the dice screen.
     */
    @Test
    fun `clear is idempotent and safe on an empty holder`() {
        val flow = HotWalletCreateFlow()

        flow.clear()
        flow.clear()

        assertNull(flow.mnemonic)
        assertEquals("", flow.passphrase)

        flow.setMnemonic(listOf("alpha"))
        flow.clear()
        flow.clear()

        assertNull(flow.mnemonic)
    }

    /** A holder put aside and re-created starts empty — nothing outlives it. */
    @Test
    fun `a new holder never inherits the last one's secrets`() {
        HotWalletCreateFlow().also {
            it.setMnemonic(listOf("alpha"))
            it.setPassphrase("hunter2")
        }

        val fresh = HotWalletCreateFlow()

        assertNull(fresh.mnemonic)
        assertEquals("", fresh.passphrase)
    }

    /**
     * There is no way to write what this holds anywhere: no persistence method
     * of any name, no `Saver` to hand to `rememberSaveable`, and no serialisation
     * interface — so "in memory only" is a property of the class, not a promise
     * about how it is called.
     */
    @Test
    fun `the holder has no persistence surface at all`() {
        val forbidden = listOf(
            "save", "restore", "encode", "decode", "write", "read", "toBytes",
            "serialize", "deserialize", "toJson", "toString",
        )

        val methodNames = HotWalletCreateFlow::class.java.declaredMethods.map { it.name.lowercase() }
        forbidden.forEach { name ->
            assertFalse(
                "a holder of recovery words must not expose `$name`",
                methodNames.any { it == name || it.startsWith("${name}_") },
            )
        }

        val interfaces = HotWalletCreateFlow::class.java.interfaces.map { it.name }
        assertTrue(
            "no serialisation interface may be implemented: $interfaces",
            interfaces.none { it.contains("Serializable") || it.contains("Parcelable") },
        )
    }

    // --- the walk, end to end on the JVM -------------------------------------

    /**
     * Dice rolls → words → holder → provisioner → a hot wallet in the store.
     *
     * This is the phase's whole claim, minus the keystore: the same
     * [DiceEntropy.rollsToMnemonic] call the dice screen makes, the same holder
     * the two screens share, and the same [HotWalletProvisioner] the wiring hands
     * to the finish screen. The record it produces must wear the amber "hot" tag
     * and must be watching-ready, and the seed must have gone to the vault rather
     * than to the record.
     */
    @Test
    fun `the dice walk provisions a hot wallet that is ready to watch`() {
        val storage = InMemoryStore()
        val vault = FakeSeedVault()
        val store = SignerStore(storage, seedVault = vault)
        val provisioner = HotWalletProvisioner(store, vault)
        val flow = HotWalletCreateFlow()

        // What the dice screen does on its terminal button.
        flow.setMnemonic(DiceEntropy.rollsToMnemonic(rolls))
        val words = requireNotNull(flow.mnemonic) { "the dice screen just set them" }
        assertEquals(12, words.size)

        // What the finish screen does on its terminal button, passphrase included.
        flow.setPassphrase("TREZOR")
        val result = provisioner.provision("My hot wallet", words, flow.passphrase)
        assertTrue("a new wallet, not a duplicate", result is ProvisionResult.Created)
        val created = (result as ProvisionResult.Created).signer
        flow.clear()

        val stored = requireNotNull(store.get(created.id)) { "the wallet is in the list" }
        assertEquals("My hot wallet", stored.name)
        assertTrue("and it wears the hot tag", stored.type is SignerType.HotSeed)
        assertEquals(Signer.HOT_LABEL, stored.typeLabel)
        assertTrue("a hot wallet is signing-ready by construction", stored.signingReady)

        // The entropy is in the vault, and only there: the record carries the
        // passphrased account, the vault carries the 16 bytes of mnemonic entropy.
        val vaulted = vault.load(created.id).entropyOrNull()
        assertNotNull("the seed went to the vault", vaulted)
        assertArrayEquals(DiceEntropy.rollsToEntropy(rolls), vaulted)

        // And nothing that was written to storage carries the seed: the signer
        // record is an account (zpub, fingerprint, path), not a seed. Checked
        // against the encodings a secret would actually appear in — the phrase,
        // the phrase run together, and the entropy as hex and base64. The check
        // is *not* per-word: a single BIP-39 word can appear by coincidence
        // inside a base58 xpub (the twelve words here really do put "all" in
        // one), and a test that cries wolf on a coincidence is a test nobody
        // keeps.
        val entropy = DiceEntropy.rollsToEntropy(rolls)
        val secretEncodings = listOf(
            words.joinToString(" "),
            words.joinToString(""),
            entropy.joinToString("") { "%02x".format(it.toInt() and 0xFF) },
            java.util.Base64.getEncoder().encodeToString(entropy),
        )
        val leaked = storage.map.entries.flatMap { (key, blob) ->
            secretEncodings.filter { blob.contains(it) }.map { "$key holds '$it'" }
        }
        assertTrue("no secret material may reach storage, but $leaked", leaked.isEmpty())

        // Wiping the flow does not touch the wallet that was created from it.
        assertNull(flow.mnemonic)
        assertNotNull(store.get(created.id))
    }
}
