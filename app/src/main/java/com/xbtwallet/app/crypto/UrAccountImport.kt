package com.xbtwallet.app.crypto

import com.sparrowwallet.hummingbird.ResultType
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import com.sparrowwallet.hummingbird.registry.CryptoOutput
import com.sparrowwallet.hummingbird.registry.ScriptExpression

/**
 * The **signing-device** import: BC-UR, not Base58.
 *
 * A signer that speaks BC-UR — SeedSigner's *Export Xpub → single sig → native
 * segwit → Animated*, Keystone, Jade — does not show a bare xpub. It shows a
 * `crypto-account` payload: a master fingerprint plus a set of output
 * descriptors, one per script type the signer knows how to export. Long payloads
 * are split into an animation of fountain-coded QR frames, any subset of which
 * reconstructs the message once enough have been seen.
 *
 * That is a different *format*, not a different key, so it gets its own decoder
 * rather than another branch in [XpubScanRoute]: pointing the watch-only path's
 * scanner at an animated export can only ever produce "not valid Base58", which
 * is true and useless.
 *
 * **Android-free on purpose.** hummingbird is plain Java, so everything here —
 * frame assembly, CBOR decode, xpub reconstruction — runs on the JVM and is
 * unit-tested without a camera or an emulator. Nothing in this file imports an
 * Android type; the registry is reached only through hummingbird's own decoder,
 * and the single seam the UI needs ([UrAccountAssembler.receive] returning an
 * [UrScanOutcome]) is a plain sealed interface, so a test can drive the whole
 * state machine frame by frame.
 *
 * **What comes out.** Exactly what the text path stores: a Base58 extended public
 * key, the master fingerprint and the derivation path. The script-type choice is
 * made here rather than asked of the user — the app's account model is native
 * segwit, so the `wpkh` descriptor is the one taken (see [nativeSegwitDescriptor]).
 */

/** What one scanned QR frame did to the assembly. */
sealed interface UrScanOutcome {

    /**
     * The frame was a usable part of the export and the message is still
     * incomplete. [percent] is the fountain decoder's own estimate (0–100), for
     * the progress indicator; it is an estimate by construction, since the
     * decoder learns how many parts there are as it goes.
     */
    data class Progress(val percent: Int) : UrScanOutcome

    /** The export is complete and decoded into an account. */
    data class Complete(val account: UrAccount) : UrScanOutcome

    /**
     * This frame cannot be part of a signing-device export — a different QR was
     * in front of the camera, or the payload is not a UR at all. [message] is
     * shown to the user verbatim.
     */
    data class Rejected(val message: String) : UrScanOutcome
}

/**
 * A decoded `crypto-account`: the key to store, plus the metadata the signing
 * flow will want and anything worth telling the user before it is saved.
 *
 * @property xpub the account key, re-encoded as a **zpub** — native segwit, the
 *   app's account model. See [UrAccountAssembler] for how it is rebuilt from the
 *   UR's key material.
 * @property masterFingerprint eight lowercase hex digits, or null when the export
 *   carried none (per BCR-2020-015 the key origin's fingerprint wins; the
 *   account's own fingerprint is the fallback).
 * @property derivationPath the account path without a leading `m/` (`84'/0'/0'`),
 *   or null when the export carried no origin. Same shape the text path stores.
 * @property warnings things that did not stop the import but are worth saying —
 *   today, a missing fingerprint or derivation path. Empty in the common case.
 */
data class UrAccount(
    val xpub: String,
    val masterFingerprint: String?,
    val derivationPath: String?,
    val warnings: List<String> = emptyList(),
)

/**
 * Assembles the QR frames of one signing-device export and decodes the account
 * inside it.
 *
 * One instance tracks one scan: it is stateful across frames (that is the whole
 * point of a fountain code — the frames may arrive in any order, and any
 * sufficiently large subset is enough), and [reset] returns it to the start for
 * the next attempt.
 *
 * **Not a `URDecoder` wrapper for its own sake.** hummingbird's decoder silently
 * returns `false` for anything it cannot use — a foreign QR, a `crypto-psbt`
 * frame, a torn frame — and "silently ignore it" is exactly the wrong behaviour
 * for a user pointing a camera at something. So the type is classified *before*
 * the frame is handed over, and the three outcomes a user can act on (not an
 * export QR / wrong kind of UR / complete) are distinguished here rather than
 * being flattened into a boolean.
 */
class UrAccountAssembler {

    private var decoder = URDecoder()

    /** The fountain decoder's estimate of how much of the message is in hand. */
    val percent: Int
        get() = Math.round(decoder.estimatedPercentComplete * 100).toInt().coerceIn(0, 100)

    /** Throws away everything assembled so far. A scan that is abandoned starts clean. */
    fun reset() {
        decoder = URDecoder()
    }

