package com.xbtwallet.app.ui.signers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.delay

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
 *
 * [showCancel] defaults to true, so the callers that can dismiss the panel keep
 * their Cancel; a screen where the scanner is the only way in passes false and
 * hides the panel by itself (on the scan it wanted) instead.
 *
 * @param showCancel whether the panel draws its Cancel button. False on a screen
 *   where the scanner cannot be dismissed.
 */
@Composable
fun XpubQrScannerPanel(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    showCancel: Boolean = true,
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

    // The screen underneath stays composed, so there is no "screen was stopped"
    // signal to react to: the camera lives exactly as long as this composable
    // does. Resume on enter, pause on dispose.
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

            if (showCancel) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

/**
 * How long the animated scan waits for a *new* part before it gives up.
 *
 * The clock restarts on every newly assembled part, so this is "no progress for
 * this long", not "the whole scan took this long". A signer's animation delivers
 * a full export in a few seconds; a minute of no new part means the camera is
 * not seeing the signer (or is seeing only some of its frames), and saying so is
 * more useful than spinning forever. What a timeout *does* is the caller's
 * decision: this panel only reports it, and the caller either resets its
 * assembly and keeps the panel up or takes the panel down.
 */
private const val ANIMATED_SCAN_TIMEOUT_MILLIS = 60_000L

/**
 * The **animated** scanner: the same panel as [XpubQrScannerPanel], but decoding
 * every frame instead of stopping at the first.
 *
 * A `crypto-account` export is usually too long for one QR code, so the signer
 * animates it as a fountain-coded sequence of frames. The frames may be shown in
 * any order and any sufficiently large subset completes the message, so the
 * scanner must not treat a frame as "the answer" and stop: it hands each one to
 * [onFrame] and keeps going until the caller dismisses it (which it does when
 * the assembly reports the export complete). The caller owns that decision, not
 * this panel — the panel knows nothing about UR.
 *
 * `decodeContinuous` is what makes that possible, and it is the whole reason
 * this is a second composable rather than a flag on the first: the xpub panel's
 * `decodeSingle` deliberately stops the decode loop after one hit.
 *
 * [progress] is the caller's assembly estimate (0–100) and is rendered here as a
 * percentage and a bar, so the user can see the export filling in — the
 * fountain-code equivalent of a download progress bar. It is also what arms the
 * timeout: each new value means a new part arrived, which restarts the clock.
 *
 * The payload is passed on verbatim. Routing and decoding are
 * [com.xbtwallet.app.crypto.UrAccountAssembler]'s job, not this file's.
 *
 * [showCancel] defaults to true so the callers that can dismiss the panel keep
 * their Cancel; a screen where the scanner is the only way in passes false.
 *
 * @param showCancel whether the panel draws its Cancel button.
 */
@Composable
fun UrQrScannerPanel(
    progress: Int,
    onFrame: (String) -> Unit,
    onCancel: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
    showCancel: Boolean = true,
) {
    val context = LocalContext.current

    // The callbacks outlive any single composition, so they are read through
    // rememberUpdatedState rather than captured once.
    val currentOnFrame by rememberUpdatedState(onFrame)
    val currentOnTimeout by rememberUpdatedState(onTimeout)

    val barcodeView = remember {
        DecoratedBarcodeView(context).apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            setStatusText("")
        }
    }

    // Continuous decoding: every frame the camera reads is offered to the
    // assembler, duplicates included (the fountain decoder dedupes them itself).
    // The camera lives exactly as long as this composable does — resume on
    // enter, pause on dispose — because the screen underneath stays composed.
    DisposableEffect(barcodeView) {
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                result.text?.let { text -> currentOnFrame(text) }
            }
        })
        barcodeView.resume()
        onDispose { barcodeView.pause() }
    }

    // Re-keyed on progress: every newly assembled part restarts the clock, so
    // the timeout measures a stall in the animation rather than the length of
    // the scan. Dismissing the panel (which completion does) cancels this.
    LaunchedEffect(progress) {
        delay(ANIMATED_SCAN_TIMEOUT_MILLIS)
        currentOnTimeout()
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Point the camera at the signer's animated export QR",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AndroidView(
                factory = { barcodeView },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
            )

            LinearProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Assembling the export \u2014 $progress%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (showCancel) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

/**
 * The **signed-transaction** scanner: the animated panel again, this time for
 * the PSBT coming back from a signer.
 *
 * It is the same embed as [UrQrScannerPanel] — `DecoratedBarcodeView`, decoding
 * continuously, every frame handed to the caller — with two differences that
 * matter for this step of the send flow:
 *
 * - [message] is a caller-owned line under the bar, and it is where a *refusal*
 *   is shown: an xpub export scanned here, a torn frame, a PSBT whose signature
 *   is not unified. The panel does not classify anything itself (routing and
 *   decoding are [com.xbtwallet.app.crypto.UrPsbtAssembler]'s job); it only
 *   renders what the caller tells it and keeps decoding, so "scan again" is
 *   simply the caller clearing the message.
 * - the caption names the signed PSBT specifically, because by this point in the
 *   flow the user has an unsigned PSBT on one screen and a signer in front of
 *   them, and "point it at the export" would be the wrong instruction.
 *
 * The payload is passed on verbatim, exactly as the other two panels pass it.
 *
 * [showCancel] defaults to true so the send flow keeps the Cancel it has always
 * had.
 *
 * @param showCancel whether the panel draws its Cancel button.
 */
@Composable
fun AnimatedUrScannerPanel(
    progress: Int,
    message: String?,
    onFrame: (String) -> Unit,
    onCancel: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
    showCancel: Boolean = true,
) {
    val context = LocalContext.current

    val currentOnFrame by rememberUpdatedState(onFrame)
    val currentOnTimeout by rememberUpdatedState(onTimeout)

    val barcodeView = remember {
        DecoratedBarcodeView(context).apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            setStatusText("")
        }
    }

    DisposableEffect(barcodeView) {
        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                result.text?.let { text -> currentOnFrame(text) }
            }
        })
        barcodeView.resume()
        onDispose { barcodeView.pause() }
    }

    // Re-keyed on progress and on the message: a newly assembled part restarts
    // the stall clock, and a refusal the user has not reacted to does not expire
    // the panel out from under them.
    LaunchedEffect(progress, message) {
        delay(ANIMATED_SCAN_TIMEOUT_MILLIS)
        currentOnTimeout()
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Point the camera at your signer's signed transaction QR",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AndroidView(
                factory = { barcodeView },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
            )

            LinearProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Assembling the signed transaction \u2014 $progress%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (showCancel) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}
