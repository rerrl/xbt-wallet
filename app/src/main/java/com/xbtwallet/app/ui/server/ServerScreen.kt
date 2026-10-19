package com.xbtwallet.app.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.xbtwallet.app.data.ServerSettingsStore
import com.xbtwallet.app.electrum.ElectrumClient
import com.xbtwallet.app.electrum.ElectrumProtocol
import com.xbtwallet.app.electrum.ElectrumRouting
import com.xbtwallet.app.electrum.ElectrumServer
import com.xbtwallet.app.ui.theme.XbtWalletTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Outcome of the most recent connection test. */
enum class ConnectionStatus { IDLE, TESTING, CONNECTED, FAILED }

/**
 * State behind the Server screen: the endpoint being edited plus the scrolling
 * connection log. Held outside the composable so the log survives recomposition
 * while a test is running.
 *
 * Transport is one switch, not a protocol list — see [ServerSsl] for the rules
 * that keep the port field in step with it.
 */
@Stable
class ServerScreenState(
    private val store: ServerSettingsStore,
    private val scope: CoroutineScope,
) {

    private val stored = store.load()

    var host by mutableStateOf(stored.host)
        private set

    var port by mutableStateOf(stored.port.toString())
        private set

    /**
     * The single transport decision the screen exposes, shown as a **Use SSL**
     * switch. It is derived from the stored protocol and written back through
     * [ServerSsl.protocolFor], so the persisted shape is unchanged.
     */
    var useSsl by mutableStateOf(ServerSsl.useSslFor(stored.protocol))
        private set

    /**
     * Whether [port] is the user's own number rather than the convention default
     * for the current switch position. A custom port must not be rewritten when
     * the transport changes.
     */
    var portCustomized by mutableStateOf(ServerSsl.isCustomPort(stored.port.toString(), useSsl))
        private set

    /** The protocol matching the switch — what is dialled and stored. */
    val protocol: ElectrumProtocol
        get() = ServerSsl.protocolFor(useSsl)

    /** One line per log entry, oldest first. */
    val log = mutableStateListOf<String>()

    var status by mutableStateOf(ConnectionStatus.IDLE)
        private set

    var isTesting by mutableStateOf(false)
        private set

    /** Set when the entered endpoint cannot be used, null otherwise. */
    var inputError by mutableStateOf<String?>(null)
        private set

    fun onHostChange(value: String) {
        host = value
        persist()
    }

    fun onPortChange(value: String) {
        port = value.filter { it.isDigit() }.take(5)
        portCustomized = ServerSsl.isCustomPort(port, useSsl)
        persist()
    }

    /**
     * Flips the transport. The port follows along only while it is still a
     * convention default; a number the user typed is theirs and stays put.
     */
    fun onUseSslChange(value: Boolean) {
        if (value == useSsl) return
        if (!portCustomized) port = ServerSsl.portAfterToggle(port, value, wasCustom = false)
        useSsl = value
        persist()
    }

    fun clearLog() {
        log.clear()
    }

    /**
     * Runs the handshake against the entered endpoint, streaming every step into
     * [log]. Nothing here can crash the app: the client reports failures as log
     * lines (see [ElectrumClient.testConnection]).
     */
    fun testConnection() {
        if (isTesting) return

        val host = host.trim()
        val portNumber = port.toIntOrNull()
        when {
            host.isEmpty() -> {
                inputError = "Enter a host or IP address."
                return
            }
            portNumber == null || portNumber !in 1..65535 -> {
                inputError = "Enter a port between 1 and 65535."
                return
            }
            else -> inputError = null
        }

        persist()
        isTesting = true
        status = ConnectionStatus.TESTING
        log.clear()
        appendLog("xbt-wallet — private Electrum connection test")
        appendLog("target: $host:$portNumber (${protocol.label})")
        appendLog("handshake: server.version ${ElectrumClient.PROTOCOL_VERSION}, then server.features, server.banner")

        val server = ElectrumServer(host = host, port = portNumber, protocol = protocol)
        if (ElectrumRouting.isOnionHost(host)) {
            appendLog("route: .onion host \u2014 the bundled Tor starts before the socket is dialled")
        }
        scope.launch {
            // The default transport routes `.onion` through the bundled Tor and
            // everything else directly; this is the same client the signer scan
            // uses, so both paths are one code path.
            val client = ElectrumClient(server = server, log = ::appendLog)
            val connected = client.testConnection()
            status = if (connected) ConnectionStatus.CONNECTED else ConnectionStatus.FAILED
            isTesting = false
        }
    }

    private fun appendLog(line: String) {
        // Called from the client's IO thread; hop onto the UI thread because the
        // log is a snapshot-backed list.
        scope.launch {
            if (log.size >= MAX_LOG_LINES) log.removeAt(0)
            log.add("${TIMESTAMP_FORMAT.format(Date())}  $line")
        }
    }

    private fun persist() {
        store.save(
            ElectrumServer(
                host = host.trim(),
                port = port.toIntOrNull() ?: ServerSsl.defaultPortFor(useSsl),
                protocol = protocol,
            ),
        )
    }

    private companion object {
        const val MAX_LOG_LINES = 1000
        val TIMESTAMP_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}

/** Remembers a [ServerScreenState] for the Server screen. */
@Composable
fun rememberServerScreenState(store: ServerSettingsStore): ServerScreenState {
    val scope = rememberCoroutineScope()
    return remember(store, scope) { ServerScreenState(store, scope) }
}

/**
 * "Private Electrum" screen modeled on Sparrow/Shrike's UX: a single private
 * server (no server list, no public servers) and a visible log of the handshake
 * — the log is the point.
 *
 * Tor is not a toggle. A host ending in `.onion` is routed through the app's
 * bundled Tor daemon automatically; a clearnet host is dialled directly. The
 * log says which route was taken, and Tor's bootstrap progress appears in it
 * while a cold start is in flight.
 */
@Composable
fun ServerScreen(
    state: ServerScreenState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onion = ElectrumRouting.isOnionHost(state.host)
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("\u2190 Back") }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Private Electrum server",
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Enter a host or IP. An .onion address is routed through the " +
                    "built-in Tor automatically \u2014 no Orbot needed; any other host is " +
                    "dialled directly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = state.host,
                onValueChange = state::onHostChange,
                label = { Text("Host or IP") },
                placeholder = { Text("192.168.1.10 or example.onion") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (onion) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Onion host \u2014 this connection goes through the bundled Tor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4CAF50),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = state.port,
                onValueChange = state::onPortChange,
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Use SSL",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = state.useSsl,
                    onCheckedChange = state::onUseSslChange,
                )
            }
            Text(
                text = "TLS on the port you enter \u2014 works for a clearnet host and a " +
                    ".onion alike.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.inputError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = state::testConnection,
                    enabled = !state.isTesting,
                ) {
                    Text("Test connection")
                }
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(onClick = state::clearLog, enabled = state.log.isNotEmpty()) {
                    Text("Clear log")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            StatusRow(state)

            Spacer(modifier = Modifier.height(12.dp))

            ConnectionLog(state, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatusRow(state: ServerScreenState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.isTesting) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        val onion = ElectrumRouting.isOnionHost(state.host)
        val (label, color) = when (state.status) {
            ConnectionStatus.IDLE -> "Not tested yet" to MaterialTheme.colorScheme.onSurfaceVariant
            ConnectionStatus.TESTING ->
                (if (onion) "Bootstrapping Tor and testing\u2026" else "Testing\u2026") to
                    MaterialTheme.colorScheme.onSurfaceVariant
            ConnectionStatus.CONNECTED ->
                (if (onion) "Connected through Tor" else "Connected") to Color(0xFF2E7D32)
            ConnectionStatus.FAILED -> "Failed \u2014 see log" to MaterialTheme.colorScheme.error
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
private fun ConnectionLog(state: ServerScreenState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (state.log.isEmpty()) {
            Text(
                text = "Connection log \u2014 requests and replies appear here.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            )
        } else {
            val listState = rememberLazyListState()
            LaunchedEffect(state.log.size) {
                listState.scrollToItem(state.log.size - 1)
            }
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.log.size) { index ->
                    Text(
                        text = state.log[index],
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun ServerScreenPreview() {
    val store = ServerSettingsStore(LocalContext.current)
    XbtWalletTheme {
        ServerScreen(state = rememberServerScreenState(store), onBack = {})
    }
}
