package com.xbtwallet.app.data

import com.xbtwallet.app.crypto.Bip39
import com.xbtwallet.app.crypto.ExtendedPrivateKey
import com.xbtwallet.app.crypto.XpubVersion

/**
 * Turning a mnemonic into the one account a hot wallet watches: BIP-39 →
 * BIP-32 → BIP-84's first account, as a zpub.
 *
 * This is the pure half of provisioning — words in, a public account out — and
 * it is deliberately separate from [HotWalletProvisioner] because it is the part
 * worth testing in isolation: the same inputs must always produce the same zpub,
 * on any device, and the BIP-84 test vector pins that.
 *
 * Nothing here is secret-bearing by the time it returns. The intermediate
 * material is: the 64-byte seed and the chain codes of the keys walked on the
 * way down exist in memory for the length of the call and are wiped (`.fill(0)`)
 * as soon as they are no longer needed. That is best-effort only — a `BigInteger`
 * scalar cannot be wiped, and neither can a copy the JVM made behind our back —
 * but no secret is returned, stored or logged.
 */
object HotWalletDerivation {

    /**
     * The account path every hot wallet is derived at: BIP-84, mainnet, first
     * account. The apostrophes mean all three steps are hardened, which is what
     * BIP-84 requires for account-level keys.
     */
    const val ACCOUNT_PATH = "84'/0'/0'"

    /** `m/84'/0'/0'` as the absolute path [ExtendedPrivateKey.parsePath] takes. */
    private const val ABSOLUTE_ACCOUNT_PATH = "m/$ACCOUNT_PATH"

    /**
     * What a mnemonic resolves to: the wallet's public identity, and nothing a
     * spender could use.
     *
     * [zpub] is the account key re-encoded from `xpub` to `zpub` version bytes,
     * which is what the app's watch-only side reads and what every import path
     * already stores — so a hot wallet is a first-class wallet everywhere that
     * only needs to watch. [masterFingerprint] is the **master** key's
     * fingerprint, not the account's: that is the value a PSBT's BIP32
     * derivation records name, and the value a hardware wallet exporting the
     * same seed would report.
     */
    data class Account(
        val zpub: String,
        val masterFingerprint: String,
        val derivationPath: String,
    )

    /**
     * Derives the account for [mnemonic] under [passphrase].
     *
     * The passphrase is BIP-39's optional 25th word, and it is **not** a secret
     * this app stores: it changes the seed, and therefore the zpub, which is
     * exactly how it is later verified (see
     * [HotWalletProvisioner.passphraseMatches]). An empty passphrase is the
     * ordinary case and is what BIP-39 defines as "no passphrase at all".
     *
     * Throws [IllegalArgumentException] when [mnemonic] is not a well-formed,
     * correctly checksummed 12- or 24-word phrase. That check is not a
     * convenience: [Bip39.mnemonicToSeed] will happily hash *any* list of words,
     * so without it a mistyped phrase would silently derive a wallet nobody can
     * restore.
     */
    fun derive(mnemonic: List<String>, passphrase: String = ""): Account {
        require(Bip39.isValid(mnemonic)) { "not a valid BIP-39 mnemonic" }

        // The one place the seed exists, and it does not outlive this statement.
        val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
        val master = try {
            ExtendedPrivateKey.fromSeed(seed)
        } finally {
            seed.fill(0)
        }

        val account = master.derive(ExtendedPrivateKey.parsePath(ABSOLUTE_ACCOUNT_PATH))
        // toPublic() copies the chain code into the public key, so the private
        // side of both keys can be wiped once the account key has been encoded.
        val zpub = account.toPublic().reencode(XpubVersion.ZPUB)
        val fingerprint = hexFingerprint(master.fingerprint())
        master.chainCode.fill(0)
        account.chainCode.fill(0)

        return Account(zpub = zpub, masterFingerprint = fingerprint, derivationPath = ACCOUNT_PATH)
    }

    /**
     * BIP-32's identifier as the eight lowercase hex digits every other import
     * path in this app stores (see `UrAccountImport`'s fingerprint), written
     * byte by byte so the big-endian order is explicit rather than resting on a
     * formatter's treatment of a signed int.
     */
    private fun hexFingerprint(fingerprint: Int): String = buildString {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            append("%02x".format((fingerprint ushr shift) and 0xFF))
        }
    }
}

/**
 * What a provisioning attempt did.
 *
 * A result rather than an exception, because a duplicate is not a failure: the
 * user has typed a seed this phone already holds, which is a thing to *say* to
 * them rather than a stack trace to swallow. [HotWalletProvisioner.provision]
 * returns one of these, and the only alternative it can take is throwing — it
 * refuses a mnemonic that is not a real one with an
 * [IllegalArgumentException], because there is no wallet to describe in that case.
 */
sealed interface ProvisionResult {

    /**
     * The wallet is new: its entropy is in the vault and its signer record is
     * written.
     *
     * @property signer the record, carrying no secret.
     */
    data class Created(val signer: Signer) : ProvisionResult

