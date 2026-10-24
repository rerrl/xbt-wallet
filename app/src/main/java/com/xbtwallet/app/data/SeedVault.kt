package com.xbtwallet.app.data

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import com.xbtwallet.app.crypto.AesGcm
import java.security.KeyStore
import java.util.Base64
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
 *
 * **A read may need the user.** Since the app-lock phase the device's vault keys
 * are bound to device authentication ([KeystoreSeedVault]), so [load] returns a
 * [SeedLoad]: the entropy, "nothing stored", or a typed
 * [SeedLoad.AuthenticationRequired] carrying the [AuthenticatedRead] that
 * completes once the user has authenticated. The caller is the only layer that
 * can show a prompt, so it is the caller that resolves a challenge (see
 * [com.xbtwallet.app.data.signHotSend], which prompts and then signs — or
 * refuses and signs nothing).
 */
interface SeedVault {
    /** Encrypts [entropy] under this signer's key and stores it, replacing any previous entry. */
    fun store(signerId: String, entropy: ByteArray)

    /** The signer's entropy, a typed authentication challenge, or nothing. */
    fun load(signerId: String): SeedLoad

    /** Purges the signer's entry — blob *and* keystore key. Silent when absent. */
    fun delete(signerId: String)

    /** Whether an entry exists for [signerId]. */
    fun has(signerId: String): Boolean
}

/**
 * What a [SeedVault.load] produced.
 *
 * A sealed result rather than a nullable `ByteArray` because there are three
 * distinct outcomes and two of them are not "no seed": a stored seed the user
 * must authenticate to read is a *different* fact from a wallet with no entry,
 * and the send flow must say different things about them.
 */
sealed interface SeedLoad {

    /**
     * The entropy, decrypted.
     *
     * The caller owns the array and must zeroize it when it is done — see the
     * interface doc.
     */
    class Loaded(val entropy: ByteArray) : SeedLoad

    /** Nothing is stored for this signer (or its entry was cleared). */
    data object Absent : SeedLoad

    /**
     * The seed is stored, but the key that decrypts it is bound to device
     * authentication and the user has not authenticated recently enough.
     *
     * [operation] is what completes the read after a successful prompt. Nothing
     * has been read yet, and nothing is read until [AuthenticatedRead.read] runs.
     */
    class AuthenticationRequired(val operation: AuthenticatedRead) : SeedLoad
}

/** The entropy when it was read without a prompt, or null for either other outcome. */
fun SeedLoad.entropyOrNull(): ByteArray? = (this as? SeedLoad.Loaded)?.entropy

/**
 * A vault read that is waiting on the user's authentication.
 *
 * The vault hands one of these back instead of throwing, so the caller — which
 * is the only layer that can show a prompt — decides what to do: authenticate and
 * call [read], or abandon the attempt. Nothing is decrypted until [read] runs.
 *
 * [cryptoObject] is the platform object a `BiometricPrompt` would be handed as
 * its `CryptoObject`, when the key is crypto-bound. A key created with a
 * **validity window** (`setUserAuthenticationValidityDurationSeconds`, which is
 * what the 30-second window in [KeystoreSeedVault] is) is *not* crypto-bound:
 * the prompt's successful authentication is itself what unlocks the key for the
 * window, so there is nothing to hand the prompt and this is null. The property
 * exists so a future auth-per-use key can supply one without changing the
 * caller, and the caller already passes it through when it is present.
 */
interface AuthenticatedRead {
    /** The `CryptoObject` payload for the system prompt, or null when none is needed. */
    val cryptoObject: Any?

    /**
     * Completes the read. Called only after the user has authenticated.
     *
     * @throws VaultAuthenticationRequiredException when the authentication did
     *   not actually satisfy the key (a race with the window closing).
     */
    fun read(): ByteArray
}

/** Raised when a vault key cannot be used until the user authenticates. */
class VaultAuthenticationRequiredException :
    Exception("the user must authenticate to use this vault key")

/**
 * One vault key: the AES-GCM primitive, with the keystore kept behind it.
 *
 * [seal] and [open] throw [VaultAuthenticationRequiredException] when the key is
 * bound to device authentication and the user has not authenticated — the one
 * failure the vault translates into a typed challenge. Every other failure (a
 * lost key, a corrupt blob) is an ordinary exception and stays one.
 */
