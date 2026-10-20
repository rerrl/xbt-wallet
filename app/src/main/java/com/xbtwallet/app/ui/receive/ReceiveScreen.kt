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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.zxing.common.BitMatrix
import com.xbtwallet.app.chain.AddressChain
import com.xbtwallet.app.chain.ScannedUtxo
import com.xbtwallet.app.ui.signers.SignerChainState
import com.xbtwallet.app.ui.signers.formatSats
import com.xbtwallet.app.ui.signers.shortenedMiddle

/**
 * Hand out one address: a QR to point a payer's camera at, and the address as
 * text to copy.
 *
 * This is the receive half of the wallet's primary job — *get money into it* —
 * and it is deliberately offline-complete: an address is arithmetic over the
 * account xpub ([SignerChainState.scanner]), so the QR renders whether or not a
 * server is reachable. The scan's only job here is to *annotate*: [address] is
 * already resolved, and the snapshot says whether it has been used and what it
 * holds.
 *
 * **The address is resolved before this screen exists.** [address] is
 * non-null and comes from whoever navigated here — the signer detail screen
 * derives the first unused address (showing a spinner on its own button while
 * it does), and an addresses-list row passes the address the user tapped, both
 * verbatim. That is why there is no loading state here: by the time this
 * composes, the address is already in hand, so the QR and the text render
 * synchronously on the first frame and the screen never flashes "Deriving…".
 *
 * **Two ways in**, and they show different addresses:
 *
 * - from a signer's own **Receive** button: the "first unused" address (the
 *   caller resolved it) — labelled as such when the scan agrees;
 * - from a row in the **addresses list**: exactly the address the user tapped,
 *   verbatim, so the QR matches the row they were reading.
 *
 * **What the scan adds.** When the shown address has history, a warning says so
 * — paying into a used address links the payments on chain — and the address's
 * own UTXOs are listed, so a user can see the address is not empty before
 * handing it out. An address the scan never asked about shows neither: the
 * wallet does not know, and it does not pretend to.
 *
 * @param state the open signer's chain state (shared with its other screens).
 * @param address the address to hand out — always known, never derived here.
 * @param onBack pops one level — the addresses list or the signer detail screen.
 */
@Composable
fun ReceiveScreen(
    state: SignerChainState,
    address: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val info = receiveAddressInfo(state.snapshot, address)
    val sourceLabel = receiveSourceLabel(state, info)

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

            if (state.accountKey == null) {
                Text(
                    text = "This xpub's stored key could not be read. Delete and re-import it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                QrPanel(payload = qrPayload(address))

                Spacer(modifier = Modifier.height(16.dp))

                // The address itself, selectable by hand as well as copyable by
                // button — both routes to the clipboard, because a wallet is a
                // tool for moving an address around.
                SelectionContainer {
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        copyToClipboard(context, address)
                        Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Copy address")
                }

                if (info?.used == true) {
                    Spacer(modifier = Modifier.height(16.dp))
                    UsedAddressWarning()
                }

                val utxos = info?.utxos.orEmpty()
                if (utxos.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    AddressUtxos(utxos)
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
 * The caption under the address: what this address *is*, as far as the scan can
 * honestly say.
 *
 * Three cases. With no snapshot at all, nothing has been asked of the server, so
 * the caption says the address is unverified rather than claiming it is unused —
 * handing out an address nobody checked is how an address with history gets
 * reused. When the shown address *is* the snapshot's own first-unused answer,
 * the caption names the rule (the signer's Receive button resolved exactly this
 * one). Everything else — a row the user picked, an address outside the scanned
 * range — reads plainly as "Receive address", because there is no rule to name.
 */
private fun receiveSourceLabel(state: SignerChainState, info: ReceiveAddressInfo?): String {
    val snapshot = state.snapshot
        ?: return "Receive address \u2014 not yet verified on chain, sync to confirm unused"
    val scanned = snapshot.addresses(AddressChain.RECEIVE)
    if (scanned.isNotEmpty()) {
        val firstUnusedIndex = resolveReceiveIndex(scanned).first
        if (info?.chain == AddressChain.RECEIVE && info.index == firstUnusedIndex && !info.used) {
            return "First unused receive address"
        }
    }
    return "Receive address"
}

/**
 * The privacy warning for an address that already carries history.
 *
 * Paying into an address twice links the two transactions — and everyone
 * watching the chain can see the connection — so this is the one thing a receive
 * screen must say out loud. It is a warning, not a block: the user may still
 * have a reason to reuse the address, and the wallet does not get to refuse.
 *
 * Plain text only — this build carries no icon pack, and a dependency for one
 * glyph is the surface this project keeps small.
 */
@Composable
private fun UsedAddressWarning(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = "This address has been used before. For your privacy, prefer a fresh address.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/**
 * The outputs sitting at the shown address, so a user can see it is not empty
 * before handing it out.
 *
 * One line per output: its outpoint (`txid:vout`, txid elided in the middle like
 * every other hash in the app), its amount, and whether it has confirmed. The
 * amounts are summed in the header with the same [formatSats] the UTXO screen
 * uses, so the two screens can never disagree about a number.
 */
@Composable
private fun AddressUtxos(utxos: List<ScannedUtxo>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        val total = utxos.sumOf { it.utxo.satoshis }
        Text(
            text = "${utxos.size} UTXO${if (utxos.size == 1) "" else "s"} at this address " +
                "\u00b7 ${formatSats(total)} sats",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        utxos.forEach { scanned ->
            val utxo = scanned.utxo
            Text(
                text = buildString {
                    append(shortenedMiddle(utxo.txHash)).append(':').append(utxo.txPos)
                    append("   ").append(formatSats(utxo.satoshis)).append(" sats")
                    if (!utxo.confirmed) append("   unconfirmed")
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
 * The bitmap is built once per payload (`remember`) at first composition, with
 * one pixel per module and `FilterQuality.None` on the way up to the screen: a
 * QR is binary data, and smoothing its modules is exactly the blur a scanner
 * cannot read. Filling 512×512 pixels is trivial and happens synchronously, so
 * the QR is on screen on the very first frame.
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
