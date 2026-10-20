package com.xbtwallet.app.ui.home

import com.xbtwallet.app.data.KeyValueStore
import com.xbtwallet.app.data.ServerSettings
import com.xbtwallet.app.data.SignerStore
import com.xbtwallet.app.electrum.ElectrumProtocol
import com.xbtwallet.app.electrum.ElectrumServer
import com.xbtwallet.app.electrum.TorPhase
import com.xbtwallet.app.electrum.TorStatus
import kotlinx.coroutines.CompletableDeferred
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
 * (a plain list, with no active selection) and "the server indicator is derived
 * from the facts every tick, never set by the act of dialling" — are testable
 * without a UI or an emulator. It is plain Kotlin over a [SignerStore] and a
 * [ServerSettings], so in-memory fakes drive it here; the ping and the Tor status
 * are both injected, so no socket is involved.
 *
 * The heartbeat tests run the real loop on a short tick and a fake clock. The
 * load-bearing ones use a latch to hold the injected ping open, which is how a
 * test observes the display *while a dial is genuinely blocked* — the shape of
 * the "Connecting…" regression this loop was rewritten to fix.
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
     * can say "up, then down" without any real network. [clock] is the fake
     * epoch-millisecond wall clock the state reads for answer staleness, so a
     * test can age a remembered answer without sleeping. [throwOnCall] makes one
     * dial blow up, which is how the "a failed dial must not kill the loop"
     * rule is exercised.
     */
    private class Fixture(
        server: ElectrumServer? = null,
        private val results: List<Boolean> = listOf(true),
        var tor: TorStatus = TorStatus.OFF,
        var clock: Long = 0L,
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
         * how a test observes the display *while a dial is genuinely in flight*
         * rather than only at the instant the probe was entered.
         */
        var hold: CompletableDeferred<Unit>? = null

        /** Index of the ping call (0-based) that should throw, or null for none. */
        var throwOnCall: Int? = null

        lateinit var state: HomeScreenState

        init {
            var call = 0
            val probe: suspend (ElectrumServer) -> Boolean = { target ->
                pinged += target
                statusDuringPing += state.serverStatus
                val index = call++
                hold?.await()
                if (index == throwOnCall) throw IllegalStateException("dial failed")
                results.getOrElse(index) { results.last() }
            }
            state = HomeScreenState(store, serverSettings, probe, torStatus = { tor }, now = { clock })
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

    // --- clearnet: unchanged behaviour --------------------------------------

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

    // --- onion servers and Tor ---------------------------------------------

    @Test
    fun `an onion server still bootstrapping renders tor starting with live percent, and never dials`() {
        val fixture = Fixture(server = ONION)
        fixture.tor = TorStatus(TorPhase.STARTING, 10)

        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            withTimeout(5_000) { while (fixture.state.torProgressPercent != 10) delay(5) }
            assertEquals(ServerStatus.TOR_STARTING, fixture.state.serverStatus)

            // The percentage is re-read every tick, so the line tracks the
            // bootstrap rather than sitting on the value it started at.
            fixture.tor = TorStatus(TorPhase.STARTING, 65)
            withTimeout(5_000) { while (fixture.state.torProgressPercent != 65) delay(5) }
            assertEquals(ServerStatus.TOR_STARTING, fixture.state.serverStatus)

            heartbeat.cancelAndJoin()
        }

        assertTrue("must not dial while Tor is bootstrapping", fixture.pinged.isEmpty())
    }

    @Test
    fun `an onion server whose tor failed renders tor failed, with no ping`() {
        val fixture = Fixture(server = ONION)
        fixture.tor = TorStatus(TorPhase.FAILED, 40)

        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            withTimeout(5_000) {
                while (fixture.state.serverStatus != ServerStatus.TOR_FAILED) delay(5)
            }
            assertEquals(40, fixture.state.torProgressPercent)
            heartbeat.cancelAndJoin()
        }

        assertTrue("nothing to dial once Tor has given up", fixture.pinged.isEmpty())
    }

    @Test
    fun `an onion server with tor ready and a reachable server reads as connected via tor`() {
        val fixture = Fixture(server = ONION, results = listOf(true))
        fixture.tor = TorStatus(TorPhase.READY, 100)

        val status = runBlocking { fixture.state.checkServerStatus() }

        assertEquals(ServerStatus.CONNECTED_VIA_TOR, status)
        assertEquals(listOf(ONION), fixture.pinged)
        // The percentage is only meaningful while bootstrapping.
        assertEquals(0, fixture.state.torProgressPercent)
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
    fun `an onion dial never blocks the tick loop — Tor progress renders while the dial is blocked`() {
        // The regression: Tor is OFF, so the state dials (the connection path
        // starts Tor itself). The dial then blocks on the bootstrap. The tick
        // loop must keep running, so when Tor flips to STARTING the live
        // percentage is rendered instead of a frozen "Connecting…".
        val fixture = Fixture(server = ONION, results = listOf(true))
        fixture.tor = TorStatus.OFF

        val gate = CompletableDeferred<Unit>()
        fixture.hold = gate

        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }

            withTimeout(5_000) { while (fixture.pinged.isEmpty()) delay(5) }
            assertEquals(ServerStatus.CONNECTING, fixture.state.serverStatus)

            fixture.tor = TorStatus(TorPhase.STARTING, 40)
            withTimeout(5_000) {
                while (fixture.state.serverStatus != ServerStatus.TOR_STARTING) delay(5)
            }

            assertEquals(40, fixture.state.torProgressPercent)
            assertEquals("the blocked dial must not have been joined", 1, fixture.pinged.size)

            gate.complete(Unit)
            heartbeat.cancelAndJoin()
        }
    }

    @Test
    fun `an established CONNECTED_VIA_TOR does not blink to CONNECTING on a stale redial`() {
        val fixture = Fixture(server = ONION, results = listOf(true))
        fixture.tor = TorStatus(TorPhase.READY, 100)
        runBlocking { fixture.state.checkServerStatus() }
        assertEquals(ServerStatus.CONNECTED_VIA_TOR, fixture.state.serverStatus)
        assertEquals(1, fixture.pinged.size)

        val gate = CompletableDeferred<Unit>()
        fixture.hold = gate
        fixture.clock = HomeScreenState.STALE_AFTER_MILLIS + 1

        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            withTimeout(5_000) { while (fixture.pinged.size < 2) delay(5) }

            assertEquals(
                "a background redial must not blank an established connection",
                ServerStatus.CONNECTED_VIA_TOR,
                fixture.state.serverStatus,
            )

            gate.complete(Unit)
            heartbeat.cancelAndJoin()
        }

        assertEquals(ServerStatus.CONNECTED_VIA_TOR, fixture.state.serverStatus)
    }

    // --- remembered answers: entry freshness and staleness ------------------

    @Test
    fun `a fresh answer renders instantly and the entry tick performs no dial`() {
        val fixture = Fixture(server = CLEARNET)
        runBlocking { fixture.state.checkServerStatus() }
        assertEquals(ServerStatus.CONNECTED, fixture.state.serverStatus)
        assertEquals(1, fixture.pinged.size)

        // Coming back inside the reuse window (a *successful* answer younger
        // than FRESH_AFTER_MILLIS): the loop ticks but never dials, and the last
        // answer stays on screen.
        fixture.clock = HomeScreenState.FRESH_AFTER_MILLIS - 1_000
        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            delay(200)
            heartbeat.cancelAndJoin()
        }

        assertEquals(ServerStatus.CONNECTED, fixture.state.serverStatus)
        assertEquals("a fresh answer must not be re-dialled", 1, fixture.pinged.size)
    }

    @Test
    fun `a stale answer is re-dialled, and the old answer stays on screen while the dial is blocked`() {
        val fixture = Fixture(server = CLEARNET, results = listOf(true))
        runBlocking { fixture.state.checkServerStatus() }
        assertEquals(ServerStatus.CONNECTED, fixture.state.serverStatus)

        val gate = CompletableDeferred<Unit>()
        fixture.hold = gate
        // Older than the window: the loop must dial again.
        fixture.clock = HomeScreenState.STALE_AFTER_MILLIS + 1

        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            withTimeout(5_000) { while (fixture.pinged.size < 2) delay(5) }

            // Quietly: the answer updates in place, it never flipped to CONNECTING.
            assertEquals(ServerStatus.CONNECTED, fixture.state.serverStatus)
            assertEquals(listOf(ServerStatus.CONNECTING, ServerStatus.CONNECTED), fixture.statusDuringPing)

            gate.complete(Unit)
            heartbeat.cancelAndJoin()
        }

        assertEquals(ServerStatus.CONNECTED, fixture.state.serverStatus)
    }

    @Test
    fun `a dial that throws reports disconnected and the loop keeps ticking`() {
        val fixture = Fixture(server = CLEARNET)
        fixture.throwOnCall = 0

        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            withTimeout(5_000) {
                while (fixture.state.serverStatus != ServerStatus.DISCONNECTED) delay(5)
            }
            assertEquals(1, fixture.pinged.size)

            // A single blown-up dial must not have killed the loop: age the
            // answer and the next due-check dials again.
            fixture.clock = HomeScreenState.STALE_AFTER_MILLIS + 1
            withTimeout(5_000) { while (fixture.pinged.size < 2) delay(5) }

            heartbeat.cancelAndJoin()
        }

        assertTrue("the loop must survive a failed dial", fixture.pinged.size >= 2)
    }

    @Test
    fun `a changed server after a fresh answer still dials on the next tick`() {
        val fixture = Fixture(server = CLEARNET)
        runBlocking { fixture.state.checkServerStatus() }
        assertEquals(listOf(CLEARNET), fixture.pinged)

        // Edited on the Server screen, still well within the stale window: the
        // remembered answer belongs to a different endpoint, so it is invalid.
        fixture.serverSettings.server = OTHER
        fixture.clock = 5_000
        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            withTimeout(5_000) { while (fixture.pinged.size < 2) delay(5) }
            heartbeat.cancelAndJoin()
        }

        assertEquals(listOf(CLEARNET, OTHER), fixture.pinged)
    }

    @Test
    fun `no answer yet shows connecting while the first dial is in flight`() {
        val fixture = Fixture(server = CLEARNET)
        val gate = CompletableDeferred<Unit>()
        fixture.hold = gate

        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            withTimeout(5_000) { while (fixture.pinged.isEmpty()) delay(5) }
            delay(50)

            assertEquals(ServerStatus.CONNECTING, fixture.state.serverStatus)

            gate.complete(Unit)
            withTimeout(5_000) {
                while (fixture.state.serverStatus != ServerStatus.CONNECTED) delay(5)
            }
            heartbeat.cancelAndJoin()
        }

        assertEquals(ServerStatus.CONNECTED, fixture.state.serverStatus)
    }

    // --- re-entering the home screen: the entry probe ------------------------

    @Test
    fun `returning to home re-probes immediately instead of reusing a stale red verdict`() {
        // The reported bug, end to end: "Test connection" succeeded on the
        // Server screen, but Home kept reading "Server disconnected" because the
        // remembered verdict was reused until the next scheduled tick.
        val fixture = Fixture(server = CLEARNET, results = listOf(false, true))
        runBlocking { fixture.state.checkServerStatus() }
        assertEquals(ServerStatus.DISCONNECTED, fixture.state.serverStatus)

        // Well inside the loop's 60s staleness window, so only the entry probe
        // can explain a second dial here.
        fixture.clock = 10_000
        val gate = CompletableDeferred<Unit>()
        fixture.hold = gate

        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            withTimeout(5_000) { while (fixture.pinged.size < 2) delay(5) }

            assertEquals(
                "the stale red must not be rendered while the fresh probe is in flight",
                ServerStatus.CONNECTING,
                fixture.state.serverStatus,
            )

            gate.complete(Unit)
            withTimeout(5_000) {
                while (fixture.state.serverStatus != ServerStatus.CONNECTED) delay(5)
            }
            heartbeat.cancelAndJoin()
        }

        assertEquals(ServerStatus.CONNECTED, fixture.state.serverStatus)
    }

    @Test
    fun `re-entering re-probes a not-so-fresh success without blanking the connection`() {
        val fixture = Fixture(server = CLEARNET)
        runBlocking { fixture.state.checkServerStatus() }
        assertEquals(ServerStatus.CONNECTED, fixture.state.serverStatus)

        // Older than the reuse window: the entry probe is due…
        fixture.clock = HomeScreenState.FRESH_AFTER_MILLIS + 1
        val gate = CompletableDeferred<Unit>()
        fixture.hold = gate

        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            withTimeout(5_000) { while (fixture.pinged.size < 2) delay(5) }

            // …but it is a green answer, so it stays on screen rather than
            // blinking to "Connecting…" the way a red one must.
            assertEquals(
                "an established connection must not blink on a background re-check",
                ServerStatus.CONNECTED,
                fixture.state.serverStatus,
            )

            gate.complete(Unit)
            heartbeat.cancelAndJoin()
        }

        assertEquals(ServerStatus.CONNECTED, fixture.state.serverStatus)
    }

    @Test
    fun `a very fresh successful answer is reused on re-entry, with no extra dial`() {
        val fixture = Fixture(server = CLEARNET)
        runBlocking { fixture.state.checkServerStatus() }
        assertEquals(1, fixture.pinged.size)

        fixture.clock = HomeScreenState.FRESH_AFTER_MILLIS - 1_000
        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            delay(200)
            heartbeat.cancelAndJoin()
        }

        assertEquals("a fresh success must not be re-dialled on re-entry", 1, fixture.pinged.size)
        assertEquals(ServerStatus.CONNECTED, fixture.state.serverStatus)
    }

    @Test
    fun `re-entering while a probe is in flight does not start a second one`() {
        val fixture = Fixture(server = CLEARNET)
        val gate = CompletableDeferred<Unit>()
        fixture.hold = gate

        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            withTimeout(5_000) { while (fixture.pinged.isEmpty()) delay(5) }

            // The user leaves and comes straight back: the entry probe runs
            // again, finds the first dial still in flight and reuses it.
            val revisit = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            delay(200)

            assertEquals("one dial in flight is reused, never doubled", 1, fixture.pinged.size)
            assertEquals(ServerStatus.CONNECTING, fixture.state.serverStatus)

            gate.complete(Unit)
            withTimeout(5_000) {
                while (fixture.state.serverStatus != ServerStatus.CONNECTED) delay(5)
            }
            heartbeat.cancelAndJoin()
            revisit.cancelAndJoin()
        }

        assertEquals(1, fixture.pinged.size)
    }

    @Test
    fun `coming back after editing the server re-checks the new endpoint at once`() {
        val fixture = Fixture(server = CLEARNET)
        runBlocking { fixture.state.checkServerStatus() }
        assertEquals(listOf(CLEARNET), fixture.pinged)

        // Edited on the Server screen while the home screen was off display: the
        // remembered answer belongs to an endpoint that is no longer saved.
        fixture.serverSettings.server = OTHER
        fixture.clock = 1_000
        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            withTimeout(5_000) { while (fixture.pinged.size < 2) delay(5) }
            heartbeat.cancelAndJoin()
        }

        assertEquals(listOf(CLEARNET, OTHER), fixture.pinged)
        assertEquals(ServerStatus.CONNECTED, fixture.state.serverStatus)
    }

    @Test
    fun `re-entering an onion home with tor bootstrapping does not dial`() {
        val fixture = Fixture(server = ONION)
        fixture.tor = TorStatus(TorPhase.READY, 100)
        runBlocking { fixture.state.checkServerStatus() }
        assertEquals(1, fixture.pinged.size)

        // Tor went back to bootstrapping (a fresh app entry, say): the entry
        // probe must respect the same gate the ticks do.
        fixture.tor = TorStatus(TorPhase.STARTING, 10)
        fixture.clock = HomeScreenState.FRESH_AFTER_MILLIS + 1
        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            withTimeout(5_000) {
                while (fixture.state.serverStatus != ServerStatus.TOR_STARTING) delay(5)
            }
            delay(100)

            assertEquals("no dialing while Tor bootstraps", 1, fixture.pinged.size)
            heartbeat.cancelAndJoin()
        }
    }

    @Test
    fun `the heartbeat re-dials only once the answer goes stale`() {
        val fixture = Fixture(server = CLEARNET)

        runBlocking {
            val heartbeat = launch { fixture.state.runHeartbeat(tickMillis = 10) }
            delay(200)
            // ~20 ticks, but the answer is fresh: exactly one dial.
            assertEquals(1, fixture.pinged.size)

            fixture.clock = HomeScreenState.STALE_AFTER_MILLIS + 1
            withTimeout(5_000) { while (fixture.pinged.size < 2) delay(5) }

            heartbeat.cancelAndJoin()
        }

        assertEquals(ServerStatus.CONNECTED, fixture.state.serverStatus)
    }

    private companion object {
        val CLEARNET = ElectrumServer(
            host = "192.168.1.10",
            port = 50002,
            protocol = ElectrumProtocol.SSL,
        )

        /** A second clearnet endpoint, so a test can change the saved server. */
        val OTHER = ElectrumServer(
            host = "192.168.1.11",
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
