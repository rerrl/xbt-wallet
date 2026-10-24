package com.xbtwallet.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * BIP-32 private derivation, against the specification's own vectors.
 *
 * Test vectors 1 and 2 are reproduced here in full: for every chain the seed's
 * derived private scalar, chain code and public key are asserted, at the exact
 * depths the spec lists. Vector 1's master key is additionally pinned verbatim
 * in its own test.
 *
 * The BIP-84 cross-check ties the whole tree together: the standard test
 * mnemonic's account key at `m/84'/0'/0'`, re-encoded as a zpub, must be the
 * account zpub BIP-84 publishes.
 *
 * The `parsePath` tests pin the path grammar, and the "never serialised" tests
 * assert by reflection that nothing on this class can turn a private key into a
 * string.
 */
class ExtendedPrivateKeyTest {

    private class Node(
        val path: String,
        val privateKey: String,
        val chainCode: String,
        val publicKey: String,
        val depth: Int,
    )

    private val vector1 = listOf(
        Node(
            path = "m",
            privateKey = "e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35",
            chainCode = "873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508",
            publicKey = "0339a36013301597daef41fbe593a02cc513d0b55527ec2df1050e2e8ff49c85c2",
            depth = 0,
        ),
        Node(
            path = "m/0'",
            privateKey = "edb2e14f9ee77d26dd93b4ecede8d16ed408ce149b6cd80b0715a2d911a0afea",
            chainCode = "47fdacbd0f1097043b78c63c20c34ef4ed9a111d980047ad16282c7ae6236141",
            publicKey = "035a784662a4a20a65bf6aab9ae98a6c068a81c52e4b032c0fb5400c706cfccc56",
            depth = 1,
        ),
        Node(
            path = "m/0'/1",
            privateKey = "3c6cb8d0f6a264c91ea8b5030fadaa8e538b020f0a387421a12de9319dc93368",
            chainCode = "2a7857631386ba23dacac34180dd1983734e444fdbf774041578e9b6adb37c19",
            publicKey = "03501e454bf00751f24b1b489aa925215d66af2234e3891c3b21a52bedb3cd711c",
            depth = 2,
        ),
        Node(
            path = "m/0'/1/2'",
            privateKey = "cbce0d719ecf7431d88e6a89fa1483e02e35092af60c042b1df2ff59fa424dca",
            chainCode = "04466b9cc8e161e966409ca52986c584f07e9dc81f735db683c3ff6ec7b1503f",
            publicKey = "0357bfe1e341d01c69fe5654309956cbea516822fba8a601743a012a7896ee8dc2",
            depth = 3,
        ),
        Node(
            path = "m/0'/1/2'/2",
            privateKey = "0f479245fb19a38a1954c5c7c0ebab2f9bdfd96a17563ef28a6a4b1a2a764ef4",
            chainCode = "cfb71883f01676f587d023cc53a35bc7f88f724b1f8c2892ac1275ac822a3edd",
            publicKey = "02e8445082a72f29b75ca48748a914df60622a609cacfce8ed0e35804560741d29",
            depth = 4,
        ),
        Node(
            path = "m/0'/1/2'/2/1000000000",
            privateKey = "471b76e389e528d6de6d816857e012c5455051cad6660850e58372a6c3e6e7c8",
            chainCode = "c783e67b921d2beb8f6b389cc646d7263b4145701dadd2161548a8b078e65e9e",
            publicKey = "022a471424da5e657499d1ff51cb43c47481a03b1e77f951fe64cec9f5a48f7011",
            depth = 5,
        ),
    )

    private val vector2 = listOf(
        Node(
            path = "m",
            privateKey = "4b03d6fc340455b363f51020ad3ecca4f0850280cf436c70c727923f6db46c3e",
            chainCode = "60499f801b896d83179a4374aeb7822aaeaceaa0db1f85ee3e904c4defbd9689",
            publicKey = "03cbcaa9c98c877a26977d00825c956a238e8dddfbd322cce4f74b0b5bd6ace4a7",
            depth = 0,
        ),
        Node(
            path = "m/0",
            privateKey = "abe74a98f6c7eabee0428f53798f0ab8aa1bd37873999041703c742f15ac7e1e",
            chainCode = "f0909affaa7ee7abe5dd4e100598d4dc53cd709d5a5c2cac40e7412f232f7c9c",
            publicKey = "02fc9e5af0ac8d9b3cecfe2a888e2117ba3d089d8585886c9c826b6b22a98d12ea",
            depth = 1,
        ),
        Node(
            path = "m/0/2147483647'",
            privateKey = "877c779ad9687164e9c2f4f0f4ff0340814392330693ce95a58fe18fd52e6e93",
            chainCode = "be17a268474a6bb9c61e1d720cf6215e2a88c5406c4aee7b38547f585c9a37d9",
            publicKey = "03c01e7425647bdefa82b12d9bad5e3e6865bee0502694b94ca58b666abc0a5c3b",
            depth = 2,
        ),
        Node(
            path = "m/0/2147483647'/1",
            privateKey = "704addf544a06e5ee4bea37098463c23613da32020d604506da8c0518e1da4b7",
            chainCode = "f366f48f1ea9f2d1d3fe958c95ca84ea18e4c4ddb9366c336c927eb246fb38cb",
            publicKey = "03a7d1d856deb74c508e05031f9895dab54626251b3806e16b4bd12e781a7df5b9",
            depth = 3,
        ),
        Node(
            path = "m/0/2147483647'/1/2147483646'",
            privateKey = "f1c7c871a54a804afe328b4c83a1c33b8e5ff48f5087273f04efa83b247d6a2d",
            chainCode = "637807030d55d01f9a0cb3a7839515d796bd07706386a6eddf06cc29a65a0e29",
            publicKey = "02d2b36900396c9282fa14628566582f206a5dd0bcc8d5e892611806cafb0301f0",
            depth = 4,
        ),
        Node(
            path = "m/0/2147483647'/1/2147483646'/2",
            privateKey = "bb7d39bdb83ecf58f2fd82b6d918341cbef428661ef01ab97c28a4842125ac23",
            chainCode = "9452b549be8cea3ecb7a84bec10dcfd94afe4d129ebfd3b3cb58eedf394ed271",
            publicKey = "024d902e1a2fc7a8755ab5b694c575fce742c48d9ff192e63df5193e4c7afe1f9c",
            depth = 5,
        ),
    )

    @Test
    fun `BIP-32 test vector 1 - every chain`() {
        assertVector(seed = "000102030405060708090a0b0c0d0e0f", nodes = vector1)
    }

    @Test
    fun `BIP-32 test vector 2 - every chain`() {
        assertVector(
            seed = "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a2" +
                "9f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542",
            nodes = vector2,
        )
    }

    @Test
    fun `BIP-32 test vector 1 master key is pinned verbatim`() {
        val master = ExtendedPrivateKey.fromSeed(fromHex("000102030405060708090a0b0c0d0e0f"))
        assertArrayEquals(
            fromHex("873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508"),
            master.chainCode,
        )
        assertEquals(
            "0339a36013301597daef41fbe593a02cc513d0b55527ec2df1050e2e8ff49c85c2",
            toHex(master.toPublic().publicKey),
        )
        assertEquals(0, master.depth)
    }

    @Test
    fun `BIP-84 cross-check - the account key re-encodes as the published zpub`() {
        val words = List(11) { "abandon" } + "about"
        val seed = Bip39.mnemonicToSeed(words)
        val account = ExtendedPrivateKey.fromSeed(seed).derive(ExtendedPrivateKey.parsePath("m/84'/0'/0'"))

        assertEquals(
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
            account.toPublic().reencode(XpubVersion.ZPUB),
        )
        assertEquals(3, account.toPublic().depth)
        assertEquals(0x80000000L, account.toPublic().childNumber)
    }

    @Test
    fun `the private half's fingerprint is its public half's`() {
        val account = ExtendedPrivateKey.fromSeed(fromHex("000102030405060708090a0b0c0d0e0f"))
            .derive(ExtendedPrivateKey.parsePath("m/0'/1"))
        assertEquals(account.toPublic().fingerprint(), account.fingerprint())
        assertEquals(
            (account.fingerprint() ushr 24).toByte(),
            Hashes.hash160(account.toPublic().publicKey)[0],
        )
    }

    @Test
    fun `normal and hardened children agree with the public derivation`() {
        val parent = ExtendedPrivateKey.fromSeed(fromHex("000102030405060708090a0b0c0d0e0f"))
            .derive(ExtendedPrivateKey.parsePath("m/0'/1"))
        // A non-hardened child must equal the public CKDpub of the same index.
        val childPublic = parent.toPublic().deriveChild(5)
        assertArrayEquals(childPublic.publicKey, parent.deriveChild(5).toPublic().publicKey)
        assertArrayEquals(childPublic.chainCode, parent.deriveChild(5).chainCode)
    }

    @Test
    fun `parsePath handles the standard grammar`() {
        assertEquals(
            listOf(0x80000054L, 0x80000000L, 0x80000000L),
            ExtendedPrivateKey.parsePath("m/84'/0'/0'"),
        )
        assertEquals(emptyList<Long>(), ExtendedPrivateKey.parsePath("m"))
        assertEquals(
            listOf(0x8000002CL, 0x80000000L, 0x80000000L),
            ExtendedPrivateKey.parsePath("m/44h/0H/0'"),
        )
        // Round-trip: the parsed path drives the same derivation the literal
        // indexes do.
        val master = ExtendedPrivateKey.fromSeed(fromHex("000102030405060708090a0b0c0d0e0f"))
        assertArrayEquals(
            master.derive(ExtendedPrivateKey.parsePath("m/0'/1")).toPublic().publicKey,
            master.derive(listOf(0x80000000L, 1L)).toPublic().publicKey,
        )
    }

    @Test
    fun `parsePath rejects garbage`() {
        listOf(
            "x/84'/0'/0'",   // does not start at m
            "",              // empty
            "n",             // not a path
            "m/-1",          // negative
            "m/0''",         // empty index after the marker
            "m/",            // empty step
            "m/abc",         // not a number
            "m/0'x",         // trailing junk
            "m/2147483648'", // index out of range
        ).forEach { path ->
            assertThrows(
                "expected \"$path\" to be rejected",
                IllegalArgumentException::class.java,
            ) { ExtendedPrivateKey.parsePath(path) }
        }
    }

    @Test
    fun `both hardened and non-hardened steps are legal`() {
        // The spec's rejection list named "m/84"; that is a legal BIP-32 relative
        // path, and it must parse, because this same helper drives the
        // non-hardened chains of BIP-32 test vectors 1 and 2 (m/0H/1,
        // m/0/2147483647H/1, m/0H/1/2H/2/1000000000). A parser that rejected a
        // bare step could not derive those vectors at all.
        assertEquals(listOf(84L), ExtendedPrivateKey.parsePath("m/84"))
        assertEquals(
            listOf(0x80000000L, 1L, 0x80000002L, 2L, 1_000_000_000L),
            ExtendedPrivateKey.parsePath("m/0'/1/2'/2/1000000000"),
        )
    }

    @Test
    fun `an out-of-range child index is rejected`() {
        val master = ExtendedPrivateKey.fromSeed(fromHex("000102030405060708090a0b0c0d0e0f"))
        assertThrows(IllegalArgumentException::class.java) { master.deriveChild(-1) }
        assertThrows(IllegalArgumentException::class.java) { master.deriveChild(0x1_0000_0000L) }
        assertThrows(IllegalArgumentException::class.java) { master.deriveChild(0x80000000L + 0x80000000L) }
    }

    @Test
    fun `the class exposes no way to serialise a private key`() {
        // The watch-only side round-trips xpub/ypub/zpub; this class must have no
        // counterpart for the private half. Assert on the declared API itself.
        val names = ExtendedPrivateKey::class.java.declaredMethods.map { it.name.lowercase() }
        listOf("xprv", "zprv", "base58", "encode", "tostring", "serialize").forEach { needle ->
            assertEquals(
                "ExtendedPrivateKey must not expose a $needle method",
                false,
                names.any { it.contains(needle) },
            )
        }
    }

    private fun assertVector(seed: String, nodes: List<Node>) {
        val master = ExtendedPrivateKey.fromSeed(fromHex(seed))
        nodes.forEach { node ->
            val key = if (node.path == "m") {
                master
            } else {
                master.derive(ExtendedPrivateKey.parsePath(node.path))
            }
            val where = "at ${node.path}"
            assertEquals(where, node.privateKey, toHex(Secp256k1.toFixedLength(key.privateKey, 32)))
            assertEquals(where, node.chainCode, toHex(key.chainCode))
            assertEquals(where, node.publicKey, toHex(key.toPublic().publicKey))
            assertEquals(where, node.depth, key.depth)
        }
    }

    private fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
