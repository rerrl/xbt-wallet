package com.xbtwallet.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hash unit tests.
 *
 * SHA-256 and HMAC-SHA512 come from the JDK; RIPEMD-160 is implemented here
 * from the public specification (Dobbertin/Bosselaers/Preneel, "RIPEMD-160: A
 * Strengthened Version of RIPEMD") because Android ships no RIPEMD provider and
 * pulling in Bouncy Castle for one hash would grow the wallet's dependency tree.
 * The vectors below are the published RIPEMD-160 / SHA-256 / RFC 4231 examples.
 */
class HashesTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `ripemd160 matches the published vectors`() {
        assertEquals("9c1185a5c5e9fc54612808977ee8f548b2258d31", hex(Hashes.ripemd160("".toByteArray())))
        assertEquals("0bdc9d2d256b3ee9daae347be6f4dc835a467ffe", hex(Hashes.ripemd160("a".toByteArray())))
        assertEquals("8eb208f7e05d987a9b044a8e98c6b087f15a0bfc", hex(Hashes.ripemd160("abc".toByteArray())))
        assertEquals(
            "5d0689ef49d2fae572b881b123a85ffa21595f36",
            hex(Hashes.ripemd160("message digest".toByteArray())),
        )
        assertEquals(
            "f71c27109c692c1b56bbdceb5b9d2865b3708dbc",
            hex(Hashes.ripemd160("abcdefghijklmnopqrstuvwxyz".toByteArray())),
        )
    }

    @Test
    fun `ripemd160 matches the two-block published vectors`() {
        assertEquals(
            "12a053384a9c0c88e405a06c27dcf49ada62eb2b",
            hex(Hashes.ripemd160("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".toByteArray())),
        )
        assertEquals(
            "b0e20b6e3116640286ed3a87a5713079b21f5189",
            hex(
                Hashes.ripemd160(
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toByteArray(),
                ),
            ),
        )
        assertEquals(
            "9b752e45573d4b39f4dbd3323cab82bf63326bfb",
            hex(
                Hashes.ripemd160(
                    "12345678901234567890123456789012345678901234567890123456789012345678901234567890".toByteArray(),
                ),
            ),
        )
    }

    @Test
    fun `ripemd160 handles the 55-56-64 byte padding boundaries`() {
        // 64 and 65 bytes force an extra padding-only block; 55/56 sit on the
        // length-field boundary.
        assertEquals("9dfb7d374ad924f3f88de96291c33e9abed53e32", hex(Hashes.ripemd160("a".repeat(64).toByteArray())))
        assertEquals("99724bb11811e7166af38f671b6a082d8ab4960b", hex(Hashes.ripemd160("a".repeat(65).toByteArray())))
    }

    @Test
    fun `sha256 matches the published vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(Hashes.sha256("abc".toByteArray())),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex(Hashes.sha256("".toByteArray())),
        )
    }

    @Test
    fun `hmacSha512 matches RFC 4231 test case 1`() {
        val key = ByteArray(20) { 0x0b }
        val data = "Hi There".toByteArray()
        assertEquals(
            "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cde" +
                "daa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854",
            hex(Hashes.hmacSha512(key, data)),
        )
    }
}
