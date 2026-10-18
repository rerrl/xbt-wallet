package com.xbtwallet.app.ui.signers

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
import com.xbtwallet.app.crypto.XpubImport
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
 * Add a signer by pasting its account xpub — the manual half of the Add-signer
 * flow, reached from [AddSignerScreen]'s **Add watch-only xpub** button.
 *
 * This is the fallback path from PLAN.md §4. The primary path is scanning a
 * SeedSigner `crypto-account` QR export, which is a later feature — a code note
 * for whoever builds it: swapping in a scanner means feeding the decoded string
 * through this same state object, because the validation already lives behind
 * [com.xbtwallet.app.crypto.validateXpubInput] and never touches a camera.
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
