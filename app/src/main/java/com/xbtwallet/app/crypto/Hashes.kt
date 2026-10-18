package com.xbtwallet.app.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The handful of hash primitives a watch-only wallet needs.
 *
 * SHA-256 and HMAC-SHA512 come from the JDK. RIPEMD-160 does not exist in the
 * Android/JDK providers, so it is implemented from the public specification in
 * [Ripemd160] rather than pulling in Bouncy Castle (PLAN.md §2.4: every added
 * library is attack surface, and a wallet's attack surface is its dependency
 * tree).
 */
object Hashes {

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun ripemd160(data: ByteArray): ByteArray = Ripemd160.digest(data)

    /** Bitcoin's HASH160: RIPEMD-160 of SHA-256 — the payload of a P2WPKH program. */
    fun hash160(data: ByteArray): ByteArray = ripemd160(sha256(data))

    /** HMAC-SHA512, the PRF used by BIP-32's child key derivation. */
    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }
}
