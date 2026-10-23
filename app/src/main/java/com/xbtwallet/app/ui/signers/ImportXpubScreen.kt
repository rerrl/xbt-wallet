package com.xbtwallet.app.ui.signers

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.xbtwallet.app.crypto.XpubImport
import com.xbtwallet.app.crypto.XpubScanRoute
import com.xbtwallet.app.crypto.validateXpubInput
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerOrigin
import com.xbtwallet.app.data.SignerStore

/**
 * State behind the watch-only xpub import screen: the signer's name, the key
 * captured by the last scan (plus whatever metadata rode with it), and the save
 * action.
 *
 * Kept out of the composable for two reasons. First, it is plain Kotlin and can
 * be exercised without a UI test — and without a camera. Second, the screen is
 * re-composed constantly; holding the scan result and the inline errors here
 * means the state machine is a property of this object, not of how many times
 * Compose happened to re-run the composable.
 *
 * There is no text field for the key any more: import is QR-only, so "what key
 * have we got" is exactly "what did the last scan produce". A bad scan clears
 * every scanned field before it records its error, so a stale earlier key can
 * never look like the one that was just scanned.
 *
 * The panel is not a screen of its own: the screen opens it on entry (asking the
 * one camera permission it needs), and it comes down again as soon as a scan has
 * produced a result — a live camera after the key is captured is not wanted.
 * What brings it back is **Rescan**, and that starts from a clean slate: it
 * forgets the last result, its metadata and its complaint before the panel
 * reappears, so nothing on screen can belong to a key the user just threw away.
 * There is no cancel while the panel is up: the ways out are a scan, or leaving
 * the screen.
 */
@Stable
class ImportXpubScreenState(
    private val signerStore: SignerStore,
    private val onSaved: (Signer) -> Unit,
) {

    var name by mutableStateOf("")
        private set

    var nameError by mutableStateOf<String?>(null)
        private set

    /** The key text of the last successful scan, null until one lands. */
    var scannedXpub by mutableStateOf<String?>(null)
        private set

    /** Master fingerprint carried by the scanned key expression, when there was one. */
    var masterFingerprint by mutableStateOf<String?>(null)
        private set

    /** Derivation path (no leading `m/`) carried by the scanned key expression, when there was one. */
    var derivationPath by mutableStateOf<String?>(null)
        private set

    /** Set to "zpub"/"xpub"/"ypub" once a scan parses, null otherwise. */
    var recognisedVersion by mutableStateOf<String?>(null)
        private set

    /** The shared validator's message for the most recent bad scan, null otherwise. */
    var scanError by mutableStateOf<String?>(null)
        private set

    /**
     * Whether the embedded scanner panel is on screen.
     *
     * The panel is part of this screen, not a separate activity, so "is the
     * scanner up" is screen state like any other — and, being plain state, it is
     * testable without a camera. It is opened by the screen on entry (or by
     * [onRescanRequested]) and closed by a scan that produced a result; no
     * control on the screen puts it away, because the QR is the only way in.
     */
    var showScanner by mutableStateOf(false)
        private set

    /**
     * Whether the user refused the CAMERA permission.
     *
     * Kept so the screen can say why there is no scanner and offer a retry —
     * the "Try again" button is the only scanner control this screen has.
     * There is no paste fallback to fall back to: the QR is the only way in.
     */
    var cameraDenied by mutableStateOf(false)
        private set

    /** The parsed key from the last successful scan, or null. */
    private var scannedKey: XpubImport.Valid? = null

    fun onNameChange(value: String) {
        name = value
        if (nameError != null && value.isNotBlank()) nameError = null
    }

    /**
     * The CAMERA permission is held — either it was already granted or the
     * system dialog just came back positive. Opens the embedded panel.
     *
     * Any earlier denial is cleared here so the hint disappears the moment the
     * user gets the permission, rather than lingering behind a working scanner.
     */
    fun onCameraPermissionGranted() {
        cameraDenied = false
        showScanner = true
    }

    /** The user refused the camera. The panel never opens. */
    fun onCameraPermissionDenied() {
        cameraDenied = true
    }

    /**
     * Closes the panel after it has produced a result.
     *
     * A live camera is only wanted while there is something to read, so the
     * screen takes the panel down as soon as a scan has landed — valid or not.
     * Whatever the scan produced is left exactly as it was: hiding the panel is
     * not the same as a bad scan, which clears the fields before it records its
     * message.
     */
    fun onScannerDismissed() {
        showScanner = false
    }

    /**
     * **Rescan** — the user is not keeping what the last scan produced.
     *
     * [clearScan] throws away the last result, its metadata and any complaint
     * about it, and the panel comes back up on that empty slate, so the
     * confirmation below can only ever describe the key that is there now.
     *
     * The caller checks the CAMERA permission before this, exactly as it does on
     * entry: [onRescanRequested] is only ever reached with the permission in
     * hand, which is also why it clears any earlier denial.
     */
    fun onRescanRequested() {
        clearScan()
        cameraDenied = false
        showScanner = true
    }

    /**
     * Drops everything the last scan left behind: the parsed key, the scanned
     * text, its version label, its fingerprint and path, and the message a bad
     * scan recorded.
     */
    private fun clearScan() {
        scannedKey = null
        scannedXpub = null
        recognisedVersion = null
        masterFingerprint = null
        derivationPath = null
        scanError = null
    }

    /**
     * Routes and validates one scanned QR payload.
     *
     * [XpubScanRoute] decides what part of the payload is the key and what
     * metadata came with it; [validateXpubInput] then decides whether that key is
     * any good — the same validator a keyboard entry would have gone through, so
     * the camera can never accept what typing would have refused.
     *
     * A valid scan populates the key and its metadata and clears any error. An
     * invalid scan **clears** the scanned fields before recording the message:
     * leaving a previous good key on screen after a bad scan would present the
     * wrong key as imported. Scanning again replaces everything either way.
     */
    fun onScanResult(payload: String) {
        val result = XpubScanRoute.extract(payload)
        when (val validation = validateXpubInput(result.keyText)) {
            is XpubImport.Valid -> {
                scannedKey = validation
                scannedXpub = result.keyText
                recognisedVersion = validation.versionLabel
                masterFingerprint = result.masterFingerprint
                derivationPath = result.derivationPath
                scanError = null
            }

            is XpubImport.Invalid -> {
                clearScan()
                scanError = validation.message
            }
        }
    }

    /** Validates and, when everything checks out, stores a new watch-only wallet. */
    fun import() {
        nameError = if (name.isBlank()) "Give this wallet a name." else null

        val key = scannedKey
        if (key == null) {
            // Do not overwrite a specific validator message with the generic
            // "scan something" one — a bad scan's reason is more useful.
            if (scanError == null) {
                scanError = "Scan the signer's account xpub QR before adding it."
            }
            return
        }
        if (nameError != null) return

        val xpub = scannedXpub ?: return
        // The metadata is stored as scanned (fingerprint lowercased, path
        // normalized by XpubScanRoute); `key` proves the xpub parsed. The origin
        // is spelled out as the text-QR default: this key came in as a plain
        // xpub QR, not off a signing device, and that is what keeps it wearing
        // the grey "watch-only" tag rather than the orange "seedsigner" one.
        onSaved(
            signerStore.add(
                name = name,
                xpub = xpub,
                masterFingerprint = masterFingerprint,
                derivationPath = derivationPath,
                importedVia = SignerOrigin.TEXT_QR,
            ),
        )
    }
}

