package com.xbtwallet.app.ui.hotwallet

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.data.HotWalletProvisioner
import com.xbtwallet.app.data.ProvisionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The screen's title, in the header, on the create walk. */
internal const val HOT_WALLET_FINISH_TITLE = "Finish"

/**
 * The screen's title on the **import** walk.
 *
 * The same screen, reached by the other way into a hot wallet: the import walk
 * has no recovery-words step (the words are already the user's), so this is its
 * last step and says so.
 */
internal const val HOT_WALLET_FINISH_IMPORT_TITLE = "Import \u2014 final step"

/** The wallet-name field's label. */
internal const val HOT_WALLET_FINISH_NAME_LABEL = "Wallet name"

/** What the name field starts as, and what a blank name falls back to. */
internal const val HOT_WALLET_FINISH_NAME_DEFAULT = "Hot wallet"

/** The passphrase field's label. */
internal const val HOT_WALLET_FINISH_PASSPHRASE_LABEL = "Passphrase (optional)"

/** The repeat-passphrase field's label. */
internal const val HOT_WALLET_FINISH_PASSPHRASE_REPEAT_LABEL = "Repeat passphrase"

/**
 * The one paragraph explaining what a passphrase is, that it is not stored, and
 * what forgetting it costs.
 */
internal const val HOT_WALLET_FINISH_PASSPHRASE_EXPLAINER =
    "Optional: a passphrase adds a second secret on top of your words. It is " +
        "never saved \u2014 you will enter it each time you spend. If you forget " +
        "it, no one can recover your coins. Leave empty to skip."

/** Shown under the passphrase fields while the two do not agree. */
internal const val HOT_WALLET_PASSPHRASE_MISMATCH = "Passphrases don't match"

/** The terminal button. */
internal const val HOT_WALLET_FINISH_CREATE = "Create wallet"

/** What that button says once a first attempt has failed. */
internal const val HOT_WALLET_FINISH_RETRY = "Retry"

/** The quiet line shown when provisioning threw. */
internal const val HOT_WALLET_FINISH_ERROR = "Couldn't create the wallet. Try again."

/**
 * Shown when the phrase is a wallet this phone already holds.
 *
 * A duplicate is not a failure — the store refuses a second record for one
 * account (see [com.xbtwallet.app.data.HotWalletProvisioner]) — so this is the
 * sentence that explains the refusal, and the button beside it does the only
 * thing left to do: leave the walk.
 */
internal const val HOT_WALLET_FINISH_DUPLICATE = "This wallet is already imported"

/** The action beside [HOT_WALLET_FINISH_DUPLICATE]: out of the walk, to the list. */
internal const val HOT_WALLET_FINISH_HOME = "Go to Home"

/**
 * The header title for a walk: `Finish` when the wallet is being created,
 * `Import — final step` when it is being imported.
 */
internal fun finishTitle(isImport: Boolean): String =
    if (isImport) HOT_WALLET_FINISH_IMPORT_TITLE else HOT_WALLET_FINISH_TITLE

/**
 * The terminal button's wording for a walk: `Create wallet` when creating,
 * `Import wallet` when importing. The import wording is the same phrase the
 * word-entry screen's button uses — it is the same action.
 */
internal fun finishSubmitLabel(isImport: Boolean): String =
    if (isImport) HOT_WALLET_IMPORT_WALLET_BUTTON else HOT_WALLET_FINISH_CREATE

/**
 * Whether the create button is tappable for a given passphrase pair.
 *
 * The rule, in full: **no passphrase at all** (`""`/`""`) is the ordinary case
 * and is enabled; **a repeated passphrase that matches** (`"x"`/`"x"`) is
 * enabled; **a repeat that differs** (`"x"`/`"y"`) is not; and **a passphrase
 * with no repeat yet** (`"x"`/`""`) is not either — that last one is not a
 * mismatch between two answers but an incomplete answer, and treating it as
 * "equal" would be the one way to save a wallet under a passphrase the user
 * never intended.
 *
 * A pure function, deliberately: it is the whole of the screen's validation
 * rule, and a JVM test pins the four cases without an activity in sight.
 */
internal fun createEnabled(passphrase: String, repeat: String): Boolean =
    repeat == passphrase

