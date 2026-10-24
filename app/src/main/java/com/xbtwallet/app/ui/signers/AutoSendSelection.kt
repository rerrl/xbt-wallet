package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.chain.InsufficientFunds
import com.xbtwallet.app.chain.P2WPKH_SCRIPT_SIZE
import com.xbtwallet.app.chain.ScannedUtxo
import com.xbtwallet.app.chain.SelectableUtxo
import com.xbtwallet.app.chain.selectCoins
import com.xbtwallet.app.chain.selectMax
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerType

/**
 * The automatic send: what the selector chose for the amount and fee rate on the
 * Send form, and the copy the form shows about it.
 *
 * Deliberately Android-free and Compose-free — the same rule the rest of this
 * package follows — so "which coins, and what does the form say about them" is
 * plain Kotlin a JVM test can drive, with no device.
 *
 * The Send form is the only place this runs: it maps the snapshot's coins into
 * the selector's vocabulary, runs it, and maps its answer back. Nothing here
 * decides *whether* a wallet may send — that is [sendSelectedEnabled], read by
 * the detail screen — so this stays a pure function of a coin set and the form.
 */

/** Shown while the form cannot yet price a selection (no amount or fee rate). */
internal const val AUTO_SELECTION_PENDING = "Coins selected automatically."

/** Added under the summary when the selection needs no change output. */
internal const val EXACT_SPEND_LINE = "Exact spend \u2014 no change output."

/** The link out of the automatic path, back to the manual coin picker. */
internal const val CHOOSE_MANUALLY_LABEL = "Choose manually"

/** The summary line, with how many inputs the send will carry. */
internal fun autoSelectionSummary(inputCount: Int): String =
    "Coins selected automatically ($inputCount inputs)"

/** The failure line: how short the wallet is, in sats. */
internal fun insufficientFundsSummary(availableSatoshis: Long, requiredSatoshis: Long): String =
    "Insufficient funds: ${formatSats(availableSatoshis)} sats available, " +
        "${formatSats(requiredSatoshis)} sats needed."

/**
 * What the automatic selection resolved to, or why it could not.
 *
 * [Selection] carries the coins as the snapshot holds them (not the selector's
 * narrow view of them) so the send flow can hand them straight to
 * [prepareSend]; [amountSatoshis] is what would actually be sent — the entered
 * amount for an ordinary send, or the whole remaining balance for a max send.
 */
internal sealed interface AutoSendOutcome {
    data class Selection(
        val inputs: List<ScannedUtxo>,
        val amountSatoshis: Long,
        val feeSatoshis: Long,
        val exact: Boolean,
    ) : AutoSendOutcome

    data class Insufficient(val availableSatoshis: Long, val requiredSatoshis: Long) : AutoSendOutcome
}

/**
 * Runs the selector for the form's snapshot, amount and fee rate.
 *
 * @param max true for a "Max" send: every coin is spent and the amount is
 *   whatever the balance leaves after its fee (see [selectMax]).
 * @param recipientScriptSize the recipient output's script size; defaults to
 *   P2WPKH, the output this wallet both derives and normally pays.
 */
internal fun autoSelect(
    utxos: List<ScannedUtxo>,
    amountSatoshis: Long,
    feeRateSatPerVb: Long,
    recipientScriptSize: Int = P2WPKH_SCRIPT_SIZE,
    changeScriptSize: Int = P2WPKH_SCRIPT_SIZE,
    max: Boolean,
): AutoSendOutcome {
    val selectable = utxos.map { SelectableUtxo(it.utxo.txHash, it.utxo.txPos, it.utxo.satoshis) }
    if (selectable.isEmpty()) {
        return AutoSendOutcome.Insufficient(availableSatoshis = 0L, requiredSatoshis = amountSatoshis)
    }
    val byOutpoint = utxos.associateBy { it.utxo.outpoint }

    return try {
        val selection = if (max) {
            selectMax(selectable, feeRateSatPerVb, recipientScriptSize)
        } else {
            selectCoins(selectable, amountSatoshis, feeRateSatPerVb, recipientScriptSize, changeScriptSize)
        }
        AutoSendOutcome.Selection(
            inputs = selection.outpoints.mapNotNull { byOutpoint[it] },
            amountSatoshis = if (max) selection.totalSatoshis - selection.feeSatoshis else amountSatoshis,
            feeSatoshis = selection.feeSatoshis,
            exact = selection.isExactMatch,
        )
    } catch (insufficient: InsufficientFunds) {
        AutoSendOutcome.Insufficient(insufficient.availableSatoshis, insufficient.requiredSatoshis)
    }
}

/**
 * The summary line for [outcome]: the input count once a selection is priced,
 * and the neutral "automatically" line until then.
 */
internal fun autoSummaryFor(outcome: AutoSendOutcome?): String = when (outcome) {
    is AutoSendOutcome.Selection -> autoSelectionSummary(outcome.inputs.size)
    else -> AUTO_SELECTION_PENDING
}

/** The failure line for [outcome], or null when there is nothing wrong to say. */
internal fun autoErrorFor(outcome: AutoSendOutcome?): String? = when (outcome) {
    is AutoSendOutcome.Insufficient ->
        insufficientFundsSummary(outcome.availableSatoshis, outcome.requiredSatoshis)

    else -> null
}

/** True when [outcome] is a no-change exact spend, so the form adds [EXACT_SPEND_LINE]. */
internal fun autoIsExact(outcome: AutoSendOutcome?): Boolean =
    outcome is AutoSendOutcome.Selection && outcome.exact

/**
 * The note under a **disabled** Send on a wallet's detail screen: why *this*
 * wallet cannot start the air-gapped flow.
 *
 * A watch-only import holds public keys only, so it is pointed at the fix — add
 * the account as a signing device — through the same sentence the UTXO screen
 * uses. A hot wallet is signing-ready but the QR dance is not its send (internal
 * signing is a later phase), so it keeps this screen's own long-standing
 * sentence. Only ever called for a wallet [sendSelectedEnabled] refuses.
 */
internal fun detailSendNote(signer: Signer): String =
    if (signer.type is SignerType.HotSeed) {
        "Send comes with the air-gapped signing flow \u2014 coming soon."
    } else {
        WATCH_ONLY_CANNOT_SPEND_HINT
    }
