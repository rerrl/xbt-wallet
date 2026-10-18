package com.xbtwallet.app.crypto

import java.math.BigInteger

/**
 * The extended-key version bytes this wallet accepts.
 *
 * xpub/ypub/zpub all encode the *same* secp256k1 public key, chain code and
 * depth — the version bytes only advertise which script type the exporter had
 * in mind (BIP-44/49/84). xbt-wallet is native-segwit-only, so all three are
 * accepted and then treated identically as a BIP-84-style account key. Keeping
 * the original version around matters only for display and for round-tripping
 * the key back out unchanged.
 */
enum class XpubVersion(val versionBytes: Int, val label: String) {
    XPUB(0x0488B21E, "xpub"),
    YPUB(0x049D7CB2, "ypub"),
    ZPUB(0x04B24746, "zpub"),
    ;

    companion object {
        fun fromVersionBytes(value: Int): XpubVersion? = entries.firstOrNull { it.versionBytes == value }
    }
}

/** Raised when a string cannot be read as a mainnet extended public key. */
class XpubParseException(val reason: Reason, message: String) : IllegalArgumentException(message) {

    /**
     * Why the parse failed. The reasons map one-to-one onto the inline error
     * messages the import screen shows, so the UI never has to string-match a
     * message.
     */
    enum class Reason {
        /** Blank input. */
        EMPTY,

        /** Not Base58 (contains 0/O/I/l or another out-of-alphabet character). */
        BASE58_INVALID,

        /** Base58 decoded but the trailing checksum did not verify. */
        CHECKSUM,

        /** Decoded, but not the 78 bytes an extended key payload is. */
        LENGTH,

        /** Version bytes are not xpub/ypub/zpub — e.g. a testnet or private key header. */
        UNKNOWN_VERSION,

        /** Version bytes are fine but the key data is not a public point. */
        NOT_PUBLIC_KEY,
    }
}

/**
 * A BIP-32 extended public key, with public-only child derivation (CKDpub).
 *
 * Parsing and derivation are implemented from BIP-32 itself. The class holds no
 * private material and [deriveChild] cannot perform a hardened step, because
 * hardened derivation is impossible without the parent private key — that
 * absence is a feature, not an oversight (PLAN.md §2.1).
 *
 * Immutable and Android-free, so the whole derivation path is unit-testable on
 * the JVM against the official BIP-84 vectors.
 */
