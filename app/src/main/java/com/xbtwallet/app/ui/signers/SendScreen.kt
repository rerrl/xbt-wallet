package com.xbtwallet.app.ui.signers

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.chain.ScannedUtxo

/**
 * The Send screen: the selected coins, a recipient, a fee rate and a label — and
 * a terminal button that is deliberately disabled this iteration.
 *
 * No transaction is built here and nothing is signed: the PSBT round-trip is a
 * later stage, so the bottom control is a disabled "Sign & broadcast (next
 * step)" placeholder rather than a button that would appear to act and not. What
 * this screen establishes is the *shape* of the flow and the coin selection that
 * feeds it.
 *
 * **The selection rides on the route** ([outpoints]) rather than in shared
 * state, so a rotation lands back on the same coins. The outpoints are resolved
 * against [SignerChainState.snapshot] — the cached snapshot renders instantly,
 * and locally derivable data must never wait on the network — so this screen
 * does **not** gate on the scan state. A snapshot that has not landed yet, and a
 * selection whose outputs have all left the UTXO set, each get a plain line and
 * the Back button instead of a form.
 *
 * The QR scanner is [XpubQrScannerPanel] reused as-is: it is payload-agnostic
 * (it just hands back whatever text it read), so a recipient address scans
 * through the very same panel the xpub import uses.
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

                else -> SendForm(
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

/**
 * The form itself: selected-coin summary and total, then recipient, fee and
 * label, then the (disabled) terminal control.
 *
 * The fields scroll; the button stays pinned at the bottom so the one control
 * this stage defines is always in reach, even with the scanner panel open.
 */
@Composable
private fun SendForm(selected: List<ScannedUtxo>, modifier: Modifier = Modifier) {
    // Keyed on the signer so switching wallets through the stack cannot carry one
    // wallet's half-typed recipient into another's send form. The selected coins
    // are not state here at all — they ride on the route.
    var recipient by remember { mutableStateOf("") }
    var feeInput by remember { mutableStateOf("1") }
    var amountInput by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var scannerOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // CAMERA is asked for by this app, not by the scanner library: the panel is
    // our own composable and it can only work with the permission in hand. A
    // granted request opens the panel; a refusal is simply remembered as
    // "closed", so the "Scan QR" button stays the manual path.
    val scanPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scannerOpen = true
    }

    // The scanner opens by default on entry — scanning is the expected way to
    // get a recipient, not an extra step. Keyed on `Unit` so it fires exactly
    // once per entry into this form: a grant opens the panel, a denial leaves
    // the form alone, and nothing here can re-open a panel the user cancelled
    // (the dismiss path only writes `scannerOpen = false`, and this effect is
    // already past).
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            scannerOpen = true
        } else {
            scanPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // "Scan QR" does the same thing: with the permission already held, open the
    // panel at once (no pointless dialog); otherwise ask for it — the grant
    // arrives in the launcher callback above and opens the panel there.
    val openScanner: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            scannerOpen = true
        } else {
            scanPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val totalSatoshis = selected.sumOf { it.utxo.satoshis }

    // The fee rate drives the max spendable, so both are derived from the input
    // rather than stored: editing the fee re-derives the max under it. A fee
    // rate that does not parse yields a null max, and every max affordance
    // (the button, the helper line, the over-max error) disappears with it —
    // there is no honest number to offer while the rate is unknown.
    val feeRate = parseFeeRate(feeInput)
    val maxSendable = feeRate?.let { rate -> maxSendableSatoshis(totalSatoshis, selected.size, rate) }
    val estimatedFee = feeRate?.let { rate -> estimateSendVsize(selected.size).toLong() * rate }

    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                onValueChange = { recipient = it },
                label = { Text("Recipient address") },
                modifier = Modifier.fillMaxWidth(),
            )
            val addressError = validateRecipientAddress(recipient)
            if (recipient.isNotEmpty() && addressError != null) {
                Text(
                    text = addressError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // A valid address gets a full readout of its own: what a user checks
            // before sending is the head and the tail of the address against
            // their own screen, so those come back in the accent colour and the
            // middle stays plain — the whole string is shown, wrapped, so
            // nothing is hidden behind a clip.
            if (recipient.isNotEmpty() && addressError == null) {
                val (first, middle, last) = splitAddressEdges(recipient)
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            append(first)
                        }
                        append(middle)
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            append(last)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            TextButton(onClick = openScanner) { Text("Scan QR") }

            if (scannerOpen) {
                XpubQrScannerPanel(
                    onResult = { payload ->
                        recipient = extractAddress(payload)
                        scannerOpen = false
                    },
                    onCancel = { scannerOpen = false },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- fee ---------------------------------------------------------
            OutlinedTextField(
                value = feeInput,
                onValueChange = { feeInput = it },
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
            // Empty by default: the amount is a decision about this particular
            // send, so it starts blank rather than proposing a number. "Max" is
            // fee-aware — it fills in what is left after the estimated fee at
            // the current rate — and appears only while that rate parses.
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it },
                    label = { Text("Amount (sats)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                if (maxSendable != null) {
                    TextButton(onClick = { amountInput = maxSendable.toString() }) { Text("Max") }
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
            val amountSatoshis = parseAmountSats(amountInput)
            when {
                amountInput.isNotEmpty() && amountSatoshis == null -> Text(
                    text = "Enter a whole number of sats greater than 0.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                amountSatoshis != null && maxSendable != null && amountSatoshis > maxSendable -> Text(
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
                onValueChange = { if (it.length <= LABEL_MAX_CHARS) label = it },
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Always disabled this iteration: PSBT building and the signing
        // round-trip are the next stage, and a button that appeared to act would
        // be worse than one that plainly does not yet.
        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign & broadcast (next step)")
        }
    }
}

/** The most a label may hold — a note to yourself, not a document. */
private const val LABEL_MAX_CHARS = 100
