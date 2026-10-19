package com.xbtwallet.app.crypto

/**
 * What a scanned QR code means, on its way to the xpub field.
 *
 * A QR code is not obliged to hold a bare key. Wallets commonly encode the
 * account as a BIP-21 style URI — `bitcoin:zpub…?amount=1&label=Cold` — because
 * the same string has to be scannable by a spending app. This object is the one
 * place that decides what to keep, and it is deliberately the *only* thing it
 * does: the result is handed to the same validator a pasted key goes through
 * ([validateXpubInput]), so a scan can never be accepted where a paste would be
 * rejected.
 *
 * That division is why this file is plain Kotlin: the routing rules are cheap to
 * unit-test on the JVM, and refusing a bad key stays the validator's job, not a
 * second copy of those rules living next to the camera.
 */
object XpubScanRoute {

    private const val SCHEME = "bitcoin:"

    /**
     * Raw QR payload → candidate xpub text for [validateXpubInput].
     *
     * The payload is trimmed. If what remains starts with `bitcoin:`
     * (case-insensitively — QR encoders are not consistent about the case of a
     * URI scheme) that scheme is stripped and everything from the first `?` is
     * dropped, since URI parameters (`amount`, `label`, …) are not part of the
     * key. The result is trimmed once more.
     *
     * Anything else is returned as trimmed text, unchanged and unjudged: a code
     * holding nonsense produces nonsense, which the shared validator then
     * refuses with its usual message. This function never throws.
     */
    fun extract(payload: String): String {
        val trimmed = payload.trim()
        if (!trimmed.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) return trimmed

        val withoutScheme = trimmed.substring(SCHEME.length)
        val withoutParams = withoutScheme.substringBefore('?')
        return withoutParams.trim()
    }
}
