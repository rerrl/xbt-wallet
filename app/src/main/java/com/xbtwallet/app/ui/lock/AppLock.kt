package com.xbtwallet.app.ui.lock

import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.xbtwallet.app.ui.theme.XbtPrimary

/** The lock gate's title, and the app's name under the lock. */
internal const val APP_LOCK_TITLE = "XBT Wallet"

/** The line under the name while the app is locked. */
internal const val APP_LOCK_SUBTITLE = "Simple, sovereign, for the people."

/** The gate's one control. */
internal const val APP_LOCK_UNLOCK_LABEL = "Unlock"

/** What the system prompt is titled. */
internal const val APP_LOCK_PROMPT_TITLE = "Unlock XBT Wallet"

/** The prompt's subtitle, which is also what the vault's own challenge shows. */
internal const val APP_LOCK_PROMPT_SUBTITLE = "Confirm it's you to open your wallet."

/** Shown under the button while a prompt is up. */
internal const val APP_LOCK_AUTHENTICATING = "Waiting for your confirmation\u2026"

/** The one-time warning when the device has no lock screen at all. */
internal const val APP_LOCK_NO_DEVICE_LOCK_TITLE = "App lock unavailable"

/**
 * The no-device-lock warning's body: why the app is not locked, and what that
 * costs.
 */
internal const val APP_LOCK_NO_DEVICE_LOCK_BODY =
    "This device has no screen lock, so XBT Wallet cannot lock itself or protect " +
        "a hot wallet's seed. Set a PIN, pattern or password in Android settings, " +
        "then reopen the app. Creating a hot wallet on this phone is disabled " +
        "until then."

/** The warning's dismiss control. */
internal const val APP_LOCK_WARNING_DISMISS = "I understand"

/**
 * The system authentication prompt, behind an interface.
 *
 * One method, because the lock gate needs exactly one thing from the platform:
 * show the prompt and say how it ended. Keeping it behind an interface is what
 * makes the gate's own rules — cold start is locked, a background re-locks, a
 * cancel leaves the gate up — testable on the JVM with a scripted fake, with no
 * activity and no biometric hardware.
 *
 * [cryptoObject] is passed through to the prompt when the caller has one. The
 * lock gate itself never does (it authenticates the device, not a key); the
 * vault's challenge does, when its key is crypto-bound.
 */
interface LockAuthenticator {
    /**
     * Shows the prompt.
     *
     * @param onSuccess the user authenticated.
     * @param onCancel the prompt was dismissed or could not run; the message is
     *   the platform's own explanation, or null when the user simply backed out.
     */
    fun authenticate(cryptoObject: Any?, onSuccess: () -> Unit, onCancel: (String?) -> Unit)
}

/**
 * The app lock's state machine — plain Kotlin, so its rules are a JVM test's.
 *
 * **Cold start is always locked.** When the device has a lock screen, the app
 * opens behind the gate: nothing renders until [unlock] succeeds, so no wallet
 * data is composed and there is nothing to flash.
 *
 * **Every backgrounding re-locks.** [onLeftForeground] is called when the app
 * leaves the foreground (the activity stops), and it locks again — there is no
 * grace period. Coming back to the app is coming back to the gate, always. The
 * caller drives this from the lifecycle; see `XbtWalletApp`.
 *
 * **No device lock means no app lock.** With no lock screen there is nothing to
 * authenticate against, so the app opens — and shows a one-time, dismissible
 * warning saying so ([warningVisible]). [hotWalletCreationBlocked] is true in
 * that state, and the add-wallet screen refuses to create a hot wallet (whose
 * seed would be bound to an authentication that cannot exist).
 *
 * @param deviceSecure whether the device has a lock screen. Read once, at
 *   construction: the answer cannot change while the app is running, and a
 *   prompt that changed it would be a lie.
 * @param authenticator the system prompt.
 * @param warningDismissed whether the user has already dismissed the
 *   no-device-lock warning in a previous session — persisted, so it is shown
 *   once and not on every launch.
 * @param onWarningDismissed called when the user dismisses it, so the caller can
 *   persist that fact.
 */
