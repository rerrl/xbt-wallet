package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.PlaceholderPrices
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * Balance formatting tests.
 *
 * The numbers a wallet prints are part of its correctness, so they are asserted
 * literally: the sat grouping, the eight-decimal coin amount, and the two
 * placeholder fiat conversions.
 */
class BalancePresentationTest {

    @Test
    fun `sats are grouped in threes`() {
        assertEquals("0", formatSats(0))
        assertEquals("999", formatSats(999))
        assertEquals("1,000", formatSats(1_000))
        assertEquals("12,345", formatSats(12_345))
        assertEquals("1,234,567", formatSats(1_234_567))
        assertEquals("123,456,789", formatSats(123_456_789))
        // 21 million coins in sats — the top of the range that can exist.
        assertEquals("2,100,000,000,000,000", formatSats(2_100_000_000_000_000L))
    }

    @Test
    fun `coins always show eight decimals`() {
        assertEquals("0.00000000", formatCoins(0))
        assertEquals("0.00000001", formatCoins(1))
        assertEquals("0.00012345", formatCoins(12_345))
        assertEquals("0.01234567", formatCoins(1_234_567))
        assertEquals("1.00000000", formatCoins(100_000_000))
        assertEquals("21.00000000", formatCoins(2_100_000_000))
    }

    @Test
    fun `the placeholder rates price a whole coin`() {
        // The constants are placeholders; these assertions pin that the screen
        // says so at exactly these values until a real feed replaces them.
        assertEquals("\$420.69", formatUsd(BigDecimal.ONE, PlaceholderPrices.USD_PER_XBT))
        assertEquals("\$81,000.00", formatUsd(BigDecimal.ONE, PlaceholderPrices.USD_PER_BTC))
    }

    @Test
    fun `a sat balance is priced at both placeholder rates`() {
        val coins = BigDecimal.valueOf(1_234_567, COIN_DECIMALS)

        // 0.01234567 XBT at $420.69.
        assertEquals("\$5.19", formatUsd(coins, PlaceholderPrices.USD_PER_XBT))
        // The same sat balance, as BTC, at $81,000 — rounds to a round thousand.
        assertEquals("\$1,000.00", formatUsd(coins, PlaceholderPrices.USD_PER_BTC))
    }

    @Test
    fun `grouping leaves the fractional part alone`() {
        assertEquals("1,234.56", groupThousands("1234.56"))
        assertEquals("0.00000001", groupThousands("0.00000001"))
        assertEquals("-1,000.00", groupThousands("-1000.00"))
    }
}
