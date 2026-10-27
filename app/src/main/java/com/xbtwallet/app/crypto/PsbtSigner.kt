package com.xbtwallet.app.crypto

/**
 * Signs a PSBT with an in-memory BIP-32 extended private key.
 *
 * The account key handed in lives at **account level** (`m/84'/0'/0'`), which is
 * where a BIP-84 wallet stops being hardened: from there a signer derives the
 * `0/i` receiving and `1/i` change keys with non-hardened steps and signs
 * native-P2WPKH inputs. Every input that carries a matching derivation is signed;
 * an input this wallet cannot honestly sign is refused with a [PsbtException]
 * rather than skipped, because a PSBT that comes back half-signed looks the same
 * as one that came back complete.
 *
 * **Refusals, in the order they matter:**
 *
 * 1. an input whose declared `0x03` sighash is missing or not `0x21` — an
 *    un-unified signature is exactly the replay risk the flag exists to prevent;
 * 2. an input with no `0x01` witness utxo — there is nothing to hash against;
 * 3. an input with no `0x06` BIP32 derivation whose master fingerprint matches
 *    the expected one — the signature would not be ours;
 * 4. a matching derivation whose path does not begin `84'/0'/0'` — a different
 *    account than the key handed in.
 *
 * **Which fingerprint to expect.** BIP-174 records each input's derivation
 * against the **master** key's fingerprint, and that is what a caller here should
 * pass ([masterFingerprint]) — the value a signer record stores and a hardware
 * wallet would report. It defaults to the fingerprint of the [accountKey] handed
 * in, which is what a PSBT built by this app's own earlier code carries, so that
 * call site keeps its exact behaviour.
 *
 * The signature is appended to as `PSBT_IN_PARTIAL_SIG` (key `0x02 || pubkey`,
 * value `<DER signature> 0x21`), so the existing [finalizePsbt] sees a normal
 * unified single-sig input.
 *
 * Android-free and side-effect free: no secret is written anywhere, and a new
 * [Psbt] is returned rather than the argument mutated.
 */
object PsbtSigner {

    /** The account-level prefix every derivation this wallet signs must start with. */
    private val ACCOUNT_PREFIX = listOf(0x80000054L, 0x80000000L, 0x80000000L)

    /**
     * Returns a new [Psbt] with a partial signature inserted on every input.
     *
     * @param masterFingerprint the four-byte BIP-32 master fingerprint the
     *   PSBT's derivations must name, or null to match the [accountKey]'s own
     *   fingerprint (see the class doc).
     * @throws PsbtException when any input cannot be signed (see the refusals above).
     */
    fun signPsbt(
        psbt: Psbt,
        accountKey: ExtendedPrivateKey,
        masterFingerprint: ByteArray? = null,
    ): Psbt {
        val tx = psbt.transaction
        if (psbt.inputs.size != tx.inputs.size) {
            throw PsbtException(
                "the PSBT has ${psbt.inputs.size} input maps but its transaction has ${tx.inputs.size} inputs",
            )
        }

        val fingerprint = masterFingerprint ?: int4(accountKey.fingerprint())
        if (fingerprint.size != 4) {
            throw PsbtException("a master fingerprint is four bytes, got ${fingerprint.size}")
        }

        // The unified signature hash commits to every spent output, so every
        // input's witness utxo is needed before any input can be signed — not
        // just the one being signed. Read them once, up front, so a missing one
        // is refused before a signature exists.
        val spentOutputs = psbt.inputs.mapIndexed { index, input ->
            input.witnessUtxo
                ?: throw PsbtException("input $index has no witness utxo; refusing to sign it")
        }

        val signedInputs = psbt.inputs.mapIndexed { index, input ->
            val sighash = input.sighashType
                ?: throw PsbtException("input $index does not declare a sighash type; refusing to sign it")
            if (sighash != SIGHASH_UNIFIED) {
                throw PsbtException(
                    "input $index declares sighash 0x${sighash.toString(16)}, not the unified 0x21",
                )
            }

            val derivation = input.derivations.firstOrNull { matches(it.masterFingerprint, fingerprint) }
                ?: throw PsbtException(
                    "input $index carries no BIP32 derivation for this account key",
                )
            val pubKey = derivation.pubKey
                ?: throw PsbtException("input $index's derivation does not carry a public key")

            if (derivation.path.size < 3 || derivation.path.subList(0, 3) != ACCOUNT_PREFIX) {
                throw PsbtException(
                    "input $index's derivation path is not under m/84'/0'/0'",
                )
            }

            val child = accountKey.derive(derivation.path.drop(3))
            val digest = Sighash.p2wpkhDigest(tx, index, spentOutputs, SIGHASH_UNIFIED)
            val signature = Ecdsa.sign(child.privateKey, digest) + byteArrayOf(SIGHASH_UNIFIED.toByte())

            ParsedPsbtInput(
                keyValues = input.keyValues + PsbtKeyValue(byteArrayOf(0x02) + pubKey, signature),
                witnessUtxo = input.witnessUtxo,
                sighashType = input.sighashType,
                derivations = input.derivations,
                partialSigs = input.partialSigs + PartialSig(pubKey, signature),
            )
        }

        return Psbt(
            unsignedTxBytes = psbt.unsignedTxBytes,
            globalKeyValues = psbt.globalKeyValues,
            inputs = signedInputs,
            outputKeyValues = psbt.outputKeyValues,
        )
    }

    /**
     * Whether a derivation's 4-byte fingerprint equals [fingerprint], byte for
     * byte. BIP-32 fingerprints are compared in the order they are written, so
     * this is an array equality rather than an endian-aware integer compare.
     */
    private fun matches(bytes: ByteArray, fingerprint: ByteArray): Boolean =
        bytes.size == fingerprint.size && bytes.contentEquals(fingerprint)

    /** An [Int] fingerprint as the four big-endian bytes a PSBT derivation carries. */
    private fun int4(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
