package com.xbtwallet.app.data

import com.xbtwallet.app.chain.AccountSnapshot
import com.xbtwallet.app.chain.AddressChain
import com.xbtwallet.app.chain.ChainUtxo
import com.xbtwallet.app.chain.HistoryEntry
import com.xbtwallet.app.chain.ScannedAddress
import com.xbtwallet.app.chain.ScannedUtxo
import com.xbtwallet.app.chain.ScripthashBalance
import com.xbtwallet.app.chain.TransactionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SnapshotStore tests.
 *
 * The store talks to a [KeyValueStore] rather than to SharedPreferences, so the
 * whole thing is exercised on the JVM with an in-memory map — no Android
 * framework, no emulator.
 *
 * What is pinned here is the *round-trip fidelity* (a scan saved is a scan read
 * back, addresses, history, UTXOs, balance and transaction rows alike) and the
 * *forgiveness rule* the cache shares with [SignerStore]: a corrupt, blank,
 * missing or future-versioned blob reads as null and never throws, because a
 * cache that cannot be read should cost a rescan, not a crash.
 */
class SnapshotStoreTest {

    /** The simplest possible [KeyValueStore]: a map that survives instances. */
    private class InMemoryStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

    private fun address(
        chain: AddressChain,
        index: Int,
        used: Boolean,
        history: List<HistoryEntry> = emptyList(),
    ) = ScannedAddress(
        chain = chain,
        index = index,
        address = "bc1q-test-${chain.name}-$index",
        scripthash = "sh-${chain.name}-$index",
        used = used,
        history = history,
        scriptPubKeyHex = "0014" + "%040x".format(index),
    )

    /** A non-trivial snapshot: both chains, history, UTXOs, balance and rows. */
    private val full = AccountSnapshot(
        receive = listOf(
            address(
                AddressChain.RECEIVE,
                0,
                used = true,
                history = listOf(
                    HistoryEntry(txHash = TX_A, height = 800_000),
                    HistoryEntry(txHash = TX_B, height = 0),
                ),
            ),
            address(AddressChain.RECEIVE, 1, used = false),
        ),
        change = listOf(address(AddressChain.CHANGE, 0, used = true, history = listOf(HistoryEntry(TX_C, 799_000)))),
        utxos = listOf(
            ScannedUtxo(
                utxo = ChainUtxo(txHash = TX_A, txPos = 0, height = 800_000, satoshis = 12_345),
                chain = AddressChain.RECEIVE,
                addressIndex = 0,
            ),
            ScannedUtxo(
                utxo = ChainUtxo(txHash = TX_C, txPos = 2, height = 0, satoshis = 67_000),
                chain = AddressChain.CHANGE,
                addressIndex = 0,
            ),
        ),
        balance = ScripthashBalance(confirmedSatoshis = 12_345, unconfirmedSatoshis = 67_000),
        transactions = listOf(
            TransactionRow(txHash = TX_B, height = 0, confirmations = 0, netSatoshis = 67_000),
            TransactionRow(txHash = TX_A, height = 800_000, confirmations = 6, netSatoshis = 12_345),
        ),
    )

    @Test
    fun `a saved snapshot round-trips through storage unchanged`() {
        val backing = InMemoryStore()
        val store = SnapshotStore(backing, now = { 1_700_000_000L })

        store.save("id-1", full)
        val loaded = store.load("id-1")

        assertEquals(full, loaded?.snapshot)
        assertEquals(1_700_000_000L, loaded?.scannedAtEpochSeconds)
    }

    @Test
    fun `a snapshot is readable by a fresh store over the same storage`() {
        val backing = InMemoryStore()
        SnapshotStore(backing, now = { 5L }).save("id-1", full)

        val reloaded = SnapshotStore(backing).load("id-1")

        assertEquals(full, reloaded?.snapshot)
        assertEquals(5L, reloaded?.scannedAtEpochSeconds)
    }

    @Test
    fun `a transaction's block timestamp round-trips when present`() {
        val backing = InMemoryStore()
        val dated = full.copy(
            transactions = listOf(
                TransactionRow(
                    txHash = TX_A,
                    height = 800_000,
                    confirmations = 6,
                    netSatoshis = 12_345,
                    blockTimestampEpochSeconds = 1_700_000_000L,
                ),
            ),
        )

        SnapshotStore(backing, now = { 1L }).save("id-1", dated)
        val loaded = SnapshotStore(backing).load("id-1")

        assertEquals(dated, loaded?.snapshot)
        assertEquals(1_700_000_000L, loaded?.snapshot?.transactions?.single()?.blockTimestampEpochSeconds)
    }

