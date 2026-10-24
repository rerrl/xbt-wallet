package com.xbtwallet.app.ui.signers

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.chain.AccountSnapshot
import com.xbtwallet.app.chain.ScannedUtxo
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerType

/**
 * The one hint shown wherever a watch-only wallet's send path is disabled.
 *
 * A watch-only wallet holds public keys only, so it can neither spend nor
 * prepare the transaction that would spend: there is nothing on this phone that
 * could sign for it, and a `[fingerprint/path]xpub` re-import would not change
 * that (it is the same watch-only key). The only way to spend from this account
 * is to add it to a device that *does* hold the keys — a signing device — so the
 * hint says exactly that instead of pointing at a flag the user cannot flip.
 *
 * Shared by [UtxosScreen]'s selection bar and the Send form's terminal control,
 * and pinned by `AddSignerCopyTest`, so the two screens cannot drift apart.
 */
internal const val WATCH_ONLY_CANNOT_SPEND_HINT =
    "A watch-only wallet holds public keys only -- it cannot spend. To send " +
        "from this account, add it as a signing device or hot wallet instead."

/**
 * The one hint shown when a **hot wallet**'s send path is disabled here.
 *
 * A hot wallet's seed *is* on this phone, so it is signing-ready by
 * construction — but the only send this screen can start is the air-gapped
 * SeedSigner QR dance, which no device can ever sign for a hot wallet (the seed
 * is not in a device). Internal signing is a later phase, so the bar stays
 * disabled under this hint rather than walking the user into a flow that cannot
 * finish.
 *
 * Pinned verbatim by `HotWalletCopyTest`, so the wording cannot drift.
 */
internal const val HOT_WALLET_CANNOT_SPEND_HINT =
    "Sending from a hot wallet comes with the internal signing flow \u2014 coming soon."

/**
 * Whether the "Send selected" bar is live for [signer] — the whole rule, as a
 * pure function so a JVM test can pin it without a UI.
 *
 * Signing-readiness is necessary but not sufficient: a hot wallet is
 * signing-ready and still must not enter this screen's QR-dance send.
 */
internal fun sendSelectedEnabled(signer: Signer): Boolean =
    signer.signingReady && signer.type !is SignerType.HotSeed

/**
 * The hint under the bar for [signer], or null when the bar is live.
 *
 * A hot wallet gets its own sentence — the watch-only one would be wrong (it
 * *can* sign, just not through this flow) — and a watch-only wallet that cannot
 * sign keeps [WATCH_ONLY_CANNOT_SPEND_HINT].
 */
internal fun sendSelectedHint(signer: Signer): String? = when {
    signer.type is SignerType.HotSeed -> HOT_WALLET_CANNOT_SPEND_HINT
    signer.signingReady -> null
    else -> WATCH_ONLY_CANNOT_SPEND_HINT
}

/**
 * The UTXO list for one signer: what `blockchain.scripthash.listunspent` said
 * about each used address, flattened into one plain scrollable list the user
 * selects coins from.
 *
 * Every row is monospaced and says the four things an output has: its amount,
 * where it sits on chain (`txid:vout`, both ends kept, middle elided), which of
 * this account's addresses holds it, and at what height it confirmed — or that
 * it has not. The row leads with a checkbox and the whole row toggles it, so a
 * multi-select is a tap per coin rather than a tap on a small target.
 *
 * **This is where a send starts.** With a non-empty selection a bottom bar
 * appears carrying the count and sum and a "Send selected" button; tapping it
 * hands the selected outpoints (in list order) up through [onSendSelected]. The
 * button is enabled only for a **signing-ready** wallet; a watch-only wallet has
 * no keys on this phone to sign with, so it stays disabled under
 * [WATCH_ONLY_CANNOT_SPEND_HINT], which points the user at a signing device
 * rather than at a re-import that would change nothing. No transaction is built
 * here; coin *control* (freezing,
 * labels, ordering) is still a later stage.
 *
 * **A resync prunes the selection.** An output another spender has consumed
 * silently leaves both the list and the selection rather than being carried as a
 * coin that no longer exists.
 *
 * The data comes from [state], the same scan the detail screen's balance is
 * built from, so the list and the number above it can never disagree.
 */
