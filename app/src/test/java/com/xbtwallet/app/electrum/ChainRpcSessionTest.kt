package com.xbtwallet.app.electrum

import com.xbtwallet.app.chain.AccountScanner
import com.xbtwallet.app.chain.AddressChain
import com.xbtwallet.app.chain.HistoryEntry
import com.xbtwallet.app.chain.Scripthash
import com.xbtwallet.app.chain.TestTransactions
import com.xbtwallet.app.crypto.ExtendedPublicKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chain RPCs, end to end over a loopback socket.
 *
 * These tests are the link between the chain layer's *types* and the wire: they
 * drive a real [ElectrumClient] against [MockElectrumServer] answering from
 * scripted fixtures, so a change to either the JSON decoding or the request
 * shape shows up here rather than as a wrong balance on a phone.
 *
 * The fixtures are keyed by Electrum scripthash, and the scripthashes are
 * computed with the same [AccountScanner] the app uses — so a full scan can be
 * scripted by saying "address 0 of the receive chain holds 5 000 sats" and then
 * *running the real scan*.
 */
class ChainRpcSessionTest {

    private val accountZpub =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

    private fun client(port: Int) = ElectrumClient(
        server = ElectrumServer(host = "127.0.0.1", port = port, protocol = ElectrumProtocol.TCP),
        timeoutMillis = 5_000,
        log = {},
    )

    /** A scripthash that is never in any fixture, for the "unused address" case. */
    private val unusedScripthash = Scripthash.ofP2wpkh(ByteArray(20) { 0x99.toByte() })

    @Test
    fun `get_history decodes entries and reports an unused address as empty`() {
        val used = Scripthash.ofP2wpkh(ByteArray(20) { 0x01 })
        MockElectrumServer.scripted(
            history = mapOf(
                used to listOf<Any?>(MockElectrumServer.historyEntry(TXID, height = 800_000)),
            ),
        ).use { mock ->
            val (usedHistory, unusedHistory) = runBlocking {
                client(mock.port).withChainSession { session ->
                    session.getHistory(used) to session.getHistory(unusedScripthash)
                }
            }

            assertEquals(1, usedHistory.size)
            assertEquals(TXID, usedHistory.first().txHash)
            assertEquals(800_000, usedHistory.first().height)
            assertTrue(usedHistory.first().confirmed)

            assertEquals(emptyList<HistoryEntry>(), unusedHistory)
            assertEquals(1, mock.connections)
        }
    }

    @Test
    fun `get_balance decodes the confirmed and unconfirmed split`() {
        MockElectrumServer.scripted(
            balances = mapOf(unusedScripthash to (1_500L to 42L)),
        ).use { mock ->
            val balance = runBlocking {
                client(mock.port).withChainSession { it.getBalance(unusedScripthash) }
            }

            assertEquals(1_500L, balance.confirmedSatoshis)
            assertEquals(42L, balance.unconfirmedSatoshis)
            assertEquals(1_542L, balance.totalSatoshis)
            assertEquals(listOf("blockchain.scripthash.get_balance"), mock.requests.map { it["method"] })
            assertEquals(listOf(unusedScripthash), mock.requests.first()["params"])
        }
    }

    @Test
    fun `listunspent decodes outputs`() {
        MockElectrumServer.scripted(
            unspent = mapOf(
                unusedScripthash to listOf<Any?>(
                    MockElectrumServer.unspentEntry(TXID, txPos = 3, height = 800_000, value = 12_345),
                    MockElectrumServer.unspentEntry(TXID, txPos = 4, height = 0, value = 7),
                ),
            ),
        ).use { mock ->
            val utxos = runBlocking {
                client(mock.port).withChainSession { it.listUnspent(unusedScripthash) }
            }

            assertEquals(2, utxos.size)
            assertEquals("$TXID:3", utxos[0].outpoint)
            assertEquals(12_345L, utxos[0].satoshis)
            assertTrue(utxos[0].confirmed)
            assertEquals(0, utxos[1].height)
            assertFalse(utxos[1].confirmed)
        }
    }

    @Test
    fun `a json-rpc error becomes an exception the caller can show`() {
        // A server that rejects the scripthash RPC (an electrs build without the
        // method, say) must surface as an exception the screen can print — never
        // as an empty history, which would silently read as "unused".
        MockElectrumServer { method, _, _ ->
            MockElectrumServer.Reply.Error(-32601, "unknown method $method")
        }.use { mock ->
            val error = assertThrows(ElectrumException::class.java) {
                runBlocking {
                    client(mock.port).withChainSession { session -> session.getHistory(unusedScripthash) }
                }
            }
            assertTrue(error.message.orEmpty().contains("get_history"))
        }
    }

