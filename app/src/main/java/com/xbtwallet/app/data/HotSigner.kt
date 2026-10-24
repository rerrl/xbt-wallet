package com.xbtwallet.app.data

import com.xbtwallet.app.crypto.Bip39
import com.xbtwallet.app.crypto.ExtendedPrivateKey
import com.xbtwallet.app.crypto.Psbt
import com.xbtwallet.app.crypto.PsbtSigner
import com.xbtwallet.app.crypto.finalizePsbt
import com.xbtwallet.app.crypto.transactionId

/**
 * Signing a send with a hot wallet's own seed — the internal-signing half of the
 * send flow, in plain Kotlin so it can be driven by a JVM test with no device.
 *
 * The whole feature rests on one asymmetry: a hot wallet's *record* carries only
 * public material (the passphrased account zpub, its master fingerprint and the
 * account path — see [SignerType.HotSeed]), while the entropy that can spend it
 * lives in the [SeedVault]. So signing is: entropy out of the vault, entropy to
 * a mnemonic, mnemonic plus the passphrase the user typed to a seed, seed to the
 * account key, and only then a signature. The passphrase is never stored — the
 * record's own zpub *is* the check, which is why the verification below comes
 * before a single byte is signed.
 */
sealed interface SignedSend {

    /**
     * The signed, finalized transaction, ready to broadcast.
     *
     * @property txHex the raw transaction, lowercase hex — what the chain state's
     *   `broadcastTransaction` takes.
     * @property txid the transaction's id, lowercase hex.
     */
    data class Signed(val txHex: String, val txid: String) : SignedSend

    /**
     * The passphrase does **not** reproduce the record's account zpub, so this is
     * not this wallet's seed-plus-passphrase. Nothing was signed.
     */
    data object WrongPassphrase : SignedSend

    /**
     * The vault has no readable entry for this signer — nothing was stored under
     * it, or the keystore key that decrypts it is gone.
     */
    data object VaultUnavailable : SignedSend
}

/**
 * Signs [psbt] with the seed for [hotSigner], under [passphrase].
 *
 * The order is deliberate and load-bearing:
 *
 * 1. **Read the entropy** ([SeedVault.load]). An absent entry — or a vault whose
 *    keystore key has been lost, which the real vault reports by throwing — is
 *    [SignedSend.VaultUnavailable], never an exception the send screen would have
 *    to translate.
 * 2. **Verify the passphrase first.** The account is re-derived from the entropy
 *    plus [passphrase] ([HotWalletDerivation.derive]) and its zpub compared to the
 *    one the record stores. On a mismatch this returns
 *    [SignedSend.WrongPassphrase] and **stops** — the wrong key is never used to
 *    sign, and no passphrase hash exists anywhere to check against instead.
 * 3. **Sign and finalize.** Only now is the private account key derived and handed
 *    to [PsbtSigner.signPsbt], and the result run through [finalizePsbt] — which
 *    is what enforces the unified sighash byte this wallet requires.
 *
 * The PSBT's BIP32 derivations must name the wallet's **master** fingerprint (the
 * passphrased master key's, exactly the value the record stores), because that is
 * what [PsbtSigner] is told to match against.
 *
 * **Wiping, and its honest limits.** The 64-byte seed is zeroed the instant the
 * master key exists, and the chain codes of the master and account keys are zeroed
 * once the signature has been produced. That is best-effort only: the JVM gives no
 * guarantee that a `byte[]` was not copied, a `BigInteger` scalar cannot be
 * zeroed at all, and the mnemonic is a `List<String>` whose `String`s are
 * immutable. Nothing here writes a secret anywhere, and no secret appears in a log
 * or an exception message.
 *
 * @param hotSigner the hot wallet to sign for. Must be a [SignerType.HotSeed];
 *   any other kind is a programming error and throws.
 * @throws IllegalArgumentException when [hotSigner] is not a hot wallet.
 * @throws com.xbtwallet.app.crypto.PsbtException when the PSBT itself cannot be
 *   signed (a defect in the PSBT, not in the seed or the passphrase).
 */
fun signHotSend(
    vault: SeedVault,
    hotSigner: Signer,
    psbt: Psbt,
    passphrase: String,
): SignedSend {
    val hot = hotSigner.type as? SignerType.HotSeed
        ?: throw IllegalArgumentException("signHotSend needs a hot wallet to sign for")

    val entropy = runCatching { vault.load(hotSigner.id) }.getOrNull()
        ?: return SignedSend.VaultUnavailable

    val mnemonic = try {
        Bip39.entropyToMnemonic(entropy)
    } catch (failure: IllegalArgumentException) {
        // Stored entropy that is not a valid BIP-39 length: nothing to sign with.
        return SignedSend.VaultUnavailable
    } finally {
        // The mnemonic has been built; the entropy is no longer needed either way.
        entropy.fill(0)
    }

    // The passphrase is verified *before* anything is signed: the re-derived
    // account zpub must be the one the record names, or this is some other wallet.
    val derived = runCatching { HotWalletDerivation.derive(mnemonic, passphrase) }.getOrNull()
        ?: return SignedSend.WrongPassphrase
    if (derived.zpub != hot.xpub) return SignedSend.WrongPassphrase

    val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
    val master = try {
        ExtendedPrivateKey.fromSeed(seed)
    } finally {
        seed.fill(0)
    }

    val account = master.derive(ExtendedPrivateKey.parsePath("m/${hot.derivationPath}"))
    try {
        val signedPsbt = PsbtSigner.signPsbt(psbt, account, masterFingerprintBytes(hot.masterFingerprint))
        val raw = finalizePsbt(signedPsbt)
        return SignedSend.Signed(txHex = raw.toHexString(), txid = transactionId(raw))
    } finally {
        account.chainCode.fill(0)
        master.chainCode.fill(0)
    }
}

/**
 * A master fingerprint written as eight hex digits — the form a hot wallet's
 * record stores — as the four bytes a PSBT's BIP32 derivation carries.
 *
 * @throws IllegalArgumentException when the string is not exactly eight hex digits.
 */
internal fun masterFingerprintBytes(hex: String): ByteArray {
    val trimmed = hex.trim()
    require(trimmed.length == 8) { "a master fingerprint is eight hex digits, got '$hex'" }
    return ByteArray(4) { index ->
        val value = trimmed.substring(index * 2, index * 2 + 2).toIntOrNull(16)
            ?: throw IllegalArgumentException("'$hex' is not a hex fingerprint")
        value.toByte()
    }
}

/** A byte array as lowercase hex — what the broadcast RPC takes. */
private fun ByteArray.toHexString(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    forEach { byte ->
        val value = byte.toInt() and 0xFF
        out.append(digits[value ushr 4]).append(digits[value and 0x0F])
    }
    return out.toString()
}