interface VaultKey {
    fun seal(plaintext: ByteArray): ByteArray
    fun open(blob: ByteArray): ByteArray
}

/**
 * Where vault keys come from — the seam that makes [KeystoreSeedVault] testable
 * on the JVM.
 *
 * The Android implementation ([AndroidVaultKeys]) is a thin shell over
 * `AndroidKeyStore`; a test supplies an in-memory one and can simulate the
 * authentication window with a flag. The vault's own rules — which alias it
 * reads, when it migrates, what a challenge looks like — are therefore testable
 * without a device.
 */
interface VaultKeys {
    /** Whether a key exists under [alias]. */
    fun has(alias: String): Boolean

    /**
     * The key under [alias], generating it on first use.
     *
     * @param authBound whether a newly generated key should be bound to device
     *   authentication. Ignored when the key already exists.
     */
    fun keyFor(alias: String, authBound: Boolean): VaultKey

    /** Removes the key under [alias]; silent when it is absent. */
    fun delete(alias: String)
}

/**
 * The device-backed [SeedVault]: one Android keystore AES-256-GCM key per
 * signer, and the encrypted blob in the shared [KeyValueStore].
 *
 * **One key per signer**, not one key for the vault. Aliases are per-signer, so
 * deleting a wallet destroys exactly that wallet's key and a later wallet that
 * happens to reuse the id starts under a key of its own rather than inheriting
 * the old one's.
 *
 * **The key is bound to device authentication.** A newly created key is made
 * with `setUserAuthenticationRequired(true)` and a **30-second validity
 * window** (`setUserAuthenticationParameters(30, …)` on API 30+, the deprecated
 * `setUserAuthenticationValidityDurationSeconds(30)` below it). One successful
 * device authentication therefore covers a whole signing operation — the vault
 * read, the passphrase re-derivation that verifies the wallet, and the signature
 * itself — which is exactly the window a send needs. A window rather than
 * auth-per-use is deliberate: auth-per-use would demand a prompt for every
 * cipher operation, and the send flow would ask the user three times.
 *
 * **Two alias generations.** The pre-app-lock keys live under
 * [LEGACY_ALIAS_PREFIX] and are *not* auth-bound; the app-lock keys live under
 * [AUTH_ALIAS_PREFIX]. On the first read after the upgrade a legacy entry is
 * opened with the old key and re-sealed under a fresh auth-bound key, and only
 * then is the old alias deleted (see [migrate]). A failure in the middle leaves
 * the old blob and the old key exactly where they were, so the wallet still
 * opens and the migration is simply retried on the next read. Nothing about the
 * failure is logged.
 *
 * **No device lock means no auth-bound key.** On a device with no lock screen
 * there is nothing to bind a key to and the keystore refuses to make one, so
 * such a device keeps using the legacy (non-auth) alias and the migration is
 * skipped — a wallet that already exists here still opens. Creating a *new* hot
 * wallet is refused upstream (see the app lock), which is why this is a
 * degradation and not a hole.
 *
 * **StrongBox where it exists, plain TEE where it does not.** The key is
 * requested as `setIsStrongBoxBacked(true)`; a device without a StrongBox throws
 * [StrongBoxUnavailableException] from `init`, and that is caught and retried
 * without the flag. The request is a preference, never a requirement — a wallet
 * must not fail to be created because the phone's secure element is modest.
 */
