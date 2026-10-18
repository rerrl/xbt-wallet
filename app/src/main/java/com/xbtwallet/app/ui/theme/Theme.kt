package com.xbtwallet.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * XBT wallet theme.
 *
 * Dark is the only mode for now: this is a security tool that gets used in the
 * dark as often as not, and a single fixed scheme keeps the surface auditable.
 * Dynamic color is intentionally not used, so the app looks the same on every
 * device (GrapheneOS included).
 */
private val XbtDarkColorScheme = darkColorScheme(
    primary = XbtPrimary,
    onPrimary = XbtOnPrimary,
    background = XbtBackgroundDark,
    onBackground = XbtOnBackgroundDark,
    surface = XbtSurfaceDark,
    onSurface = XbtOnSurfaceDark,
    surfaceVariant = XbtSurfaceVariantDark,
    onSurfaceVariant = XbtOnSurfaceVariantDark,
    outline = XbtOutlineDark,
    error = XbtErrorDark,
    onError = Color(0xFF2A0A0A),
)

@Composable
fun XbtWalletTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = XbtDarkColorScheme,
        typography = XbtTypography,
        content = content,
    )
}
