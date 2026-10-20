package com.xbtwallet.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The route string: how one back-stack entry survives a configuration change.
 *
 * A [Route] is flattened to `name`, `name|signerId` or `name|signerId|address`
 * and read back, and the *reader* is what has to be forgiving: the strings a
 * previous build wrote are still on the stack after an upgrade, so the one- and
 * two-segment forms must keep decoding exactly as they did. The three-segment
 * form is what the receive screen added — the requested address rides with the
 * route rather than in shared mutable state, so a specific address survives a
 * rotation instead of collapsing back to "the first unused one".
 *
 * The separator is `|`, so a signer id must not contain one; ids are UUIDs
 * ([com.xbtwallet.app.data.SignerStore]), and each segment is split on the
 * *first* `|` after it, never re-split, so a later segment may contain one.
 */
class RouteCodecTest {

    @Test
    fun `a screen with no argument encodes as its name alone`() {
        assertEquals("HOME", Route(Screen.HOME).encode())
        assertEquals(Route(Screen.HOME), Route.decode("HOME"))
    }

    @Test
    fun `a signer id is the second segment`() {
        val route = Route(Screen.SIGNER_DETAIL, signerId = "id-7")

        assertEquals("SIGNER_DETAIL|id-7", route.encode())
        assertEquals(route, Route.decode(route.encode()))
    }

    @Test
    fun `a requested address is the third segment and round-trips`() {
        val route = Route(Screen.RECEIVE, signerId = "id-7", address = "bc1qexample")

        assertEquals("RECEIVE|id-7|bc1qexample", route.encode())
        assertEquals(route, Route.decode(route.encode()))
    }

    @Test
    fun `an address rides without an id rather than as a stray segment`() {
        // Wiring always passes both, but the encoder must not emit a bare third
        // segment with an empty second one: `RECEIVE||addr` would decode back to
        // a null id and an address, which is a *different* route from the one
        // written. Omitting the trailing segments keeps encode/decode honest.
        val route = Route(Screen.RECEIVE, address = "bc1qexample")

        assertEquals("RECEIVE", route.encode())
        assertNull(Route.decode(route.encode()).address)
    }

    @Test
    fun `legacy one- and two-segment strings decode unchanged`() {
        assertEquals(Route(Screen.SIGNERS), Route.decode("SIGNERS"))
        assertEquals(
            Route(Screen.SIGNER_ADDRESSES, signerId = "id-7"),
            Route.decode("SIGNER_ADDRESSES|id-7"),
        )
    }

    @Test
    fun `an empty trailing segment decodes to null, not the empty string`() {
        val route = Route.decode("SIGNER_DETAIL|id-7|")

        assertEquals("id-7", route.signerId)
        assertNull(route.address)
    }

    @Test
    fun `an unreadable saved screen falls back to HOME`() {
        assertEquals(Route(Screen.HOME), Route.decode("NOT_A_SCREEN"))
        assertEquals(Route(Screen.HOME), Route.decode(""))
    }
}