@Stable
class AppLockState(
    val deviceSecure: Boolean,
    private val authenticator: LockAuthenticator,
    warningDismissed: Boolean,
    private val onWarningDismissed: () -> Unit,
) {

    /**
     * Whether the gate is up. True at construction on a secure device — the
     * app's cold-start state — and true again after every backgrounding.
     */
    var locked by mutableStateOf(deviceSecure)
        private set

    /** True while the system prompt is showing. The button disables on it. */
    var authenticating by mutableStateOf(false)
        private set

    /** The platform's reason the last prompt did not succeed, or null. */
    var error by mutableStateOf<String?>(null)
        private set

    /** Whether the one-time no-device-lock warning is on screen. */
    var warningVisible by mutableStateOf(!deviceSecure && !warningDismissed)
        private set

    /**
     * Whether creating a hot wallet on this phone must be refused.
     *
     * True exactly when there is no lock screen: a hot wallet's seed is sealed
     * under a key bound to device authentication, and with no device lock there
     * is no authentication to bind to. The add-wallet screen reads this and says
     * so rather than failing later.
     */
    val hotWalletCreationBlocked: Boolean get() = !deviceSecure

    /**
     * Shows the prompt — the cold start's own ask, and what the `Unlock` button
     * re-invokes after a cancel.
     *
     * A no-op when the app is already unlocked, a prompt is already up, or there
     * is no device lock (in which case the app is never locked in the first
     * place). A successful prompt clears the gate; a dismissed one leaves it up
     * with the platform's message, so the button is the way back.
     */
    fun unlock() {
        if (!deviceSecure || locked.not() || authenticating) return
        authenticating = true
        error = null
        authenticator.authenticate(
            cryptoObject = null,
            onSuccess = {
                authenticating = false
                error = null
                locked = false
            },
            onCancel = { message ->
                authenticating = false
                error = message
            },
        )
    }

    /**
     * The app left the foreground: lock again.
     *
     * Called on every stop, with no timer and no exception — a glance at a
     * notification and a return is a return to the gate. A no-op on a device
     * with no lock screen, which is never locked.
     */
    fun onLeftForeground() {
        if (!deviceSecure) return
        locked = true
        authenticating = false
        error = null
    }

    /** Dismisses the no-device-lock warning and records that it has been seen. */
    fun dismissWarning() {
        if (!warningVisible) return
        warningVisible = false
        onWarningDismissed()
    }
}

/**
 * The gate itself: the locked screen, or [content], and never both.
 *
 * When [state] is locked the content is **not composed at all** — the gate is an
 * `if`, not an overlay — so a wallet balance, an address or a transaction row
 * cannot appear for a frame behind a prompt that is still up. That is the whole
 * point of gating at the root rather than on each screen.
 *
 * The cold start asks for the prompt once, so opening the app is one confirmation
 * rather than a tap and then a confirmation. The `Unlock` button is what asks
 * again after a cancel, and what the gate falls back to if the platform refused
 * to show a prompt at all.
 *
 * The no-device-lock warning is drawn over the app (which is *open* in that
 * case) and dismissed for good by the user.
 */
@Composable
fun AppLockGate(state: AppLockState, content: @Composable () -> Unit) {
    // One ask, at cold start. Keyed on Unit, so it fires once per process — a
    // later re-lock is the user's own doing and they get the button, not a
    // prompt they did not ask for.
    LaunchedEffect(Unit) { state.unlock() }

    if (state.locked) {
        LockedScreen(
            busy = state.authenticating,
            error = state.error,
            onUnlock = state::unlock,
        )
    } else {
        content()
    }

    if (state.warningVisible) {
        AlertDialog(
            onDismissRequest = state::dismissWarning,
            title = { Text(APP_LOCK_NO_DEVICE_LOCK_TITLE) },
            text = { Text(APP_LOCK_NO_DEVICE_LOCK_BODY) },
            confirmButton = {
                TextButton(onClick = state::dismissWarning) { Text(APP_LOCK_WARNING_DISMISS) }
            },
        )
    }
}

