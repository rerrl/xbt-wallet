package com.xbtwallet.app.ui.signers

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.PlaceholderPrices
import com.xbtwallet.app.chain.AccountSnapshot
import com.xbtwallet.app.chain.AddressChain
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerType
import com.xbtwallet.app.ui.receive.resolveReceiveIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/**
 * One signer's own screen — the place every per-signer action lives.
 *
 * Top to bottom, as the card specifies:
 *  1. the signer's name and its watch-only type badge,
 *  2. the **balance**, live from the user's own Electrum server: this xpub's
 *     confirmed *and* unconfirmed satoshis, with a loading state while the scan
 *     runs and an honest error state when there is no server or it did not
 *     answer ("Connect a server"),
 *  3. the four actions: **UTXOs**, **Addresses** and **Receive** open their own
 *     screens; **Send** is shown disabled because it needs the air-gapped
 *     signing flow that does not exist yet,
 *  4. the **Transactions** list: the same scan's `get_history` answers,
 *     aggregated and deduped (see [TransactionsSection]), below the button that
 *     reveals the account key on demand.
 *
 * The screen owns no chain state of its own: [state] is created and driven by
 * the navigation layer so that the balance is scanned once per signer visit and
 * is the same number the UTXO and Addresses screens show.
 *
 * [signer] being non-null is a precondition here; a signer that no longer
 * resolves is sent Home by the navigation layer before this is composed.
 */
@Composable
fun SignerDetailScreen(
    signer: Signer,
    state: SignerChainState,
    onOpenUtxos: () -> Unit,
    onOpenAddresses: () -> Unit,
    onOpenReceive: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // --- the signer's live subscription --------------------------------------
    //
    // This screen is the subscription's lifetime, by design: it opens when the
    // signer's detail appears and closes the moment that detail goes away —
    // navigating to UTXOs, Addresses or Receive, back Home, or to another
    // signer. Reopening starts a fresh connection, and the gap it left is caught
    // by the manager's status-hash diff (the same path a live notification takes),
    // so nothing is missed by not running in the background.
    //
    // The disposer runs after the composition that seeded the cached snapshot, so
    // there is nothing to wait for: the balance is already on screen, the rescan
    // is already running beside it, and neither is waiting on this. Opening the
    // subscription is best-effort — `startSubscriptions` returns immediately and
    // silently does nothing when there is no server, no key, or no connection to
    // be had, leaving the pull path exactly as it was.
    DisposableEffect(state) {
        state.startSubscriptions()
        onDispose { state.stopSubscriptions() }
    }

    // True while the receive address is being resolved. It disables the Receive
    // button and shows a spinner inside it, so the navigation happens only with
    // the address already in hand — the receive screen then never has to show a
    // loading state of its own.
    var resolvingReceive by remember { mutableStateOf(false) }
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                // Small phones and large font scales both overflow a fixed
                // column; scrolling keeps every control reachable.
                .verticalScroll(rememberScrollState()),
        ) {
            // 1. Header: identity and type.
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("\u2190 Back") }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = signer.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                // The screen's one and only "syncing" signal: a compact spinner
                // to the left of the type badge, shown exactly while a rescan
                // runs. The balance and transactions sections no longer carry
                // their own indicators.
                if (state.isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp).height(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                // The type tag is chosen by the signer's origin — the same rule
                // the list row uses — and sits to the right of the sync spinner.
                val tag = signerTagFor(signer)
                TypeBadge(label = tag.label, accent = tag.accent)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Balance.
            BalanceCard(state)

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Actions.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onOpenUtxos, modifier = Modifier.weight(1f)) {
                    Text("UTXOs")
                }
                OutlinedButton(onClick = onOpenAddresses, modifier = Modifier.weight(1f)) {
                    Text("Addresses")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Send is disabled, not hidden: the thing a wallet does with
                // coins stays visible so the screen reads as a wallet, and greyed
                // so it never promises the air-gapped signing flow it does not
                // have. Receive needs no signing, so it is live — it resolves the
                // first unused address *here*, then opens the QR for it.
                Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                    Text("Send")
                }
                Button(
                    onClick = {
                        if (resolvingReceive) return@Button
                        resolvingReceive = true
                        scope.launch {
                            try {
                                val scanner = state.scanner
                                if (scanner == null) return@launch
                                val scanned = state.snapshot?.addresses(AddressChain.RECEIVE) ?: emptyList()
                                val (index, _) = resolveReceiveIndex(scanned)
                                // Key derivation is a few elliptic-curve
                                // operations: cheap, but not a frame's work.
                                val address = withContext(Dispatchers.Default) {
                                    scanner.derive(AddressChain.RECEIVE, index).address
                                }
                                onOpenReceive(address)
                            } catch (cancellation: CancellationException) {
                                // Leaving the screen cancels the launch; the
                                // `finally` still clears the busy flag so the
                                // button is never left wedged.
                                throw cancellation
                            } catch (error: Exception) {
                                // Nothing more to do here: a sync failure has its
                                // own surfaces (the balance card's Retry). The
                                // button simply becomes usable again.
                            } finally {
                                resolvingReceive = false
                            }
                        }
                    },
                    enabled = !resolvingReceive,
                    modifier = Modifier.weight(1f),
                ) {
                    if (resolvingReceive) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp).height(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Receive")
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Send comes with the air-gapped signing flow \u2014 coming soon.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // The account key is quiet by design — it is the thing this signer
            // *is*, but it is not an action and it is long. It lives behind its
            // own button and dialog so the body stays readable; tap to read or
            // copy it.
            val watchOnly = signer.type as? SignerType.WatchOnlyXpub
            val xpub = watchOnly?.xpub
            var showAccountKey by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { showAccountKey = true },
                enabled = xpub != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Account key")
            }

            if (showAccountKey && xpub != null) {
                AccountKeyDialog(
                    xpub = xpub,
                    masterFingerprint = watchOnly?.masterFingerprint,
                    derivationPath = watchOnly?.derivationPath,
                    onDismiss = { showAccountKey = false },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 4. Transactions: what the scan's own get_history answers add up to,
            // underneath the account key. Same scan, same server, no extra RPC.
            TransactionsSection(state)
        }
    }
}

