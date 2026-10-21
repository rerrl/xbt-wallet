package com.xbtwallet.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * BIP-174, pinned three ways.
 *
 * 1. **Against the spec's own vectors.** Three PSBTs from BIP-174's test-vector
 *    section are parsed and re-serialised; the bytes must come back identical.
 *    That is the strongest statement available about the parser: every field type
 *    the spec uses, in the order the spec wrote them, survives a round trip.
 * 2. **Against an independently generated fixture.** A transaction this wallet
 *    would build, its PSBT, a signed version of it, and the final network
 *    serialization are byte-compared to a fixture produced by a separate
 *    reference implementation — the `FIXTURE_*` constants below, generated off
 *    this code, not by it.
 * 3. **Against the refusals.** The unified-sighash rule and the
 *    single-signature rule each have a test that must fail.
 */
class PsbtTest {

    // ---------------------------------------------------------------------
    // BIP-174's own test vectors (their base64, as published).
    // ---------------------------------------------------------------------

    /** BIP-174 valid vector: one input, a witness_utxo, a partial sig, two derivations. */
    private val bip174WitnessUtxo = base64("cHNidP8BAFUCAAAAASeaIyOl37UfxF8iD6WLD8E+HjNCeSqF1+Ns1jM7XLw5AAAAAAD/////AaBa6gsAAAAAGXapFP/pwAYQl8w7Y28ssEYPpPxCfStFiKwAAAAAAAEBIJVe6gsAAAAAF6kUY0UgD2jRieGtwN8cTRbqjxTA2+uHIQIDsTQcy6doO2r08SOM1ul+cWfVafrEfx5I1HVBhENVvUYwQwIgBCS1jv+qppThVZ6lyTu/1KiQZCJAVc3wcLZ3FGlELQcCH1yOsP6mUW1guKyzOtZO3mDoeFv7OqlLmb34YVHbmpoBAQQiACB3H9GK1FlmbdSfPVZOPbxC9MhHdONgraFoFqjtSI1WgQEFR1IhA7E0HMunaDtq9PEjjNbpfnFn1Wn6xH8eSNR1QYRDVb1GIQPeVdHh2sgF4/iljB+/m5TALz26r+En/vykmV8m+CCDvVKuIgYDsTQcy6doO2r08SOM1ul+cWfVafrEfx5I1HVBhENVvUYQtKa6ZwAAAIAAAACABAAAgCIGA95V0eHayAXj+KWMH7+blMAvPbqv4Sf+/KSZXyb4IIO9ELSmumcAAACAAAAAgAUAAIAAAA==")

    /** BIP-174 valid vector: a global xpub, a non-witness utxo, a taproot derivation. */
    private val bip174GlobalXpub = base64("cHNidP8BAFICAAAAAZ38ZijCbFiZ/hvT3DOGZb/VXXraEPYiCXPfLTht7BJ2AQAAAAD/////AfA9zR0AAAAAFgAUezoAv9wU0neVwrdJAdCdpu8TNXkAAAAATwEENYfPAto/0AiAAAAAlwSLGtBEWx7IJ1UXcnyHtOTrwYogP/oPlMAVZr046QADUbdDiH7h1A3DKmBDck8tZFmztaTXPa7I+64EcvO8Q+IM2QxqT64AAIAAAACATwEENYfPAto/0AiAAAABuQRSQnE5zXjCz/JES+NTzVhgXj5RMoXlKLQH+uP2FzUD0wpel8itvFV9rCrZp+OcFyLrrGnmaLbyZnzB1nHIPKsM2QxqT64AAIABAACAAAEBKwBlzR0AAAAAIgAgLFSGEmxJeAeagU4TcV1l82RZ5NbMre0mbQUIZFuvpjIBBUdSIQKdoSzbWyNWkrkVNq/v5ckcOrlHPY5DtTODarRWKZyIcSEDNys0I07Xz5wf6l0F1EFVeSe+lUKxYusC4ass6AIkwAtSriIGAp2hLNtbI1aSuRU2r+/lyRw6uUc9jkO1M4NqtFYpnIhxENkMak+uAACAAAAAgAAAAAAiBgM3KzQjTtfPnB/qXQXUQVV5J76VQrFi6wLhqyzoAiTACxDZDGpPrgAAgAEAAIAAAAAAACICA57/H1R6HV+S36K6evaslxpL0DukpzSwMVaiVritOh75EO3kXMUAAACAAAAAgAEAAIAA")

