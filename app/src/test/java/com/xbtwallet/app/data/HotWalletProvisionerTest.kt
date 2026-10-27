package com.xbtwallet.app.data

import com.xbtwallet.app.crypto.Bip39
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hot-wallet provisioning tests.
 *
 * The whole flow is plain Kotlin over a [SignerStore] and a [SeedVault], so it is
 * driven here by an in-memory vault and a map — no Android, no emulator, no
 * keystore.
 *
 * Three things are being pinned, and they are the three that would be expensive
 * to get wrong:
 *
 *  1. **The derivation is right.** The BIP-84 test mnemonic must produce the
 *     zpub the BIP publishes, byte for byte; that is the cross-check that says
 *     this wallet is the wallet other software sees.
 *  2. **The vault holds entropy, and the record holds no secret.** The 16-byte
 *     mnemonic entropy goes to the vault; the 64-byte PBKDF2 seed never does; and
 *     nothing secret reaches the signer blob.
 *  3. **The passphrase is verified by re-derivation, not by a stored hash.**
 *     `"TREZOR"` matches, `"trezor"` does not — BIP-39 passphrases are
 *     case-sensitive — and there is nothing on disk either could be compared
 *     against.
 */
class HotWalletProvisionerTest {

    /** The simplest possible [KeyValueStore]: a map that survives instances. */
    private class InMemoryStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    /**
     * The published BIP-84 test vector: the account key of the all-zero entropy
     * mnemonic, re-encoded as a zpub.
     */
    private val bip84Zpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868" +
            "RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    /**
     * …and its master fingerprint, which is *not* in the BIP: it is the first
     * four bytes of HASH160 of the master public key, re-derived independently
     * when this test was written. Pinning it here means a fingerprint-format
     * change (endianness, padding, case) fails loudly instead of quietly
     * mismatching what a hardware wallet reports for the same seed.
     */
    private val bip84MasterFingerprint = "73c5da0a"

    /** `abandon` × 11 + `about`: BIP-39's all-zero-entropy vector. */
    private val bip84Words: List<String> = List(11) { "abandon" } + "about"

    private lateinit var backing: InMemoryStore
    private lateinit var vault: FakeSeedVault
    private lateinit var signerStore: SignerStore
    private lateinit var provisioner: HotWalletProvisioner
    private var idCounter = 0
    private var clock = 1_700_000_000L

    @Before
    fun setUp() {
        idCounter = 0
        clock = 1_700_000_000L
        backing = InMemoryStore()
        vault = FakeSeedVault()
        signerStore = SignerStore(
            storage = backing,
            now = { ++clock },
            newId = { "id-${++idCounter}" },
            seedVault = vault,
        )
        provisioner = HotWalletProvisioner(signerStore, vault)
    }

    /**
     * The new record from a successful attempt.
     *
     * [HotWalletProvisioner.provision] reports what it did rather than throwing on
     * a duplicate, so a test that expects a *new* wallet unwraps the result here —
     * and fails, with the result it actually got, when it was not one.
     */
    private val ProvisionResult.created: Signer
        get() {
            assertTrue("expected a new wallet, got $this", this is ProvisionResult.Created)
            return (this as ProvisionResult.Created).signer
        }

    private fun hotOf(signer: Signer) = signer.type as SignerType.HotSeed

    /**
     * [HotWalletProvisioner.provision] is `suspend` because a device-backed
     * vault's write can need the user to authenticate first (see
     * [HotWalletProvisionAuthTest]). The in-memory vault these tests use never
     * challenges, so no prompt is ever needed here — this drives the attempt to
     * completion instead, keeping each test a plain statement about what was
     * stored.
     */
    private fun provisionNow(
        provisioner: HotWalletProvisioner,
        name: String,
        mnemonic: List<String>,
        passphrase: String = "",
    ): ProvisionResult = runBlocking { provisioner.provision(name, mnemonic, passphrase) }

    // --- the derivation -----------------------------------------------------

    @Test
    fun `the BIP-84 test mnemonic provisions the published account zpub`() {
        val signer = provisionNow(provisioner, "Hot wallet", bip84Words).created

        assertEquals(bip84Zpub, hotOf(signer).xpub)
        assertEquals("84'/0'/0'", hotOf(signer).derivationPath)
        assertEquals(bip84MasterFingerprint, hotOf(signer).masterFingerprint)
    }

