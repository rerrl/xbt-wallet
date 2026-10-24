package com.xbtwallet.app.crypto

import java.math.BigInteger

/**
 * A BIP-32 extended **private** key, with private child key derivation (CKDpriv).
 *
 * This is the hot wallet's only holder of secret material. Two rules govern the
 * file, and both are the point of it:
 *
 * 1. **Private material is never serialised.** There is deliberately no
 *    xprv/zprv Base58 encoder: unlike the Android-free watch-only side, which
 *    round-trips extended *public* keys, a private key here has no string form
 *    at all. The only artifact that leaves this class is
 *    [toPublic], and the only thing that may be done with that is
 *    `reencode(ZPUB)` (PLAN.md §2.1).
 * 2. **Nothing is logged.** No `toString`, no `println`, no exception message
 *    carries key bytes — the messages name the failure, never the value.
 *
 * CKDpriv, from BIP-32:
 *
 * - hardened (`i >= 2^31`): `I = HMAC-SHA512(c_par, 0x00 || ser256(k_par) || ser32(i))`
 * - normal: `I = HMAC-SHA512(c_par, serP(K_par) || ser32(i))`
 * - `k_i = (I_L + k_par) mod n`, `c_i = I_R`; `I_L >= n` and `k_i == 0` are refused.
 *
 * Android-free (JDK [BigInteger] and the app's own [Hashes]/[Secp256k1]), so the
 * whole tree is a JVM unit test against the official BIP-32/BIP-84 vectors.
 */
