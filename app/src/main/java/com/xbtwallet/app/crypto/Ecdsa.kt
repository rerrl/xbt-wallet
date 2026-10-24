package com.xbtwallet.app.crypto

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ECDSA over secp256k1: deterministic (RFC 6979) signing and strict verification.
 *
 * Both halves are implemented from the specifications rather than delegated to a
 * provider, for the same reason every other primitive here is: the signature a
 * hardware signer will accept must be byte-for-byte reproducible, and
 * `Signature`/`SecureRandom` defaults vary between JDK and Android versions. A
 * deterministic nonce also removes the single worst failure mode of ECDSA — a
 * repeated `k` leaks the private key.
 *
 * Three rules are enforced rather than assumed:
 *
 * - **Low-S.** A produced signature always carries `s <= n/2`, and
 *   [verify] rejects a high-S signature outright. Bitcoin's standardness rules
 *   only relay low-S signatures, so a high-S one that "verified" would still be
 *   untransmittable.
 * - **Strict DER.** [verify] accepts exactly `30 len 02 rlen r 02 slen s`, with
 *   minimal big-endian integers and no trailing bytes, and refuses everything
 *   else (including Bitcoin's historical OpenSSL laxness).
 * - **`r`, `s` in `[1, n-1]`.** Anything outside is not a scalar and is refused
 *   before any curve arithmetic.
 *
 * Android-free: [BigInteger], [Mac] (JDK) and the app's own [Secp256k1]/[Hashes].
 */
object Ecdsa {

    /** `n/2`, the low-S boundary. */
    private val HALF_N: BigInteger = Secp256k1.N.shiftRight(1)

    /**
     * Signs [messageHash] (a 32-byte digest) with [privateKey], deterministically
     * per RFC 6979 §3.2, low-S normalised, DER-encoded.
     */
    fun sign(privateKey: BigInteger, messageHash: ByteArray): ByteArray {
        require(messageHash.size == 32) { "a signature signs a 32-byte digest, got ${messageHash.size}" }
        require(privateKey.signum() > 0 && privateKey < Secp256k1.N) {
            "a private scalar lies in [1, n-1]"
        }

        val z = bits2int(messageHash)
        val generator = Rfc6979(int2octets(privateKey), bits2octets(messageHash))

        while (true) {
            val k = generator.next()
            val point = Secp256k1.multiply(k, Secp256k1.G) ?: continue
            val r = point.x.mod(Secp256k1.N)
            if (r.signum() == 0) continue

            val s = k.modInverse(Secp256k1.N).multiply(z.add(r.multiply(privateKey))).mod(Secp256k1.N)
            if (s.signum() == 0) continue

            val lowS = if (s > HALF_N) Secp256k1.N.subtract(s) else s
            return der(r, lowS)
        }
    }

    /**
     * Verifies a DER signature over [messageHash] against [publicKey].
     *
     * Returns `false` — never throws — for a malformed encoding, an out-of-range
     * `r`/`s`, a non-low-S `s`, or a signature that simply does not match.
     */
    fun verify(publicKey: Secp256k1.Point, messageHash: ByteArray, derSignature: ByteArray): Boolean {
        if (messageHash.size != 32) return false
        val (r, s) = decodeDer(derSignature) ?: return false
        if (r.signum() <= 0 || r >= Secp256k1.N) return false
        if (s.signum() <= 0 || s >= Secp256k1.N) return false
        if (s > HALF_N) return false

        val z = bits2int(messageHash)
        val w = s.modInverse(Secp256k1.N)
        val u1 = z.multiply(w).mod(Secp256k1.N)
        val u2 = r.multiply(w).mod(Secp256k1.N)

        val point = Secp256k1.add(
            Secp256k1.multiply(u1, Secp256k1.G),
            Secp256k1.multiply(u2, publicKey),
        ) ?: return false

        return point.x.mod(Secp256k1.N) == r
    }

    // -----------------------------------------------------------------------
    // RFC 6979 §3.2 nonce generation
    // -----------------------------------------------------------------------

    /**
     * The HMAC-DRBG RFC 6979 specifies for `k`, walked one candidate at a time.
     *
     * `K = 0x00*32`, `V = 0x01*32`, then
     * `K = HMAC(K, V || 0x00 || x || h)`, `V = HMAC(K, V)`,
     * `K = HMAC(K, V || 0x01 || x || h)`, `V = HMAC(K, V)`; each candidate is
     * `bits2int(V || V || …)` until 32 bytes, with `K`/`V` stepped again between
     * candidates. `x` is `int2octets` of the private scalar and `h` is
     * `bits2octets` of the digest.
     */
    private class Rfc6979(x: ByteArray, h: ByteArray) {

        private var k = ByteArray(32)
        private var v = ByteArray(32) { 0x01 }

        init {
            k = hmacSha256(k, v + byteArrayOf(0x00) + x + h)
            v = hmacSha256(k, v)
            k = hmacSha256(k, v + byteArrayOf(0x01) + x + h)
            v = hmacSha256(k, v)
        }

        /** The next in-range nonce candidate. */
        fun next(): BigInteger {
            while (true) {
                val t = ByteArrayOutputStream()
                while (t.size() < 32) {
                    v = hmacSha256(k, v)
                    t.write(v)
                }
                val candidate = BigInteger(1, t.toByteArray())
                // Step the generator so a rejected candidate continues the stream.
                k = hmacSha256(k, v + byteArrayOf(0x00))
                v = hmacSha256(k, v)
                if (candidate.signum() > 0 && candidate < Secp256k1.N) return candidate
            }
        }
    }

    /** RFC 6979 `int2octets`: the 32-byte big-endian scalar. */
    private fun int2octets(value: BigInteger): ByteArray = Secp256k1.toFixedLength(value, 32)

    /**
     * RFC 6979 `bits2int`: the leftmost `qlen = 256` bits of the input as an
     * integer (a right shift when the input is longer than 256 bits).
     */
    private fun bits2int(bytes: ByteArray): BigInteger {
        val value = BigInteger(1, bytes)
        val excess = bytes.size * 8 - 256
        return if (excess > 0) value.shiftRight(excess) else value
    }

    /** RFC 6979 `bits2octets`: `int2octets(bits2int(b) mod n)`. */
    private fun bits2octets(bytes: ByteArray): ByteArray = int2octets(bits2int(bytes).mod(Secp256k1.N))

    // -----------------------------------------------------------------------
    // DER
    // -----------------------------------------------------------------------

    /**
     * `SEQUENCE { INTEGER r, INTEGER s }`. [BigInteger.toByteArray] already emits
     * the minimal two's-complement form, which for a positive integer is exactly
     * DER's rule: no redundant leading zero, but a `0x00` pad when the top bit of
     * the magnitude is set.
     */
    private fun der(r: BigInteger, s: BigInteger): ByteArray {
        val rBytes = r.toByteArray()
        val sBytes = s.toByteArray()
        val body = byteArrayOf(0x02, rBytes.size.toByte()) + rBytes +
            byteArrayOf(0x02, sBytes.size.toByte()) + sBytes
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    /**
     * Strictly decodes `30 len 02 rlen r 02 slen s`, returning null on anything
     * else. Only the short (single-byte) length form is accepted — the largest
     * legal signature here is 72 bytes, so a long-form length would be an
     * encoding no honest signer produces.
     */
    private fun decodeDer(signature: ByteArray): Pair<BigInteger, BigInteger>? {
        if (signature.size < 8) return null
        if (signature[0] != 0x30.toByte()) return null
        if (signature[1].toInt() and 0xFF != signature.size - 2) return null
        if (signature[2] != 0x02.toByte()) return null

        val rLength = signature[3].toInt() and 0xFF
        if (rLength == 0 || 4 + rLength + 2 > signature.size) return null
        if (signature[4 + rLength] != 0x02.toByte()) return null

        val sLength = signature[5 + rLength].toInt() and 0xFF
        if (sLength == 0 || 6 + rLength + sLength != signature.size) return null

        val rBytes = signature.copyOfRange(4, 4 + rLength)
        val sBytes = signature.copyOfRange(6 + rLength, 6 + rLength + sLength)
        if (!isMinimal(rBytes) || !isMinimal(sBytes)) return null

        return BigInteger(1, rBytes) to BigInteger(1, sBytes)
    }

    /** DER's minimality rule for a positive INTEGER's content octets. */
    private fun isMinimal(value: ByteArray): Boolean {
        if (value.isEmpty()) return false
        // A set top bit without a 0x00 pad is a negative number, not a signature scalar.
        if (value[0].toInt() and 0x80 != 0) return false
        // A 0x00 pad is only legal when the next byte would otherwise be negative.
        if (value[0] == 0x00.toByte() && (value.size == 1 || value[1].toInt() and 0x80 == 0)) return false
        return true
    }
}

/** HMAC-SHA256, RFC 6979's PRF. The JDK's provider is fixed, so it is used directly. */
private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}
