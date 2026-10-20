package com.xbtwallet.app.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconnect schedule: exponential, capped, jittered — and the attempt budget
 * and stable-session rule that go with it.
 *
 * This is pure arithmetic, so it is pinned directly rather than through a
 * subscription: the loop that *uses* the schedule is tested by
 * [ReconnectingSubscriptionTest], and the thing a reader has to be able to check
 * by eye is what "2s, doubling, capped at 60s, ±20%" actually produces.
 */
class ReconnectPolicyTest {

    /** A policy that never jitters, so the schedule is exactly the backoff. */
    private fun steady(
        initialDelayMillis: Long = 2_000L,
        maxDelayMillis: Long = 60_000L,
        maxAttempts: Int = 6,
    ) = ReconnectPolicy(
        initialDelayMillis = initialDelayMillis,
        maxDelayMillis = maxDelayMillis,
        maxAttempts = maxAttempts,
        jitterFraction = 0.0,
    )

    @Test
    fun `the default schedule starts at two seconds, doubles, and is capped at a minute`() {
        assertEquals(
            listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L),
            steady().delaysMillis,
        )
        // Attempt 6 would be 64s uncapped: the cap is what keeps a dead server
        // from being dialled once a minute forever at 128s and beyond.
        assertEquals(60_000L, steady().delayMillis(7))
        assertEquals(60_000L, steady().delayMillis(20))
    }

    @Test
    fun `the whole schedule is the attempts the policy allows`() {
        assertEquals(listOf(2_000L, 4_000L), steady(maxAttempts = 2).delaysMillis)
        assertEquals(listOf(2_000L), steady(maxAttempts = 1).delaysMillis)
        assertEquals(2, steady(maxAttempts = 2).maxAttempts)
    }

    @Test
    fun `jitter stays within the fraction either side of the backoff`() {
        val low = ReconnectPolicy(jitterFraction = 0.2, random = { 0.0 })
        val high = ReconnectPolicy(jitterFraction = 0.2, random = { 1.0 })
        val middle = ReconnectPolicy(jitterFraction = 0.2, random = { 0.5 })

        // −20%: two seconds becomes 1.6s; +20%: 2.4s; and the un-jittered middle
        // draw is exactly the backoff.
        assertEquals(1_600L, low.delayMillis(1))
        assertEquals(2_400L, high.delayMillis(1))
        assertEquals(2_000L, middle.delayMillis(1))

        // The band is a fraction of *each* attempt's base, not of the first one.
        assertEquals(6_400L, low.delayMillis(3))
        assertEquals(9_600L, high.delayMillis(3))
    }

    @Test
    fun `jitter never makes a wait negative, even at the widest fraction`() {
        val wildest = ReconnectPolicy(jitterFraction = 1.0, random = { 0.0 })
        assertEquals(0L, wildest.delayMillis(1))
        assertTrue(wildest.delaysMillis.all { it >= 0L })
    }

    @Test
    fun `an attempt is numbered from one`() {
        // A 0 here would be a caller mixing up the loop's counter, and returning
        // the initial delay for it would hide exactly that bug.
        assertThrows(IllegalArgumentException::class.java) { steady().delayMillis(0) }
        assertThrows(IllegalArgumentException::class.java) { steady().delayMillis(-1) }
    }

    @Test
    fun `a schedule that could not work is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy(initialDelayMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy(initialDelayMillis = 5_000, maxDelayMillis = 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy(maxAttempts = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy(jitterFraction = 1.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectPolicy(stableSessionMillis = -1)
        }
    }

    @Test
    fun `the defaults are the documented policy`() {
        val policy = ReconnectPolicy()
        assertEquals(2_000L, policy.initialDelayMillis)
        assertEquals(60_000L, policy.maxDelayMillis)
        assertEquals(6, policy.maxAttempts)
        assertEquals(0.2, policy.jitterFraction, 0.0)
        assertEquals(60_000L, policy.stableSessionMillis)
    }
}
