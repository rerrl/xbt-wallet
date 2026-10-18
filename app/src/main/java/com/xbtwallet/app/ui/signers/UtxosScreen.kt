package com.xbtwallet.app.ui.signers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.chain.AccountSnapshot
import com.xbtwallet.app.chain.ScannedUtxo

/**
 * The UTXO list for one signer: what `blockchain.scripthash.listunspent` said
 * about each used address, flattened into one plain scrollable list.
 *
 * Every row is monospaced and says the four things an output has: its amount,
 * where it sits on chain (`txid:vout`, both ends kept, middle elided), which of
 * this account's addresses holds it, and at what height it confirmed — or that
 * it has not. That is the whole screen: no coin control, no freezing, no
 * selection. Those are v0.2.0.
 *
 * The data comes from [state], the same scan the detail screen's balance is
 * built from, so the list and the number above it can never disagree.
 */
@Composable
fun UtxosScreen(
    state: SignerChainState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("\u2190 Back") }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "UTXOs",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = state.signer.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            ScannedFromBanner(state)

            Spacer(modifier = Modifier.height(12.dp))

            ScanStateBody(state, modifier = Modifier.weight(1f)) { snapshot ->
                UtxoList(snapshot)
            }
        }
    }
}

@Composable
private fun UtxoList(snapshot: AccountSnapshot, modifier: Modifier = Modifier) {
    if (snapshot.utxos.isEmpty()) {
        Column(modifier = modifier.fillMaxWidth()) {
            Text("No UTXOs.", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "This account is empty on this server: none of its scanned addresses " +
                    "holds a spendable output.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(snapshot.utxos) { scanned -> UtxoRow(scanned) }
    }
}

/**
 * One output: amount on top, provenance underneath — the same "what, then
 * where from" shape a block explorer uses, in monospace.
 */
@Composable
private fun UtxoRow(scanned: ScannedUtxo) {
    val utxo = scanned.utxo
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${formatSats(utxo.satoshis)} sats",
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = buildString {
                append(shortenedMiddle(utxo.txHash)).append(':').append(utxo.txPos)
                append("   ").append(scanned.chain.label.lowercase()).append(" #").append(scanned.addressIndex)
                append("   ").append(if (utxo.confirmed) "height ${formatSats(utxo.height.toLong())}" else "unconfirmed")
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
