package com.xbtwallet.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
}
