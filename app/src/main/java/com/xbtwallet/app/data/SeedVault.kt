package com.xbtwallet.app.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import com.xbtwallet.app.crypto.AesGcm
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where a hot signer's seed entropy lives between sessions.
 *
 * The interface is four calls and one idea: a signer's entropy is stored, read
 * back, purged, or reported absent — by signer id, nothing else. It says nothing
 * about *how* the entropy is protected, which is what keeps the whole hot-wallet
 * orchestration ([HotWalletProvisioner]) JVM-testable against an in-memory
 * implementation while the device gets one backed by the Android keystore.
 *
 * What is stored is the **mnemonic entropy** (16 or 32 bytes) and only that.
 * The 64-byte BIP-39 seed is deliberately never written down: it is a function
 * of the mnemonic *and* the passphrase, so storing it would freeze the wallet to
 * one passphrase — the entropy plus a passphrase typed at spend time is what the
 * user actually holds. Entropy is also the only form the words can be rebuilt
 * from ([com.xbtwallet.app.crypto.Bip39.entropyToMnemonic]).
 *
 * Plaintext entropy exists only in memory, only for the length of a call: the
 * implementation encrypts before it hands anything to storage, and the caller
 * is expected to zeroize whatever it holds when it is done (see
 * [HotWalletProvisioner]). This is best-effort — the JVM gives no guarantee that
 * a byte array was not copied by a GC or a `String` — but nothing in this file
 * ever writes entropy in the clear.
 */
interface SeedVault {
    /** Encrypts [entropy] under this signer's key and stores it, replacing any previous entry. */
    fun store(signerId: String, entropy: ByteArray)

    /** The signer's entropy, or null when nothing is stored under [signerId]. */
    fun load(signerId: String): ByteArray?

    /** Purges the signer's entry — blob *and* keystore key. Silent when absent. */
    fun delete(signerId: String)

    /** Whether an entry exists for [signerId]. */
    fun has(signerId: String): Boolean
}

/**
 * The device-backed [SeedVault]: one Android keystore AES-256-GCM key per
 * signer, and the encrypted blob in the shared [KeyValueStore].
 *
 * **One key per signer**, not one key for the vault. Aliases are per-signer
 * (`xbt.hotseed.<signerId>`), so deleting a wallet destroys exactly that
 * wallet's key and a later wallet that happens to reuse the id starts under a
 * key of its own rather than inheriting the old one's.
 *
 * **A plain software-decryptable blob, on purpose, for now.** The key is
 * requested without `setUserAuthenticationRequired`: opening the app is not yet
 * gated by device credentials, so a key bound to authentication would make the
 * vault unreadable at exactly the moment the app needs it. The app-lock phase
 * is what will bind these keys to device auth; until it lands, an attacker with
 * the unlocked device and the app's own privileges is inside the vault, which is
 * the same attacker the rest of this app's storage is already open to.
 *
 * **StrongBox where it exists, plain TEE where it does not.** The key is
 * requested as `setIsStrongBoxBacked(true)`; a device without a StrongBox throws
 * [StrongBoxUnavailableException] from `init`, and that is caught and retried
 * without the flag. The request is a preference, never a requirement — a wallet
 * must not fail to be created because the phone's secure element is modest.
 *
 * The keystore itself is the only place this class touches Android beyond the
 * [KeyValueStore], and the encryption is AES-256-GCM either way: [AesGcm]
 * (plain `javax.crypto`, unit-tested on the JVM) when the key material can be
 * exported, the platform's own [Cipher] with the key by reference when it cannot
 * — see [seal]. Both paths write the same blob layout. The `context` constructor
 * parameter is part of the agreed construction shape — every store in this app is
 * built from the app context — and is unused by the keystore API today; the
 * app-lock phase, which will ask `KeyguardManager` about device authentication,
 * is where it earns its place.
 */