class KeystoreSeedVault(
    private val store: KeyValueStore,
    private val keys: VaultKeys,
    private val deviceSecure: () -> Boolean,
) : SeedVault {

    /**
     * The app-context construction every store in this app uses.
     *
     * The keystore and the keyguard are both reached from the context; the
     * primary constructor takes them as collaborators so a JVM test can stand in
     * for both.
     */
    constructor(context: Context, store: KeyValueStore) : this(
        store = store,
        keys = AndroidVaultKeys(),
        deviceSecure = { context.isDeviceSecure() },
    )

    override fun store(signerId: String, entropy: ByteArray) {
        // Encrypt with the signer's own key; create that key on first use. A
        // device with no lock screen gets the legacy, non-auth key — see the
        // class doc.
        val blob = keys.keyFor(aliasFor(signerId), authBound = deviceSecure()).seal(entropy)
        store.putString(prefKey(signerId), BASE64_ENCODER.encodeToString(blob))
    }

    override fun load(signerId: String): SeedLoad {
        // An empty string is what [delete] leaves behind — [KeyValueStore] has
        // no way to remove a key — and it means absent, not "zero-length seed".
        val stored = store.getString(prefKey(signerId))?.takeIf { it.isNotEmpty() }
            ?: return SeedLoad.Absent
        val blob = BASE64_DECODER.decode(stored)

        // The current generation wins when it is present. Read the key that
        // already exists rather than creating one: if the device lost the
        // keystore entry, the blob is unreadable for real, and a freshly
        // generated key would only turn that into a confusing tag failure.
        val authAlias = AUTH_ALIAS_PREFIX + signerId
        if (keys.has(authAlias)) {
            return readOrChallenge(keys.keyFor(authAlias, authBound = true), blob)
        }

        // No current key: this entry predates the app lock, so open it with the
        // legacy key and migrate it forward.
        val legacyAlias = LEGACY_ALIAS_PREFIX + signerId
        if (keys.has(legacyAlias)) {
            val legacy = keys.keyFor(legacyAlias, authBound = false)
            val entropy = try {
                legacy.open(blob)
            } catch (auth: VaultAuthenticationRequiredException) {
                // A legacy key is never auth-bound; if this fires the keystore is
                // in a state this vault cannot explain. Treat it as unreadable
                // rather than guessing.
                return SeedLoad.Absent
            }
            return migrate(signerId, entropy)
        }

        throw IllegalStateException("this wallet's seed cannot be read: its keystore key is gone")
    }

    override fun delete(signerId: String) {
        // Purge in this order: the blob first (so a crash in between leaves no
        // ciphertext behind), then the keys that could decrypt it.
        store.putString(prefKey(signerId), "")
        keys.delete(aliasFor(signerId))
        keys.delete(LEGACY_ALIAS_PREFIX + signerId)
    }

    override fun has(signerId: String): Boolean =
        !store.getString(prefKey(signerId)).isNullOrEmpty()

    /** The alias a *new* entry is written under, chosen by whether a lock exists. */
    private fun aliasFor(signerId: String): String =
        if (deviceSecure()) AUTH_ALIAS_PREFIX + signerId else LEGACY_ALIAS_PREFIX + signerId

    /** Decrypts [blob], or hands back the challenge when the key needs the user. */
    private fun readOrChallenge(key: VaultKey, blob: ByteArray): SeedLoad = try {
        SeedLoad.Loaded(key.open(blob))
    } catch (auth: VaultAuthenticationRequiredException) {
        SeedLoad.AuthenticationRequired(GcmRead(key, blob))
    }

    /**
     * Re-seals a legacy entry under a fresh auth-bound key.
     *
     * The read that produced [entropy] already succeeded, so the worst a failure
     * here can do is leave the entry where it was: the old blob and the old key
     * are untouched until the new blob is safely written, and the old alias is
     * deleted only afterwards. That ordering is the whole safety argument — a
     * crash or a cancelled prompt at any point leaves a wallet that still opens.
     */
    private fun migrate(signerId: String, entropy: ByteArray): SeedLoad {
        // Nothing to migrate to on a device with no lock screen.
        if (!deviceSecure()) return SeedLoad.Loaded(entropy)

        return try {
            resealUnderAuthKey(signerId, entropy)
            SeedLoad.Loaded(entropy)
        } catch (auth: VaultAuthenticationRequiredException) {
            // The key wants the user before it will seal. Hand back a challenge
            // that finishes the migration; until it runs, the legacy entry is
            // exactly as it was.
            SeedLoad.AuthenticationRequired(MigrationRead(signerId, entropy.copyOf()))
        } catch (failure: Exception) {
            // Keep the old data and try again next launch. The read already
            // succeeded, so the wallet is readable either way.
            SeedLoad.Loaded(entropy)
        }
    }

    /** Writes the auth-bound blob, then drops the legacy alias. */
    private fun resealUnderAuthKey(signerId: String, entropy: ByteArray) {
        val blob = keys.keyFor(AUTH_ALIAS_PREFIX + signerId, authBound = true).seal(entropy)
        store.putString(prefKey(signerId), BASE64_ENCODER.encodeToString(blob))
        keys.delete(LEGACY_ALIAS_PREFIX + signerId)
    }

    /** The challenge that completes a migration once the user authenticates. */
    private inner class MigrationRead(
        private val signerId: String,
        private val entropy: ByteArray,
    ) : AuthenticatedRead {
        override val cryptoObject: Any? get() = null

        override fun read(): ByteArray {
            try {
                resealUnderAuthKey(signerId, entropy)
                return entropy.copyOf()
            } finally {
                entropy.fill(0)
            }
        }
    }

    /** The challenge that completes an ordinary read once the user authenticates. */
    private class GcmRead(
        private val key: VaultKey,
        private val blob: ByteArray,
    ) : AuthenticatedRead {
        override val cryptoObject: Any? get() = null
        override fun read(): ByteArray = key.open(blob)
    }

    companion object {
        /**
         * The [KeyValueStore] key an encrypted seed lives under: `hotseed.<id>`.
         *
         * The prefix is the grep surface for "where can seed material be on this
         * device" — exactly one key shape, and its value is always Base64
         * ciphertext. Nothing else in the app writes under it.
         */
        const val PREFS_KEY_PREFIX = "hotseed."

        /** The keystore alias an auth-bound (app-lock) entry's key lives under. */
        const val AUTH_ALIAS_PREFIX = "xbt.hotseed.v2."

        /** The keystore alias the pre-app-lock keys live under. */
        const val LEGACY_ALIAS_PREFIX = "xbt.hotseed."

        /**
         * Base64 for the blob, as `java.util.Base64` rather than the Android
         * one: the basic encoder does not wrap, which is the whole reason the
         * blob fits in one preference value, and being a JDK class it keeps
         * [KeystoreSeedVault] exercisable on the JVM.
         */
        private val BASE64_ENCODER: Base64.Encoder = Base64.getEncoder()
        private val BASE64_DECODER: Base64.Decoder = Base64.getDecoder()

        internal fun prefKey(signerId: String): String = PREFS_KEY_PREFIX + signerId
    }
}

