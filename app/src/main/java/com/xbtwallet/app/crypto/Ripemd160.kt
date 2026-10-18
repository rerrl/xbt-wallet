package com.xbtwallet.app.crypto

/**
 * RIPEMD-160, implemented from the published specification
 * (Dobbertin, Bosselaers, Preneel — "RIPEMD-160: A Strengthened Version of
 * RIPEMD", 1996).
 *
 * Why hand-rolled: Bitcoin's HASH160 is `RIPEMD160(SHA256(x))`, and neither the
 * JDK nor Android ships a RIPEMD-160 provider. The alternatives are Bouncy
 * Castle (a large dependency for one 160-bit hash) or this file. It is
 * deliberately a straight transcription of the spec — two parallel 80-round
 * chains, left and right — so it can be read side by side with the paper, and
 * it is pinned by the published test vectors in `HashesTest`.
 *
 * Internal to the module: the wallet's public crypto surface is [Hashes].
 */
internal object Ripemd160 {

    /** Message word order, left line (r). */
    private val R_LEFT = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
        3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
        1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
        4, 0, 5, 9, 7, 12, 2, 10, 14, 1, 3, 8, 11, 6, 15, 13,
    )

    /** Message word order, right line (r'). */
    private val R_RIGHT = intArrayOf(
        5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
        6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
        15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
        8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
        12, 15, 10, 4, 1, 5, 8, 7, 6, 2, 13, 14, 0, 3, 9, 11,
    )

    /** Rotation amounts, left line (s). */
    private val S_LEFT = intArrayOf(
        11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
        7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
        11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
        11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
        9, 15, 5, 11, 6, 8, 13, 12, 5, 12, 13, 14, 11, 8, 5, 6,
    )

    /** Rotation amounts, right line (s'). */
    private val S_RIGHT = intArrayOf(
        8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
        9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
        9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
        15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
        8, 5, 12, 9, 12, 5, 14, 6, 8, 13, 6, 5, 15, 13, 11, 11,
    )

    private val K_LEFT = intArrayOf(0x00000000, 0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC.toInt(), 0xA953FD4E.toInt())
    private val K_RIGHT = intArrayOf(0x50A28BE6, 0x5C4DD124, 0x6D703EF3, 0x7A6D76E9, 0x00000000)

    fun digest(message: ByteArray): ByteArray {
        val padded = pad(message)

        // 160-bit state: five 32-bit little-endian words.
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()

        val x = IntArray(16)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) x[i] = readLittleEndianInt(padded, offset + i * 4)

            var al = h0; var bl = h1; var cl = h2; var dl = h3; var el = h4
            var ar = h0; var br = h1; var cr = h2; var dr = h3; var er = h4

            for (j in 0 until 80) {
                val round = j / 16

                // Left line runs the functions f1..f5; the right line runs the
                // same functions mirrored (f5..f1). That mirroring is the whole
                // point of RIPEMD-160 versus the original RIPEMD.
                var t = Integer.rotateLeft(
                    al + f(j, bl, cl, dl) + x[R_LEFT[j]] + K_LEFT[round],
                    S_LEFT[j],
                ) + el
                al = el; el = dl; dl = Integer.rotateLeft(cl, 10); cl = bl; bl = t

                t = Integer.rotateLeft(
                    ar + f(79 - j, br, cr, dr) + x[R_RIGHT[j]] + K_RIGHT[round],
                    S_RIGHT[j],
                ) + er
                ar = er; er = dr; dr = Integer.rotateLeft(cr, 10); cr = br; br = t
            }

            val t = h1 + cl + dr
            h1 = h2 + dl + er
            h2 = h3 + el + ar
            h3 = h4 + al + br
            h4 = h0 + bl + cr
            h0 = t

            offset += 64
        }

        val out = ByteArray(20)
        writeLittleEndianInt(out, 0, h0)
        writeLittleEndianInt(out, 4, h1)
        writeLittleEndianInt(out, 8, h2)
        writeLittleEndianInt(out, 12, h3)
        writeLittleEndianInt(out, 16, h4)
        return out
    }

    /** The spec's five round functions, selected by round index j (0..79). */
    private fun f(j: Int, a: Int, b: Int, c: Int): Int = when (j / 16) {
        0 -> a xor b xor c
        1 -> (a and b) or (a.inv() and c)
        2 -> (a or b.inv()) xor c
        3 -> (a and c) or (b and c.inv())
        else -> a xor (b or c.inv())
    }

    /**
     * MD-style padding: a 0x80 byte, zeros, then the message length in bits as a
     * little-endian 64-bit integer, so the total is a multiple of 64 bytes.
     */
    private fun pad(message: ByteArray): ByteArray {
        val bitLength = message.size.toLong() * 8
        var paddingLength = 64 - ((message.size + 9) % 64)
        if (paddingLength == 64) paddingLength = 0
        val out = ByteArray(message.size + 9 + paddingLength)
        message.copyInto(out)
        out[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            out[out.size - 8 + i] = ((bitLength ushr (8 * i)) and 0xFF).toByte()
        }
        return out
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeLittleEndianInt(out: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) {
            out[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }
}
