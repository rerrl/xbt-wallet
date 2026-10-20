package com.xbtwallet.app.ui.signers

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xbtwallet.app.crypto.UrAccountAssembler
import com.xbtwallet.app.crypto.UrScanOutcome
import com.xbtwallet.app.crypto.XpubImport
import com.xbtwallet.app.crypto.validateXpubInput
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerStore
import com.xbtwallet.app.ui.theme.XbtWalletTheme

/**
 * State behind the **add signing device** screen: the signer's name, the account
 * decoded out of the last animated export (plus its fingerprint and path), and
 * the save action.
 *
 * This is [ImportXpubScreenState]'s sibling, and deliberately so — the screen,
 * the fields and the save are the same; only *what a scan is* differs. Here a
 * scan is not one QR code but an assembly of fountain-coded frames
 * ([UrAccountAssembler]), so the state machine has one more axis the text path
 * does not have: a scan in progress.
 *
 * Plain Kotlin, no Compose UI, so the whole thing is exercised on the JVM by
 * feeding it frames — including out-of-order and interleaved ones, which is the
 * point of a fountain code and the thing a UI test could never pin down.
 *
 * Like the text path, a scan that ends badly **clears** everything it would have
 * populated before it records its message: a stale earlier key must never look
 * like the one that was just scanned.
 */
@Stable
class AddSigningDeviceScreenState(
    private val signerStore: SignerStore,
    private val onSaved: (Signer) -> Unit,
) {

    var name by mutableStateOf("")
        private set

    var nameError by mutableStateOf<String?>(null)
        private set

    /** The key text of the last completed export, null until one decodes. */
    var scannedXpub by mutableStateOf<String?>(null)
        private set

    /** Master fingerprint the export carried, when it carried one. */
    var masterFingerprint by mutableStateOf<String?>(null)
        private set

    /** Derivation path (no leading `m/`) the export carried, when it carried one. */
    var derivationPath by mutableStateOf<String?>(null)
        private set

    /** Set to "zpub" once a decoded account parses, null otherwise. */
    var recognisedVersion by mutableStateOf<String?>(null)
        private set

    /** Non-fatal remarks about the last export — a missing fingerprint or path. */
    var warnings by mutableStateOf<List<String>>(emptyList())
        private set

    /** The message for the most recent bad scan, null otherwise. */
    var scanError by mutableStateOf<String?>(null)
        private set

    /** How much of the animated export the fountain decoder has, 0–100. */
    var scanProgress by mutableStateOf(0)
        private set

    /** Whether the embedded animated-QR panel is on screen. */
    var showScanner by mutableStateOf(false)
        private set

    /** Whether the user refused the CAMERA permission. */
    var cameraDenied by mutableStateOf(false)
        private set

    /** The parsed key from the last completed export, or null. */
    private var scannedKey: XpubImport.Valid? = null

    /** The in-flight assembly. One export at a time; reset when a scan ends. */
    private val assembler = UrAccountAssembler()

    fun onNameChange(value: String) {
        name = value
        if (nameError != null && value.isNotBlank()) nameError = null
    }

    /** The CAMERA permission is held; opens the embedded panel on a clean slate. */
    fun onCameraPermissionGranted() {
        cameraDenied = false
        // A fresh panel means a fresh assembly: a half-finished export from a
        // previous attempt must not be spliced onto this one.
        assembler.reset()
        scanProgress = 0
        showScanner = true
    }

    /** The user refused the camera. The panel never opens. */
    fun onCameraPermissionDenied() {
        cameraDenied = true
    }

    /**
     * Dismisses the panel (Cancel). Whatever the last completed export produced
     * is left exactly as it was — cancelling a scan is not the same as scanning
     * a bad one — and the half-assembled frames are thrown away.
     */
    fun onScannerDismissed() {
        showScanner = false
        assembler.reset()
        scanProgress = 0
    }

    /**
     * The animation stalled: no new frame for a minute.
     *
     * Closes the panel and says so. The screen is left fully usable — the scan
     * button is right there again — which is the point of timing out rather than
     * spinning forever.
     */
    fun onScanTimedOut() {
        showScanner = false
        assembler.reset()
        scanProgress = 0
        scanError = SCAN_TIMED_OUT_MESSAGE
    }

    /**
     * Offers one scanned QR frame to the assembly.
     *
     * Three things can come back. **Progress** is the normal case for an animated
     * export and just moves the percentage. **Complete** fills the scanned fields
     * and closes the panel. **Rejected** means this QR cannot be part of a
     * signing-device export — the wrong code entirely, or a UR of the wrong type
     * — and is reported in the user's terms ("this is not a signing-device export
     * QR", "that's a transaction QR"), never as a Base58 complaint about a key
     * that was never there.
     */
    fun onScanFrame(payload: String) {
        when (val outcome = assembler.receive(payload)) {
            is UrScanOutcome.Progress -> scanProgress = outcome.percent

            is UrScanOutcome.Complete -> {
                applyAccount(outcome)
                finishScan()
            }

            is UrScanOutcome.Rejected -> {
                clearScanned()
                scanError = outcome.message
                finishScan()
            }
        }
    }

    /** Validates and, when everything checks out, stores a new watch-only signer. */
    fun import() {
        nameError = if (name.isBlank()) "Give this signer a name." else null

        val key = scannedKey
        if (key == null) {
            // Do not overwrite a specific scan message with the generic "scan
            // something" one — the reason the last scan failed is more useful.
            if (scanError == null) {
                scanError = "Scan the signing device's export QR before adding it."
            }
            return
        }
        if (nameError != null) return

        val xpub = scannedXpub ?: return
        // The metadata is stored as decoded; `key` proves the xpub parsed. The
        // record is the same one the watch-only path writes — same store, same
        // signer type — so a signer added either way is indistinguishable later.
        onSaved(
            signerStore.add(
                name = name,
                xpub = xpub,
                masterFingerprint = masterFingerprint,
                derivationPath = derivationPath,
            ),
        )
    }

    /** A completed export: the shared validator is still the gate before anything is kept. */
    private fun applyAccount(outcome: UrScanOutcome.Complete) {
        val account = outcome.account
        when (val validation = validateXpubInput(account.xpub)) {
            is XpubImport.Valid -> {
                scannedKey = validation
                scannedXpub = account.xpub
                recognisedVersion = validation.versionLabel
                masterFingerprint = account.masterFingerprint
                derivationPath = account.derivationPath
                warnings = account.warnings
                scanError = null
            }

            is XpubImport.Invalid -> {
                clearScanned()
                scanError = validation.message
            }
        }
    }

    /** Closes the panel and drops the assembly, whatever the outcome was. */
    private fun finishScan() {
        showScanner = false
        assembler.reset()
        scanProgress = 0
    }

    private fun clearScanned() {
        scannedKey = null
        scannedXpub = null
        recognisedVersion = null
        masterFingerprint = null
        derivationPath = null
        warnings = emptyList()
    }

    companion object {
        /** Shown when the animation stops producing frames. */
        const val SCAN_TIMED_OUT_MESSAGE =
            "The scan timed out before the export finished \u2014 tap Scan QR and keep " +
                "the signer's animated QR in view."
    }
}

