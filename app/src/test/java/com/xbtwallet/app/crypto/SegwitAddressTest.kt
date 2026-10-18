package com.xbtwallet.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Native-segwit address building tests.
 *
 * The P2WPKH vector is the BIP-173 worked example (public key -> hash160 ->
 * address), and the BIP-84 addresses are asserted end-to-end in
 * [ExtendedPublicKeyTest].
 */
class SegwitAddressTest {

    private fun unhex(text: String) =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `BIP-173 worked example - P2WPKH address for the generator point`() {
        val pubkey = unhex("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            SegwitAddress.encodeP2wpkh("bc", Hashes.hash160(pubkey)),
        )
    }

    @Test
    fun `hash160 of the BIP-173 example pubkey is the published program`() {
        val pubkey = unhex("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
        assertArrayEquals(unhex("751e76e8199196d454941c45d1b3a323f1433bd6"), Hashes.hash160(pubkey))
    }

    @Test
    fun `testnet hrp produces a tb1 address`() {
        val program = unhex("751e76e8199196d454941c45d1b3a323f1433bd6")
        assertEquals("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx", SegwitAddress.encode("tb", 0, program))
    }

    @Test
    fun `segwit addresses round-trip through the bech32 decoder`() {
        val address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        val decoded = Bech32.decode(address)
        assertEquals("bc", decoded.hrp)
        // The first 5-bit group is the witness version, the rest is the program.
        assertEquals(0, decoded.data[0].toInt())
        val program = SegwitAddress.convertBits(decoded.data.copyOfRange(1, decoded.data.size), 5, 8, false)
        assertArrayEquals(unhex("751e76e8199196d454941c45d1b3a323f1433bd6"), program)
    }

    @Test
    fun `version zero rejects a program that is not 20 or 32 bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            SegwitAddress.encode("bc", 0, ByteArray(21))
        }
    }

    @Test
    fun `the witness version must be in range`() {
        assertThrows(IllegalArgumentException::class.java) {
            SegwitAddress.encode("bc", 17, ByteArray(20))
        }
    }
}