    @Test
    fun `a hot wallet is a hot signer, and it is signing-ready`() {
        val signer = provisionNow(provisioner, "Hot wallet", bip84Words).created

        assertTrue("the record is a HotSeed", signer.type is SignerType.HotSeed)
        assertEquals(SignerOrigin.HOT, signer.importedVia)
        assertEquals("hot", signer.typeLabel)
        assertTrue("a hot wallet can sign", signer.signingReady)
        assertEquals("Hot wallet", signer.name)
        assertEquals(1, signerStore.count())
        assertEquals(signer, signerStore.list().single())
    }

    @Test
    fun `the mnemonic with a passphrase derives a different account`() {
        val plain = provisionNow(provisioner, "No passphrase", bip84Words).created
        val hidden = provisionNow(provisioner, "Hidden", bip84Words, passphrase = "TREZOR").created

        assertFalse("a passphrase changes the seed, and so the account",
            hotOf(plain).xpub == hotOf(hidden).xpub)
        assertFalse("…and the master fingerprint with it",
            hotOf(plain).masterFingerprint == hotOf(hidden).masterFingerprint)
        assertFalse(hotOf(plain).hasPassphrase)
        assertTrue(hotOf(hidden).hasPassphrase)
    }

    @Test
    fun `derivation is deterministic`() {
        // Same words, same passphrase, same account — every time, in this run and
        // in any other. Nothing here may depend on a clock, a random source or
        // iteration order.
        val first = HotWalletDerivation.derive(bip84Words)
        val second = HotWalletDerivation.derive(bip84Words)

        assertEquals(first, second)
        assertEquals(bip84Zpub, first.zpub)

        // Provisioning the same phrase twice is the duplicate guard's business
        // (see below) — the account it derives is still the same account, which
        // is exactly why there is only one wallet to have.
        val one = provisionNow(provisioner, "One", bip84Words).created
        val again = provisionNow(provisioner, "Two", bip84Words)
        assertTrue(again is ProvisionResult.AlreadyExists)
        assertEquals(one, (again as ProvisionResult.AlreadyExists).signer)
        assertEquals(hotOf(one).xpub, first.zpub)
        assertEquals(hotOf(one).masterFingerprint, first.masterFingerprint)
    }

    @Test
    fun `the fingerprint is eight lowercase hex characters`() {
        val fingerprint = HotWalletDerivation.derive(bip84Words).masterFingerprint

        assertEquals(8, fingerprint.length)
        assertTrue("lowercase hex only", fingerprint.all { it in "0123456789abcdef" })
    }

    @Test
    fun `the passphrase path is part of the derivation and is not trimmed`() {
        // A passphrase is not a name: whitespace is significant, and so is case.
        // Two different passphrases must give two different accounts.
        val spaced = HotWalletDerivation.derive(bip84Words, " TREZOR")
        val bare = HotWalletDerivation.derive(bip84Words, "TREZOR")

        assertFalse(spaced.zpub == bare.zpub)
    }

    // --- what the vault gets ------------------------------------------------

    @Test
    fun `the vault holds the mnemonic entropy, never the seed`() {
        val signer = provisionNow(provisioner, "Hot wallet", bip84Words).created

        val stored = vault.peek(signer.id)
        assertNotNull("the seed is in the vault under the signer id", stored)
        assertEquals("entropy, not the 64-byte PBKDF2 seed", 16, stored!!.size)
        assertArrayEquals(Bip39.mnemonicToEntropy(bip84Words), stored)
        assertFalse(
            "the 64-byte seed must never be stored",
            stored.contentEquals(Bip39.mnemonicToSeed(bip84Words)),
        )
        assertTrue(vault.has(signer.id))
        assertEquals(listOf(signer.id), vault.stores.map { it.first })
    }

    @Test
    fun `the vault write happens before the signer record`() {
        // The ordering is the safety property: a crash in between must leave an
        // orphaned vault entry, never a listed wallet whose seed was never
        // stored. So at the last moment the vault is written, the record that
        // owns the entry must not exist yet.
        val watchedVault = object : SeedVault by vault {
            override fun store(signerId: String, entropy: ByteArray) {
                assertNull("the record must not exist yet", signerStore.get(signerId))
                vault.store(signerId, entropy)
                assertNotNull("…and it must not exist while the vault is written", vault.load(signerId).entropyOrNull())
            }
        }
        val sut = HotWalletProvisioner(signerStore, watchedVault)

        val signer = provisionNow(sut, "Hot wallet", bip84Words).created
        assertTrue(vault.has(signer.id))
    }

