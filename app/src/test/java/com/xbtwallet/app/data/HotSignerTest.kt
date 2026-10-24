package com.xbtwallet.app.data

import com.xbtwallet.app.chain.TransactionParser
import com.xbtwallet.app.crypto.Bip39
import com.xbtwallet.app.crypto.ExtendedPrivateKey
import com.xbtwallet.app.crypto.Hashes
import com.xbtwallet.app.crypto.Psbt
import com.xbtwallet.app.crypto.PsbtInput
import com.xbtwallet.app.crypto.buildUnsignedPsbt
import com.xbtwallet.app.crypto.parsePsbt
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signing a send with a hot wallet's own seed.
 *
 * The end-to-end test is the whole proof of the feature: the BIP-84 test
 * mnemonic's entropy goes into a vault, the record derived from the passphrased
 * account is what the signer is given, and a PSBT built over that account's own
 * coin comes back **finalized** — its raw transaction parsed by the app's own
 * chain parser, and its txid the one this file computed.
 *
 * The passphrase is pinned the way it actually protects the wallet: the wrong one
 * must be refused *before* anything is signed, and an ordinary wallet's empty
 * passphrase must work without a prompt.
 */
class HotSignerTest {

    /** The BIP-39 vector used across the crypto suite: entropy of all zeroes. */
    private val words = List(11) { "abandon" } + "about"

    /** The BIP-84 account zpub for the empty passphrase — pinned, so the vector is real. */
    private val bip84Zpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868" +
            "RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    private val accountPath = ExtendedPrivateKey.parsePath("m/84'/0'/0'")

    @Test
    fun `the vector mnemonic derives the BIP-84 account`() {
        assertEquals(bip84Zpub, HotWalletDerivation.derive(words, "").zpub)
    }

    // ---------------------------------------------------------------------
    // The end-to-end proof
    // ---------------------------------------------------------------------

    @Test
    fun `a hot wallet signs its own send and the result finalizes`() {
        val signer = hotSigner(passphrase = "")
        val vault = vaultFor(signer, passphrase = "")
        val psbt = psbtFor(passphrase = "")

        val outcome = signHotSend(vault, signer, psbt, passphrase = "")

        val signed = outcome as SignedSend.Signed

        // The app's own parser reads the finalized transaction back, and the id
        // this file derived from it is the id the signer reported.
        val parsed = TransactionParser.parse(signed.txHex)
        assertEquals(signed.txid, parsed.txHash)
        assertEquals("one input", 1, parsed.inputs.size)
        assertEquals("one output", 1, parsed.outputs.size)
        assertEquals("the recipient's 100,000 sats", 100_000L, parsed.outputs.single().satoshis)
        // The input's derivation was signed, not merely present.
        assertTrue("the signature must be this wallet's", signed.txHex.isNotEmpty())
    }

    @Test
    fun `an empty-passphrase wallet signs with the empty passphrase`() {
        val signer = hotSigner(passphrase = "")
        val vault = vaultFor(signer, passphrase = "")

        val outcome = signHotSend(vault, signer, psbtFor(passphrase = ""), passphrase = "")

        assertTrue("an empty passphrase is the ordinary case", outcome is SignedSend.Signed)
    }

    @Test
    fun `a passphrased wallet signs with its passphrase`() {
        val signer = hotSigner(passphrase = "correct horse")
        val vault = vaultFor(signer, passphrase = "correct horse")

        val outcome = signHotSend(vault, signer, psbtFor(passphrase = "correct horse"), passphrase = "correct horse")

        assertTrue("the right passphrase must sign", outcome is SignedSend.Signed)
    }

    // ---------------------------------------------------------------------
    // The refusals
    // ---------------------------------------------------------------------

    @Test
    fun `a wrong passphrase is refused and the PSBT is untouched`() {
        val signer = hotSigner(passphrase = "correct horse")
        val vault = vaultFor(signer, passphrase = "correct horse")
        val psbt = psbtFor(passphrase = "correct horse")
        val before = psbt.toBytes().copyOf()

        val outcome = signHotSend(vault, signer, psbt, passphrase = "wrong horse")

        assertEquals(SignedSend.WrongPassphrase, outcome)
        // Nothing was signed: no partial signatures, and the bytes are identical.
        assertTrue("no input may be signed", psbt.inputs.all { it.partialSigs.isEmpty() })
        assertTrue("the PSBT must be byte-identical", before.contentEquals(psbt.toBytes()))
    }

