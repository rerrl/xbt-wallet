package com.xbtwallet.app.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.xbtwallet.app.data.SavedServer
import com.xbtwallet.app.data.SavedServersStore
import com.xbtwallet.app.data.ServerSettingsStore
import com.xbtwallet.app.data.SharedPreferencesKeyValueStore
import com.xbtwallet.app.electrum.ElectrumClient
import com.xbtwallet.app.electrum.ElectrumProtocol
import com.xbtwallet.app.electrum.ElectrumRouting
import com.xbtwallet.app.electrum.ElectrumServer
import com.xbtwallet.app.ui.theme.XbtWalletTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How long the "Copied N lines" confirmation stays on screen. */
private const val COPIED_NOTICE_MILLIS: Long = 2_000L

/** Outcome of the most recent connection test. */
enum class ConnectionStatus { IDLE, TESTING, CONNECTED, FAILED }

/**
 * State behind the Server screen: the endpoint being edited plus the scrolling
 * connection log. Held outside the composable so the log survives recomposition
 * while a test is running.
 *
 * It also owns the user's **saved presets** — named endpoints they can drop back
 * into the fields. A preset only refills the form ([applyPreset]); saving,
 * deleting and dialling all stay explicit actions.
 *
 * Transport is one switch, not a protocol list — see [ServerSsl] for the rules
 * that keep the port field in step with it.
 */