    /**
     * Offers one scanned QR frame to the assembly.
     *
     * The payload is classified first: anything that is not a `ur:` string is
     * [UrScanOutcome.Rejected] with the "this is not a signing-device export QR"
     * message — *not* a Base58 complaint — and a UR of any other type is rejected
     * with a message naming what it actually is. Only a `crypto-account` frame
     * reaches the decoder.
     *
     * A frame the fountain decoder cannot use (a torn read, a part whose
     * fragment length disagrees with the rest) is **not** an error: losing frames
     * is what a fountain code is for, so it reports the unchanged progress and
     * the scan carries on. Completion is decided by the decoder, never by a part
     * count, so the single-frame (static) export and the animated one follow the
     * same path.
     */
    fun receive(payload: String): UrScanOutcome {
        val trimmed = payload.trim()
        val type = urTypeOf(trimmed) ?: return UrScanOutcome.Rejected(NOT_A_SIGNING_DEVICE_EXPORT)
        if (type != CRYPTO_ACCOUNT) return UrScanOutcome.Rejected(messageForForeignType(type))

        // Already complete: the camera may well hand us another frame before the
        // panel is dismissed, and re-decoding it would be wasted work.
        if (decoder.result != null) return UrScanOutcome.Progress(percent)

        decoder.receivePart(trimmed)
        val result = decoder.result ?: return UrScanOutcome.Progress(percent)
        if (result.type != ResultType.SUCCESS) return UrScanOutcome.Rejected(EXPORT_DID_NOT_DECODE)

        val ur = result.ur ?: return UrScanOutcome.Rejected(EXPORT_DID_NOT_DECODE)
        return decodeAccount(ur)
    }

    /** CBOR → [UrAccount], or the reason it cannot be read. */
    private fun decodeAccount(ur: UR): UrScanOutcome {
        val account = try {
            ur.decodeFromRegistry() as? CryptoAccount
                ?: return UrScanOutcome.Rejected(EXPORT_DID_NOT_DECODE)
        } catch (e: UR.URException) {
            return UrScanOutcome.Rejected(EXPORT_UNREADABLE)
        } catch (e: RuntimeException) {
            // hummingbird wraps most malformed CBOR in URException, but a
            // class-cast or arithmetic failure escapes as a plain RuntimeException.
            return UrScanOutcome.Rejected(EXPORT_UNREADABLE)
        }

        val output = nativeSegwitDescriptor(account.outputDescriptors)
            ?: return UrScanOutcome.Rejected(NO_NATIVE_SEGWIT_ACCOUNT)
        val hdKey = output.hdKey ?: return UrScanOutcome.Rejected(NO_NATIVE_SEGWIT_ACCOUNT)

        val key = hdKey.key
        val chainCode = hdKey.chainCode
        if (key == null || key.size != 33 || chainCode == null || chainCode.size != 32) {
            return UrScanOutcome.Rejected(EXPORT_UNREADABLE)
        }

        // The key origin, when the signer included one. Its fingerprint is the
        // source of truth (BCR-2020-015); the account-level fingerprint is the
        // fallback for a descriptor that omitted it.
        val origin = hdKey.origin
        val path = origin?.path?.takeIf { it.isNotBlank() }
        val fingerprintBytes = origin?.sourceFingerprint ?: account.masterFingerprint
        val fingerprint = fingerprintBytes?.takeIf { it.size == 4 }?.toHex()

        val extendedKey = ExtendedPublicKey(
            version = XpubVersion.ZPUB,
            depth = pathDepth(path),
            parentFingerprint = hdKey.parentFingerprint.toIntBigEndian(),
            childNumber = childNumberOf(path),
            chainCode = chainCode,
            publicKey = key,
        )
        val xpub = extendedKey.reencode(XpubVersion.ZPUB)

        // The UR carried raw key material, so the rebuilt key is re-read through
        // the same validator a typed or text-QR key goes through. It should never
        // fail, and if it does the export is not usable rather than half-usable.
        if (validateXpubInput(xpub) !is XpubImport.Valid) {
            return UrScanOutcome.Rejected(EXPORT_UNREADABLE)
        }

        return UrScanOutcome.Complete(
            UrAccount(
                xpub = xpub,
                masterFingerprint = fingerprint,
                derivationPath = path,
                warnings = warningsFor(fingerprint, path),
            ),
        )
    }

