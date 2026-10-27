package com.xbtwallet.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The unified signature hash, against the specification's own vectors.
 *
 * The vectors are rows of `src/test/data/unified_sighash.json` from the
 * reference implementation of the opt-in unified signature hash (Knots PR 357,
 * `doc/unified-sighash.md`): `scriptCode`, `rawTx`, `inIdx`, `hashType`,
 * `scriptType`, `spentOutputs` and the expected signature hash. Six rows are
 * carried here, covering both script types this wallet can be asked about (0 and
 * 1) and the hashtypes that change the message's shape — `0x21`
 * (`ALL|UNIFIED`), `0x23` (`SINGLE|UNIFIED`) and `0xa3`
 * (`SINGLE|ANYONECANPAY|UNIFIED`).
 *
 * These are what pin the algorithm independently of this codebase: the wallet's
 * own signing test cannot, because a digest recomputed with the code under test
 * agrees with itself. A BIP-143 digest with `0x21` appended is a *different*
 * message, and this file is what proves which one is correct.
 */
class UnifiedSighashTest {

    /** One row of the specification's vector file, hashes as raw bytes. */
    private class Vector(
        val scriptType: Int,
        val hashType: Long,
        val inputIndex: Int,
        val rawTx: String,
        val scriptCode: String,
        val spentOutputs: List<Pair<Long, String>>,
        val expected: String,
    )

    /** Knots unified_sighash.json — script type 1, hashtype 0x21. */
    private val v0 = Vector(
        scriptType = 1,
        hashType = 0x21L,
        inputIndex = 0,
        rawTx = "8e197b4c0194140e3f6cb8a9d6ca4088e0a815b50735368cf53764d84558499518643b09740d6dea19002155ac0602c372fe59aa0e010007565656565656563f9c44ce1e3c0600055555555555419088c0",
        scriptCode = "515151515151",
        spentOutputs = listOf(163728673632716L to "51515151515151"),
        expected = "f2c5bab31e7924172309e841f22885a287927cecb6e06fec9abc8902882fe659",
    )

    /** Knots unified_sighash.json — script type 1, hashtype 0x23. */
    private val v1 = Vector(
        scriptType = 1,
        hashType = 0x23L,
        inputIndex = 2,
        rawTx = "55e7df1f03a2e1028e84853655014dca467ee40df143ba8bdf28c7a2bb902d962d57e75aa1553476250067f858af19515e13caeb324ef33c411dd9b184a33ffc2522321efa6c24dcbc4cc390ed8388b8f39f0005dff9359800d66f4b314f9cc34a44b7323768d15a033b74cc33270e33b1bf25d071fc3118d7fc6900103619800365c7f93ca4450300085252525252525252c463c07561ca0600025353858998a30e5c0600065353535353534beec045",
        scriptCode = "535353",
        spentOutputs = listOf(163193100250490L to "5353", 743032443157238L to "565656", 771531468213182L to "5252"),
        expected = "aedc404d5b84a28f4b437efbff8f665f73531fb62ed313127a7529528f515d94",
    )

    /** Knots unified_sighash.json — script type 1, hashtype 0xa3. */
    private val v2 = Vector(
        scriptType = 1,
        hashType = 0xa3L,
        inputIndex = 0,
        rawTx = "8261d4630114ac830b2173f4b004875606e69603c631e3b97391b07b19a95ae77ce264854e68d2b52600c59d899c029d3ef4bd27160600055656565656055ed19d0e550700025252594e8ed4",
        scriptCode = "555555",
        spentOutputs = listOf(208108053237348L to "535353535353"),
        expected = "ee757f740795f1b9a988013679dba04e3411cb3920f13902c292d166abc1de28",
    )

    /** Knots unified_sighash.json — script type 0, hashtype 0x21. */
    private val v3 = Vector(
        scriptType = 0,
        hashType = 0x21L,
        inputIndex = 1,
        rawTx = "6545f23b028086f79d0214a6d4a5dd1b8b94156292f62833123f2a0ee3379fe7f60782813287c8a349005068857bbe8e8c327e37f35216638c08205def4ea4135da23022a15c8f0962ac9f512dd72a32b05400c532b0dd038029a3926d0c020007555555555555559a55adaccf90000006515151515151c794d460842b030007515151515151516febe787",
        scriptCode = "56565656565656",
        spentOutputs = listOf(202290887164102L to "51515151", 1694130844146557L to "5454545454"),
        expected = "f504339d8836b360b938386d702797ffc105b19852b131661a2e29008de360ab",
    )

    /** Knots unified_sighash.json — script type 0, hashtype 0x23. */
    private val v4 = Vector(
        scriptType = 0,
        hashType = 0x23L,
        inputIndex = 0,
        rawTx = "d4a768100179d2803fd948c03b52dfaa095b0d11f3c1a5552563b267d4c1d2a2fe0aafdae8431bd34500c7cee298025af6b78797fd0600045353535357d4858bffe8040002545497cc3f69",
        scriptCode = "5656565656",
        spentOutputs = listOf(1795699524180909L to "52"),
        expected = "d8257e16a63d4cc46676d55e65fce20b0b0e745f9697ae4295a524dfdd6fe6b3",
    )

