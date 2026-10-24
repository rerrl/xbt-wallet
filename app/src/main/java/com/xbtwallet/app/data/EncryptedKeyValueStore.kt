package com.xbtwallet.app.data

import com.xbtwallet.app.crypto.AesGcm
import java.util.Base64

/**
 * Base64 for every envelope, as `java.util.Base64` rather than the Android one:
 * the basic encoder does not wrap (which is what keeps an envelope one
 * preference value), and a JDK class keeps this file identical to the shape the
 * JVM tests exercise through [AesGcmStoreCipher].
 */
private val BASE64_ENCODER: Base64.Encoder = Base64.getEncoder()
private val BASE64_DECODER: Base64.Decoder = Base64.getDecoder()

/**
 * Seals and opens one stored value, as a string.
 *
 * The interface is deliberately tiny and Android-free at the seam: the
 * production implementation reaches the Android keystore
 * ([KeystoreStoreCipher]), and a JVM test supplies [AesGcmStoreCipher] over a
 * fixed key — the *same* AES-GCM this project already unit-tests — so the
 * envelope round-trip and the legacy migration are exercised on the JVM without
 * a device.
 */
interface StoreCipher {
    /** Encrypts [plaintext] into an envelope string. */
    fun seal(plaintext: String): String

    /** Decrypts an envelope produced by [seal]. */
    fun open(envelope: String): String
}

/**
 * The versioned envelope a sealed value wears: `v1:` then Base64.
 *
 * The prefix is what tells a sealed value from the plaintext a pre-app-lock
 * build wrote under the same key — the marker that makes [EncryptedKeyValueStore]
 * able to migrate in place without being told the schema changed. It is spelled
 * out rather than left implicit so a future envelope format can be recognised
 * and handled rather than misread.
 */
const val ENVELOPE_PREFIX = "v1:"

/**
 * A [KeyValueStore] that seals every value it writes with AES-256-GCM under a
 * keystore key.
 *
 * **What it is for.** The signer list and the cached account snapshots are
 * plaintext JSON in SharedPreferences — a list of account xpubs, the addresses
 * derived from them, and every transaction the account has seen. None of that is
 * a private key, but it is the whole map of an account, and this phase closes
 * the gap: from here on those values are ciphertext at rest.
 *
 * **Versioned, and migrating.** [getString] reads a value in either shape. A
 * value that is an envelope is decrypted. A value that is not — plaintext
 * written before this store existed — is returned as-is *and re-sealed in place*,
 * so the plaintext is overwritten by its own ciphertext on the first read and
 * never survives a second one. The write is to the same key, which is what makes
 * the migration idempotent: there is no second copy to clean up, and a crash
 * between the read and the write simply repeats the migration next time.
 *
 * **Forgiving, like the rest of the app's caches.** A value that is an envelope
 * but will not decrypt — a lost keystore key, a truncated blob — reads as
 * absent rather than throwing. That is the same degradation [SnapshotStore]
 * already accepts, and the alternative is an exception in the middle of
 * composition, which would take the whole app down on every screen. The one
 * thing this store must never do is crash a screen.
 *
 * **Not everything is encrypted.** Server settings and the app's own preference
 * flags stay plaintext, deliberately: a server host is not a secret worth a
 * keystore dependency at startup, and the preference flags have to be readable
 * before any key is available. The wiring in `XbtWalletApp` wraps only the two
 * stores whose contents describe an account.
 */
class EncryptedKeyValueStore(
    private val delegate: KeyValueStore,
    private val cipher: StoreCipher,
) : KeyValueStore {

    override fun getString(key: String): String? {
        val raw = delegate.getString(key) ?: return null
        // The blank string is the app's own "cleared" marker and is passed
        // through untouched: sealing it would be work for no gain, and callers
        // already read blank as absent.
        if (raw.isEmpty()) return raw

        if (!raw.startsWith(ENVELOPE_PREFIX)) {
            // Legacy plaintext. Re-seal it under the same key and hand back the
            // plaintext this read was asked for; the next read sees ciphertext.
            // A failure to seal is not fatal — the value is still returned — and
            // the migration is simply retried next time.
            runCatching { delegate.putString(key, cipher.seal(raw)) }
            return raw
        }

        return runCatching { cipher.open(raw) }.getOrNull()
    }

    override fun putString(key: String, value: String) {
        delegate.putString(key, cipher.seal(value))
    }
}

/**
 * [StoreCipher] over a key the caller holds — the JVM test double, and the shape
 * the production cipher's own round-trip is pinned against.
 *
 * Uses [AesGcm], the project's own AES-256-GCM, so what the tests exercise is
 * byte-for-byte the algorithm the device runs.
 */
class AesGcmStoreCipher(private val key: ByteArray) : StoreCipher {

    override fun seal(plaintext: String): String =
        ENVELOPE_PREFIX + BASE64_ENCODER.encodeToString(
            AesGcm.encrypt(key, plaintext.toByteArray(Charsets.UTF_8)),
        )

    override fun open(envelope: String): String {
        require(envelope.startsWith(ENVELOPE_PREFIX)) { "not a sealed value" }
        val blob = BASE64_DECODER.decode(envelope.removePrefix(ENVELOPE_PREFIX))
        return String(AesGcm.decrypt(key, blob), Charsets.UTF_8)
    }
}

/**
 * [StoreCipher] backed by an Android keystore key under its own alias.
 *
 * **Not bound to device authentication.** This key guards data the app must read
 * the moment a screen is composed — the wallet list on Home, a signer's cached
 * balance — and a prompt before any of that could render would make the lock
 * gate's job impossible. Device authentication guards the *seeds*
 * ([KeystoreSeedVault]); this guards the map of the accounts. The alias is its
 * own ([STORE_ALIAS]) so it can be rotated or dropped without touching a seed's
 * key.
 *
 * The keystore key is non-exportable, so the platform [javax.crypto.Cipher]
 * does the work by reference; the blob layout is the one [AesGcm] describes.
 */
class KeystoreStoreCipher : StoreCipher {

    private val key = AndroidVaultKeys().keyFor(STORE_ALIAS, authBound = false)

    override fun seal(plaintext: String): String =
        ENVELOPE_PREFIX + BASE64_ENCODER.encodeToString(
            key.seal(plaintext.toByteArray(Charsets.UTF_8)),
        )

    override fun open(envelope: String): String {
        require(envelope.startsWith(ENVELOPE_PREFIX)) { "not a sealed value" }
        val blob = BASE64_DECODER.decode(envelope.removePrefix(ENVELOPE_PREFIX))
        return String(key.open(blob), Charsets.UTF_8)
    }

    private companion object {
        /** The keystore alias for the store cipher — its own, not a seed's. */
        const val STORE_ALIAS = "xbt.store"
    }
}
