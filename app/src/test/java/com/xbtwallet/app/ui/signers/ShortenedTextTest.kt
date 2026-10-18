package com.xbtwallet.app.ui.signers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Shortening tests: a txid in a UTXO row keeps its two ends and nothing else.
 */
class ShortenedTextTest {

    @Test
    fun `a long identifier keeps the head and the tail`() {
        val txid = "ab".repeat(32)
        assertEquals("abababab\u2026abab", shortenedMiddle(txid))
    }

    @Test
    fun `a short value is shown whole`() {
        // Eliding anything shorter than head + tail + 1 would leave less than the
        // ellipsis it replaced.
        assertEquals("short", shortenedMiddle("short"))
        assertEquals("1234567890123", shortenedMiddle("1234567890123"))
    }
}
