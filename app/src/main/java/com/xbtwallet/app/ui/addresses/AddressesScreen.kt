package com.xbtwallet.app.ui.addresses

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.xbtwallet.app.crypto.ExtendedPublicKey
import com.xbtwallet.app.crypto.Hashes
import com.xbtwallet.app.crypto.SegwitAddress
import com.xbtwallet.app.data.Wallet

/**
 * The two BIP-32 chains of an account. 0 is "external" (addresses handed out to
 * receive coins), 1 is "internal" (change). The labels are the ones every
 * Bitcoin wallet shows; the numbers are what actually goes into the derivation
 * path `m/84'/0'/0'/<chain>/<index>`.
 */
enum class AddressChain(val index: Long, val label: String) {
    RECEIVE(0L, "Receive"),
    CHANGE(1L, "Change"),
}

/** One derived address and the index it sits at. */
data class DerivedAddress(val index: Int, val address: String)

/**
 * State behind the Addresses screen: which chain is selected, how many rows are
 * shown, and the derived addresses for those rows.
 *
 * Addresses are derived on demand and cached per (chain, index), so pressing
 * "show more" only pays for the new rows. Derivation here is a handful of
 * HMAC + elliptic-curve operations per address — cheap enough at this size to
 * stay on the calling thread; when gap-limit scanning over chain data arrives it
 * will have to move off the UI thread.
 */
@Stable
class AddressesScreenState(val wallet: Wallet) {

    /** Parsed account key, or null if the stored xpub is unreadable. */
    val accountKey: ExtendedPublicKey? = runCatching { ExtendedPublicKey.parse(wallet.xpub) }.getOrNull()

    var chain by mutableStateOf(AddressChain.RECEIVE)
        private set

    var visibleCount by mutableStateOf(PAGE_SIZE)
        private set

    var addresses by mutableStateOf<List<DerivedAddress>>(emptyList())
        private set

    private val cache = mutableMapOf<Pair<AddressChain, Int>, String>()

    init {
        refresh()
    }

    fun selectChain(value: AddressChain) {
        if (chain == value) return
        chain = value
        // Each tab starts at its own first page, like any other wallet.
        visibleCount = PAGE_SIZE
        refresh()
    }

    fun showMore() {
        visibleCount += PAGE_SIZE
        refresh()
    }

    private fun refresh() {
        val key = accountKey ?: return
        val chainKey = key.deriveChild(chain.index)
        addresses = (0 until visibleCount).map { index ->
            DerivedAddress(
                index = index,
                address = cache.getOrPut(chain to index) {
                    // Native segwit single-sig: P2WPKH over HASH160 of the derived
                    // compressed public key.
                    SegwitAddress.encodeP2wpkh(
                        hrp = SegwitAddress.MAINNET_HRP,
                        hash160 = Hashes.hash160(chainKey.deriveChild(index.toLong()).publicKey),
                    )
                },
            )
        }
    }

    companion object {
        /** Rows shown per tab before "show more" — no gap-limit logic yet. */
        const val PAGE_SIZE = 20
    }
}

/** Remembers an [AddressesScreenState] for a wallet. */
@Composable
fun rememberAddressesScreenState(wallet: Wallet): AddressesScreenState =
    remember(wallet.id) { AddressesScreenState(wallet) }

/**
 * Address viewer for one wallet.
 *
 * Tabs switch between the receive and change chains; every row is a derived
 * `bc1q...` address with its index, and tapping one copies it. Only the first
 * page is shown until the user asks for more — the wallet has no chain data, so
 * it cannot tell which addresses are actually used.
 */
@Composable
fun AddressesScreen(
    state: AddressesScreenState,
    onBack: () -> Unit,
    onChangeWallet: (() -> Unit)? = null,
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
                    text = state.wallet.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (onChangeWallet != null) {
                    TextButton(onClick = onChangeWallet) { Text("Switch wallet") }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Native segwit \u2014 derived from the account key, no chain data yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (state.accountKey == null) {
                Text(
                    text = "This wallet's stored key could not be read. Delete and re-import it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddressChain.entries.forEach { chain ->
                    FilterChip(
                        selected = state.chain == chain,
                        onClick = { state.selectChain(chain) },
                        label = { Text(chain.label) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            AddressList(state, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun AddressList(state: AddressesScreenState, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(state.addresses.size) { position ->
            val row = state.addresses[position]
            AddressRow(
                row = row,
                onClick = {
                    copyToClipboard(context, row.address)
                    Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                },
            )
        }

        item {
            // There is no gap limit yet, so more addresses can always be derived.
            TextButton(
                onClick = state::showMore,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Show ${AddressesScreenState.PAGE_SIZE} more")
            }
        }
    }
}

@Composable
private fun AddressRow(row: DerivedAddress, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
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
        )
    }
}

/** Plain Android clipboard call — one line of platform, no extra Compose dependency. */
private fun copyToClipboard(context: Context, address: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Bitcoin address", address))
}

/**
 * Wallet chooser, shown when more than one wallet is stored. Tapping a row goes
 * straight to that wallet's addresses.
 */
@Composable
fun WalletPickerScreen(
    wallets: List<Wallet>,
    onSelect: (Wallet) -> Unit,
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
                Text("Choose a wallet", style = MaterialTheme.typography.titleLarge)
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(wallets.size) { position ->
                    val wallet = wallets[position]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(wallet) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(text = wallet.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = wallet.xpub.take(20) + "\u2026",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
