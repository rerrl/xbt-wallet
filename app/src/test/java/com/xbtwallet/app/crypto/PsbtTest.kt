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

    /**
     * BIP-174 **invalid** vector: "PSBT with invalid input witness utxo typed key".
     * The input's `0x01` key carries key data (`0100`), which the spec forbids —
     * `PSBT_IN_WITNESS_UTXO`'s keydata is "None".
     */
    private val bip174InvalidWitnessUtxoKey = base64("cHNidP8BAFUCAAAAASeaIyOl37UfxF8iD6WLD8E+HjNCeSqF1+Ns1jM7XLw5AAAAAAD/////AaBa6gsAAAAAGXapFP/pwAYQl8w7Y28ssEYPpPxCfStFiKwAAAAAAAIBACCVXuoLAAAAABepFGNFIA9o0YnhrcDfHE0W6o8UwNvrhyICA7E0HMunaDtq9PEjjNbpfnFn1Wn6xH8eSNR1QYRDVb1GRjBDAiAEJLWO/6qmlOFVnqXJO7/UqJBkIkBVzfBwtncUaUQtBwIfXI6w/qZRbWC4rLM61k7eYOh4W/s6qUuZvfhhUduamgEBBCIAIHcf0YrUWWZt1J89Vk49vEL0yEd042CtoWgWqO1IjVaBAQVHUiEDsTQcy6doO2r08SOM1ul+cWfVafrEfx5I1HVBhENVvUYhA95V0eHayAXj+KWMH7+blMAvPbqv4Sf+/KSZXyb4IIO9Uq4iBgOxNBzLp2g7avTxI4zW6X5xZ9Vp+sR/HkjUdUGEQ1W9RhC0prpnAAAAgAAAAIAEAACAIgYD3lXR4drIBeP4pYwfv5uUwC89uq/hJ/78pJlfJvggg70QtKa6ZwAAAIAAAACABQAAgAAA")

    // ---------------------------------------------------------------------
    // The generated fixture (a separate reference implementation, not this code):
    // a 2-output transaction spending one P2WPKH coin, its unsigned PSBT, the
    // same PSBT with one partial signature ending in the unified sighash byte
    // 0x21, the same PSBT with that byte changed to 0x01, the same PSBT with a
    // second partial signature, and the final segwit serialization.
    // ---------------------------------------------------------------------

    private val fixtureUnsignedTx = "020000000111111111111111111111111111111111111111111111111111111111111111110000000000ffffffff0250c30000000000001600142222222222222222222222222222222222222222409c000000000000160014333333333333333333333333333333333333333300000000"

    private val fixturePsbtUnsigned = "70736274ff010071020000000111111111111111111111111111111111111111111111111111111111111111110000000000ffffffff0250c30000000000001600142222222222222222222222222222222222222222409c0000000000001600143333333333333333333333333333333333333333000000000001011fa086010000000000160014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa01030421000000220603abababababababababababababababababababababababababababababababab18123456785400008000000080000000800000000000000000000000"

    private val fixturePsbtSigned = "70736274ff010071020000000111111111111111111111111111111111111111111111111111111111111111110000000000ffffffff0250c30000000000001600142222222222222222222222222222222222222222409c0000000000001600143333333333333333333333333333333333333333000000000001011fa086010000000000160014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa01030421000000220603abababababababababababababababababababababababababababababababab18123456785400008000000080000000800000000000000000220203abababababababababababababababababababababababababababababababab473044022001010101010101010101010101010101010101010101010101010101010101010220020202020202020202020202020202020202020202020202020202020202020221000000"

    private val fixturePsbtSighashAll = "70736274ff010071020000000111111111111111111111111111111111111111111111111111111111111111110000000000ffffffff0250c30000000000001600142222222222222222222222222222222222222222409c0000000000001600143333333333333333333333333333333333333333000000000001011fa086010000000000160014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa01030421000000220603abababababababababababababababababababababababababababababababab18123456785400008000000080000000800000000000000000220203abababababababababababababababababababababababababababababababab473044022001010101010101010101010101010101010101010101010101010101010101010220020202020202020202020202020202020202020202020202020202020202020201000000"

    private val fixturePsbtTwoSigs = "70736274ff010071020000000111111111111111111111111111111111111111111111111111111111111111110000000000ffffffff0250c30000000000001600142222222222222222222222222222222222222222409c0000000000001600143333333333333333333333333333333333333333000000000001011fa086010000000000160014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa01030421000000220603abababababababababababababababababababababababababababababababab18123456785400008000000080000000800000000000000000220203abababababababababababababababababababababababababababababababab473044022001010101010101010101010101010101010101010101010101010101010101010220020202020202020202020202020202020202020202020202020202020202020221220202cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd473044022001010101010101010101010101010101010101010101010101010101010101010220020202020202020202020202020202020202020202020202020202020202020221000000"

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
        val derivation = parsePsbt(fromHex(fixturePsbtUnsigned)).inputs.single().derivations.single()
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
    // The typed fields, against the specification rather than against ourselves
    //
    // Every field below is written and read back by this wallet, which is why a
    // round-trip test alone proves nothing about its *encoding*: a field written
    // and read the same wrong way round-trips perfectly. These tests pin the
    // bytes, and read the spec's own vectors, instead.
    // ---------------------------------------------------------------------

    /**
     * The spec's own `witness_utxo`, read as a typed value: an 8-byte
     * little-endian amount, a compact-size scriptPubKey length, then the script.
     *
     * This is the regression that matters. Reading the same bytes as a compact
     * size takes the amount's first byte, `0x95`, as the number 149 and folds the
     * length byte into the script — so the wallet would hand a signer a 149-sat
     * coin and, for any coin of 253 sats or more, produce a PSBT no conformant
     * signer can read at all.
     */
    @Test
    fun `a BIP-174 witness utxo is an 8-byte amount then a length-prefixed script`() {
        val witness = requireNotNull(parsePsbt(bip174WitnessUtxo).inputs.single().witnessUtxo)

        assertEquals(199_909_013L, witness.satoshis)

        // The value's bytes exactly as the spec writes them: the amount, then the
        // script's length (`0x17`), then the script.
        assertEquals(
            "955eea0b0000000017a9146345200f68d189e1adc0df1c4d16ea8f14c0dbeb87",
            toHex(witnessUtxoValue(bip174WitnessUtxo)),
        )

        // The scriptPubKey is the 23 bytes *after* that length prefix — a P2SH
        // `a914…87`, not a 24-byte blob starting with 0x17.
        assertEquals(23, witness.scriptPubKey.size)
        assertEquals("a9146345200f68d189e1adc0df1c4d16ea8f14c0dbeb87", toHex(witness.scriptPubKey))
    }

    @Test
    fun `a built witness utxo is an 8-byte amount then a length-prefixed script`() {
        // 100,000 sats is `a086010000000000` little-endian, then `0x16` (22) and the
        // script — never the five-byte compact size `fea0860100`, which is what
        // this wallet used to write and what no other implementation reads.
        assertEquals(
            "a086010000000000" + "16" + fixtureInputScript,
            toHex(witnessUtxoValue(fromHex(fixturePsbtUnsigned))),
        )
    }

    @Test
    fun `the signed fixture carries the same corrected witness utxo`() {
        val witness = requireNotNull(parsePsbt(fromHex(fixturePsbtSigned)).inputs.single().witnessUtxo)
        assertEquals(100_000L, witness.satoshis)
        assertEquals(fixtureInputScript, toHex(witness.scriptPubKey))
    }

    @Test
    fun `the corrected unsigned fixture round-trips`() {
        assertRoundTrips(fromHex(fixturePsbtUnsigned))
    }

    @Test
    fun `a witness utxo whose amount is compact-size encoded is refused, not mis-read`() {
        // How this wallet wrote the field before the fix: `fe a0 86 01 00`, the
        // compact size of 100,000, with no script length prefix at all. A reader
        // that accepts it is a reader that will mis-read a spec-conformant one.
        val legacy = fromHex(
            fixturePsbtUnsigned.replace(
                "011fa086010000000000160014aaaa",
                "011bfea08601000014aaaa",
            ),
        )
        val failure = assertThrowsPsbt { parsePsbt(legacy) }
        assertTrue(
            "expected a witness_utxo refusal, got: ${failure.message}",
            failure.message.orEmpty().contains("witness_utxo"),
        )
    }

    @Test
    fun `a witness utxo key that carries key data is refused`() {
        // BIP-174's own "invalid input witness utxo typed key" vector: the map key
        // is `01 00` — keytype 0x01 with key data, and the spec allows none.
        val failure = assertThrowsPsbt { parsePsbt(bip174InvalidWitnessUtxoKey) }
        assertTrue(
            "expected a witness_utxo key refusal, got: ${failure.message}",
            failure.message.orEmpty().contains("witness_utxo"),
        )
    }

    @Test
    fun `a sighash type that is not four bytes is refused`() {
        // `0x03`'s valuedata is a 32-bit little-endian uint; eight bytes is not one.
        val ragged = fromHex(
            fixturePsbtUnsigned.replace("01030421000000", "0103082100000000000000"),
        )
        val failure = assertThrowsPsbt { parsePsbt(ragged) }
        assertTrue(
            "expected a sighash refusal, got: ${failure.message}",
            failure.message.orEmpty().contains("sighash"),
        )
    }

    @Test
    fun `the spec vector's derivations keep their fingerprints and uint32 paths`() {
        val derivations = parsePsbt(bip174WitnessUtxo).inputs.single().derivations
        assertEquals(2, derivations.size)
        assertEquals(listOf("b4a6ba67", "b4a6ba67"), derivations.map { toHex(it.masterFingerprint) })
        assertEquals(listOf(0x80000000L, 0x80000000L, 0x80000004L), derivations[0].path)
        assertEquals(listOf(0x80000000L, 0x80000000L, 0x80000005L), derivations[1].path)
    }

    @Test
    fun `a derivation whose path is not whole uint32 steps is refused`() {
        // `0x06`'s valuedata is a fingerprint then whole 32-bit little-endian steps;
        // two stray bytes must not be silently dropped.
        val ragged = fromHex(
            fixturePsbtUnsigned.replace(
                "18123456785400008000000080000000800000000000000000",
                "1a123456785400008000000080000000800000000000000000abcd",
            ),
        )
        val failure = assertThrowsPsbt { parsePsbt(ragged) }
        assertTrue(
            "expected a derivation refusal, got: ${failure.message}",
            failure.message.orEmpty().contains("derivation"),
        )
    }

    // ---------------------------------------------------------------------
    // Marking the change output (PSBT_OUT_BIP32_DERIVATION, 0x02)
    // ---------------------------------------------------------------------

    /** The change key's 33-byte compressed public key, as the fixture states it. */
    private val fixtureChangePubKey = "03" + "cd".repeat(32)

    /** `m/84'/0'/0'/1/0` — the account path, the change chain, change index 0. */
    private val fixtureChangePath = listOf(0x80000054L, 0x80000000L, 0x80000000L, 1L, 0L)

    /**
     * [fixturePsbtUnsigned] with the change output's map filled in.
     *
     * Written out by hand from BIP-174's encoding — key `0x02 || pubkey`, value
     * `<4-byte fingerprint><uint32 LE per path step>` — rather than produced by
     * the builder under test, so the byte comparison below proves the builder
     * writes what the spec says and not merely what this file's reader expects.
     * The change output is index 1, so the map lands between the two output
     * terminators.
     */
    private val fixturePsbtUnsignedWithChange =
        "70736274ff010071020000000111111111111111111111111111111111111111111111111111111111111111110000000000ffffffff0250c30000000000001600142222222222222222222222222222222222222222409c0000000000001600143333333333333333333333333333333333333333000000000001011fa086010000000000160014aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa01030421000000220603abababababababababababababababababababababababababababababababab181234567854000080000000800000008000000000000000000000220203cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd1812345678540000800000008000000080010000000000000000"

    private fun buildFixturePsbt(
        change: Bip32Derivation? = null,
        changeOutputIndex: Int? = null,
    ): ByteArray = buildUnsignedPsbt(
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
        change = change,
        changeOutputIndex = changeOutputIndex,
    )

    private fun changeDerivation() = Bip32Derivation(
        masterFingerprint = fromHex("12345678"),
        path = fixtureChangePath,
        pubKey = fromHex(fixtureChangePubKey),
    )

    @Test
    fun `a PSBT with change reproduces the change-marked fixture byte for byte`() {
        assertEquals(
            fixturePsbtUnsignedWithChange,
            toHex(buildFixturePsbt(changeDerivation(), changeOutputIndex = 1)),
        )
    }

    @Test
    fun `the change output map carries exactly one 0x02 key-value and no other output does`() {
        val psbt = parsePsbt(buildFixturePsbt(changeDerivation(), changeOutputIndex = 1))

        assertTrue("output 0 must stay unmarked", psbt.outputKeyValues[0].isEmpty())
        assertEquals(1, psbt.outputKeyValues[1].size)

        val changeKeyValue = psbt.outputKeyValues[1].single()
        assertEquals("02$fixtureChangePubKey", toHex(changeKeyValue.key))
        assertEquals(
            "12345678" +
                "54000080" +
                "00000080" +
                "00000080" +
                "01000000" +
                "00000000",
            toHex(changeKeyValue.value),
        )
    }

    @Test
    fun `the parsed outputs expose the change derivation as a typed value`() {
        val output = parsePsbt(buildFixturePsbt(changeDerivation(), changeOutputIndex = 1)).outputs[1]
        val derivation = output.derivations.single()
        assertEquals("12345678", toHex(derivation.masterFingerprint))
        assertEquals(fixtureChangePath, derivation.path)
        assertEquals(fixtureChangePubKey, toHex(derivation.pubKey!!))
    }

    @Test
    fun `a PSBT without change leaves every output map empty`() {
        val psbt = parsePsbt(buildFixturePsbt())
        assertEquals(2, psbt.outputKeyValues.size)
        assertTrue(psbt.outputKeyValues.all { it.isEmpty() })
    }

    @Test
    fun `the signer's own derivation on the change output reproduces its scriptPubKey`() {
        // The account key is m/84'/0'/0'; the change address is its 1/<index>
        // child. A signer holding the seed follows the 0x02 derivation, derives
        // that child and compares HASH160 of the key with the output's program.
        val accountKey = com.xbtwallet.app.crypto.ExtendedPublicKey.parse(
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
        )
        val changeIndex = 7L
        val changeKey = accountKey.derive(listOf(1L, changeIndex))
        val changeScript = com.xbtwallet.app.chain.p2wpkhScriptPubKey(changeKey.publicKey)

        val unsignedTx = com.xbtwallet.app.chain.buildUnsignedTransaction(
            inputs = listOf(com.xbtwallet.app.chain.TxInput(fixtureTxid, 0)),
            outputs = listOf(
                com.xbtwallet.app.chain.TxOutput(50_000L, fixtureRecipientScript),
                com.xbtwallet.app.chain.TxOutput(40_000L, toHex(changeScript)),
            ),
        )
        val accountPath = listOf(0x80000054L, 0x80000000L, 0x80000000L)
        val built = buildUnsignedPsbt(
            unsignedTxBytes = unsignedTx,
            inputs = listOf(
                PsbtInput(
                    witnessUtxoScriptPubKey = fromHex(fixtureInputScript),
                    witnessUtxoSatoshis = 100_000L,
                    masterFingerprint = fromHex("12345678"),
                    derivationPath = fixtureDerivationPath,
                    pubKey = fromHex(fixturePubKey),
                ),
            ),
            change = Bip32Derivation(
                masterFingerprint = fromHex("12345678"),
                path = accountPath + listOf(1L, changeIndex),
                pubKey = changeKey.publicKey,
            ),
            changeOutputIndex = 1,
        )

        val parsed = parsePsbt(built)
        val stated = parsed.outputs[1].derivations.single()
        val derived = accountKey.derive(stated.path.drop(accountPath.size))
        assertTrue(
            "the derivation on the change output must resolve to its own scriptPubKey",
            toHex(com.xbtwallet.app.chain.p2wpkhScriptPubKey(derived.publicKey)) ==
                toHex(parsed.transaction.outputs[1].scriptPubKey),
        )
        assertEquals(
            "the stated pubkey must be the key at the stated path",
            toHex(changeKey.publicKey),
            toHex(stated.pubKey!!),
        )
    }

    @Test
    fun `a change derivation without an output index is refused`() {
        val failure = assertThrowsPsbt { buildFixturePsbt(changeDerivation()) }
        assertTrue(failure.message.orEmpty().contains("change"))
    }

    @Test
    fun `a change output index beyond the transaction's outputs is refused`() {
        val failure = assertThrowsPsbt {
            buildFixturePsbt(changeDerivation(), changeOutputIndex = 2)
        }
        assertTrue(failure.message.orEmpty().contains("change"))
    }

    @Test
    fun `a change derivation without a public key is refused`() {
        val failure = assertThrowsPsbt {
            buildFixturePsbt(
                Bip32Derivation(fromHex("12345678"), fixtureChangePath),
                changeOutputIndex = 1,
            )
        }
        assertTrue(failure.message.orEmpty().contains("public key"))
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

    /** The raw bytes of the single input's `0x01` value, as they were read. */
    private fun witnessUtxoValue(raw: ByteArray): ByteArray {
        val psbt = parsePsbt(raw)
        return psbt.inputs.single().keyValues
            .first { it.key.size == 1 && it.key[0] == 0x01.toByte() }
            .value
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
