package com.xbtwallet.app.chain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * The date string a transaction row shows.
 *
 * Both the time zone and the locale are passed in, so these assertions hold on
 * any machine: the same epoch must format to a *pinned* string in a *pinned*
 * zone. The two-zone case is the point — it proves the zone parameter is
 * actually used rather than the JVM default leaking in.
 */
class FormatBlockTimestampTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val newYork = TimeZone.getTimeZone("America/New_York")

    @Test
    fun `an epoch formats as day month year, middot, 24h time in the given zone`() {
        // 1600000000 = 2020-09-13 12:26:40 UTC.
        assertEquals(
            "13 Sep 2020 \u00b7 12:26",
            formatBlockTimestamp(1_600_000_000L, utc, Locale.US),
        )
    }

    @Test
    fun `the same epoch reads differently in another zone`() {
        assertEquals(
            "13 Sep 2020 \u00b7 08:26",
            formatBlockTimestamp(1_600_000_000L, newYork, Locale.US),
        )
    }

    @Test
    fun `a single-digit day has no leading zero`() {
        // The genesis block: 2009-01-03 18:15:05 UTC.
        assertEquals(
            "3 Jan 2009 \u00b7 18:15",
            formatBlockTimestamp(1_231_006_505L, utc, Locale.US),
        )
    }
}