/**
 * **Finish** — the last step of either hot-wallet walk: name the wallet, add an
 * optional passphrase, and provision it.
 *
 * The words come in from the shared [HotWalletCreateFlow] (never off the route;
 * see that class) and go straight into [HotWalletProvisioner], which validates
 * them, derives the account, refuses an account the phone already holds, and
 * otherwise writes the entropy into the keystore-backed vault and only then
 * creates the signer record.
 *
 * **One screen, two walks.** [isImport] changes exactly two strings — the title
 * and the terminal button — because the work is the same either way: the words
 * are in the flow holder, and the rest is derivation and storage. The create walk
 * reaches this screen from the recovery-words screen; the import walk reaches it
 * straight from the words it typed.
 *
 * **A duplicate is reported, not retried.** When the phrase resolves to an
 * account this phone already holds, nothing was written and there is nothing to
 * retry: the screen says so ([HOT_WALLET_FINISH_DUPLICATE]) and its button turns
 * into the way out ([HOT_WALLET_FINISH_HOME]). The words are cleared on the way
 * out, exactly as they are after a successful provision.
 *
 * The passphrase is a *second* secret on top of the words, so the screen is
 * screenshot-proof for as long as it is composed: the activity window carries
 * [WindowManager.LayoutParams.FLAG_SECURE] from entry to exit rather than from
 * a reveal (there is no reveal here — the passphrase is typed in, not read out).
 *
 * Provisioning runs off the main thread, on [Dispatchers.Default] — it is
 * PBKDF2 plus a few BIP-32 derivations, and none of that belongs on the frame
 * thread. While it runs the pinned button carries a spinner and is disabled, the
 * pattern the rest of the app uses.
 *
 * On success the flow is wiped and [onCreated] lands the user on Home, where the
 * new wallet is in the list. On failure the flow is **not** wiped: the words are
 * still only in memory, and a retry should not send the user back to the dice or
 * to the word field.
 *
 * @param mnemonic the recovery words, in order. Never empty: the navigation
 *   guards ahead of this screen restart at Home when there is no mnemonic.
 * @param flow the shared holder, wiped on success and on the way out of a
 *   duplicate.
 * @param provisioner the app's one provisioner, built in `XbtWalletApp`.
 * @param isImport which walk reached this screen. Only the wording changes.
 * @param onCreated called once the wallet exists, or once the user accepts a
 *   duplicate — the caller refreshes Home and resets the stack.
 * @param onBack the header's back affordance, one level up the walk.
 */
@Composable
fun HotWalletFinishScreen(
    mnemonic: List<String>,
    flow: HotWalletCreateFlow,
    provisioner: HotWalletProvisioner,
    isImport: Boolean = false,
    onCreated: () -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(HOT_WALLET_FINISH_NAME_DEFAULT) }
    var passphrase by remember { mutableStateOf("") }
    var repeatPassphrase by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var duplicate by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // FLAG_SECURE for as long as this screen is composed: a passphrase is being
    // typed into it, and the words it is about to be paired with are one screen
    // back. Cleared on the way out so the rest of the app is untouched.
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    val canSubmit = createEnabled(passphrase, repeatPassphrase) && !creating
    val submitLabel = when {
        creating -> finishSubmitLabel(isImport)
        duplicate -> HOT_WALLET_FINISH_HOME
        error == null -> finishSubmitLabel(isImport)
        else -> HOT_WALLET_FINISH_RETRY
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Button(
                    onClick = {
                        // A duplicate has nothing left to do here: the wallet is
                        // already in the list, so the button is the way out. The
                        // words go with it — they have been matched against a
                        // wallet the phone holds, and there is no reason to keep
                        // them in memory for the rest of the session.
                        if (duplicate) {
                            flow.clear()
                            onCreated()
                            return@Button
                        }
                        // Trimmed on save, with the default standing in for a
                        // name the user emptied out.
                        val walletName = name.trim().ifEmpty { HOT_WALLET_FINISH_NAME_DEFAULT }
                        creating = true
                        error = null
                        scope.launch {
                            try {
                                // Hand the passphrase to the flow for the length
                                // of the attempt, so the success path wipes it
                                // with everything else.
                                flow.setPassphrase(passphrase)
                                val result = withContext(Dispatchers.Default) {
                                    provisioner.provision(walletName, mnemonic, passphrase)
                                }
                                when (result) {
                                    // The entropy is in the vault and the record
                                    // exists. Wipe the words, then land on Home
                                    // with the new wallet in the list.
                                    is ProvisionResult.Created -> {
                                        flow.clear()
                                        onCreated()
                                    }
                                    // Nothing was written: this account is already
                                    // this phone's. Say so, and let the button
                                    // become the way out.
                                    is ProvisionResult.AlreadyExists -> duplicate = true
                                }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: Exception) {
                                // Quiet, one line, and the flow keeps its words:
                                // the Retry below runs the same attempt again
                                // rather than sending the user back to the dice.
                                error = HOT_WALLET_FINISH_ERROR
                            } finally {
                                creating = false
                            }
                        }
                    },
                    enabled = duplicate || canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (creating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(submitLabel)
                    } else {
                        Text(submitLabel)
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            HotWalletHeader(title = finishTitle(isImport), onBack = onBack)

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(HOT_WALLET_FINISH_NAME_LABEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = HOT_WALLET_FINISH_PASSPHRASE_EXPLAINER,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(HOT_WALLET_FINISH_PASSPHRASE_LABEL) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = repeatPassphrase,
                onValueChange = { repeatPassphrase = it },
                label = { Text(HOT_WALLET_FINISH_PASSPHRASE_REPEAT_LABEL) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            if (!createEnabled(passphrase, repeatPassphrase)) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = HOT_WALLET_PASSPHRASE_MISMATCH,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // A duplicate explains itself where the passphrase fields are, since
            // the button below has already become the way out of the walk.
            if (duplicate) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = HOT_WALLET_FINISH_DUPLICATE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Read once into a val: a smart cast is not available on the
            // delegated `error` state, and this is what it is showing.
            val errorText = error
            if (errorText != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