    /** Knots unified_sighash.json — script type 0, hashtype 0xa3. */
    private val v5 = Vector(
        scriptType = 0,
        hashType = 0xa3L,
        inputIndex = 0,
        rawTx = "2316de5a02a4fedc0d9e902f93a402329869afcf6b22c444f800297418b54d2e002c57eb6af3c4730b00569037965acac7bfd72758f60ddbe8ca7b1da23a70984f5e8b03bc44889190ea56034ec7bf259257003f9f66fc01cbbc461ea5ac04000254544e112901",
        scriptCode = "5353535353",
        spentOutputs = listOf(650374865900231L to "515151515151", 877685345816497L to "52525252525252"),
        expected = "4a84224afd272deeaa13972fb03ea70c738d78e50b53a63af3b3a9decfb548f5",
    )

    private val allVectors = listOf(v0, v1, v2, v3, v4, v5)

    @Test
    fun `every reference vector is reproduced byte for byte`() {
        allVectors.forEachIndexed { index, vector ->
            val tx = decodeTransaction(fromHex(vector.rawTx))
            val spent = vector.spentOutputs.map { (value, script) -> WitnessUtxo(value, fromHex(script)) }
            assertEquals(
                "vector $index (script type ${vector.scriptType}, hashtype 0x${vector.hashType.toString(16)})",
                vector.expected,
                toHex(
                    Sighash.unifiedDigestWithScript(
                        tx = tx,
                        inputIndex = vector.inputIndex,
                        spentOutputs = spent,
                        hashType = vector.hashType,
                        scriptType = vector.scriptType,
                        scriptCode = fromHex(vector.scriptCode),
                    ),
                ),
            )
        }
    }

    @Test
    fun `the wallet's own shape is the script-type-1 message`() {
        // A P2WPKH input signed with 0x21: the public entry point derives the
        // BIP-143 implied P2PKH scriptCode, which must be the same message the
        // explicit-scriptCode form builds.
        val pubKey = fromHex("0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c")
        val scriptPubKey = byteArrayOf(0x00, 0x14) + Hashes.hash160(pubKey)
        val unsigned = unsignedTransaction(scriptPubKey)
        val tx = decodeTransaction(unsigned)
        val spent = listOf(WitnessUtxo(100_000L, scriptPubKey))

        val impliedScriptCode =
            byteArrayOf(0x19, 0x76, 0xa9.toByte(), 0x14) + Hashes.hash160(pubKey) + byteArrayOf(0x88.toByte(), 0xac.toByte())

        assertEquals(
            toHex(Sighash.unifiedDigestWithScript(tx, 0, spent, 0x21L, 1, impliedScriptCode)),
            toHex(Sighash.unifiedDigest(tx, 0, spent, 0x21L)),
        )
        assertEquals(
            toHex(Sighash.unifiedDigest(tx, 0, spent, 0x21L)),
            toHex(Sighash.p2wpkhDigest(tx, 0, spent, SIGHASH_UNIFIED)),
        )
        // The unified message is not the BIP-143 one.
        assertNotEquals(
            toHex(Sighash.p2wpkhLegacyDigest(tx, 0, spent[0])),
            toHex(Sighash.unifiedDigest(tx, 0, spent, 0x21L)),
        )
    }

    @Test
    fun `a hashtype without the opt-in bit is not a unified message`() {
        val tx = decodeTransaction(fromHex(v0.rawTx))
        val spent = v0.spentOutputs.map { (value, script) -> WitnessUtxo(value, fromHex(script)) }
        assertThrows(IllegalArgumentException::class.java) {
            Sighash.unifiedDigestWithScript(tx, 0, spent, 0x01L, 1, fromHex(v0.scriptCode))
        }
    }

    @Test
    fun `spent outputs must be given for every input`() {
        val tx = decodeTransaction(fromHex(v1.rawTx))
        assertThrows(IllegalArgumentException::class.java) {
            Sighash.unifiedDigestWithScript(
                tx = tx,
                inputIndex = 0,
                spentOutputs = listOf(WitnessUtxo(1L, fromHex("515151515151"))),
                hashType = 0x21L,
                scriptType = 1,
                scriptCode = fromHex("515151515151"),
            )
        }
    }

    @Test
    fun `the taproot script types are refused`() {
        val tx = decodeTransaction(fromHex(v0.rawTx))
        val spent = v0.spentOutputs.map { (value, script) -> WitnessUtxo(value, fromHex(script)) }
        listOf(2, 3).forEach { scriptType ->
            assertThrows(IllegalArgumentException::class.java) {
                Sighash.unifiedDigestWithScript(tx, 0, spent, 0x21L, scriptType, ByteArray(0))
            }
        }
    }

    private fun unsignedTransaction(scriptPubKey: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x02, 0x00, 0x00, 0x00))
        out.write(0x01)
        out.write(ByteArray(32) { 0x11 })
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write(0x00)
        out.write(byteArrayOf(-1, -1, -1, -1))
        out.write(0x01)
        out.write(ByteArray(8) { i -> ((100_000L ushr (8 * i)) and 0xFF).toByte() })
        out.write(scriptPubKey.size)
        out.write(scriptPubKey)
        out.write(byteArrayOf(0, 0, 0, 0))
        return out.toByteArray()
    }

    private fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