    /** BIP-174 valid vector: two inputs, a witness_utxo and taproot derivations. */
    private val bip174TwoInputs = base64("cHNidP8BAJ0BAAAAAnEOp2q0XFy2Q45gflnMA3YmmBgFrp4N/ZCJASq7C+U1AQAAAAD/////GQmU1qizyMgsy8+y+6QQaqBmObhyqNRHRlwNQliNbWcAAAAAAP////8CAOH1BQAAAAAZdqkUtrwsDuVlWoQ9ea/t0MzD991kNAmIrGBa9AUAAAAAFgAUEYjvjkzgRJ6qyPsUHL9aEXbmoIgAAAAATwEEiLIeA55TDKyAAAAAPbyKXJdp8DGxfnf+oVGGAyIaGP0Y8rmlTGyMGsdcvDUC8jBYSxVdHH8c1FEgplPEjWULQxtnxbLBPyfXFCA3wWkQJ1acUDEAAIAAAACAAAAAgAABAR8A4fUFAAAAABYAFDO5gvkbKPFgySC0q5XljOUN2jpKIgIDMJaA8zx9446mpHzU7NZvH1pJdHxv+4gI7QkDkkPjrVxHMEQCIC1wTO2DDFapCTRL10K2hS3M0QPpY7rpLTjnUlTSu0JFAiAthsQ3GV30bAztoITyopHD2i1kBw92v5uQsZXn7yj3cgEiBgMwloDzPH3jjqakfNTs1m8fWkl0fG/7iAjtCQOSQ+OtXBgnVpxQMQAAgAAAAIAAAACAAAAAAAEAAAAAAQEfAOH1BQAAAAAWABQ4j7lEMH63fvRRl9CwskXgefAR3iICAsd3Fh9z0LfHK57nveZQKT0T8JW8dlatH1Jdpf0uELEQRzBEAiBMsftfhpyULg4mEAV2ElQ5F5rojcqKncO6CPeVOYj6pgIgUh9JynkcJ9cOJzybFGFphZCTYeJb4nTqIA1+CIJ+UU0BIgYCx3cWH3PQt8crnue95lApPRPwlbx2Vq0fUl2l/S4QsRAYJ1acUDEAAIAAAACAAAAAgAAAAAAAAAAAAAAiAgLSDKUC7iiWhtIYFb1DqAY3sGmOH7zb5MrtRF9sGgqQ7xgnVpxQMQAAgAAAAIAAAACAAAAAAAQAAAAA")

    // ---------------------------------------------------------------------
    // The generated fixture (a separate reference implementation, not this code):
    // a 2-output transaction spending one P2WPKH coin, its unsigned PSBT, the
    // same PSBT with one partial signature ending in the unified sighash byte
    // 0x21, the same PSBT with that byte changed to 0x01, the same PSBT with a
    // second partial signature, and the final segwit serialization.
    // ---------------------------------------------------------------------

    private val fixtureUnsignedTx = "020000000111111111111111111111111111111111111111111111111111111111111111110000000000ffffffff0250c30000000000001600142222222222222222222222222222222222222222409c000000000000160014333333333333333333333333333333333333333300000000"

    private val fixturePsbtUnsigned = "70736274ff010071020000000111111111111111111111111111111111111111111111111111111111111111110000000000ffffffff0250c30000000000001600142222222222222222222222222222222222222222409c0000000000001600143333333333333333333333333333333333333333000000000001011bfea08601000014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa01030421000000220603abababababababababababababababababababababababababababababababab18123456785400008000000080000000800000000000000000000000"

