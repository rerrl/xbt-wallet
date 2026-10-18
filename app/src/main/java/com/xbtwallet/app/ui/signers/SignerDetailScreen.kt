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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.PlaceholderPrices
import com.xbtwallet.app.chain.AccountSnapshot
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerType
import kotlinx.coroutines.launch
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
 *  3. the four actions: **UTXOs** and **Addresses** open their own screens;
 *     **Send** and **Receive** are shown disabled because they need the
 *     air-gapped signing flow that does not exist yet,
 *  4. the **Transactions** list, under the account key: the same scan's
 *     `get_history` answers, aggregated and deduped (see [TransactionsSection]).
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                TypeBadge(label = signer.typeLabel)
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
                // Disabled, not hidden: the two things a wallet does with coins
                // are visible so the screen reads as a wallet, and greyed so it
                // never promises something it cannot do.
                Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                    Text("Send")
                }
                Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                    Text("Receive")
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Send and Receive come with the air-gapped signing flow \u2014 coming soon.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // The account key, quiet and monospaced at the foot of the screen:
            // it is the thing this signer *is*, but it is not an action.
            val xpub = (signer.type as? SignerType.WatchOnlyXpub)?.xpub
            if (xpub != null) {
                Text(
                    text = "Account key",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = xpub,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 4. Transactions: what the scan's own get_history answers add up to,
            // underneath the account key. Same scan, same server, no extra RPC.
            TransactionsSection(state)
        }
    }
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
                ChainPhase.READY -> LoadedBalance(state.snapshot ?: AccountSnapshot.EMPTY)
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
            Text(
                text = if (checked > 0) {
                    "checked $checked addresses on ${state.serverLabel.orEmpty()}"
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
 * No endpoint saved. Not an error — a setup step — so the card invites the user
 * to do it rather than showing a red failure.
 */
@Composable
private fun NoServerBalance() {
    Text("Connect a server", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Balance comes from your own Electrum server. Add one under Server on the " +
            "home screen and this signer's balance appears here \u2014 the phone never asks " +
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