    @Test
    fun `the stored signer record carries no secret`() {
        val signer = provisionNow(provisioner, "Hot wallet", bip84Words).created
        val json = backing.map[SignerStore.STORAGE_KEY].orEmpty()

        assertTrue("the record is tagged hot", json.contains("\"type\":\"hot\""))
        assertTrue("the zpub is on it", json.contains(bip84Zpub))
        assertTrue("the fingerprint is on it", json.contains(bip84MasterFingerprint))
        assertTrue("the origin is written explicitly", json.contains("\"importedVia\":\"hot\""))
        assertTrue(json.contains("\"hasPassphrase\":false"))
        assertFalse("no mnemonic word reaches the record", json.contains("abandon"))
        assertFalse("no entropy hex reaches the record", json.contains("00000000000000000000000000000000"))
        assertFalse("no path to the vault", json.contains("hotseed."))
        // And the signer survives a reload as itself.
        val reloaded = SignerStore(backing, seedVault = vault).get(signer.id)
        assertEquals(signer.type, reloaded?.type)
        assertEquals(SignerOrigin.HOT, reloaded?.importedVia)
    }

    // --- the passphrase check ----------------------------------------------

    @Test
    fun `passphraseMatches accepts the passphrase the wallet was made with`() {
        val signer = provisionNow(provisioner, "Hidden", bip84Words, passphrase = "TREZOR").created
        val entropy = vault.load(signer.id).entropyOrNull()!!

        assertTrue(provisioner.passphraseMatches(signer, entropy, "TREZOR"))
    }

    @Test
    fun `passphraseMatches is case-sensitive and refuses an empty passphrase`() {
        val signer = provisionNow(provisioner, "Hidden", bip84Words, passphrase = "TREZOR").created
        val entropy = vault.load(signer.id).entropyOrNull()!!

        assertFalse("BIP-39 passphrases are case-sensitive", provisioner.passphraseMatches(signer, entropy, "trezor"))
        assertFalse("and 'no passphrase' is a different wallet entirely", provisioner.passphraseMatches(signer, entropy, ""))
        assertFalse(provisioner.passphraseMatches(signer, entropy, "TREZOR "))
    }

    @Test
    fun `passphraseMatches accepts the empty passphrase on a wallet made without one`() {
        val signer = provisionNow(provisioner, "Hot wallet", bip84Words).created
        val entropy = vault.load(signer.id).entropyOrNull()!!

        assertTrue(provisioner.passphraseMatches(signer, entropy, ""))
        assertFalse(provisioner.passphraseMatches(signer, entropy, "TREZOR"))
    }

    @Test
    fun `passphraseMatches is false for a signer that is not a hot wallet`() {
        // There is no seed to verify against, and answering "yes" would be the
        // dangerous way to be wrong.
        val watchOnly = Signer(
            id = "w",
            name = "Cold",
            type = SignerType.WatchOnlyXpub(bip84Zpub, masterFingerprint = bip84MasterFingerprint, derivationPath = "84'/0'/0'"),
            createdAtEpochSeconds = 0L,
            importedVia = SignerOrigin.SEEDSIGNER_UR,
        )

        assertFalse(provisioner.passphraseMatches(watchOnly, Bip39.mnemonicToEntropy(bip84Words), "TREZOR"))
    }

    @Test
    fun `passphraseMatches survives the vault round-trip the UI will do`() {
        // The real call site reads the entropy out of the vault, checks it, and
        // wipes what it read. Both halves of that are exercised here.
        val signer = provisionNow(provisioner, "Hidden", bip84Words, passphrase = "TREZOR").created
        val entropy = vault.load(signer.id).entropyOrNull()!!

        assertTrue(provisioner.passphraseMatches(signer, entropy, "TREZOR"))
        entropy.fill(0)
        assertArrayEquals(ByteArray(16), entropy)
        // The vault's own copy is untouched by the wipe.
        assertTrue(provisioner.passphraseMatches(signer, vault.load(signer.id).entropyOrNull()!!, "TREZOR"))
    }

    // --- invalid input ------------------------------------------------------

    @Test
    fun `an invalid mnemonic writes nothing at all`() {
        // A bad checksum, a wrong word count, and a word that is not in the
        // list: all three must be refused before anything is stored.
        val badChecksum = List(12) { "abandon" }
        val tooShort = List(11) { "abandon" }
        val notAWord = List(11) { "abandon" } + "zzzz"

        listOf(badChecksum, tooShort, notAWord).forEach { words ->
            val vaultBefore = vault.stores.size
            assertThrows("$words must be refused", IllegalArgumentException::class.java) {
                provisionNow(provisioner, "Nope", words).created
            }
            assertEquals("the vault is untouched", vaultBefore, vault.stores.size)
            assertTrue("the vault is untouched", vault.deleted.isEmpty())
            assertEquals("no signer was recorded", 0, signerStore.count())
            assertNull("no vault entry exists", vault.peek("id-1"))
        }

        // And the backing store was never written to at all — not an empty list,
        // nothing: [SignerStore.read] writes nothing when there is nothing to
        // migrate, and [provision] failed before its write.
        assertNull(backing.map[SignerStore.STORAGE_KEY])
    }

