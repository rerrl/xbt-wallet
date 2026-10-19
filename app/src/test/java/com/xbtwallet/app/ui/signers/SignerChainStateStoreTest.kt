package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.chain.AccountSnapshot
import com.xbtwallet.app.data.ServerSettings
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerType
import com.xbtwallet.app.electrum.ElectrumProtocol
import com.xbtwallet.app.electrum.ElectrumServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SignerChainStateStore tests — the identity, single-scan and removal rules that
 * make a per-signer sync survive navigation.
 *
 * The store's whole reason to exist is that the *same* [SignerChainState] comes
 * back for the same signer id (so a scan in flight is not discarded when a screen
 * leaves), that [SignerChainStateStore.sync] can never start a second scan for a
 * signer that is already scanning, and that deleting a signer cancels its scan
 * and forgets its state. The scan is injected, so these rules are exercised with
 * a latch-controlled fake and no socket; the store runs its jobs on a real
 * dispatcher, so the tests wait on latches rather than a virtual clock.
 */
class SignerChainStateStoreTest {

    private val accountZpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    private fun signer(id: String = "id-1") =
        Signer(id = id, name = "Signer $id", type = SignerType.WatchOnlyXpub(accountZpub), createdAtEpochSeconds = 0L)

    /** A settable in-memory stand-in for the saved endpoint; null host = none saved. */
    private class FakeServerSettings(var server: ElectrumServer? = null) : ServerSettings {
        override fun load(): ElectrumServer = server ?: ElectrumServer(
            host = "",
            port = ElectrumProtocol.SSL.defaultPort,
            protocol = ElectrumProtocol.SSL,
        )
    }

    /** A store whose scans block until cancelled — every test's own scope. */
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `stateFor returns the same instance per signer id and distinct ones otherwise`() {
        val scope = scope()
        val store = SignerChainStateStore(FakeServerSettings(SERVER), null, scope) { _, _, _, _ -> AccountSnapshot.EMPTY }

        val first = store.stateFor(signer("id-1"))
        val again = store.stateFor(signer("id-1"))
        val other = store.stateFor(signer("id-2"))

        assertSame("the same signer id must resolve to the same state", first, again)
        assertNotSame("a different signer id is a different state", first, other)

        scope.cancel()
    }

    @Test
    fun `sync while a scan is in flight starts no second scan`() {
        val entered = CountDownLatch(1)
        val scans = AtomicInteger(0)
        val scope = scope()
        val store = SignerChainStateStore(FakeServerSettings(SERVER), null, scope) { _, _, _, _ ->
            scans.incrementAndGet()
            entered.countDown()
            delay(60_000L)
            AccountSnapshot.EMPTY
        }

        val target = signer("id-1")
        store.sync(target)
        assertTrue("the first sync must reach the scan", entered.await(5, TimeUnit.SECONDS))

        // Re-entering the signer while its scan runs — the navigation case.
        store.sync(target)
        store.sync(target)

        assertEquals("concurrent sync() calls must produce exactly one scan", 1, scans.get())

        scope.cancel()
    }

    @Test
    fun `remove cancels an in-flight scan and forgets the state`() {
        val entered = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val scope = scope()
        val store = SignerChainStateStore(FakeServerSettings(SERVER), null, scope) { _, _, _, _ ->
            entered.countDown()
            try {
                delay(60_000L)
            } catch (cancellation: CancellationException) {
                // The scan sees the cancellation rather than being silently
                // abandoned; the state's own handler then keeps it re-startable.
                cancelled.set(true)
                throw cancellation
            }
            AccountSnapshot.EMPTY
        }

        val target = signer("id-1")
        val before = store.stateFor(target)
        store.sync(target)
        assertTrue("the sync must reach the scan", entered.await(5, TimeUnit.SECONDS))

        store.remove(target.id)

        val deadline = System.currentTimeMillis() + 5_000
        while (!cancelled.get() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("remove() must cancel the in-flight scan", cancelled.get())

        assertNotSame("remove() must forget the state", before, store.stateFor(target))

        scope.cancel()
    }

    private companion object {
        val SERVER = ElectrumServer(host = "192.168.1.10", port = 50002, protocol = ElectrumProtocol.SSL)
    }
}
