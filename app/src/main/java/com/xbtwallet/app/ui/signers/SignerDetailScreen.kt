package com.xbtwallet.app.ui.signers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 * One signer's own screen.
 *
 * **Stub for now.** A follow-up card builds the real thing — the signer's
 * addresses, its xpub, and the per-signer actions. This placeholder exists so
 * that tapping a signer in the list navigates somewhere sane and never crashes;
 * it shows the identity it was opened for and the type badge, and offers a way
 * back.
 *
 * [signer] is null when the id the navigation layer passed no longer resolves —
 * the signer was removed while the screen was open. The screen says so instead
 * of showing a blank or throwing.
 */
@Composable
fun SignerDetailScreen(
    signer: Signer?,
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
                Text(
                    text = signer?.name ?: "Signer",
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (signer == null) {
                Text(
                    text = "This signer is no longer saved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Type",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(12.dp))
                TypeBadge(label = signer.typeLabel)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // The full key, monospaced, so the user can read what this signer
            // watches. Wrapping is left on: an xpub is far wider than a phone.
            val xpub = (signer.type as? SignerType.WatchOnlyXpub)?.xpub
            if (xpub != null) {
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

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Addresses and per-signer actions arrive in a follow-up update.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
