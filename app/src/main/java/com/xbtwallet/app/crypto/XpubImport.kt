package com.xbtwallet.app.crypto

/**
 * Turns raw user input into either a usable account key or a message explaining
 * exactly what is wrong with it.
 *
 * This is the seam between the crypto package and the import screen: the screen
 * never touches [XpubParseException] reasons or Base58 internals, it just renders
 * [Invalid.message]. Keeping it here (rather than in the Compose file) means the
 * wording of every validation error is unit-testable.
 */
sealed interface XpubImport {

    /** The text parsed as a mainnet extended public key. */
    data class Valid(val key: ExtendedPublicKey, val versionLabel: String) : XpubImport

    /** The text did not parse; [message] is shown inline under the field. */
    data class Invalid(val message: String, val reason: XpubParseException.Reason) : XpubImport
}

/**
 * Validates one xpub/ypub/zpub string.
 *
 * Accepts all three mainnet versions on purpose: they carry the same public key
 * and this wallet only ever derives native segwit addresses from it (see the
 * import screen's "treated as native segwit" note).
 */
fun validateXpubInput(raw: String): XpubImport {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        return XpubImport.Invalid("Enter an extended public key (xpub, ypub or zpub).", XpubParseException.Reason.EMPTY)
    }
    return try {
        val key = ExtendedPublicKey.parse(trimmed)
        XpubImport.Valid(key, key.version.label)
    } catch (e: XpubParseException) {
        XpubImport.Invalid(messageFor(e.reason), e.reason)
    }
}

private fun messageFor(reason: XpubParseException.Reason): String = when (reason) {
    XpubParseException.Reason.EMPTY -> "Enter an extended public key (xpub, ypub or zpub)."
    XpubParseException.Reason.BASE58_INVALID ->
        "Not valid Base58 \u2014 check for characters like 0, O, I or l, which are not used."
    XpubParseException.Reason.CHECKSUM -> "The checksum does not match \u2014 the key looks mistyped or truncated."
    XpubParseException.Reason.LENGTH -> "That is the wrong length for an extended public key."
    XpubParseException.Reason.UNKNOWN_VERSION ->
        "Unrecognised version bytes. Expected an xpub, ypub or zpub (mainnet, public) \u2014 " +
            "and never a private key (xprv), which must stay on your signer."
    XpubParseException.Reason.NOT_PUBLIC_KEY ->
        "That is not a public key. Never import a private key (xprv) into the phone."
}