/**
 * The locked state: the app's name, its tagline, and one button.
 *
 * Deliberately minimal — a branded screen and nothing else. No balance, no
 * wallet list, no count: behind this screen there is nothing rendered, and the
 * screen itself says nothing about what is stored here.
 */
@Composable
private fun LockedScreen(
    busy: Boolean,
    error: String?,
    onUnlock: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = APP_LOCK_TITLE,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = XbtPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = APP_LOCK_SUBTITLE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = onUnlock,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Text(APP_LOCK_UNLOCK_LABEL)
                    }
                }

                if (busy) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = APP_LOCK_AUTHENTICATING,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                val errorText = error
                if (errorText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Shows the prompt and suspends until it settles, as a `true`/`false` answer.
 *
 * The bridge between the callback-shaped [LockAuthenticator] and the
 * `suspend`-shaped callers that need the answer inline — the hot send's sign,
 * which must not produce a signature until the user has authenticated. A
 * cancellation of the surrounding coroutine is not a "no": the continuation is
 * simply not resumed.
 */
suspend fun LockAuthenticator.authenticateAndWait(cryptoObject: Any? = null): Boolean =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        authenticate(
            cryptoObject = cryptoObject,
            onSuccess = { if (continuation.isActive) continuation.resumeWith(Result.success(true)) },
            onCancel = { if (continuation.isActive) continuation.resumeWith(Result.success(false)) },
        )
    }

/**
 * The real prompt: the system [BiometricPrompt] with a device-credential
 * fallback.
 *
 * `setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)` is the whole
 * configuration: a class-3 biometric is not required (a modest sensor is fine),
 * and a PIN/pattern/password is always an acceptable answer — which is what
 * keeps the app usable on a device with no fingerprint reader. There is no
 * custom PIN and no in-app credential of any kind; the system owns the prompt.
 *
 * A prompt that ends without success reports the platform's own message, except
 * for the two "the user backed out" codes, which are a decision rather than a
 * failure and read as null. A single non-matching finger does **not** end the
 * prompt — `onAuthenticationFailed` is a retry, not an error — so it is
 * deliberately not forwarded.
 *
 * @param activity the host activity; `BiometricPrompt` requires a
 *   `FragmentActivity`, which is why the app's activity is one.
 */
class BiometricLockAuthenticator(
    private val activity: FragmentActivity,
    private val title: String = APP_LOCK_PROMPT_TITLE,
    private val subtitle: String = APP_LOCK_PROMPT_SUBTITLE,
) : LockAuthenticator {

    override fun authenticate(
        cryptoObject: Any?,
        onSuccess: () -> Unit,
        onCancel: (String?) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED,
                        -> onCancel(null)

                        else -> onCancel(errString.toString())
                    }
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()

        val crypto = cryptoObject as? BiometricPrompt.CryptoObject
        if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
    }
}

/**
 * Remembers the app lock for the app's lifetime, from the activity's own
 * authenticator.
 *
 * A null [activity] (a preview, a test composition) gets an authenticator that
 * never succeeds, so the gate stays up rather than silently opening.
 */
@Composable
fun rememberAppLockState(
    deviceSecure: Boolean,
    warningDismissed: Boolean,
    onWarningDismissed: () -> Unit,
): AppLockState {
    val activity = LocalActivity.current as? FragmentActivity
    return androidx.compose.runtime.remember(activity, deviceSecure) {
        AppLockState(
            deviceSecure = deviceSecure,
            authenticator = activity
                ?.let { BiometricLockAuthenticator(it) }
                ?: UnavailableAuthenticator,
            warningDismissed = warningDismissed,
            onWarningDismissed = onWarningDismissed,
        )
    }
}

/** A prompt that can never run — the preview/test fallback for [rememberAppLockState]. */
private object UnavailableAuthenticator : LockAuthenticator {
    override fun authenticate(
        cryptoObject: Any?,
        onSuccess: () -> Unit,
        onCancel: (String?) -> Unit,
    ) = onCancel(null)
}
