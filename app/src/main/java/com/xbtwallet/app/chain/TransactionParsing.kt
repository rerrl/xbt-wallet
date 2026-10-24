package com.xbtwallet.app.chain

import com.xbtwallet.app.crypto.Hashes

/**
 * Decoding of a raw Bitcoin transaction, and nothing else.
 *
 * **Why the wallet parses transactions at all.** `get_history` says a
 * transaction touched one of our addresses; it does not say whether value came
 * in or went out, and it does not say how much. The signer screen needs the
 * wallet's *net* effect per transaction ("+0.00012345 XBT in" / "-… out"), and
 * the only honest way to get it is to read the transaction itself: sum the
 * outputs that pay our scripts, subtract the value of our own coins it spends.
 * That is what `blockchain.transaction.get` returns hex for, and what this file
 * turns into inputs and outputs.
 *
 * **Scope.** This is a parser, not a validator: it reads the serialisation the
 * protocol hands over (legacy and segwit/BIP-144) and refuses anything it cannot
 * read exactly. It does not verify signatures, does not know about sighashes and
 * does not interpret scripts — comparing 20-byte scriptPubKey hex is enough to
 * answer "is this output ours", because the wallet only ever creates P2WPKH
 * scripts (see [Scripthash.p2wpkhScript]).
 *
 * **txid.** Computed from the transaction's *stripped* serialisation — the
 * version, inputs, outputs and locktime without the witness — and rendered in
 * the byte order a block explorer shows. That is the identifier `get_history`
 * and the outpoints of later transactions use, so the net calculation can match
 * an input to the output it spends.
 *
 * The parsing is deliberately strict: a truncated or mis-sized field throws
 * [TransactionParseException] rather than being guessed at. A transaction this
 * wallet half-understands is a row that shows the wrong amount.
 */
object TransactionParser {

    /**
     * Parses one raw transaction hex string.
     *
     * @throws TransactionParseException when the hex is malformed or truncated.
     */
    fun parse(hex: String): ParsedTransaction {
        val bytes = hexBytes(hex)
        val reader = ByteReader(bytes)

        // The version is kept for the detail view, which prints it as-is. It is
        // still not *interpreted* here — nothing in the wallet branches on it.
        val version = reader.readUInt32()
        // BIP-144: a zero byte where an input count would be is the marker, and
        // the flag is 1. A legacy transaction can never have zero inputs, so the
        // pattern is unambiguous.
        val segwit = reader.remaining >= 2 && reader.peek() == 0x00 && reader.peek(1) == 0x01
        if (segwit) reader.skip(2)

        val bodyStart = reader.position
        val inputs = ArrayList<TxInput>()
        val inputCount = reader.readCount("input")
        repeat(inputCount) {
            val previousHash = reader.readHash()
            val previousIndex = reader.readUInt32()
            val scriptLength = reader.readCount("scriptSig")
            reader.skip(scriptLength)
            reader.skip(4) // sequence
            inputs += TxInput(previousTxHash = previousHash, previousOutputIndex = previousIndex)
        }

        val outputs = ArrayList<TxOutput>()
        val outputCount = reader.readCount("output")
        repeat(outputCount) {
            val satoshis = reader.readUInt64()
            val scriptLength = reader.readCount("scriptPubKey")
            val script = reader.readBytes(scriptLength)
            outputs += TxOutput(satoshis = satoshis, scriptPubKeyHex = toHex(script))
        }
        val bodyEnd = reader.position

        if (segwit) {
            // One witness stack per input: a count, then items. Skipped rather
            // than kept — the witness is not part of the txid or the amounts.
            repeat(inputCount) {
                val items = reader.readCount("witness item")
                repeat(items) {
                    val itemLength = reader.readCount("witness element")
                    reader.skip(itemLength)
                }
            }
        }

        val lockTimeStart = reader.position
        val lockTime = reader.readUInt32().toLong() and 0xFFFFFFFFL
        if (!reader.atEnd()) {
            throw TransactionParseException(
                "transaction has ${reader.remaining} trailing byte(s) after the locktime",
            )
        }

        // The txid commits to the stripped form: version, body (inputs and
        // outputs), locktime — witness excluded.
        val stripped = bytes.copyOfRange(0, 4) +
            bytes.copyOfRange(bodyStart, bodyEnd) +
            bytes.copyOfRange(lockTimeStart, lockTimeStart + 4)
        val digest = Hashes.sha256d(stripped)
        // BIP-141 weight: the stripped serialisation counts four times, the
        // witness bytes once. The detail view reports both size measures; the
        // arithmetic belongs here, where the boundaries are already known.
        val strippedSize = stripped.size
        val weight = strippedSize * 4L + (bytes.size - strippedSize).toLong()
        return ParsedTransaction(
            txHash = toHex(digest.reversedArray()),
            inputs = inputs,
            outputs = outputs,
            version = version,
            lockTime = lockTime,
            weight = weight,
            vsize = (weight + 3) / 4,
        )
    }

