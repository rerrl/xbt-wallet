package com.xbtwallet.app.chain

import com.xbtwallet.app.crypto.Hashes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Scripthash tests.
 *
 * The vector is real: [BIP84_FIRST_RECEIVE_ADDRESS] is BIP-84's first receiving
 * address (m/84'/0'/0'/0/0, the same account key `ExtendedPublicKeyTest` uses),
 * its witness program and scriptPubKey are decoded from the address itself, and
 * the scripthash is `sha256(scriptPubKey)` reversed. That makes it an
 * independent check on the reversal step — the one Electrum convention that is
 * easy to get backwards and impossible to notice when it is (every address
 * would simply look unused).
 */
class ScripthashTest {

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `a p2wpkh script is OP_0 then a 20-byte push`() {
        val script = Scripthash.p2wpkhScript(hexToBytes(BIP84_PROGRAM))
        assertEquals("0014$BIP84_PROGRAM", Scripthash.toHex(script))
    }

    @Test
    fun `the BIP-84 first receiving address hashes to the electrum scripthash vector`() {
        assertEquals(BIP84_SCRIPTHASH, Scripthash.ofP2wpkh(hexToBytes(BIP84_PROGRAM)))
    }

    @Test
    fun `the scripthash is sha256 of the script with the bytes reversed`() {
        val script = hexToBytes("0014" + "ab".repeat(20))
        val sha = Hashes.sha256(script)
        val reversed = Scripthash.toHex(ByteArray(sha.size) { sha[sha.size - 1 - it] })

        assertEquals(reversed, Scripthash.of(script))
        // The reversal is not cosmetic: read forwards, the string is different,
        // and a server would answer "unused" for every address in the wallet.
        assertNotEquals(Scripthash.toHex(sha), Scripthash.of(script))
    }

    @Test
    fun `hex is lowercase with two digits per byte`() {
        assertEquals("00ff10", Scripthash.toHex(byteArrayOf(0x00, 0xFF.toByte(), 0x10)))
    }

    @Test
    fun `a program that is not 20 bytes is refused`() {
        assertThrows(IllegalArgumentException::class.java) { Scripthash.p2wpkhScript(ByteArray(19)) }
    }

    private companion object {
        /** BIP-84's first receiving address, for the record. */
        const val BIP84_FIRST_RECEIVE_ADDRESS = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"

        /** Its HASH160 witness program. */
        const val BIP84_PROGRAM = "c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2"

        /** sha256("0014" ++ program), reversed. */
        const val BIP84_SCRIPTHASH = "6e4f16236139f15046b38f399a683fb2aa8edf5fd128b3e5db017fb0ac74078a"
    }
}
