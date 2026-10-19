package com.xbtwallet.app.ui.receive

import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders [payload] as a QR code and returns the module matrix.
 *
 * ZXing's encoder, and nothing else: `com.google.zxing:core` is already on the
 * compile classpath as a transitive of `com.journeyapps:zxing-android-embedded`
 * (the library the xpub scanner uses), so a receive QR costs **no new
 * dependency** — which matters on a GrapheneOS/F-Droid build where every extra
 * artifact is another thing to vet, and where a Google Play services dependency
 * is simply not an option. The same encoder that will later write the animated
 * PSBT frames already writes this one, and it is the same ZXing core that turns
 * around and decodes the payload back in the JVM tests.
 *
 * Kept free of Android imports on purpose: it returns a [BitMatrix] — plain Java
 * — so the round-trip (encode here, decode with a ZXing reader) is a JVM unit
 * test, with no emulator and no `Bitmap`. Turning the matrix into pixels is the
 * screen's job, one short function away.
 *
 * [size] is the requested width and height in modules. ZXing honours the
 * *smaller* of the request and what it can build, so a payload that needs fewer
 * modules than [size] comes back as an exact multiple of the symbol containing
 * the four-module quiet zone ZXing adds; the screen scales it, with nearest
 * filtering, so the modules stay square rather than being smoothed into a blur a
 * camera could not read.
 *
 * @throws com.google.zxing.WriterException if the payload cannot be encoded —
 *   not reachable for a BIP-21 address, which the alphanumeric mode always fits.
 */
internal fun renderQr(payload: String, size: Int = QR_REQUESTED_SIZE): BitMatrix =
    QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)

/**
 * The module count requested from ZXing. Comfortably above the ~29–57 modules a
 * BIP-21 address needs, so the encoder is never the limit on the symbol's size.
 */
internal const val QR_REQUESTED_SIZE = 512