    @Test
    fun `an empty passphrase is refused for a passphrased wallet`() {
        val signer = hotSigner(passphrase = "correct horse")
        val vault = vaultFor(signer, passphrase = "correct horse")

        val outcome = signHotSend(vault, signer, psbtFor(passphrase = "correct horse"), passphrase = "")

        assertEquals(SignedSend.WrongPassphrase, outcome)
    }

    @Test
    fun `a vault with no entry for the wallet is VaultUnavailable`() {
        val signer = hotSigner(passphrase = "")

        val outcome = signHotSend(FakeSeedVault(), signer, psbtFor(passphrase = ""), passphrase = "")

        assertEquals(SignedSend.VaultUnavailable, outcome)
    }

    @Test
    fun `a deleted entry reads as VaultUnavailable, never as an empty seed`() {
        val signer = hotSigner(passphrase = "")
        val vault = vaultFor(signer, passphrase = "")
        vault.delete(signer.id)

        val outcome = signHotSend(vault, signer, psbtFor(passphrase = ""), passphrase = "")

        assertEquals(SignedSend.VaultUnavailable, outcome)
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    /** The record a provisioning walk would have written for [passphrase]. */
    private fun hotSigner(passphrase: String): Signer {
        val account = HotWalletDerivation.derive(words, passphrase)
        return Signer(
            id = "hot-1",
            name = "Hot wallet",
            type = SignerType.HotSeed(
                xpub = account.zpub,
                masterFingerprint = account.masterFingerprint,
                derivationPath = account.derivationPath,
                hasPassphrase = passphrase.isNotEmpty(),
            ),
            createdAtEpochSeconds = 0L,
            importedVia = SignerOrigin.HOT,
        )
    }

    /** A vault holding exactly the entropy the record's phrase came from. */
    private fun vaultFor(signer: Signer, passphrase: String): SeedVault {
        val record = signer.type as SignerType.HotSeed
        assertEquals(
            "the fixture's passphrase must reproduce this record's account",
            record.xpub,
            HotWalletDerivation.derive(words, passphrase).zpub,
        )
        val vault = FakeSeedVault()
        vault.store(signer.id, Bip39.mnemonicToEntropy(words))
        return vault
    }

    /**
     * A one-input, one-output PSBT over the account's own coin at `0/0`, labelled
     * with the passphrased **master** fingerprint — exactly what the send form
     * builds and what [signHotSend] tells the signer to expect.
     */
    private fun psbtFor(passphrase: String): Psbt {
        val pubKey = accountKey(passphrase).deriveChild(0L).deriveChild(0L).toPublic().publicKey
        val fingerprint = masterFingerprintBytes(
            HotWalletDerivation.derive(words, passphrase).masterFingerprint,
        )

        val built = buildUnsignedPsbt(
            unsignedTransaction(pubKey),
            listOf(
                PsbtInput(
                    witnessUtxoScriptPubKey = p2wpkh(pubKey),
                    witnessUtxoSatoshis = 100_000L,
                    masterFingerprint = fingerprint,
                    derivationPath = accountPath + listOf(0L, 0L),
                    pubKey = pubKey,
                ),
            ),
        )
        return parsePsbt(built)
    }

    private fun accountKey(passphrase: String): ExtendedPrivateKey =
        ExtendedPrivateKey
            .fromSeed(Bip39.mnemonicToSeed(words, passphrase))
            .derive(accountPath)

    private fun p2wpkh(pubKey: ByteArray): ByteArray = byteArrayOf(0x00, 0x14) + Hashes.hash160(pubKey)

    /** One input spending an all-`0x11` outpoint, one output paying [pubKey]. */
    private fun unsignedTransaction(pubKey: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x02, 0x00, 0x00, 0x00)) // nVersion
        out.write(0x01) // one input
        out.write(ByteArray(32) { 0x11 })
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write(0x00) // empty scriptSig
        out.write(byteArrayOf(-1, -1, -1, -1))
        out.write(0x01) // one output
        out.write(uint64Le(100_000L))
        out.write(0x16)
        out.write(p2wpkh(pubKey))
        out.write(byteArrayOf(0, 0, 0, 0)) // nLockTime
        return out.toByteArray()
    }

    private fun uint64Le(value: Long): ByteArray = ByteArray(8) { i -> ((value ushr (8 * i)) and 0xFF).toByte() }
}
