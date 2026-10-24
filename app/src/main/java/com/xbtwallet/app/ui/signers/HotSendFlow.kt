package com.xbtwallet.app.ui.signers

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xbtwallet.app.data.SignedSend
import kotlinx.coroutines.CancellationException

/**
 * The hot-wallet send's own little state machine — Compose-free in everything but
 * the `mutableStateOf` fields, so a JVM test drives it with fake sign and
 * broadcast functions and no device, no socket and no crypto.
 *
 * It exists because the hot branch of the send flow is a *different* shape from
 * the air-gapped one: there is no QR to show and no PSBT to scan back, only a
 * review, an optional passphrase prompt, a signature, and a broadcast. Keeping
 * that machine here — rather than as `remember`ed flags inside the composable —
 * is what makes "the silent empty-passphrase attempt", "the wrong-passphrase
 * loop", "a refused broadcast retries with the plan intact" and "the success
 * screen carries the txid" things a test can pin.
 *
 * The passphrase itself is deliberately **not** held here: the screen keeps it in
 * a plain `remember` for the length of one prompt and passes it to
 * [submitPassphrase]. Only the outcomes live in this object.
 *
 * @param sign signs the prepared transaction under a passphrase. In production
 *   this is [com.xbtwallet.app.data.signHotSend] off the main thread; a test
 *   returns a scripted [SignedSend].
 * @param broadcast hands a signed transaction to the configured server and
 *   returns its txid. In production this is the chain state's
 *   `broadcastTransaction`; a test returns an id or throws.
 */
@Stable
class HotSendFlowState(
    private val sign: suspend (passphrase: String) -> SignedSend,
    private val broadcast: suspend (txHex: String) -> String,
) {

    /**
     * The last signing failure that is not a wrong passphrase, or null. The plan
     * is untouched by it — the user can back out and start again.
     */
    var error by mutableStateOf<String?>(null)
        private set

    /** True while a sign or a broadcast is in flight. Every action disables on it. */
    var busy by mutableStateOf(false)
        private set

    /** What the in-flight action is, for the button's own label, or null when idle. */
    var status by mutableStateOf<String?>(null)
        private set

    /**
     * The server's reason for refusing the broadcast, or null. Set on a refusal
     * and cleared on the next attempt; the signed transaction is kept, so a retry
     * never re-signs and never rebuilds the plan.
     */
    var broadcastError by mutableStateOf<String?>(null)
        private set

    /** The mismatch sentence shown on the passphrase prompt, or null. */
    var passphraseError by mutableStateOf<String?>(null)
        private set

    /** True once the empty passphrase was refused: the prompt is what is wanted. */
    private var passphraseRequired by mutableStateOf(false)

    /** The broadcast transaction's id, once the server has accepted it. */
    var txid by mutableStateOf<String?>(null)
        private set

    /** The signed transaction hex, held between a sign and one or more broadcasts. */
    var signedTxHex by mutableStateOf<String?>(null)
        private set

    /** True once a signature exists, so the review's button becomes a retry. */
    val hasSignedTransaction: Boolean get() = signedTxHex != null

    /**
     * Which screen the hot send is on.
     *
     * Derived from the facts rather than stored, so it cannot disagree with them:
     * an accepted txid is the success screen, a refused empty passphrase is the
     * prompt, and everything else is the review.
     */
    val step: HotSendStep
        get() = when {
            txid != null -> HotSendStep.SUCCESS
            passphraseRequired -> HotSendStep.PASSPHRASE
            else -> HotSendStep.REVIEW
        }

    /**
     * What the review's `Sign & broadcast` does: the **empty passphrase is tried
     * silently first**, and only a zpub mismatch puts the prompt up. When a
     * signature already exists (a refused broadcast), this is a retry and no
     * signing happens at all.
     */
    suspend fun signAndBroadcast() {
        if (busy) return
        val alreadySigned = signedTxHex
        if (alreadySigned != null) {
            broadcastAndShow(alreadySigned)
            return
        }
        attempt(passphrase = "")
    }

    /** Submit a typed passphrase from the prompt. */
    suspend fun submitPassphrase(passphrase: String) {
        if (busy) return
        attempt(passphrase)
    }

    /** Back out of the prompt without signing anything: the plan is intact. */
    fun backToReview() {
        if (busy) return
        passphraseRequired = false
        passphraseError = null
        error = null
    }

    private suspend fun attempt(passphrase: String) {
        busy = true
        status = SIGNING_LABEL
        error = null
        broadcastError = null

        val outcome = try {
            sign(passphrase)
        } catch (cancellation: CancellationException) {
            busy = false
            status = null
            throw cancellation
        } catch (failure: Exception) {
            error = "Could not sign this transaction: ${failure.message ?: failure::class.java.simpleName}"
            busy = false
            status = null
            return
        }

        when (outcome) {
            is SignedSend.Signed -> {
                signedTxHex = outcome.txHex
                passphraseRequired = false
                passphraseError = null
                broadcastAndShow(outcome.txHex)
            }

            SignedSend.WrongPassphrase -> {
                // The empty passphrase is the *silent* probe, so a mismatch on it
                // shows a clean prompt rather than an error the user did not
                // cause. A typed attempt that mismatches is the retry sentence.
                if (passphrase.isNotEmpty()) passphraseError = PASSPHRASE_MISMATCH
                passphraseRequired = true
                busy = false
                status = null
            }

            SignedSend.VaultUnavailable -> {
                error = VAULT_UNAVAILABLE_MESSAGE
                busy = false
                status = null
            }
        }
    }

    private suspend fun broadcastAndShow(txHex: String) {
        busy = true
        status = BROADCASTING_LABEL
        broadcastError = null
        try {
            txid = broadcast(txHex)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            broadcastError = describeBroadcastFailure(failure)
        } finally {
            busy = false
            status = null
        }
    }
}

