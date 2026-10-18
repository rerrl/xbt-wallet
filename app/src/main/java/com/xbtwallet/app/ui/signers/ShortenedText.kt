package com.xbtwallet.app.ui.signers

/**
 * Shortens a long identifier for a narrow row by keeping both ends.
 *
 * A txid is 64 hex characters and a phone row is not. Keeping the head and the
 * tail keeps the parts a person actually compares against another view (a block
 * explorer, the signer's screen), and elides the middle — the same idea as the
 * signer list's xpub snippet.
 */
internal fun shortenedMiddle(value: String, head: Int = HEAD_CHARS, tail: Int = TAIL_CHARS): String {
    // Short enough to show whole: eliding would leave less than the ellipsis it
    // replaced, which is how shortened strings end up unreadable.
    if (value.length <= head + tail + 1) return value
    return value.take(head) + "\u2026" + value.takeLast(tail)
}

/** Characters kept from the front of a shortened identifier. */
internal const val HEAD_CHARS = 8

/** Characters kept from the back of a shortened identifier. */
internal const val TAIL_CHARS = 4
