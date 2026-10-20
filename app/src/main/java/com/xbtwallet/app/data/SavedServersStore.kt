package com.xbtwallet.app.data

import com.xbtwallet.app.electrum.ElectrumProtocol
import com.xbtwallet.app.electrum.JsonParseException
import com.xbtwallet.app.electrum.MiniJson

/**
 * One named server connection the user saved: a [name] they will recognise in a
 * picker, plus the endpoint behind it.
 *
 * [name] is *this* app's label — it is never sent anywhere, and it is the only
 * part of a preset the picker ever shows. [host] is the dial target and, like
 * every other saved endpoint in the app, it stays out of the UI: the presets
 * menu lists names, never host:port (see the privacy note on
 * [SavedServersStore]). [port] and [protocol] are stored exactly as the Server
 * screen's fields held them, so applying a preset rounds back to the same form
 * state the user saved.
 */
data class SavedServer(
    val name: String,
    val host: String,
    val port: Int,
    val protocol: ElectrumProtocol,
)

/**
 * Persists the user's named server presets as one JSON blob.
 *
 * A preset is a convenience, not a second source of truth: the app's *current*
 * endpoint is still the single record in `data/ServerSettingsStore.kt`, and a
 * preset only refills the Server screen's fields so the user can Test/Save from
 * there. Nothing in this store dials anything.
 *
 * The shape mirrors [SignerStore] deliberately — a [KeyValueStore] rather than a
 * `Context` (so it is unit-testable on the JVM with an in-memory map), one JSON
 * array written under a single key, and [MiniJson] rather than a JSON library
 * (PLAN.md §2.4, lean and auditable). The key is [STORAGE_KEY] and each element
 * is `{"name":…,"host":…,"port":…,"protocol":"SSL"|"TCP"}`; the protocol is
 * written by enum *name*, the same wire form `ServerSettingsStore` uses for the
 * live endpoint.
 *
 * **Nothing here throws, and nothing here loses the rest of the list.** An
 * absent or unreadable blob reads as *no presets* — a watch-only convenience is
 * not worth taking composition down for — and an element this build cannot
 * interpret (say, a protocol it does not know) is skipped on its own, leaving
 * its neighbours intact.
 *
 * **Privacy.** A preset's `host` is a server address, and the standing rule on
 * this project is that a saved server address is never rendered, logged or
 * committed. The store keeps the host so the preset can *do* its job; every
 * caller must keep it off screen (the picker shows [SavedServer.name] only).
 */
class SavedServersStore(private val storage: KeyValueStore) {

    /** Every saved preset, in the order the user first added them. */
    fun list(): List<SavedServer> = read()

    /**
     * Saves [preset], overwriting the entry with the same `name` if there is one.
     *
     * Matching is on the name *exactly* — case-sensitive, no trimming — because
     * the name is the user's own label and the store has no business rewriting
     * it. An existing name is replaced **in place**, keeping its list position,
     * so re-saving a preset the user has tweaked does not shuffle the picker
     * under them; a new name is appended.
     */
    fun save(preset: SavedServer) {
        val current = read()
        val index = current.indexOfFirst { it.name == preset.name }
        val next = if (index >= 0) {
            current.toMutableList().also { it[index] = preset }
        } else {
            current + preset
        }
        write(next)
    }

    /** Removes the preset named [name]; a name that is not there is a no-op. */
    fun delete(name: String) {
        write(read().filterNot { it.name == name })
    }

    /** Overwrites the whole list — the one write path, so there is one JSON shape. */
    private fun write(presets: List<SavedServer>) {
        val json = MiniJson.encode(
            presets.map { preset ->
                mapOf(
                    "name" to preset.name,
                    "host" to preset.host,
                    "port" to preset.port,
                    "protocol" to preset.protocol.name,
                )
            },
        )
        storage.putString(STORAGE_KEY, json)
    }

    /** Reads the stored list; an absent key and an unreadable blob both read empty. */
    private fun read(): List<SavedServer> {
        val raw = storage.getString(STORAGE_KEY) ?: return emptyList()
        val entries = try {
            MiniJson.parse(raw) as? List<*> ?: return emptyList()
        } catch (e: JsonParseException) {
            return emptyList()
        }
        return entries.mapNotNull(::toSavedServer)
    }

    /**
     * Turns one parsed element back into a [SavedServer], or null when it is not
     * one — a missing field, a non-numeric port, or a protocol string this build
     * does not know (guessing a transport would be the dangerous way to be
     * wrong). A skipped element is only that element: its neighbours still load.
     */
    private fun toSavedServer(entry: Any?): SavedServer? {
        val map = entry as? Map<*, *> ?: return null
        val name = map["name"] as? String ?: return null
        val host = map["host"] as? String ?: return null
        val port = (map["port"] as? Number)?.toInt() ?: return null
        val protocol = ElectrumProtocol.fromNameOrNull(map["protocol"] as? String) ?: return null
        return SavedServer(name = name, host = host, port = port, protocol = protocol)
    }

    companion object {
        /** SharedPreferences key holding the whole preset list as one JSON array. */
        const val STORAGE_KEY = "savedServers"
    }
}