class KeystoreSeedVault(
    @Suppress("UNUSED_PARAMETER")
    context: Context,
    private val store: KeyValueStore,
) : SeedVault {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun store(signerId: String, entropy: ByteArray) {
        // Encrypt with the signer's own key; create that key on first use.
        val blob = seal(keyFor(signerId), entropy)
        store.putString(prefKey(signerId), Base64.encodeToString(blob, Base64.NO_WRAP))
    }

    override fun load(signerId: String): ByteArray? {
        // An empty string is what [delete] leaves behind — [KeyValueStore] has
        // no way to remove a key — and it means absent, not "zero-length seed".
        val stored = store.getString(prefKey(signerId))?.takeIf { it.isNotEmpty() } ?: return null
        // Read the key that already exists rather than creating one: if the
        // device lost the keystore entry, the blob is unreadable for real, and a
        // freshly generated key would only turn that into a confusing tag
        // failure. Naming the cause is the honest answer.
        val key = existingKey(aliasFor(signerId))
            ?: throw IllegalStateException("this wallet's seed cannot be read: its keystore key is gone")
        return open(key, Base64.decode(stored, Base64.NO_WRAP))
    }

    override fun delete(signerId: String) {
        // Purge in this order: the blob first (so a crash in between leaves no
        // ciphertext behind), then the key that could decrypt it.
        store.putString(prefKey(signerId), "")
        keyStore.deleteEntry(aliasFor(signerId))
    }

    override fun has(signerId: String): Boolean =
        !store.getString(prefKey(signerId)).isNullOrEmpty()

    /**
     * Encrypts [plaintext] under the signer's key, as `IV ‖ ciphertext ‖ tag`.
     *
     * [AesGcm] does the work whenever the platform hands the key material over —
     * that is the same GCM, in code this project unit-tests on the JVM, so the
     * bytes on disk are the bytes those tests describe.
     *
     * Some keystore keys cannot be exported (`SecretKey.getEncoded()` is null or
     * throws — StrongBox-backed keys in particular), and for those the platform's
     * own [Cipher] does the identical job with the key by reference. The layout
     * is the same on both paths, which is what lets [open] read a blob without
     * knowing which one wrote it.
     *
     * On that second path the IV must come *from* the cipher: a key generated
     * with `setRandomizedEncryptionRequired(true)` refuses a caller-supplied IV
     * (that is the point of the flag), so there is nothing to pass at `init`
     * beyond the mode. GCM's IV on Android is the 12 bytes [AesGcm] also uses,
     * and the require below refuses to write a blob of a different shape rather
     * than store one [open] could not read.
     */
    private fun seal(key: SecretKey, plaintext: ByteArray): ByteArray {
        keyMaterial(key)?.let { material -> return AesGcm.encrypt(material, plaintext) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_BYTES) {
            "the platform chose a ${iv.size}-byte GCM IV; this vault's blob layout is built on $IV_BYTES"
        }
        return iv + cipher.doFinal(plaintext)
    }

    /**
     * Decrypts a blob [seal] produced, throwing when it cannot be trusted.
     *
     * Wrong key, tampered ciphertext and a truncated blob all fail the GCM tag
     * check before any plaintext is returned, on either path.
     */
    private fun open(key: SecretKey, blob: ByteArray): ByteArray {
        keyMaterial(key)?.let { material -> return AesGcm.decrypt(material, blob) }

        require(blob.size > IV_BYTES) {
            "a GCM blob is at least an IV plus a tag, got ${blob.size} bytes"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_BYTES)),
        )
        return cipher.doFinal(blob.copyOfRange(IV_BYTES, blob.size))
    }

    /**
     * The key's raw material, when the platform will give it up — null when it
     * will not (a non-exportable keystore key).
     *
     * Only 32-byte material is accepted: anything else is not the AES-256 key
     * this vault asked for, and [AesGcm] would refuse it anyway.
     */
    private fun keyMaterial(key: SecretKey): ByteArray? =
        runCatching { key.encoded }.getOrNull()?.takeIf { it.size == KEY_SIZE_BITS / 8 }

    /** The signer's key, generating it on first use. */
    private fun keyFor(signerId: String): SecretKey {
        val alias = aliasFor(signerId)
        return existingKey(alias) ?: generateKey(alias)
    }

    private fun existingKey(alias: String): SecretKey? =
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    /**
     * Generates a per-signer AES-256 key in the keystore, preferring StrongBox.
     *
     * `setRandomizedEncryptionRequired(true)` makes the provider refuse a caller
     * that would pass its own IV — which is exactly the nonce-reuse failure
     * [AesGcm] avoids by drawing one fresh, so the platform enforces the same
     * discipline on the path that hands the IV to the cipher (see [seal]).
     * `setUserAuthenticationRequired` is deliberately *not* called; see the
     * class doc.
     *
     * StrongBox is requested only on API 28+, which is also the only place the
     * `StrongBoxUnavailableException` catch can ever fire: a device that cannot
     * do it answers with that exception, and the same key is then generated
     * without the flag. A key is never left half-created: generation happens
     * once, in whichever branch wins.
     */
    private fun generateKey(alias: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generator.init(specBuilder(alias).setIsStrongBoxBacked(true).build())
                return generator.generateKey()
            } catch (e: StrongBoxUnavailableException) {
                // No secure element here (or it is busy): fall through to a key
                // in the plain TEE.
            }
        }
        generator.init(specBuilder(alias).build())
        return generator.generateKey()
    }

    private fun specBuilder(alias: String): KeyGenParameterSpec.Builder =
        KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)

    companion object {
        /** Keystore provider name. */
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** AES-256, to match [AesGcm]'s fixed key length. */
        private const val KEY_SIZE_BITS = 256

        /**
         * The blob layout, shared with [AesGcm]: a 12-byte IV, the ciphertext,
         * then the 16-byte GCM tag.
         */
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /**
         * The [KeyValueStore] key an encrypted seed lives under: `hotseed.<id>`.
         *
         * The prefix is the grep surface for "where can seed material be on this
         * device" — exactly one key shape, and its value is always Base64
         * ciphertext. Nothing else in the app writes under it.
         */
        const val PREFS_KEY_PREFIX = "hotseed."

        /** The keystore alias an entry's key lives under: `xbt.hotseed.<id>`. */
        const val KEYSTORE_ALIAS_PREFIX = "xbt.hotseed."

        internal fun prefKey(signerId: String): String = PREFS_KEY_PREFIX + signerId

        internal fun aliasFor(signerId: String): String = KEYSTORE_ALIAS_PREFIX + signerId
    }
}
