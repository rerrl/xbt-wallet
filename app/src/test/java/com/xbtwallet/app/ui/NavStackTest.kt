package com.xbtwallet.app.ui

import androidx.compose.runtime.saveable.SaverScope
import com.xbtwallet.app.data.SignerOrigin
import com.xbtwallet.app.ui.signers.AddWalletRow
import com.xbtwallet.app.ui.signers.routeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigation back-stack tests.
 *
 * The stack exists so "back" has one unambiguous meaning — one level up — and so
 * each per-signer screen remembers *which* signer it is showing. Both rules are
 * pure data, so they are pinned here on the JVM: no Compose UI, no emulator, no
 * activity. Compose's snapshot state (the list is a `SnapshotStateList`) works
 * outside a composition, which is why this needs nothing beyond JUnit.
 */
class NavStackTest {

    @Test
    fun `a fresh stack is HOME with nowhere to go back to`() {
        val stack = NavStack()

        assertEquals(Route(Screen.HOME), stack.current)
        assertEquals(1, stack.depth)
        assertFalse(stack.canGoBack)
        assertFalse("back at the root belongs to the system", stack.pop())
        assertEquals(Route(Screen.HOME), stack.current)
    }

    @Test
    fun `back unwinds the add-signer walk one level at a time`() {
        val stack = NavStack()

        stack.push(Route(Screen.ADD_SIGNER))
        stack.push(Route(Screen.IMPORT_XPUB))

        // HOME → ADD_SIGNER → form
        assertEquals(Route(Screen.IMPORT_XPUB), stack.current)
        assertTrue(stack.pop())
        assertEquals(Route(Screen.ADD_SIGNER), stack.current)
        assertTrue(stack.pop())
        assertEquals(Route(Screen.HOME), stack.current)
        assertFalse(stack.pop())
    }

    /**
     * The dice-create walk: create → dice → the words → finish, and back down
     * the same way. Every entry is a bare destination — the recovery words and
     * the passphrase are the walk's product, and they deliberately ride the
     * shared in-memory flow holder rather than any of these entries, because the
     * stack is saved state.
     */
    @Test
    fun `back unwinds the create-hot-wallet walk one level at a time`() {
        val stack = NavStack()

        stack.push(Route(Screen.HOT_WALLET_CREATE))
        stack.push(Route(Screen.HOT_WALLET_DICE))
        stack.push(Route(Screen.HOT_WALLET_BACKUP))
        stack.push(Route(Screen.HOT_WALLET_FINISH))

        assertEquals(
            "no entry on the walk carries an argument",
            listOf(
                Route(Screen.HOME),
                Route(Screen.HOT_WALLET_CREATE),
                Route(Screen.HOT_WALLET_DICE),
                Route(Screen.HOT_WALLET_BACKUP),
                Route(Screen.HOT_WALLET_FINISH),
            ),
            stack.routes,
        )

        // Back from the finish screen is the words, then the dice, then the
        // create screen, then Home.
        assertTrue(stack.pop())
        assertEquals(Screen.HOT_WALLET_BACKUP, stack.current.screen)
        assertTrue(stack.pop())
        assertEquals(Screen.HOT_WALLET_DICE, stack.current.screen)
        assertTrue(stack.pop())
        assertEquals(Screen.HOT_WALLET_CREATE, stack.current.screen)
        assertTrue(stack.pop())
        assertEquals(Route(Screen.HOME), stack.current)
        assertFalse(stack.pop())
    }

    /**
     * A rotation lands the user back on the same step of the create walk — the
     * destinations survive, and nothing else does (the words are in a plain
     * `remember`, in memory, and are gone if the process was rebuilt rather than
     * merely the activity).
     */
    @Test
    fun `the saver round-trips the create walk's bare screens`() {
        val stack = NavStack()
        stack.push(Route(Screen.HOT_WALLET_DICE))
        stack.push(Route(Screen.HOT_WALLET_BACKUP))

        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = with(NavStack.Saver) { with(scope) { save(stack) } }

        assertEquals(listOf("HOME", "HOT_WALLET_DICE", "HOT_WALLET_BACKUP"), saved)
        assertEquals(stack.routes, NavStack.Saver.restore(saved!!)!!.routes)
    }

    /**
     * The chooser's second branch: **Add signing device**. It is a sibling of the
     * text-QR form, not a step in front of it — back from it returns to the
     * chooser, and the two branches never stack on top of each other.
     */
    @Test
    fun `back unwinds the signing-device walk one level at a time`() {
        val stack = NavStack()

        stack.push(Route(Screen.ADD_SIGNER))
        stack.push(Route(Screen.ADD_SIGNING_DEVICE))

        // HOME → ADD_SIGNER → the animated-export form
        assertEquals(Route(Screen.ADD_SIGNING_DEVICE), stack.current)
        assertTrue(stack.pop())
        assertEquals(Route(Screen.ADD_SIGNER), stack.current)
        assertTrue(stack.pop())
        assertEquals(Route(Screen.HOME), stack.current)
    }

