package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.crypto.UrTestVectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post-scan summary's copy is a rule, not a rendering detail: these tests
 * pin what the success panel says and which rows it shows, without a UI.
 */
class ScannedAccountSummaryTest {

    private fun row(label: String, rows: List<SummaryRow>) =
        rows.single { it.label == label }.value

    @Test
    fun `a full export shows the fingerprint, the whole path and the elided key`() {
        val rows = scannedAccountRows(
            xpub = UrTestVectors.bcrZpub,
            masterFingerprint = "37b5eed4",
            derivationPath = "84'/0'/0'",
        )

        assertEquals(
            listOf(FINGERPRINT_LABEL, DERIVATION_PATH_LABEL, ACCOUNT_KEY_LABEL),
            rows.map { it.label },
        )
        assertEquals("37b5eed4", row(FINGERPRINT_LABEL, rows))
        assertEquals("m/84'/0'/0'", row(DERIVATION_PATH_LABEL, rows))
    }

    @Test
    fun `the account key keeps both ends and elides the middle`() {
        val rows = scannedAccountRows(
            xpub = UrTestVectors.bcrZpub,
            masterFingerprint = "37b5eed4",
            derivationPath = "84'/0'/0'",
        )

        val key = row(ACCOUNT_KEY_LABEL, rows)
        assertTrue("a 111-character zpub must not be shown whole", key.length < UrTestVectors.bcrZpub.length)
        assertEquals(
            UrTestVectors.bcrZpub.take(ACCOUNT_KEY_HEAD_CHARS) +
                "\u2026" +
                UrTestVectors.bcrZpub.takeLast(ACCOUNT_KEY_TAIL_CHARS),
            key,
        )
    }

    @Test
    fun `an export without a fingerprint or path shows only the key row`() {
        val rows = scannedAccountRows(
            xpub = UrTestVectors.bcrZpub,
            masterFingerprint = null,
            derivationPath = null,
        )

        // The absent metadata is not faked into a placeholder row here: the
        // warnings block is what says it was missing.
        assertEquals(listOf(ACCOUNT_KEY_LABEL), rows.map { it.label })
    }

    @Test
    fun `the title says the summary came from the signing device`() {
        assertEquals("Scanned from signing device", SCANNED_SUMMARY_TITLE)
    }
}
