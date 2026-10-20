package com.xbtwallet.app.ui.receive

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receive QR, encoded and then *read back* — on the JVM, with no emulator
 * and no `Bitmap`.
 *
 * A QR screen that draws the wrong modules looks identical to one that draws the
 * right ones, so the assertion that matters is not "something was rendered" but
 * "a conforming decoder gets the payload back". ZXing encodes and ZXing decodes,
 * and the payload that goes in is the payload that comes out — which also pins
 * the BIP-21 shape (scheme, colon, address) and the upper-casing that makes it
 * alphanumeric-mode dense.
 */
class QrPayloadTest {

    /** The payload's exact shape: `BITCOIN:` and the address, upper-cased. */
    @Test
    fun `payload is the uppercase BIP-21 URI`() {
        assertEquals(
            "BITCOIN:BC1QAR0SRRRF3V0K7WP0W8L0A5C9N5N47F4U2LR5JX",
            qrPayload("bc1qar0srrrf3v0k7wp0w8l0a5c9n5n47f4u2lr5jx"),
        )
    }

    /** Mixed-case input still comes out uniformly upper case. */
    @Test
    fun `payload uppercases the whole URI`() {
        assertEquals("BITCOIN:BC1QABC", qrPayload("bc1Qabc"))
    }

    @Test
    fun `the rendered QR decodes back to the payload`() {
        val payload = qrPayload("bc1qar0srrrf3v0k7wp0w8l0a5c9n5n47f4u2lr5jx")

        val matrix = renderQr(payload)
        val decoded = QRCodeReader().decode(asBinaryBitmap(matrix))

        assertEquals("the QR must carry the payload verbatim", payload, decoded.text)
    }

    @Test
    fun `the matrix is large enough to be readable and carries a quiet zone`() {
        val matrix = renderQr(qrPayload("bc1qar0srrrf3v0k7wp0w8l0a5c9n5n47f4u2lr5jx"))

        assertTrue("a QR this size is never a 1x1 symbol", matrix.width >= 21)
        assertEquals("ZXing emits square symbols", matrix.width, matrix.height)
        // The four-module quiet zone: the corner must be light, or a camera
        // aimed at the phone has no margin to find the finder patterns against.
        assertTrue("top-left corner is quiet (light)", !matrix.get(0, 0))
    }

    /**
     * A [com.google.zxing.common.BitMatrix] as the decoder wants it: one ARGB
     * pixel per module. Black modules are opaque black, light modules opaque
     * white — this is exactly the mapping the screen's `Bitmap` build uses, so
     * what is decoded here is what is drawn there.
     */
    private fun asBinaryBitmap(matrix: com.google.zxing.common.BitMatrix): BinaryBitmap {
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            val x = i % matrix.width
            val y = i / matrix.width
            if (matrix.get(x, y)) BLACK else WHITE
        }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        return BinaryBitmap(HybridBinarizer(source))
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}