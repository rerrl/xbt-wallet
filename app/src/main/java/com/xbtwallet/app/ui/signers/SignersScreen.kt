package com.xbtwallet.app.ui.signers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.data.Signer
import com.xbtwallet.app.ui.theme.XbtDeviceTagDark
import com.xbtwallet.app.ui.theme.XbtHotTagDark
import com.xbtwallet.app.ui.theme.XbtPrimary

/**
 * The shared wallet-row components.
 *
 * This file used to hold the standalone Wallets screen; that screen is retired
 * and the wallet list now lives inline on the home landing page, so all that
 * remains here are the pieces other screens draw a wallet with: [SignerRow] (one
 * row of the list — name, a head…tail snippet of the key, the type tag and the
 * ⋮ overflow menu) and [TypeBadge] (the little pill beside a wallet's name).
 *
 * [SignerRow] is `internal` so Home can reuse it; its rendering and its
 * overflow-menu API are unchanged from when the Wallets screen owned it. A row
 * still selects nothing and marks nothing "active": tapping it opens that
 * wallet's own screen, which is the only place a per-wallet action lives, and
 * the only state the row carries is which menu is open — passed in, so the
 * caller decides. The tag itself comes from [signerTagFor], so the row and a
 * signer's own screen cannot disagree about what a wallet is.
 */

@Composable
internal fun SignerRow(
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
                text = shortenedXpub(signer.accountXpub),
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
            SignerTagAccent.HOT -> XbtHotTagDark.copy(alpha = 0.16f)
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
                SignerTagAccent.HOT -> XbtHotTagDark
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
