package com.xbtwallet.app.ui.hotwallet

import com.xbtwallet.app.crypto.Bip39
import com.xbtwallet.app.data.FakeSeedVault
import com.xbtwallet.app.data.HotWalletDerivation
import com.xbtwallet.app.data.HotWalletProvisioner
import com.xbtwallet.app.data.KeyValueStore
import com.xbtwallet.app.data.ProvisionResult
import com.xbtwallet.app.data.SignerOrigin
import com.xbtwallet.app.data.SignerStore
import com.xbtwallet.app.data.SignerType
import com.xbtwallet.app.ui.signers.HOT_TAG_LABEL
import com.xbtwallet.app.ui.signers.SignerTag
import com.xbtwallet.app.ui.signers.SignerTagAccent
import com.xbtwallet.app.ui.signers.signerTagFor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The import walk end to end, on the JVM.
 *
 * The claim this walk makes is that a phrase *typed by the user* reaches the same
 * place a phrase rolled on dice does: words → [ImportWordsParser] → the shared
 * in-memory holder → [HotWalletProvisioner] → a hot signer record with the amber
 * tag and the entropy in the vault, and the holder empty afterwards. Every step
 * of that is the call the screen makes, in the order it makes it; the only part a
 * unit test cannot cover is the Android keystore behind the real vault, which
 * [FakeSeedVault] stands in for.
 *
 * The duplicate half is the second claim: a phrase whose account is already on
 * this phone is *reported*, and no second record, vault entry or name is written.
 */
class HotWalletImportFlowTest {

    /** The simplest possible [KeyValueStore]: a map that survives instances. */
    private class InMemoryStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    /** BIP-39's all-zero-entropy vector. */
    private val words: List<String> = List(11) { "abandon" } + "about"

    /** The same phrase with the noise a paste really carries. */
    private val typed = "  Abandon\tabandon abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon About \n"

    private lateinit var storage: InMemoryStore
    private lateinit var vault: FakeSeedVault
    private lateinit var store: SignerStore
    private lateinit var provisioner: HotWalletProvisioner
    private lateinit var flow: HotWalletCreateFlow

    @Before
    fun setUp() {
        storage = InMemoryStore()
        vault = FakeSeedVault()
        store = SignerStore(storage, seedVault = vault)
        provisioner = HotWalletProvisioner(store, vault)
        flow = HotWalletCreateFlow()
    }

    /**
     * What the word field's button does: validate what was typed, hand the
     * normalised words to the holder, and only then is the walk allowed to go on.
     */
    private fun enterWords(): ImportWordsState {
        val state = ImportWordsParser.validate(typed, expectedCount = 12)
        assertTrue("the field's button is only enabled for a valid phrase", state.isValid)
        flow.setMnemonic(state.words)
        return state
    }

    @Test
    fun `an imported phrase becomes a hot wallet, and the holder is emptied`() {
        val state = enterWords()
        assertEquals("what the field holds is the phrase the vectors name", words, state.words)

        // What the finish screen does on its terminal button.
        val result = provisioner.provision("Imported", requireNotNull(flow.mnemonic))
        flow.clear()

        assertTrue("a new wallet, not a duplicate", result is ProvisionResult.Created)
        val signer = (result as ProvisionResult.Created).signer
        assertTrue("the record is a hot signer", signer.type is SignerType.HotSeed)
        assertEquals("Imported", signer.name)

        // …and it is the wallet the words name: the same account the BIP-84
        // vector pins, and the same record the create path produces.
        val hot = signer.type as SignerType.HotSeed
        assertEquals(Bip39.entropyToMnemonic(Bip39.mnemonicToEntropy(words)), state.words)
        assertEquals(HotWalletDerivation.derive(words).zpub, hot.xpub)
        assertEquals("84'/0'/0'", hot.derivationPath)
        assertEquals(SignerOrigin.HOT, signer.importedVia)
        assertNotNull(store.get(signer.id))

        // The amber tag: a hot wallet is the one signer whose seed is here.
        assertEquals(SignerTag(HOT_TAG_LABEL, SignerTagAccent.HOT), signerTagFor(signer))

        // The holder is empty once the wallet exists — the words have no reason
        // to stay in memory for the rest of the session.
        assertNull(flow.mnemonic)
        assertEquals("", flow.passphrase)
    }

    @Test
    fun `the vault holds the phrase's entropy, never the seed`() {
        enterWords()
        val result = provisioner.provision("Imported", requireNotNull(flow.mnemonic))
        val signer = (result as ProvisionResult.Created).signer

        val stored = vault.peek(signer.id)
        assertNotNull("the entropy went to the vault", stored)
        assertEquals("16 bytes of mnemonic entropy", 16, stored!!.size)
        assertArrayEquals(Bip39.mnemonicToEntropy(words), stored)
        assertFalse(
            "the 64-byte PBKDF2 seed must never be stored",
            stored.contentEquals(Bip39.mnemonicToSeed(words)),
        )
        assertEquals(listOf(signer.id), vault.stores.map { it.first })
    }

    /**
     * Nothing that reached storage carries the phrase. Checked against the
     * encodings a secret would actually appear in — the phrase, the phrase run
     * together, the entropy as hex and base64 — rather than per word, because a
     * single BIP-39 word can land inside a base58 xpub by coincidence.
     */
    @Test
    fun `no part of the phrase reaches storage`() {
        enterWords()
        provisioner.provision("Imported", requireNotNull(flow.mnemonic))

        val entropy = Bip39.mnemonicToEntropy(words)
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
        assertTrue("and the record is still there", store.count() == 1)
    }

    // --- the duplicate guard -------------------------------------------------

    @Test
    fun `importing a phrase this phone already holds writes nothing`() {
        enterWords()
        val first = provisioner.provision("Imported", requireNotNull(flow.mnemonic))
        assertTrue(first is ProvisionResult.Created)
        flow.clear()

        // A second walk with the same words — the field validates them, the holder
        // takes them, and the finish screen's button runs the same call.
        enterWords()
        val second = provisioner.provision("Imported again", requireNotNull(flow.mnemonic))

        assertTrue("the walk is told, rather than left with a second wallet", second is ProvisionResult.AlreadyExists)
        assertEquals((first as ProvisionResult.Created).signer, (second as ProvisionResult.AlreadyExists).signer)
        assertEquals("one record, the one that was already here", 1, store.count())
        assertEquals("one vault entry", 1, vault.stores.size)
        assertEquals(
            "and the wallet keeps the name it was imported with",
            "Imported",
            store.list().single().name,
        )
    }

    /**
     * The holder is emptied whether the walk succeeds or turns out to be a
     * duplicate: the finish screen's way out of a duplicate clears it, exactly as
     * the success path does — and so does leaving the walk unfinished, which the
     * navigation layer does (see `HOT_WALLET_CREATE_FLOW_SCREENS`).
     */
    @Test
    fun `the holder is emptied by both the success path and the duplicate's way out`() {
        enterWords()
        val first = provisioner.provision("Imported", requireNotNull(flow.mnemonic))
        assertTrue(first is ProvisionResult.Created)
        flow.clear()
        assertNull(flow.mnemonic)

        enterWords()
        val second = provisioner.provision("Imported", requireNotNull(flow.mnemonic))
        assertTrue(second is ProvisionResult.AlreadyExists)
        // The duplicate's Home action.
        flow.clear()

        assertNull(flow.mnemonic)
        assertEquals("", flow.passphrase)
    }
}
