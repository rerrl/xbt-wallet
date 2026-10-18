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
import com.xbtwallet.app.chain.AccountSnapshot
import com.xbtwallet.app.chain.AddressChain
import com.xbtwallet.app.ui.signers.ScanStateBody
import com.xbtwallet.app.ui.signers.ScannedFromBanner
import com.xbtwallet.app.ui.signers.SignerChainState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One rendered row: the address, and what the scan knows about it. */
private data class AddressRowModel(
    val index: Int,
    val address: String,
    /** `true` used, `false` unused, `null` beyond the range the scan visited. */
    val used: Boolean?,
)

/**
 * Address viewer for one signer — the old viewer, now annotated with chain data.
 *
 * Receive and Change are the account's two BIP-32 chains; each row is a derived
 * `bc1q...` address with its index, and tapping one copies it. What is new is
 * the right-hand tag: the gap-limit scan has asked the server about each
 * address, so the screen can say **used**, **unused**, or — for the one address
 * about to be handed out — **first unused**, which is highlighted.
 *
 * The list starts as exactly what the scan visited: a full unused batch past the
 * last used address, which is the gap limit. "Show more" derives further
 * addresses on demand *in a coroutine on the default dispatcher*, so even that
 * derivation stays off the UI thread; those rows are marked **not scanned**
 * rather than **unused**, because the app genuinely has not asked about them,
 * and a receive screen that guesses is how an address with history gets handed
 * out twice.
 */
@Composable
fun AddressesScreen(
    state: SignerChainState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chain by remember { mutableStateOf(AddressChain.RECEIVE) }

    // Rows beyond the scanned range, requested by "Show more". The count is the
    // intent; the addresses themselves are derived by the effect below, off the
    // UI thread, and reset whenever the tab or the scan changes.
    var extraCount by remember(chain) { mutableStateOf(0) }
    var extraAddresses by remember(chain) { mutableStateOf(emptyList<String>()) }
    var deriving by remember(chain) { mutableStateOf(false) }

    LaunchedEffect(chain, extraCount, state.snapshot) {
        val scannedSize = state.snapshot?.addresses(chain)?.size ?: 0
        val scanner = state.scanner
        if (extraCount == 0 || scanner == null) {
            extraAddresses = emptyList()
            deriving = false
            return@LaunchedEffect
        }
        deriving = true
        // Key derivation is a few elliptic-curve operations per address: cheap,
        // but not something to do between frames while the user is scrolling.
        extraAddresses = withContext(Dispatchers.Default) {
            (scannedSize until scannedSize + extraCount).map { index -> scanner.derive(chain, index).address }
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

            ScanStateBody(state, modifier = Modifier.weight(1f)) { snapshot ->
                AddressList(
                    snapshot = snapshot,
                    chain = chain,
                    extraAddresses = extraAddresses,
                    deriving = deriving,
                    onShowMore = { extraCount += PAGE_SIZE },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AddressList(
    snapshot: AccountSnapshot,
    chain: AddressChain,
    extraAddresses: List<String>,
    deriving: Boolean,
    onShowMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scanned = snapshot.addresses(chain)
    val firstUnusedIndex = scanned.indexOfFirst { !it.used }.takeIf { it >= 0 }

    val scannedRows = scanned.map { visited ->
        AddressRowModel(index = visited.index, address = visited.address, used = visited.used)
    }
    val extraRows = extraAddresses.mapIndexed { offset, address ->
        AddressRowModel(index = scanned.size + offset, address = address, used = null)
    }
    val rows = scannedRows + extraRows

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
 * Rows derived per "Show more", beyond the range the scan visited. The viewer
 * and the scanner both page in twenties, so a reader sees the same rhythm.
 */
private const val PAGE_SIZE = 20

/** Plain Android clipboard call — one line of platform, no extra Compose dependency. */
private fun copyToClipboard(context: Context, address: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Bitcoin address", address))
}
