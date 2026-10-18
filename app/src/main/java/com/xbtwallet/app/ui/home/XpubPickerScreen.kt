package com.xbtwallet.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.data.Wallet

/**
 * Chooses which saved xpub is the active signer.
 *
 * It sets the active xpub and returns — it does not open anything. Everything
 * that works on an xpub (the Addresses viewer today) reads the active one from
 * the home screen, so there is exactly one place to change it and no second
 * picker hidden inside a downstream screen.
 *
 * A radio button marks the current selection; the screen is stateless, so it is
 * also correct if the active xpub is cleared while it is open.
 */
@Composable
fun XpubPickerScreen(
    xpubs: List<Wallet>,
    activeId: String?,
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
                Text("Select active xpub", style = MaterialTheme.typography.titleLarge)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "The app shows addresses and balances for the xpub you pick here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(xpubs.size) { position ->
                    val wallet = xpubs[position]
                    XpubRow(
                        wallet = wallet,
                        selected = wallet.id == activeId,
                        onClick = { onSelect(wallet) },
                    )
                }
            }
        }
    }
}

@Composable
private fun XpubRow(wallet: Wallet, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)

        Column(modifier = Modifier.weight(1f)) {
            Text(text = wallet.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = shortened(wallet.xpub),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** First [HomeScreenState.SNIPPET_CHARS] characters of a key, plus an ellipsis. */
private fun shortened(xpub: String): String =
    if (xpub.length <= HomeScreenState.SNIPPET_CHARS) {
        xpub
    } else {
        xpub.take(HomeScreenState.SNIPPET_CHARS) + "\u2026"
    }