    /**
     * The walk each row of the Add-wallet chooser starts, pushed exactly as the
     * app pushes it (`routeFor`). The two animated rows land on the *same*
     * screen, so the origin on the entry is the only thing that survives the
     * walk — and it has to, because the form behind it stamps the saved wallet
     * with it.
     */
    @Test
    fun `each chooser row pushes its own form, with its declared origin`() {
        AddWalletRow.entries.forEach { row ->
            val stack = NavStack()
            stack.push(Route(Screen.ADD_SIGNER))
            stack.push(routeFor(row))

            assertEquals("row $row lands on its form", routeFor(row).screen, stack.current.screen)
            assertEquals("row $row declares its origin", routeFor(row).origin, stack.current.origin)

            // Back from the form returns to the chooser that sent you there.
            assertTrue(stack.pop())
            assertEquals(Route(Screen.ADD_SIGNER), stack.current)
        }
    }

    @Test
    fun `the two animated rows share a screen but not an origin`() {
        val seedsigner = routeFor(AddWalletRow.SEEDSIGNER)
        val other = routeFor(AddWalletRow.OTHER_SIGNING_DEVICE)

        assertEquals("one screen for both", seedsigner.screen, other.screen)
        assertTrue("but the origins must differ", seedsigner.origin != other.origin)
    }

    @Test
    fun `the two add branches are separate destinations`() {
        val stack = NavStack()
        stack.push(Route(Screen.ADD_SIGNER))
        stack.push(Route(Screen.ADD_SIGNING_DEVICE))

        // Back to the chooser, then down the other branch: the watch-only form is
        // its own entry, carrying no argument from the branch just left.
        assertTrue(stack.pop())
        stack.push(Route(Screen.IMPORT_XPUB))

        assertEquals(Route(Screen.IMPORT_XPUB), stack.current)
        assertEquals("neither add form carries a signer", null, stack.current.signerId)
    }

    @Test
    fun `a per-signer screen carries its own signer in the stack entry`() {
        val stack = NavStack()
        stack.push(Route(Screen.SETTINGS))
        stack.push(Route(Screen.SIGNER_DETAIL, signerId = "id-7"))
        stack.push(Route(Screen.SIGNER_UTXOS, signerId = "id-7"))

        assertEquals("id-7", stack.current.signerId)

        // Back from UTXOs returns to *that* signer's detail screen, argument
        // intact — the whole reason the argument lives on the entry.
        assertTrue(stack.pop())
        assertEquals(Screen.SIGNER_DETAIL, stack.current.screen)
        assertEquals("id-7", stack.current.signerId)

        assertTrue(stack.pop())
        assertEquals(Screen.SETTINGS, stack.current.screen)
        assertNull("a screen that needs no argument carries none", stack.current.signerId)
    }

    @Test
    fun `opening a second signer does not inherit the first one's id`() {
        val stack = NavStack()
        stack.push(Route(Screen.SIGNER_DETAIL, signerId = "id-1"))
        stack.pop()
        stack.push(Route(Screen.SIGNER_DETAIL, signerId = "id-2"))

        assertEquals("id-2", stack.current.signerId)
    }

    @Test
    fun `reset restarts the stack, optionally somewhere other than HOME`() {
        val stack = NavStack()
        stack.push(Route(Screen.ADD_SIGNER))
        stack.push(Route(Screen.IMPORT_XPUB))

        // What saving the import form does: the walk must not sit under the list.
        stack.reset()
        assertEquals(listOf(Route(Screen.HOME)), stack.routes)
        stack.push(Route(Screen.SETTINGS))
        assertEquals(listOf(Route(Screen.HOME), Route(Screen.SETTINGS)), stack.routes)

        stack.reset(Route(Screen.SERVER))
        assertEquals(listOf(Route(Screen.SERVER)), stack.routes)
        assertFalse(stack.canGoBack)
    }

    @Test
    fun `the saver round-trips the stack with its arguments`() {
        val stack = NavStack()
        stack.push(Route(Screen.SETTINGS))
        // A real signer id: a UUID (see SignerStore). Ids never contain the `|`
        // the route string is split on, so each entry's arguments come back
        // intact; see RouteCodecTest for the codec's own rules.
        val signerId = "3f2a6b1e-6c3d-4c0f-9a1b-2d7e5f8c4a11"
        stack.push(Route(Screen.SIGNER_DETAIL, signerId = signerId))
        // And a form opened with a declared origin, which is the argument that
        // rides alone.
        stack.push(Route(Screen.ADD_SIGNING_DEVICE, origin = SignerOrigin.SIGNING_DEVICE_UR))

        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        // `Saver.save` is a member *with* a SaverScope receiver, so both
        // receivers have to be in scope to call it.
        val saved = with(NavStack.Saver) { with(scope) { save(stack) } }
        val restored = NavStack.Saver.restore(saved!!)!!

        assertEquals(stack.routes, restored.routes)
        assertEquals(Route(Screen.SIGNER_DETAIL, signerId = signerId), restored.routes[2])
        assertEquals(
            Route(Screen.ADD_SIGNING_DEVICE, origin = SignerOrigin.SIGNING_DEVICE_UR),
            restored.current,
        )
    }

    @Test
    fun `the saver restores HOME from nothing`() {
        val restored = NavStack.Saver.restore(emptyList<String>())!!

        assertEquals(Route(Screen.HOME), restored.current)
        assertEquals(1, restored.depth)
    }

    @Test
    fun `an unreadable saved screen falls back to HOME`() {
        assertEquals(Route(Screen.HOME), Route.decode("NOT_A_SCREEN"))
    }
}
