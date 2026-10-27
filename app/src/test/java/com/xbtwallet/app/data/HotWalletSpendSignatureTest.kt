package com.xbtwallet.app.data

import com.xbtwallet.app.chain.AddressChain
import com.xbtwallet.app.chain.Scripthash
import com.xbtwallet.app.chain.TxInput
import com.xbtwallet.app.chain.TxOutput
import com.xbtwallet.app.chain.buildUnsignedTransaction
import com.xbtwallet.app.chain.p2wpkhScriptPubKey
import com.xbtwallet.app.chain.scriptPubKeyFromAddress
import com.xbtwallet.app.crypto.Bip32Derivation
import com.xbtwallet.app.crypto.Bip39
import com.xbtwallet.app.crypto.Ecdsa
import com.xbtwallet.app.crypto.ExtendedPrivateKey
import com.xbtwallet.app.crypto.Hashes
import com.xbtwallet.app.crypto.PsbtInput
import com.xbtwallet.app.crypto.SIGHASH_UNIFIED
import com.xbtwallet.app.crypto.Secp256k1
import com.xbtwallet.app.crypto.Sighash
import com.xbtwallet.app.crypto.WitnessUtxo
import com.xbtwallet.app.crypto.buildUnsignedPsbt
import com.xbtwallet.app.crypto.decodeTransaction
import com.xbtwallet.app.crypto.parsePsbt
import com.xbtwallet.app.crypto.transactionId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A hot wallet's own spend, signed end to end and then verified **input by
 * input against its prevout**.
 *
 * This is the regression the send path was missing. `HotSignerTest` proves a
 * signature exists; `PsbtSignerTest` recomputes the digest with the production
 * code, which agrees with itself whatever that code does. Neither can tell a
 * unified signature from a BIP-143 one wearing a `0x21` byte — and that is
 * exactly the defect that had the node answer
 * `mempool-script-verify-flag-failed (Signature must be zero for failed
 * CHECK(MULTI)SIG operation)`.
 *
 * So the fixture is a realistic hot-wallet spend — two of the account's own
 * coins (a receive address and a change address), a payment out and change
 * back — and every finalized witness is checked three ways:
 *
 * 1. the witness public key hashes to the program of the prevout it spends;
 * 2. the signature verifies against the **unified** digest for that input,
 *    whose algorithm `UnifiedSighashTest` pins against the specification's own
 *    vectors and whose value for this fixture is pinned here independently;
 * 3. the signature does **not** verify against the BIP-143 digest, so a
 *    regression to the old message fails here rather than on the node.
 */
class HotWalletSpendSignatureTest {

    /** The BIP-39 vector used across the crypto suite: entropy of all zeroes. */
    private val words = List(11) { "abandon" } + "about"

    private val passphrase = "correct horse"

    /** The recipient, an ordinary P2WPKH address this wallet does not own. */
    private val recipient = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"

    private val accountPath = ExtendedPrivateKey.parsePath("m/84'/0'/0'")

    private fun h(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun fromHex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }

    @Test
    fun `a two-input hot-wallet spend verifies against every prevout`() {
        val fixture = fixture()
        val outcome = runBlocking { signHotSend(fixture.vault, fixture.signer, parsePsbt(fixture.psbtBytes), passphrase) }
        val signed = outcome as SignedSend.Signed

        val decoded = decodeTransaction(fromHex(signed.txHex))
        assertEquals("one witness stack per input", 2, decoded.witnesses.size)

        decoded.witnesses.forEachIndexed { index, stack ->
            assertEquals("input $index witness stack", 2, stack.size)
            val signature = stack[0]
            val pubKey = stack[1]

            // 1. The witness key is the one the prevout pays.
            assertEquals("input $index witness key", h(fixture.pubKeys[index]), h(pubKey))
            assertArrayEquals(
                "input $index must hash to its prevout program",
                fixture.spentScripts[index].copyOfRange(2, 22),
                Hashes.hash160(pubKey),
            )

            // 2. The unified sighash byte, and a signature over the unified message.
            assertEquals("input $index unified sighash byte", 0x21, signature.last().toInt() and 0xFF)
            val unified = Sighash.unifiedDigest(decoded, index, fixture.spentOutputs, SIGHASH_UNIFIED)
            assertTrue(
                "input $index must verify against its prevout's unified digest",
                Ecdsa.verify(Secp256k1.parse(pubKey), unified, signature.copyOf(signature.size - 1)),
            )

            // 3. ...and it must not verify against the BIP-143 message a `0x21`
            //    byte alone would have implied.
            val legacy = Sighash.p2wpkhLegacyDigest(decoded, index, fixture.spentOutputs[index])
            assertFalse(
                "input $index must not verify against the BIP-143 digest",
                Ecdsa.verify(Secp256k1.parse(pubKey), legacy, signature.copyOf(signature.size - 1)),
            )
        }

        // The reported id is the finalized transaction's own.
        assertEquals(transactionId(fromHex(signed.txHex)), signed.txid)
    }