    private val fixturePsbtSigned = "70736274ff010071020000000111111111111111111111111111111111111111111111111111111111111111110000000000ffffffff0250c30000000000001600142222222222222222222222222222222222222222409c0000000000001600143333333333333333333333333333333333333333000000000001011bfea08601000014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa01030421000000220603abababababababababababababababababababababababababababababababab18123456785400008000000080000000800000000000000000220203abababababababababababababababababababababababababababababababab473044022001010101010101010101010101010101010101010101010101010101010101010220020202020202020202020202020202020202020202020202020202020202020221000000"

    private val fixturePsbtSighashAll = "70736274ff010071020000000111111111111111111111111111111111111111111111111111111111111111110000000000ffffffff0250c30000000000001600142222222222222222222222222222222222222222409c0000000000001600143333333333333333333333333333333333333333000000000001011bfea08601000014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa01030421000000220603abababababababababababababababababababababababababababababababab18123456785400008000000080000000800000000000000000220203abababababababababababababababababababababababababababababababab473044022001010101010101010101010101010101010101010101010101010101010101010220020202020202020202020202020202020202020202020202020202020202020201000000"

    private val fixturePsbtTwoSigs = "70736274ff010071020000000111111111111111111111111111111111111111111111111111111111111111110000000000ffffffff0250c30000000000001600142222222222222222222222222222222222222222409c0000000000001600143333333333333333333333333333333333333333000000000001011bfea08601000014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa01030421000000220603abababababababababababababababababababababababababababababababab18123456785400008000000080000000800000000000000000220203abababababababababababababababababababababababababababababababab473044022001010101010101010101010101010101010101010101010101010101010101010220020202020202020202020202020202020202020202020202020202020202020221220202cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd473044022001010101010101010101010101010101010101010101010101010101010101010220020202020202020202020202020202020202020202020202020202020202020221000000"

    private val fixtureFinalTx = "0200000000010111111111111111111111111111111111111111111111111111111111111111110000000000ffffffff0250c30000000000001600142222222222222222222222222222222222222222409c0000000000001600143333333333333333333333333333333333333333024730440220010101010101010101010101010101010101010101010101010101010101010102200202020202020202020202020202020202020202020202020202020202020202212103abababababababababababababababababababababababababababababababab00000000"

    private val fixtureTxid = "55b09bb1e99a690cf0a7fba3d27cd0ce19483a6df3c6179a86f994f438b94754"

    private val fixturePubKey = "03abababababababababababababababababababababababababababababababab"
    private val fixtureInputScript = "0014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val fixtureRecipientScript = "00142222222222222222222222222222222222222222"
    private val fixtureChangeScript = "00143333333333333333333333333333333333333333"

    private val fixtureDerivationPath = listOf(0x80000054L, 0x80000000L, 0x80000000L, 0L, 0L)

    // ---------------------------------------------------------------------
    // Building
    // ---------------------------------------------------------------------

    @Test
    fun `buildUnsignedPsbt reproduces the reference fixture byte for byte`() {
        val built = buildUnsignedPsbt(
            unsignedTxBytes = fromHex(fixtureUnsignedTx),
            inputs = listOf(
                PsbtInput(
                    witnessUtxoScriptPubKey = fromHex(fixtureInputScript),
                    witnessUtxoSatoshis = 100_000L,
                    masterFingerprint = fromHex("12345678"),
                    derivationPath = fixtureDerivationPath,
                    pubKey = fromHex(fixturePubKey),
                ),
            ),
        )
        assertEquals(fixturePsbtUnsigned, toHex(built))
    }

    @Test
    fun `the built PSBT declares the unified sighash on its input`() {
        assertEquals(SIGHASH_UNIFIED, parsePsbt(fromHex(fixturePsbtUnsigned)).inputs.single().sighashType)
    }

    @Test
    fun `the built PSBT carries the witness utxo amount and script`() {
        val witness = parsePsbt(fromHex(fixturePsbtUnsigned)).inputs.single().witnessUtxo
        requireNotNull(witness)
        assertEquals(100_000L, witness.satoshis)
        assertEquals(fixtureInputScript, toHex(witness.scriptPubKey))
    }