/**
 * The Android keystore as a [VaultKeys].
 *
 * AES-256-GCM, one key per alias, StrongBox where the device has it. A key
 * requested [authBound] carries a 30-second validity window (see
 * [KeystoreSeedVault]).
 */
class AndroidVaultKeys : VaultKeys {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun has(alias: String): Boolean = keyStore.containsAlias(alias)

    override fun keyFor(alias: String, authBound: Boolean): VaultKey {
        val key = existingKey(alias) ?: generateKey(alias, authBound)
        return AndroidVaultKey(key)
    }

    override fun delete(alias: String) {
        keyStore.deleteEntry(alias)
    }

    private fun existingKey(alias: String): SecretKey? =
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    /**
     * Generates an AES-256 key, preferring StrongBox.
     *
     * `setRandomizedEncryptionRequired(true)` makes the provider refuse a caller
     * that would pass its own IV — which is exactly the nonce-reuse failure
     * [AesGcm] avoids by drawing one fresh, so the platform enforces the same
     * discipline on the path that hands the IV to the cipher.
     *
     * StrongBox is requested only on API 28+, which is also the only place the
     * `StrongBoxUnavailableException` catch can ever fire: a device that cannot
     * do it answers with that exception, and the same key is then generated
     * without the flag. A key is never left half-created: generation happens
     * once, in whichever branch wins.
     */
    private fun generateKey(alias: String, authBound: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generator.init(specBuilder(alias, authBound).setIsStrongBoxBacked(true).build())
                return generator.generateKey()
            } catch (e: StrongBoxUnavailableException) {
                // No secure element here (or it is busy): fall through to a key
                // in the plain TEE.
            }
        }
        generator.init(specBuilder(alias, authBound).build())
        return generator.generateKey()
    }

    private fun specBuilder(alias: String, authBound: Boolean): KeyGenParameterSpec.Builder {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)

        if (authBound) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    AUTH_VALIDITY_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
            }
            builder.setUserAuthenticationRequired(true)
            // A new fingerprint must not destroy the wallet: the app's own lock
            // gate is the thing that guards access, and invalidating the key on
            // enrolment would silently strand the seed. (There is no lock-screen
            // PIN change to survive — a changed credential keeps the key.)
            builder.setInvalidatedByBiometricEnrollment(false)
        }
        return builder
    }

    private companion object {
        /** Keystore provider name. */
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** AES-256, to match [AesGcm]'s fixed key length. */
        const val KEY_SIZE_BITS = 256

        /** The window one authentication covers, in seconds. */
        const val AUTH_VALIDITY_SECONDS = 30
    }
}

