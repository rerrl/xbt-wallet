package com.xbtwallet.app.electrum.tor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.xbtwallet.app.electrum.TorBootstrap
import com.xbtwallet.app.electrum.TorBootstrapException
import com.xbtwallet.app.electrum.TorPhase
import com.xbtwallet.app.electrum.TorProxy
import com.xbtwallet.app.electrum.TorStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.torproject.jni.TorService
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Runs the bundled Tor daemon (the native tor inside `info.guardianproject:tor-android`)
 * and reports the SOCKS port it is listening on.
 *
 * **Why not a foreground service.** `TorService` starts tor in `onCreate` and
 * publishes its control connection through a local binder; binding with
 * `BIND_AUTO_CREATE` is enough to bring it up, and the binding keeps it alive
 * while the app is using it. The service is *also* started — see
 * [bindTorService] — so tor survives the app being backgrounded for a while: a
 * bound-only service is torn down as soon as the app's foreground time ends,
 * which is what made Tor silently drop out in the background. There is
 * deliberately no foreground service and no notification (the GrapheneOS/F-Droid
 * stance this app takes), so when the system kills tor anyway the recovery path
 * is the probe-before-reuse in [start], not a persistent notification.
 *
 * **Readiness.** A SOCKS port existing is not the same as Tor being usable: tor
 * opens its SOCKS listener early, but a `.onion` connect before the first
 * circuit is established fails. So this waits for the reported SOCKS port,
 * `status/circuit-established` (or `BOOTSTRAP PROGRESS=100`), *and* a real
 * connect to the SOCKS listener itself before handing back the proxy. Progress
 * is logged at every 10% so the Server screen's log shows a long bootstrap
 * doing something rather than hanging.
 *
 * **Lifetime.** Tor is started once and then reused for the life of the process.
 * [start] probes the cached SOCKS endpoint before reuse: a listener that answers
 * is handed back immediately, while a dead one (the daemon killed while the app
 * was away) is dropped and re-bootstrapped, so recovery is automatic even when
 * `onServiceDisconnected` is never delivered. A bootstrap that is *cancelled*
 * mid-flight (screen left, app backgrounded, activity recreated) publishes
 * [TorPhase.OFF] rather than freezing on STARTING, so no screen is left reading
 * a stale percentage forever. The binding is intentionally never released —
 * unbinding would destroy the service and kill tor mid-session, which would make
 * the second connection test mysteriously slower and the first scan fail.
 *
 * @param context any context; the application context is what gets bound.
 * @param timeoutMillis how long to wait for a working circuit before giving up.
 * @param pollIntervalMillis how often to ask the control connection.
 */