    @Test
    fun `a 24-word mnemonic is refused when it is not a real one`() {
        val wrongChecksum = List(24) { "abandon" }

        assertThrows(IllegalArgumentException::class.java) {
            HotWalletDerivation.derive(wrongChecksum)
        }
    }

    @Test
    fun `deriving refuses an invalid mnemonic rather than hashing any words at all`() {
        // BIP-39's seed step will hash *anything*, so this is the check that
        // stops a mistyped phrase creating a wallet nobody can restore.
        assertThrows(IllegalArgumentException::class.java) {
            HotWalletDerivation.derive(listOf("not", "a", "mnemonic"))
        }
    }

    // --- deletion -----------------------------------------------------------

    @Test
    fun `deleting a hot wallet purges its vault entry`() {
        val signer = provisionNow(provisioner, "Hot wallet", bip84Words).created
        assertTrue(vault.has(signer.id))

        assertTrue(signerStore.delete(signer.id))

        assertFalse("the seed is gone with the wallet", vault.has(signer.id))
        assertEquals(listOf(signer.id), vault.deleted)
        assertNull(vault.load(signer.id).entropyOrNull())
    }

    // --- the duplicate guard ------------------------------------------------

    /**
     * The account is a wallet's identity, so a phrase whose account is already in
     * the store is the same wallet — and provisioning it again must leave the
     * stores exactly as they were: no second record for one account, no second
     * vault entry for a seed that is already there, and no id allocated on the way
     * to discovering that.
     */
    @Test
    fun `a phrase this phone already holds is reported, and nothing is written`() {
        val first = provisionNow(provisioner, "Hot wallet", bip84Words).created
        val vaultWritesAfterFirst = vault.stores.size

        val second = provisionNow(provisioner, "Another name entirely", bip84Words)

        assertTrue("the attempt must report what it found", second is ProvisionResult.AlreadyExists)
        assertEquals(first, (second as ProvisionResult.AlreadyExists).signer)
        assertEquals("still one signer", 1, signerStore.count())
        assertEquals(
            "and the record is the one that already existed, name and all",
            "Hot wallet",
            signerStore.get(first.id)?.name,
        )
        assertEquals("no second vault entry", vaultWritesAfterFirst, vault.stores.size)
        assertEquals(1, vault.stores.size)
    }

    /**
     * The guard reads the store, so it holds across a relaunch: a second
     * provisioner over the same backing — a fresh install's worth of state — still
     * recognises the account.
     */
    @Test
    fun `the guard reads the store, so it survives a reload`() {
        val first = provisionNow(provisioner, "Hot wallet", bip84Words).created

        val reloaded = SignerStore(backing, seedVault = vault)
        val second = provisionNow(HotWalletProvisioner(reloaded, vault), "Hot wallet", bip84Words)

        assertTrue(second is ProvisionResult.AlreadyExists)
        assertEquals(first.id, (second as ProvisionResult.AlreadyExists).signer.id)
        assertEquals(1, reloaded.count())
        assertEquals(1, vault.stores.size)
    }

    /**
     * The same words under a different passphrase are a different account
     * altogether — nothing about them collides, so both wallets exist.
     */
    @Test
    fun `the same words under a passphrase are a different wallet, not a duplicate`() {
        val plain = provisionNow(provisioner, "Plain", bip84Words)
        val hidden = provisionNow(provisioner, "Hidden", bip84Words, passphrase = "TREZOR")

        assertTrue(plain is ProvisionResult.Created)
        assertTrue(hidden is ProvisionResult.Created)
        assertEquals(2, signerStore.count())
        assertEquals(2, vault.stores.size)
    }

    /**
     * A duplicate is refused before the vault write, so the ordering guarantee is
     * untouched: the guard returns ahead of the id allocation and the vault write
     * entirely, which this fake vault proves by never being called.
     */
    @Test
    fun `a duplicate never reaches the vault`() {
        provisionNow(provisioner, "Hot wallet", bip84Words).created
        val vault = object : SeedVault by this.vault {
            override fun store(signerId: String, entropy: ByteArray) {
                throw AssertionError("a duplicate must not write to the vault")
            }
        }

        val result = provisionNow(HotWalletProvisioner(signerStore, vault), "Hot wallet", bip84Words)

        assertTrue(result is ProvisionResult.AlreadyExists)
    }
}
