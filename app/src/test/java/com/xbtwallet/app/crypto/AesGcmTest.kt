package com.xbtwallet.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException

/**
 * AesGcm tests.
 *
 * This is the primitive the whole hot-wallet vault rests on, and the properties
 * worth pinning are the ones a "it decrypted, so it must be fine" attitude would
 * miss:
 *
 *  - a blob only ever decrypts under the key that made it, and only if nothing
 *    in it was touched (GCM's tag is checked *before* any plaintext is returned);
 *  - the IV is fresh every time, because a repeated GCM nonce under one key is a
 *    break, not a degradation — so it is asserted, not assumed;
 *  - the blob's shape is exactly `IV ‖ ciphertext ‖ tag`, which is what the
 *    vault's Base64 entries and the decrypt path both depend on.
 *
 * Nothing here touches Android: `javax.crypto` is on the JVM.
 */
class AesGcmTest {

    /** A 32-byte AES-256 key, distinct from every other key in this file. */
    private val key = ByteArray(32) { it.toByte() }

    /** A different 32-byte key of the same length — a wrong key, not a malformed one. */
    private val otherKey = ByteArray(32) { (it + 1).toByte() }

    private val entropy = byteArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
    )

    @Test
    fun `a blob round-trips under the key that made it`() {
        val blob = AesGcm.encrypt(key, entropy)
        assertArrayEquals(entropy, AesGcm.decrypt(key, blob))
    }

    @Test
    fun `the blob is an IV, the ciphertext and a tag`() {
        val blob = AesGcm.encrypt(key, entropy)

        // 12-byte IV + plaintext-length ciphertext + 16-byte GCM tag.
        assertEquals(12 + entropy.size + 16, blob.size)
        // The IV is public and carried in the clear: the first twelve bytes are
        // not the plaintext's first twelve.
        assertFalse(
            "the IV must not be the plaintext",
            blob.copyOfRange(0, 12).contentEquals(entropy.copyOfRange(0, 12)),
        )
    }

    @Test
    fun `the same plaintext encrypts differently every time`() {
        val first = AesGcm.encrypt(key, entropy)
        val second = AesGcm.encrypt(key, entropy)

        // A fixed IV would make these identical, and a repeated GCM nonce under
        // one key is a real break — so this is the property that keeps
        // `setRandomizedEncryptionRequired(true)` honest.
        assertFalse("two encryptions must not produce the same blob", first.contentEquals(second))
        assertFalse(
            "the IV must be fresh per encryption",
            first.copyOfRange(0, 12).contentEquals(second.copyOfRange(0, 12)),
        )
        // …and both still decrypt.
        assertArrayEquals(entropy, AesGcm.decrypt(key, first))
        assertArrayEquals(entropy, AesGcm.decrypt(key, second))
    }

    @Test
    fun `an empty plaintext round-trips`() {
        val blob = AesGcm.encrypt(key, ByteArray(0))

        assertEquals(12 + 16, blob.size)
        assertEquals(0, AesGcm.decrypt(key, blob).size)
    }

    @Test
    fun `a single flipped ciphertext bit is refused`() {
        val blob = AesGcm.encrypt(key, entropy)
        // Flip one bit in the ciphertext body — not the IV — which the tag
        // covers.
        val tampered = blob.copyOf()
        tampered[12] = (tampered[12].toInt() xor 0x01).toByte()

        assertThrows(GeneralSecurityException::class.java) { AesGcm.decrypt(key, tampered) }
    }

    @Test
    fun `a flipped tag bit is refused`() {
        val blob = AesGcm.encrypt(key, entropy)
        val tampered = blob.copyOf()
        val lastIndex = tampered.size - 1
        tampered[lastIndex] = (tampered[lastIndex].toInt() xor 0x01).toByte()

        assertThrows(GeneralSecurityException::class.java) { AesGcm.decrypt(key, tampered) }
    }

    @Test
    fun `a flipped IV bit is refused`() {
        // The IV is not a secret, but it *is* authenticated: GCM folds it into
        // the tag, so rewriting it cannot silently decrypt to something else.
        val blob = AesGcm.encrypt(key, entropy)
        val tampered = blob.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()

        assertThrows(GeneralSecurityException::class.java) { AesGcm.decrypt(key, tampered) }
    }

    @Test
    fun `a wrong key is refused, not silently mis-decrypted`() {
        val blob = AesGcm.encrypt(key, entropy)

        assertThrows(GeneralSecurityException::class.java) { AesGcm.decrypt(otherKey, blob) }
    }

    @Test
    fun `a truncated blob is refused`() {
        val blob = AesGcm.encrypt(key, entropy)

        // Losing the tag entirely (a half-written prefs value, say) must fail.
        assertThrows(GeneralSecurityException::class.java) {
            AesGcm.decrypt(key, blob.copyOfRange(0, blob.size - 16))
        }
        // As must a blob chopped inside the ciphertext.
        assertThrows(GeneralSecurityException::class.java) {
            AesGcm.decrypt(key, blob.copyOfRange(0, blob.size - 1))
        }
    }

    @Test
    fun `a blob too short to hold an IV and a tag is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            AesGcm.decrypt(key, ByteArray(12))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AesGcm.decrypt(key, ByteArray(0))
        }
    }

    @Test
    fun `a key that is not 32 bytes is refused on both paths`() {
        assertThrows(IllegalArgumentException::class.java) {
            AesGcm.encrypt(ByteArray(16), entropy)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AesGcm.decrypt(ByteArray(16), AesGcm.encrypt(key, entropy))
        }
    }

    @Test
    fun `decrypting two different blobs gives each one's own plaintext`() {
        // Guards against any shared state in the cipher setup: two blobs, one
        // instance, no cross-talk.
        val a = AesGcm.encrypt(key, "first".toByteArray())
        val b = AesGcm.encrypt(key, "second".toByteArray())

        assertTrue(String(AesGcm.decrypt(key, a)) == "first")
        assertTrue(String(AesGcm.decrypt(key, b)) == "second")
        assertNotEquals(String(AesGcm.decrypt(key, a)), String(AesGcm.decrypt(key, b)))
    }
}
