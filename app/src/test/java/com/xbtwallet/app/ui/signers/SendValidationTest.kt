package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.crypto.SegwitAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Send screen's parsing and validation, exercised on the JVM.
 *
 * These helpers are deliberately Android-free (see [SendValidation]), so the
 * whole of the address and fee reasoning is checkable with no device and no
 * composition. The vectors are the ones the BIPs actually name: the BIP-173
 * `bc1q…` example for a valid v0 address, and its all-uppercase form (bech32
 * permits either case, but never a mix).
 */
class SendValidationTest {

    /** The canonical BIP-173 mainnet P2WPKH vector. */
    private val validV0 = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"

    // --- validateRecipientAddress ------------------------------------------

    @Test
    fun `a valid BIP-173 mainnet address is accepted`() {
        assertNull(validateRecipientAddress(validV0))
    }

    @Test
    fun `the all-uppercase form of a valid address is accepted`() {
        assertNull(validateRecipientAddress(validV0.uppercase()))
    }

    @Test
    fun `a bitcoin URI is stripped before validating`() {
        assertNull(
            validateRecipientAddress(
                "bitcoin:$validV0?amount=0.001&label=x",
            ),
        )
    }

    @Test
    fun `a version 1 address round-trips as valid`() {
        // Built here rather than pasted: the point is that a bech32m v1 address
        // with its 32-byte program is accepted, and encoding one is the honest
        // way to be sure the bytes are well-formed.
        val taproot = SegwitAddress.encode("bc", 1, ByteArray(32))
        assertNull(validateRecipientAddress(taproot))
    }

    @Test
    fun `a testnet address is rejected — wrong HRP`() {
        val testnetAddress = SegwitAddress.encode("tb", 0, ByteArray(20))
        assertEquals("Not a valid Bitcoin address.", validateRecipientAddress(testnetAddress))
    }

    @Test
    fun `a bad checksum is rejected`() {
        // One character short of the real checksum (…t4 → …t5).
        assertEquals(
            "Not a valid Bitcoin address.",
            validateRecipientAddress("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5"),
        )
    }

    @Test
    fun `an empty input asks for an address`() {
        assertEquals("Enter a recipient address.", validateRecipientAddress(""))
        // Whitespace extracts to empty too, and means the same thing.
        assertEquals("Enter a recipient address.", validateRecipientAddress("   "))
    }

    @Test
    fun `random text is not a valid address`() {
        assertEquals("Not a valid Bitcoin address.", validateRecipientAddress("hello there"))
    }

    @Test
    fun `a version 0 address with the wrong program length is unsupported`() {
        // A 32-byte v0 program is P2WSH: a real address kind this BIP-84
        // P2WPKH-only wallet does not build for.
        val p2wsh = SegwitAddress.encode("bc", 0, ByteArray(32))
        assertEquals("Unsupported address type.", validateRecipientAddress(p2wsh))
    }

    @Test
    fun `a future witness version is unsupported`() {
        val v2 = SegwitAddress.encode("bc", 2, ByteArray(32))
        assertEquals("Unsupported address type.", validateRecipientAddress(v2))
    }

    // --- parseFeeRate -------------------------------------------------------

    @Test
    fun `a whole number of at least one parses`() {
        assertEquals(1L, parseFeeRate("1"))
        assertEquals(3L, parseFeeRate(" 3 "))
        assertEquals(25L, parseFeeRate("25"))
    }

    @Test
    fun `zero, fractions, blanks and text do not parse`() {
        assertNull(parseFeeRate("0"))
        assertNull(parseFeeRate("1.5"))
        assertNull(parseFeeRate(""))
        assertNull(parseFeeRate("   "))
        assertNull(parseFeeRate("abc"))
        assertNull(parseFeeRate("-1"))
    }

    // --- extractAddress -----------------------------------------------------

    @Test
    fun `extractAddress trims whitespace`() {
        assertEquals(validV0, extractAddress("  $validV0\n"))
    }

    @Test
    fun `extractAddress strips the scheme whatever its case`() {
        assertEquals(validV0, extractAddress("bitcoin:$validV0"))
        assertEquals(validV0, extractAddress("BITCOIN:$validV0"))
        assertEquals(validV0, extractAddress("Bitcoin:$validV0"))
    }

    @Test
    fun `extractAddress drops a query string`() {
        assertEquals(validV0, extractAddress("bitcoin:$validV0?amount=0.001&label=x"))
        assertEquals(validV0, extractAddress("$validV0?foo=bar"))
    }

    @Test
    fun `extractAddress passes a bare address through unchanged`() {
        assertEquals(validV0, extractAddress(validV0))
    }
}
