package com.xbtwallet.app.ui.signers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the list-row snippet rule.
 *
 * The Signers list shows a head…tail of each key so two signers are told apart
 * at a glance, and so the middle of an xpub never fills the row. This is pure
 * formatting, but it is the kind of formatting that quietly loses an end of the
 * key if it is wrong, so it is pinned here rather than left to the eye.
 */
class SignerPresentationTest {

    /** The BIP-84 account zpub used elsewhere in the suite — 111 characters. */
    private val bip84Zpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868" +
            "RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    @Test
    fun `a real key keeps its head and its tail`() {
        val shortened = shortenedXpub(bip84Zpub)

        assertEquals("zpub6rFR7y\u2026GutZYs", shortened)
        assertTrue("the head is preserved", shortened.startsWith(bip84Zpub.take(10)))
        assertTrue("the tail is preserved", shortened.endsWith(bip84Zpub.takeLast(6)))
        assertTrue("the middle is elided", shortened.length < bip84Zpub.length)
    }

    @Test
    fun `a key short enough to show whole is left whole`() {
        // No point replacing content with an ellipsis that is longer than what
        // it hides.
        assertEquals("zpub6xyz", shortenedXpub("zpub6xyz"))
    }

    @Test
    fun `the boundary is inclusive - one character over elides`() {
        // Exactly head + tail + 1 (17): shown whole, so the ellipsis would not
        // be longer than what it hides.
        val whole = "x".repeat(SHORTENED_HEAD_CHARS + SHORTENED_TAIL_CHARS + 1)
        assertEquals(whole, shortenedXpub(whole))

        // One character more (18) crosses into elision: 10 from the front, the
        // last 6 (five x's and the Z) from the back.
        val oneMore = whole + "Z"
        assertEquals("x".repeat(10) + "\u2026" + "xxxxxZ", shortenedXpub(oneMore))
    }

    @Test
    fun `an empty key stays empty`() {
        assertEquals("", shortenedXpub(""))
    }
}