    /**
     * The fixture's own digests, pinned to values computed by a separate
     * implementation of the specification (the same reference the algorithm is
     * checked against vector by vector in `UnifiedSighashTest`). If the message
     * this wallet signs ever changes shape again, this fails before a node has
     * to say so.
     */
    @Test
    fun `the fixture's unified digests are the independently computed ones`() {
        val fixture = fixture()
        val tx = decodeTransaction(fixture.unsigned)

        // Computed by a separate implementation of the specification for this
        // exact unsigned transaction and these exact spent outputs (the same
        // reference implementation the vectors in `UnifiedSighashTest` come
        // from). The `scriptCode` tail is serialised **once** — the raw 25-byte
        // implied P2PKH script behind a single compact-size length byte — which
        // is what a node's `SignatureHashUnified` writes when it does
        // `ss << scriptCode` on that script. If the message this wallet signs
        // changes shape again, this fails before a node has to reject the spend.
        assertEquals(
            "input 0 unified digest",
            "c58d93700304feb2d39339c0e297ffc411bcc67baf93e5a9945a6af7d13525b7",
            h(Sighash.unifiedDigest(tx, 0, fixture.spentOutputs, SIGHASH_UNIFIED)),
        )
        assertEquals(
            "input 1 unified digest",
            "43e5ac47a69791677c7978141bf931778dc92cfcb2dbb42cc5f3b9c00fd55914",
            h(Sighash.unifiedDigest(tx, 1, fixture.spentOutputs, SIGHASH_UNIFIED)),
        )

        // The two messages really are different: the BIP-143 digests for the
        // same inputs, also computed independently.
        assertEquals(
            "input 0 must not be a BIP-143 message",
            "f54e90a1d1514f6fbeab723b645a71827ed62943ac1c241cae4cc220b9db819d",
            h(Sighash.p2wpkhLegacyDigest(tx, 0, fixture.spentOutputs[0])),
        )
        assertEquals(
            "input 1 must not be a BIP-143 message",
            "3b2038eba72dcea1d21e8d90c81190b337d8d12aa348549c807047f1abfd057c",
            h(Sighash.p2wpkhLegacyDigest(tx, 1, fixture.spentOutputs[1])),
        )
    }

    private class Fixture(
        val unsigned: ByteArray,
        val psbtBytes: ByteArray,
        val spentScripts: List<ByteArray>,
        val spentValues: List<Long>,
        val pubKeys: List<ByteArray>,
        val signer: Signer,
        val vault: SeedVault,
    ) {
        val spentOutputs: List<WitnessUtxo>
            get() = spentScripts.mapIndexed { index, script -> WitnessUtxo(spentValues[index], script) }
    }

    /**
     * A two-input, two-output send of the account's own coins: receive `0/0`
     * (100 000 sat) and change `1/0` (60 000 sat) in, a 120 000-sat payment out
     * and 30 000 sat back to change `1/1` — the shape `prepareSend` builds.
     */
    private fun fixture(): Fixture {
        val account = HotWalletDerivation.derive(words, passphrase)
        val signer = Signer(
            id = "hot-1",
            name = "Hot wallet",
            type = SignerType.HotSeed(
                xpub = account.zpub,
                masterFingerprint = account.masterFingerprint,
                derivationPath = account.derivationPath,
                hasPassphrase = true,
            ),
            createdAtEpochSeconds = 0L,
            importedVia = SignerOrigin.HOT,
        )
        val vault = FakeSeedVault().also { it.store(signer.id, Bip39.mnemonicToEntropy(words)) }

        val accountKey = ExtendedPrivateKey
            .fromSeed(Bip39.mnemonicToSeed(words, passphrase))
            .derive(accountPath)

        val receiveKey = accountKey.derive(listOf(AddressChain.RECEIVE.index, 0L))
        val spentChangeKey = accountKey.derive(listOf(AddressChain.CHANGE.index, 0L))
        val changeKey = accountKey.derive(listOf(AddressChain.CHANGE.index, 1L))

        val receivePub = receiveKey.toPublic().publicKey
        val spentChangePub = spentChangeKey.toPublic().publicKey
        val changePub = changeKey.toPublic().publicKey

        val spentScripts = listOf(p2wpkhScriptPubKey(receivePub), p2wpkhScriptPubKey(spentChangePub))
        val spentValues = listOf(100_000L, 60_000L)

        val unsigned = buildUnsignedTransaction(
            inputs = listOf(TxInput("11".repeat(32), 0), TxInput("22".repeat(32), 1)),
            outputs = listOf(
                TxOutput(120_000L, Scripthash.toHex(scriptPubKeyFromAddress(recipient))),
                TxOutput(30_000L, Scripthash.toHex(p2wpkhScriptPubKey(changePub))),
            ),
        )

        val fingerprint = masterFingerprintBytes(account.masterFingerprint)
        val psbtBytes = buildUnsignedPsbt(
            unsignedTxBytes = unsigned,
            inputs = listOf(
                PsbtInput(
                    witnessUtxoScriptPubKey = spentScripts[0],
                    witnessUtxoSatoshis = spentValues[0],
                    masterFingerprint = fingerprint,
                    derivationPath = accountPath + listOf(AddressChain.RECEIVE.index, 0L),
                    pubKey = receivePub,
                ),
                PsbtInput(
                    witnessUtxoScriptPubKey = spentScripts[1],
                    witnessUtxoSatoshis = spentValues[1],
                    masterFingerprint = fingerprint,
                    derivationPath = accountPath + listOf(AddressChain.CHANGE.index, 0L),
                    pubKey = spentChangePub,
                ),
            ),
            change = Bip32Derivation(
                masterFingerprint = fingerprint,
                path = accountPath + listOf(AddressChain.CHANGE.index, 1L),
                pubKey = changePub,
            ),
            changeOutputIndex = 1,
        )

        return Fixture(
            unsigned = unsigned,
            psbtBytes = psbtBytes,
            spentScripts = spentScripts,
            spentValues = spentValues,
            pubKeys = listOf(receivePub, spentChangePub),
            signer = signer,
            vault = vault,
        )
    }
}
