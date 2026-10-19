package com.xbtwallet.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * secp256k1 public-key arithmetic tests.
 *
 * Only public-key operations exist in this file's subject: point addition,
 * scalar multiplication of a public point, and compressed point
 * (de)serialisation. There is no private scalar anywhere in the wallet
 * (PLAN.md §2.1), and this test pins that down with the standard published
 * constants for the generator point.
 */
class Secp256k1Test {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String) =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `generator point serialises to its published compressed form`() {
        assertEquals(
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            hex(Secp256k1.compress(Secp256k1.G)),
        )
    }

    @Test
    fun `decompress is the inverse of compress`() {
        val compressed = unhex("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
        val point = Secp256k1.decompress(compressed)
        assertEquals(Secp256k1.G.x, point.x)
        assertEquals(Secp256k1.G.y, point.y)
        assertArrayEquals(compressed, Secp256k1.compress(point))
    }

    @Test
    fun `odd-y compressed points decompress to the right y`() {
        val point = Secp256k1.decompress(
            unhex("03f028892bad7ed57d2fb57bf33081d5cfcf6f9ed3d3d7f159c2e2fff579dc341a"),
        )
        assertEquals(BigInteger.ONE, point.y.mod(BigInteger.TWO))
        assertArrayEquals(
            unhex("03f028892bad7ed57d2fb57bf33081d5cfcf6f9ed3d3d7f159c2e2fff579dc341a"),
            Secp256k1.compress(point),
        )
    }

    @Test
    fun `point doubling matches the published 2G`() {
        val doubled = Secp256k1.add(Secp256k1.G, Secp256k1.G)
        assertEquals(
            "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5",
            hex(Secp256k1.compress(doubled!!)),
        )
    }

    @Test
    fun `scalar multiplication by one is the identity`() {
        assertEquals(Secp256k1.G, Secp256k1.multiply(BigInteger.ONE, Secp256k1.G))
    }

    @Test
    fun `scalar multiplication by the curve order is the point at infinity`() {
        assertNull(Secp256k1.multiply(Secp256k1.N, Secp256k1.G))
    }

    @Test
    fun `k times G equals G added to itself k-1 times`() {
        val twoG = Secp256k1.add(Secp256k1.G, Secp256k1.G)!!
        assertEquals(twoG, Secp256k1.multiply(BigInteger.TWO, Secp256k1.G))
    }

    @Test
    fun `adding a point to its negation yields infinity`() {
        val negated = Secp256k1.Point(Secp256k1.G.x, Secp256k1.P.subtract(Secp256k1.G.y))
        assertNull(Secp256k1.add(Secp256k1.G, negated))
    }

    @Test
    fun `decompression rejects an x coordinate that is not on the curve`() {
        // x = 0: y^2 = 7 has no solution mod p (7 is a quadratic non-residue).
        assertThrows(IllegalArgumentException::class.java) {
            Secp256k1.decompress(
                unhex("020000000000000000000000000000000000000000000000000000000000000000"),
            )
        }
    }

    @Test
    fun `decompression rejects an unknown prefix byte`() {
        assertThrows(IllegalArgumentException::class.java) {
            Secp256k1.decompress(
                unhex("050000000000000000000000000000000000000000000000000000000000000001"),
            )
        }
    }

    // --- Jacobian rewrite: the same public behaviour, pinned harder ----------
    //
    // The internals moved from affine to Jacobian coordinates (see the file
    // KDoc). These tests pin the *results* of the rewritten code against
    // published constants and against invariants (on-curve, group laws), so the
    // coordinate change cannot have altered what the public API returns.

    /** The affine curve equation `y^2 = x^3 + 7` over the field. */
    private fun isOnCurve(point: Secp256k1.Point): Boolean =
        point.y.multiply(point.y).mod(Secp256k1.P) ==
            point.x.modPow(BigInteger.valueOf(3), Secp256k1.P)
                .add(BigInteger.valueOf(7)).mod(Secp256k1.P)

    @Test
    fun `scalar multiplication by N-1 is the negation of the generator`() {
        // (N-1)·G == -G: same x, y negated mod p. G's y is even, so -G carries
        // the odd-y 0x03 prefix.
        val negated = Secp256k1.multiply(Secp256k1.N.subtract(BigInteger.ONE), Secp256k1.G)!!
        assertEquals(Secp256k1.G.x, negated.x)
        assertEquals(Secp256k1.P.subtract(Secp256k1.G.y), negated.y)
        assertEquals(
            "0379be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            hex(Secp256k1.compress(negated)),
        )
    }

    @Test
    fun `scalar multiplication by three matches the published 3G`() {
        assertEquals(
            "02f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
            hex(Secp256k1.compress(Secp256k1.multiply(BigInteger.valueOf(3), Secp256k1.G)!!)),
        )
    }

    @Test
    fun `multiply results lie on the curve for high hamming weight scalars`() {
        // 2^255 - 1 is 255 set bits: the longest possible double-and-add walk
        // under N, and every one of its additions is exercised. A wrong point
        // would not satisfy y^2 = x^3 + 7.
        val allOnes = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE)
        val alternating = BigInteger("5555555555555555555555555555555555555555555555555555555555555555", 16)
        for (scalar in listOf(allOnes, alternating, Secp256k1.N.subtract(BigInteger.ONE))) {
            val product = Secp256k1.multiply(scalar, Secp256k1.G)!!
            assertTrue("scalar $scalar produced an off-curve point", isOnCurve(product))
        }
    }

    @Test
    fun `multiplication is additive in the scalar`() {
        // (a + b)·G == a·G + b·G. Different scalars walk different double/add
        // sequences, so this catches bit-handling bugs the single-vector checks
        // would miss.
        val a = BigInteger.valueOf(12_345)
        val b = BigInteger.valueOf(67_890)
        val sum = Secp256k1.add(
            Secp256k1.multiply(a, Secp256k1.G),
            Secp256k1.multiply(b, Secp256k1.G),
        )
        assertEquals(Secp256k1.multiply(a.add(b), Secp256k1.G), sum)
    }

    @Test
    fun `point addition commutes on real curve points`() {
        val p = Secp256k1.multiply(BigInteger.valueOf(5), Secp256k1.G)!!
        val q = Secp256k1.multiply(BigInteger.valueOf(7), Secp256k1.G)!!
        assertEquals(Secp256k1.add(p, q), Secp256k1.add(q, p))
        assertTrue(isOnCurve(Secp256k1.add(p, q)!!))
    }

    @Test
    fun `adding a point to itself equals an explicit doubling`() {
        val p = Secp256k1.multiply(BigInteger.valueOf(9), Secp256k1.G)!!
        assertEquals(Secp256k1.multiply(BigInteger.TWO, p), Secp256k1.add(p, p))
    }

    @Test
    fun `infinity is the identity of addition in both directions`() {
        val p = Secp256k1.multiply(BigInteger.valueOf(11), Secp256k1.G)!!
        assertEquals(p, Secp256k1.add(p, null))
        assertEquals(p, Secp256k1.add(null, p))
        assertNull(Secp256k1.add(null, null))
    }

    @Test
    fun `the generator comb path agrees with the general binary path`() {
        // multiply(k, G) takes the fixed-base comb; multiply(k, 2G) takes the
        // general binary walk. Since 2G is a real curve point, (2k)·G computed
        // one way must equal k·(2G) computed the other — an independent
        // cross-check of the comb's table and column indexing.
        val twoG = Secp256k1.add(Secp256k1.G, Secp256k1.G)!!
        for (k in listOf(
            BigInteger.ONE,
            BigInteger.valueOf(1_000_000_007),
            BigInteger("deadbeefcafebabefeedfaced00d1234567890fedcba9876543210abcdef", 16),
            Secp256k1.N.subtract(BigInteger.TWO),
        )) {
            val viaComb = Secp256k1.multiply(k.shiftLeft(1).mod(Secp256k1.N), Secp256k1.G)
            val viaBinary = Secp256k1.multiply(k, twoG)
            assertEquals("scalar $k disagrees between paths", viaComb, viaBinary)
        }
    }

    @Test
    fun `compress and decompress round-trip on derived points`() {
        for (scalar in listOf(BigInteger.valueOf(2), BigInteger.valueOf(42), Secp256k1.N.subtract(BigInteger.TWO))) {
            val point = Secp256k1.multiply(scalar, Secp256k1.G)!!
            val compressed = Secp256k1.compress(point)
            assertEquals(33, compressed.size)
            val roundTripped = Secp256k1.decompress(compressed)
            assertEquals(point, roundTripped)
            assertArrayEquals(compressed, Secp256k1.compress(roundTripped))
        }
    }
}