/**
 * The account key, shown on demand: the full xpub in monospace, selectable so a
 * user can copy it by hand, with a Copy button that puts it on the clipboard.
 *
 * When the signer was imported from a signing device, the master fingerprint and
 * derivation path that came with that BC-UR export are shown underneath the key
 * — they are what the later PSBT signing flow needs to name the signer's device,
 * and this dialog is the only place the user can check them. A watch-only text-QR
 * import carries no such metadata (it is discarded on import and on read), and
 * then nothing is added.
 *
 * Dismissal follows the platform default — [onDismissRequest] fires for an
 * outside tap and for Back, and both simply close the dialog. Nothing here
 * swallows those gestures.
 */
@Composable
private fun AccountKeyDialog(
    xpub: String,
    masterFingerprint: String?,
    derivationPath: String?,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account key") },
        text = {
            Column {
                SelectionContainer {
                    Text(
                        text = xpub,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                masterFingerprint?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Master fingerprint: $it",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                derivationPath?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Derivation: m/$it",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(xpub))
                    onDismiss()
                },
            ) {
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/**
 * The balance panel: one card whose content is a function of [state]'s phase.
 *
 * Splitting on the phase rather than on "is the snapshot null" is deliberate:
 * a snapshot that has never loaded and a snapshot from a server that has gone
 * away are different things to a user, and only one of them is worth a Retry.
 */
@Composable
private fun BalanceCard(state: SignerChainState, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (state.phase) {
                ChainPhase.IDLE, ChainPhase.LOADING -> LoadingBalance(state)
                ChainPhase.NO_SERVER -> NoServerBalance()
                ChainPhase.ERROR -> FailedBalance(state)
                ChainPhase.READY -> {
                    LoadedBalance(state.snapshot ?: AccountSnapshot.EMPTY)
                    // The refresh controls sit under the numbers, never instead
                    // of them: a rescan updates the balance in place.
                    BalanceRefreshControl(state)
                }
            }
        }
    }
}

/**
 * The loading state: proof that a scan is running.
 *
 * The address counter is the honest part — the scan's cost is proportional to
 * how many addresses the account has used, and showing the count makes a long
 * scan on a busy account legible instead of suspicious.
 */
@Composable
private fun LoadingBalance(state: SignerChainState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text("Loading balance\u2026", style = MaterialTheme.typography.titleMedium)
            val checked = state.scannedAddressCount
            // No host here: this is the pre-refresh loading line, and which
            // server the user chose is never rendered.
            Text(
                text = if (checked > 0) {
                    "checked $checked addresses"
                } else {
                    "asking your Electrum server"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The refresh row under a loaded balance.
 *
 * A cached snapshot is what the screen opens on, so its refresh is a background
 * rescan rather than a load: while it runs, the header's single spinner says so
 * (this card no longer carries its own indicator), a failed refresh says the
 * numbers are the last synced ones and offers a Retry, and an idle card offers a
 * plain Refresh. The cached values stay on screen in every one of those cases —
 * a refresh never blanks a balance.
 */
@Composable
private fun BalanceRefreshControl(state: SignerChainState) {
    val scope = rememberCoroutineScope()
    Spacer(modifier = Modifier.height(10.dp))
    when {
        state.syncFailed -> {
            Text(
                text = "Couldn't refresh \u2014 showing the last synced values.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { scope.launch { state.reload() } }) { Text("Retry") }
        }
        else -> TextButton(onClick = { scope.launch { state.reload() } }) { Text("Refresh") }
    }
}

/**
 * No endpoint saved. Not an error — a setup step — so the card invites the user
 * to do it rather than showing a red failure.
 */
@Composable
private fun NoServerBalance() {
    Text("Connect a server", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Balance comes from your own Electrum server. Add one under Server on the " +
            "home screen and this wallet's balance appears here \u2014 the phone never asks " +
            "anyone else.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The server was configured but the scan failed. Say what happened, offer a retry. */
@Composable
private fun FailedBalance(state: SignerChainState) {
    val scope = rememberCoroutineScope()
    Text("Balance unavailable", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = state.errorMessage ?: "The scan failed.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = { scope.launch { state.reload() } }) { Text("Retry") }
}

/**
 * The answer: sats first, then the same balance dressed in the two placeholder
 * fiat rates — each line explicitly marked, because a number with a `$` in front
 * of it is trusted by default and must not be.
 */
@Composable
private fun LoadedBalance(snapshot: AccountSnapshot) {
    val coins: BigDecimal = BigDecimal.valueOf(snapshot.satoshis, COIN_DECIMALS).setScale(COIN_DECIMALS)

    Text(
        text = "${formatSats(snapshot.satoshis)} sats",
        style = MaterialTheme.typography.headlineSmall,
        fontFamily = FontFamily.Monospace,
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = "${formatCoins(snapshot.satoshis)} XBT",
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
    )

    // The confirmed / unconfirmed split only earns a line when there is
    // something unconfirmed to explain.
    val unconfirmed = snapshot.balance.unconfirmedSatoshis
    if (unconfirmed != 0L) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "includes ${formatSats(unconfirmed)} sats not yet confirmed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    // XBT priced at the placeholder XBT rate...
    Text(
        text = "\u2248 ${formatUsd(coins, PlaceholderPrices.USD_PER_XBT)} at XBT price " +
            "${formatUsd(BigDecimal.ONE, PlaceholderPrices.USD_PER_XBT)} \u2014 placeholder rate",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // ...and the same balance, in the same sat unit, priced at the BTC rate.
    Text(
        text = "worth ${formatCoins(snapshot.satoshis)} BTC \u2248 " +
            "${formatUsd(coins, PlaceholderPrices.USD_PER_BTC)} at BTC price " +
            "${formatUsd(BigDecimal.ONE, PlaceholderPrices.USD_PER_BTC)} \u2014 placeholder rate",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
