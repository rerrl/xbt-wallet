package com.xbtwallet.app.crypto

import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ECDSA over secp256k1: RFC 6979 determinism, low-S, and strict DER.
 *
 * The first test is the BIP-143 native-P2WPKH vector, which doubles as the
 * RFC 6979 vector: the signature the spec publishes for a known private key and
 * digest must be reproduced byte for byte, DER and all. Everything else pins a
 * property — the round-trip, the low-S invariant, the DER padding rule, and the
 * refusals — rather than another fixed value.
 */
class EcdsaTest {

    private val privateKey = BigInteger("619c335025c7f4012e556c2a58b2506e30b8511b53ade95ea316fd8c3286feb9", 16)
    private val digest = fromHex("c37af31116d1b27caf68aae9e3ac82f1477929014d5b917657d0eb49478cb670")
    private val expectedDer =
        "304402203609e17b84f6a7d30c80bfa610b5b4542f32a8a0d5447a12fb1366d7f01cc44a" +
            "0220573a954c4518331561406f90300e8f3358f51928d43c212a8caed02de67eebee"
    private val publicKey = requireNotNull(Secp256k1.multiply(privateKey, Secp256k1.G))

    @Test
    fun `RFC6979 vector - the BIP-143 signature is reproduced byte for byte`() {
        assertEquals(expectedDer, toHex(Ecdsa.sign(privateKey, digest)))
        assertTrue(Ecdsa.verify(publicKey, digest, fromHex(expectedDer)))
    }

    @Test
    fun `sign then verify round-trips over derived keys and messages`() {
        val master = ExtendedPrivateKey.fromSeed(ByteArray(32) { it.toByte() })
        for (i in 0 until 32) {
            val key = master.derive(listOf(i.toLong(), 0L))
            val message = Hashes.sha256("message-$i".toByteArray())
            val signature = Ecdsa.sign(key.privateKey, message)
            assertTrue("signature $i must verify", Ecdsa.verify(key.toPublic().let { Secp256k1.parse(it.publicKey) }, message, signature))
        }
    }

    @Test
    fun `a tampered signature, key or message fails`() {
        val signature = Ecdsa.sign(privateKey, digest)

        // A flipped bit in the DER body.
        val tampered = signature.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2].toInt() xor 0x01).toByte()
        assertFalse(Ecdsa.verify(publicKey, digest, tampered))

        // The wrong key.
        val otherKey = requireNotNull(Secp256k1.multiply(privateKey.add(BigInteger.ONE), Secp256k1.G))
        assertFalse(Ecdsa.verify(otherKey, digest, signature))

        // The wrong message.
        val otherDigest = Hashes.sha256(digest)
        assertFalse(Ecdsa.verify(publicKey, otherDigest, signature))
    }

    @Test
    fun `every produced signature is low-S`() {
        val halfN = Secp256k1.N.shiftRight(1)
        val master = ExtendedPrivateKey.fromSeed(ByteArray(32) { (it * 7).toByte() })
        for (i in 0 until 64) {
            val key = master.deriveChild(0x80000000L + i)
            val signature = Ecdsa.sign(key.privateKey, Hashes.sha256("low-s-$i".toByteArray()))
            val s = BigInteger(1, sBytes(signature))
            assertTrue("s must not exceed n/2", s <= halfN)
            assertTrue(s.signum() > 0)
        }
    }

    @Test
    fun `a high-S signature is rejected`() {
        val signature = Ecdsa.sign(privateKey, digest)
        val r = rBytes(signature)
        val s = BigInteger(1, sBytes(signature))
        val highS = Secp256k1.N.subtract(s).toByteArray()
        val high = encodeDer(r, highS)

        assertTrue(
            "the fixture must actually be high-S",
            BigInteger(1, highS) > Secp256k1.N.shiftRight(1),
        )
        assertFalse(Ecdsa.verify(publicKey, digest, high))
    }

    @Test
    fun `r or s with the high bit set carries the DER 0x00 pad`() {
        // Search for a digest whose signature needs the pad on r; about half do.
        var found = false
        for (i in 0 until 256) {
            val signature = Ecdsa.sign(privateKey, Hashes.sha256("pad-$i".toByteArray()))
            val r = rBytes(signature)
            if (r[0] == 0x00.toByte()) {
                found = true
                // The pad appears exactly when the magnitude's top bit is set,
                // and only then — DER's minimal-integer rule.
                assertTrue("the pad only appears before a high bit", r[1].toInt() and 0x80 != 0)
                assertTrue("a padded integer is at least 32 bytes", r.size >= 32)
                assertArrayEquals(r, BigInteger(1, r.copyOfRange(1, r.size)).toByteArray())
                assertTrue(Ecdsa.verify(publicKey, Hashes.sha256("pad-$i".toByteArray()), signature))
            }
        }
        assertTrue("expected at least one signature with a padded r", found)
    }

    @Test
    fun `malformed DER is rejected`() {
        val valid = fromHex(expectedDer)

        val cases = mapOf(
            "truncated" to valid.copyOfRange(0, valid.size - 1),
            "trailing garbage" to (valid + byteArrayOf(0x00)),
            "wrong sequence tag" to byteArrayOf(0x31) + valid.copyOfRange(1, valid.size),
            "empty" to ByteArray(0),
            "only a header" to byteArrayOf(0x30, 0x06),
            // r = 0 encoded non-minimally as 0x00 0x00, s copied from the vector.
            "padded r" to encodeDer(byteArrayOf(0x00, 0x00), sBytes(valid)),
            // A negative integer: a set top bit with no 0x00 pad.
            "negative r" to encodeDer(byteArrayOf(0x80.toByte(), 0x01), sBytes(valid)),
        )
        cases.forEach { (name, bytes) ->
            assertFalse(name, Ecdsa.verify(publicKey, digest, bytes))
        }
    }

    @Test
    fun `out-of-range r and s are rejected`() {
        val r = rBytes(fromHex(expectedDer))
        val s = sBytes(fromHex(expectedDer))
        val n = Secp256k1.N.toByteArray()

        // s = n is not a scalar.
        assertFalse(Ecdsa.verify(publicKey, digest, encodeDer(r, n)))
        // s = 0 is not a scalar.
        assertFalse(Ecdsa.verify(publicKey, digest, encodeDer(r, byteArrayOf(0x00))))
        // r = 0 is not a scalar.
        assertFalse(Ecdsa.verify(publicKey, digest, encodeDer(byteArrayOf(0x00), s)))
    }

    @Test
    fun `a digest of the wrong size is rejected`() {
        assertFalse(Ecdsa.verify(publicKey, ByteArray(31), fromHex(expectedDer)))
        assertFalse(Ecdsa.verify(publicKey, ByteArray(33), fromHex(expectedDer)))
    }

    // ---------------------------------------------------------------------
    // The minimum DER plumbing the tests need, written independently of Ecdsa's.
    // ---------------------------------------------------------------------

    private fun rBytes(signature: ByteArray): ByteArray {
        assertEquals(0x02, signature[2].toInt())
        val length = signature[3].toInt()
        return signature.copyOfRange(4, 4 + length)
    }

    private fun sBytes(signature: ByteArray): ByteArray {
        val offset = 4 + rBytes(signature).size
        assertEquals(0x02, signature[offset].toInt())
        val length = signature[offset + 1].toInt()
        return signature.copyOfRange(offset + 2, offset + 2 + length)
    }

    private fun encodeDer(r: ByteArray, s: ByteArray): ByteArray {
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
