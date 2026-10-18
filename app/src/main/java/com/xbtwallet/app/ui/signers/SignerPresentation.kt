package com.xbtwallet.app.ui.signers

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
