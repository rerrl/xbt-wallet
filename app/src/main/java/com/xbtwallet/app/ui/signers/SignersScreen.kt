package com.xbtwallet.app.ui.signers

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerType

/**
 * The list of saved signers.
 *
 * This screen is the app's inventory, and it is deliberately plain: every signer
 * by the name the user gave it, with its type badge, and nothing selected,
 * nothing "active". Tapping one opens that signer (the detail screen is built in
 * a follow-up card; for now it is a stub), which is the only place a per-signer
 * action will ever live.
 *
 * It is stateless — [signers] is passed in from the same [com.xbtwallet.app.ui.home.HomeScreenState]
 * the home screen counts, so both screens can never disagree about what is saved.
 */
@Composable
fun SignersScreen(
    signers: List<Signer>,
    onOpenSigner: (Signer) -> Unit,
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
                TextButton(onClick = onBack) { Text("← Back") }
                Spacer(modifier = Modifier.width(4.dp))
                Text("Signers", style = MaterialTheme.typography.titleLarge)
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (signers.isEmpty()) {
                Text(
                    text = "No signers yet — add one from the home screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Text(
                text = "Tap a signer to open it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(signers.size) { position ->
                    val signer = signers[position]
                    SignerRow(signer = signer, onClick = { onOpenSigner(signer) })
                }
            }
        }
    }
}

@Composable
private fun SignerRow(signer: Signer, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = signer.name, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(2.dp))
            // Only the fingerprint of the key belongs in a list row; the full
            // xpub lives on the signer's own screen.
            Text(
                text = shortXpub(signer),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        TypeBadge(label = signer.typeLabel)
    }
}

/** First [SNIPPET_CHARS] characters of a signer's key, plus an ellipsis. */
private fun shortXpub(signer: Signer): String {
    val xpub = (signer.type as? SignerType.WatchOnlyXpub)?.xpub ?: return ""
    return if (xpub.length <= SNIPPET_CHARS) xpub else xpub.take(SNIPPET_CHARS) + "…"
}

private const val SNIPPET_CHARS = 16

/** The little "watch-only" pill next to a signer's name. */
@Composable
internal fun TypeBadge(label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
