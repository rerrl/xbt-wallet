package com.xbtwallet.app.ui.signers

/**
 * The copy and the row model behind the post-scan summary on the **Add signing
 * device** screen.
 *
 * A completed animated export produces a small set of facts — the account key,
 * the master fingerprint and the derivation path — and the screen shows them
 * back as a *success* summary: this is what was read, not a complaint about it.
 * Keeping the wording and the row order here, free of Compose imports, means the
 * copy is testable Kotlin rather than a rendering detail a JVM test cannot reach.
 *
 * [scannedAccountRows] deliberately drops the rows an export did not carry rather
 * than printing placeholders: an absent fingerprint is already reported as a
 * *warning* underneath, and inventing a fake value here would dress up missing
 * data as if it had been read.
 */
internal const val SCANNED_SUMMARY_TITLE = "Scanned from signing device"

/** The label of the master-fingerprint row. */
internal const val FINGERPRINT_LABEL = "Master fingerprint"

/** The label of the derivation-path row. */
internal const val DERIVATION_PATH_LABEL = "Derivation path"

/** The label of the account-key row. */
internal const val ACCOUNT_KEY_LABEL = "Account key"

/**
 * One labelled line of the summary: a label the user reads, and the value that
 * goes under it in monospace.
 */
internal data class SummaryRow(val label: String, val value: String)

/**
 * How much of the account key is kept on each side of the ellipsis.
 *
 * A full zpub is 111 characters and the row is one line wide; twelve characters
 * of each end is enough to recognise the key and to tell two accounts apart
 * without turning the panel into a wall of Base58.
 */
internal const val ACCOUNT_KEY_HEAD_CHARS = 12

/** Characters kept from the back of the account key. */
internal const val ACCOUNT_KEY_TAIL_CHARS = 12

/**
 * The labelled rows of the post-scan summary, in reading order.
 *
 * [masterFingerprint] and [derivationPath] are the values the export carried, or
 * null when it carried none — the missing-row case the warnings block covers.
 * The path is rendered whole (`m/84'/0'/0'`) so it reads as the derivation path a
 * person compares against the signer's own screen, not as a bare fragment.
 */
internal fun scannedAccountRows(
    xpub: String,
    masterFingerprint: String?,
    derivationPath: String?,
): List<SummaryRow> = buildList {
    masterFingerprint?.let { add(SummaryRow(FINGERPRINT_LABEL, it)) }
    derivationPath?.let { add(SummaryRow(DERIVATION_PATH_LABEL, "m/$it")) }
    add(
        SummaryRow(
            ACCOUNT_KEY_LABEL,
            shortenedMiddle(xpub, head = ACCOUNT_KEY_HEAD_CHARS, tail = ACCOUNT_KEY_TAIL_CHARS),
        ),
    )
}
