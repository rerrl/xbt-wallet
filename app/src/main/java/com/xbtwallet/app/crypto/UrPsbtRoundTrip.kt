package com.xbtwallet.app.crypto

import com.sparrowwallet.hummingbird.ResultType
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoPSBT

/**
 * BC-UR for transactions: the `crypto-psbt` export and import, the mirror image
 * of the `crypto-account` path in [UrAccountImport].
 *
 * [UrAccountImport] is how a signer's account key arrives; this is how a
 * transaction leaves for the signer and comes back signed. Both directions are
 * the same machinery — a fountain-coded animation of QR frames, any sufficiently
 * large subset of which reconstructs one message — with a different UR type and
 * a different payload inside it (`crypto-psbt`, a CBOR byte string of the raw
 * PSBT).
 *
 * **One dependency, both directions.** hummingbird is already on the classpath
 * for the account import, so the PSBT path adds no new artifact: the same
 * `UREncoder`/`URDecoder` that read a SeedSigner's xpub animation write and read
 * the signing round-trip's.
 *
 * **Android-free**, exactly like [UrAccountImport]: the encoder hands back plain
 * strings and the assembler is a plain sealed result, so the whole animation is
 * driven frame by frame in a JVM test with no camera and no emulator.
 */

/** What one scanned QR frame did to the PSBT assembly. */
sealed interface UrPsbtOutcome {

    /** A usable part; the message is still incomplete. [percent] is the estimate. */
    data class Progress(val percent: Int) : UrPsbtOutcome

    /** The PSBT is complete: the raw BIP-174 bytes a signer produced. */
    data class Complete(val psbtBytes: ByteArray) : UrPsbtOutcome

    /** This frame cannot be part of a signed transaction — [message] says why, to show verbatim. */
    data class Rejected(val message: String) : UrPsbtOutcome
}

/**
 * Encodes a PSBT as a `crypto-psbt` UR, and hands out its animation frames.
 *
 * A short PSBT that fits one frame comes back as a **single static** UR string
 * ([isSingleFrame] is true and [parts] holds one entry) — the common case for
 * the small, single-input transactions this wallet builds, and the one a user
 * simply holds still. A longer one is split into fountain-coded frames
 * ([maxFragmentLength] bytes each) that the screen cycles through.
 *
 * The parts are collected once, up front: `nextPart()` walks a deterministic
 * sequence, so an animation can loop over [parts] forever without the screen
 * holding (or re-deriving) encoder state.
 */
class UrPsbtEncoder(psbtBytes: ByteArray) {

    private val ur: UR = CryptoPSBT(psbtBytes).toUR()

    private val encoder = UREncoder(ur, MAX_FRAGMENT_LENGTH, MIN_FRAGMENT_LENGTH, 0L)

    /** True when the whole PSBT fits one QR — the caller renders it statically. */
    val isSingleFrame: Boolean = encoder.isSinglePart

    /** Every frame of the animation, in sequence. One entry when [isSingleFrame]. */
    val parts: List<String> = List(encoder.seqLen.coerceAtLeast(1)) { encoder.nextPart() }

    companion object {
        /**
         * The largest a single QR frame may be, in bytes of the message. 200 is
         * comfortably inside what a phone camera resolves at the size this app
         * draws its QR — a bigger frame is denser and harder to read, a smaller
         * one is more frames and a longer animation.
         */
        const val MAX_FRAGMENT_LENGTH = 200

        /** The smallest frame hummingbird may pick; anything at least 1 is valid. */
        const val MIN_FRAGMENT_LENGTH = 1
    }
}

/**
 * Assembles the frames of one signed PSBT and decodes the payload inside them.
 *
 * The same discipline as [UrAccountAssembler]: the payload is **classified
 * before** it reaches the decoder, because hummingbird's decoder silently
 * ignores anything it cannot use and "silently ignore it" is the wrong answer
 * for a camera pointed at the wrong QR. Here the likeliest mistake is an xpub
 * export in front of a PSBT screen, so that QR gets its own sentence.
 *
 * One instance tracks one scan; [reset] starts it over.
 */
