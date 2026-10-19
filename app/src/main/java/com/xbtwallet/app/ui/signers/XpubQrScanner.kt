package com.xbtwallet.app.ui.signers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/**
 * The xpub scanner as a panel, not a screen.
 *
 * This deliberately does **not** launch journeyapps' `CaptureActivity` through
 * `ScanContract`. That activity is a camera app of its own: it takes over the
 * whole screen, and the library's flow can lock it landscape while every other
 * screen in this app is portrait. What is wanted is the opposite — a small
 * portrait window on the import screen, with the text field still visible
 * beneath it so pasting stays the fallback.
 *
 * So the library is used for what it is good at (its `DecoratedBarcodeView` is
 * a plain `LinearLayout` that owns a `SurfaceView` preview and a ZXing decoder)
 * and the app owns the rest. The view is embedded with `AndroidView`, in this
 * app's own portrait activity, and *nothing here touches orientation*: there is
 * no `setOrientationLocked`, no `requestedOrientation`, no manifest entry — the
 * panel simply inherits the portrait window it is drawn in.
 *
 * Only QR codes are decoded. The view is configured for exactly that format, so
 * a barcode of another kind in front of the camera is not reported at all
 * rather than being routed to the validator as nonsense.
 *
 * [onResult] fires once per scan: `decodeSingle` stops the camera's decode
 * loop after the first hit, and the caller is expected to hide the panel (which
 * disposes this composable and pauses the camera). The payload is passed on
 * verbatim — routing and validation are [XpubScanRoute]'s and
 * [com.xbtwallet.app.crypto.validateXpubInput]'s job, not this file's.
 */
@Composable
fun XpubQrScannerPanel(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // The decode callback outlives any single composition, so it reads the
    // latest `onResult` through rememberUpdatedState rather than capturing the
    // first one.
    val currentOnResult by rememberUpdatedState(onResult)

    val barcodeView = remember {
        DecoratedBarcodeView(context).apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            // The library's status line is meant for the full-screen prompt; in
            // a 280dp panel it is noise. The caption below says the same thing.
            setStatusText("")
            decodeSingle { result -> result.text?.let { text -> currentOnResult(text) } }
        }
    }

    // The import screen stays composed underneath the panel, so there is no
    // "screen was stopped" signal to react to: the camera lives exactly as long
    // as this composable does. Resume on enter, pause on dispose.
    DisposableEffect(barcodeView) {
        barcodeView.resume()
        onDispose { barcodeView.pause() }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Scan the account xpub QR",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AndroidView(
                factory = { barcodeView },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
            )

            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Cancel")
            }
        }
    }
}
