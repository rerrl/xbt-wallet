package com.xbtwallet.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing rules for a scanned QR payload.
 *
 * The point of these tests is the seam: [XpubScanRoute.extract] decides what part
 * of a QR code is the key and what metadata rides with it, and [validateXpubInput]
 * then decides whether that key is any good. So an extract that returns something
 * odd is not a bug as long as it returns *something* — it must never throw on the
 * way to the validator.
 */
class XpubScanRouteTest {

    private val zpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADtwsVXhbp9BjJ9n8uMzXKK3dUY1qY2uJ3ok9dBHyPtJQ5"

    // --- plain keys and URIs: no metadata ----------------------------------

    @Test
    fun `a bare key is passed through untouched, with no metadata`() {
        val result = XpubScanRoute.extract(zpub)
        assertEquals(zpub, result.keyText)
        assertNull(result.masterFingerprint)
        assertNull(result.derivationPath)
    }

    @Test
    fun `a bip21 uri keeps only the key, with no metadata`() {
        val result = XpubScanRoute.extract("bitcoin:$zpub?amount=1&label=x")
        assertEquals(zpub, result.keyText)
        assertNull(result.masterFingerprint)
        assertNull(result.derivationPath)
    }

    @Test
    fun `a uri with no parameters still loses the scheme`() {
        assertEquals(zpub, XpubScanRoute.extract("bitcoin:$zpub").keyText)
    }

    @Test
    fun `the scheme is matched case-insensitively`() {
        assertEquals(zpub, XpubScanRoute.extract("BITCOIN:$zpub?amount=1").keyText)
        assertEquals(zpub, XpubScanRoute.extract("Bitcoin:$zpub?label=cold").keyText)
    }

    @Test
    fun `an uppercase scheme does not leak into the key`() {
        val extracted = XpubScanRoute.extract("BITCOIN:$zpub?amount=1").keyText
        assertFalse("the scheme must not survive extraction", extracted.startsWith("BITCOIN", ignoreCase = true))
        assertTrue(extracted.startsWith("zpub"))
    }

    @Test
    fun `surrounding whitespace is trimmed on every path`() {
        assertEquals(zpub, XpubScanRoute.extract("  $zpub  ").keyText)
        assertEquals(zpub, XpubScanRoute.extract("\n $zpub \t").keyText)
        assertEquals(zpub, XpubScanRoute.extract("  bitcoin:$zpub?amount=1  ").keyText)
        assertEquals(zpub, XpubScanRoute.extract("bitcoin:  $zpub  ?amount=1").keyText)
        assertEquals("", XpubScanRoute.extract("   ").keyText)
    }

    @Test
    fun `only the first query separator matters`() {
        assertEquals(zpub, XpubScanRoute.extract("bitcoin:$zpub?label=a?b").keyText)
        assertEquals(zpub, XpubScanRoute.extract("bitcoin:$zpub?message=hello?world").keyText)
    }

    // --- key expressions: fingerprint + path -------------------------------

    @Test
    fun `a full key expression yields the key, the fingerprint and the path`() {
        val result = XpubScanRoute.extract("[ab12cd34/84'/0'/0']$zpub")
        assertEquals(zpub, result.keyText)
        assertEquals("ab12cd34", result.masterFingerprint)
        assertEquals("84'/0'/0'", result.derivationPath)
    }

    @Test
    fun `a key expression with no path yields the fingerprint only`() {
        val result = XpubScanRoute.extract("[ab12cd34]$zpub")
        assertEquals(zpub, result.keyText)
        assertEquals("ab12cd34", result.masterFingerprint)
        assertNull("no path group means no path, not an empty string", result.derivationPath)
    }

    @Test
    fun `h and H hardening are normalized to an apostrophe`() {
        val result = XpubScanRoute.extract("[ab12cd34/84h/0H/0']$zpub")
        assertEquals("84'/0'/0'", result.derivationPath)
    }

