package com.xbtwallet.app.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.xbtwallet.app.data.SignerOrigin

/**
 * One entry on the navigation back stack: *where* to go, plus the arguments that
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
 *   For [Screen.SEND] this free-form slot instead carries the **selected
 *   outpoints** as a comma-joined `txid:vout` list (e.g.
 *   `"ab…:0,cd…:1"`) — the same segment, a different payload. `ChainUtxo.outpoint`
 *   already produces `txid:vout`, and txids are hex, so a comma is a safe
 *   separator and the segment needs no escaping. Keeping the selection on the
 *   route (rather than in shared state) is what makes a rotation on the Send
 *   screen land back on the same set of coins.
 *   The automatic send (a signing-device wallet's detail-screen Send) carries
 *   the literal `auto` in the same slot — a marker no outpoint can collide
 *   with, since the coins are re-selected from the snapshot rather than
 *   persisted. See `AUTO_SEND_MARKER`.
 *   For [Screen.SIGNER_TRANSACTION_DETAIL] the same slot carries the **txid**
 *   of the row that was tapped. The screen reads the row itself back out of the
 *   current snapshot by that id; putting row data on a persisted route would
 *   replay a stale amount after a refresh or a restore.
 * @property origin the import path the user *declared* by tapping a row of the
 *   Add-wallet chooser, carried onto the form that row opened. Only the two
 *   animated-BC-UR rows need it — both of them open the same scanner, and only
 *   the tap can say whether the device in hand is a SeedSigner or some other one
 *   — but it lives on the route like every other argument so a rotation cannot
 *   land the user on a form that has forgotten which row sent them there. Null
 *   for every screen that declares nothing, which is all of them but the two
 *   import forms.
 */
data class Route(
    val screen: Screen,
    val signerId: String? = null,
    val address: String? = null,
    val origin: SignerOrigin? = null,
) {
    /**
     * One string, for [NavStack.Saver]: the screen name, then the signer id, then
     * a requested address, then a declared origin, each after its own `|`.
     * Segments are emitted only as far as they are needed, and an id is always
     * present when an address is — a lone `name||address` would decode back to a
     * route with a null id, i.e. a *different* route from the one written.
     *
     * The origin is the one argument that can stand entirely on its own (the
     * import forms have no signer and no address yet), so it is written with the
     * two empty slots in front of it that put it in its own position:
     * `ADD_SIGNING_DEVICE|||SEEDSIGNER_UR`. That is not a stray segment — it
     * decodes back to exactly the route that wrote it. Kept deliberately tiny — a
     * `Bundle` can hold a list of strings and nothing more exotic is needed.
     */
    fun encode(): String = when {
        origin != null -> "${screen.name}|||${origin.name}"
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
         * they did; the later segments are simply absent and come back null. The
         * separator is `|` and each field is split at the *first* `|` after it —
         * so a *later* segment may itself contain one without being truncated —
         * which is why a signer id must not contain one (they are UUIDs) and why
         * the free-form segments are the last ones.
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
            val originSeparator = rest.indexOf('|', addressSeparator + 1)
            if (originSeparator < 0) {
                return Route(
                    screen = screen,
                    signerId = rest.substring(0, addressSeparator).ifEmpty { null },
                    address = rest.substring(addressSeparator + 1).ifEmpty { null },
                )
            }
            return Route(
                screen = screen,
                signerId = rest.substring(0, addressSeparator).ifEmpty { null },
                address = rest.substring(addressSeparator + 1, originSeparator).ifEmpty { null },
                origin = originNamed(rest.substring(originSeparator + 1)),
            )
        }

        private fun screenNamed(name: String): Screen =
            Screen.entries.firstOrNull { it.name == name } ?: Screen.HOME

        /**
         * A saved origin reads back by its enum name, and anything this build
         * does not know comes back null — the version of [screenNamed]'s
         * forgiveness, and for the same reason: a route is restored state, not
         * imported data, so the worst a stale name may do is leave the form it
         * opened with no declared origin (which the import screen then treats as
         * the SeedSigner path it has always been).
         */
        private fun originNamed(name: String): SignerOrigin? =
            SignerOrigin.entries.firstOrNull { it.name == name }
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
