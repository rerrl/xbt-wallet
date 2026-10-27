package com.xbtwallet.app.crypto

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signing a PSBT with an in-memory account key.
 *
 * The end-to-end test is Phase A's proof: the BIP-84 test mnemonic's account key
 * signs a two-input PSBT built over that account's own addresses, and the
 * finalized transaction's every witness signature ends in the unified sighash
 * byte `0x21` and verifies against the input's public key and a digest recomputed
 * from the unsigned transaction. Nothing here is stubbed: the derivation, the
 * sighash, the RFC 6979 signature and the finalizer are all the production code.
 *
 * The refusal tests pin the four ways an input can be unsignable, each of which
 * must throw rather than be skipped.
 */
class PsbtSignerTest {

    private val words = List(11) { "abandon" } + "about"

    private val account: ExtendedPrivateKey = ExtendedPrivateKey
        .fromSeed(Bip39.mnemonicToSeed(words))
        .derive(ExtendedPrivateKey.parsePath("m/84'/0'/0'"))

    private val accountPrefix = listOf(0x80000054L, 0x80000000L, 0x80000000L)
    private val accountFingerprint: ByteArray = int4(account.fingerprint())

    private val receiveKey = account.derive(listOf(0L, 0L))
    private val changeKey = account.derive(listOf(1L, 0L))
    private val receivePub = receiveKey.toPublic().publicKey
    private val changePub = changeKey.toPublic().publicKey

    private val receiveUtxo = WitnessUtxo(100_000L, p2wpkh(receivePub))
    private val changeUtxo = WitnessUtxo(50_000L, p2wpkh(changePub))

    // ---------------------------------------------------------------------
    // The end-to-end proof
    // ---------------------------------------------------------------------

    @Test
    fun `signs and finalizes a two-input PSBT end to end`() {
        val unsigned = unsignedTransaction()
        val built = buildUnsignedPsbt(unsigned, psbtInputs())

        val signed = PsbtSigner.signPsbt(parsePsbt(built), account)

        // Every input now carries exactly one partial signature.
        signed.inputs.forEachIndexed { index, input ->
            assertEquals("input $index", 1, input.partialSigs.size)
        }
        assertEquals(
            toHex(receivePub),
            toHex(signed.inputs[0].partialSigs.single().pubKey),
        )

        val finalized = finalizePsbt(signed)
        val decoded = decodeTransaction(finalized)
        assertEquals(2, decoded.witnesses.size)

        val utxos = listOf(receiveUtxo, changeUtxo)
        val pubs = listOf(receivePub, changePub)
        decoded.witnesses.forEachIndexed { index, stack ->
            assertEquals("input $index witness stack", 2, stack.size)
            val signature = stack[0]
            val pubKey = stack[1]

            assertEquals("input $index must carry the unified sighash byte", 0x21, signature.last().toInt() and 0xFF)
            assertArrayEquals("input $index public key", pubs[index], pubKey)

            val digest = Sighash.p2wpkhDigest(decodeTransaction(unsigned), index, utxos, SIGHASH_UNIFIED)
            assertTrue(
                "input $index signature must verify",
                Ecdsa.verify(Secp256k1.parse(pubKey), digest, signature.copyOf(signature.size - 1)),
            )
        }
    }

    @Test
    fun `the signature is deterministic - signing twice gives the same bytes`() {
        val built = buildUnsignedPsbt(unsignedTransaction(), psbtInputs())
        val first = PsbtSigner.signPsbt(parsePsbt(built), account)
        val second = PsbtSigner.signPsbt(parsePsbt(built), account)

        first.inputs.indices.forEach { index ->
            assertArrayEquals(
                first.inputs[index].partialSigs.single().signature,
                second.inputs[index].partialSigs.single().signature,
            )
        }
    }

    // ---------------------------------------------------------------------
    // The refusals
    // ---------------------------------------------------------------------

    @Test
    fun `a PSBT whose input declares sighash 0x01 is refused`() {
        val refusing = Psbt(
            unsignedTransaction(),
            emptyList(),
            listOf(validInput(sighashType = Sighash.SIGHASH_ALL), validInput()),
            List(2) { emptyList() },
        )
        assertThrows(PsbtException::class.java) { PsbtSigner.signPsbt(refusing, account) }
    }

    @Test
    fun `a PSBT whose input declares no sighash is refused`() {
        val refusing = Psbt(
            unsignedTransaction(),
            emptyList(),
            listOf(validInput(sighashType = null), validInput()),
            List(2) { emptyList() },
        )
        assertThrows(PsbtException::class.java) { PsbtSigner.signPsbt(refusing, account) }
    }

    @Test
    fun `a PSBT whose input has no witness utxo is refused`() {
        val refusing = Psbt(
            unsignedTransaction(),
            emptyList(),
            listOf(validInput(witnessUtxo = null), validInput()),
            List(2) { emptyList() },
        )
        assertThrows(PsbtException::class.java) { PsbtSigner.signPsbt(refusing, account) }
    }