    @Test
    fun `an unhardened path is kept as-is`() {
        assertEquals("0/0", XpubScanRoute.extract("[ab12cd34/0/0]$zpub").derivationPath)
    }

    @Test
    fun `an uppercase fingerprint is lowercased`() {
        assertEquals("ab12cd34", XpubScanRoute.extract("[AB12CD34/84'/0'/0']$zpub").masterFingerprint)
    }

    @Test
    fun `a key expression behind a bitcoin uri is still recognised`() {
        val result = XpubScanRoute.extract("bitcoin:[ab12cd34/84'/0'/0']$zpub?amount=1")
        assertEquals(zpub, result.keyText)
        assertEquals("ab12cd34", result.masterFingerprint)
        assertEquals("84'/0'/0'", result.derivationPath)
    }

    @Test
    fun `the path is stored without a leading m slash`() {
        val path = XpubScanRoute.extract("[ab12cd34/84'/0'/0']$zpub").derivationPath
        assertFalse("the stored path must not carry m/", path!!.startsWith("m/"))
        assertFalse("the stored path must not carry a leading slash", path.startsWith("/"))
    }

    @Test
    fun `whitespace around a key expression is trimmed away`() {
        val result = XpubScanRoute.extract("  [ab12cd34/84'/0'/0']$zpub  ")
        assertEquals(zpub, result.keyText)
        assertEquals("84'/0'/0'", result.derivationPath)
    }

    // --- things that are not key expressions -------------------------------

    @Test
    fun `an empty payload yields an empty key and no metadata`() {
        val result = XpubScanRoute.extract("")
        assertEquals("", result.keyText)
        assertNull(result.masterFingerprint)
        assertNull(result.derivationPath)
    }

    /**
     * Garbage is returned as trimmed text rather than rejected here — the shared
     * validator owns rejection, and extraction must not throw before it gets the
     * chance.
     */
    @Test
    fun `non-uri garbage is returned trimmed, with no metadata, and does not throw`() {
        val result = XpubScanRoute.extract("  hello world  ")
        assertEquals("hello world", result.keyText)
        assertNull(result.masterFingerprint)
        assertNull(result.derivationPath)

        assertEquals("https://example.com/x", XpubScanRoute.extract("https://example.com/x").keyText)
        assertEquals("{\"json\":true}", XpubScanRoute.extract(" {\"json\":true} ").keyText)
        assertEquals("bitcoincash:qq", XpubScanRoute.extract("bitcoincash:qq").keyText)
    }

    /** A fingerprint is exactly eight hex digits; seven is not a key expression. */
    @Test
    fun `a short fingerprint is not treated as a key expression`() {
        val payload = "[ab12cd3/84'/0'/0']$zpub"
        val result = XpubScanRoute.extract(payload)
        assertEquals(payload, result.keyText)
        assertNull(result.masterFingerprint)
        assertNull(result.derivationPath)
    }

    /** A key expression needs a key behind the bracket; a bare prefix is not one. */
    @Test
    fun `a bracket prefix with nothing behind it is not a key expression`() {
        val payload = "[ab12cd34/84'/0'/0']"
        val result = XpubScanRoute.extract(payload)
        assertEquals(payload, result.keyText)
        assertNull(result.masterFingerprint)
    }

    @Test
    fun `a scheme with nothing behind it yields an empty candidate`() {
        assertEquals("", XpubScanRoute.extract("bitcoin:?amount=1").keyText)
        assertEquals("", XpubScanRoute.extract("bitcoin:").keyText)
    }

    /**
     * The shared validator is the gate, not this object: whatever extraction
     * produces for a nonsense code must still be refused downstream. Asserted
     * here so the seam's direction is pinned in one place.
     */
    @Test
    fun `extracted garbage is still refused by the shared validator`() {
        val extracted = XpubScanRoute.extract("  not-a-key  ").keyText
        assertEquals("not-a-key", extracted)
        assertTrue(validateXpubInput(extracted) is XpubImport.Invalid)
    }
}
