package com.xbtwallet.app.electrum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routing rule in isolation: an `.onion` host means Tor, everything else
 * means a direct connection, and neither choice changes the Electrum port.
 *
 * No sockets and no Tor daemon here — this is the decision, not the plumbing.
 * `ElectrumClientTorTest` covers what the client does with the decision.
 */
class ElectrumRoutingTest {

    @Test
    fun `onion hosts are recognised`() {
        assertTrue(ElectrumRouting.isOnionHost("abcdefghijklmnop.onion"))
        assertTrue(ElectrumRouting.isOnionHost("ABCDEFGHIJKLMNOP.ONION"))
        assertTrue("a pasted address often carries spaces", ElectrumRouting.isOnionHost("  example.onion  "))
        assertTrue("an absolute DNS name may carry a trailing dot", ElectrumRouting.isOnionHost("example.onion."))
    }

    @Test
    fun `clearnet hosts and lookalikes are not onion hosts`() {
        assertFalse(ElectrumRouting.isOnionHost("192.168.1.10"))
        assertFalse(ElectrumRouting.isOnionHost("electrum.example.com"))
        assertFalse("an onion-looking label is not a hidden service", ElectrumRouting.isOnionHost("onion.example.com"))
        assertFalse(ElectrumRouting.isOnionHost("notonion"))
        assertFalse(ElectrumRouting.isOnionHost(""))
    }

    @Test
    fun `onion hosts imply Tor and clearnet hosts do not`() {
        assertEquals(ElectrumRoute.TOR, ElectrumRouting.route("example.onion"))
        assertEquals(ElectrumRoute.DIRECT, ElectrumRouting.route("127.0.0.1"))
        assertEquals(ElectrumRoute.DIRECT, ElectrumRouting.route("electrum.example.com"))
    }

    @Test
    fun `an onion endpoint keeps the Electrum protocol's default port`() {
        // A hidden service forwards the same convention ports as a public server;
        // Tor's own SOCKS port (9050) is never offered as an Electrum port.
        assertEquals(50001, ElectrumRouting.defaultPort(ElectrumProtocol.TCP))
        assertEquals(50002, ElectrumRouting.defaultPort(ElectrumProtocol.SSL))
        assertEquals(50001, ElectrumProtocol.TCP.defaultPort)
        assertEquals(50002, ElectrumProtocol.SSL.defaultPort)
    }

    @Test
    fun `the log line names the route`() {
        assertTrue(ElectrumRouting.describe("example.onion").contains("Tor"))
        assertFalse(ElectrumRouting.describe("example.onion").contains("direct"))
        assertTrue(ElectrumRouting.describe("192.168.1.10").contains("direct"))
    }
}
