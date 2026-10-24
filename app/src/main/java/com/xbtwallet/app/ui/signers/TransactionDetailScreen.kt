package com.xbtwallet.app.ui.signers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.chain.ChainQuery
import com.xbtwallet.app.chain.TransactionDetail
import com.xbtwallet.app.chain.TransactionDetailInput
import com.xbtwallet.app.chain.TransactionDetailOutput
import com.xbtwallet.app.chain.TransactionDirection
import com.xbtwallet.app.chain.TransactionRow
import com.xbtwallet.app.chain.blockTimestampFromHeaderHex
import com.xbtwallet.app.chain.formatBlockTimestamp
import com.xbtwallet.app.chain.loadTransactionDetail
import com.xbtwallet.app.chain.resolveUnknownInputValues

/**
 * One transaction, in full: the wallet's net effect at the top, the chain facts
 * in a details block, the complete txid, and the outputs and inputs that make up
 * the arithmetic.
 *
 * **The row opens the screen; the screen fetches the structure.** The navigation
 * route carries only the txid. The row the user tapped is read back out of the
 * current [SignerChainState] snapshot by that id, so the header renders
 * immediately from data the scan already paid for. The full transaction is then
 * fetched once, over the same connection the scan used, to fill the parse
 * section. A row that is no longer in the snapshot falls back to a fresh lookup:
 * the id is fetched and its height is recovered from the snapshot's own address
 * histories where possible, never invented.
 *
 * **The fee is loaded on demand.** A transaction's inputs name previous outputs
 * and carry no values, so the fee cannot be known without reading each parent.
 * Opening this screen does not do that: the fee row offers a "Load fee" button,
 * and only a tap starts the sequential parent fetch. Leaving the screen cancels
 * the load with the composition's scope; a failed load keeps the transaction
 * structure on screen and offers a Retry.
 *
 * The strings are pinned in [TransactionDetailPresentation] and the state
 * machine in [TransactionDetailState]; this file is the layout over them.
 */
@Composable
fun TransactionDetailScreen(
    txHash: String,
    state: SignerChainState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    // The row this screen was opened from. Re-read every composition: if the
    // snapshot refreshes while the screen is open, the header follows the fresh
    // row rather than a stale copy captured at navigation time.
    val row: TransactionRow? = state.snapshot?.transactions?.firstOrNull { it.txHash == txHash }

    val detailState = remember(txHash) {
        TransactionDetailState(
            txHash = txHash,
            row = row,
            scope = scope,
            loader = {
                state.withChainSession { query ->
                    loadDetailOverConnection(txHash, state.snapshot, query)
                }
            },
            feeResolver = { current, onProgress ->
                state.withChainSession { query ->
                    val walletScripts = walletScriptsOf(state.snapshot)
                    resolveUnknownInputValues(current, walletScripts, query, onProgress)
                        .let { current.withKnownInputs(it) }
                }
            },
        )
    }

    LaunchedEffect(txHash) { detailState.load() }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(TransactionDetailStrings.BACK) }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = TransactionDetailStrings.TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                ConfirmationBadge(detailState.confirmations)
            }

            Header(
                netSatoshis = detailState.netSatoshis,
                direction = transactionDirection(detailState.netSatoshis ?: 0L),
            )

            when (detailState.phase) {
                DetailPhase.LOADING -> LoadingLine(TransactionDetailStrings.LOADING)
                DetailPhase.ERROR -> {
                    Text(
                        text = detailState.errorMessage ?: TransactionDetailStrings.LOAD_FAILED,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { detailState.retry() }) { Text(TransactionDetailStrings.RETRY) }
                }
                DetailPhase.READY -> {
                    val detail = detailState.detail
                    if (detail != null) {
                        DetailsBlock(
                            detail = detail,
                            row = row,
                            feePhase = detailState.feePhase,
                            feeProgress = detailState.feeProgress,
                            feeError = detailState.feeErrorMessage,
                            onLoadFee = { detailState.loadFee() },
                            onRetryFee = { detailState.retryFee() },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        TransactionIdBlock(
                            txHash = txHash,
                            copied = copied,
                            onCopy = {
                                clipboard.setText(AnnotatedString(txHash))
                                copied = true
                            },
                        )
                        if (copied) {
                            LaunchedEffect(copied) {
                                kotlinx.coroutines.delay(1_500)
                                copied = false
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        OutputsBlock(detail.outputs)
                        Spacer(modifier = Modifier.height(4.dp))
                        InputsBlock(detail.inputs)
                    }
                }
            }
        }
    }
}

