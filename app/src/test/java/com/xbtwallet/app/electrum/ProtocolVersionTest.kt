package com.xbtwallet.app.electrum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of `server.version` negotiation: what the client asks for, how
 * it reads the server's answer, and how two protocol versions are ordered.
 *
 * These are the pieces that made the connection to a server requiring protocol
 * 1.8 fail. The old handshake sent a single version, `"1.4"`, and such a server
 * refuses it outright ("a client below protocol 1.8 cannot read [the extended
 * block headers]"). The fix is a `[min, max]` range plus reading back the
 * version the server picked; the socket-level behaviour is pinned in
 * [ElectrumClientTest].
 */
class ProtocolVersionTest {

    @Test
    fun `the request advertises a min-max range, not a single version`() {
        val params = serverVersionParams()

        assertEquals("xbt-wallet 0.0.1", params[0])
        val range = params[1]
        assertTrue("the second parameter must be a range, was: $range", range is List<*>)
        range as List<*>
        assertEquals(listOf("1.4", "1.8"), range)
    }

    @Test
    fun `the advertised range reaches the 1_8 a server may demand`() {
        // The regression guard: if the range's max ever drops back below 1.8,
        // the BLAKE2b server that refuses sub-1.8 clients becomes unreachable.
        @Suppress("UNCHECKED_CAST")
        val range = serverVersionParams()[1] as List<String>
        assertTrue(
            "a server requiring 1.8 must find it inside the advertised range",
            compareProtocolVersions(range[1], "1.8") >= 0,
        )
    }

    @Test
    fun `the negotiated version is read out of the server's reply`() {
        assertEquals("1.8", negotiatedProtocolVersion(listOf("Fulcrum 1.11.1", "1.8")))
        assertEquals("1.4", negotiatedProtocolVersion(listOf("electrs 0.10.0", "1.4")))
        // A JSON number rather than a string is still a version.
        assertEquals("1.8", negotiatedProtocolVersion(listOf("server", 1.8)))
    }

    @Test
    fun `a reply without a version is not an error`() {
        assertNull(negotiatedProtocolVersion(null))
        assertNull(negotiatedProtocolVersion("just a banner"))
        assertNull(negotiatedProtocolVersion(listOf("server-name-only")))
    }

    @Test
    fun `versions compare numerically, so 1_10 is above 1_8`() {
        assertTrue(compareProtocolVersions("1.10", "1.8") > 0)
        assertTrue(compareProtocolVersions("1.8", "1.10") < 0)
        assertTrue(compareProtocolVersions("1.8", "1.8") == 0)
        assertTrue(compareProtocolVersions("1.4", "1.8") < 0)
        assertTrue(compareProtocolVersions("2.0", "1.99") > 0)
        // A single-component version is the major alone.
        assertTrue(compareProtocolVersions("2", "1.9") > 0)
    }
}
