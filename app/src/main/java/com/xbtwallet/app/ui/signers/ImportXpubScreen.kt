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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
     * testable without a camera.
     */
    var showScanner by mutableStateOf(false)
        private set

    /**
     * Whether the user refused the CAMERA permission.
     *
     * Kept so the screen can say why the button did nothing and offer a retry.
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
     * Dismisses the panel (Cancel, or the caller hiding it after a scan).
     *
     * Whatever the last scan produced is left exactly as it was: cancelling a
     * scan is not the same as scanning a bad one.
     */
    fun onScannerDismissed() {
        showScanner = false
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
                scannedKey = null
                scannedXpub = null
                recognisedVersion = null
                masterFingerprint = null
                derivationPath = null
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
 * Add a wallet by scanning its account xpub — the row of the Add-wallet flow
 * reached from [AddSignerScreen]'s **Watch-only xpub** button.
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

    // CAMERA is asked for by this app, not by the scanner library: the panel is
    // our own composable and it can only work with the permission in hand. A
    // granted request opens the panel; a refusal is remembered so the screen can
    // explain itself.
    val scanPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) state.onCameraPermissionGranted() else state.onCameraPermissionDenied()
    }

    // "Scan QR" and "Try again" do the same thing: with the permission already
    // held, open the panel at once (no pointless dialog); otherwise ask for it.
    // The request is pending only for as long as the system dialog is up — its
    // answer arrives in the launcher callback above — so no flag has to outlive
    // it.
    val pendingScanRequest: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            state.onCameraPermissionGranted()
        } else {
            scanPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

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

            OutlinedButton(
                onClick = pendingScanRequest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Scan QR")
            }

            if (state.cameraDenied) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Camera permission is required \u2014 wallets are imported by " +
                        "scanning the signer's QR.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = pendingScanRequest) { Text("Try again") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // The scanner is a panel inside this column, portrait, scrolled with
            // the rest of the screen. A scan is routed by XpubScanRoute and
            // validated by the same seam a keyboard entry would have used, so the
            // camera can never accept a key typing would refuse.
            if (state.showScanner) {
                XpubQrScannerPanel(
                    onResult = { payload ->
                        state.onScanResult(payload)
                        state.onScannerDismissed()
                    },
                    onCancel = state::onScannerDismissed,
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