    /** Lowercase hex to bytes; throws when the input is not an even-length hex string. */
    internal fun hexBytes(hex: String): ByteArray {
        val text = hex.trim()
        if (text.length % 2 != 0) {
            throw TransactionParseException("hex string has an odd length (${text.length})")
        }
        val out = ByteArray(text.length / 2)
        for (index in out.indices) {
            val high = hexDigit(text[2 * index])
            val low = hexDigit(text[2 * index + 1])
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }

    internal fun toHex(bytes: ByteArray): String {
        val alphabet = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(alphabet[value ushr 4]).append(alphabet[value and 0x0F])
        }
        return out.toString()
    }

    private fun hexDigit(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> throw TransactionParseException("'$char' is not a hex digit")
    }

    /** A bounds-checked cursor over the raw bytes. */
    private class ByteReader(private val bytes: ByteArray) {
        var position: Int = 0
            private set

        val remaining: Int get() = bytes.size - position

        fun atEnd(): Boolean = position >= bytes.size

        fun peek(offset: Int = 0): Int {
            val at = position + offset
            if (at >= bytes.size) throw TransactionParseException("truncated transaction at offset $at")
            return bytes[at].toInt() and 0xFF
        }

        fun skip(count: Int) {
            if (count < 0 || position + count > bytes.size) {
                throw TransactionParseException(
                    "truncated transaction: need $count more byte(s) at offset $position, have $remaining",
                )
            }
            position += count
        }

        fun readBytes(count: Int): ByteArray {
            skip(count)
            val value = bytes.copyOfRange(position - count, position)
            return value
        }

        /** One 32-byte txid in wire (little-endian) order, rendered big-endian. */
        fun readHash(): String = toHex(readBytes(32).reversedArray())

        fun readUInt32(): Int {
            skip(4)
            var value = 0
            for (index in 3 downTo 0) value = (value shl 8) or (bytes[position - 4 + index].toInt() and 0xFF)
            return value
        }

        fun readUInt64(): Long {
            skip(8)
            var value = 0L
            for (index in 7 downTo 0) value = (value shl 8) or (bytes[position - 8 + index].toLong() and 0xFF)
            if (value < 0) {
                throw TransactionParseException("output value does not fit in a signed 64-bit amount")
            }
            return value
        }

        /**
         * A Bitcoin varint, used for counts and lengths. Anything above
         * [MAX_COUNT] is refused: a corrupt length must not become an
         * allocation, and no real transaction has billions of inputs.
         */
        fun readCount(label: String): Int {
            val first = peek()
            val value = when (first) {
                0xFF -> {
                    val long = readUInt64()
                    if (long > MAX_COUNT) throw TransactionParseException("$label count $long is implausible")
                    long
                }
                0xFE -> readUInt32().toLong() and 0xFFFFFFFFL
                0xFD -> {
                    skip(1)
                    skip(2)
                    var short = 0
                    for (index in 1 downTo 0) short = (short shl 8) or (bytes[position - 2 + index].toInt() and 0xFF)
                    short.toLong()
                }
                else -> {
                    skip(1)
                    first.toLong()
                }
            }
            if (value > MAX_COUNT) throw TransactionParseException("$label count $value is implausible")
            return value.toInt()
        }

        private companion object {
            const val MAX_COUNT = 1_000_000L
        }
    }
}

/** Raised when a raw transaction cannot be decoded exactly. */
class TransactionParseException(message: String) : Exception(message)

/**
 * One spent outpoint, in display order: the funding transaction's id and the
 * output index inside it. `txid:vout` is what identifies a coin on chain.
 */
data class TxInput(
    val previousTxHash: String,
    val previousOutputIndex: Int,
) {
    val outpoint: String get() = "$previousTxHash:$previousOutputIndex"
}

/** One output: its value in satoshis and the scriptPubKey it pays, as lowercase hex. */
data class TxOutput(
    val satoshis: Long,
    val scriptPubKeyHex: String,
)

/**
 * A decoded transaction: the txid, what it spends, what it creates, and the
 * serialisation facts the detail view reports.
 *
 * [version], [lockTime], [weight] and [vsize] default to zero so a
 * [ParsedTransaction] built by hand in a test or a fixture still reads as the
 * three-field value it always was; the parser fills all seven.
 */
data class ParsedTransaction(
    val txHash: String,
    val inputs: List<TxInput>,
    val outputs: List<TxOutput>,
    val version: Int = 0,
    val lockTime: Long = 0,
    val weight: Long = 0,
    val vsize: Long = 0,
) {
    /** `txid:vout` for one of this transaction's own outputs. */
    fun outpoint(index: Int): String = "$txHash:$index"
}