class UrPsbtAssembler {

    private var decoder = URDecoder()

    /** The fountain decoder's estimate of how much of the message is in hand, 0–100. */
    val percent: Int
        get() = Math.round(decoder.estimatedPercentComplete * 100).toInt().coerceIn(0, 100)

    /** Throws away everything assembled so far — a scan abandoned starts clean. */
    fun reset() {
        decoder = URDecoder()
    }

    /**
     * Offers one scanned QR frame to the assembly.
     *
     * A non-UR payload and a UR of another type are both [UrPsbtOutcome.Rejected]
     * with a message naming what was actually seen; only a `crypto-psbt` frame
     * reaches the decoder. A frame the fountain decoder cannot use is not an
     * error — losing frames is what a fountain code is for — so it reports the
     * unchanged progress and the scan continues.
     */
    fun receive(payload: String): UrPsbtOutcome {
        val trimmed = payload.trim()
        val type = UrAccountAssembler.urTypeOf(trimmed)
            ?: return UrPsbtOutcome.Rejected(NOT_A_TRANSACTION_QR)
        if (type != CRYPTO_PSBT) return UrPsbtOutcome.Rejected(messageForForeignType(type))

        // Already complete: the camera may hand us another frame before the panel
        // is dismissed, and re-decoding it would be wasted work.
        if (decoder.result != null) return UrPsbtOutcome.Progress(percent)

        decoder.receivePart(trimmed)
        val result = decoder.result ?: return UrPsbtOutcome.Progress(percent)
        if (result.type != ResultType.SUCCESS) return UrPsbtOutcome.Rejected(DID_NOT_DECODE)

        val ur = result.ur ?: return UrPsbtOutcome.Rejected(DID_NOT_DECODE)
        return decode(ur)
    }

    /** CBOR → the raw PSBT bytes, or the reason it cannot be read. */
    private fun decode(ur: UR): UrPsbtOutcome {
        val psbt = try {
            (ur.decodeFromRegistry() as? CryptoPSBT)?.psbt
                ?: return UrPsbtOutcome.Rejected(UNREADABLE)
        } catch (e: UR.URException) {
            return UrPsbtOutcome.Rejected(UNREADABLE)
        } catch (e: RuntimeException) {
            // hummingbird wraps most malformed CBOR in URException; a class-cast
            // or arithmetic failure escapes as a plain RuntimeException.
            return UrPsbtOutcome.Rejected(UNREADABLE)
        }
        return UrPsbtOutcome.Complete(psbt)
    }

    companion object {

        /** The UR type this screen is for. */
        const val CRYPTO_PSBT = "crypto-psbt"

        /**
         * The message for a QR that is not a UR at all — a bare address, a
         * `bitcoin:` URI, a web link.
         */
        const val NOT_A_TRANSACTION_QR =
            "This is not a signed transaction QR \u2014 scan the animated QR your signer shows for the signed PSBT."

        /** The animation ended without the fountain decoder rebuilding the message. */
        const val DID_NOT_DECODE =
            "The signed transaction did not decode completely \u2014 scan it again."

        /** Decoded as a UR but its CBOR could not be read. */
        const val UNREADABLE =
            "The signed transaction could not be read \u2014 the QR may be damaged. Scan it again."

        /**
         * The message for a UR of another type. The account-export QR is the
         * likeliest mistake here — a user who just finished importing a wallet
         * has that animation on their signer — so it is named specifically.
         */
        fun messageForForeignType(type: String): String = when (type) {
            UrAccountAssembler.CRYPTO_ACCOUNT -> "That's an xpub export, not a signed transaction."
            else -> "That is a $type QR, not a signed transaction."
        }
    }
}