    companion object {

        /** The UR type this screen is for. */
        const val CRYPTO_ACCOUNT = "crypto-account"

        /**
         * The message for a QR that is not a UR at all — a bare xpub, a `bitcoin:`
         * URI, a plain-text PSBT, a web link.
         *
         * Deliberately *not* a Base58 error: the user is on the signing-device
         * screen, so what is wrong is the QR, not the key inside it.
         */
        const val NOT_A_SIGNING_DEVICE_EXPORT =
            "This is not a signing-device export QR \u2014 scan the animated export your signer shows."

        /** A UR of the wrong type, in general. */
        const val WRONG_UR_TYPE_PREFIX = "That is a "

        /** A transaction QR specifically, which is the likeliest mistake on this screen. */
        const val TRANSACTION_QR_MESSAGE =
            "That's a transaction QR, not an xpub export."

        /** The animation ended without the fountain decoder being able to rebuild the message. */
        const val EXPORT_DID_NOT_DECODE =
            "The animated export did not decode completely \u2014 scan it again."

        /** The payload decoded as a UR but its CBOR could not be read. */
        const val EXPORT_UNREADABLE =
            "The signing device's export could not be read \u2014 the QR may be damaged. Scan it again."

        /** The export decoded, but holds no native segwit account to import. */
        const val NO_NATIVE_SEGWIT_ACCOUNT =
            "This export has no native segwit (wpkh) account to import."

        /** Shown when the export carried no derivation path. */
        const val NO_DERIVATION_PATH_WARNING =
            "This export carries no derivation path \u2014 fine for watching; the signing flow may ask for a richer QR later."

        /** Shown when the export carried no master fingerprint. */
        const val NO_FINGERPRINT_WARNING =
            "This export carries no master fingerprint \u2014 fine for watching; the signing flow may ask for a richer QR later."

        private const val UR_PREFIX = "ur:"

        /**
         * The UR type of a payload, lowercased, or null when the payload is not a
         * `ur:<type>/…` string.
         *
         * Read off the text rather than asked of hummingbird because the type is
         * what decides *which message the user gets*: the decoder's own parser
         * treats "not a UR" and "a UR of some other type" identically (both are
         * just a part it will not use).
         */
        fun urTypeOf(payload: String): String? {
            val trimmed = payload.trim()
            if (!trimmed.regionMatches(0, UR_PREFIX, 0, UR_PREFIX.length, ignoreCase = true)) return null
            val rest = trimmed.substring(UR_PREFIX.length)
            if (!rest.contains('/')) return null
            return rest.substringBefore('/').lowercase().ifEmpty { null }
        }

        /** The message for a UR that is not a `crypto-account`. */
        fun messageForForeignType(type: String): String = when (type) {
            "crypto-psbt", "psbt" -> TRANSACTION_QR_MESSAGE
            else -> "$WRONG_UR_TYPE_PREFIX$type QR, not a signing-device xpub export."
        }

        /**
         * The native segwit (`wpkh`) descriptor to import.
         *
         * A `crypto-account` normally carries one descriptor per script type the
         * signer can export — `pkh`, `sh(wpkh)`, `wpkh`, the multisig cosigners,
         * `tr`. The app derives native segwit only, so `wpkh` is the one that
         * matches its account model; a descriptor is `wpkh` when its script
         * expression list is exactly `[witness-public-key-hash]` (a `sh(wpkh)` is
         * `[script-hash, witness-public-key-hash]` and is a *different* account).
         *
         * When several `wpkh` descriptors are present — a signer exporting more
         * than one account — the BIP-84 one (`m/84'…`) wins, and failing that the
         * first, so the choice is deterministic rather than dependent on the order
         * the CBOR happened to use.
         */
        fun nativeSegwitDescriptor(outputs: List<CryptoOutput>): CryptoOutput? {
            val candidates = outputs.filter { output ->
                output.hdKey != null &&
                    output.scriptExpressions == listOf(ScriptExpression.WITNESS_PUBLIC_KEY_HASH)
            }
            return candidates.firstOrNull { it.hdKey?.origin?.path?.startsWith("84'") == true }
                ?: candidates.firstOrNull()
        }

        /** Depth implied by the origin path: one level per component. */
        private fun pathDepth(path: String?): Int =
            path?.split('/')?.count { it.isNotEmpty() } ?: 0

        /**
         * The last path component as a raw child index, hardened if it was
         * written hardened (`0'`, `0h`, `0H`).
         *
         * The UR does not carry the account key's own child number, so it is
         * reconstructed from the origin path — which is where the signer put it.
         * 0 when there is no path to read it from.
         */
        private fun childNumberOf(path: String?): Long {
            val last = path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: return 0L
            val hardened = last.endsWith("'") || last.endsWith("h") || last.endsWith("H")
            val index = last.dropLast(if (hardened) 1 else 0).toLongOrNull() ?: return 0L
            return if (hardened) index or ExtendedPublicKey.HARDENED_OFFSET else index
        }

        /** Warn-but-allow, matching the manual import's stance on missing metadata. */
        private fun warningsFor(fingerprint: String?, path: String?): List<String> = buildList {
            if (fingerprint == null) add(NO_FINGERPRINT_WARNING)
            if (path == null) add(NO_DERIVATION_PATH_WARNING)
        }
    }
}

/** Lowercase hex, for the four-byte fingerprint. */
private fun ByteArray.toHex(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

/**
 * A four-byte big-endian field as an `Int`, or 0 when absent.
 *
 * BIP-32 stores the parent fingerprint as a 32-bit big-endian value, which is
 * exactly what [ExtendedPublicKey] holds; the UR carries the same four bytes.
 */
private fun ByteArray?.toIntBigEndian(): Int {
    if (this == null || size != 4) return 0
    return ((this[0].toInt() and 0xFF) shl 24) or
        ((this[1].toInt() and 0xFF) shl 16) or
        ((this[2].toInt() and 0xFF) shl 8) or
        (this[3].toInt() and 0xFF)
}