/**
 * Remembers an [AddSigningDeviceScreenState].
 *
 * Same shape as [rememberImportXpubScreenState], and for the same reason: keying
 * the state on the store alone means a fresh `onSaved` lambda on every
 * composition cannot recreate it and wipe what the user has entered.
 */
@Composable
fun rememberAddSigningDeviceScreenState(
    signerStore: SignerStore,
    onSaved: (Signer) -> Unit,
): AddSigningDeviceScreenState {
    val currentOnSaved by rememberUpdatedState(onSaved)
    return remember(signerStore) {
        AddSigningDeviceScreenState(signerStore) { signer -> currentOnSaved(signer) }
    }
}

/**
 * Add a signer by scanning its **signing device's own export** — the sibling of
 * [ImportXpubScreen], reached from [AddSignerScreen]'s **Add signing device**
 * button.
 *
 * The two screens are near-identical on purpose: a name, a scan, a save, and the
 * same signer record at the end. What differs is the QR they expect. The
 * watch-only path takes text (a bare xpub, a `bitcoin:` URI, a `[fp/path]xpub`
 * key expression); this one takes the BC-UR `crypto-account` payload a
 * SeedSigner, Keystone or Jade shows when it exports an account — usually as an
 * animation of fountain-coded frames, which is why this screen's scanner keeps
 * decoding until the export is whole and shows how much of it has arrived.
 *
 * Pointing the wrong screen at the wrong code is expected — the two buttons sit
 * next to each other — so each says plainly what it did not get, rather than
 * complaining about Base58 for a payload that was never Base58 in the first
 * place.
 *
 * The name validates inline and the save is refused until the name is set and an
 * export has decoded.
 */
@Composable
fun AddSigningDeviceScreen(
    state: AddSigningDeviceScreenState,
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
    // held, open the panel at once; otherwise ask for it.
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
                    text = "Add signing device",
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Scan the animated export QR your signer shows \u2014 SeedSigner, " +
                    "Keystone or Jade. The phone reads the account's public key, its " +
                    "master fingerprint and its derivation path.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = state.name,
                onValueChange = state::onNameChange,
                label = { Text("signer name") },
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
                    text = "Camera permission is required \u2014 signers are imported by " +
                        "scanning the signer's QR.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = pendingScanRequest) { Text("Try again") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // The scanner is a panel inside this column, portrait. It decodes
            // continuously: the export is a fountain-coded animation, so every
            // frame is offered to the assembly and the panel stays up until the
            // state says the export is whole (or the animation stalls).
            if (state.showScanner) {
                UrQrScannerPanel(
                    progress = state.scanProgress,
                    onFrame = state::onScanFrame,
                    onCancel = state::onScannerDismissed,
                    onTimeout = state::onScanTimedOut,
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
                DecodedAccountConfirmation(
                    xpub = xpub,
                    version = state.recognisedVersion,
                    masterFingerprint = state.masterFingerprint,
                    derivationPath = state.derivationPath,
                    warnings = state.warnings,
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            Button(
                onClick = state::import,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add signer")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * What the export decoded to, echoed back before it is saved: the account key in
 * monospace, the version the app will treat it as, and the fingerprint and
 * derivation path that came out of the UR.
 *
 * The key is middle-elided — a 111-character zpub would dominate the screen, and
 * the point here is confirmation that *an* account was read, not a second place
 * to copy it from. Anything the export left out (or that could not be trusted)
 * is listed underneath as a warning rather than silently dropped: it is not an
 * error, but it is worth knowing before the signing flow asks for a richer QR.
 */
@Composable
private fun DecodedAccountConfirmation(
    xpub: String,
    version: String?,
    masterFingerprint: String?,
    derivationPath: String?,
    warnings: List<String>,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = shortenedMiddle(xpub, head = 12, tail = 12),
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

            warnings.forEach { warning ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun AddSigningDeviceScreenPreview() {
    XbtWalletTheme {
        AddSigningDeviceScreen(
            state = AddSigningDeviceScreenState(SignerStore(NoopKeyValueStore)) {},
            onBack = {},
        )
    }
}

/** A store that keeps nothing, for previews only. */
private object NoopKeyValueStore : com.xbtwallet.app.data.KeyValueStore {
    override fun getString(key: String): String? = null
    override fun putString(key: String, value: String) = Unit
}
