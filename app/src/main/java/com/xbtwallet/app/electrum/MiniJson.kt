package com.xbtwallet.app.electrum

/** Raised when a peer sends text that is not valid JSON. */
class JsonParseException(message: String) : Exception(message)

/**
 * Tiny, dependency-free JSON reader/writer.
 *
 * The Electrum client speaks newline-delimited JSON-RPC over a socket, so it
 * needs to encode a request and read a reply — nothing more. Pulling in a JSON
 * library for that would grow the wallet's dependency tree for no benefit
 * (see PLAN.md §2.4 "lean and auditable"), so this is a deliberately small
 * implementation of the subset required: objects, arrays, strings, numbers,
 * booleans and null, decoded into [LinkedHashMap] / [ArrayList] / [String] /
 * [Int] / [Long] / [Double] / [Boolean] / null.
 *
 * It is not a general-purpose library: no streaming, no pretty-printing, and it
 * rejects anything malformed rather than trying to recover.
 */
internal object MiniJson {

    /** Parses a complete JSON document. Throws [JsonParseException] when invalid. */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) {
            throw JsonParseException("trailing characters at offset ${parser.offset}")
        }
        return value
    }

    /** Encodes a value ([Map], [Iterable], [String], numbers, booleans, null). */
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(value.toString())
            is Double ->
                if (value.isFinite() && value == Math.floor(value)) {
                    out.append(value.toLong().toString())
                } else {
                    out.append(value.toString())
                }
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, entry) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key.toString())
                    out.append(':')
                    write(out, entry)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (entry in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, entry)
                }
                out.append(']')
            }
            is Array<*> -> write(out, value.toList())
            else -> writeString(out, value.toString())
        }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else ->
                    if (char < ' ') {
                        out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(char)
                    }
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        var offset: Int = 0
            private set

        fun atEnd(): Boolean = offset >= text.length

        fun skipWhitespace() {
            while (offset < text.length && text[offset].isWhitespace()) offset++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw JsonParseException("unexpected end of input")
            return when (val char = text[offset]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else ->
                    if (char == '-' || char.isDigit()) {
                        parseNumber()
                    } else {
                        throw JsonParseException("unexpected character '$char' at offset $offset")
                    }
            }
        }

        private fun expect(literal: String) {
            if (!text.startsWith(literal, offset)) {
                throw JsonParseException("expected '$literal' at offset $offset")
            }
            offset += literal.length
        }

        private fun parseObject(): Map<String, Any?> {
            offset++ // consume '{'
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && text[offset] == '}') {
                offset++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (atEnd() || text[offset] != ':') {
                    throw JsonParseException("expected ':' at offset $offset")
                }
                offset++
                result[key] = parseValue()
                skipWhitespace()
                if (atEnd()) throw JsonParseException("unterminated object")
                when (text[offset]) {
                    ',' -> offset++
                    '}' -> { offset++; return result }
                    else -> throw JsonParseException("expected ',' or '}' at offset $offset")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            offset++ // consume '['
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && text[offset] == ']') {
                offset++
                return result
            }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                if (atEnd()) throw JsonParseException("unterminated array")
                when (text[offset]) {
                    ',' -> offset++
                    ']' -> { offset++; return result }
                    else -> throw JsonParseException("expected ',' or ']' at offset $offset")
                }
            }
        }

        private fun parseString(): String {
            if (atEnd() || text[offset] != '"') {
                throw JsonParseException("expected string at offset $offset")
            }
            offset++
            val result = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonParseException("unterminated string")
                when (val char = text[offset++]) {
                    '"' -> return result.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonParseException("unterminated escape")
                        when (val escaped = text[offset++]) {
                            '"' -> result.append('"')
                            '\\' -> result.append('\\')
                            '/' -> result.append('/')
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                if (offset + 4 > text.length) {
                                    throw JsonParseException("truncated unicode escape at offset $offset")
                                }
                                val hex = text.substring(offset, offset + 4)
                                offset += 4
                                result.append(
                                    hex.toIntOrNull(16)?.toChar()
                                        ?: throw JsonParseException("invalid unicode escape '\\u$hex'"),
                                )
                            }
                            else -> throw JsonParseException("invalid escape '\\$escaped'")
                        }
                    }
                    else -> result.append(char)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = offset
            if (text[offset] == '-') offset++
            while (!atEnd() && (text[offset].isDigit() || text[offset] in ".eE+-")) offset++
            val literal = text.substring(start, offset)
            return literal.toIntOrNull()
                ?: literal.toLongOrNull()
                ?: literal.toDoubleOrNull()
                ?: throw JsonParseException("invalid number '$literal'")
        }
    }
}