    /**
     * The phrase describes an account this phone already holds, and **nothing was
     * written** — no vault entry, no record.
     *
     * @property signer the record that already exists, so a screen can name the
     *   wallet the user is looking at.
     */
    data class AlreadyExists(val signer: Signer) : ProvisionResult
}

/**
 * The orchestration the create/import screens call: words in, a stored wallet
 * out.
 *
 * Two jobs, and the shape of both is the interesting part.
 *
 * **Provisioning is ordered.** The entropy goes into the vault *before* the
 * signer record is written. A crash in the middle therefore leaves an orphaned
 * vault entry — bytes nobody can name — rather than a wallet listed on Home
 * whose seed was never stored, which would be a wallet that can never spend and
 * never say why. The vault is keyed by signer id, so the id is allocated first
 * and the record created under it.
 *
 * **The same seed is only ever stored once.** Before anything is written the
 * derived account is compared against every signer already in the store, and a
 * match comes back as [ProvisionResult.AlreadyExists] with the stores untouched.
 * Importing a phrase the phone already holds would otherwise leave two records
 * for one account — two rows on Home, one of them indistinguishable from the
 * other, and a second vault entry for a seed that is already there. The check is
 * on the account's key string, so it is exact and works across both signer kinds.
 *
 * **There is no passphrase check to store.** The passphrase is verified by
 * re-deriving: the record's zpub *is* the passphrased account, so the app can
 * take the entropy out of the vault, add the passphrase the user typed, derive
 * again, and see whether the same zpub comes out. No hash of the passphrase —
 * salted or otherwise — is written anywhere, which is one less thing on disk to
 * attack and one less thing that can fall out of sync with the wallet itself.
 *
 * This class touches no Android API and holds no state beyond the two stores, so
 * the whole flow is JVM-tested against an in-memory vault.
 */
class HotWalletProvisioner(
    private val signerStore: SignerStore,
    private val seedVault: SeedVault,
) {

    /**
     * Validates [mnemonic], derives its account, and — if this phone does not
     * already hold that account — stores the entropy and creates the signer, in
     * that order.
     *
     * The record it returns carries no secret: the passphrased zpub, the
     * passphrased master fingerprint, the account path, and whether a passphrase
     * was used at all. [HotWalletDerivation.derive] and [Bip39.mnemonicToEntropy]
     * both reject a bad phrase, so nothing is written — to the vault or to the
     * record — for a mnemonic that is not a real one.
     *
     * A phrase whose account is already in the store returns
     * [ProvisionResult.AlreadyExists] and writes nothing at all: the vault is not
     * touched and no id is allocated. The account is the identity of a wallet, so
     * a record that is already there is the same wallet, whatever name this call
     * was given.
     */
    fun provision(name: String, mnemonic: List<String>, passphrase: String = ""): ProvisionResult {
        // Validate once, and keep the entropy the validation produced: it is what
        // the vault stores. (Only the entropy is stored — never the 64-byte seed,
        // which is passphrase-dependent and would freeze the wallet to one
        // passphrase.)
        val entropy = Bip39.mnemonicToEntropy(mnemonic)
        val account = HotWalletDerivation.derive(mnemonic, passphrase)

        // The duplicate guard, before anything has been written. The comparison
        // is on the account key string the store already holds (see
        // Signer.accountXpub), which the reader hands back unchanged, so it is
        // exact for a hot wallet and for a watch-only signer of the same account.
        val existing = signerStore.list().firstOrNull { it.accountXpub == account.zpub }
        if (existing != null) {
            // The vault never saw this copy, but the caller handed it to us and
            // nothing here keeps it.
            entropy.fill(0)
            return ProvisionResult.AlreadyExists(existing)
        }

        val id = signerStore.allocateId()
        seedVault.store(id, entropy)
        // The vault has its own (encrypted) copy now, so this one can go.
        entropy.fill(0)

        return ProvisionResult.Created(
            signerStore.addHotWithId(
                id = id,
                name = name,
                xpub = account.zpub,
                masterFingerprint = account.masterFingerprint,
                derivationPath = account.derivationPath,
                hasPassphrase = passphrase.isNotEmpty(),
            ),
        )
    }

    /**
     * Whether [passphrase] is the one this wallet was created with.
     *
     * The check is a re-derivation compared against the record's own zpub — see
     * the class doc for why nothing is hashed. [entropy] is what the caller read
     * out of the vault ([SeedVault.load]); this method neither reads nor writes
     * the vault itself, so it is safe to call with bytes held for one prompt and
     * wiped after.
     *
     * False for a signer that is not a hot wallet: there is no seed to verify,
     * and answering "yes" would be the dangerous way to be wrong.
     */
    fun passphraseMatches(signer: Signer, entropy: ByteArray, passphrase: String): Boolean {
        val hot = signer.type as? SignerType.HotSeed ?: return false
        val mnemonic = Bip39.entropyToMnemonic(entropy)
        return HotWalletDerivation.derive(mnemonic, passphrase).zpub == hot.xpub
    }
}