    @Test
    fun `a whole account scan runs over a single connection`() {
        val scanner = AccountScanner(ExtendedPublicKey.parse(accountZpub))
        val funded = scanner.derive(AddressChain.RECEIVE, 0)

        // The scan now also reads the transaction behind each history entry, so
        // it can show a net in/out amount: one `blockchain.transaction.get` for
        // the single transaction, plus one `blockchain.headers.subscribe` for
        // the tip the confirmations count from. The transaction pays the funded
        // address 5 000 sats from a coin this wallet does not own, which is the
        // row's net effect.
        val (fundingTxId, fundingTx) = TestTransactions.buildWithId(
            inputs = listOf(UNRELATED_TXID to 0),
            outputs = listOf(5_000L to funded.scriptPubKeyHex),
        )

        MockElectrumServer.scripted(
            history = mapOf(funded.scripthash to listOf<Any?>(MockElectrumServer.historyEntry(fundingTxId, 800_000))),
            balances = mapOf(funded.scripthash to (5_000L to 7L)),
            unspent = mapOf(
                funded.scripthash to listOf<Any?>(
                    MockElectrumServer.unspentEntry(fundingTxId, txPos = 0, height = 800_000, value = 5_000),
                ),
            ),
            transactions = mapOf(fundingTxId to fundingTx),
            tipHeight = 800_000,
        ).use { mock ->
            val snapshot = runBlocking {
                client(mock.port).withChainSession { session -> scanner.scan(session) }
            }

            // Address 0 of the receive chain is funded, so that chain needs two
            // batches (0..19 is not entirely unused, 20..39 is) while the change
            // chain stops after its first: 60 history calls in all, then exactly
            // one balance call and one UTXO call for the single used address.
            val methods = mock.requests.map { it["method"] }
            assertEquals(60, methods.count { it == "blockchain.scripthash.get_history" })
            assertEquals(1, methods.count { it == "blockchain.scripthash.get_balance" })
            assertEquals(1, methods.count { it == "blockchain.scripthash.listunspent" })
            assertEquals(1, methods.count { it == "blockchain.transaction.get" })
            assertEquals(1, methods.count { it == "blockchain.headers.subscribe" })

            // ... and it is all one connection, which is the point of the
            // session API: a scan must not look like a port scan.
            assertEquals(1, mock.connections)

            assertEquals(5_007L, snapshot.satoshis)
            assertEquals(1, snapshot.utxos.size)
            assertEquals(AddressChain.RECEIVE, snapshot.utxos.first().chain)
            assertEquals(0, snapshot.utxos.first().addressIndex)

            // The transaction row: 5 000 in, mined at the tip, one confirmation.
            val row = snapshot.transactions.single()
            assertEquals(fundingTxId, row.txHash)
            assertEquals(5_000L, row.netSatoshis)
            assertEquals(1, row.confirmations)
        }
    }

    @Test
    fun `an unsolicited header notification is not mistaken for the reply`() {
        // `blockchain.headers.subscribe` makes the server push a notification on
        // every new block. One arriving between a request and its reply must be
        // skipped, or the session would read it as the answer and desynchronise.
        val notification =
            "{\"jsonrpc\":\"2.0\",\"method\":\"blockchain.headers.subscribe\",\"params\":[{\"height\":800001,\"hex\":\"00\"}]}"

        MockElectrumServer { method, _, _ ->
            when (method) {
                "blockchain.headers.subscribe" -> MockElectrumServer.Reply.Result(
                    value = linkedMapOf<String, Any?>("height" to 800_000, "hex" to "00"),
                    precedingNotifications = listOf(notification),
                )
                "blockchain.scripthash.get_history" -> MockElectrumServer.Reply.Result(emptyList<Any?>())
                else -> MockElectrumServer.Reply.Error(-32601, "unknown method $method")
            }
        }.use { mock ->
            val (tip, history) = runBlocking {
                client(mock.port).withChainSession { session ->
                    session.getTipHeight() to session.getHistory(unusedScripthash)
                }
            }

            assertEquals(800_000, tip)
            assertEquals(emptyList<HistoryEntry>(), history)
        }
    }

    private companion object {
        const val TXID = "1111111111111111111111111111111111111111111111111111111111111111"

        /** An outpoint from a transaction this wallet has no history for. */
        const val UNRELATED_TXID = "2222222222222222222222222222222222222222222222222222222222222222"
    }
}
