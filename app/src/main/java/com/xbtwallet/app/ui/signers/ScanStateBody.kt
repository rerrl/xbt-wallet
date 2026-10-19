package com.xbtwallet.app.ui.signers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.chain.AccountSnapshot
import kotlinx.coroutines.launch

/**
 * The scan gate every per-signer screen shares: it renders the loading,
 * no-server and failed states, and only calls [content] when there is a
 * completed scan to show.
 *
 * Having the gate in one place is what keeps the three screens honest with each
 * other — the UTXO list, the address list and the detail screen all say the same
 * thing when the server is missing, and all offer the same Retry, because it is
 * the same code.
 *
 * The card in the detail screen deliberately does *not* use this: it needs the
 * balance presented inside its own panel, not as a full-screen state.
 */
@Composable
internal fun ScanStateBody(
    state: SignerChainState,
    modifier: Modifier = Modifier,
    content: @Composable (AccountSnapshot) -> Unit,
) {
    when (state.phase) {
        ChainPhase.IDLE, ChainPhase.LOADING -> ScanLoading(state, modifier)
        ChainPhase.NO_SERVER -> ScanMissingServer(modifier)
        ChainPhase.ERROR -> ScanFailed(state, modifier)
        ChainPhase.READY -> content(state.snapshot ?: AccountSnapshot.EMPTY)
    }
}

@Composable
private fun ScanLoading(state: SignerChainState, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = if (state.scannedAddressCount > 0) {
                "Checking addresses\u2026 ${state.scannedAddressCount} so far"
            } else {
                "Checking addresses\u2026"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanMissingServer(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text("Connect a server", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "This view needs chain data. Add your own Electrum server under Server on " +
                "the home screen first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanFailed(state: SignerChainState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = state.errorMessage ?: "The scan failed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = { scope.launch { state.reload() } }) { Text("Retry") }
    }
}

/**
 * A quiet one-line banner under a per-signer screen's header.
 *
 * It used to name the saved `host:port`. It no longer does — the wallet never
 * renders which server the user chose (a screenshot of a UTXO list should not
 * leak their private endpoint), so this is the route-level fact instead: whether
 * a server is backing the view at all.
 */
@Composable
internal fun ScannedFromBanner(state: SignerChainState, modifier: Modifier = Modifier) {
    val hasServer = state.phase != ChainPhase.NO_SERVER
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (hasServer) "Scanned from your Electrum server" else "No server configured",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
