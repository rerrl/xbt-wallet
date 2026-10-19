package com.xbtwallet.app.crypto

/**
 * What a scanned QR code means, on its way to the import screen.
 *
 * A QR code is not obliged to hold a bare key. Three shapes are accepted today:
 *
 *  1. **A bare key** — `zpub…`: the text Sparrow puts in its xpub QR, and what
 *     SeedSigner emits for a static export.
 *  2. **A BIP-21 style URI** — `bitcoin:zpub…?amount=1&label=Cold` — because the
 *     same string has to be scannable by a spending app. The scheme and any
 *     parameters are dropped.
 *  3. **A BIP-32 key expression** — `[ab12cd34/84'/0'/0']zpub…` — SeedSigner's
 *     Specter-legacy export. The bracketed prefix carries the master
 *     fingerprint and derivation path that a bare key does not: the fingerprint
 *     cannot be derived from the xpub, so it has to be captured from the QR if
 *     the later PSBT signing flow is to name the signer's device. The path is
 *     captured verbatim (this app derives native segwit only, so paths are
 *     implied in practice, but nothing here assumes that).
 *
 * A fourth shape — the UR `crypto-account` encoding — is deliberately **not**
 * handled here. Decoding it needs BC-UR (hummingbird), which is deferred to the
 * signing batch that needs BC-UR anyway; adding it now would be a dependency for
 * a format no accepted signer emits for a plain account export.
 *
 * This object is deliberately the *only* thing it does: it decides what part of
 * the payload is the key and what metadata rides with it, and hands the key to
 * the same validator a keyboard entry would go through ([validateXpubInput]), so
 * a scan can never be accepted where a paste would be rejected. That division is
 * why this file is plain Kotlin: the routing rules are cheap to unit-test on the
 * JVM, and refusing a bad key stays the validator's job, not a second copy of
 * those rules living next to the camera.
 */
object XpubScanRoute {

    private const val SCHEME = "bitcoin:"

    /**
     * `[fingerprint/path]key` — a BIP-32 key expression.
     *
     * The fingerprint is exactly eight hex digits; the path is zero or more
     * `/N` steps, each optionally hardened with `'`, `h` or `H`. The path group
     * may be empty: `[ab12cd34]zpub…` carries a fingerprint and no path.
     */
    private val KEY_EXPRESSION =
        Regex("^\\[([0-9A-Fa-f]{8})((?:/[0-9]+['hH]?)*)\\](.+)$")

    /**
     * Raw QR payload → the key candidate plus whatever metadata came with it.
     *
     * The payload is trimmed. If what remains starts with `bitcoin:`
     * (case-insensitively — QR encoders are not consistent about the case of a
     * URI scheme) that scheme is stripped and everything from the first `?` is
     * dropped, since URI parameters (`amount`, `label`, …) are not part of the
     * key. The result is trimmed once more.
     *
     * What is left is then checked for a key expression. When it matches, the
     * fingerprint is lowercased, the path is normalized (`h`/`H` → `'`) and
     * stored **without** a leading `m/`, and the remainder after the closing
     * bracket is the key text. When it does not match, the whole stripped text
     * is the key text and both metadata fields are null.
     *
     * Nothing is judged here: a code holding nonsense produces nonsense as the
     * key text, which the shared validator then refuses with its usual message.
     * This function never throws.
     */
    fun extract(payload: String): XpubScanResult {
        val stripped = stripUri(payload)

        val match = KEY_EXPRESSION.matchEntire(stripped)
            ?: return XpubScanResult(stripped, masterFingerprint = null, derivationPath = null)

        val fingerprint = match.groupValues[1].lowercase()
        val path = match.groupValues[2].ifEmpty { null }?.let(::normalizePath)
        val keyText = match.groupValues[3].trim()
        return XpubScanResult(keyText, masterFingerprint = fingerprint, derivationPath = path)
    }

    /** Trims, drops a `bitcoin:` scheme and any `?` parameters, trims again. */
    private fun stripUri(payload: String): String {
        val trimmed = payload.trim()
        if (!trimmed.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) return trimmed

        val withoutScheme = trimmed.substring(SCHEME.length)
        return withoutScheme.substringBefore('?').trim()
    }

    /** `/84h/0H/0'` → `84'/0'/0'`; the leading slash and any `m/` are not stored. */
    private fun normalizePath(raw: String): String =
        raw.trimStart('/').replace('h', '\'').replace('H', '\'')
}

/**
 * The outcome of routing one QR payload: the key candidate [keyText] and the
 * optional [masterFingerprint] / [derivationPath] a key expression carried.
 *
 * [keyText] is only a *candidate* — it still has to pass [validateXpubInput]
 * before anything is stored. The metadata fields are null whenever the QR was a
 * bare key or a `bitcoin:` URI, which is the common case.
 */
data class XpubScanResult(
    val keyText: String,
    val masterFingerprint: String? = null,
    val derivationPath: String? = null,
)