/**
 * Remembers an [ImportXpubScreenState] for the import screen.
 *
 * The `onSaved` callback goes through [rememberUpdatedState] and the state
 * object is keyed only on `signerStore`: if the callback were a `remember` key,
 * a fresh lambda on every composition would recreate the state object and wipe
 * whatever the user had entered.
 */
@Composable
fun rememberImportXpubScreenState(
    signerStore: SignerStore,
    onSaved: (Signer) -> Unit,
): ImportXpubScreenState {
    val currentOnSaved by rememberUpdatedState(onSaved)
    return remember(signerStore) { ImportXpubScreenState(signerStore) { signer -> currentOnSaved(signer) } }
}

/**
 * Add a wallet by scanning its account xpub — the screen of the Add-wallet flow
 * reached from [AddSignerScreen]'s **Watch only** card.
 *
 * Import is **QR-only**. The QR is the air-gapped channel between the signer and
 * this phone, and it is the only channel that can carry the account's metadata
 * (the master fingerprint and derivation path a BIP-32 key-expression QR holds).
 * A paste field would have been a second way in that silently drops that
 * metadata and invites fat-fingered keys, so it is gone: every key that reaches
 * the store came off a QR, was routed by [XpubScanRoute], and was then validated
 * by the shared [com.xbtwallet.app.crypto.validateXpubInput] — the same gate a
 * keyboard entry would have had to pass.
 *
 * Scanning is an embedded panel, not a screen of its own: [XpubQrScannerPanel]
 * opens inline in this column, in portrait. Nothing here launches the scanner
 * library's full-screen activity.
 *
 * **The scanner is up from the moment the screen opens, and it is the only way
 * in.** Entering the screen asks for the camera once ([LaunchedEffect] below,
 * through the same request the denial recovery uses): already-granted opens the
 * panel immediately, otherwise the one system permission dialog runs. There is
 * no button to press first. Once the panel is up there is no cancel and no way
 * to put it away — the ways out are a scan that produces a result, or leaving
 * the screen.
 *
 * A scan does put the panel away: a live camera after the key is captured is not
 * wanted, so the confirmation below is what the user is left looking at.
 * **Rescan** (shown once there is a result to throw away) is the way back — it
 * clears the last scan and its complaint and reopens the panel through the same
 * permission check as entry.
 *
 * A denied permission is the one state with no scanner at all: the screen says
 * what is missing and offers "Try again", which is the only scanner control it
 * renders besides Rescan.
 *
 * The name validates inline and the save is refused until the name is set and a
 * key has been scanned and parsed.
 */