@Stable
class ServerScreenState(
    private val store: ServerSettingsStore,
    private val savedServersStore: SavedServersStore,
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

    /**
     * Whether the entered endpoint would pass the same checks [testConnection]
     * makes before dialling: a host is given, the port is in range, and an onion
     * host is one Tor could actually serve.
     *
     * The save-preset action is gated on this, so a preset can never be a record
     * of an endpoint the app itself would refuse to test.
     */
    val formIsValid: Boolean
        get() {
            val trimmedHost = host.trim()
            val portNumber = port.toIntOrNull()
            return trimmedHost.isNotEmpty() &&
                portNumber != null && portNumber in 1..65535 &&
                ElectrumRouting.onionAddressProblem(trimmedHost) == null
        }

    /**
     * The user's saved connection presets, in the order they were added — the
     * list the picker shows.
     *
     * Seeded from [SavedServersStore.list] and re-read from the store after every
     * save or delete, so what is on screen is what is on disk. Only
     * [SavedServer.name] is ever rendered: a preset's host is a server address,
     * and a saved server address is never shown in this app's UI.
     */
    val presets = mutableStateListOf<SavedServer>().also { it.addAll(savedServersStore.list()) }

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
     * Refills the form from [preset] — and only refills it.
     *
     * Nothing here dials or reconnects: the user still taps Test connection (or
     * Save, by leaving the screen) themselves, which is what makes a preset a
     * shortcut rather than an action.
     *
     * The port is marked as customized on purpose. A preset's port is a value the
     * user chose deliberately when they saved it, so the SSL switch — which only
     * ever rewrites a port still sitting on a convention default — must not snap
     * it to 50001/50002 behind their back.
     */
    fun applyPreset(preset: SavedServer) {
        host = preset.host
        port = preset.port.toString()
        useSsl = ServerSsl.useSslFor(preset.protocol)
        portCustomized = true
        persist()
    }

    /**
     * Saves the form's current endpoint under [name], trimming the name first.
     *
     * A name that is already in the list is overwritten **in place** by the store,
     * with no confirmation: the picker's Delete row is the deliberate way to
     * remove one, and re-saving a name the user already chose is them saying
     * "this one, not the old one". A blank name is not a preset, so it is
     * ignored.
     */
    fun savePreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        savedServersStore.save(
            SavedServer(
                name = trimmed,
                host = host.trim(),
                port = port.toIntOrNull() ?: ServerSsl.defaultPortFor(useSsl),
                protocol = protocol,
            ),
        )
        refreshPresets()
    }

    /** Removes the preset named [name], then re-reads the picker's list. */
    fun deletePreset(name: String) {
        savedServersStore.delete(name)
        refreshPresets()
    }

    private fun refreshPresets() {
        presets.clear()
        presets.addAll(savedServersStore.list())
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
        val onionProblem = ElectrumRouting.onionAddressProblem(host)
        when {
            host.isEmpty() -> {
                inputError = "Enter a host or IP address."
                return
            }
            portNumber == null || portNumber !in 1..65535 -> {
                inputError = "Enter a port between 1 and 65535."
                return
            }
            onionProblem != null -> {
                // Refused before Tor is even started: an address Tor cannot
                // serve would come back as an opaque "SOCKS server general
                // failure" from the proxy, which tells the user nothing.
                inputError = null
                log.clear()
                appendLog("xbt-wallet \u2014 private Electrum connection test")
                appendLog("target: $host:$portNumber (${protocol.label})")
                appendLog("\u2717 connection test refused: $onionProblem")
                status = ConnectionStatus.FAILED
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
fun rememberServerScreenState(
    store: ServerSettingsStore,
    savedServersStore: SavedServersStore,
): ServerScreenState {
    val scope = rememberCoroutineScope()
    return remember(store, savedServersStore, scope) {
        ServerScreenState(store, savedServersStore, scope)
    }
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

            if (state.presets.isNotEmpty()) {
                // Names only, deliberately. A preset carries a host:port, and a
                // saved server address never appears in this app's UI — the name
                // is the whole of what the user sees here.
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { menuOpen = true }) {
                        Text("Saved connections")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        state.presets.forEach { preset ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = preset.name,
                                            modifier = Modifier.weight(1f),
                                        )
                                        // A row of its own button, so deleting a
                                        // preset is never the same tap as using it.
                                        TextButton(onClick = { state.deletePreset(preset.name) }) {
                                            Text("Delete")
                                        }
                                    }
                                },
                                onClick = {
                                    // Populates the fields and nothing else: the
                                    // user still taps Test connection themselves.
                                    state.applyPreset(preset)
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

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
                val problem = ElectrumRouting.onionAddressProblem(state.host)
                if (problem != null) {
                    // Refused, not just warned: "Test connection" will not dial.
                    Text(
                        text = problem,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = "Onion host \u2014 this connection goes through the bundled Tor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50),
                    )
                }
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

            val clipboard = LocalClipboardManager.current
            var copiedNotice by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(copiedNotice) {
                if (copiedNotice != null) {
                    delay(COPIED_NOTICE_MILLIS)
                    copiedNotice = null
                }
            }

            var showSavePreset by remember { mutableStateOf(false) }
            var presetName by remember { mutableStateOf("") }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = state::testConnection,
                    enabled = !state.isTesting,
                ) {
                    Text("Test connection")
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Disabled on the same input checks testConnection makes, so a
                // preset can never record an endpoint the app would refuse.
                TextButton(
                    onClick = {
                        presetName = ""
                        showSavePreset = true
                    },
                    enabled = state.formIsValid,
                ) {
                    Text("Save as preset\u2026")
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = state::clearLog, enabled = state.log.isNotEmpty()) {
                    Text("Clear log")
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(
                    onClick = {
                        // The whole log, timestamps included, in the order it is
                        // shown — that is what a bug report needs.
                        clipboard.setText(AnnotatedString(state.log.joinToString("\n")))
                        copiedNotice = "Copied ${state.log.size} lines"
                    },
                    enabled = state.log.isNotEmpty(),
                ) {
                    Text("Copy log")
                }
            }

            copiedNotice?.let { notice ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2E7D32),
                )
            }

            if (showSavePreset) {
                // No path to a host or port is offered here: a preset is a name
                // plus whatever the form currently holds.
                AlertDialog(
                    onDismissRequest = { showSavePreset = false },
                    title = { Text("Save connection preset") },
                    text = {
                        OutlinedTextField(
                            value = presetName,
                            onValueChange = { presetName = it },
                            label = { Text("Preset name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                state.savePreset(presetName)
                                showSavePreset = false
                            },
                            enabled = presetName.isNotBlank(),
                        ) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSavePreset = false }) {
                            Text("Cancel")
                        }
                    },
                )
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
    val savedServersStore = SavedServersStore(SharedPreferencesKeyValueStore(LocalContext.current))
    XbtWalletTheme {
        ServerScreen(
            state = rememberServerScreenState(store, savedServersStore),
            onBack = {},
        )
    }
}
