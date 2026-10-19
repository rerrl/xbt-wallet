package com.xbtwallet.app.ui.addresses

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.chain.AddressChain
import com.xbtwallet.app.chain.DerivedAddress
import com.xbtwallet.app.ui.signers.ScannedFromBanner
import com.xbtwallet.app.ui.signers.SignerChainState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Address viewer for one signer — derived on device, annotated by the scan.
 *
 * Receive and Change are the account's two BIP-32 chains; each row is a derived
 * `bc1q...` address with its index, and tapping one copies it. Deriving an
 * address is pure local crypto ([SignerChainState.scanner] over the account
 * xpub), so the list appears **instantly**, in every scan phase: LOADING,
 * NO_SERVER, ERROR and READY all render the same rows, because none of them
 * change the arithmetic.
 *
 * The scan is an annotation, never a gate. Where a cached or completed snapshot
 * already knows an address's status, the right-hand tag says **used**,
 * **unused**, or **first unused** (highlighted — the address the next receive
 * hands out) from the moment the screen opens; a fresh scan re-labels those
 * rows in place. Rows past the range the scan visited are **not scanned**: the
 * app genuinely has not asked the server about them, and a screen that guessed
 * "unused" is how an address with history gets handed out twice.
 *
 * "Show more" grows the page by [PAGE_SIZE] and the derivation effect fills the
 * new tail *in a coroutine on the default dispatcher*, so even that work stays
 * off the UI thread.
 */
@Composable
fun AddressesScreen(
    state: SignerChainState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chain by remember { mutableStateOf(AddressChain.RECEIVE) }

    // How many pages of locally derived addresses the user has asked for. One
    // page is on screen from the start; "Show more" adds one at a time. Keyed on
    // the tab so each chain keeps its own page count, and switching tabs derives
    // that chain independently.
    var pageCount by remember(chain) { mutableStateOf(1) }
    var derived by remember(chain) { mutableStateOf(emptyList<DerivedAddress>()) }
    var deriving by remember(chain) { mutableStateOf(false) }

    // What the scan has answered about this chain, if anything. Empty until a
    // (cached or fresh) snapshot exists — the derivation below is not.
    val scanned = state.snapshot?.addresses(chain) ?: emptyList()

    // The scan may have visited further than the current page; always derive at
    // least as far as it went so no scanned row is ever truncated.
    val target = maxOf(scanned.size, pageCount * PAGE_SIZE)

    LaunchedEffect(chain, pageCount, state.snapshot, state.accountKey) {
        val scanner = state.scanner
        if (scanner == null) {
            derived = emptyList()
            deriving = false
            return@LaunchedEffect
        }
        deriving = true
        // Key derivation is a few elliptic-curve operations per address: cheap,
        // but not something to do between frames while the user is scrolling.
        derived = withContext(Dispatchers.Default) {
            (0 until target).map { index -> scanner.derive(chain, index) }
        }
        deriving = false
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
                    text = "Addresses",
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

            if (state.accountKey == null) {
                Text(
                    text = "This xpub's stored key could not be read. Delete and re-import it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddressChain.entries.forEach { option ->
                    FilterChip(
                        selected = chain == option,
                        onClick = { chain = option },
                        label = { Text(option.label) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val rows = buildAddressRows(scanned = scanned, derived = derived)
            AddressList(
                rows = rows,
                firstUnusedIndex = firstUnusedIndex(rows),
                deriving = deriving,
                onShowMore = { pageCount++ },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AddressList(
    rows: List<AddressRowModel>,
    firstUnusedIndex: Int?,
    deriving: Boolean,
    onShowMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(rows, key = { it.index }) { row ->
            AddressRow(
                row = row,
                firstUnused = row.index == firstUnusedIndex,
                onClick = {
                    copyToClipboard(context, row.address)
                    Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                },
            )
        }

        item {
            TextButton(onClick = onShowMore, modifier = Modifier.fillMaxWidth()) {
                Text(if (deriving) "Deriving\u2026" else "Show $PAGE_SIZE more")
            }
        }
    }
}

@Composable
private fun AddressRow(row: AddressRowModel, firstUnused: Boolean, onClick: () -> Unit) {
    // The one address the next receive would hand out is tinted; everything else
    // sits on the plain background so the highlight stays meaningful.
    val background = if (firstUnused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface

    Surface(color = background, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 10.dp),
        ) {
            Text(
                text = row.index.toString().padStart(3, ' '),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = row.address,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = usageLabel(row.used, firstUnused),
                style = MaterialTheme.typography.labelSmall,
                // The address that would be handed out next gets the accent
                // colour; "used"/"unused"/"not scanned" are all quiet metadata.
                color = if (firstUnused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** The right-hand tag: what the scan learned, or that it did not look here. */
private fun usageLabel(used: Boolean?, firstUnused: Boolean): String = when {
    used == true -> "used"
    used == false && firstUnused -> "first unused"
    used == false -> "unused"
    else -> "not scanned"
}

/**
 * Addresses derived per page. Deriving locally is free of the network, so this
 * is purely how many rows a reader sees before asking for more.
 */
private const val PAGE_SIZE = 20

/** Plain Android clipboard call — one line of platform, no extra Compose dependency. */
private fun copyToClipboard(context: Context, address: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Bitcoin address", address))
}
