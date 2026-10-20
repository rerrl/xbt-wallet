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

    // --- parseAmountSats ----------------------------------------------------

    @Test
    fun `a whole number of at least one satoshi parses`() {
        assertEquals(1L, parseAmountSats("1"))
        assertEquals(500L, parseAmountSats(" 500 "))
        assertEquals(2_100_000L, parseAmountSats("2100000"))
    }

    @Test
    fun `zero, negatives, fractions, blanks and text do not parse`() {
        assertNull(parseAmountSats("0"))
        assertNull(parseAmountSats("-5"))
        assertNull(parseAmountSats("1.5"))
        assertNull(parseAmountSats(""))
        assertNull(parseAmountSats("   "))
        assertNull(parseAmountSats("abc"))
    }

    @Test
    fun `an amount that overflows a Long is null, never a wrapped value`() {
        assertNull(parseAmountSats("99999999999999999999"))
    }

    // --- estimateSendVsize --------------------------------------------------

    @Test
    fun `the send vsize estimate is 10 plus 68 per input plus 62`() {
        assertEquals(140, estimateSendVsize(1))
        assertEquals(208, estimateSendVsize(2))
        assertEquals(72, estimateSendVsize(0))
    }

    // --- maxSendableSatoshis ------------------------------------------------

    @Test
    fun `the max spendable subtracts the estimated fee at the given rate`() {
        // 2 inputs → 208 vB; at 1 sat/vB that is 208 sats.
        assertEquals(99_792L, maxSendableSatoshis(100_000L, 2, 1L))
        // …and at 5 sat/vB, 208 * 5 = 1_040 sats.
        assertEquals(98_960L, maxSendableSatoshis(100_000L, 2, 5L))
    }

    @Test
    fun `the max spendable multiplies the exact vsize by the rate`() {
        // 1 input → 140 vB; 140 * 3 = 420.
        assertEquals(100_000L - 140L * 3L, maxSendableSatoshis(100_000L, 1, 3L))
        assertEquals(100_000L - 72L * 7L, maxSendableSatoshis(100_000L, 0, 7L))
    }

    @Test
    fun `the max spendable floors at zero when the fee would exceed the total`() {
        assertEquals(0L, maxSendableSatoshis(100L, 2, 5L))
        assertEquals(0L, maxSendableSatoshis(0L, 1, 1L))
        assertEquals(0L, maxSendableSatoshis(208L, 2, 1L))
    }

    // --- splitAddressEdges --------------------------------------------------

    @Test
    fun `a 42-char address splits 8-26-8 with no overlap and reassembles losslessly`() {
        val address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        assertEquals(42, address.length)
        val (first, middle, last) = splitAddressEdges(address)
        assertEquals(8, first.length)
        assertEquals(26, middle.length)
        assertEquals(8, last.length)
        assertEquals(address, first + middle + last)
    }

    @Test
    fun `an address of 16 chars or fewer is all-first, with an empty middle and last`() {
        val sixteen = "abcdefghijklmnop"
        assertEquals(Triple(sixteen, "", ""), splitAddressEdges(sixteen))
        val ten = "abcdefghij"
        assertEquals(Triple(ten, "", ""), splitAddressEdges(ten))
    }

    @Test
    fun `a 17-char address splits 8-1-8`() {
        val seventeen = "abcdefghijklmnopq"
        val (first, middle, last) = splitAddressEdges(seventeen)
        assertEquals("abcdefgh", first)
        assertEquals("i", middle)
        assertEquals("jklmnopq", last)
        assertEquals(seventeen, first + middle + last)
    }
}