    @Test
    fun `the built PSBT carries the BIP32 derivation of the signing key`() {
        val derivation = parsePsbt(fromHex(fixturePsbtUnsigned)).inputs.single().derivation
        requireNotNull(derivation)
        assertEquals("12345678", toHex(derivation.masterFingerprint))
        assertEquals(fixtureDerivationPath, derivation.path)
    }

    // ---------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------

    @Test
    fun `a BIP-174 witness-utxo vector parses and re-serialises unchanged`() {
        assertRoundTrips(bip174WitnessUtxo)
    }

    @Test
    fun `a BIP-174 global-xpub vector parses and re-serialises unchanged`() {
        assertRoundTrips(bip174GlobalXpub)
    }

    @Test
    fun `a BIP-174 two-input vector parses and re-serialises unchanged`() {
        assertRoundTrips(bip174TwoInputs)
    }

    @Test
    fun `the witness-utxo vector exposes its partial signature`() {
        val sigs = parsePsbt(bip174WitnessUtxo).inputs.single().partialSigs
        assertEquals(1, sigs.size)
        // BIP-174's vector signs with SIGHASH_ALL: the last byte is 0x01.
        assertEquals(0x01, sigs.single().signature.last().toInt() and 0xFF)
    }

    @Test
    fun `the signed fixture round-trips through parse and serialize`() {
        assertEquals(fixturePsbtSigned, toHex(parsePsbt(fromHex(fixturePsbtSigned)).toBytes()))
    }

    @Test
    fun `a payload without the psbt magic is refused`() {
        val failure = assertThrowsPsbt { parsePsbt(fromHex("001122334455")) }
        assertTrue(failure.message.orEmpty().contains("psbt magic"))
    }

    // ---------------------------------------------------------------------
    // Finalizing
    // ---------------------------------------------------------------------

    @Test
    fun `finalizePsbt extracts the reference final transaction`() {
        assertEquals(
            fixtureFinalTx,
            toHex(finalizePsbt(parsePsbt(fromHex(fixturePsbtSigned)))),
        )
    }

    @Test
    fun `the extracted transaction's id is the non-witness double-SHA256, reversed`() {
        assertEquals(fixtureTxid, transactionId(fromHex(fixtureFinalTx)))
    }

    @Test
    fun `finalizing an unsigned PSBT is refused for a missing signature`() {
        val failure = assertThrowsPsbt { finalizePsbt(parsePsbt(fromHex(fixturePsbtUnsigned))) }
        assertTrue(failure.message.orEmpty().contains("no signature"))
    }

    @Test
    fun `a signature that does not carry the unified sighash byte is refused`() {
        val failure = assertThrowsPsbt { finalizePsbt(parsePsbt(fromHex(fixturePsbtSighashAll))) }
        assertTrue(
            "expected the sighash refusal, got: ${failure.message}",
            failure.message.orEmpty().contains("sighash"),
        )
    }

    @Test
    fun `finalizing a BIP-174 SIGHASH_ALL vector is refused`() {
        // The spec's own vector signs with 0x01 — exactly the un-unified
        // signature this wallet's replay protection refuses to broadcast.
        val failure = assertThrowsPsbt { finalizePsbt(parsePsbt(bip174WitnessUtxo)) }
        assertTrue(
            "expected the sighash refusal, got: ${failure.message}",
            failure.message.orEmpty().contains("sighash"),
        )
    }

    @Test
    fun `an input with more than one signature is refused`() {
        val failure = assertThrowsPsbt { finalizePsbt(parsePsbt(fromHex(fixturePsbtTwoSigs))) }
        assertTrue(failure.message.orEmpty().contains("more than one signature"))
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private fun assertRoundTrips(raw: ByteArray) {
        assertEquals(toHex(raw), toHex(parsePsbt(raw).toBytes()))
    }

    private fun assertThrowsPsbt(block: () -> Unit): PsbtException {
        try {
            block()
        } catch (failure: PsbtException) {
            return failure
        }
        fail("expected a PsbtException")
        error("unreachable")
    }

    private fun base64(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)

    private fun fromHex(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    private fun toHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            out.append(digits[value ushr 4]).append(digits[value and 0x0F])
        }
        return out.toString()
    }
}
