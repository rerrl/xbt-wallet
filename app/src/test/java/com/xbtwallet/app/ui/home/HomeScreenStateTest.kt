package com.xbtwallet.app.ui.home

import com.xbtwallet.app.data.KeyValueStore
import com.xbtwallet.app.data.ServerSettings
import com.xbtwallet.app.data.SignerStore
import com.xbtwallet.app.electrum.ElectrumProtocol
import com.xbtwallet.app.electrum.ElectrumServer
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HomeScreenState tests.
 *
 * The class exists so the home screen's rules — "how many signers are saved"
 * (a plain list, with no active selection) and "the server indicator follows a
 * real check, never a guess" — are testable without a UI or an emulator. It is
 * plain Kotlin over a [SignerStore] and a [ServerSettings], so in-memory fakes
 * drive it here; the ping itself is injected, so no socket and no clock are
 * involved (except in the one test that runs the heartbeat loop, which is
 * bounded).
 */
class HomeScreenStateTest {

    private class InMemoryStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    /**
     * A settable in-memory stand-in for the saved endpoint. A null host is what
     * [ServerSettingsStore] returns when the user has never entered one, which
     * is the "no server saved" case the home screen renders differently.
     */
    private class FakeServerSettings(var server: ElectrumServer? = null) : ServerSettings {
        override fun load(): ElectrumServer = server ?: ElectrumServer(
            host = "",
            port = ElectrumProtocol.SSL.defaultPort,
            protocol = ElectrumProtocol.SSL,
        )
    }

    /**
     * Signer store + server settings + a scripted ping, wired into a state.
     *
     * [results] answers the pings in order (the last value repeats), so a test
     * can say "up, then down" without any real network.
     */
    private class Fixture(
        server: ElectrumServer? = null,
        private val results: List<Boolean> = listOf(true),
    ) {
        val backing = InMemoryStore()
        private var counter = 0
        val store = SignerStore(
            storage = backing,
            now = { 0L },
            newId = { "id-${++counter}" },
        )
        val serverSettings = FakeServerSettings(server)

        /** Every endpoint the state checked, in order. */
        val pinged = mutableListOf<ElectrumServer>()

        /** [HomeScreenState.serverStatus] as seen *inside* each ping, i.e. in flight. */
        val statusDuringPing = mutableListOf<ServerStatus>()

        lateinit var state: HomeScreenState

        init {
            var call = 0
            val probe: suspend (ElectrumServer) -> Boolean = { target ->
                pinged += target
                statusDuringPing += state.serverStatus
                results.getOrElse(call++) { results.last() }
            }
            state = HomeScreenState(store, serverSettings, probe)
        }
    }

    @Test
    fun `a fresh state has no signers and no server`() {
        val fixture = Fixture()
        assertEquals(emptyList<String>(), fixture.state.signers)
        assertEquals(ServerStatus.NOT_CONFIGURED, fixture.state.serverStatus)
    }

    @Test
    fun `refresh picks up a signer written through the store`() {
        val fixture = Fixture()
        fixture.store.add("Added elsewhere", "zpub6xyz")

        assertEquals(emptyList<String>(), fixture.state.signers.map { it.name })
        fixture.state.refresh()

        assertEquals(listOf("Added elsewhere"), fixture.state.signers.map { it.name })
    }

    @Test
    fun `refresh drops a signer deleted behind the state's back`() {
        val fixture = Fixture()
        val signer = fixture.store.add("Temp", "zpub6xyz")
        fixture.state.refresh()
        assertEquals(1, fixture.state.signers.size)

        fixture.store.delete(signer.id)
        fixture.state.refresh()

        assertEquals(emptyList<String>(), fixture.state.signers.map { it.name })
    }

    @Test
    fun `signers are a plain list, not a selection`() {
        val fixture = Fixture()
        fixture.store.add("A", "xpubA")
        fixture.store.add("B", "xpubB")
        fixture.state.refresh()

        assertEquals(listOf("A", "B"), fixture.state.signers.map { it.name })
    }