@Composable
fun UtxosScreen(
    state: SignerChainState,
    onSendSelected: (List<String>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the signer id so walking between signers cannot carry one
    // wallet's selection onto another's coins.
    var selected by remember(state.signer.id) { mutableStateOf(setOf<String>()) }

    // Drop any selected outpoint the latest snapshot no longer holds. Keyed on
    // the snapshot so it re-runs on every resync: an output another spender used
    // must leave the selection (and would otherwise linger as an unspendable
    // entry the send button still counted).
    LaunchedEffect(state.snapshot) {
        val snapshot = state.snapshot ?: return@LaunchedEffect
        val present = snapshot.utxos.mapTo(mutableSetOf()) { it.utxo.outpoint }
        val pruned = selected.filterTo(mutableSetOf()) { it in present }
        if (pruned.size != selected.size) selected = pruned
    }

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
                UtxoList(
                    snapshot = snapshot,
                    selected = selected,
                    onToggle = { outpoint ->
                        selected = if (outpoint in selected) {
                            selected - outpoint
                        } else {
                            selected + outpoint
                        }
                    },
                )
            }

            if (selected.isNotEmpty()) {
                SendSelectedBar(
                    selected = selected,
                    snapshot = state.snapshot,
                    signer = state.signer,
                    onSendSelected = onSendSelected,
                )
            }
        }
    }
}

/**
 * The bar that appears under the list once at least one coin is selected.
 *
 * It is what carries the selection out of this screen: it sums the selected
 * outpoints that the snapshot actually holds (the same set the list shows),
 * labels the button with that count and sum, and — only when [sendSelectedEnabled]
 * says so — hands the outpoints up, in list order. A watch-only wallet keeps the
 * button visible but disabled, under [WATCH_ONLY_CANNOT_SPEND_HINT], and a hot
 * wallet — signing-ready, but with no device to sign this screen's QR dance —
 * under [HOT_WALLET_CANNOT_SPEND_HINT], so the capability is discoverable
 * without ever pretending to be available.
 */
@Composable
private fun SendSelectedBar(
    selected: Set<String>,
    snapshot: AccountSnapshot?,
    signer: Signer,
    onSendSelected: (List<String>) -> Unit,
) {
    // In list order: the order the user sees them in, which is the order the
    // Send screen will summarise them in.
    val ordered = snapshot?.utxos
        ?.filter { it.utxo.outpoint in selected }
        ?.map { it.utxo.outpoint }
        .orEmpty()
    val sum = snapshot?.utxos
        ?.filter { it.utxo.outpoint in selected }
        ?.sumOf { it.utxo.satoshis }
        ?: 0L

    val enabled = sendSelectedEnabled(signer)
    val hint = sendSelectedHint(signer)

    Spacer(modifier = Modifier.height(8.dp))

    Button(
        onClick = { onSendSelected(ordered) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Send selected (${selected.size} \u2022 ${formatSats(sum)} sats)")
    }

    if (hint != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UtxoList(
    snapshot: AccountSnapshot,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
        items(snapshot.utxos) { scanned ->
            UtxoRow(
                scanned = scanned,
                selected = scanned.utxo.outpoint in selected,
                onToggle = { onToggle(scanned.utxo.outpoint) },
            )
        }
    }
}

/**
 * One output: a leading checkbox, then amount on top and provenance underneath —
 * the same "what, then where from" shape a block explorer uses, in monospace.
 *
 * The checkbox's own `onCheckedChange` is `null` on purpose: the row around it is
 * the click target, so a tap anywhere toggles the coin. That is both a larger
 * hit area and the standard list-multiselect behaviour.
 */
@Composable
private fun UtxoRow(
    scanned: ScannedUtxo,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val utxo = scanned.utxo
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
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
}
