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
 * The word on the tag a signer wears when its key came off a **SeedSigner**.
 *
 * This is deliberately the device family's own name. It is the word the
 * SeedSigner option of the Add-wallet flow promises, and this is the one place
 * the wording lives, so a later rename is a one-line change.
 */
internal const val SEEDSIGNER_TAG_LABEL = "seedsigner"

/**
 * The word on the tag a signer wears when its key came off *another* signing
 * device — Keystone, Jade, or anything else that exports BC-UR.
 *
 * Deliberately not "seedsigner": the user told us only that the device was not a
 * SeedSigner, and a tag that claimed more than that would be a lie about where
 * the key came from. It is also not the grey "watch-only" pill, which means a
 * key that arrived as plain text.
 */
internal const val SIGNING_DEVICE_TAG_LABEL = "signing device"

/**
 * The word on the tag a signer wears when the wallet's own seed is on this
 * phone — a hot wallet.
 *
 * The same word as the record's own `type` discriminator, deliberately: "hot" is
 * what the wallet *is*, and the tag is where a user sees it. It is also the word
 * that should make someone careful — this is the one tag whose wallet can be
 * emptied by whoever holds the phone.
 */
internal const val HOT_TAG_LABEL = "hot"

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

    /** The theme's orange, worn by a key that came off a SeedSigner. */
    ORANGE,

    /**
     * A neutral-but-distinct accent, worn by a key that came off some other
     * signing device. Not the orange (which would claim SeedSigner) and not the
     * grey (which would claim a text xpub); the view layer maps it to the
     * palette's calm blue.
     */
    DEVICE,

    /**
     * The amber accent for a hot wallet — a seed on this phone.
     *
     * Amber because it is the one tag that is a warning: the other three
     * describe keys that live somewhere else, and this one describes a wallet
     * whose entire spending power is in the device the user is looking at. The
     * three neighbouring colours are taken (grey means a text xpub, orange means
     * SeedSigner, blue means another signing device) and the palette's amber is
     * the one that reads as caution without being the red of an error.
     */
    HOT,
}

/**
 * The tag a signer wears: the word and the accent it is painted in.
 *
 * [Signer.importedVia] decides it — the origin the chooser declared. A key read
 * off a SeedSigner over BC-UR wears "seedsigner" in orange; a key read off any
 * other signing device wears "signing device" in the blue; a key that arrived as
 * plain text keeps the original grey "watch-only" pill, unchanged, because for a
 * text xpub that word is still exactly right; and a wallet whose seed is on this
 * phone wears "hot" in amber, because that is the one case where the phone
 * itself can spend.
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
        SignerOrigin.SEEDSIGNER_UR -> SignerTag(SEEDSIGNER_TAG_LABEL, SignerTagAccent.ORANGE)
        SignerOrigin.SIGNING_DEVICE_UR -> SignerTag(SIGNING_DEVICE_TAG_LABEL, SignerTagAccent.DEVICE)
        SignerOrigin.HOT -> SignerTag(HOT_TAG_LABEL, SignerTagAccent.HOT)
    }
