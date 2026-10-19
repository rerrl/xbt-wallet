package com.xbtwallet.app.electrum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `a well formed v3 onion address has no problem`() {
        assertNull(ElectrumRouting.onionAddressProblem(V3_ONION))
        assertNull("case is not a problem", ElectrumRouting.onionAddressProblem(V3_ONION.uppercase()))
        assertNull("a trailing dot is a legal absolute name", ElectrumRouting.onionAddressProblem("$V3_ONION."))
        assertNull("a pasted address may carry spaces", ElectrumRouting.onionAddressProblem("  $V3_ONION  "))
    }

    @Test
    fun `a non-onion host is never a problem`() {
        assertNull(ElectrumRouting.onionAddressProblem("192.168.1.10"))
        assertNull(ElectrumRouting.onionAddressProblem("electrum.example.com"))
        assertNull(ElectrumRouting.onionAddressProblem("onion.example.com"))
        assertNull(ElectrumRouting.onionAddressProblem(""))
    }

    @Test
    fun `a sixteen character onion says v2 was retired`() {
        val problem = ElectrumRouting.onionAddressProblem("abcdefghijklmnop.onion")
        assertNotNull(problem)
        assertTrue("$problem", problem!!.contains("v2 onion addresses were retired network-wide"))
        assertTrue("$problem", problem.contains("v3 address"))
        assertTrue("the message must explain that Tor cannot connect: $problem", problem.contains("Tor cannot connect"))
    }

    @Test
    fun `a label that is not a v3 address is rejected`() {
        // Wrong length: 17 and 55 characters.
        assertEquals(
            ElectrumRouting.ONION_PROBLEM_NOT_V3,
            ElectrumRouting.onionAddressProblem("abcdefghijklmnopq.onion"),
        )
        assertEquals(
            ElectrumRouting.ONION_PROBLEM_NOT_V3,
            ElectrumRouting.onionAddressProblem(V3_LABEL.dropLast(1) + ".onion"),
        )
        // A single-character label is still an onion host, and still not a v3 one.
        assertEquals(ElectrumRouting.ONION_PROBLEM_NOT_V3, ElectrumRouting.onionAddressProblem("a.onion"))
        // Right length, wrong alphabet: 0, 1, '-' and uppercase leave base32.
        assertEquals(
            ElectrumRouting.ONION_PROBLEM_NOT_V3,
            ElectrumRouting.onionAddressProblem(V3_LABEL.dropLast(1) + "0.onion"),
        )
        assertEquals(
            ElectrumRouting.ONION_PROBLEM_NOT_V3,
            ElectrumRouting.onionAddressProblem(V3_LABEL.dropLast(1) + "1.onion"),
        )
        assertEquals(
            ElectrumRouting.ONION_PROBLEM_NOT_V3,
            ElectrumRouting.onionAddressProblem(V3_LABEL.dropLast(1) + "-.onion"),
        )
        assertEquals(
            ElectrumRouting.ONION_PROBLEM_NOT_V3,
            ElectrumRouting.onionAddressProblem(V3_LABEL.dropLast(1) + "8.onion"),
        )
    }

    @Test
    fun `a problem never goes unnoticed just because the label length looks right`() {
        // Every base32-legal 56-character label is fine; any character outside
        // base32 must be caught, wherever it sits in the label.
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        "0189-=_ ".forEach { bad ->
            val label = V3_LABEL.dropLast(1) + bad
            assertEquals(
                "a '$bad' at the end of a 56-character label must be rejected",
                ElectrumRouting.ONION_PROBLEM_NOT_V3,
                ElectrumRouting.onionAddressProblem("$label.onion"),
            )
            val inFront = bad + V3_LABEL.drop(1)
            assertEquals(
                "a '$bad' at the front of a 56-character label must be rejected",
                ElectrumRouting.ONION_PROBLEM_NOT_V3,
                ElectrumRouting.onionAddressProblem("$inFront.onion"),
            )
        }
        alphabet.forEach { good ->
            val label = V3_LABEL.dropLast(1) + good
            assertNull(
                "'$good' is in the base32 alphabet, so '$label.onion' must be accepted",
                ElectrumRouting.onionAddressProblem("$label.onion"),
            )
        }
    }

    @Test
    fun `every problem reads as a sentence rather than a code`() {
        val problems = listOf(
            ElectrumRouting.onionAddressProblem("abcdefghijklmnop.onion"),
            ElectrumRouting.onionAddressProblem("short.onion"),
            ElectrumRouting.onionAddressProblem(V3_LABEL.dropLast(1) + "0.onion"),
        )
        problems.forEach { problem ->
            assertNotNull("expected a message, got null", problem)
            assertTrue("$problem", problem!!.length > 15)
            assertTrue("a problem must name the onion it is about: $problem", problem.contains("onion"))
            assertFalse("a problem must not be a bare stack-trace code: $problem", problem.contains("Exception"))
        }
    }

    private companion object {
        /** A 56-character base32 label: the left half of a well-formed v3 address. */
        const val V3_LABEL = "rlvqju4vqc4ovfpn7avfl2qejgfp3ntpvdm7pgv7oz3zst3ptjqrxiid"

        /** The same, as the host a user would paste. */
        const val V3_ONION = "$V3_LABEL.onion"
    }
}
