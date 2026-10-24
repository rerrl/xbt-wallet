package com.xbtwallet.app.ui

import com.xbtwallet.app.data.SignerOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The route string: how one back-stack entry survives a configuration change.
 *
 * A [Route] is flattened to `name`, `name|signerId`, `name|signerId|address` or
 * `name|||origin` and read back, and the *reader* is what has to be forgiving:
 * the strings a previous build wrote are still on the stack after an upgrade, so
 * the one- and two-segment forms must keep decoding exactly as they did. The
 * three-segment form is what the receive screen added — the requested address
 * rides with the route rather than in shared mutable state, so a specific address
 * survives a rotation instead of collapsing back to "the first unused one". The
 * four-segment form is the declared import origin, which the Add-wallet chooser
 * puts on the form it opens; it is the one argument that can stand alone, so its
 * empty leading slots are part of the encoding rather than stray text.
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
    fun `a declared origin is the fourth segment and round-trips`() {
        val route = Route(Screen.ADD_SIGNING_DEVICE, origin = SignerOrigin.SEEDSIGNER_UR)

        // The import forms carry no signer and no address yet, so the origin keeps
        // the empty slots in front of it that put it in its own position — not a
        // stray segment, but the honest encoding of "only the origin is set".
        assertEquals("ADD_SIGNING_DEVICE|||SEEDSIGNER_UR", route.encode())
        assertEquals(route, Route.decode(route.encode()))
    }

    @Test
    fun `each declared origin round-trips`() {
        SignerOrigin.entries.forEach { origin ->
            val route = Route(Screen.IMPORT_XPUB, origin = origin)

            assertEquals(origin, Route.decode(route.encode()).origin)
        }
    }

    @Test
    fun `a route with an origin and nothing else decodes to null arguments before it`() {
        val route = Route.decode("ADD_SIGNING_DEVICE|||SIGNING_DEVICE_UR")

        assertEquals(Screen.ADD_SIGNING_DEVICE, route.screen)
        assertNull(route.signerId)
        assertNull(route.address)
        assertEquals(SignerOrigin.SIGNING_DEVICE_UR, route.origin)
    }

    @Test
    fun `a signer id and an address still decode with no origin`() {
        val route = Route.decode("RECEIVE|id-7|bc1qexample")

        assertEquals("id-7", route.signerId)
        assertEquals("bc1qexample", route.address)
        assertNull("nothing declared an origin on this route", route.origin)
    }

    @Test
    fun `an unreadable saved origin decodes to null rather than guessing`() {
        // The enum name these are saved under is an implementation detail of the
        // build that wrote the stack: an unknown one must not be read as some
        // other path, because that would claim a wallet came from a device the
        // user never tapped.
        val route = Route.decode("ADD_SIGNING_DEVICE|||SOMETHING_NEW")

        assertEquals(Screen.ADD_SIGNING_DEVICE, route.screen)
        assertNull(route.origin)
    }

    @Test
    fun `a send route carries its outpoints in the address slot and round-trips`() {
        // The free-form `address` segment doubles as the selected-outpoint list
        // for SEND: a comma-joined `txid:vout,…`. `|` is the only forbidden
        // character in a later segment, and txids are hex, so commas survive
        // encode/decode without any escaping — which is the whole reason the
        // selection can ride on the route and survive a rotation.
        val route = Route(Screen.SEND, signerId = "id-7", address = "ab12cd34:0,ef56ab78:1")

        assertEquals("SEND|id-7|ab12cd34:0,ef56ab78:1", route.encode())
        assertEquals(route, Route.decode(route.encode()))
    }

    @Test
    fun `legacy one- and two-segment strings decode unchanged`() {
        assertEquals(Route(Screen.SETTINGS), Route.decode("SETTINGS"))
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

    /**
     * The two screens the dice-create walk added carry nothing at all: they are
     * bare names, before and after a round trip, and no argument of any kind
     * rides them.
     *
     * That is the point of the phase rather than an accident of it: the recovery
     * words and the passphrase are the two things this walk produces, and a route
     * is saved state — written to the instance-state bundle and kept across a
     * process restart. There is deliberately nowhere on the route for them to sit.
     */
    @Test
    fun `the create walk's new screens encode as bare names with no argument`() {
        val walk = listOf(Screen.HOT_WALLET_BACKUP, Screen.HOT_WALLET_FINISH)

        walk.forEach { screen ->
            val route = Route(screen)

            assertEquals("a bare screen name, and nothing after it", screen.name, route.encode())
            assertEquals(route, Route.decode(route.encode()))
            assertNull("no signer on this route", route.signerId)
            assertNull("no address on this route", route.address)
            assertNull("no origin on this route", route.origin)
        }

        assertEquals("HOT_WALLET_BACKUP", Route(Screen.HOT_WALLET_BACKUP).encode())
        assertEquals("HOT_WALLET_FINISH", Route(Screen.HOT_WALLET_FINISH).encode())
        assertEquals(Route(Screen.HOT_WALLET_BACKUP), Route.decode("HOT_WALLET_BACKUP"))
        assertEquals(Route(Screen.HOT_WALLET_FINISH), Route.decode("HOT_WALLET_FINISH"))
    }
}