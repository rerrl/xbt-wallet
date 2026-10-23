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
import com.xbtwallet.app.data.SignerOrigin
import com.xbtwallet.app.data.SignerStore
import com.xbtwallet.app.ui.theme.XbtSuccessDark
import com.xbtwallet.app.ui.theme.XbtWalletTheme
import com.xbtwallet.app.ui.theme.XbtWarningDark

/**
 * State behind the **add signing device** screen: the wallet's name, the account
 * decoded out of the last animated export (plus its fingerprint and path), and
 * the save action.
 *
 * This is [ImportXpubScreenState]'s sibling, and deliberately so — the screen,
 * the fields and the save are the same; only *what a scan is* differs. Here a
 * scan is not one QR code but an assembly of fountain-coded frames
 * ([UrAccountAssembler]), so the state machine has one more axis the text path
 * does not have: a scan in progress.
 *
 * [origin] is the chooser row that opened this screen — `SEEDSIGNER_UR` for the
 * SeedSigner row, `SIGNING_DEVICE_UR` for the other-device row. Both rows read
 * the very same QR, so the *payload* cannot tell them apart; the user's tap is
 * the only thing that can, which is why it is passed in and recorded rather than
 * guessed at scan time. It changes nothing about the key: the same xpub,
 * fingerprint and path are stored either way, and only the tag the UI shows
 * reads it.
 *
 * Plain Kotlin, no Compose UI, so the whole thing is exercised on the JVM by
 * feeding it frames — including out-of-order and interleaved ones, which is the
 * point of a fountain code and the thing a UI test could never pin down.
 *
 * Like the text path, a scan that ends badly **clears** everything it would have
 * populated before it records its message: a stale earlier key must never look
 * like the one that was just scanned.
 *
 * The panel is the screen's entry point, not something a button opens: the
 * screen asks for the camera once on entry, and the panel comes down as soon as
 * a scan has ended — a whole export, a refusal, or a stall — because a live
 * camera after the answer is not wanted. **Rescan** is the way back: it clears
 * the last export's confirmation (and its complaint, if it had one), resets the
 * assembly and brings the panel up again. A cancelled scan is not a state this
 * screen has: nothing here takes the panel down except a scan that ended.
 */
