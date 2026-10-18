package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.chain.AccountSnapshot
import com.xbtwallet.app.chain.ScripthashBalance
import com.xbtwallet.app.data.ServerSettings
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerType
import com.xbtwallet.app.electrum.ElectrumProtocol
import com.xbtwallet.app.electrum.ElectrumServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

/**
 * SignerChainState tests — the three answers the signer screen can give.
 *
 * The scan itself is injected, so the rules under test are the screen's, not the
 * network's: *no server means the connect-a-server hint and no socket opened*,
 * *a scan that returns means ready*, and *a scan that throws means an error with
 * a sentence a user can act on*. `load()` being one-shot is pinned too, because
 * the composable that calls it may re-run for reasons that have nothing to do
 * with this signer.
 */
class SignerChainStateTest {

    private val accountZpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    private fun signer(xpub: String = accountZpub) =
        Signer(id = "id-1", name = "Cold storage", type = SignerType.WatchOnlyXpub(xpub), createdAtEpochSeconds = 0L)

    /** A settable in-memory stand-in for the saved endpoint; null host = none saved. */
    private class FakeServerSettings(var server: ElectrumServer? = null) : ServerSettings {
        override fun load(): ElectrumServer = server ?: ElectrumServer(
            host = "",
            port = ElectrumProtocol.SSL.defaultPort,
            protocol = ElectrumProtocol.SSL,
        )
    }

    @Test
    fun `with no server saved the screen asks for one and opens no connection`() {
        var scans = 0
        val state = SignerChainState(signer(), FakeServerSettings(null)) { _, _, _ ->
            scans++
            AccountSnapshot.EMPTY
        }

        runBlocking { state.load() }

        assertEquals(ChainPhase.NO_SERVER, state.phase)
        assertEquals("no scan may run without an endpoint", 0, scans)
        assertNull(state.errorMessage)
        assertNull(state.serverLabel)
        assertNull(state.snapshot)
    }

    @Test
    fun `a completed scan lands ready with its snapshot`() {
        val snapshot = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(1_234, 5))
        val state = SignerChainState(signer(), FakeServerSettings(SERVER)) { server, _, onProgress ->
            assertEquals("the saved endpoint is what is scanned", SERVER, server)
            onProgress(40)
            snapshot
        }

        runBlocking { state.load() }

        assertEquals(ChainPhase.READY, state.phase)
        assertEquals(snapshot, state.snapshot)
        assertEquals(40, state.scannedAddressCount)
        assertEquals(1_239L, state.snapshot?.satoshis)
        assertEquals("192.168.1.10:50002", state.serverLabel)
        assertNull(state.errorMessage)
    }

    @Test
    fun `a failing scan reports the endpoint and the reason`() {
        val state = SignerChainState(signer(), FakeServerSettings(SERVER)) { _, _, _ ->
            throw ConnectException("Connection refused")
        }

        runBlocking { state.load() }

        assertEquals(ChainPhase.ERROR, state.phase)
        assertNull("a failed scan must not leave a stale balance on screen", state.snapshot)
        val message = state.errorMessage.orEmpty()
        assertTrue(message, message.contains("192.168.1.10:50002"))
        assertTrue(message, message.contains("refused"))
    }

    @Test
    fun `the loading phase is visible while the scan runs`() {
        var phaseDuringScan: ChainPhase? = null
        lateinit var state: SignerChainState
        state = SignerChainState(signer(), FakeServerSettings(SERVER)) { _, _, _ ->
            phaseDuringScan = state.phase
            AccountSnapshot.EMPTY
        }

        runBlocking { state.load() }

        // The screen has something to render from the first frame: loading.
        assertEquals(ChainPhase.LOADING, phaseDuringScan)
        assertEquals(ChainPhase.READY, state.phase)
    }

    @Test
    fun `load scans once and reload scans again`() {
        var scans = 0
        val state = SignerChainState(signer(), FakeServerSettings(SERVER)) { _, _, _ ->
            scans++
            AccountSnapshot.EMPTY
        }

        runBlocking {
            // The LaunchedEffect that drives this may re-run on recomposition;
            // only the first call may actually scan.
            state.load()
            state.load()
            state.load()
        }
        assertEquals(1, scans)

        runBlocking { state.reload() }
        assertEquals("reload is the Retry button", 2, scans)
    }

    @Test
    fun `an unreadable xpub is reported as a key problem, not a network one`() {
        var scans = 0
        val state = SignerChainState(signer(xpub = "not-an-xpub"), FakeServerSettings(SERVER)) { _, _, _ ->
            scans++
            AccountSnapshot.EMPTY
        }

        runBlocking { state.load() }

        assertEquals(ChainPhase.ERROR, state.phase)
        assertEquals(0, scans)
        assertTrue(state.errorMessage.orEmpty().contains("key"))
        assertNull(state.scanner)
    }

    @Test
    fun `a cancelled scan stays a cancellation and is not shown as a failure`() {
        val state = SignerChainState(signer(), FakeServerSettings(SERVER)) { _, _, _ ->
            // What leaving the screen while the scan is in flight does.
            throw CancellationException("screen left")
        }

        assertThrows(CancellationException::class.java) { runBlocking { state.load() } }
        assertFalse(state.phase == ChainPhase.ERROR)
    }

    @Test
    fun `retry after a failure can succeed`() {
        var attempt = 0
        val state = SignerChainState(signer(), FakeServerSettings(SERVER)) { _, _, _ ->
            attempt++
            if (attempt == 1) throw ConnectException("Connection refused") else AccountSnapshot.EMPTY
        }

        runBlocking { state.load() }
        assertEquals(ChainPhase.ERROR, state.phase)

        runBlocking { state.reload() }
        assertEquals(ChainPhase.READY, state.phase)
        assertNull(state.errorMessage)
    }

    private companion object {
        val SERVER = ElectrumServer(host = "192.168.1.10", port = 50002, protocol = ElectrumProtocol.SSL)
    }
}