class ExtendedPublicKey(
    val version: XpubVersion,
    val depth: Int,
    /** Parent fingerprint as the raw big-endian 32-bit value BIP-32 stores. */
    val parentFingerprint: Int,
    /** The child index this key was derived at; `>= 0x80000000` means hardened. */
    val childNumber: Long,
    val chainCode: ByteArray,
    /** The compressed public key (33 bytes). */
    val publicKey: ByteArray,
) {

    init {
        require(chainCode.size == 32) { "chain code must be 32 bytes" }
        require(publicKey.size == 33) { "an extended public key holds a 33-byte compressed key" }
        require(depth in 0..255) { "depth must fit in one byte" }
    }

    /**
     * BIP-32 CKDpub: `I = HMAC-SHA512(chainCode, serP(Kpar) || ser32(i))`, then
     * `Ki = point(IL) + Kpar` and the child chain code is `IR`.
     *
     * Hardened indexes are rejected — parsing a hardened child from a public
     * parent is not possible, and silently returning something else would be a
     * wallet bug of the worst kind.
     */
    fun deriveChild(index: Long): ExtendedPublicKey {
        require(index in 0 until HARDENED_OFFSET) {
            "index $index is hardened; a public key cannot derive a hardened child"
        }

        val data = publicKey + ser32(index)
        val i = Hashes.hmacSha512(chainCode, data)
        val tweak = BigInteger(1, i.copyOfRange(0, 32))
        require(tweak < Secp256k1.N) { "child tweak is not a valid scalar" }

        val parent = Secp256k1.parse(publicKey)
        val child = Secp256k1.add(Secp256k1.multiply(tweak, Secp256k1.G), parent)
            ?: throw IllegalArgumentException("derived child is the point at infinity")

        return ExtendedPublicKey(
            version = version,
            depth = depth + 1,
            parentFingerprint = fingerprint(),
            childNumber = index,
            chainCode = i.copyOfRange(32, 64),
            publicKey = Secp256k1.compress(child),
        )
    }

    /** Applies a relative derivation path, e.g. `listOf(0L, 5L)` for `0/5`. */
    fun derive(relativePath: List<Long>): ExtendedPublicKey =
        relativePath.fold(this) { key, index -> key.deriveChild(index) }

    /** BIP-32 key identifier's parent-fingerprint half: the first four bytes of HASH160. */
    fun fingerprint(): Int {
        val hash = Hashes.hash160(publicKey)
        return ((hash[0].toInt() and 0xFF) shl 24) or
            ((hash[1].toInt() and 0xFF) shl 16) or
            ((hash[2].toInt() and 0xFF) shl 8) or
            (hash[3].toInt() and 0xFF)
    }

    /** The 78-byte serialisation, without the Base58Check checksum. */
    fun toPayload(reencodedAs: XpubVersion = version): ByteArray {
        val out = ByteArray(78)
        out[0] = (reencodedAs.versionBytes ushr 24).toByte()
        out[1] = (reencodedAs.versionBytes ushr 16).toByte()
        out[2] = (reencodedAs.versionBytes ushr 8).toByte()
        out[3] = reencodedAs.versionBytes.toByte()
        out[4] = depth.toByte()
        out[5] = (parentFingerprint ushr 24).toByte()
        out[6] = (parentFingerprint ushr 16).toByte()
        out[7] = (parentFingerprint ushr 8).toByte()
        out[8] = parentFingerprint.toByte()
        out[9] = (childNumber ushr 24).toByte()
        out[10] = (childNumber ushr 16).toByte()
        out[11] = (childNumber ushr 8).toByte()
        out[12] = childNumber.toByte()
        chainCode.copyInto(out, 13)
        publicKey.copyInto(out, 45)
        return out
    }

    /** Base58Check-encodes this key with the given version bytes. */
    fun reencode(reencodedAs: XpubVersion): String = Base58Check.encode(toPayload(reencodedAs))

    companion object {

        /** Index at and above which a child number is hardened. */
        const val HARDENED_OFFSET = 0x80000000L

        /** Payload length of a BIP-32 extended key, before the Base58Check checksum. */
        const val PAYLOAD_LENGTH = 78

        /**
         * Parses a mainnet extended public key in any accepted version.
         *
         * Order of checks matters for the error messages: Base58 alphabet, then
         * checksum, then length, then version bytes, then the key data. Throws
         * [XpubParseException] with the specific [XpubParseException.Reason].
         */
        fun parse(text: String): ExtendedPublicKey {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                throw XpubParseException(XpubParseException.Reason.EMPTY, "no key was entered")
            }

            val payload = try {
                Base58Check.decode(trimmed)
            } catch (e: Base58Exception) {
                throw XpubParseException(XpubParseException.Reason.BASE58_INVALID, e.message.orEmpty())
            } catch (e: Base58CheckException) {
                throw XpubParseException(XpubParseException.Reason.CHECKSUM, e.message.orEmpty())
            }

            if (payload.size != PAYLOAD_LENGTH) {
                throw XpubParseException(
                    XpubParseException.Reason.LENGTH,
                    "an extended key payload is $PAYLOAD_LENGTH bytes, got ${payload.size}",
                )
            }

            val versionBytes = readInt(payload, 0)
            val version = XpubVersion.fromVersionBytes(versionBytes)
                ?: throw XpubParseException(
                    XpubParseException.Reason.UNKNOWN_VERSION,
                    "version bytes 0x${versionBytes.toString(16)} are not xpub, ypub or zpub",
                )

            val keyData = payload.copyOfRange(45, 78)
            // A private extended key encodes 0x00 || 32-byte secret here. Refusing
            // it explicitly is a hard requirement: no secret may ever be imported.
            if (keyData[0] != 0x02.toByte() && keyData[0] != 0x03.toByte()) {
                throw XpubParseException(
                    XpubParseException.Reason.NOT_PUBLIC_KEY,
                    "key data is not a compressed public key",
                )
            }
            try {
                Secp256k1.decompress(keyData)
            } catch (e: IllegalArgumentException) {
                throw XpubParseException(XpubParseException.Reason.NOT_PUBLIC_KEY, e.message.orEmpty())
            }

            return ExtendedPublicKey(
                version = version,
                depth = payload[4].toInt() and 0xFF,
                parentFingerprint = readInt(payload, 5),
                childNumber = readInt(payload, 9).toLong() and 0xFFFFFFFFL,
                chainCode = payload.copyOfRange(13, 45),
                publicKey = keyData,
            )
        }

        private fun readInt(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)

        private fun ser32(index: Long): ByteArray = byteArrayOf(
            (index ushr 24).toByte(),
            (index ushr 16).toByte(),
            (index ushr 8).toByte(),
            index.toByte(),
        )
    }
}