@Composable
fun ImportXpubScreen(
    state: ImportXpubScreenState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // What to open once CAMERA is in hand. Both ways the panel comes up — entry
    // into the screen and Rescan — go through this one system request, and the
    // launcher's answer continues with whichever action was asked for: open the
    // panel on a clean slate, or open it on the rescan's cleared one.
    var pendingOpen by remember { mutableStateOf<(() -> Unit)?>(null) }

    // CAMERA is asked for by this app, not by the scanner library: the panel is
    // our own composable and it can only work with the permission in hand. The
    // answer completes whatever request was in flight — requestCamera always
    // arms one before it launches — and a refusal is the one thing the screen has
    // to report.
    val scanPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val open = pendingOpen
        pendingOpen = null
        if (granted) open?.invoke() else state.onCameraPermissionDenied()
    }

    // The one way the panel is opened: with the permission already held the
    // action runs at once (no pointless dialog); otherwise the request goes out
    // and its answer completes the same action. "Try again" and Rescan both
    // call this, so the screen has exactly one permission path.
    val requestCamera: (() -> Unit) -> Unit = { action ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingOpen = action
            scanPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // The screen's entry point *is* the scanner: opening the screen opens the
    // panel (or fires the one permission request it needs), exactly as the send
    // flow does. Keyed on Unit, so it happens once per visit rather than on
    // every recomposition — a re-entry into the screen asks again, which is what
    // a user who left and came back expects.
    LaunchedEffect(Unit) { requestCamera { state.onCameraPermissionGranted() } }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("\u2190 Back") }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Add watch-only xpub",
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Scan the account xpub QR from your signer or coordinator. " +
                    "Only public keys belong on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = state.name,
                onValueChange = state::onNameChange,
                label = { Text("wallet name") },
                placeholder = { Text("Cold storage") },
                singleLine = true,
                isError = state.nameError != null,
                supportingText = state.nameError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (state.cameraDenied) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Camera permission is required \u2014 wallets are imported by " +
                        "scanning the signer's QR.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { requestCamera { state.onCameraPermissionGranted() } }) {
                    Text("Try again")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // The scanner is a panel inside this column, portrait, scrolled with
            // the rest of the screen — and it is the screen's entry point: the
            // LaunchedEffect above opened it as soon as the screen appeared.
            // `showCancel = false` says there is no way out of it except the scan
            // itself, and a scan is what takes it down: a live camera is wanted
            // only while the QR is being read, and Rescan below is the way back.
            if (state.showScanner) {
                XpubQrScannerPanel(
                    onResult = { payload ->
                        state.onScanResult(payload)
                        state.onScannerDismissed()
                    },
                    // Never invoked: this panel draws no cancel (showCancel is
                    // false).
                    onCancel = {},
                    showCancel = false,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            state.scanError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            state.scannedXpub?.let { xpub ->
                ScannedKeyConfirmation(
                    xpub = xpub,
                    version = state.recognisedVersion,
                    masterFingerprint = state.masterFingerprint,
                    derivationPath = state.derivationPath,
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            // Once a scan has produced something — a key to keep, or a reason it
            // could not be read — the panel is gone and this is the way back to
            // it. It clears whatever the last scan left behind first, so the
            // confirmation below can only ever describe the key that is there
            // now. Tonal rather than the vibrant filled style of "Add wallet"
            // beside it: it is the secondary action, and the two must not read as
            // competing.
            if (state.scannedXpub != null || state.scanError != null) {
                FilledTonalButton(
                    onClick = { requestCamera { state.onRescanRequested() } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Rescan")
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            Button(
                onClick = state::import,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add wallet")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * What was just scanned, echoed back before it is saved: the key in monospace,
 * the version line the screen has always shown, and — when the QR was a BIP-32
 * key expression — the master fingerprint and derivation path that came with it.
 *
 * The key is middle-elided rather than shown whole: a 111-character zpub would
 * dominate the screen, and the point of this block is confirmation that *a* key
 * was read, not a second place to copy it from. When the QR carried no metadata,
 * a quiet line says so — it is not an error, but it is worth knowing before the
 * signing flow asks for a richer QR later.
 */
@Composable
private fun ScannedKeyConfirmation(
    xpub: String,
    version: String?,
    masterFingerprint: String?,
    derivationPath: String?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = elideMiddle(xpub),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )

            version?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Recognised $it \u2014 treated as native segwit (bc1q\u2026 addresses).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            masterFingerprint?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Master fingerprint: $it",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            derivationPath?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Derivation: m/$it",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            if (masterFingerprint == null && derivationPath == null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "This QR carries no fingerprint or derivation path \u2014 fine for " +
                        "watching; the signing flow may ask for a richer QR later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Middle-elides a long key: `zpub6rFR7y4Q…dBHyPtJQ5`. */
private fun elideMiddle(key: String, head: Int = 12, tail: Int = 12): String =
    if (key.length <= head + tail + 1) key else key.take(head) + "\u2026" + key.takeLast(tail)
