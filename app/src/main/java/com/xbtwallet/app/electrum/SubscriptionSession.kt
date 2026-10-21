package com.xbtwallet.app.electrum

import com.xbtwallet.app.chain.ChainQuery
import com.xbtwallet.app.chain.ChainUtxo
import com.xbtwallet.app.chain.HistoryEntry
import com.xbtwallet.app.chain.ScripthashBalance
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The chain tip `blockchain.headers.subscribe` reports: a height and that block's raw header. */
data class BlockHeaderTip(val height: Int, val hex: String)

/**
 * Raised to a waiting caller — and to every caller after that — once the
 * session's connection has died, and to a caller that writes to a session the
 * user has already [SubscriptionSession.close]d.
 */
class SubscriptionSessionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A long-lived Electrum connection that carries server-pushed notifications.
 *
 * [ElectrumClient.withChainSession] connects, answers, disconnects — the right
 * shape for a scan, and no shape at all for a subscription. This class is the
 * other one: one socket is opened (through the same [ElectrumTransport] and the
 * same TLS/[ElectrumRouting] rules `runChainSession` uses), kept open, and
 * served by a reader thread for as long as it lives.
 *
 * **Multiplexing.** Requests are numbered from a monotonically increasing
 * counter. The reader thread takes complete lines off the socket and routes
 * each one by `id` to the caller waiting on it, so several threads may have
 * requests in flight at once — a targeted refresh of three addresses does not
 * serialise behind the first reply. A line carrying a `method` and no `id` is a
 * *notification*, and goes to [onNotification] instead. (The single-threaded
 * client has to skip those lines inside `rpc()`; here they are the point.)
 *
 * **Failure is terminal, and reported once.** A peer that hangs up, a socket
 * that resets and a line that is not JSON all mean the same thing: this
 * connection is finished. Every in-flight call is failed with a
 * [SubscriptionSessionException], every later call fails immediately, and
 * [onClosed] is invoked exactly once with the same error. Reconnecting is
 * deliberately *not* decided here — that is a policy question (how long to back
 * off, whether the user is even on the screen) and belongs to the layer that
 * owns the session's lifetime. This layer only reports the death faithfully.
 *
 * **No read timeout.** [ElectrumClient.DEFAULT_TIMEOUT_MILLIS] is a *read*
 * timeout as well as a connect timeout, which is right for a request/response
 * session and wrong here: an idle subscription socket is silent by design — a
 * quiet address can go days without a notification — and a 10-second read
 * timeout would tear down a perfectly healthy connection in the middle of the
 * night. So the session socket's `soTimeout` is left at 0 (wait forever;
 * [readTimeoutMillis] exposes it, and [NO_READ_TIMEOUT_MILLIS] is the value),
 * which is also what Fulcrum-over-Tor needs: a circuit that has gone slow
 * mid-rendezvous must be waited out, not mistimed as a death. Liveness is the
 * chain layer's question to ask (the home screen's `server.ping` heartbeat),
 * not this socket's.
 *
 * **Threading.** The query methods are *blocking* — [ChainQuery] is blocking on
 * purpose and the scanner calls it from a worker thread — so they should be
 * called off the UI thread. [onNotification] and [onClosed] run on the reader
 * thread, so a UI that consumes them must hand them to its own dispatcher.
 *
 * No Android imports here: this is protocol plumbing, driven by the JVM tests
 * in `SubscriptionSessionTest.kt` against [MockElectrumServer].
 *
 * @param channel the connected socket (TLS already layered on when the server
 *   asks for it), owned by this session: [close] closes it.
 * @param reader line reader over [channel].
 * @param writer line writer over [channel].
 * @param log receives one line per request, notification and protocol oddity;
 *   called from the reader thread, so implementations must be thread-safe.
 * @param notificationHandler initial value of [onNotification].
 * @param closedHandler initial value of [onClosed].
 */