/**
 * Loads one transaction over [query], recovering the fallback row's height from
 * the snapshot's address histories when the row is no longer in the transactions
 * list.
 *
 * This is the screen's wiring of the pure [loadTransactionDetail]: the wallet's
 * scripts and known txids come from the same snapshot the row did, and the block
 * header is read only when the row is absent and its height is confirmed — so
 * the fallback still shows a real date instead of a blank.
 */
private fun loadDetailOverConnection(
    txHash: String,
    snapshot: com.xbtwallet.app.chain.AccountSnapshot?,
    query: ChainQuery,
): TransactionDetail {
    val walletScripts = walletScriptsOf(snapshot)
    val walletTransactionIds = snapshot?.transactions?.mapTo(HashSet()) { it.txHash }.orEmpty()

    var height: Int? = null
    var timestamp: Long? = null

    if (snapshot != null) {
        // The row is the cheap path: the scan already fetched and dated it.
        val row = snapshot.transactions.firstOrNull { it.txHash == txHash }
        if (row != null) {
            height = row.height
            timestamp = row.blockTimestampEpochSeconds
        } else {
            // Fallback: the transaction is not in the shown rows, but the
            // snapshot's address histories may still know its height. That
            // costs no RPC; only the date needs a header call, and only when
            // the height is confirmed.
            val history = snapshot.addresses.asSequence()
                .flatMap { it.history.asSequence() }
                .firstOrNull { it.txHash == txHash }
            height = history?.height
            if (height != null && height > 0) {
                timestamp = try {
                    blockTimestampFromHeaderHex(query.blockHeader(height))
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    return loadTransactionDetail(
        txHash = txHash,
        walletScripts = walletScripts,
        query = query,
        knownHeight = height,
        knownBlockTimestampEpochSeconds = timestamp,
        walletTransactionIds = walletTransactionIds,
    )
}

/** The wallet's scriptPubKeys from the scan's addresses; empty when nothing is scanned. */
private fun walletScriptsOf(snapshot: com.xbtwallet.app.chain.AccountSnapshot?): Set<String> =
    snapshot?.addresses
        ?.mapNotNull { address -> address.scriptPubKeyHex.takeIf { it.isNotEmpty() } }
        ?.toSet()
        .orEmpty()

/** The direction-tinted net amount, big and monospace. */
@Composable
private fun Header(netSatoshis: Long?, direction: TransactionDirection) {
    val tint = when (direction) {
        TransactionDirection.IN -> IncomingGreen
        TransactionDirection.OUT -> OutgoingRed
        TransactionDirection.NEUTRAL -> NeutralGrey
    }
    Text(
        text = detailAmountLabel(netSatoshis),
        style = MaterialTheme.typography.headlineSmall,
        fontFamily = FontFamily.Monospace,
        color = tint,
    )
}

/** "Loading…" with a spinner, in the transactions section's compact style. */
@Composable
private fun LoadingLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The chain facts: status, exact confirmation count, block height and time, fee,
 * size, and the input/output counts. Unknown values are an em dash — the same
 * rule as everywhere else in this wallet.
 */
@Composable
private fun DetailsBlock(
    detail: TransactionDetail,
    row: TransactionRow?,
    feePhase: FeePhase,
    feeProgress: Pair<Int, Int>,
    feeError: String?,
    onLoadFee: () -> Unit,
    onRetryFee: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DetailRow(TransactionDetailStrings.STATUS, statusLabel(detail.confirmed))
        DetailRow(TransactionDetailStrings.CONFIRMATIONS, exactConfirmations(detail.confirmations ?: row?.confirmations))
        DetailRow(TransactionDetailStrings.BLOCK_HEIGHT, formatBlockHeight(detail.height))
        DetailRow(
            TransactionDetailStrings.BLOCK_TIME,
            detail.blockTimestampEpochSeconds?.let { formatBlockTimestamp(it) } ?: UNKNOWN_VALUE,
        )
        DetailRow(TransactionDetailStrings.SIZE, formatSize(detail.vsize, detail.weight))
        DetailRow(TransactionDetailStrings.INPUTS, detail.inputCount.toString())
        DetailRow(TransactionDetailStrings.OUTPUTS, detail.outputCount.toString())

        // The fee gets its own row because it has three states: known, loadable,
        // and being loaded. A spinner takes the value's place while parents are
        // fetched; the button only appears when there is nothing to show yet.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = TransactionDetailStrings.FEE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(120.dp),
            )
            when {
                detail.feeSatoshis != null -> Text(
                    text = formatFee(detail.feeSatoshis, detail.vsize) ?: UNKNOWN_VALUE,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                feePhase == FeePhase.LOADING -> LoadingLine(
                    TransactionDetailStrings.fetchingParents(feeProgress.second),
                )
                feePhase == FeePhase.ERROR -> {
                    Column {
                        Text(
                            text = feeError ?: TransactionDetailStrings.FEE_LOAD_FAILED,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRetryFee) { Text(TransactionDetailStrings.RETRY) }
                    }
                }
                else -> Button(onClick = onLoadFee) { Text(TransactionDetailStrings.LOAD_FEE) }
            }
        }
    }
}

/** One label/value row in the details block. */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * The full txid, monospace and tap-to-copy. Selection is also enabled so a long
 * press can copy it by hand; the tap's "Copied" notice is the primary feedback.
 */
@Composable
private fun TransactionIdBlock(txHash: String, copied: Boolean, onCopy: () -> Unit) {
    Column {
        Text(
            text = TransactionDetailStrings.TRANSACTION_ID,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
        SelectionContainer {
            Text(
                text = txHash,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onCopy)
                    .padding(vertical = 4.dp),
            )
        }
        if (copied) {
            Text(
                text = TransactionDetailStrings.COPIED,
                style = MaterialTheme.typography.labelSmall,
                color = IncomingGreen,
            )
        }
    }
}

/** The outputs list: shortened address, amount, and a "you" tag on ours. */
@Composable
private fun OutputsBlock(outputs: List<TransactionDetailOutput>) {
    SectionTitle(TransactionDetailStrings.OUTPUTS)
    outputs.forEachIndexed { index, output ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = shortenedAddress(output.address),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            if (output.isOurs) YouTag()
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatSatsWithUnit(output.satoshis),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (index < outputs.lastIndex) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** The inputs list: shortened prev outpoint, amount or em dash, and "you" on ours. */
@Composable
private fun InputsBlock(inputs: List<TransactionDetailInput>) {
    SectionTitle(TransactionDetailStrings.INPUTS)
    inputs.forEachIndexed { index, input ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = shortenedOutpoint(input.previousTxHash, input.previousOutputIndex),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            if (input.isOurs) YouTag()
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = input.satoshis?.let { formatSatsWithUnit(it) } ?: UNKNOWN_VALUE,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (index < inputs.lastIndex) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The "you" tag on an output or input that belongs to this wallet. */
@Composable
private fun YouTag() {
    Text(
        text = TransactionDetailStrings.YOU,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = IncomingGreen,
        modifier = Modifier
            .background(IncomingGreen.copy(alpha = 0.14f), MaterialTheme.shapes.small)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
