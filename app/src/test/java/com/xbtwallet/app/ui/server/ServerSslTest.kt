package com.xbtwallet.app.ui.server

import com.xbtwallet.app.electrum.ElectrumProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the Server screen's single **Use SSL** switch.
 *
 * Two things are being pinned down here. First, the switch and the stored
 * [ElectrumProtocol] must be exact inverses of each other, or an existing
 * install's saved protocol would come back as the opposite transport. Second —
 * and this is where the screen's old protocol radio buttons got it wrong — the
 * port field must follow the switch only while it is still a convention default.
 */
class ServerSslTest {

    @Test
    fun `stored protocol maps to the switch position`() {
        assertTrue(ServerSsl.useSslFor(ElectrumProtocol.SSL))
        assertFalse(ServerSsl.useSslFor(ElectrumProtocol.TCP))
    }

    @Test
    fun `switch position maps back to the stored protocol`() {
        assertEquals(ElectrumProtocol.SSL, ServerSsl.protocolFor(useSsl = true))
        assertEquals(ElectrumProtocol.TCP, ServerSsl.protocolFor(useSsl = false))
    }

    @Test
    fun `mapping is an exact round trip in both directions`() {
        for (protocol in ElectrumProtocol.entries) {
            assertEquals(protocol, ServerSsl.protocolFor(ServerSsl.useSslFor(protocol)))
        }
        assertEquals(true, ServerSsl.useSslFor(ServerSsl.protocolFor(true)))
        assertEquals(false, ServerSsl.useSslFor(ServerSsl.protocolFor(false)))
    }

    @Test
    fun `default ports follow the convention`() {
        assertEquals(50002, ServerSsl.defaultPortFor(useSsl = true))
        assertEquals(50001, ServerSsl.defaultPortFor(useSsl = false))
        assertEquals(ElectrumProtocol.SSL.defaultPort, ServerSsl.defaultPortFor(useSsl = true))
        assertEquals(ElectrumProtocol.TCP.defaultPort, ServerSsl.defaultPortFor(useSsl = false))
    }

    @Test
    fun `blank port is never custom`() {
        assertFalse(ServerSsl.isCustomPort("", useSsl = true))
        assertFalse(ServerSsl.isCustomPort("", useSsl = false))
        assertFalse(ServerSsl.isCustomPort("   ", useSsl = true))
        assertFalse(ServerSsl.isCustomPort("   ", useSsl = false))
    }

    @Test
    fun `a port matching the switch default is not custom`() {
        assertFalse(ServerSsl.isCustomPort("50002", useSsl = true))
        assertFalse(ServerSsl.isCustomPort("50001", useSsl = false))
        // Surrounding whitespace is not an edit the user made.
        assertFalse(ServerSsl.isCustomPort("  50002  ", useSsl = true))
    }

    @Test
    fun `the other protocol's default is custom for this one`() {
        assertTrue(ServerSsl.isCustomPort("50001", useSsl = true))
        assertTrue(ServerSsl.isCustomPort("50002", useSsl = false))
    }

    @Test
    fun `any other port is custom`() {
        assertTrue(ServerSsl.isCustomPort("50003", useSsl = true))
        assertTrue(ServerSsl.isCustomPort("50003", useSsl = false))
        assertTrue(ServerSsl.isCustomPort("60001", useSsl = true))
    }

    @Test
    fun `a non-numeric port is custom - the field has one, the default is not it`() {
        assertTrue(ServerSsl.isCustomPort("abc", useSsl = true))
        assertTrue(ServerSsl.isCustomPort("abc", useSsl = false))
    }

    @Test
    fun `a port left on a default snaps to the new transport's default`() {
        // 50001 -> SSL, 50002 -> TCP: the user never touched the field.
        assertEquals("50002", ServerSsl.portAfterToggle("50001", toSsl = true, wasCustom = false))
        assertEquals("50001", ServerSsl.portAfterToggle("50002", toSsl = false, wasCustom = false))
        // Toggling back is symmetric.
        assertEquals("50001", ServerSsl.portAfterToggle("50002", toSsl = false, wasCustom = false))
        assertEquals("50002", ServerSsl.portAfterToggle("50001", toSsl = true, wasCustom = false))
    }

    @Test
    fun `a port left blank is filled with the new transport's default`() {
        assertEquals("50002", ServerSsl.portAfterToggle("", toSsl = true, wasCustom = false))
        assertEquals("50001", ServerSsl.portAfterToggle("", toSsl = false, wasCustom = false))
    }

    @Test
    fun `a custom port is returned unchanged`() {
        assertEquals("60001", ServerSsl.portAfterToggle("60001", toSsl = true, wasCustom = true))
        assertEquals("60001", ServerSsl.portAfterToggle("60001", toSsl = false, wasCustom = true))
        assertEquals("50003", ServerSsl.portAfterToggle("50003", toSsl = true, wasCustom = true))
    }

    /**
     * The adversarial stored pair: a TCP endpoint saved on port 50002, which is
     * SSL's convention port.
     *
     * Read naively, 50002 looks like "the default" — but for the *other*
     * transport. The screen must therefore come up with the switch off (the
     * stored protocol is TCP) and the port already marked custom, so flipping
     * the switch on leaves 50002 in place. If the port were treated as a default
     * here, selecting SSL would rewrite the user's endpoint.
     */
    @Test
    fun `TCP stored on SSL's port initialises as custom and survives the toggle`() {
        val storedProtocol = ElectrumProtocol.TCP
        val storedPort = "50002"

        val useSsl = ServerSsl.useSslFor(storedProtocol)
        assertFalse("a TCP endpoint must not come back as SSL", useSsl)

        val portCustomized = ServerSsl.isCustomPort(storedPort, useSsl)
        assertTrue("50002 is not TCP's default port, so it is the user's own", portCustomized)

        // Toggling SSL on: portCustomized is true, so the port is untouched.
        val afterToggle = ServerSsl.portAfterToggle(storedPort, toSsl = true, wasCustom = true)
        assertEquals("50002", afterToggle)

        // And what gets stored is the protocol the switch now reads.
        assertEquals(ElectrumProtocol.SSL, ServerSsl.protocolFor(useSsl = true))
        // 50002 is SSL's default, so the field is no longer custom at that point.
        assertFalse(ServerSsl.isCustomPort(afterToggle, useSsl = true))
    }
}
