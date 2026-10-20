package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerOrigin

/**
 * How a signer's key is shown in a list.
 *
 * A full account xpub is ~111 characters — far too wide for a list row, and far
 * too much to make two keys apart at a glance. The snippet keeps the head and
 * the tail, the two ends people actually compare, and elides the middle.
 *
 * This lives in its own file, free of Compose imports, for one reason: the rule
 * is testable Kotlin, not a rendering detail, so a JVM unit test can pin the
 * formatting without standing up a UI.
 */
internal fun shortenedXpub(
    xpub: String,
    head: Int = SHORTENED_HEAD_CHARS,
    tail: Int = SHORTENED_TAIL_CHARS,
): String {
    // Short enough to show whole: eliding would leave less key than the
    // ellipsis it replaced.
    if (xpub.length <= head + tail + 1) return xpub
    return xpub.take(head) + "\u2026" + xpub.takeLast(tail)
}

/** Characters kept from the front of a key — enough to read its version prefix. */
internal const val SHORTENED_HEAD_CHARS = 10

/** Characters kept from the back of a key — enough to tell two keys apart. */
internal const val SHORTENED_TAIL_CHARS = 6

/**
 * The word on the tag a signer wears when its key came off a signing device.
 *
 * This is deliberately the device family's own name rather than the transfer
 * method: the UR path can carry a Keystone or a Jade export as readily as a
 * SeedSigner one, but the user asked for "seedsigner" here and this is the one
 * place the wording lives, so a later rename is a one-line change.
 */
internal const val SIGNING_DEVICE_TAG_LABEL = "seedsigner"

/**
 * The accent a signer's tag is painted in.
 *
 * An enum rather than a `Color` on purpose: this file has no Compose imports so
 * the *rule* — which signer wears which word — stays a JVM-testable fact, and
 * the view layer is the only place that knows what "orange" means.
 */
internal enum class SignerTagAccent {
    /** The calm grey of the original watch-only pill. */
    NEUTRAL,

    /** The theme's orange, worn by a key that came off a signing device. */
    ORANGE,
}

/**
 * The tag a signer wears: the word and the accent it is painted in.
 *
 * [Signer.importedVia] decides it. A key read off a signing device over BC-UR
 * wears "seedsigner" in orange — it arrived through the deliberate, hardware-in-
 * hand path, and the tag is how a user tells it apart from a key that was just
 * scanned or pasted. Everything else keeps the original grey "watch-only" pill,
 * unchanged, because for a text xpub that word is still exactly right.
 */
internal data class SignerTag(val label: String, val accent: SignerTagAccent)

/**
 * Chooses the tag for [signer] from the origin its key arrived by.
 *
 * Kept as a pure function over the record so both screens — the list row and the
 * signer's own header — cannot drift apart in what they show.
 */
internal fun signerTagFor(signer: Signer): SignerTag =
    when (signer.importedVia) {
        SignerOrigin.TEXT_QR -> SignerTag(signer.typeLabel, SignerTagAccent.NEUTRAL)
        SignerOrigin.SIGNING_DEVICE_UR -> SignerTag(SIGNING_DEVICE_TAG_LABEL, SignerTagAccent.ORANGE)
    }