class AndroidTorBootstrap(
    context: Context,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val pollIntervalMillis: Long = 250L,
) : TorBootstrap {

    private val appContext: Context = context.applicationContext

    /** Serialises concurrent starts: two screens asking for Tor start one daemon. */
    private val mutex = Mutex()

    @Volatile
    private var service: TorService? = null

    @Volatile
    private var ready: TorProxy? = null

    /** Held for the process lifetime so the binding is never garbage-collected. */
    @Volatile
    private var connection: ServiceConnection? = null

    /**
     * The live bootstrap state, as Compose state so a screen can observe it.
     *
     * This file is the one place in the Tor stack that is allowed an Android
     * import, so it is also where the observable flavour of [status] lives: the
     * screens render a status line, and a `State<TorStatus>` lets them recompose
     * when it changes without polling. [status] reads the same value for callers
     * (the home screen's check) that only want one snapshot.
     */
    private val _status = mutableStateOf(TorStatus.OFF)

    /** The bootstrap state as observable Compose state. */
    val observableStatus: State<TorStatus> get() = _status

    override val status: TorStatus get() = _status.value

    override suspend fun start(log: (String) -> Unit): TorProxy {
        ready?.let { cached ->
            if (withContext(Dispatchers.IO) { socksListenerReachable(cached.port) }) {
                log("< tor: already running, socks proxy ${cached.host}:${cached.port}")
                return cached
            }
            // The daemon died under us (service killed while the app was away):
            // the cached SOCKS endpoint is dead — drop it and re-bootstrap.
            log("< tor: cached SOCKS endpoint ${cached.host}:${cached.port} is dead — restarting Tor")
            ready = null
            _status.value = TorStatus(TorPhase.OFF, 0)
        }
        return mutex.withLock {
            ready?.let { cached ->
                if (withContext(Dispatchers.IO) { socksListenerReachable(cached.port) }) {
                    log("< tor: already running, socks proxy ${cached.host}:${cached.port}")
                    return@withLock cached
                }
                ready = null
                _status.value = TorStatus(TorPhase.OFF, 0)
            }
            withContext(Dispatchers.IO) { bootstrapTor(log) }
        }
    }

    private suspend fun bootstrapTor(log: (String) -> Unit): TorProxy {
        // Entry point of a bootstrap (first call, or a retry after a failure):
        // from here until either READY or FAILED the status is STARTING, so the
        // home screen shows a progress line instead of pinging a server it
        // cannot reach through a Tor that is not up yet.
        _status.value = TorStatus(TorPhase.STARTING, 0)
        try {
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            val tor = service ?: bindTorService(log)
            log("< tor: starting the bundled Tor daemon (this can take up to ${timeoutMillis / 1000}s)")

            var lastTenth = -1
            var lastPhase = "starting"
            while (SystemClock.elapsedRealtime() < deadline) {
                val socksPort = runCatching { tor.socksPort }.getOrDefault(0)
                val phase = runCatching { tor.getInfo("status/bootstrap-phase") }.getOrNull()
                val circuitEstablished = runCatching { tor.getInfo("status/circuit-established") }.getOrNull()

                val progress = PROGRESS.find(phase.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                lastPhase = describePhase(phase, progress, circuitEstablished)

                // Publish each new percentage so the UI tracks the bootstrap
                // rather than freezing on the value it saw first.
                if (progress != _status.value.progressPercent) {
                    _status.value = TorStatus(TorPhase.STARTING, progress)
                }

                if (socksPort > 0 && (circuitEstablished?.trim() == "1" || progress >= 100)) {
                    // tor opens the SOCKS listener before the daemon is actually
                    // able to answer through it, so "port exists" is not "usable".
                    // Probe the listener for real: a connect that completes is the
                    // only proof that the port this app will dial is the port tor
                    // is listening on (9050 could equally belong to another app).
                    if (socksListenerReachable(socksPort)) {
                        log("< tor: SOCKS listener ${SOCKS_HOST}:$socksPort accepted a probe connection")
                        val proxy = TorProxy(host = SOCKS_HOST, port = socksPort)
                        ready = proxy
                        _status.value = TorStatus(TorPhase.READY, 100)
                        log("< tor: ready, socks proxy on ${proxy.host}:${proxy.port}")
                        runCatching { tor.getInfo("net/listeners/socks") }.getOrNull()
                            ?.let { log("< tor: net/listeners/socks: $it") }
                        runCatching { tor.getInfo("version") }.getOrNull()
                            ?.let { log("< tor: version: $it") }
                        return proxy
                    }
                    lastPhase = "SOCKS listener on $socksPort is not accepting connections yet"
                }

                val tenth = progress / 10
                if (tenth != lastTenth) {
                    log("< tor: bootstrapping $progress%${summarySuffix(phase)}")
                    lastTenth = tenth
                }

                delay(pollIntervalMillis)
            }

            throw TorBootstrapException(
                "Tor did not finish bootstrapping within ${timeoutMillis / 1000}s (last status: $lastPhase)",
            )
        } catch (cancel: CancellationException) {
            // A cancelled bootstrap (screen left, app backgrounded, activity
            // recreated) is "not started", NOT "starting": freezing STARTING here
            // is what stranded the home screen on "Tor starting… 100%" forever.
            _status.value = TorStatus(TorPhase.OFF, 0)
            throw cancel
        } catch (failure: Exception) {
            // Remember how far it got, so the phase stops reading STARTING once
            // the bootstrap has given up and a screen can honestly say it failed.
            // Anything thrown — not just a TorBootstrapException — must unstick it.
            _status.value = TorStatus(TorPhase.FAILED, _status.value.progressPercent)
            throw failure
        }
    }

    /**
     * Binds [TorService], which is what starts tor. A failure here is reported
     * as a [TorBootstrapException] with a sentence a user can act on — it means
     * the platform refused to create the service, not that the network is down.
     */
    private fun bindTorService(log: (String) -> Unit): TorService {
        val latch = java.util.concurrent.CountDownLatch(1)
        var bound: TorService? = null
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bound = (binder as? TorService.LocalBinder)?.service
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // tor died under us. Clear the cached endpoint AND the published
                // status together: a later start() must re-bootstrap rather than
                // hand back a proxy pointing at a dead SOCKS port.
                service = null
                ready = null
                _status.value = TorStatus(TorPhase.OFF, 0)
            }
        }
        connection = serviceConnection

        val boundOk = runCatching {
            appContext.bindService(
                Intent(appContext, TorService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrElse { error ->
            throw TorBootstrapException("could not bind the bundled Tor service: ${error.message}", error)
        }
        if (!boundOk) {
            throw TorBootstrapException("the platform refused to bind the bundled Tor service")
        }
        if (!latch.await(BIND_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            throw TorBootstrapException("the bundled Tor service did not start within ${BIND_TIMEOUT_MILLIS / 1000}s")
        }
        val started = bound ?: throw TorBootstrapException("the bundled Tor service started without a usable binder")
        service = started
        // Bound-only services die with the app's foreground time; a started+bound
        // service survives background stints within the system's grace window.
        // TorService.onStartCommand is null-intent safe (verified against the
        // tor-android 0.4.9.6 AAR). Best-effort: a background start is refused on
        // API 26+ — the binding alone still works, and the self-healing start()
        // above is the backstop for when the daemon is killed anyway.
        runCatching { appContext.startService(Intent(appContext, TorService::class.java)) }
            .onFailure { log("< tor: startService refused (${it.message}) — continuing bound-only") }
        log("< tor: service bound")
        return started
    }

    private fun describePhase(phase: String?, progress: Int, circuitEstablished: String?): String = when {
        phase.isNullOrBlank() && circuitEstablished == null -> "control connection not ready"
        progress >= 100 || circuitEstablished?.trim() == "1" -> "circuit established"
        else -> "bootstrap ${progress}%${summarySuffix(phase)}"
    }

    /**
     * Opens and immediately closes a local socket to the SOCKS listener.
     *
     * This is the readiness test that matters: tor can report a SOCKS port and
     * an established circuit while the listener is still not accepting. A
     * refused connection ([java.net.ConnectException], a subclass of
     * [java.io.IOException]) means "not ready, keep polling" — the caller does,
     * because this only answers a question. Reporting the port tor actually
     * listens on, rather than assuming 9050, is also what settles any question
     * of who owns that port.
     */
    private fun socksListenerReachable(port: Int): Boolean = try {
        Socket().use { probe ->
            probe.connect(InetSocketAddress(SOCKS_HOST, port), SOCKS_PROBE_TIMEOUT_MILLIS)
        }
        true
    } catch (error: java.io.IOException) {
        false
    }

    private fun summarySuffix(phase: String?): String {
        val summary = SUMMARY.find(phase.orEmpty())?.groupValues?.getOrNull(1)?.trim()
        return if (summary.isNullOrEmpty()) "" else " ($summary)"
    }

    companion object {
        /** Default patience: a cold Tor bootstrap on a phone can take a minute. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 120_000

        /** How long the platform gets to hand back the service binder. */
        const val BIND_TIMEOUT_MILLIS: Long = 10_000

        /** Tor's SOCKS listener is loopback-only. */
        const val SOCKS_HOST: String = "127.0.0.1"

        /** How long the SOCKS listener probe may take before "not ready". */
        const val SOCKS_PROBE_TIMEOUT_MILLIS: Int = 1_000

        private val PROGRESS = Regex("PROGRESS=(\\d+)")
        private val SUMMARY = Regex("SUMMARY=\"([^\"]*)\"")
    }
}
