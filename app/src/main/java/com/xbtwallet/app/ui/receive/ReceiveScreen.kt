package com.xbtwallet.app.ui.receive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.zxing.common.BitMatrix
import com.xbtwallet.app.chain.AddressChain
import com.xbtwallet.app.ui.signers.SignerChainState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hand out one address: a QR to point a payer's camera at, and the address as
 * text to copy.
 *
 * This is the receive half of the wallet's primary job — *get money into it* —
 * and it is deliberately offline-complete: an address is arithmetic over the
 * account xpub ([SignerChainState.scanner]), so the QR renders whether or not a
 * server is reachable. The scan's only job here is to say *which* index to hand
 * out: the first address the chain walk saw as unused (see
 * [resolveReceiveIndex]). No scan answer means index 0, labelled as unverified —
 * the screen never claims a server cleared an address it never asked about.
 *
 * **Two ways in**, and they show different addresses:
 *
 * - from a signer's own **Receive** button, with no [requestedAddress]: the
 *   "first unused" rule above;
 * - from a row in the **addresses list**, with [requestedAddress] set: exactly
 *   the address the user tapped, verbatim, so the QR matches the row they were
 *   reading rather than silently switching to a different index.
 *
 * The requested address rides in on the navigation [com.xbtwallet.app.ui.Route],
 * so it survives a rotation with the rest of the back stack.
 *
 * @param state the open signer's chain state (shared with its other screens).
 * @param requestedAddress the address to show, or null to pick the first unused
 *   receive address.
 * @param onBack pops one level — the addresses list or the signer detail screen.
 */
@Composable
fun ReceiveScreen(
    state: SignerChainState,
    requestedAddress: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // The address on screen, resolved once per (snapshot, key, request) and
    // derived off the UI thread — same reason the addresses list does it there:
    // a handful of elliptic-curve operations is cheap but is not a frame's work.
    var address by remember { mutableStateOf<String?>(null) }
    var sourceLabel by remember { mutableStateOf("") }
    var verified by remember { mutableStateOf(true) }

    LaunchedEffect(state.snapshot, state.accountKey, requestedAddress) {
        if (requestedAddress != null) {
            // Tapped from the list: show that address, not a guessed one.
            address = requestedAddress
            sourceLabel = "Selected address"
            verified = true
            return@LaunchedEffect
        }
        val scanner = state.scanner
        if (scanner == null) {
            address = null
            return@LaunchedEffect
        }
        val scanned = state.snapshot?.addresses(AddressChain.RECEIVE) ?: emptyList()
        val (index, isVerified) = resolveReceiveIndex(scanned)
        sourceLabel = "First unused receive address"
        verified = isVerified
        address = withContext(Dispatchers.Default) {
            scanner.derive(AddressChain.RECEIVE, index).address
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                // Small phones and large font scales overflow a fixed column;
                // scrolling keeps the copy button reachable under a big QR.
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onBack) { Text("\u2190 Back") }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Receive",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = state.signer.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val shown = address
            if (state.accountKey == null) {
                Text(
                    text = "This xpub's stored key could not be read. Delete and re-import it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (shown == null) {
                Text(
                    text = "Deriving the receive address\u2026",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                QrPanel(payload = qrPayload(shown))

                Spacer(modifier = Modifier.height(16.dp))

                // The address itself, selectable by hand as well as copyable by
                // button — both routes to the clipboard, because a wallet is a
                // tool for moving an address around.
                SelectionContainer {
                    Text(
                        text = shown,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (verified) {
                        sourceLabel
                    } else {
                        // Honest about what the app has and has not asked its
                        // server: handing out an address nobody checked is how
                        // an address with history gets reused.
                        "$sourceLabel \u2014 not yet verified on chain, sync to confirm unused"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        copyToClipboard(context, shown)
                        Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Copy address")
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Scan this with the payer's wallet, or copy it and send it to them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The QR as it is drawn: a white panel with the symbol centred in it.
 *
 * The white [Surface] is not decoration — it is the quiet zone. A QR module
 * touching the dark background of a phone screen gives a camera nothing to find
 * the finder patterns against, so the symbol keeps its own light margin.
 *
 * The bitmap is built once per payload (`remember`), with one pixel per module
 * and `FilterQuality.None` on the way up to the screen: a QR is binary data, and
 * smoothing its modules is exactly the blur a scanner cannot read.
 */
@Composable
private fun QrPanel(payload: String, modifier: Modifier = Modifier) {
    val bitmap = remember(payload) { qrBitmap(renderQr(payload)) }
    Surface(
        color = androidx.compose.ui.graphics.Color.White,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Address QR code",
            filterQuality = FilterQuality.None,
            modifier = Modifier.padding(16.dp).size(240.dp),
        )
    }
}

/**
 * The [BitMatrix] as pixels: black modules on white, one pixel each.
 *
 * The same mapping the JVM round-trip test decodes, so what is asserted there is
 * what is drawn here.
 */
private fun qrBitmap(matrix: BitMatrix): Bitmap {
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) BLACK else ANDROID_WHITE)
        }
    }
    return bitmap
}

/** Opaque black — the dark module. */
private const val BLACK = 0xFF000000.toInt()

/** Opaque white — the light module. */
private const val ANDROID_WHITE = 0xFFFFFFFF.toInt()

/** Plain Android clipboard call — the same one the addresses list uses. */
private fun copyToClipboard(context: Context, address: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Bitcoin address", address))
}