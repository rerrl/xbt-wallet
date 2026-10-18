package com.xbtwallet.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.data.KeyValueStore
import com.xbtwallet.app.data.Wallet
import com.xbtwallet.app.data.WalletStore
import com.xbtwallet.app.ui.theme.XbtWalletTheme

/**
 * State behind the Home screen: the saved xpubs and which one is the active
 * signer.
 *
 * This is the home screen's whole state machine, and it is deliberately plain
 * Kotlin: [WalletStore] owns persistence, this class owns the snapshot Compose
 * observes, and [HomeScreen] only renders. That split keeps the screen a pure
 * function of the state (easy to preview) and keeps the "what is active" rule
 * testable without a UI.
 *
 * Nothing writes the store except through this class, so the in-memory snapshot
 * cannot drift from what is on disk.
 */
@Stable
class HomeScreenState(private val walletStore: WalletStore) {

    /** Every saved xpub, oldest first. */
    var xpubs by mutableStateOf(walletStore.list())
        private set

    /**
     * Id of the active signer, or null when none is selected. It is read back
     * through the store rather than remembered here, so an id whose xpub was
     * deleted reads as "none" instead of as a dangling selection.
     */
    var activeId by mutableStateOf(walletStore.activeId())
        private set

    /** The active xpub, or null when nothing is selected. */
    val active: Wallet?
        get() = xpubs.firstOrNull { it.id == activeId }

    /** Shortened active xpub for display, or null when nothing is active. */
    val activeSnippet: String?
        get() = active?.xpub?.let { key ->
            if (key.length <= SNIPPET_CHARS) key else key.take(SNIPPET_CHARS) + "\u2026"
        }

    /**
     * Re-reads the store. Called after the import screen saves and after the
     * picker selects, so the screen reflects writes made through other screens.
     */
    fun refresh() {
        xpubs = walletStore.list()
        activeId = walletStore.activeId()
    }

    /** Makes [wallet] the active signer. */
    fun setActive(wallet: Wallet) {
        walletStore.setActive(wallet.id)
        refresh()
    }

    companion object {
        /** Characters of an xpub shown on the signer card before the ellipsis. */
        const val SNIPPET_CHARS = 16
    }
}

/**
 * Remembers a [HomeScreenState] for the app's lifetime.
 *
 * Held at the navigation level rather than inside [HomeScreen] so it survives
 * leaving and returning to the screen — and so the Addresses screen can ask it
 * which xpub is active.
 */
@Composable
fun rememberHomeScreenState(walletStore: WalletStore): HomeScreenState =
    remember(walletStore) { HomeScreenState(walletStore) }

/**
 * Home screen — the app's hub, and its statement of what the app is: a
 * coordinator holding xpubs, not a wallet holding keys.
 *
 * Top to bottom: **Server** (private Electrum endpoint), the **active signer**
 * card showing the selected xpub, **Import xpub**, **Select active xpub**, and
 * **Addresses** — which needs an active signer to have anything to derive, so it
 * stays disabled until one is selected. Receive and Send are gone: importing an
 * xpub is the one way in, and the signer round-trip those would need does not
 * exist yet.
 *
 * The screen owns no state: it renders [state] and forwards taps.
 */
@Composable
fun HomeScreen(
    state: HomeScreenState,
    onOpenServer: () -> Unit,
    onOpenImportXpub: () -> Unit,
    onSelectActiveXpub: () -> Unit,
    onOpenAddresses: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 1. Server first: it is the one setup step that has nothing to do
            //    with keys, so the screen reads as "configure, then import".
            OutlinedButton(
                onClick = onOpenServer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Server")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Connect to your own Electrum server",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 2. The active signer gets its own box, so "who am I watching" is
            //    the thing the eye lands on rather than one button among many.
            ActiveSignerCard(state)

            Spacer(modifier = Modifier.height(24.dp))

            // 3. Import.
            Button(
                onClick = onOpenImportXpub,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Import xpub")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Pick which saved xpub the rest of the app works on. There is
            //    nothing to pick from until something has been imported.
            Button(
                onClick = onSelectActiveXpub,
                enabled = state.xpubs.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Select active xpub")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 5. Addresses follows the active signer, so it is only meaningful
            //    once one has been chosen.
            Button(
                onClick = onOpenAddresses,
                enabled = state.active != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Addresses")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when (state.xpubs.size) {
                    0 -> "No xpub imported yet \u2014 import an account xpub to begin."
                    1 -> "1 xpub saved."
                    else -> "${state.xpubs.size} xpubs saved."
                },
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "XBT / SeedSigner coordinator \u2014 watch-only",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The active-signer box: the selected xpub's name plus a shortened key, or a
 * placeholder explaining that nothing is selected.
 */
@Composable
private fun ActiveSignerCard(state: HomeScreenState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = "Active signer",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            val active = state.active
            if (active == null) {
                Text(
                    text = "No active xpub",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Import an xpub, then select it as the active signer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = active.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.activeSnippet.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** In-memory storage, so the previews can show a populated home screen. */
private class PreviewKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun HomeScreenPreview() {
    val previewState = rememberHomeScreenState(remember { previewHomeScreenStore(active = true) })
    XbtWalletTheme {
        HomeScreen(
            state = previewState,
            onOpenServer = {},
            onOpenImportXpub = {},
            onSelectActiveXpub = {},
            onOpenAddresses = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun HomeScreenEmptyPreview() {
    val previewState = rememberHomeScreenState(remember { previewHomeScreenStore(active = false) })
    XbtWalletTheme {
        HomeScreen(
            state = previewState,
            onOpenServer = {},
            onOpenImportXpub = {},
            onSelectActiveXpub = {},
            onOpenAddresses = {},
        )
    }
}

/**
 * A throwaway store for the previews: two xpubs with deterministic ids, and
 * optionally one of them made active.
 */
private fun previewHomeScreenStore(active: Boolean): WalletStore {
    var counter = 0
    val store = WalletStore(
        storage = PreviewKeyValueStore(),
        now = { 0L },
        newId = { "id-${++counter}" },
    )
    val vector =
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868" +
            "RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
    val cold = store.add("Cold storage", vector)
    store.add("Savings", vector)
    if (active) store.setActive(cold.id)
    return store
}
