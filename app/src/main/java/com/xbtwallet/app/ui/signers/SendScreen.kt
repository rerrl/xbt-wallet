package com.xbtwallet.app.ui.signers

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.common.BitMatrix
import com.xbtwallet.app.chain.AddressChain
import com.xbtwallet.app.chain.ScannedUtxo
import com.xbtwallet.app.chain.SendInput
import com.xbtwallet.app.chain.buildUnsignedTransaction
import com.xbtwallet.app.chain.p2wpkhScriptPubKey
import com.xbtwallet.app.chain.planSend
import com.xbtwallet.app.chain.scriptPubKeyFromAddress
import com.xbtwallet.app.crypto.Bip32Derivation
import com.xbtwallet.app.crypto.PsbtException
import com.xbtwallet.app.crypto.PsbtInput
import com.xbtwallet.app.crypto.UrPsbtAssembler
import com.xbtwallet.app.crypto.UrPsbtEncoder
import com.xbtwallet.app.crypto.UrPsbtOutcome
import com.xbtwallet.app.crypto.buildUnsignedPsbt
import com.xbtwallet.app.crypto.finalizePsbt
import com.xbtwallet.app.crypto.parsePsbt
import com.xbtwallet.app.crypto.transactionId
import com.xbtwallet.app.data.SignerType
import com.xbtwallet.app.ui.receive.renderQr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Send screen: the whole air-gapped round-trip, in four steps.
 *
 * 1. **FORM** — the selected coins, a recipient, a fee rate, an amount and a
 *    label; the terminal control is **"Finalize transaction for signing"**, and
 *    it is enabled only for a paying amount on a *signing-ready* wallet.
 * 2. **SHOW_QR** — the unsigned PSBT as a QR for the signer to read: a static
 *    symbol when it fits one, an animated fountain-coded cycle when it does not.
 * 3. **SCAN** — the signed PSBT read back off the signer's screen, frame by
 *    frame, and finalized (see [finalizePsbt] — the sighash byte `0x21` is
 *    required, which is this wallet's replay protection).
 * 4. **BROADCAST** — the finalized transaction, its computed txid, and one
 *    button that hands it to the configured Electrum server.
 *
 * **The selection rides on the route** ([outpoints]) rather than in shared
 * state, so a rotation lands back on the same coins. [step] and every field are
 * held in `remember` for this composition only: a rotation restarts the flow at
 * the FORM, which is deliberate — the round-trip is a single sitting with a
 * signer in hand, and re-rendering a half-scanned QR after a rotation would be a
 * worse lie than starting the form again. Nothing is lost by it: the recipient
 * and amount are still on screen to re-submit.
 *
 * The outpoints are resolved against [SignerChainState.snapshot] — the cached
 * snapshot renders instantly, and locally derivable data must never wait on the
 * network — so this screen does **not** gate on the scan state. A screenshot
 * with no snapshot, and a selection whose outputs have all left the UTXO set,
 * each get a plain line and the Back button instead of a form.
 */
@Composable
fun SendScreen(
    state: SignerChainState,
    outpoints: List<String>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot

    // Resolve the selection in `outpoints` order, dropping any outpoint the
    // snapshot does not (or no longer) holds. A resync may have found one spent;
    // it simply falls out here, exactly as it falls out of the UTXO screen's
    // selection.
    val selected: List<ScannedUtxo> = if (snapshot == null) {
        emptyList()
    } else {
        outpoints.mapNotNull { outpoint ->
            snapshot.utxos.firstOrNull { it.utxo.outpoint == outpoint }
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            SendHeader(state = state, onBack = onBack)

            Spacer(modifier = Modifier.height(12.dp))

            when {
                snapshot == null -> SendNotice(
                    text = "Syncing \u2014 selected outputs unavailable until the first snapshot lands.",
                )

                selected.isEmpty() -> SendNotice(
                    text = "The selected outputs are no longer in this wallet's UTXO set " +
                        "\u2014 a resync may have found them spent.",
                )

                else -> SendFlow(
                    state = state,
                    selected = selected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

/** The header every per-signer screen wears: Back, a title, then who it is about. */
@Composable
private fun SendHeader(state: SignerChainState, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("\u2190 Back") }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Send",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = state.signer.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A one-line, terminal state: what is wrong, and nothing else but Back. */
@Composable
private fun SendNotice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Where the flow is. Rotating restarts at [FORM] — see the screen's doc. */
private enum class SendStep { FORM, SHOW_QR, SCAN, BROADCAST }

/** The unsigned PSBT and the numbers its summary shows, held between steps. */
private class PreparedSend(
    val psbtBytes: ByteArray,
    val recipient: String,
    val amountSatoshis: Long,
    val feeSatoshis: Long,
    val changeSatoshis: Long,
)

/** A finalized transaction ready to broadcast. */
private class SignedSend(
    val txHex: String,
    val txid: String,
)

/**
 * The step machine itself. All state is in `remember` for this composition —
 * see the screen's doc for why a rotation restarting the flow is accepted.
 */
@Composable
private fun SendFlow(
    state: SignerChainState,
    selected: List<ScannedUtxo>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val signerKey = state.signer.id

    var step by remember(signerKey) { mutableStateOf(SendStep.FORM) }

    // --- the form ---
    var recipient by remember(signerKey) { mutableStateOf("") }
    var feeInput by remember(signerKey) { mutableStateOf("1") }
    var amountInput by remember(signerKey) { mutableStateOf("") }
    var label by remember(signerKey) { mutableStateOf("") }
    var scannerOpen by remember(signerKey) { mutableStateOf(false) }
    var buildError by remember(signerKey) { mutableStateOf<String?>(null) }

    // --- the round-trip ---
    var prepared by remember(signerKey) { mutableStateOf<PreparedSend?>(null) }
    var signed by remember(signerKey) { mutableStateOf<SignedSend?>(null) }
    var scanProgress by remember(signerKey) { mutableIntStateOf(0) }
    var scanMessage by remember(signerKey) { mutableStateOf<String?>(null) }
    var broadcasting by remember(signerKey) { mutableStateOf(false) }
    var broadcastError by remember(signerKey) { mutableStateOf<String?>(null) }
    var broadcastTxid by remember(signerKey) { mutableStateOf<String?>(null) }

    // The scanner's own state machine, one per scan. Remembered (not recreated
    // per frame) because a fountain code is stateful across frames.
    val assembler = remember(signerKey) { UrPsbtAssembler() }

    // CAMERA is asked for by this app, not by the scanner library: the panel is
    // our own composable and it can only work with the permission in hand. A
    // granted request opens the recipient scanner; a refusal is simply
    // remembered as "closed", so the "Scan QR" button stays the manual path.
    val recipientScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scannerOpen = true
    }

    // The recipient scanner opens by default on entry — scanning is the expected
    // way to get a recipient, not an extra step. Keyed on `Unit` so it fires
    // exactly once per entry into this form.
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            scannerOpen = true
        } else {
            recipientScanLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val openRecipientScanner: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            scannerOpen = true
        } else {
            recipientScanLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val totalSatoshis = selected.sumOf { it.utxo.satoshis }
    val feeRate = parseFeeRate(feeInput)
    val maxSendable = feeRate?.let { rate -> maxSendableSatoshis(totalSatoshis, selected.size, rate) }
    val estimatedFee = feeRate?.let { rate -> estimateFeeSatoshis(selected.size, rate) }
    val amountSatoshis = parseAmountSats(amountInput)
    val addressError = validateRecipientAddress(recipient)

    val canFinalize = state.signer.signingReady &&
        recipient.isNotEmpty() && addressError == null &&
        amountSatoshis != null && maxSendable != null && amountSatoshis <= maxSendable

    Column(modifier = modifier) {
        // The SHOW_QR step is the one step that must not scroll: its QR is sized
        // by a weight, and a scrolling parent measures its content against an
        // unbounded height — a weighted child there would be given nothing. Every
        // other step keeps the scrolling column.
        val stepScrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    if (step == SendStep.SHOW_QR) {
                        Modifier
                    } else {
                        Modifier.verticalScroll(stepScrollState)
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (step) {
                SendStep.FORM -> FormStep(
                    selected = selected,
                    totalSatoshis = totalSatoshis,
                    recipient = recipient,
                    onRecipientChange = { recipient = it },
                    addressError = if (recipient.isNotEmpty()) addressError else null,
                    scannerOpen = scannerOpen,
                    onOpenScanner = openRecipientScanner,
                    onScannerResult = { payload ->
                        recipient = extractAddress(payload)
                        scannerOpen = false
                    },
                    onScannerDismiss = { scannerOpen = false },
                    feeInput = feeInput,
                    onFeeChange = { feeInput = it },
                    amountInput = amountInput,
                    onAmountChange = { amountInput = it },
                    maxSendable = maxSendable,
                    estimatedFee = estimatedFee,
                    label = label,
                    onLabelChange = { if (it.length <= LABEL_MAX_CHARS) label = it },
                    buildError = buildError,
                )

                SendStep.SHOW_QR -> {
                    val current = prepared
                    if (current == null) {
                        LaunchedEffect(Unit) { step = SendStep.FORM }
                    } else {
                        ShowQrStep(
                            prepared = current,
                            onScan = {
                                scanMessage = null
                                scanProgress = 0
                                step = SendStep.SCAN
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                SendStep.SCAN -> ScanStep(
                    state = state,
                    assembler = assembler,
                    progress = scanProgress,
                    message = scanMessage,
                    onProgress = { scanProgress = it },
                    onMessage = { scanMessage = it },
                    onComplete = { raw ->
                        signed = SignedSend(raw.toHexString(), transactionId(raw))
                        broadcastError = null
                        broadcastTxid = null
                        step = SendStep.BROADCAST
                    },
                    onCancel = {
                        assembler.reset()
                        scanMessage = null
                        scanProgress = 0
                        step = SendStep.SHOW_QR
                    },
                )

                SendStep.BROADCAST -> {
                    val current = prepared
                    val signedSend = signed
                    if (current == null || signedSend == null) {
                        LaunchedEffect(Unit) { step = SendStep.FORM }
                    } else {
                        BroadcastStep(
                            state = state,
                            prepared = current,
                            signed = signedSend,
                            broadcasting = broadcasting,
                            error = broadcastError,
                            txid = broadcastTxid,
                            onBroadcast = {
                                broadcasting = true
                                broadcastError = null
                                scope.launch {
                                    try {
                                        val txid = state.broadcastTransaction(signedSend.txHex)
                                        broadcastTxid = txid
                                        // The outgoing transaction should now be
                                        // in the account's history as unconfirmed.
                                        state.reload()
                                    } catch (failure: Exception) {
                                        broadcastError = describeBroadcastFailure(failure)
                                    } finally {
                                        broadcasting = false
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // The terminal control for the FORM step: the whole round-trip starts
        // here, and it is enabled only when there is an honest transaction to
        // build — a signing-ready wallet, a valid recipient, and an amount that
        // fits under the fee-adjusted max.
        if (step == SendStep.FORM) {
            Button(
                onClick = {
                    val rate = feeRate
                    val amount = amountSatoshis
                    if (rate == null || amount == null) return@Button
                    buildError = null
                    try {
                        prepared = prepareSend(state, selected, recipient, amount, rate)
                        step = SendStep.SHOW_QR
                    } catch (failure: Exception) {
                        buildError = "Could not build the transaction: ${failure.message ?: "unknown error"}"
                    }
                },
                enabled = canFinalize,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Finalize transaction for signing")
            }

            if (!state.signer.signingReady) {
                Text(
                    // The same shared hint the UTXO list shows: a watch-only
                    // wallet cannot spend, so the way forward is a signing
                    // device, not a re-import of the same public key.
                    text = WATCH_ONLY_CANNOT_SPEND_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The form itself: selected-coin summary, recipient, fee, amount and label.
 *
 * The fields scroll; the terminal button is drawn by [SendFlow] below this.
 */
@Composable
private fun FormStep(
    selected: List<ScannedUtxo>,
    totalSatoshis: Long,
    recipient: String,
    onRecipientChange: (String) -> Unit,
    addressError: String?,
    scannerOpen: Boolean,
    onOpenScanner: () -> Unit,
    onScannerResult: (String) -> Unit,
    onScannerDismiss: () -> Unit,
    feeInput: String,
    onFeeChange: (String) -> Unit,
    amountInput: String,
    onAmountChange: (String) -> Unit,
    maxSendable: Long?,
    estimatedFee: Long?,
    label: String,
    onLabelChange: (String) -> Unit,
    buildError: String?,
) {
    // --- the selected coins ------------------------------------------
    Text(
        text = "Selected outputs",
        style = MaterialTheme.typography.titleMedium,
    )
    selected.forEach { scanned ->
        val utxo = scanned.utxo
        Text(
            text = "${formatSats(utxo.satoshis)} sats   " +
                "${shortenedMiddle(utxo.txHash)}:${utxo.txPos}",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
    Text(
        text = "Total: ${formatSats(totalSatoshis)} sats",
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
    )

    Spacer(modifier = Modifier.height(12.dp))

    // --- recipient ---------------------------------------------------
    OutlinedTextField(
        value = recipient,
        onValueChange = onRecipientChange,
        label = { Text("Recipient address") },
        modifier = Modifier.fillMaxWidth(),
    )
    if (addressError != null) {
        Text(
            text = addressError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    // A valid address gets a full readout of its own: what a user checks before
    // sending is the head and the tail against their own screen, so those come
    // back in the accent colour and the middle stays plain.
    if (recipient.isNotEmpty() && addressError == null) {
        AddressEdgeHighlight(recipient)
    }

    TextButton(onClick = onOpenScanner) { Text("Scan QR") }

    if (scannerOpen) {
        XpubQrScannerPanel(
            onResult = onScannerResult,
            onCancel = onScannerDismiss,
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // --- fee ---------------------------------------------------------
    OutlinedTextField(
        value = feeInput,
        onValueChange = onFeeChange,
        label = { Text("Fee rate (sat/vB)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    if (feeInput.isNotEmpty() && parseFeeRate(feeInput) == null) {
        Text(
            text = "Enter a whole number of at least 1.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // --- amount ------------------------------------------------------
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = amountInput,
            onValueChange = onAmountChange,
            label = { Text("Amount (sats)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        if (maxSendable != null) {
            TextButton(onClick = { onAmountChange(maxSendable.toString()) }) { Text("Max") }
        }
    }
    if (maxSendable != null && estimatedFee != null) {
        Text(
            text = "Max spendable: ${formatSats(maxSendable)} sats " +
                "(estimated fee ${formatSats(estimatedFee)} sats)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val amount = parseAmountSats(amountInput)
    when {
        amountInput.isNotEmpty() && amount == null -> Text(
            text = "Enter a whole number of sats greater than 0.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        amount != null && maxSendable != null && amount > maxSendable -> Text(
            text = "Exceeds the max spendable (${formatSats(maxSendable)} sats " +
                "after the estimated fee).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // --- label -------------------------------------------------------
    OutlinedTextField(
        value = label,
        onValueChange = onLabelChange,
        label = { Text("Label (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (buildError != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = buildError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
}

/**
 * The unsigned PSBT as a QR, with the summary a user checks before scanning it
 * into their signer.
 *
 * The QR is the point of this step, so it is the element the layout gives the
 * space to: one compact line above it (see [showQrSummary]), the symbol itself
 * filling everything left between that line and the caption, and the "Scan QR"
 * button pinned below both.
 *
 * A single-frame PSBT renders one static symbol. A bigger one animates: the
 * frames cycle through a `LaunchedEffect` about four times a second, which is
 * slow enough for a phone camera to lock onto each and fast enough that a
 * twenty-frame PSBT is a few seconds.
 */
@Composable
private fun ShowQrStep(
    prepared: PreparedSend,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val encoder = remember(prepared.psbtBytes) { UrPsbtEncoder(prepared.psbtBytes) }
    val frames = remember(encoder) { encoder.parts }
    var frameIndex by remember(encoder) { mutableIntStateOf(0) }

    if (!encoder.isSingleFrame) {
        LaunchedEffect(encoder, frames.size) {
            while (true) {
                delay(FRAME_INTERVAL_MILLIS)
                frameIndex = (frameIndex + 1) % frames.size
            }
        }
    }

    val payload = frames[frameIndex.coerceIn(0, frames.size - 1)]

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = showQrSummary(
                amountSatoshis = prepared.amountSatoshis,
                recipient = prepared.recipient,
                feeSatoshis = prepared.feeSatoshis,
            ),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // The symbol takes every pixel the summary above and the caption below
        // leave, and stays square inside what is left: width or height,
        // whichever binds, is what it is measured against.
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            QrImage(payload = payload, modifier = Modifier.aspectRatio(1f))
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (encoder.isSingleFrame) {
                "Scan this with your SeedSigner to sign the transaction."
            } else {
                "Keep this in view of your SeedSigner \u2014 ${frames.size} frames."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Text("Scan QR")
        }
    }
}

/**
 * Read the signed PSBT back and finalize it the moment it is whole.
 *
 * The assembler is passed in (not created here) so its state survives
 * recomposition of the panel: a fountain code is stateful across frames, and
 * throwing the decoder away on every redraw is the bug this avoids. A frame the
 * assembler refuses or a PSBT that will not finalize sets a message and resets
 * the assembler, so "scan again" is just the user pointing the camera again.
 */
@Composable
private fun ScanStep(
    state: SignerChainState,
    assembler: UrPsbtAssembler,
    progress: Int,
    message: String?,
    onProgress: (Int) -> Unit,
    onMessage: (String?) -> Unit,
    onComplete: (ByteArray) -> Unit,
    onCancel: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Scan the signed transaction",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = state.signer.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    AnimatedUrScannerPanel(
        progress = progress,
        message = message,
        onFrame = { payload ->
            when (val outcome = assembler.receive(payload)) {
                is UrPsbtOutcome.Progress -> onProgress(outcome.percent)

                is UrPsbtOutcome.Rejected -> onMessage(outcome.message)

                is UrPsbtOutcome.Complete -> {
                    // Finalize immediately. The sighash check lives in
                    // finalizePsbt and is the one thing this step must not skip.
                    try {
                        val raw = finalizePsbt(parsePsbt(outcome.psbtBytes))
                        onComplete(raw)
                    } catch (refusal: PsbtException) {
                        onMessage(refusal.message)
                        assembler.reset()
                        onProgress(0)
                    } catch (refusal: IllegalArgumentException) {
                        onMessage("That signed transaction could not be read: ${refusal.message}")
                        assembler.reset()
                        onProgress(0)
                    }
                }
            }
        },
        onCancel = onCancel,
        onTimeout = {
            onMessage("No signed transaction arrived \u2014 point the camera at your signer and try again.")
        },
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = "Your signer shows the signed PSBT as a QR. Hold the camera steady " +
            "until the whole transaction has been read.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The last step: what is about to be sent, the txid it will have, and one
 * button that sends it.
 *
 * The button carries its own spinner, and a broadcast that the server refuses
 * shows the server's own reason with the button still there to retry — a
 * rejection is a fact about the transaction, not a reason to rebuild it.
 */
@Composable
private fun BroadcastStep(
    state: SignerChainState,
    prepared: PreparedSend,
    signed: SignedSend,
    broadcasting: Boolean,
    error: String?,
    txid: String?,
    onBroadcast: () -> Unit,
) {
    Text(
        text = "Ready to broadcast",
        style = MaterialTheme.typography.titleMedium,
    )

    Spacer(modifier = Modifier.height(4.dp))

    AddressEdgeHighlight(prepared.recipient)
    Text(
        text = "Amount: ${formatSats(prepared.amountSatoshis)} sats",
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
    )
    Text(
        text = "Fee: ${formatSats(prepared.feeSatoshis)} sats",
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
    )
    Text(
        text = "Transaction id",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = signed.txid,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )

    Spacer(modifier = Modifier.height(16.dp))

    if (txid != null) {
        Text(
            text = "Broadcast \u2713",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = txid,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "The server accepted it. It will show as unconfirmed in ${state.signer.name}'s " +
                "history until the next block.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = onBroadcast,
            enabled = !broadcasting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (broadcasting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Broadcasting\u2026")
            } else {
                Text(if (error == null) "Broadcast" else "Retry broadcast")
            }
        }
    }
}

/** The recipient's head and tail in the accent colour, the middle plain. */
@Composable
private fun AddressEdgeHighlight(address: String) {
    val (first, middle, last) = splitAddressEdges(address)
    Text(
        text = buildAnnotatedString {
            withStyle(
                SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary),
            ) {
                append(first)
            }
            append(middle)
            withStyle(
                SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary),
            ) {
                append(last)
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The QR as it is drawn: a white panel with the symbol filling it.
 *
 * The panel is the size it is given — the caller keeps it square — and the
 * symbol scales into whatever that is, so a large panel is a large symbol
 * rather than a fixed one with dead space around it. The white [Surface] is
 * what is left of the quiet zone: a QR module touching the dark background
 * gives a camera nothing to find the finder patterns against, so a thin white
 * surround stays even though ZXing already pads the matrix it returns. The
 * bitmap is one pixel per module with `FilterQuality.None`, because a QR is
 * binary data and smoothing its modules is the blur a scanner cannot read.
 */
@Composable
private fun QrImage(payload: String, modifier: Modifier = Modifier) {
    val bitmap = remember(payload) { qrBitmap(renderQr(payload)) }
    Surface(
        color = Color.White,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Unsigned transaction QR code",
            filterQuality = FilterQuality.None,
            modifier = Modifier
                .fillMaxSize()
                .padding(QR_QUIET_SURROUND_DP.dp),
        )
    }
}

/**
 * The one line [ShowQrStep] shows above the QR: the amount, the recipient and
 * the fee — the three numbers a user checks against the signer's own screen.
 *
 * The recipient is not written out in full: the symbol beside this line is what
 * is being verified, and an edge-shortened form — the first and last eight
 * characters — is exactly what a person compares across two screens. It is the
 * same head-and-tail the rest of the wallet uses ([shortenedMiddle]). Amount and
 * fee are grouped the way every other satoshi figure in the wallet is
 * ([formatSats]), so the numbers read as the same kind of thing wherever they
 * appear.
 */
internal fun showQrSummary(
    amountSatoshis: Long,
    recipient: String,
    feeSatoshis: Long,
): String = "${formatSats(amountSatoshis)} sats \u2192 " +
    shortenedMiddle(recipient, head = RECIPIENT_EDGE_CHARS, tail = RECIPIENT_EDGE_CHARS) +
    " \u00b7 fee ${formatSats(feeSatoshis)} sats"

/** The [BitMatrix] as pixels: black modules on white, one pixel each. */
private fun qrBitmap(matrix: BitMatrix): Bitmap {
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) BLACK else ANDROID_WHITE)
        }
    }
    return bitmap
}

/** Opaque black — the dark module. */
private const val BLACK = 0xFF000000.toInt()

/** Opaque white — the light module. */
private const val ANDROID_WHITE = 0xFFFFFFFF.toInt()

/** The most a label may hold — a note to yourself, not a document. */
private const val LABEL_MAX_CHARS = 100

/** How long each animated frame stays on screen, in milliseconds. */
private const val FRAME_INTERVAL_MILLIS = 250L

/** Characters kept at each end of the recipient on the SHOW_QR summary line. */
private const val RECIPIENT_EDGE_CHARS = 8

/**
 * The white surround between the QR panel's edge and the symbol, in dp.
 *
 * ZXing already pads the matrix with a four-module quiet zone; this is the
 * panel's own margin against the dark theme, kept thin so the symbol — not the
 * border — is what the camera sees most of.
 */
private const val QR_QUIET_SURROUND_DP = 8

/**
 * Builds everything a signer needs for this send: the plan, the unsigned
 * transaction, and the PSBT that carries them.
 *
 * This is where the wallet's *own* metadata is turned into the PSBT's: the
 * account path and master fingerprint from the signer record become each
 * input's BIP32 derivation (the account path, then the coin's chain and index),
 * and each coin's public key — derived here from the account key, never from
 * the phone's storage — becomes both the witness_utxo's script and the
 * derivation's key.
 *
 * The change output, when the plan has one, is labelled the same way at
 * `account/<change chain>/<change index>`: the signer derives that key from its
 * own seed and checks it against the output, which is how it recognises the
 * change as its own rather than as a payment away.
 *
 * @throws PsbtException when the wallet is not signing-ready or the change
 *   address cannot be resolved — both are pre-existing conditions, not user error.
 */
private fun prepareSend(
    state: SignerChainState,
    selected: List<ScannedUtxo>,
    recipient: String,
    amountSatoshis: Long,
    feeRateSatPerVb: Long,
): PreparedSend {
    val accountKey = state.accountKey
        ?: throw PsbtException("this wallet's key could not be read")
    val type = state.signer.type as? SignerType.WatchOnlyXpub
        ?: throw PsbtException("this wallet has no account key")
    val fingerprintHex = type.masterFingerprint
        ?: throw PsbtException("this wallet has no master fingerprint")
    val pathText = type.derivationPath
        ?: throw PsbtException("this wallet has no derivation path")

    val accountPath = parseDerivationPath(pathText)
    val fingerprint = masterFingerprintBytes(fingerprintHex)

    val change = resolveChangeAddress(state)
        ?: throw PsbtException("no change address is available for this wallet")

    val recipientScript = scriptPubKeyFromAddress(recipient)
    val changeScript = scriptPubKeyFromAddress(change.address)

    // Derive each selected coin's public key once, and keep the absolute path it
    // was derived at — the two things a signer needs to check its own signature.
    val keys = selected.map { scanned ->
        val key = accountKey
            .deriveChild(scanned.chain.index)
            .deriveChild(scanned.addressIndex.toLong())
        val path = accountPath + listOf(scanned.chain.index, scanned.addressIndex.toLong())
        key.publicKey to path
    }

    val sendInputs = selected.mapIndexed { index, scanned ->
        SendInput(
            txidHex = scanned.utxo.txHash,
            vout = scanned.utxo.txPos,
            satoshis = scanned.utxo.satoshis,
            scriptPubKey = p2wpkhScriptPubKey(keys[index].first),
        )
    }

    val plan = planSend(
        inputs = sendInputs,
        amountSatoshis = amountSatoshis,
        feeRateSatPerVb = feeRateSatPerVb,
        recipientScriptPubKey = recipientScript,
        changeScriptPubKey = changeScript,
    )

    val unsignedTx = buildUnsignedTransaction(plan)

    // Where the change output landed, and the key that pays it: account/<change
    // chain>/<change index>. Stamping this on the output's map is what lets a
    // signer recognise the change as its own — otherwise SeedSigner can only warn
    // that the whole UTXO is being sent away.
    val changeOutputIndex = if (plan.hasChange) {
        val changeScriptHex = changeScript.toHexString()
        plan.outputs
            .indexOfFirst { it.scriptPubKeyHex == changeScriptHex }
            .takeIf { it >= 0 }
            ?: throw PsbtException("the change output could not be located in the plan")
    } else {
        null
    }
    val changeDerivation = changeOutputIndex?.let {
        val changeKey = accountKey
            .deriveChild(AddressChain.CHANGE.index)
            .deriveChild(change.index.toLong())
        Bip32Derivation(
            masterFingerprint = fingerprint,
            path = accountPath + listOf(AddressChain.CHANGE.index, change.index.toLong()),
            pubKey = changeKey.publicKey,
        )
    }

    val psbt = buildUnsignedPsbt(
        unsignedTxBytes = unsignedTx,
        inputs = plan.inputs.mapIndexed { index, input ->
            PsbtInput(
                witnessUtxoScriptPubKey = input.scriptPubKey,
                witnessUtxoSatoshis = input.satoshis,
                masterFingerprint = fingerprint,
                derivationPath = keys[index].second,
                pubKey = keys[index].first,
            )
        },
        change = changeDerivation,
        changeOutputIndex = changeOutputIndex,
    )

    return PreparedSend(
        psbtBytes = psbt,
        recipient = recipient,
        amountSatoshis = plan.amountSatoshis,
        feeSatoshis = plan.feeSatoshis,
        changeSatoshis = plan.changeSatoshis,
    )
}

/** A change address, with the index it was derived at — the PSBT needs both. */
private class ChangeTarget(val address: String, val index: Int)

/**
 * The change address: the **first unused** address of the account's change
 * chain in the cached snapshot, so change comes back to an address the account
 * is watching and has not spent from.
 *
 * When the scan knows of no unused change address — nothing cached yet, or every
 * one the scan visited is used — this falls back to deriving change index 0 from
 * the account key, which costs nothing and never waits on the network.
 *
 * The index travels with the address because the PSBT's change output must be
 * labelled with the exact derivation path the address was built from.
 */
private fun resolveChangeAddress(state: SignerChainState): ChangeTarget? {
    val firstUnused = state.snapshot
        ?.change
        ?.firstOrNull { !it.used }
    if (firstUnused != null) return ChangeTarget(firstUnused.address, firstUnused.index)
    return state.scanner?.derive(AddressChain.CHANGE, 0)?.let { ChangeTarget(it.address, it.index) }
}

/** The estimated fee for [inputCount] inputs at [feeRateSatPerVb], in satoshis. */
private fun estimateFeeSatoshis(inputCount: Int, feeRateSatPerVb: Long): Long =
    com.xbtwallet.app.chain.estimateSendVsize(inputCount).toLong() * feeRateSatPerVb

/** A byte array as lowercase hex — what the broadcast RPC takes. */
private fun ByteArray.toHexString(): String {
    val hex = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    forEach { byte ->
        val value = byte.toInt() and 0xFF
        out.append(hex[value ushr 4]).append(hex[value and 0x0F])
    }
    return out.toString()
}

/**
 * A broadcast failure as a sentence.
 *
 * The server's own reason is the useful part — "bad-txns-inputs-missingorspent",
 * "dust", "non-mandatory-script-verify-flag" — and it must reach the user
 * unmangled. The wallet's own plumbing failures are prefixed so a reader can
 * tell a *rejected transaction* from a *connection that did not answer*.
 */
private fun describeBroadcastFailure(failure: Exception): String = when (failure) {
    is com.xbtwallet.app.electrum.ElectrumException ->
        "The server refused the transaction: ${failure.message}"

    is java.io.IOException ->
        "Could not reach the server \u2014 ${failure.message ?: "the connection failed"}. Retry when it is back."

    else -> "Broadcast failed: ${failure.message ?: failure::class.java.simpleName}"
}
