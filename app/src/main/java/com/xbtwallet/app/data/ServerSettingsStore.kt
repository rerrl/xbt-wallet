package com.xbtwallet.app.data

import android.content.Context
import com.xbtwallet.app.electrum.ElectrumProtocol
import com.xbtwallet.app.electrum.ElectrumServer

/**
 * Persists the single private Electrum endpoint the user entered.
 *
 * SharedPreferences rather than DataStore: this is one small record written on
 * a settings screen, and the wallet keeps its dependency surface small on
 * purpose (PLAN.md §2.4). Both are sanctioned by the feature request.
 */
class ServerSettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Reads the stored endpoint, falling back to a sensible default. */
    fun load(): ElectrumServer {
        val protocol = ElectrumProtocol.fromNameOrNull(prefs.getString(KEY_PROTOCOL, null))
            ?: ElectrumProtocol.SSL
        return ElectrumServer(
            host = prefs.getString(KEY_HOST, "") ?: "",
            port = prefs.getInt(KEY_PORT, protocol.defaultPort),
            protocol = protocol,
        )
    }

    /** Stores the endpoint. `apply()` keeps this off the caller's thread. */
    fun save(server: ElectrumServer) {
        prefs.edit()
            .putString(KEY_HOST, server.host)
            .putInt(KEY_PORT, server.port)
            .putString(KEY_PROTOCOL, server.protocol.name)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "electrum_server"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_PROTOCOL = "protocol"
    }
}