    @Test
    fun `a saved server starts out as checking, never as disconnected`() {
        val fixture = Fixture(server = SERVER)

        // Seeded from the store, so the first frame after launch already reads
        // "Checking…" instead of showing a red dot that nothing justified.
        assertEquals(ServerStatus.CHECKING, fixture.state.serverStatus)
        assertTrue("nothing should have been checked yet", fixture.pinged.isEmpty())
        assertEquals("192.168.1.10:50002", fixture.state.serverLabel)
    }

    @Test
    fun `a successful check reports connected against the saved endpoint`() {
        val fixture = Fixture(server = SERVER)

        val status = runBlocking { fixture.state.checkServerStatus() }

        assertEquals(ServerStatus.CONNECTED, status)
        assertEquals(ServerStatus.CONNECTED, fixture.state.serverStatus)
        assertEquals(listOf(SERVER), fixture.pinged)
        assertEquals(listOf(ServerStatus.CHECKING), fixture.statusDuringPing)
    }

    @Test
    fun `a failed check reports disconnected`() {
        val fixture = Fixture(server = SERVER, results = listOf(false))

        val status = runBlocking { fixture.state.checkServerStatus() }

        assertEquals(ServerStatus.DISCONNECTED, status)
        assertEquals(ServerStatus.DISCONNECTED, fixture.state.serverStatus)
        // The label stays: the Home screen keeps the host on the failed line.
        assertEquals("192.168.1.10:50002", fixture.state.serverLabel)
    }

    @Test
    fun `the first check shows checking in flight, later checks keep the last answer`() {
        val fixture = Fixture(server = SERVER, results = listOf(true, false))

        runBlocking {
            fixture.state.checkServerStatus() // first check of the screen visit
            fixture.state.checkServerStatus(showChecking = false) // a later heartbeat
        }

        // In flight: "Checking…" then the previous answer — never blank, never a
        // sign flip, which is what keeps a 60-second poll from blinking.
        assertEquals(
            listOf(ServerStatus.CHECKING, ServerStatus.CONNECTED),
            fixture.statusDuringPing,
        )
        assertEquals(ServerStatus.DISCONNECTED, fixture.state.serverStatus)
        assertEquals(2, fixture.pinged.size)
    }

    @Test
    fun `checking with no server saved settles on not configured without pinging`() {
        val fixture = Fixture()

        val status = runBlocking { fixture.state.checkServerStatus() }

        assertEquals(ServerStatus.NOT_CONFIGURED, status)
        assertTrue("there is nothing to ping without an endpoint", fixture.pinged.isEmpty())
        assertNull(fixture.state.serverLabel)
    }

    @Test
    fun `the endpoint key follows a change of saved server`() {
        val fixture = Fixture()
        assertEquals("", fixture.state.serverKey)

        // What the Server screen does when the user edits the host.
        fixture.serverSettings.server = ElectrumServer(
            host = "10.0.0.2",
            port = 50001,
            protocol = ElectrumProtocol.TCP,
        )

        // The home screen keys its heartbeat on this, so the change re-checks
        // with the new settings instead of waiting out the old loop.
        assertEquals("10.0.0.2:50001:TCP", fixture.state.serverKey)
        assertEquals("10.0.0.2:50001", fixture.state.serverLabel)
    }

    @Test
    fun `the heartbeat checks immediately and keeps polling until cancelled`() {
        val fixture = Fixture(server = SERVER)

        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(intervalMillis = 25) }
            withTimeout(5_000) {
                while (fixture.pinged.size < 3) delay(5)
            }
            heartbeat.cancelAndJoin()
        }

        // Cancelling the coroutine is exactly how leaving the home screen (or
        // the process going to the background) stops the polling.
        assertEquals(ServerStatus.CONNECTED, fixture.state.serverStatus)
        assertTrue("expected repeated checks, saw ${fixture.pinged.size}", fixture.pinged.size >= 3)
    }

    private companion object {
        val SERVER = ElectrumServer(
            host = "192.168.1.10",
            port = 50002,
            protocol = ElectrumProtocol.SSL,
        )
    }
}
