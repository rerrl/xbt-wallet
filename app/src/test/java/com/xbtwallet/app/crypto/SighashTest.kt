package com.xbtwallet.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BIP-143 segwit v0 signature hashing, against the specification's own vector.
 *
 * The vector is the native-P2WPKH example from `bip-0143.mediawiki`: a two-input
 * transaction whose second input is a P2WPKH program. The preimage is pinned
 * verbatim, and both accepted hashtypes are checked against it — `0x01`, the
 * spec's own digest, and `0x21`, the unified variant this wallet signs with,
 * which differs only in the last four bytes of the preimage.
 *
 * The refusals are as important as the vector: a non-P2WPKH witness utxo, an
 * out-of-range input index, and every hashtype outside the SIGHASH_ALL class.
 */
class SighashTest {

    /** BIP-143's unsigned two-input transaction, verbatim. */
    private val unsignedTx =
        "0100000002fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f0000000000eeffffff" +
            "ef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a0100000000ffffffff" +
            "02202cb206000000001976a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac" +
            "9093510d000000001976a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac" +
            "11000000"

    /** Input 1's witness utxo: 6 BTC, P2WPKH. */
    private val witnessUtxo = WitnessUtxo(
        satoshis = 600_000_000L,
        scriptPubKey = fromHex("00141d0f172a0ecb48aee1be1f2687d2963ae33f71a1"),
    )

    /** BIP-143's published preimage for input 1 with nHashType 0x01. */
    private val preimage01 =
        "0100000096b827c8483d4e9b96712b6713a7b68d6e8003a781feba36c31143470b4efd37" +
            "52b0a642eea2fb7ae638c36f6252b6750293dbe574a806984b8e4d8548339a3b" +
            "ef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a01000000" +
            "1976a9141d0f172a0ecb48aee1be1f2687d2963ae33f71a188ac" +
            "0046c32300000000" +
            "ffffffff" +
            "863ef3e1a92afbfdb97f31ad0fc7683ee943e9abcf2501590ff8f6551f47e5e5" +
            "11000000" +
            "01000000"

    private val digest01 = "c37af31116d1b27caf68aae9e3ac82f1477929014d5b917657d0eb49478cb670"

    /** The same vector with only the nHashType field swapped to the unified 0x21. */
    private val digest21 = "847b264c435c77ae161560b48d50030bfba3ce80aecefdd85abc071c3d199673"

    private val tx get() = decodeTransaction(fromHex(unsignedTx))

    @Test
    fun `BIP-143 vector - the preimage is reproduced verbatim`() {
        assertEquals(preimage01, toHex(Sighash.p2wpkhPreimage(tx, 1, witnessUtxo, Sighash.SIGHASH_ALL)))
    }

    @Test
    fun `BIP-143 vector - the SIGHASH_ALL digest`() {
        assertEquals(digest01, toHex(Sighash.p2wpkhDigest(tx, 1, witnessUtxo, Sighash.SIGHASH_ALL)))
    }

    @Test
    fun `the unified 0x21 digest is the same preimage with the hashtype swapped`() {
        val unified = Sighash.p2wpkhPreimage(tx, 1, witnessUtxo)
        val plain = Sighash.p2wpkhPreimage(tx, 1, witnessUtxo, Sighash.SIGHASH_ALL)

        // Identical up to the four nHashType bytes...
        assertEquals(toHex(plain.dropLast(4).toByteArray()), toHex(unified.dropLast(4).toByteArray()))
        assertEquals("21000000", toHex(unified.takeLast(4).toByteArray()))
        // ... and the published digest.
        assertEquals(digest21, toHex(Sighash.p2wpkhDigest(tx, 1, witnessUtxo)))
        assertEquals(digest21, toHex(Sighash.p2wpkhDigest(tx, 1, witnessUtxo, SIGHASH_UNIFIED)))
    }

    @Test
    fun `the default hashtype is the unified one`() {
        assertEquals(
            toHex(Sighash.p2wpkhDigest(tx, 1, witnessUtxo, SIGHASH_UNIFIED)),
            toHex(Sighash.p2wpkhDigest(tx, 1, witnessUtxo)),
        )
    }

    @Test
    fun `a non-P2WPKH witness utxo is refused`() {
        val p2pkh = WitnessUtxo(600_000_000L, fromHex("76a9141d0f172a0ecb48aee1be1f2687d2963ae33f71a188ac"))
        assertThrows(IllegalArgumentException::class.java) {
            Sighash.p2wpkhPreimage(tx, 1, p2pkh)
        }
        // A 33-byte program (P2WSH) and a P2PK script are refused too.
        assertThrows(IllegalArgumentException::class.java) {
            Sighash.p2wpkhPreimage(tx, 1, WitnessUtxo(1L, fromHex("0020" + "11".repeat(32))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Sighash.p2wpkhPreimage(
                tx,
                0,
                WitnessUtxo(1L, fromHex("2103c9f4836b9a4f77fc0d81f7bcb01b7f1b35916864b9476c241ce9fc198bd25432ac")),
            )
        }
    }

    @Test
    fun `an out-of-range input index is refused`() {
        assertThrows(IllegalArgumentException::class.java) { Sighash.p2wpkhPreimage(tx, -1, witnessUtxo) }
        assertThrows(IllegalArgumentException::class.java) { Sighash.p2wpkhPreimage(tx, 2, witnessUtxo) }
    }

    @Test
    fun `hashtypes outside the SIGHASH_ALL class are refused`() {
        listOf(0x00L, 0x02L, 0x03L, 0x81L, 0x82L, 0x83L).forEach { hashType ->
            assertThrows(
                "hashtype 0x${hashType.toString(16)} must be refused",
                IllegalArgumentException::class.java,
            ) { Sighash.p2wpkhPreimage(tx, 1, witnessUtxo, hashType) }
        }
    }

    @Test
    fun `the preimage is the length BIP-143 specifies`() {
        // 4 + 32 + 32 + 36 + 26 + 8 + 4 + 32 + 4 + 4
        assertEquals(182, Sighash.p2wpkhPreimage(tx, 1, witnessUtxo).size)
        assertTrue(Sighash.p2wpkhDigest(tx, 1, witnessUtxo).size == 32)
    }

    private fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
