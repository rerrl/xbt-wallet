package com.xbtwallet.app.ui.signers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.data.SignerType
import com.xbtwallet.app.ui.theme.XbtDeviceTagDark
import com.xbtwallet.app.ui.theme.XbtPrimary

/**
 * The list of saved wallets — the app's inventory.
 *
 * Every wallet is here by the name the user gave it, with its *type* tag
 * ("watch-only", "seedsigner" or "signing device" — see [signerTagFor]) and a
 * head…tail snippet of its key. Nothing is selected and nothing is "active":
 * tapping a row opens that wallet's own screen, which is the only place a
 * per-wallet action lives.
 *
 * The screen is otherwise stateless — [signers] is passed in from the same
 * [com.xbtwallet.app.ui.home.HomeScreenState] the home screen counts, so the two
 * screens can never disagree about what is saved. The only state it keeps is
 * local to the interaction: which row's overflow menu is open, and which wallet
 * is waiting on a delete confirmation. Neither outlives the screen, and neither
 * touches storage — removing a wallet is [onDeleteSigner]'s job, on the screen
 * that owns the store.
 *
 * Deletion is a two-step (menu → confirm dialog) on purpose: a watch-only xpub
 * is the only record of an account on this phone, so a stray tap must not be
 * able to lose it.
 *
 * **Add wallet is the primary action pinned to the bottom edge of this screen**
 * — it moved here from Home, so adding a wallet happens next to the list it adds
 * to. The screen reads top to bottom: the header, then the hint, then the wallet
 * list itself, which starts directly under the hint and fills everything that is
 * left. The button sits last, on the bottom edge for one-handed reach, which is
 * where a thumb naturally lands.
 */
@Composable
fun SignersScreen(
    signers: List<Signer>,
    onOpenSigner: (Signer) -> Unit,
    onAddSigner: () -> Unit,
    onDeleteSigner: (Signer) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which row's "⋮" menu is open, by signer id — one at a time.
    var menuForId by remember { mutableStateOf<String?>(null) }

    // Which signer is awaiting confirmation, by signer id. Saved across
    // configuration changes so a rotation does not silently drop the question.
    var confirmingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }

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
                Text("Wallets", style = MaterialTheme.typography.titleLarge)
            }

            if (signers.isEmpty()) {
                // The empty state does the explaining; the way out of itself is
                // the Add wallet button pinned to the bottom edge, so an empty
                // list is never a dead end.
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No wallets yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Add a wallet to watch an account: scan the account xpub " +
                        "from your air-gapped signer and this phone can see it without " +
                        "ever holding its keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Flexible space, so the Add wallet button below still lands on
                // the bottom edge of the screen.
                Spacer(modifier = Modifier.weight(1f))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap a wallet to open it. Its xpub stays on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // The list starts at the top, directly under the hint, and takes
                // every remaining pixel: the first wallet renders right below the
                // hint and the list scrolls down from there.
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(signers, key = { it.id }) { signer ->
                        SignerRow(
                            signer = signer,
                            onClick = { onOpenSigner(signer) },
                            menuOpen = menuForId == signer.id,
                            onMenuOpenChange = { open -> menuForId = if (open) signer.id else null },
                            onRemoveClick = {
                                menuForId = null
                                confirmingDeleteId = signer.id
                            },
                        )
                    }
                }
            }

            // The primary action, and the last thing on the screen: full-width
            // and pinned to the bottom edge in both states, where a thumb
            // naturally lands.
            Button(
                onClick = onAddSigner,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add wallet")
            }
        }
    }

    // Resolve the pending id against the current list at render time: if the
    // signer went away underneath us, there is nothing to confirm and the dialog
    // simply is not shown.
    val pendingDelete = confirmingDeleteId?.let { id -> signers.firstOrNull { it.id == id } }
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmingDeleteId = null },
            title = { Text("Remove wallet?") },
            text = {
                Text(
                    "\u201c${pendingDelete.name}\u201d will be removed from this phone. " +
                        "Only the public key is stored here, so nothing is deleted on " +
                        "the signer itself \u2014 you can add it back any time.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDeleteId = null
                        onDeleteSigner(pendingDelete)
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDeleteId = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SignerRow(
    signer: Signer,
    onClick: () -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onRemoveClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
        ) {
            Text(text = signer.name, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(2.dp))
            // Only the ends of the key belong in a list row; the full xpub lives
            // on the signer's own screen.
            Text(
                text = (signer.type as? SignerType.WatchOnlyXpub)?.let { shortenedXpub(it.xpub) }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        // The tag is chosen by the signer's origin, not by hand here — the same
        // rule the signer's own screen uses, so the two cannot disagree.
        val tag = signerTagFor(signer)
        TypeBadge(label = tag.label, accent = tag.accent)
        Spacer(modifier = Modifier.width(4.dp))
        Box {
            // A text glyph rather than an icon dependency: one character keeps
            // the row's trailing edge calm and adds nothing to the build.
            TextButton(onClick = { onMenuOpenChange(true) }) {
                Text("\u22ee", style = MaterialTheme.typography.titleMedium)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { onMenuOpenChange(false) },
            ) {
                DropdownMenuItem(
                    text = { Text("Remove wallet") },
                    onClick = onRemoveClick,
                )
            }
        }
    }
}

/**
 * The little pill next to a wallet's name — "watch-only" in grey, "seedsigner"
 * in the theme's orange for a SeedSigner export, or "signing device" in the
 * theme's steel blue for an export from any other device.
 *
 * The accent comes in as [SignerTagAccent] rather than a colour so the choice of
 * which wallet wears which tag stays in [signerTagFor], where a unit test can
 * reach it, and this composable only maps the chosen accent onto the palette.
 */
@Composable
internal fun TypeBadge(
    label: String,
    accent: SignerTagAccent = SignerTagAccent.NEUTRAL,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = when (accent) {
            SignerTagAccent.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
            // A translucent wash of the accent rather than a solid fill: the
            // pill stays quiet next to the wallet's name while still reading as
            // orange (or blue), and the label colour carries the contrast.
            SignerTagAccent.ORANGE -> XbtPrimary.copy(alpha = 0.16f)
            SignerTagAccent.DEVICE -> XbtDeviceTagDark.copy(alpha = 0.16f)
        },
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = when (accent) {
                SignerTagAccent.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                SignerTagAccent.ORANGE -> XbtPrimary
                SignerTagAccent.DEVICE -> XbtDeviceTagDark
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