/** Where the hot send is. The review is the entry point; there is no form here. */
enum class HotSendStep { REVIEW, PASSPHRASE, SUCCESS }

/** The prompt's one error sentence, word for word. */
const val PASSPHRASE_MISMATCH = "Passphrase doesn't match this wallet"

/** Shown when the vault has no readable seed for this wallet. */
internal const val VAULT_UNAVAILABLE_MESSAGE =
    "This wallet's seed cannot be read on this phone."

/** The review screen's title, and the form's terminal button for a hot wallet. */
internal const val HOT_REVIEW_TITLE = "Review transaction"

/** The review's terminal control. */
internal const val SIGN_AND_BROADCAST_LABEL = "Sign & broadcast"

/** The same control once a signature exists and the server refused it. */
internal const val RETRY_BROADCAST_LABEL = "Retry broadcast"

/** The review button's label while the signature is being produced. */
internal const val SIGNING_LABEL = "Signing\u2026"

/** The review button's label while the transaction is being broadcast. */
internal const val BROADCASTING_LABEL = "Broadcasting\u2026"

/** The passphrase prompt's title and field label. */
internal const val HOT_PASSPHRASE_TITLE = "Passphrase"

/** The success screen: a checkmark, the amount, the txid and one way out. */
internal const val HOT_SUCCESS_TITLE = "Sent"

/** The success screen's terminal control. */
internal const val HOT_DONE_LABEL = "Done"

/** What the success screen says above the txid. */
internal const val HOT_SUCCESS_CHECK = "\u2713"

/**
 * The change line the review shows, in the QR flow's own words for the no-change
 * case: an exact spend says so, and an ordinary send says how much comes back.
 */
internal fun hotChangeLine(changeSatoshis: Long): String =
    if (changeSatoshis == 0L) {
        EXACT_SPEND_LINE
    } else {
        "Change: ${formatSats(changeSatoshis)} sats back to this wallet."
    }