/**
 * One Android keystore key as a [VaultKey].
 *
 * [AesGcm] does the work whenever the platform hands the key material over — the
 * same GCM, in code this project unit-tests on the JVM. Android keystore keys
 * are non-exportable, so in practice the platform's own [Cipher] does the
 * identical job with the key by reference; the layout is the same on both paths.
 *
 * The IV comes *from* the cipher on that path: a key generated with
 * `setRandomizedEncryptionRequired(true)` refuses a caller-supplied IV, so there
 * is nothing to pass at `init` beyond the mode. Decrypt requires the IV, which
 * is carried in the blob.
 *
 * A key bound to device authentication throws [UserNotAuthenticatedException]
 * from `init` (or from `doFinal`) when the user has not authenticated within the
 * validity window; that is translated into [VaultAuthenticationRequiredException],
 * the one failure the vault turns into a prompt.
 */
private class AndroidVaultKey(private val key: SecretKey) : VaultKey {

    override fun seal(plaintext: ByteArray): ByteArray {
        keyMaterial()?.let { material -> return AesGcm.encrypt(material, plaintext) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } catch (notAuthed: UserNotAuthenticatedException) {
            throw VaultAuthenticationRequiredException()
        }
        val iv = cipher.iv
        require(iv.size == IV_BYTES) {
            "the platform chose a ${iv.size}-byte GCM IV; this vault's blob layout is built on $IV_BYTES"
        }
        return try {
            iv + cipher.doFinal(plaintext)
        } catch (notAuthed: UserNotAuthenticatedException) {
            throw VaultAuthenticationRequiredException()
        }
    }

    override fun open(blob: ByteArray): ByteArray {
        keyMaterial()?.let { material -> return AesGcm.decrypt(material, blob) }

        require(blob.size > IV_BYTES) {
            "a GCM blob is at least an IV plus a tag, got ${blob.size} bytes"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_BYTES)),
            )
        } catch (notAuthed: UserNotAuthenticatedException) {
            throw VaultAuthenticationRequiredException()
        }
        return try {
            cipher.doFinal(blob.copyOfRange(IV_BYTES, blob.size))
        } catch (notAuthed: UserNotAuthenticatedException) {
            throw VaultAuthenticationRequiredException()
        }
    }

    /**
     * The key's raw material, when the platform will give it up — null when it
     * will not (any Android keystore key).
     *
     * Only 32-byte material is accepted: anything else is not the AES-256 key
     * this vault asked for, and [AesGcm] would refuse it anyway.
     */
    private fun keyMaterial(): ByteArray? =
        runCatching { key.encoded }.getOrNull()?.takeIf { it.size == KEY_SIZE_BITS / 8 }

    private companion object {
        /** AES-256, to match [AesGcm]'s fixed key length. */
        const val KEY_SIZE_BITS = 256

        /** The blob layout, shared with [AesGcm]: a 12-byte IV, then the ciphertext and tag. */
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * Whether the device has a lock screen — a PIN, a pattern, a password or a
 * biometric that is backed by one.
 *
 * This is the single fact the app lock turns on: no lock screen means there is
 * nothing to authenticate against, so the app cannot be locked and a vault key
 * cannot be bound to device authentication. Read through `KeyguardManager`
 * rather than inferred from whether a prompt succeeded, because the answer must
 * be the same before any prompt is shown.
 */
internal fun Context.isDeviceSecure(): Boolean =
    getSystemService(KeyguardManager::class.java)?.isDeviceSecure ?: false
