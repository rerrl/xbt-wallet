package com.xbtwallet.app.ui.home

import com.xbtwallet.app.data.KeyValueStore
import com.xbtwallet.app.data.ServerSettings
import com.xbtwallet.app.data.SignerStore
import com.xbtwallet.app.electrum.ElectrumProtocol
import com.xbtwallet.app.electrum.ElectrumServer
import com.xbtwallet.app.electrum.TorPhase
import com.xbtwallet.app.electrum.TorStatus
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HomeScreenState tests.
 *
 * The class exists so the home screen's rules — "how many signers are saved"
 * (a plain list, with no active selection) and "the server indicator follows a
 * real check, never a guess" — are testable without a UI or an emulator. It is
 * plain Kotlin over a [SignerStore] and a [ServerSettings], so in-memory fakes
 * drive it here; the ping and the Tor status are both injected, so no socket and
 * no clock are involved (except in the one test that runs the heartbeat loop,
 * which is bounded).
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
     * [com.xbtwallet.app.data.ServerSettingsStore] returns when the user has
     * never entered one, which is the "no server saved" case the home screen
     * renders differently.
     */
    private class FakeServerSettings(var server: ElectrumServer? = null) : ServerSettings {
        override fun load(): ElectrumServer = server ?: ElectrumServer(
            host = "",
            port = ElectrumProtocol.SSL.defaultPort,
            protocol = ElectrumProtocol.SSL,
        )
    }

    /**
     * Signer store + server settings + a scripted ping + a scripted Tor status,
     * wired into a state.
     *
     * [results] answers the pings in order (the last value repeats), so a test
     * can say "up, then down" without any real network.
     */
    private class Fixture(
        server: ElectrumServer? = null,
        private val results: List<Boolean> = listOf(true),
        var tor: TorStatus = TorStatus.OFF,
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

        /**
         * When set, the probe suspends here until the test completes it. That is
         * how a test observes the status *while a dial is genuinely in flight*
         * rather than only at the instant the probe was entered.
         */
        var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        lateinit var state: HomeScreenState

        init {
            var call = 0
            val probe: suspend (ElectrumServer) -> Boolean = { target ->
                pinged += target
                statusDuringPing += state.serverStatus
                hold?.await()
                results.getOrElse(call++) { results.last() }
            }
            state = HomeScreenState(store, serverSettings, probe, torStatus = { tor })
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
    fun `a saved server starts out as connecting, never as disconnected`() {
        val fixture = Fixture(server = CLEARNET)

        // Seeded from the store, so the first frame after launch already reads
        // "Connecting…" instead of showing a red dot that nothing justified.
        assertEquals(ServerStatus.CONNECTING, fixture.state.serverStatus)
        assertTrue("nothing should have been checked yet", fixture.pinged.isEmpty())
    }

    @Test
    fun `a successful check against a clearnet server reports connected`() {
        val fixture = Fixture(server = CLEARNET)

        val status = runBlocking { fixture.state.checkServerStatus() }

        assertEquals(ServerStatus.CONNECTED, status)
        assertEquals(ServerStatus.CONNECTED, fixture.state.serverStatus)
        assertEquals(listOf(CLEARNET), fixture.pinged)
        assertEquals(listOf(ServerStatus.CONNECTING), fixture.statusDuringPing)
    }

    @Test
    fun `a failed check reports disconnected`() {
        val fixture = Fixture(server = CLEARNET, results = listOf(false))

        val status = runBlocking { fixture.state.checkServerStatus() }

        assertEquals(ServerStatus.DISCONNECTED, status)
        assertEquals(ServerStatus.DISCONNECTED, fixture.state.serverStatus)
    }

    @Test
    fun `the first check shows connecting in flight, later checks keep the last answer`() {
        val fixture = Fixture(server = CLEARNET, results = listOf(true, false))

        runBlocking {
            fixture.state.checkServerStatus() // first check of the screen visit
            fixture.state.checkServerStatus(showChecking = false) // a later heartbeat
        }

        // In flight: "Connecting…" then the previous answer — never blank, never a
        // sign flip, which is what keeps a 60-second poll from blinking.
        assertEquals(
            listOf(ServerStatus.CONNECTING, ServerStatus.CONNECTED),
            fixture.statusDuringPing,
        )
        assertEquals(ServerStatus.DISCONNECTED, fixture.state.serverStatus)
        assertEquals(2, fixture.pinged.size)
    }

    // --- onion servers and Tor ---------------------------------------------

    @Test
    fun `an onion server still bootstrapping reads as tor starting, with no ping`() {
        val fixture = Fixture(server = ONION)
        fixture.tor = TorStatus(TorPhase.STARTING, 40)

        val status = runBlocking { fixture.state.checkServerStatus() }

        // A ping here would block on the Tor bootstrap mutex for the whole cold
        // start, so the shared Tor status answers instead.
        assertEquals(ServerStatus.TOR_STARTING, status)
        assertEquals(40, fixture.state.torProgressPercent)
        assertTrue("must not dial while Tor is bootstrapping", fixture.pinged.isEmpty())
    }

    @Test
    fun `an onion server whose tor failed reads as tor failed, with no ping`() {
        val fixture = Fixture(server = ONION)
        fixture.tor = TorStatus(TorPhase.FAILED, 40)

        val status = runBlocking { fixture.state.checkServerStatus() }

        assertEquals(ServerStatus.TOR_FAILED, status)
        assertEquals(40, fixture.state.torProgressPercent)
        assertTrue("nothing to dial once Tor has given up", fixture.pinged.isEmpty())
    }

    @Test
    fun `an onion server with tor ready and a reachable server reads as connected via tor`() {
        val fixture = Fixture(server = ONION, results = listOf(true))
        fixture.tor = TorStatus(TorPhase.READY, 100)

        val status = runBlocking { fixture.state.checkServerStatus() }

        assertEquals(ServerStatus.CONNECTED_VIA_TOR, status)
        assertEquals(listOf(ONION), fixture.pinged)
        assertEquals(100, fixture.state.torProgressPercent)
    }

    @Test
    fun `an onion server whose tor failed to reach the server reads as disconnected`() {
        // Tor is up but the server behind the onion is not answering: that is a
        // server problem, not a Tor one.
        val fixture = Fixture(server = ONION, results = listOf(false))
        fixture.tor = TorStatus(TorPhase.READY, 100)

        val status = runBlocking { fixture.state.checkServerStatus() }

        assertEquals(ServerStatus.DISCONNECTED, status)
    }

    @Test
    fun `onion + Tor READY pings and promotes to CONNECTED_VIA_TOR`() {
        // Regression: one tick sees Tor STARTING (the 100% the user was stuck
        // on), the next sees it READY. The READY tick must actually dial and
        // promote, not short-circuit on the stale STARTING value.
        val fixture = Fixture(server = ONION, results = listOf(true))
        fixture.tor = TorStatus(TorPhase.STARTING, 100)

        val first = runBlocking { fixture.state.checkServerStatus() }
        assertEquals(ServerStatus.TOR_STARTING, first)
        assertEquals("nothing to dial while Tor bootstraps", 0, fixture.pinged.size)

        fixture.tor = TorStatus(TorPhase.READY, 100)
        val second = runBlocking { fixture.state.checkServerStatus(showChecking = false) }

        assertEquals(ServerStatus.CONNECTED_VIA_TOR, second)
        assertEquals(ServerStatus.CONNECTED_VIA_TOR, fixture.state.serverStatus)
        assertEquals(listOf(ONION), fixture.pinged)
    }

    @Test
    fun `onion + Tor READY shows CONNECTING while the first dial is in flight`() {
        // A multi-minute first rendezvous must not look frozen on "Tor
        // starting… 100%": the fall-through to a real dial has to move the label
        // to CONNECTING even though this is not the first check of the visit.
        val fixture = Fixture(server = ONION, results = listOf(true))
        fixture.tor = TorStatus(TorPhase.STARTING, 100)

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fixture.hold = gate

        runBlocking {
            assertEquals(ServerStatus.TOR_STARTING, fixture.state.checkServerStatus())
            fixture.tor = TorStatus(TorPhase.READY, 100)

            val check = launch { fixture.state.checkServerStatus(showChecking = false) }
            withTimeout(5_000) { while (fixture.pinged.isEmpty()) delay(5) }

            assertEquals(
                "a first dial must not sit on a frozen Tor starting 100%",
                ServerStatus.CONNECTING,
                fixture.state.serverStatus,
            )

            gate.complete(Unit)
            check.join()
        }

        assertEquals(ServerStatus.CONNECTED_VIA_TOR, fixture.state.serverStatus)
    }

    @Test
    fun `an established CONNECTED_VIA_TOR does not blink to CONNECTING on a later heartbeat`() {
        val fixture = Fixture(server = ONION, results = listOf(true))
        fixture.tor = TorStatus(TorPhase.READY, 100)

        runBlocking { fixture.state.checkServerStatus() }
        assertEquals(ServerStatus.CONNECTED_VIA_TOR, fixture.state.serverStatus)

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fixture.hold = gate

        runBlocking {
            val check = launch { fixture.state.checkServerStatus(showChecking = false) }
            withTimeout(5_000) { while (fixture.pinged.size < 2) delay(5) }

            assertEquals(
                "a 60-second re-ping must not blink an established connection",
                ServerStatus.CONNECTED_VIA_TOR,
                fixture.state.serverStatus,
            )

            gate.complete(Unit)
            check.join()
        }

        assertEquals(ServerStatus.CONNECTED_VIA_TOR, fixture.state.serverStatus)
    }

    @Test
    fun `checking with no server saved settles on not configured without pinging`() {
        val fixture = Fixture()

        val status = runBlocking { fixture.state.checkServerStatus() }

        assertEquals(ServerStatus.NOT_CONFIGURED, status)
        assertTrue("there is nothing to ping without an endpoint", fixture.pinged.isEmpty())
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
    }

    // --- heartbeat cadence --------------------------------------------------

    @Test
    fun `the heartbeat polls faster while tor is bootstrapping, and at the interval otherwise`() {
        val fixture = Fixture()
        val minute = 60_000L

        assertEquals(
            "Tor starting must tick fast so the percentage moves",
            2_000L,
            fixture.state.nextDelayMillis(ServerStatus.TOR_STARTING, minute),
        )
        // Every other state waits out the caller's interval unchanged.
        listOf(
            ServerStatus.NOT_CONFIGURED,
            ServerStatus.TOR_FAILED,
            ServerStatus.CONNECTING,
            ServerStatus.CONNECTED,
            ServerStatus.CONNECTED_VIA_TOR,
            ServerStatus.DISCONNECTED,
        ).forEach { status ->
            assertEquals(status.name, minute, fixture.state.nextDelayMillis(status, minute))
        }
    }

    @Test
    fun `the heartbeat checks immediately and keeps polling until cancelled`() {
        val fixture = Fixture(server = CLEARNET)

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
        val CLEARNET = ElectrumServer(
            host = "192.168.1.10",
            port = 50002,
            protocol = ElectrumProtocol.SSL,
        )

        /** A well-formed-looking onion host; routing only cares about the suffix. */
        val ONION = ElectrumServer(
            host = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuv.onion",
            port = 50001,
            protocol = ElectrumProtocol.TCP,
        )
    }
}
