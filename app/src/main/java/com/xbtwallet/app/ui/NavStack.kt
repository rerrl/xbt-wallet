package com.xbtwallet.app.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * One entry on the navigation back stack: *where* to go, plus the argument that
 * destination needs.
 *
 * This replaces the old "a `Screen` enum value beside one shared id field"
 * arrangement. The argument now rides *with* the destination instead of in a
 * field that had to be cleared by hand, which is what a stack needs: every level
 * remembers its own signer, so popping from a signer's UTXOs lands back on *that*
 * signer's detail screen and not on whatever id was set last.
 *
 * @property screen the destination to render.
 * @property signerId the signer a per-signer screen is showing, or null for
 *   screens that need no argument.
 * @property address the address a receive screen was opened *from* — the one the
 *   user tapped in the addresses list — or null when the screen picks its own
 *   (the "first unused" rule). It rides here rather than in shared mutable state
 *   for the same reason the signer id does: a rotation must put the user back on
 *   the address they were looking at, not on whatever the screen would choose by
 *   default.
 */
data class Route(
    val screen: Screen,
    val signerId: String? = null,
    val address: String? = null,
) {
    /**
     * One string, for [NavStack.Saver]: the screen name, then the signer id, then
     * a requested address, each after its own `|`. Segments are emitted only as
     * far as they are needed, and an id is always present when an address is —
     * a lone `name||address` would decode back to a route with a null id, i.e. a
     * *different* route from the one written. Kept deliberately tiny — a `Bundle`
     * can hold a list of strings and nothing more exotic is needed.
     */
    fun encode(): String = when {
        signerId == null -> screen.name
        address == null -> "${screen.name}|$signerId"
        else -> "${screen.name}|$signerId|$address"
    }

    companion object {
        /**
         * The inverse of [encode]; anything unreadable falls back to [Screen.HOME].
         *
         * Forgiving on purpose. A stack saved by an older build holds only the
         * one- and two-segment forms, and those must still read back exactly as
         * they did; the address segment is simply absent and comes back null. The
         * separator is `|` and each field is split at the *first* `|` after it —
         * so a *later* segment may itself contain one without being truncated —
         * which is why a signer id must not contain one (they are UUIDs).
         */
        fun decode(text: String): Route {
            val separator = text.indexOf('|')
            if (separator < 0) return Route(screenNamed(text))
            val screen = screenNamed(text.substring(0, separator))
            val rest = text.substring(separator + 1)
            val addressSeparator = rest.indexOf('|')
            if (addressSeparator < 0) {
                return Route(screen = screen, signerId = rest.ifEmpty { null })
            }
            return Route(
                screen = screen,
                signerId = rest.substring(0, addressSeparator).ifEmpty { null },
                address = rest.substring(addressSeparator + 1).ifEmpty { null },
            )
        }

        private fun screenNamed(name: String): Screen =
            Screen.entries.firstOrNull { it.name == name } ?: Screen.HOME
    }
}

/**
 * The app's navigation back stack.
 *
 * A tiny list, not a navigation library: a handful of destinations still do not
 * justify a dependency (PLAN.md §2.4). The rules are the whole API —
 *
 * - **navigate** = [push] the destination you are going to; it becomes [current]
 *   and everything below it is remembered.
 * - **back** = [pop] exactly one level. The composable wires this to Android's
 *   back button via `BackHandler`, which is *enabled* only while [canGoBack], so
 *   back at HOME falls through to the system (leave the app) instead of being
 *   swallowed.
 * - [reset] throws the stack away and starts again — used where landing
 *   somewhere new should not leave the walk that got there behind it (a freshly
 *   saved signer lands on the Signers list, not on top of the import form).
 *
 * **Observability.** The entries live in a `SnapshotStateList`, so the app
 * recomposes when the stack changes without any extra state plumbing, and
 * [current] is just the top of the list.
 *
 * **Surviving rotation.** The stack is restored through [Saver]; each [Route]
 * becomes one string, so a configuration change — or the process being rebuilt
 * from its saved state — puts the user back on the screen they were on, with its
 * argument intact.
 */
@Stable
class NavStack(initial: Route = Route(Screen.HOME)) {

    private val entries = mutableStateListOf(initial)

    /** The destination to render: the top of the stack. */
    val current: Route get() = entries.last()

    /** How many destinations are on the stack. HOME alone is 1. */
    val depth: Int get() = entries.size

    /** True when there is somewhere to go back to. */
    val canGoBack: Boolean get() = entries.size > 1

    /** Every entry, bottom first. Read by tests and [Saver]. */
    val routes: List<Route> get() = entries.toList()

    /** Go to [route]: it is pushed on top and becomes [current]. */
    fun push(route: Route) {
        entries.add(route)
    }

    /**
     * Go back exactly one level.
     *
     * @return true when a level was popped; false at the root, where back is the
     *   system's to handle.
     */
    fun pop(): Boolean {
        if (entries.size <= 1) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    /** Throw the stack away and start again at [route] (HOME by default). */
    fun reset(route: Route = Route(Screen.HOME)) {
        entries.clear()
        entries.add(route)
    }

    companion object {
        /** A `rememberSaveable` saver; see the class doc for what it preserves. */
        val Saver: Saver<NavStack, Any> = listSaver(
            save = { stack -> stack.entries.map { it.encode() } },
            restore = { saved: List<String> ->
                if (saved.isEmpty()) {
                    NavStack()
                } else {
                    // First entry is the bottom of the stack; the rest are pushed
                    // in order so the top ends up where it was.
                    NavStack(Route.decode(saved.first())).also { stack ->
                        saved.drop(1).forEach { stack.push(Route.decode(it)) }
                    }
                }
            },
        )
    }
}