class SubscriptionSession internal constructor(
    private val channel: Socket,
    private val reader: BufferedReader,
    private val writer: BufferedWriter,
    private val log: (String) -> Unit = {},
    notificationHandler: (method: String, params: Any?) -> Unit = { _, _ -> },
    closedHandler: (error: Throwable) -> Unit = {},
) : ChainQuery, AutoCloseable {

    /**
     * Called for every notification the server pushes: `blockchain.scripthash.subscribe`
     * with `params = [scripthash, statusHash-or-null]`, and
     * `blockchain.headers.subscribe` with `params = [{height, hex}]`.
     *
     * Runs on the reader thread; it must not block for long, because a slow
     * handler delays every other notification and reply behind it.
     */
    @Volatile
    var onNotification: (method: String, params: Any?) -> Unit = notificationHandler

    /**
     * Called **once**, on the reader thread, when the connection dies — with
     * the same [SubscriptionSessionException] every in-flight call is failed
     * with. A [close] initiated by the caller is not a failure and does not
     * call this; the caller already knows.
     */
    @Volatile
    var onClosed: (error: Throwable) -> Unit = closedHandler

    private val writeLock = Any()

    /** One id counter for the whole session: every request is numbered once. */
    private val nextId = AtomicInteger(0)

    /** Requests sent but not yet answered, keyed by id. */
    private val pending = ConcurrentHashMap<Int, Pending>()

    /**
     * The one error this session died of, or null while it is alive. This is
     * the transition that makes failure terminal: whoever sets it first owns
     * the reporting, and everyone after that sees it.
     */
    private val terminal = AtomicReference<Throwable?>()

    /** True once the connection has died or the session has been closed. */
    val isClosed: Boolean get() = terminal.get() != null

    /** The socket's read timeout in milliseconds; 0 means "wait forever". */
    val readTimeoutMillis: Int get() = channel.soTimeout

    /**
     * The scripthash queries on this same connection, decoded by the same
     * [ChainRpcSession] the scan path uses — a subscription to ten addresses and
     * their targeted refreshes must not be eleven connections.
     */
    private val queries: ChainQuery = ChainRpcSession { method, params -> call(method, params) }

    /** Kept as a field so the reader thread is never collected while it runs. */
    private val readerThread: Thread = Thread({ readLoop() }, "electrum-subscription").apply {
        isDaemon = true
        start()
    }

    // ---- subscription API -------------------------------------------------

    /**
     * `blockchain.scripthash.subscribe`: subscribe to this address and return
     * the status hash the server currently holds for it.
     *
     * The status hash is the whole catch-up mechanism: it changes whenever the
     * address's history changes, so comparing it with the last value seen (live
     * notification or persisted one) says whether anything happened. `null` is a
     * real answer, not an error — Fulcrum answers null for an address it has
     * never seen a transaction for.
     *
     * Later changes arrive through [onNotification].
     */
    fun subscribeScripthash(scripthash: String): String? {
        val result = call("blockchain.scripthash.subscribe", listOf(scripthash))
        return when (result) {
            null -> null
            is String -> result
            else -> throw ElectrumException(
                "scripthash.subscribe did not answer with a status hash: ${MiniJson.encode(result)}",
            )
        }
    }

    /**
     * `blockchain.headers.subscribe`: subscribe to new blocks and return the
     * current tip.
     *
     * Each new block arrives through [onNotification] as
     * `blockchain.headers.subscribe` with the new tip in `params`.
     */
    fun subscribeHeaders(): BlockHeaderTip {
        val result = call("blockchain.headers.subscribe", emptyList())
        val fields = result as? Map<*, *>
            ?: throw ElectrumException("headers.subscribe did not answer with an object: ${MiniJson.encode(result)}")
        return BlockHeaderTip(
            height = (fields["height"] as? Number)?.toInt()
                ?: throw ElectrumException("headers.subscribe reply has no height: ${MiniJson.encode(result)}"),
            hex = fields["hex"] as? String
                ?: throw ElectrumException("headers.subscribe reply has no header hex: ${MiniJson.encode(result)}"),
        )
    }

    // ---- ChainQuery, over the same socket ---------------------------------

    override fun getHistory(scripthash: String): List<HistoryEntry> = queries.getHistory(scripthash)

    override fun getBalance(scripthash: String): ScripthashBalance = queries.getBalance(scripthash)

    override fun listUnspent(scripthash: String): List<ChainUtxo> = queries.listUnspent(scripthash)

    override fun getTransaction(txHash: String): String = queries.getTransaction(txHash)

    override fun getTipHeight(): Int = queries.getTipHeight()

    override fun blockHeader(height: Int): String = queries.blockHeader(height)

    override fun broadcast(txHex: String): String = queries.broadcast(txHex)

    // ---- lifecycle --------------------------------------------------------

    /**
     * Closes the socket and fails anything still in flight.
     *
     * Idempotent, and never calls [onClosed]: this is the caller's decision,
     * not the connection's failure. A session that already died keeps its own
     * terminal error.
     */
    override fun close() {
        if (terminal.compareAndSet(null, callerClosed())) {
            drainPending(terminal.get())
        }
        runCatching { channel.close() }
    }

    // ---- the wire ---------------------------------------------------------

    /**
     * Sends one request, blocks until the reader thread routes its reply here,
     * and returns the `result`.
     *
     * A JSON-RPC `error` for *this* request throws [ElectrumException] to this
     * caller only — the connection is fine and the others carry on. A dead
     * connection throws the one terminal [SubscriptionSessionException] to
     * everyone.
     */
    private fun call(method: String, params: List<Any?>): Any? {
        // Fail fast: a caller arriving after the death must not be left waiting
        // on a socket that will never answer again.
        terminal.get()?.let { throw it }

        val id = nextId.incrementAndGet()
        val request = MiniJson.encode(linkedMapOf<String, Any?>("id" to id, "method" to method, "params" to params))
        val call = Pending(method)
        // Registered before the write: the reply can arrive before flush()
        // returns, and a reply for an unknown id would be dropped.
        pending[id] = call
        // ... and re-checked after the registration. The connection can die
        // between the check above and this line, and its drain would then have
        // missed this request — leaving a caller waiting on a socket nobody will
        // ever read again. Registration is what makes the pair race-free: the
        // death either happened before it (so this read sees it) or after it (so
        // the drain finds the entry).
        terminal.get()?.let { error ->
            pending.remove(id)
            throw error
        }
        try {
            synchronized(writeLock) {
                log("> $request")
                writer.write(request)
                writer.write("\n")
                writer.flush()
            }
        } catch (failure: Throwable) {
            pending.remove(id)
            // A socket that cannot be written to is as dead as one that cannot
            // be read from: report it once, to everyone.
            failTerminal(SubscriptionSessionException("could not send '$method': ${failure.message}", failure))
            throw terminal.get() ?: failure
        }

        // Blocks until this id's reply is routed here, or until the connection
        // dies and the waiter is failed. There is no per-call deadline on
        // purpose: the socket has no read timeout (see the class doc), so a
        // busy server on a slow Tor circuit is waited out rather than mistaken
        // for a dead one. The queue is the handoff from the reader thread; the
        // connection's death always puts something in it, so this cannot hang
        // forever once the session is closed or dead.
        while (true) {
            when (val signal = call.signal.take()) {
                is Answer -> return signal.result
                is Failure -> throw signal.error
            }
        }
    }

    /** The reader loop: one line in, one routing decision, forever. */
    private fun readLoop() {
        var failure: Throwable? = null
        try {
            while (true) {
                val line = reader.readLine()
                    ?: throw SubscriptionSessionException("the peer closed the connection while the session was idle")
                route(line)
            }
        } catch (error: Throwable) {
            // EOF, a reset socket, a malformed line: all of them are the end of
            // this connection. Nothing is swallowed — it becomes the session's
            // one terminal error below.
            failure = error
        }
        val terminalError = failure
        failTerminal(
            if (terminalError is SubscriptionSessionException) {
                terminalError
            } else {
                SubscriptionSessionException("the connection failed: ${terminalError?.message}", terminalError)
            },
        )
    }

    private fun route(line: String) {
        val parsed = try {
            MiniJson.parse(line)
        } catch (error: JsonParseException) {
            throw SubscriptionSessionException("malformed JSON-RPC line from the server: ${error.message}", error)
        }
        val message = parsed as? Map<*, *>
            ?: throw SubscriptionSessionException("expected a JSON-RPC object from the server, got: $line")

        val id = message["id"]
        val method = message["method"] as? String
        if (id == null && method != null) {
            log("< (notification) $line")
            // A handler that throws is the caller's bug, not the server's: it
            // must not kill a healthy connection for every other subscriber.
            runCatching { onNotification(method, message["params"]) }
                .onFailure { log("< notification handler threw: ${it.message}") }
            return
        }

        val numericId = (id as? Number)?.toInt()
            ?: throw SubscriptionSessionException("JSON-RPC line without a usable id: $line")
        val waiting = pending.remove(numericId) ?: run {
            // A reply to a request that already failed, or a server bug. Either
            // way it is not worth killing the connection over.
            log("< (unmatched reply) $line")
            return
        }

        val rpcError = message["error"]
        when {
            rpcError != null -> waiting.signal.put(
                Failure(ElectrumException("'${waiting.method}' returned an error: ${MiniJson.encode(rpcError)}")),
            )
            message.containsKey("result") -> waiting.signal.put(Answer(message["result"]))
            else -> waiting.signal.put(
                Failure(ElectrumException("'${waiting.method}' returned neither result nor error: $line")),
            )
        }
    }

    /**
     * The session's one death notice: fails everything in flight and reports it
     * once. Whoever gets there first owns it — the reader thread for a real
     * failure, [close] for a caller-initiated one — and a second caller is
     * silent rather than reporting a session that is already down.
     */
    private fun failTerminal(error: Throwable) {
        if (!terminal.compareAndSet(null, error)) return
        drainPending(error)
        runCatching { onClosed(error) }
        runCatching { channel.close() }
    }

    /** Fails every in-flight call, so nobody is left blocked on a dead socket. */
    private fun drainPending(error: Throwable?) {
        val reason = error ?: SubscriptionSessionException("the session was closed")
        pending.keys.toList().forEach { id -> pending.remove(id)?.signal?.put(Failure(reason)) }
    }

    private fun callerClosed(): SubscriptionSessionException =
        SubscriptionSessionException("the session was closed by the caller")

    /** A request that is out on the wire. */
    private class Pending(val method: String) {
        /** The reader thread's handoff to the waiting caller. At most one entry. */
        val signal = LinkedBlockingQueue<Signal>(1)
    }

    /** What the reader thread hands a waiting caller. */
    private sealed interface Signal

    private class Answer(val result: Any?) : Signal

    private class Failure(val error: Throwable) : Signal

    companion object {
        /**
         * The session socket's read timeout: none. A long-lived subscription is
         * idle for hours by design and must not be torn down for silence — see
         * the class doc.
         */
        const val NO_READ_TIMEOUT_MILLIS: Int = 0
    }
}