class ExtendedPrivateKey internal constructor(
    /** The 32-byte scalar, `k`. Internal: [Ecdsa] and [PsbtSigner] consume it directly. */
    val privateKey: BigInteger,
    /** The 32-byte chain code, `c`. */
    val chainCode: ByteArray,
    /** Depth in the derivation tree; the master key is 0. */
    val depth: Int,
    /**
     * The fingerprint of the parent key, kept so [toPublic] can rebuild a
     * byte-exact [ExtendedPublicKey] (and therefore an exact zpub). Never
     * serialised on its own.
     */
    internal val parentFingerprint: Int = 0,
    /** The index this key was derived at (with the hardened flag in bit 31). */
    internal val childNumber: Long = 0L,
) {

    init {
        require(chainCode.size == 32) { "a chain code is 32 bytes, got ${chainCode.size}" }
        require(depth in 0..255) { "depth must fit in one byte" }
        require(privateKey.signum() > 0 && privateKey < Secp256k1.N) {
            "a private scalar lies in [1, n-1]"
        }
    }

    /**
     * The public point `k*G`, computed at most once.
     *
     * Used both for the non-hardened `serP(K_par)` input to CKDpriv and for
     * [toPublic]. It is the expensive half of a derivation, and the key is
     * immutable, so a single lazy multiply serves the whole lifetime.
     */
    private val publicPoint: Secp256k1.Point by lazy {
        Secp256k1.multiply(privateKey, Secp256k1.G)
            ?: throw IllegalStateException("a scalar in [1, n-1] cannot multiply to infinity")
    }

    /**
     * Derives one child. The index is hardened exactly when it is at or above
     * [ExtendedPublicKey.HARDENED_OFFSET]; the two branches differ only in
     * whether the parent's private scalar or its public point is hashed.
     */
    fun deriveChild(index: Long): ExtendedPrivateKey {
        require(index in 0..0xFFFFFFFFL) { "a child index is a uint32, got $index" }
        val hardened = index >= ExtendedPublicKey.HARDENED_OFFSET

        val data = if (hardened) {
            byteArrayOf(0x00) + Secp256k1.toFixedLength(privateKey, 32) + ser32(index)
        } else {
            Secp256k1.compress(publicPoint) + ser32(index)
        }

        val i = Hashes.hmacSha512(chainCode, data)
        val tweak = BigInteger(1, i.copyOfRange(0, 32))
        require(tweak < Secp256k1.N) { "the derived tweak is not a valid scalar" }

        val child = tweak.add(privateKey).mod(Secp256k1.N)
        require(child.signum() != 0) { "the derived child key is zero" }

        return ExtendedPrivateKey(
            privateKey = child,
            chainCode = i.copyOfRange(32, 64),
            depth = depth + 1,
            parentFingerprint = fingerprint(),
            childNumber = index,
        )
    }

    /** Applies a relative derivation path, e.g. `listOf(0L, 5L)` for `0/5`. */
    fun derive(path: List<Long>): ExtendedPrivateKey =
        path.fold(this) { key, index -> key.deriveChild(index) }

    /**
     * The public half, rebuilt exactly: same depth, chain code, parent
     * fingerprint and child number, so re-encoding it as a zpub reproduces the
     * account key BIP-84 pins.
     */
    fun toPublic(): ExtendedPublicKey = ExtendedPublicKey(
        version = XpubVersion.XPUB,
        depth = depth,
        parentFingerprint = parentFingerprint,
        childNumber = childNumber,
        chainCode = chainCode,
        publicKey = Secp256k1.compress(publicPoint),
    )

    /** BIP-32 key identifier: the first four bytes of HASH160(serP(K)). */
    fun fingerprint(): Int = toPublic().fingerprint()

    companion object {

        /** The HMAC key BIP-32 fixes for master key generation. */
        private val MASTER_HMAC_KEY = "Bitcoin seed".toByteArray(Charsets.US_ASCII)

        /**
         * Master key from a seed: `I = HMAC-SHA512("Bitcoin seed", seed)`, with
         * `k = I_L` and `c = I_R`. A seed whose `I_L` is not a valid scalar is
         * refused rather than silently repaired.
         */
        fun fromSeed(seed: ByteArray): ExtendedPrivateKey {
            val i = Hashes.hmacSha512(MASTER_HMAC_KEY, seed)
            val key = BigInteger(1, i.copyOfRange(0, 32))
            require(key.signum() != 0 && key < Secp256k1.N) {
                "the seed yields an invalid master key"
            }
            return ExtendedPrivateKey(key, i.copyOfRange(32, 64), depth = 0)
        }

        /**
         * Parses a BIP-32 derivation path, e.g.
         * `"m/84'/0'/0'"` -> `[0x80000054, 0x80000000, 0x80000000]`.
         *
         * `m` alone is the master key and yields an empty list. Each step is a
         * non-negative index, optionally marked hardened with an apostrophe (or
         * `h`/`H`), which sets bit 31. Both kinds of step are legal — the official
         * BIP-32 test vectors mix them (`m/0H/1`, `m/0/2147483647H/1`) — so this
         * parses the whole BIP-32 grammar rather than only account paths.
         *
         * Throws [IllegalArgumentException] for a path that does not start at `m`
         * (e.g. `"x/84'/0'/0'"`), an empty string, an empty or non-numeric step,
         * a negative step, or an index that does not fit a hardened uint32.
         */
        fun parsePath(path: String): List<Long> {
            val parts = path.split("/")
            require(parts[0] == "m") {
                "a derivation path starts at the master key: \"m/...\""
            }
            return parts.drop(1).map { step ->
                require(step.isNotEmpty()) { "the path has an empty step" }
                val hardened = step.last() == '\'' || step.last() == 'h' || step.last() == 'H'
                val digits = if (hardened) step.dropLast(1) else step
                require(digits.isNotEmpty() && digits.all { it in '0'..'9' }) {
                    "a derivation step is a non-negative index, optionally with a hardened marker"
                }
                val value = digits.toLongOrNull()
                    ?: throw IllegalArgumentException("a derivation index is out of range")
                require(value in 0 until ExtendedPublicKey.HARDENED_OFFSET) {
                    "a derivation index must be below 2^31"
                }
                if (hardened) value + ExtendedPublicKey.HARDENED_OFFSET else value
            }
        }

        /** Big-endian 32-bit, BIP-32's `ser32`. */
        private fun ser32(value: Long): ByteArray = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }
}
