package com.xbtwallet.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Base58 / Base58Check unit tests.
 *
 * Vectors are the well-known Bitcoin ones from the public Base58Check
 * specification (WIkipedia "Base58"/BIP drafts and the original Satoshi
 * client): the genesis coinbase address and a short byte-string example.
 */
class Base58Test {

    @Test
    fun `encode of the canonical byte string matches the spec example`() {
        // 00 00 28 7f b4 cd -> "11233QC4"
        val bytes = byteArrayOf(0x00, 0x00, 0x28, 0x7f, 0xb4.toByte(), 0xcd.toByte())
        assertEquals("11233QC4", Base58.encode(bytes))
    }

    @Test
    fun `decode is the inverse of encode for round-trippable data`() {
        val bytes = byteArrayOf(0x00, 0x00, 0x28, 0x7f, 0xb4.toByte(), 0xcd.toByte())
        assertArrayEquals(bytes, Base58.decode(Base58.encode(bytes)))
    }

    @Test
    fun `leading zero bytes become leading ones`() {
        assertEquals("11", Base58.encode(byteArrayOf(0, 0)))
        assertArrayEquals(byteArrayOf(0, 0), Base58.decode("11"))
    }

    @Test
    fun `decode rejects characters outside the alphabet`() {
        assertThrows(Base58Exception::class.java) { Base58.decode("0OIl") }
    }

    @Test
    fun `Base58Check decodes the genesis coinbase address payload`() {
        // 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa = version 0x00 + hash160 of the
        // genesis block public key.
        val payload = Base58Check.decode("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")
        assertEquals(21, payload.size)
        assertEquals(0x00, payload[0].toInt())
        assertEquals(
            "62e907b15cbf27d5425399ebf6f0fb50ebb88f18",
            payload.copyOfRange(1, 21).joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `Base58Check round-trips a 78-byte extended key payload`() {
        val payload = ByteArray(78) { (it * 7 + 1).toByte() }
        val encoded = Base58Check.encode(payload)
        assertArrayEquals(payload, Base58Check.decode(encoded))
    }

    @Test
    fun `Base58Check rejects a corrupted checksum`() {
        val encoded = Base58Check.encode("hello xbt-wallet".toByteArray())
        val corrupted = encoded.dropLast(1) + if (encoded.last() == 'A') 'B' else 'A'
        assertThrows(Base58CheckException::class.java) { Base58Check.decode(corrupted) }
    }
}
