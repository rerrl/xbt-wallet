package com.xbtwallet.app.ui.server

import com.xbtwallet.app.electrum.ElectrumProtocol

/**
 * The one bit the Server screen actually asks the user for, and the ports that
 * follow from it.
 *
 * The stored endpoint has always carried a full [ElectrumProtocol] (see
 * `data/ServerSettingsStore.kt`), and it still does — this object only translates
 * between that enum and the single **Use SSL** switch the screen now shows. The
 * switch is a presentation of the same fact, so an existing install needs no
 * data migration at all: a stored `SSL` reads back as a switch that is on, a
 * stored `TCP` as one that is off, and saving writes the enum again.
 *
 * Kept as plain Kotlin with no Android imports so the toggle/port rules can be
 * unit-tested on the JVM (see `app/src/test/.../ServerSslTest.kt`). The fiddly
 * cases are the ones worth having tests for: a custom port must survive a
 * toggle, and a port left at the *other* protocol's convention must not.
 */
object ServerSsl {

    /** The switch position for a stored protocol: `SSL` is on, `TCP` is off. */
    fun useSslFor(protocol: ElectrumProtocol): Boolean = protocol == ElectrumProtocol.SSL

    /** The protocol to store and dial for a given switch position. */
    fun protocolFor(useSsl: Boolean): ElectrumProtocol =
        if (useSsl) ElectrumProtocol.SSL else ElectrumProtocol.TCP

    /** The Electrum convention port for the switch position: 50002 / 50001. */
    fun defaultPortFor(useSsl: Boolean): Int = protocolFor(useSsl).defaultPort

    /**
     * Whether the entered port is the user's own choice rather than the
     * convention default for [useSsl].
     *
     * A blank field is not "custom": there is nothing entered yet, so the next
     * toggle is free to fill in a default.
     */
    fun isCustomPort(port: String, useSsl: Boolean): Boolean {
        val text = port.trim()
        return text.isNotEmpty() && text != defaultPortFor(useSsl).toString()
    }

    /**
     * The port to leave in the field after [toSsl] is selected.
     *
     * A port the user typed is theirs and is returned untouched — they may be
     * pointing at a non-standard endpoint, and silently rewriting it would undo
     * their edit. A port still sitting on a convention default just moves with
     * the transport (50001 \u2194 50002).
     */
    fun portAfterToggle(currentPort: String, toSsl: Boolean, wasCustom: Boolean): String =
        if (wasCustom) currentPort else defaultPortFor(toSsl).toString()
}
