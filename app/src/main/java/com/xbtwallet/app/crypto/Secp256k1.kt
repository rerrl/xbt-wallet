package com.xbtwallet.app.crypto

import java.math.BigInteger

/**
 * The secp256k1 curve, public-key side only.
 *
 * This file implements exactly what a watch-only coordinator needs: adding two
 * public points, multiplying a public point by a scalar, and (de)serialising
 * compressed points. Child key derivation for a *public* key (BIP-32 CKDpub)
 * needs `point(IL) + Kpar`, so point arithmetic is unavoidable even though no
 * private key is ever involved.
 *
 * There is deliberately no function here that takes a private scalar and
 * produces a point: the wallet never has a secret to multiply (PLAN.md §2.1).
 * A reviewer can check that claim by reading this file top to bottom.
 */
object Secp256k1 {

    /** Field prime: 2^256 - 2^32 - 977. */
    val P: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)

    /** Group order. */
    val N: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    private val SEVEN = BigInteger.valueOf(7)
    private val THREE = BigInteger.valueOf(3)

    /** A point in affine coordinates. The point at infinity is modelled as `null`. */
    data class Point(val x: BigInteger, val y: BigInteger)

    /** Generator point G. */
    val G: Point = Point(
        BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
    )

    /**
     * Point addition, with `null` as the point at infinity.
     *
     * Doubling and the "same x, opposite y" case (which yields infinity) are
     * handled explicitly; the generic slope formula divides by a non-zero
     * difference only.
     */
    fun add(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x) {
            // Either the same point (double) or y-negations (infinity).
            if (a.y.add(b.y).mod(P) == BigInteger.ZERO) return null
            return double(a)
        }
        val slope = b.y.subtract(a.y).multiply(b.x.subtract(a.x).modInverse(P)).mod(P)
        val x = slope.pow(2).subtract(a.x).subtract(b.x).mod(P)
        val y = slope.multiply(a.x.subtract(x)).subtract(a.y).mod(P)
        return Point(x, y)
    }

    private fun double(a: Point): Point? {
        if (a.y.signum() == 0) return null
        val slope = a.x.pow(2).multiply(THREE).multiply(a.y.shiftLeft(1).modInverse(P)).mod(P)
        val x = slope.pow(2).subtract(a.x.shiftLeft(1)).mod(P)
        val y = slope.multiply(a.x.subtract(x)).subtract(a.y).mod(P)
        return Point(x, y)
    }

    /** Double-and-add scalar multiplication of a public point. Returns `null` for infinity. */
    fun multiply(scalar: BigInteger, point: Point): Point? {
        val k = scalar.mod(N)
        if (k.signum() == 0) return null
        var result: Point? = null
        var addend: Point? = point
        var remaining = k
        while (remaining.signum() > 0) {
            if (remaining.testBit(0)) result = add(result, addend)
            addend = addend?.let { double(it) }
            remaining = remaining.shiftRight(1)
        }
        return result
    }

    /** Serialises a point to its 33-byte compressed form (`0x02`/`0x03` prefix). */
    fun compress(point: Point): ByteArray {
        val prefix = if (point.y.testBit(0)) 0x03 else 0x02
        return byteArrayOf(prefix.toByte()) + toFixedLength(point.x, 32)
    }

    /**
     * Parses a 33-byte compressed public key.
     *
     * Because the field prime is 3 mod 4, the square root of the right-hand side
     * is `rhs^((p+1)/4)`; a candidate whose square is not `rhs` means the x
     * coordinate is not on the curve, and this throws [IllegalArgumentException].
     */
    fun decompress(compressed: ByteArray): Point {
        require(compressed.size == 33) { "a compressed point is 33 bytes, got ${compressed.size}" }
        val prefix = compressed[0].toInt() and 0xFF
        require(prefix == 0x02 || prefix == 0x03) { "unknown point prefix 0x${prefix.toString(16)}" }

        val x = BigInteger(1, compressed.copyOfRange(1, 33))
        require(x < P) { "x coordinate is outside the field" }

        val rhs = x.modPow(THREE, P).add(SEVEN).mod(P)
        var y = rhs.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        require(y.multiply(y).mod(P) == rhs) { "x is not on the curve" }
        if (y.testBit(0) != (prefix == 0x03)) y = P.subtract(y)
        return Point(x, y)
    }

    /** Parses a compressed or uncompressed public key. */
    fun parse(encoded: ByteArray): Point = when (encoded.size) {
        33 -> decompress(encoded)
        65 -> {
            require(encoded[0] == 0x04.toByte()) { "unknown uncompressed point prefix" }
            Point(
                BigInteger(1, encoded.copyOfRange(1, 33)),
                BigInteger(1, encoded.copyOfRange(33, 65)),
            )
        }
        else -> throw IllegalArgumentException("a public key is 33 or 65 bytes, got ${encoded.size}")
    }

    /** Big-endian fixed-width encoding, zero-padded on the left. */
    fun toFixedLength(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val magnitude = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        require(magnitude.size <= length) { "$value does not fit in $length bytes" }
        val out = ByteArray(length)
        magnitude.copyInto(out, length - magnitude.size)
        return out
    }
}
