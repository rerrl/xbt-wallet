package com.xbtwallet.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xbtwallet.app.ui.theme.XbtWalletTheme

/**
 * **Settings** — the app-level knobs, one step off the home landing page.
 *
 * Top to bottom: **Preferences**, the app-level flags moved here from Home —
 * today just *Keep screen on while app is open*, which holds the display on the
 * whole time the app is in the foreground so the screen never sleeps out from
 * under a scan or a read — then **Server**, a single button that opens the
 * existing Server screen unchanged. The server entry point moved here with the
 * preferences, so Home stays the landing page and setup lives behind Settings.
 *
 * The screen owns no state: the preference value and its persistence belong to
 * the navigation layer (see `XbtWalletApp`), and the Server screen behind the
 * button is the same one it always was.
 *
 * @param keepScreenOn the current value of the keep-screen-on preference.
 * @param onKeepScreenOnChange called with the new value when the switch is
 *   flipped; the caller persists it.
 * @param onOpenServer called when the Server button is tapped; opens the
 *   existing Server screen.
 * @param onBack called when the user taps the back affordance.
 */
@Composable
fun SettingsScreen(
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onOpenServer: () -> Unit,
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
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Preferences",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Keep screen on while app is open",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = keepScreenOn,
                    onCheckedChange = onKeepScreenOnChange,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Server",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onOpenServer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Server")
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101418)
@Composable
private fun SettingsScreenPreview() {
    XbtWalletTheme {
        SettingsScreen(
            keepScreenOn = true,
            onKeepScreenOnChange = {},
            onOpenServer = {},
            onBack = {},
        )
    }
}
