package com.xbtwallet.app.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM over a key the caller supplies.
 *
 * This is the one symmetric primitive the hot-wallet vault needs, and it is
 * deliberately *dumb*: it does not own a key, decide where a key lives, or know
 * what a "signer" is. That is what keeps it Android-free —
 * `javax.crypto` is on the JVM, so the round-trip, the tamper detection and the
 * nonce discipline are all unit-tested off-device, and the keystore-backed vault
 * ([com.xbtwallet.app.data.KeystoreSeedVault]) is left as the thin shell that
 * only knows how to obtain a key.
 *
 * The blob layout is the whole wire format:
 *
 * ```
 * IV (12 bytes) ‖ ciphertext (same length as the plaintext) ‖ tag (16 bytes)
 * ```
 *
 * GCM's nonce must never repeat under one key — a repeat is not a degradation
 * but a break of both confidentiality and authenticity — so the IV is drawn
 * fresh from [SecureRandom] on every [encrypt] call and carried in the blob
 * rather than supplied by the caller. Nothing here is a secret: the IV is
 * public by construction. The key is not, which is why it is never copied,
 * logged or held.
 *
 * A 32-byte key is required (AES-256): the vault's keys come from the Android
 * keystore already at that size, and refusing anything else here means a
 * mis-sized key fails loudly at the seam instead of silently running AES-128.
 */
object AesGcm {

    /** AES-256 takes a 32-byte key. */
    private const val KEY_BYTES = 32

    /** GCM's customary nonce length: 96 bits, the size the standard is defined around. */
    private const val IV_BYTES = 12

    /** The tag length GCM appends; 128 bits is the maximum and the default. */
    private const val TAG_BITS = 128

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * The only random source in this file, created once.
     *
     * [SecureRandom] is thread-safe, and one instance is enough for the whole
     * app: the seeding cost is paid once instead of on every encryption.
     */
    private val random = SecureRandom()

    /**
     * Encrypts [plaintext] under [key], returning `IV ‖ ciphertext ‖ tag`.
     *
     * The same plaintext encrypted twice returns two different blobs, because
     * the IV is fresh each time. That is a property to rely on, not a nuisance:
     * it is what keeps two signers that happen to hold the same seed from
     * producing identical vault entries.
     */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) {
            "AES-256-GCM takes a $KEY_BYTES-byte key, got ${key.size}"
        }
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /**
     * Decrypts a blob produced by [encrypt], throwing if it cannot be trusted.
     *
     * A wrong key, a flipped bit anywhere in the ciphertext or tag, a truncated
     * blob and a blob whose IV was tampered with all fail the same way: GCM
     * verifies the tag before returning anything, so a caller either gets the
     * exact plaintext or an exception — never a plausible-looking partial
     * answer. The exception carries no key or plaintext material.
     */
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) {
            "AES-256-GCM takes a $KEY_BYTES-byte key, got ${key.size}"
        }
        require(blob.size > IV_BYTES) {
            "a GCM blob is at least an IV plus a tag, got ${blob.size} bytes"
        }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
