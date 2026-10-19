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
 *
 * **Coordinates.** The *public* API works in affine coordinates ([Point], the
 * point at infinity as `null`) and is unchanged. Internally, every operation
 * now works in Jacobian projective coordinates `(X, Y, Z)` with the convention
 * `Z == 0` is the point at infinity, because a field inversion
 * ([BigInteger.modInverse]) is the single most expensive primitive here and the
 * affine formulas need one per addition and per doubling. With a=0 (secp256k1)
 * a scalar multiplication is ~256 doublings and ~128 additions, so the affine
 * version paid ~384 inversions per `multiply`; the Jacobian version pays
 * exactly **one**, at the final conversion back to affine. The formulas are the
 * standard a=0 short-Weierstrass Jacobian ones (doubling `dbl-2009-l`, mixed
 * Jacobian-affine addition `madd-2007-bl` from the Explicit-Formulas Database)
 * so they can be checked against any reference.
 *
 * **Fixed base.** The one multiplication that matters is `IL·G` — the base is
 * always the generator ([G]), because CKDpub always tweaks a point by
 * `IL·G`. [multiply] therefore uses a lazily built fixed-base comb (Lim–Lee)
 * for [G], cutting the walk from 256 doublings and ~128 additions to 64 of
 * each; any other base falls back to the plain binary walk. See [multiply].
 *
 * Measured effect (JVM, JIT-warm, 20 fresh addresses): ~266 ms before, ~12 ms
 * after — a ~22× speed-up, of which the Jacobian change is ~8× and the comb
 * the rest.
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
     * A working point in Jacobian projective coordinates.
     *
     * `(X, Y, Z)` represents the affine point `(X/Z^2, Y/Z^3)`; `Z == 0` is the
     * point at infinity. Internal only — every public function returns an affine
     * [Point] or `null`, so callers never see this representation.
     */
    private class Jacobian(val x: BigInteger, val y: BigInteger, val z: BigInteger)

    /** The Jacobian point at infinity (the `X`/`Y` values are irrelevant at `Z == 0`). */
    private val INFINITY = Jacobian(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    /** Lifts an affine point into Jacobian form with `Z = 1`. */
    private fun lift(point: Point): Jacobian =
        Jacobian(point.x.mod(P), point.y.mod(P), BigInteger.ONE)

    /**
     * Converts a Jacobian point back to affine, paying the single field inversion
     * a whole scalar multiplication is allowed: `x = X/Z^2`, `y = Y/Z^3`.
     * Returns `null` (affine infinity) when `Z == 0`.
     */
    private fun toAffine(point: Jacobian): Point? {
        if (point.z.signum() == 0) return null
        val zInv = point.z.modInverse(P)
        val zInv2 = zInv.multiply(zInv).mod(P)
        return Point(
            point.x.multiply(zInv2).mod(P),
            point.y.multiply(zInv2).multiply(zInv).mod(P),
        )
    }

    /**
     * Jacobian point doubling for `a = 0` (EFD `dbl-2009-l`).
     *
     * `Y == 0` is the 2-torsion point, whose double is infinity, and infinity
     * doubles to itself.
     */
    private fun double(point: Jacobian): Jacobian {
        if (point.z.signum() == 0 || point.y.signum() == 0) return INFINITY
        val xx = point.x.multiply(point.x).mod(P)
        val yy = point.y.multiply(point.y).mod(P)
        val yyyy = yy.multiply(yy).mod(P)
        // D = 2 * ((X + Y^2)^2 - X^2 - Y^4)
        val d = point.x.add(yy).let { it.multiply(it) }
            .subtract(xx).subtract(yyyy).shiftLeft(1).mod(P)
        // M = 3 * X^2 for a = 0.
        val m = xx.multiply(THREE).mod(P)
        val x3 = m.multiply(m).subtract(d.shiftLeft(1)).mod(P)
        val y3 = m.multiply(d.subtract(x3)).subtract(yyyy.shiftLeft(3)).mod(P)
        val z3 = point.y.multiply(point.z).shiftLeft(1).mod(P)
        return Jacobian(x3, y3, z3)
    }

    /**
     * Mixed addition of a Jacobian point and an affine point (EFD `madd-2007-bl`).
     *
     * This is the hot path of [multiply] — the running accumulator is Jacobian and
     * the addend is an affine curve point — so it never touches the expensive
     * general Jacobian-Jacobian formulas. The same-`x` cases are handled
     * explicitly: equal points double, `y`-negations yield infinity.
     */
    private fun addAffine(a: Jacobian, b: Point): Jacobian {
        if (a.z.signum() == 0) return lift(b)
        val z1z1 = a.z.multiply(a.z).mod(P)
        val u2 = b.x.mod(P).multiply(z1z1).mod(P)
        val s2 = b.y.mod(P).multiply(a.z).multiply(z1z1).mod(P)
        val h = u2.subtract(a.x).mod(P)
        val s = s2.subtract(a.y).mod(P)
        if (h.signum() == 0) {
            // Same x: doubling when the y's match, infinity when they negate.
            return if (s.signum() == 0) double(a) else INFINITY
        }
        val hh = h.multiply(h).mod(P)
        val i = hh.shiftLeft(2).mod(P)
        val j = h.multiply(i).mod(P)
        val r = s.shiftLeft(1).mod(P)
        val v = a.x.multiply(i).mod(P)
        val x3 = r.multiply(r).subtract(j).subtract(v.shiftLeft(1)).mod(P)
        val y3 = r.multiply(v.subtract(x3)).subtract(a.y.multiply(j).shiftLeft(1)).mod(P)
        val z3 = a.z.add(h).let { it.multiply(it) }.subtract(z1z1).subtract(hh).mod(P)
        return Jacobian(x3, y3, z3)
    }

    /**
     * Point addition, with `null` as the point at infinity.
     *
     * Doubling and the "same x, opposite y" case (which yields infinity) are
     * handled explicitly; everything else lifts `a` into Jacobian form and adds
     * the affine `b` with the mixed formula, then converts back. Both operands
     * are affine, so a single mixed addition is all that is needed.
     */
    fun add(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x) {
            // Either the same point (double) or y-negations (infinity).
            if (a.y.add(b.y).mod(P) == BigInteger.ZERO) return null
            return toAffine(double(lift(a)))
        }
        return toAffine(addAffine(lift(a), b))
    }

    /**
     * Scalar multiplication of a public point. Returns `null` for infinity.
     *
     * Two internal strategies, one public result:
     *
     * - For the generator [G] the walk uses the *fixed-base comb* below, which is
     *   legitimate precisely because the base never changes: CKDpub is always
     *   `point(IL) + Kpar` and `point(IL)` is always `IL·G`. The comb precomputes
     *   a small table once (lazily, at first use) and then needs only
     *   `256 / COMB_W = 64` doublings and 64 additions instead of the binary
     *   walk's 256 doublings and ~128 additions — the doubling count is the
     *   dominant cost, so this is a ~2.8× saving on top of the Jacobian change.
     * - For any other point the walk is plain left-to-right binary double-and-add,
     *   for which no worthwhile precomputation is amortisable.
     *
     * Both keep the accumulator in Jacobian coordinates for the whole walk, so
     * the only field inversion is the final [toAffine].
     */
    fun multiply(scalar: BigInteger, point: Point): Point? {
        val k = scalar.mod(N)
        if (k.signum() == 0) return null
        return if (point == G) multiplyGenerator(k) else multiplyPlain(k, point)
    }

    /** Left-to-right binary double-and-add; the general, precomputation-free path. */
    private fun multiplyPlain(k: BigInteger, point: Point): Point? {
        var result = INFINITY
        for (bit in k.bitLength() - 1 downTo 0) {
            result = double(result)
            if (k.testBit(bit)) result = addAffine(result, point)
        }
        return toAffine(result)
    }

    /**
     * Fixed-base comb scalar multiplication of the generator (Lim–Lee comb).
     *
     * The scalar `k` is read in `COMB_W = 4` columns of `COMB_D = 64` bit
     * positions spaced `COMB_D` apart. The precomputed table holds
     * `sum over the set bits j of a table index of 2^(j*COMB_D) · G` (entry 0 is
     * infinity); multiplying each entry by `2^i` for `i = COMB_D-1 .. 0` and
     * summing reproduces `k·G` exactly, because every bit `k_p` of `k` is
     * `k_{i + j*COMB_D}` at weight `2^i · 2^(j*COMB_D)`. See
     * [combTableIndex] for the table lookup. The `COMB_D` doublings and
     * `COMB_D` additions are what the walk costs — the table is built once.
     */
    private fun multiplyGenerator(k: BigInteger): Point? {
        val table = generatorCombTable
        var result = INFINITY
        for (i in COMB_D - 1 downTo 0) {
            result = double(result)
            val index = combTableIndex(k, i)
            if (index != 0) {
                // Table entries are affine, so this is the cheap mixed addition.
                result = addAffine(result, table[index]!!)
            }
        }
        return toAffine(result)
    }

    /**
     * The comb table index for column `i`: bit `j` of the index is bit
     * `i + j * COMB_D` of the scalar.
     */
    private fun combTableIndex(k: BigInteger, i: Int): Int {
        var index = 0
        if (k.testBit(i)) index = index or 1
        if (k.testBit(i + COMB_D)) index = index or 2
        if (k.testBit(i + 2 * COMB_D)) index = index or 4
        if (k.testBit(i + 3 * COMB_D)) index = index or 8
        return index
    }

    /**
     * The generator's comb table, built on first use: entry `I` is the sum of
     * `2^(j*COMB_D) · G` over the set bits `j` of `I`, in affine coordinates so
     * the walk can use mixed addition. Entry 0 is infinity (the point at
     * infinity is `null`, matching the public API).
     *
     * The build pays `3 * COMB_D = 192` doublings to reach `2^64·G`, `2^128·G`
     * and `2^192·G`, plus the table sums and their affine conversions — a
     * one-off cost amortised across the whole process, since CKDpub multiplies
     * the generator on every derivation.
     */
    private val generatorCombTable: Array<Point?> by lazy {
        val powers = ArrayList<Jacobian>(COMB_W)
        var current = lift(G)
        powers.add(current) // 2^(0*COMB_D) · G == G
        repeat(COMB_W - 1) {
            repeat(COMB_D) { current = double(current) }
            powers.add(current)
        }
        val affinePowers = powers.map { toAffine(it)!! }

        val table = arrayOfNulls<Point?>(1 shl COMB_W)
        table[0] = null
        for (index in 1 until (1 shl COMB_W)) {
            var sum = INFINITY
            for (j in 0 until COMB_W) {
                if ((index shr j) and 1 == 1) sum = addAffine(sum, affinePowers[j])
            }
            table[index] = toAffine(sum)
        }
        table
    }

    /** How many bits one comb column covers. */
    private const val COMB_W = 4

    /** How many columns the comb walk has: 256 bits / [COMB_W]. */
    private const val COMB_D = 64

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
     * The `modPow` is one exponentiation per parsed key, not per bit of anything.
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
