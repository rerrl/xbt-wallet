package com.xbtwallet.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing rules for a scanned QR payload.
 *
 * The point of these tests is the seam: [XpubScanRoute.extract] decides what
 * part of a QR code is the key, and [validateXpubInput] then decides whether that
 * key is any good. So an extract that returns something odd is not a bug as long
 * as it returns *something* — it must never throw on the way to the validator.
 */
class XpubScanRouteTest {

    private val zpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADtwsVXhbp9BjJ9n8uMzXKK3dUY1qY2uJ3ok9dBHyPtJQ5"

    @Test
    fun `a bare key is passed through untouched`() {
        assertEquals(zpub, XpubScanRoute.extract(zpub))
    }

    @Test
    fun `a bip21 uri keeps only the key`() {
        assertEquals(zpub, XpubScanRoute.extract("bitcoin:$zpub?amount=1&label=x"))
    }

    @Test
    fun `a uri with no parameters still loses the scheme`() {
        assertEquals(zpub, XpubScanRoute.extract("bitcoin:$zpub"))
    }

    @Test
    fun `the scheme is matched case-insensitively`() {
        assertEquals(zpub, XpubScanRoute.extract("BITCOIN:$zpub?amount=1"))
        assertEquals(zpub, XpubScanRoute.extract("Bitcoin:$zpub?label=cold"))
    }

    @Test
    fun `an uppercase scheme does not leak into the key`() {
        val extracted = XpubScanRoute.extract("BITCOIN:$zpub?amount=1")
        assertFalse("the scheme must not survive extraction", extracted.startsWith("BITCOIN", ignoreCase = true))
        assertTrue(extracted.startsWith("zpub"))
    }

    @Test
    fun `surrounding whitespace is trimmed on every path`() {
        assertEquals(zpub, XpubScanRoute.extract("  $zpub  "))
        assertEquals(zpub, XpubScanRoute.extract("\n $zpub \t"))
        assertEquals(zpub, XpubScanRoute.extract("  bitcoin:$zpub?amount=1  "))
        assertEquals(zpub, XpubScanRoute.extract("bitcoin:  $zpub  ?amount=1"))
        assertEquals("", XpubScanRoute.extract("   "))
    }

    @Test
    fun `only the first query separator matters`() {
        assertEquals(zpub, XpubScanRoute.extract("bitcoin:$zpub?label=a?b"))
        assertEquals(zpub, XpubScanRoute.extract("bitcoin:$zpub?message=hello?world"))
    }

    @Test
    fun `an empty payload stays empty`() {
        assertEquals("", XpubScanRoute.extract(""))
    }

    /**
     * Garbage is returned as trimmed text rather than rejected here — the shared
     * validator owns rejection, and extraction must not throw before it gets the
     * chance. `extract` returning the same thing it was given is the contract.
     */
    @Test
    fun `non-uri garbage is returned trimmed and does not throw`() {
        assertEquals("hello world", XpubScanRoute.extract("  hello world  "))
        assertEquals("https://example.com/x", XpubScanRoute.extract("https://example.com/x"))
        assertEquals("{\"json\":true}", XpubScanRoute.extract(" {\"json\":true} "))
        assertEquals("bitcoincash:qq", XpubScanRoute.extract("bitcoincash:qq"))
    }

    /**
     * A `bitcoin:` URI with an empty path yields an empty string, which the
     * import screen already treats as "nothing entered" — not a crash and not a
     * key.
     */
    @Test
    fun `a scheme with nothing behind it yields an empty candidate`() {
        assertEquals("", XpubScanRoute.extract("bitcoin:?amount=1"))
        assertEquals("", XpubScanRoute.extract("bitcoin:"))
    }

    /**
     * The shared validator is the gate, not this object: whatever extraction
     * produces for a nonsense code must still be refused downstream. Asserted
     * here so the seam's direction is pinned in one place.
     */
    @Test
    fun `extracted garbage is still refused by the shared validator`() {
        val extracted = XpubScanRoute.extract("  not-a-key  ")
        assertEquals("not-a-key", extracted)
        assertTrue(validateXpubInput(extracted) is XpubImport.Invalid)
    }
}