    @Test
    fun `a PSBT whose input has no derivation is refused`() {
        val refusing = Psbt(
            unsignedTransaction(),
            emptyList(),
            listOf(validInput(derivations = emptyList()), validInput()),
            List(2) { emptyList() },
        )
        assertThrows(PsbtException::class.java) { PsbtSigner.signPsbt(refusing, account) }
    }

    @Test
    fun `a PSBT for a different account fingerprint is refused`() {
        val other = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val built = buildUnsignedPsbt(
            unsignedTransaction(),
            listOf(
                PsbtInput(receiveUtxo.scriptPubKey, receiveUtxo.satoshis, other, accountPrefix + listOf(0L, 0L), receivePub),
                PsbtInput(changeUtxo.scriptPubKey, changeUtxo.satoshis, other, accountPrefix + listOf(1L, 0L), changePub),
            ),
        )
        assertThrows(PsbtException::class.java) { PsbtSigner.signPsbt(parsePsbt(built), account) }
    }

    @Test
    fun `a derivation that is not under the account prefix is refused`() {
        val wrongPath = Bip32Derivation(
            masterFingerprint = accountFingerprint,
            path = listOf(0x8000002CL, 0x80000000L, 0x80000000L, 0L, 0L), // m/44'/0'/0'/0/0
            pubKey = receivePub,
        )
        val refusing = Psbt(
            unsignedTransaction(),
            emptyList(),
            listOf(validInput(derivations = listOf(wrongPath)), validInput()),
            List(2) { emptyList() },
        )
        assertThrows(PsbtException::class.java) { PsbtSigner.signPsbt(refusing, account) }
    }

    @Test
    fun `signing does not mutate the PSBT it is given`() {
        val built = buildUnsignedPsbt(unsignedTransaction(), psbtInputs())
        val original = parsePsbt(built)

        PsbtSigner.signPsbt(original, account)

        original.inputs.forEach { assertTrue(it.partialSigs.isEmpty()) }
        assertEquals(toHex(built), toHex(original.toBytes()))
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private fun p2wpkh(pubKey: ByteArray): ByteArray = byteArrayOf(0x00, 0x14) + Hashes.hash160(pubKey)

    private fun psbtInputs(): List<PsbtInput> = listOf(
        PsbtInput(
            witnessUtxoScriptPubKey = receiveUtxo.scriptPubKey,
            witnessUtxoSatoshis = receiveUtxo.satoshis,
            masterFingerprint = accountFingerprint,
            derivationPath = accountPrefix + listOf(0L, 0L),
            pubKey = receivePub,
        ),
        PsbtInput(
            witnessUtxoScriptPubKey = changeUtxo.scriptPubKey,
            witnessUtxoSatoshis = changeUtxo.satoshis,
            masterFingerprint = accountFingerprint,
            derivationPath = accountPrefix + listOf(1L, 0L),
            pubKey = changePub,
        ),
    )

    /** A fully valid parsed input for the first input of [unsignedTransaction], with optional defects. */
    private fun validInput(
        witnessUtxo: WitnessUtxo? = receiveUtxo,
        sighashType: Long? = SIGHASH_UNIFIED,
        derivations: List<Bip32Derivation> = listOf(
            Bip32Derivation(accountFingerprint, accountPrefix + listOf(0L, 0L), receivePub),
        ),
    ): ParsedPsbtInput = ParsedPsbtInput(
        keyValues = emptyList(),
        witnessUtxo = witnessUtxo,
        sighashType = sighashType,
        derivations = derivations,
        partialSigs = emptyList(),
    )

    /** Two inputs spending the account's own coins, two outputs, no witness data. */
    private fun unsignedTransaction(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x02, 0x00, 0x00, 0x00)) // nVersion
        out.write(0x02) // two inputs
        out.write(ByteArray(32) { 0x11 })
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write(0x00) // empty scriptSig
        out.write(byteArrayOf(-1, -1, -1, -1))
        out.write(ByteArray(32) { 0x22 })
        out.write(byteArrayOf(1, 0, 0, 0))
        out.write(0x00)
        out.write(byteArrayOf(-1, -1, -1, -1))
        out.write(0x02) // two outputs
        out.write(uint64Le(90_000L))
        out.write(0x16)
        out.write(p2wpkh(receivePub))
        out.write(uint64Le(50_000L))
        out.write(0x16)
        out.write(p2wpkh(changePub))
        out.write(byteArrayOf(0, 0, 0, 0)) // nLockTime
        return out.toByteArray()
    }

    private fun int4(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun uint64Le(value: Long): ByteArray = ByteArray(8) { i -> ((value ushr (8 * i)) and 0xFF).toByte() }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