@Stable
class AddSigningDeviceScreenState(
    private val signerStore: SignerStore,
    private val origin: SignerOrigin,
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
     * **Rescan** — the user is not keeping what the last scan produced (or is
     * having another go at an export that refused or timed out).
     *
     * Everything the last scan left behind goes: the decoded account and its
     * metadata, any complaint about it, and the half-assembled frames. The panel
     * comes back up on that empty slate, so the summary below can only ever
     * describe the export that is in front of the camera now.
     *
     * The caller checks the CAMERA permission before this, exactly as it does on
     * entry: [onRescanRequested] is only ever reached with the permission in
     * hand, which is also why it clears any earlier denial.
     */
    fun onRescanRequested() {
        clearScanned()
        scanError = null
        cameraDenied = false
        assembler.reset()
        scanProgress = 0
        showScanner = true
    }

    /**
     * The animation stalled: no new frame for a minute.
     *
     * Closes the panel and says so. The screen is left fully usable — Rescan
     * opens a clean panel again — which is the point of timing out rather than
     * spinning forever at a signer the camera cannot see.
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

    /** Validates and, when everything checks out, stores a new watch-only wallet. */
    fun import() {
        nameError = if (name.isBlank()) "Give this wallet a name." else null

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
        // signer type — so a wallet added either way is indistinguishable in the
        // data. What *does* differ is the origin: this key came off a signing
        // device over BC-UR, and the chooser row the user tapped said *which*
        // kind of device it was. Recording that declared origin is what earns the
        // account its tag — orange "seedsigner" for the SeedSigner row, the blue
        // "signing device" for any other device.
        onSaved(
            signerStore.add(
                name = name,
                xpub = xpub,
                masterFingerprint = masterFingerprint,
                derivationPath = derivationPath,
                importedVia = origin,
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
            "The scan timed out before the export finished \u2014 tap Rescan and keep " +
                "the signer's animated QR in view."
    }
}

/**
 * Remembers an [AddSigningDeviceScreenState].
 *
 * Same shape as [rememberImportXpubScreenState], and for the same reason: keying
 * the state on the store (and the declared [origin]) alone means a fresh
 * `onSaved` lambda on every composition cannot recreate it and wipe what the user
 * has entered. The origin is a key as well because it is what the saved record
 * will carry — a state object that survived a change of origin would stamp the
 * wrong one.
 */
@Composable
fun rememberAddSigningDeviceScreenState(
    signerStore: SignerStore,
    origin: SignerOrigin,
    onSaved: (Signer) -> Unit,
): AddSigningDeviceScreenState {
    val currentOnSaved by rememberUpdatedState(onSaved)
    return remember(signerStore, origin) {
        AddSigningDeviceScreenState(signerStore, origin) { signer -> currentOnSaved(signer) }
    }
}

/**
 * Add a wallet by scanning its **signing device's own export** — the sibling of
 * [ImportXpubScreen], reached from [SigningDeviceChoiceScreen], the two options
 * behind [AddSignerScreen]'s external-signer card.
 *
 * The two screens are near-identical on purpose: a name, a scan, a save, and the
 * same signer record at the end. What differs is the QR they expect. The
 * watch-only path takes text (a bare xpub, a `bitcoin:` URI, a `[fp/path]xpub`
 * key expression); this one takes the BC-UR `crypto-account` payload a
 * SeedSigner, Keystone or Jade shows when it exports an account — usually as an
 * animation of fountain-coded frames, which is why this screen's scanner keeps
 * decoding until the export is whole and shows how much of it has arrived.
 *
 * Pointing the wrong screen at the wrong code is expected — the rows sit next to
 * each other — so each says plainly what it did not get, rather than complaining
 * about Base58 for a payload that was never Base58 in the first place.
 *
 * **The scanner is up from the moment the screen opens, and it is the only way
 * in.** Entering the screen asks for the camera once ([LaunchedEffect] below):
 * already-granted opens the panel immediately, otherwise the one system
 * permission dialog runs. There is no button to press first, and while the panel
 * is up there is no cancel and no way to put it away either — the ways out are a
 * scan that ends (a completed export, a refusal, or a stall) or leaving the
 * screen.
 *
 * An ended scan puts the panel away, and **Rescan** is the way back: it clears
 * the last export's confirmation and complaint, resets the assembly and reopens
 * the panel through the same permission check as entry.
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

    // What to open once CAMERA is in hand. Both ways the panel comes up — entry
    // into the screen and Rescan — go through this one system request, and the
    // launcher's answer continues with whichever action was asked for.
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
    // and its answer completes the same action. "Try again" and Rescan both call
    // this.
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
    // panel (or fires the one permission request it needs). Keyed on Unit, so it
    // happens once per visit rather than on every recomposition.
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

            // The scanner is a panel inside this column, portrait. It decodes
            // continuously: the export is a fountain-coded animation, so every
            // frame is offered to the assembly and the panel stays up until the
            // state says the export is whole (or it refuses the code, or the
            // animation stalls). `showCancel = false`: there is nothing to cancel
            // here — the panel is the only way in, and Rescan is the way back to
            // it once a scan has ended.
            if (state.showScanner) {
                UrQrScannerPanel(
                    progress = state.scanProgress,
                    onFrame = state::onScanFrame,
                    // Never invoked: this panel draws no cancel (showCancel is
                    // false).
                    onCancel = {},
                    onTimeout = state::onScanTimedOut,
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
                DecodedAccountConfirmation(
                    xpub = xpub,
                    version = state.recognisedVersion,
                    masterFingerprint = state.masterFingerprint,
                    derivationPath = state.derivationPath,
                    warnings = state.warnings,
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            // Once a scan has ended — a decoded account, a refusal, or a stall —
            // the panel is gone and this is the way back to it. It clears the
            // last export's confirmation and complaint and resets the assembly
            // first, so nothing on screen can belong to an export the user just
            // threw away. Tonal rather than the vibrant filled style of "Add
            // wallet" beside it: it is the secondary action, and the two must not
            // read as competing.
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
 * What the export decoded to, shown as a *success* summary before it is saved:
 * a green title saying the account came off the signing device, then the facts
 * the export carried as labelled monospace rows — the master fingerprint, the
 * derivation path and the account key.
 *
 * This panel is the reward for a scan that worked, so nothing here wears the
 * error palette: the accent is the app's own success green and the rows sit on a
 * neutral surface. It used to echo the key first in the primary orange, which is
 * close enough to amber that a user read a successful scan as a complaint.
 *
 * The key is middle-elided — a 111-character zpub would dominate the screen, and
 * the point here is confirmation that *an* account was read, not a second place
 * to copy it from. Anything the export left out (a missing fingerprint or
 * derivation path) is listed underneath in the warning colour: it did not stop
 * the import, but it is worth knowing before the signing flow asks for a richer
 * QR. That warning styling is the only non-success colour on this panel, and it
 * appears only when there is something to warn about.
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
                text = SCANNED_SUMMARY_TITLE,
                style = MaterialTheme.typography.titleSmall,
                color = XbtSuccessDark,
            )

            version?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Recognised $it \u2014 treated as native segwit (bc1q\u2026 addresses).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            scannedAccountRows(
                xpub = xpub,
                masterFingerprint = masterFingerprint,
                derivationPath = derivationPath,
            ).forEach { row ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = row.value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            warnings.forEachIndexed { index, warning ->
                Spacer(modifier = Modifier.height(if (index == 0) 10.dp else 4.dp))
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = XbtWarningDark,
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
            state = AddSigningDeviceScreenState(
                SignerStore(NoopKeyValueStore),
                SignerOrigin.SEEDSIGNER_UR,
            ) {},
            onBack = {},
        )
    }
}

/** A store that keeps nothing, for previews only. */
private object NoopKeyValueStore : com.xbtwallet.app.data.KeyValueStore {
    override fun getString(key: String): String? = null
    override fun putString(key: String, value: String) = Unit
}
