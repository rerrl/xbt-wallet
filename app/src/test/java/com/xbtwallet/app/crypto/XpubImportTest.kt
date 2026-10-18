package com.xbtwallet.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the validation seam the import screen talks to.
 *
 * These pin the user-visible wording, so a change to a message has to be a
 * deliberate change to a test — the screen has no other way to explain itself.
 */
class XpubImportTest {

    private val accountZpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    @Test
    fun `a valid zpub is accepted and reports its version`() {
        val result = validateXpubInput(accountZpub)
        assertTrue(result is XpubImport.Valid)
        assertEquals("zpub", (result as XpubImport.Valid).versionLabel)
    }

    @Test
    fun `surrounding whitespace from a paste is tolerated`() {
        val result = validateXpubInput("  $accountZpub\n")
        assertTrue(result is XpubImport.Valid)
    }

    @Test
    fun `blank input is rejected with an instruction`() {
        val result = validateXpubInput("   ")
        assertEquals(XpubParseException.Reason.EMPTY, (result as XpubImport.Invalid).reason)
        assertTrue(result.message.contains("Enter an extended public key"))
    }

    @Test
    fun `a mistyped key is rejected with the checksum reason`() {
        val mistyped = accountZpub.dropLast(1) + if (accountZpub.last() == 'a') 'b' else 'a'
        val result = validateXpubInput(mistyped)
        assertEquals(XpubParseException.Reason.CHECKSUM, (result as XpubImport.Invalid).reason)
        assertTrue(result.message.contains("checksum"))
    }

    @Test
    fun `a private key is rejected with a warning about xprv`() {
        val result = validateXpubInput(
            "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi",
        )
        assertEquals(XpubParseException.Reason.UNKNOWN_VERSION, (result as XpubImport.Invalid).reason)
        assertTrue(result.message.contains("xprv"))
    }

    @Test
    fun `non-base58 input is rejected`() {
        val result = validateXpubInput("zpub0OIl")
        assertEquals(XpubParseException.Reason.BASE58_INVALID, (result as XpubImport.Invalid).reason)
    }
}
