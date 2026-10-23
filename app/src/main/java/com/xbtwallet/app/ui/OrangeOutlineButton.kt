package com.xbtwallet.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.ui.theme.XbtPrimary

/**
 * The app's one **secondary** button: an [OutlinedButton] whose outline is the
 * wallet's orange at half strength.
 *
 * The cards and the import screens have two kinds of action, and they must not
 * read as competing: a *primary* one is a filled `Button` ("Add wallet"), and
 * everything beside it — the card "Select"/"Create"/"Import" taps, the
 * dice/word-count choices, "Rescan" — is this. A tonal fill gave the secondary
 * actions more weight than they earn and, sitting inside a card, made the card
 * itself look like it had changed colour; a plain outlined button was too quiet
 * to be the obvious next tap. A single half-transparent orange hairline is the
 * one style that sits between them, and keeping it here means every secondary
 * action in the app wears the exact same border rather than six hand-rolled
 * copies that can drift.
 *
 * The label and the rest of the button's styling are [OutlinedButton]'s own
 * defaults; only the border is overridden.
 */
@Composable
internal fun OrangeOutlineButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        border = BorderStroke(1.dp, XbtPrimary.copy(alpha = 0.5f)),
    ) {
        content()
    }
}
