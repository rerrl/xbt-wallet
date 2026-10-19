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
import com.xbtwallet.app.data.SignerStore

/**
 * State behind the watch-only xpub import screen: two text fields, their inline
 * errors, and the save action.
 *
 * Kept out of the composable for two reasons. First, it is plain Kotlin and can
 * be exercised without a UI test. Second, the screen is re-composed constantly
 * while the user types; holding the field values and the "already attempted a
 * save" flag here means the validation behaviour is a property of the state
 * object, not of how many times Compose happened to re-run the composable.
 */
@Stable
class ImportXpubScreenState(
    private val signerStore: SignerStore,
    private val onSaved: (Signer) -> Unit,
) {

    var name by mutableStateOf("")
        private set

    var xpub by mutableStateOf("")
        private set

    var nameError by mutableStateOf<String?>(null)
        private set

    var xpubError by mutableStateOf<String?>(null)
        private set

    /** Set to "zpub"/"xpub"/"ypub" once the field parses, null otherwise. */
    var recognisedVersion by mutableStateOf<String?>(null)
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
     * Kept so the screen can say why the button did nothing and offer a retry,
     * without ever taking the paste path away: a wallet must be importable on a
     * device whose camera is denied or absent.
     */
    var cameraDenied by mutableStateOf(false)
        private set

    /** The parsed key, or null while the field is empty or invalid. */
    private var parsedKey: XpubImport.Valid? = null

    /**
     * Inline errors only appear after the first save attempt — flagging every
     * half-typed key on the way to a pasted one would be noise.
     */
    private var submitAttempted = false

    fun onNameChange(value: String) {
        name = value
        if (nameError != null && value.isNotBlank()) nameError = null
    }

    fun onXpubChange(value: String) {
        xpub = value
        revalidate(showError = submitAttempted)
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

    /**
     * The user refused the camera. The panel never opens; the screen shows the
     * paste-is-still-available hint instead.
     */
    fun onCameraPermissionDenied() {
        cameraDenied = true
    }

    /**
     * Dismisses the panel (Cancel, or the caller hiding it after a scan).
     *
     * Nothing in the field is touched: a cancelled scan must leave whatever the
     * user had typed exactly as it was.
     */
    fun onScannerDismissed() {
        showScanner = false
    }

    /** Validates and, when everything checks out, stores a new watch-only signer. */
    fun import() {
        submitAttempted = true
        nameError = if (name.isBlank()) "Give this signer a name." else null
        revalidate(showError = true)

        val key = parsedKey
        if (nameError != null || key == null) return

        // The key is stored as entered (trimmed) rather than re-encoded: the
        // user should see back what they pasted, and every accepted version is
        // derived as native segwit anyway. `key` proves it was valid.
        onSaved(signerStore.add(name = name, xpub = xpub))
    }

    private fun revalidate(showError: Boolean) {
        val text = xpub.trim()
        if (text.isEmpty()) {
            parsedKey = null
            recognisedVersion = null
            xpubError = if (showError) "Paste the account extended public key (xpub, ypub or zpub)." else null
            return
        }

        when (val result = validateXpubInput(text)) {
            is XpubImport.Valid -> {
                parsedKey = result
                recognisedVersion = result.versionLabel
                xpubError = null
            }

            is XpubImport.Invalid -> {
                parsedKey = null
                recognisedVersion = null
                xpubError = if (showError) result.message else null
            }
        }
    }
}

/**
 * Remembers an [ImportXpubScreenState] for the import screen.
 *
 * The `onSaved` callback goes through [rememberUpdatedState] and the state
 * object is keyed only on `signerStore`: if the callback were a `remember` key,
 * a fresh lambda on every composition would recreate the state object and wipe
 * whatever the user had typed.
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
 * Add a signer by its account xpub — the half of the Add-signer flow reached
 * from [AddSignerScreen]'s **Add watch-only xpub** button.
 *
 * There are two ways in and they share one validator. The key can be **pasted**
 * into the field, or **scanned** from the signer's screen; a scan is routed by
 * [XpubScanRoute] and then lands in the same field through the same
 * [com.xbtwallet.app.crypto.validateXpubInput], so the camera can never accept
 * something the keyboard would reject. Pasting stays available at all times,
 * which is also what makes the wallet usable when camera permission is refused
 * or the device has no camera.
 *
 * Scanning is an embedded panel, not a screen of its own: [XpubQrScannerPanel]
 * opens inline in this column, in portrait, and leaves the field visible below
 * it. Nothing here launches the scanner library's full-screen activity.
 *
 * The fields validate inline (via the shared [validateXpubInput] seam, not a
 * copy of it) and the save is refused until the name is set and the key parses.
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
    // explain itself. The paste path below is untouched either way.
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
                text = "Paste the account-level extended public key from your signer. " +
                    "Only public keys belong on this phone.",
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

            // Scanning and pasting fill the same field, so the button sits right
            // on top of it rather than leading to a screen of its own — and the
            // scanner, when it opens, opens right here.
            OutlinedButton(
                onClick = pendingScanRequest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Scan QR")
            }

            if (state.cameraDenied) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Camera permission is needed to scan \u2014 you can still paste the key below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = pendingScanRequest) { Text("Try again") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // The scanner is a panel inside this column, directly above the field
            // it fills: portrait — nothing here locks orientation, so it inherits
            // the activity's — small, and scrolled with the rest of the screen. A
            // scan is routed by XpubScanRoute and lands in the field through the
            // same onXpubChange a paste uses, so the camera can never accept a key
            // the keyboard would refuse.
            if (state.showScanner) {
                XpubQrScannerPanel(
                    onResult = { payload ->
                        state.onXpubChange(XpubScanRoute.extract(payload))
                        state.onScannerDismissed()
                    },
                    onCancel = state::onScannerDismissed,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = state.xpub,
                onValueChange = state::onXpubChange,
                label = { Text("Account xpub / ypub / zpub") },
                placeholder = { Text("zpub6rFR7y4Q2\u2026") },
                minLines = 3,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                isError = state.xpubError != null,
                supportingText = state.xpubError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            state.recognisedVersion?.let { version ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Recognised $version \u2014 treated as native segwit (bc1q\u2026 addresses).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Manual import carries no master fingerprint or derivation path. " +
                        "All three of xpub, ypub and zpub hold the same public key, so this " +
                        "wallet derives native segwit P2WPKH (bc1q\u2026) addresses from it " +
                        "regardless of which you paste.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

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
