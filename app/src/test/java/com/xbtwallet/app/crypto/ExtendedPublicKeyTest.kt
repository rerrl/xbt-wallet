package com.xbtwallet.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BIP-32 extended public key + BIP-84 derivation tests.
 *
 * The account zpub and the derived addresses are the official BIP-84 test
 * vectors: the account key at m/84'/0'/0' and the first receiving/change
 * addresses under it. They are the acceptance criterion for this feature, so
 * they are asserted literally rather than through a helper.
 */
class ExtendedPublicKeyTest {

    /** BIP-84 test vector: account-level key for m/84'/0'/0'. */
    private val accountZpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    private fun addressAt(key: ExtendedPublicKey, chain: Long, index: Long): String =
        SegwitAddress.encodeP2wpkh(
            hrp = "bc",
            hash160 = Hashes.hash160(key.derive(listOf(chain, index)).publicKey),
        )

    @Test
    fun `parses the BIP-84 account zpub`() {
        val key = ExtendedPublicKey.parse(accountZpub)
        assertEquals(XpubVersion.ZPUB, key.version)
        assertEquals(3, key.depth)
        // m/84'/0'/0' -> the last element is hardened index 0.
        assertEquals(0x80000000L, key.childNumber)
        assertEquals(32, key.chainCode.size)
        assertEquals(33, key.publicKey.size)
        assertTrue(
            "expected a compressed-point prefix, got ${key.publicKey[0]}",
            key.publicKey[0] == 0x02.toByte() || key.publicKey[0] == 0x03.toByte(),
        )
    }

    @Test
    fun `BIP-84 vector - first receiving address is 0-0`() {
        val key = ExtendedPublicKey.parse(accountZpub)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", addressAt(key, 0, 0))
    }

    @Test
    fun `BIP-84 vector - the next receiving addresses follow`() {
        val key = ExtendedPublicKey.parse(accountZpub)
        assertEquals("bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g", addressAt(key, 0, 1))
        assertEquals("bc1qp59yckz4ae5c4efgw2s5wfyvrz0ala7rgvuz8z", addressAt(key, 0, 2))
    }

    @Test
    fun `BIP-84 vector - first change address is 1-0`() {
        val key = ExtendedPublicKey.parse(accountZpub)
        assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el", addressAt(key, 1, 0))
    }

    @Test
    fun `xpub ypub and zpub versions encode the same key material`() {
        val zpub = ExtendedPublicKey.parse(accountZpub)
        val xpub = ExtendedPublicKey.parse(zpub.reencode(XpubVersion.XPUB))
        val ypub = ExtendedPublicKey.parse(zpub.reencode(XpubVersion.YPUB))

        assertEquals(XpubVersion.XPUB, xpub.version)
        assertEquals(XpubVersion.YPUB, ypub.version)
        // Same pubkey/chain code, so the same addresses: the wallet treats every
        // accepted version as a native-segwit account key.
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", addressAt(xpub, 0, 0))
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", addressAt(ypub, 0, 0))
    }

    @Test
    fun `derivation is deterministic and does not mutate the parent`() {
        val key = ExtendedPublicKey.parse(accountZpub)
        val first = key.derive(listOf(0L, 0L))
        val second = key.derive(listOf(0L, 0L))
        assertArrayEquals(first.publicKey, second.publicKey)
        assertArrayEquals(first.chainCode, second.chainCode)
        assertEquals(3, key.depth)
    }

    @Test
    fun `derived children carry depth and parent fingerprint`() {
        val key = ExtendedPublicKey.parse(accountZpub)
        val child = key.derive(listOf(0L, 5L))
        assertEquals(5, child.depth)
        assertEquals(5L, child.childNumber)
        assertEquals(key.derive(listOf(0L)).fingerprint(), child.parentFingerprint)
    }

    @Test
    fun `hardened derivation from a public key is refused`() {
        val key = ExtendedPublicKey.parse(accountZpub)
        assertThrows(IllegalArgumentException::class.java) { key.deriveChild(0x80000000L) }
    }

    @Test
    fun `a corrupted checksum is rejected`() {
        val corrupted = accountZpub.dropLast(1) + if (accountZpub.last() == 'x') 'y' else 'x'
        val error = assertThrows(XpubParseException::class.java) { ExtendedPublicKey.parse(corrupted) }
        assertEquals(XpubParseException.Reason.CHECKSUM, error.reason)
    }

    @Test
    fun `a testnet tpub version is rejected`() {
        val payload = ExtendedPublicKey.parse(accountZpub).toPayload(XpubVersion.XPUB).copyOf()
        payload[0] = 0x04.toByte()
        payload[1] = 0x35.toByte()
        payload[2] = 0x87.toByte()
        payload[3] = 0xCF.toByte()
        val error = assertThrows(XpubParseException::class.java) {
            ExtendedPublicKey.parse(Base58Check.encode(payload))
        }
        assertEquals(XpubParseException.Reason.UNKNOWN_VERSION, error.reason)
    }

    @Test
    fun `a private extended key is rejected`() {
        val xprv =
            "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi"
        val error = assertThrows(XpubParseException::class.java) { ExtendedPublicKey.parse(xprv) }
        assertEquals(XpubParseException.Reason.UNKNOWN_VERSION, error.reason)
    }

    @Test
    fun `an empty string is rejected`() {
        assertEquals(
            XpubParseException.Reason.EMPTY,
            assertThrows(XpubParseException::class.java) { ExtendedPublicKey.parse("   ") }.reason,
        )
    }

    @Test
    fun `a payload of the wrong length is rejected`() {
        val short = Base58Check.encode(ByteArray(40))
        val error = assertThrows(XpubParseException::class.java) { ExtendedPublicKey.parse(short) }
        assertEquals(XpubParseException.Reason.LENGTH, error.reason)
    }

    @Test
    fun `xpub payload with a private-key key-data tag is rejected`() {
        // Same bytes as the account zpub but with 0x00 in the key-data prefix:
        // a well-formed payload that must still be refused as private.
        val key = ExtendedPublicKey.parse(accountZpub)
        val payload = key.toPayload(XpubVersion.XPUB).copyOf()
        payload[45] = 0x00
        val error = assertThrows(XpubParseException::class.java) {
            ExtendedPublicKey.parse(Base58Check.encode(payload))
        }
        assertEquals(XpubParseException.Reason.NOT_PUBLIC_KEY, error.reason)
    }

    @Test
    fun `fingerprint is the first four bytes of hash160 of the compressed key`() {
        val key = ExtendedPublicKey.parse(accountZpub)
        val expected = Hashes.hash160(key.publicKey).copyOfRange(0, 4)
        val actual = byteArrayOf(
            (key.fingerprint() ushr 24).toByte(),
            (key.fingerprint() ushr 16).toByte(),
            (key.fingerprint() ushr 8).toByte(),
            key.fingerprint().toByte(),
        )
        assertArrayEquals(expected, actual)
    }
}