    @Test
    fun `a transaction blob written before the field existed still loads, with a null date`() {
        val backing = InMemoryStore()
        // Exactly what the previous schema wrote: no `blockTimestamp` key.
        backing.putString(
            SnapshotStore.KEY_PREFIX + "id-1",
            """{"version":1,"scannedAt":1,"receive":[],"change":[],"utxos":[],""" +
                """"balance":{"confirmed":0,"unconfirmed":0},""" +
                """"transactions":[{"txHash":"$TX_A","height":800000,"confirmations":6,"netSatoshis":12345}]}""",
        )

        val loaded = SnapshotStore(backing).load("id-1")

        assertNotNull("an older blob must keep loading", loaded)
        assertNull(loaded?.snapshot?.transactions?.single()?.blockTimestampEpochSeconds)
    }

    @Test
    fun `each signer gets its own blob`() {
        val store = SnapshotStore(InMemoryStore(), now = { 1L })
        val other = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(42, 0))

        store.save("alice", full)
        store.save("bob", other)

        assertEquals(full, store.load("alice")?.snapshot)
        assertEquals(other, store.load("bob")?.snapshot)
    }

    @Test
    fun `a missing key reads as null`() {
        assertNull(SnapshotStore(InMemoryStore()).load("never-saved"))
    }

    @Test
    fun `a blank value reads as null`() {
        val backing = InMemoryStore()
        backing.putString(SnapshotStore.KEY_PREFIX + "id-1", "")
        assertNull(SnapshotStore(backing).load("id-1"))
    }

    @Test
    fun `corrupt stored JSON degrades to null instead of throwing`() {
        val backing = InMemoryStore()
        backing.putString(SnapshotStore.KEY_PREFIX + "id-1", "{not json at all")
        assertNull(SnapshotStore(backing).load("id-1"))
    }

    @Test
    fun `a blob with an unknown version is discarded unread`() {
        val backing = InMemoryStore()
        backing.putString(
            SnapshotStore.KEY_PREFIX + "id-1",
            """{"version":99,"scannedAt":1,"receive":[],"change":[],"utxos":[],""" +
                """"balance":{"confirmed":0,"unconfirmed":0},"transactions":[]}""",
        )
        assertNull(SnapshotStore(backing).load("id-1"))
    }

    @Test
    fun `a blob missing a required field reads as null`() {
        val backing = InMemoryStore()
        // No `balance`.
        backing.putString(
            SnapshotStore.KEY_PREFIX + "id-1",
            """{"version":1,"scannedAt":1,"receive":[],"change":[],"utxos":[],"transactions":[]}""",
        )
        assertNull(SnapshotStore(backing).load("id-1"))
    }

    @Test
    fun `clear drops the snapshot for one signer only`() {
        val store = SnapshotStore(InMemoryStore(), now = { 1L })
        val other = AccountSnapshot(emptyList(), emptyList(), emptyList(), ScripthashBalance(42, 0))
        store.save("alice", full)
        store.save("bob", other)

        store.clear("alice")

        assertNull(store.load("alice"))
        assertEquals(other, store.load("bob")?.snapshot)
    }

    @Test
    fun `the stored key is the documented snapshot prefix plus the signer id`() {
        val backing = InMemoryStore()
        SnapshotStore(backing, now = { 1L }).save("id-9", full)
        assertEquals("snapshot_id-9", SnapshotStore.KEY_PREFIX + "id-9")
        assertEquals(setOf("snapshot_id-9"), backing.map.keys)
    }

    // --- the subscription manager's status hashes ---------------------------

    @Test
    fun `status hashes round-trip, null values included`() {
        val backing = InMemoryStore()
        val store = SnapshotStore(backing, now = { 1L })
        val statuses = linkedMapOf(
            "sh-0" to "a1b2",
            // Fulcrum's answer for an address it has never seen a transaction for.
            "sh-1" to null,
        )

        store.saveStatusHashes("id-1", statuses)

        val loaded = store.loadStatusHashes("id-1")
        assertEquals(statuses, loaded)
        assertTrue("a null status must come back as a real entry", loaded.containsKey("sh-1"))
        assertNull(loaded["sh-1"])
        assertEquals(setOf("statuses_id-1"), backing.map.keys)
    }

    @Test
    fun `status hashes are readable by a fresh store over the same storage`() {
        val backing = InMemoryStore()
        SnapshotStore(backing, now = { 1L }).saveStatusHashes("id-1", mapOf("sh-0" to "abc"))

        assertEquals(mapOf("sh-0" to "abc"), SnapshotStore(backing).loadStatusHashes("id-1"))
    }

    @Test
    fun `a status blob and a snapshot blob do not clobber each other`() {
        val store = SnapshotStore(InMemoryStore(), now = { 1L })
        store.save("id-1", full)
        store.saveStatusHashes("id-1", mapOf("sh-0" to "abc"))

        // The manager rewrites the statuses far more often than the snapshot.
        store.saveStatusHashes("id-1", mapOf("sh-0" to "def"))

        assertEquals(full, store.load("id-1")?.snapshot)
        assertEquals(mapOf("sh-0" to "def"), store.loadStatusHashes("id-1"))
    }

    @Test
    fun `status hashes are forgiven when missing, blank, corrupt or from a future schema`() {
        val missing = InMemoryStore()
        assertTrue(SnapshotStore(missing).loadStatusHashes("id-1").isEmpty())

        val blank = InMemoryStore()
        blank.putString(SnapshotStore.STATUS_KEY_PREFIX + "id-1", "")
        assertTrue(SnapshotStore(blank).loadStatusHashes("id-1").isEmpty())

        val corrupt = InMemoryStore()
        corrupt.putString(SnapshotStore.STATUS_KEY_PREFIX + "id-1", "{not json at all")
        assertTrue(SnapshotStore(corrupt).loadStatusHashes("id-1").isEmpty())

        val future = InMemoryStore()
        future.putString(SnapshotStore.STATUS_KEY_PREFIX + "id-1", """{"version":99,"statuses":{"sh-0":"a"}}""")
        assertTrue(SnapshotStore(future).loadStatusHashes("id-1").isEmpty())

        val notAStatusHash = InMemoryStore()
        notAStatusHash.putString(SnapshotStore.STATUS_KEY_PREFIX + "id-1", """{"version":1,"statuses":{"sh-0":7}}""")
        assertTrue(SnapshotStore(notAStatusHash).loadStatusHashes("id-1").isEmpty())
    }

    @Test
    fun `each signer gets its own status hashes`() {
        val store = SnapshotStore(InMemoryStore(), now = { 1L })
        store.saveStatusHashes("alice", mapOf("sh-a" to "1"))
        store.saveStatusHashes("bob", mapOf("sh-b" to "2"))

        assertEquals(mapOf("sh-a" to "1"), store.loadStatusHashes("alice"))
        assertEquals(mapOf("sh-b" to "2"), store.loadStatusHashes("bob"))
    }

    @Test
    fun `clear drops the status hashes along with the snapshot`() {
        val store = SnapshotStore(InMemoryStore(), now = { 1L })
        store.save("id-1", full)
        store.saveStatusHashes("id-1", mapOf("sh-0" to "1"))

        store.clear("id-1")

        assertNull(store.load("id-1"))
        assertTrue(store.loadStatusHashes("id-1").isEmpty())
    }

    @Test
    fun `the subscription state adapter reads and writes the same signer's records`() {
        val store = SnapshotStore(InMemoryStore(), now = { 7L })
        val state = store.subscriptionState("id-1")

        state.saveStatusHashes(mapOf("sh-0" to "abc", "sh-1" to null))
        state.saveSnapshot(full)

        assertEquals(mapOf("sh-0" to "abc", "sh-1" to null), store.loadStatusHashes("id-1"))
        assertEquals(full, store.load("id-1")?.snapshot)
        assertEquals(mapOf("sh-0" to "abc", "sh-1" to null), state.loadStatusHashes())
        assertEquals(
            "another signer must see none of it",
            emptyMap<String, String?>(),
            store.loadStatusHashes("id-2"),
        )
    }

    private companion object {
        const val TX_A = "00000000000000000000000000000000000000000000000000000000000000a1"
        const val TX_B = "00000000000000000000000000000000000000000000000000000000000000b2"
        const val TX_C = "00000000000000000000000000000000000000000000000000000000000000c3"
    }
}
