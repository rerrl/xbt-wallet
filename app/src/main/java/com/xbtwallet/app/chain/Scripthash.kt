package com.xbtwallet.app.chain

import com.xbtwallet.app.crypto.Hashes

/**
 * Electrum's *scripthash*: the 32-byte lookup key a server indexes scripts by.
 *
 * The Electrum protocol defines it as `sha256(scriptPubKey)` with the bytes
 * **reversed**, rendered as lowercase hex. Reversing looks arbitrary but is not:
 * Electrum presents hashes in the same byte order as a block explorer's txid, so
 * a scripthash reads the way a user expects a hash to read. Getting the reversal
 * wrong is the classic "everything looks unused" bug, so it is pinned by a
 * vector test.
 *
 * This file also builds the one script this wallet ever creates: a P2WPKH
 * scriptPubKey, `OP_0 PUSH20 <hash160>` (`0x00 0x14` then the 20-byte program).
 * The wallet is native-segwit-only, so that is the whole vocabulary it needs.
 */
object Scripthash {

    /** `OP_0` (witness version 0) followed by a 20-byte push: the P2WPKH script. */
    fun p2wpkhScript(hash160: ByteArray): ByteArray {
        require(hash160.size == 20) { "a P2WPKH program is 20 bytes, got ${hash160.size}" }
        return byteArrayOf(0x00, 0x14) + hash160
    }

    /**
     * The Electrum scripthash of a scriptPubKey: `sha256(script)` reversed, as
     * lowercase hex.
     */
    fun of(script: ByteArray): String {
        val digest = Hashes.sha256(script)
        // Reverse in place-free style: the JDK hands back a fresh array, so a
        // copy is not needed, but reversing explicitly keeps the intent obvious.
        val reversed = ByteArray(digest.size) { digest[digest.size - 1 - it] }
        return toHex(reversed)
    }

    /** Convenience: the scripthash of a P2WPKH address's 20-byte program. */
    fun ofP2wpkh(hash160: ByteArray): String = of(p2wpkhScript(hash160))

    /** Lowercase hex, two digits per byte. */
    fun toHex(bytes: ByteArray): String {
        val hex = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(hex[value ushr 4]).append(hex[value and 0x0F])
        }
        return out.toString()
    }
}
