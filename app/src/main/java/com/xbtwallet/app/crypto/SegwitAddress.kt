package com.xbtwallet.app.crypto

/**
 * Native segwit (P2WPKH) address encoding.
 *
 * Address derivation for this wallet is deliberately narrow: it assumes an
 * account-level key and produces `bc1q...` addresses (witness version 0,
 * 20-byte HASH160 program) no matter which extended-key version bytes the user
 * pasted. That is the "treated as native segwit" promise the import screen
 * makes. Witness versions above 0 use bech32m (BIP-350) and are supported for
 * completeness, since the code is the same minus a checksum constant.
 */
object SegwitAddress {

    const val MAINNET_HRP = "bc"

    /** Encodes `hrp1<version><program>` with the checksum the version requires. */
    fun encode(hrp: String, witnessVersion: Int, program: ByteArray): String {
        require(witnessVersion in 0..16) { "witness version $witnessVersion is outside 0..16" }
        if (witnessVersion == 0) {
            require(program.size == 20 || program.size == 32) {
                "a version 0 program is 20 or 32 bytes, got ${program.size}"
            }
        } else {
            require(program.size in 2..40) { "a witness program is 2..40 bytes, got ${program.size}" }
        }

        val constant = if (witnessVersion == 0) Bech32.BECH32_CONSTANT else Bech32.BECH32M_CONSTANT
        val groups = byteArrayOf(witnessVersion.toByte()) + Bech32.convertBits(program, 8, 5, true)
        return Bech32.encode(hrp, groups, constant)
    }

    /** The address for a 20-byte HASH160 public-key program: the wallet's only address type today. */
    fun encodeP2wpkh(hrp: String, hash160: ByteArray): String {
        require(hash160.size == 20) { "a P2WPKH program is 20 bytes, got ${hash160.size}" }
        return encode(hrp, 0, hash160)
    }

    /** Exposed for tests and for decoding an address back into its program. */
    fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray =
        Bech32.convertBits(data, fromBits, toBits, pad)
}
