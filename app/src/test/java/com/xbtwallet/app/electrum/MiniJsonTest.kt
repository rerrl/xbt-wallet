package com.xbtwallet.app.electrum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun `parses a JSON-RPC result object`() {
        val parsed = MiniJson.parse("""{"id":2,"result":{"genesis_hash":"abc","protocol_min":"1.4","hosts":{}}}""")

        val map = parsed as Map<*, *>
        assertEquals(2, map["id"])
        assertNull(map["error"])
        val result = map["result"] as Map<*, *>
        assertEquals("abc", result["genesis_hash"])
        assertEquals("1.4", result["protocol_min"])
        assertTrue(result["hosts"] is Map<*, *>)
    }

    @Test
    fun `parses arrays numbers booleans and null`() {
        val parsed = MiniJson.parse("""[1,2.5,-3,true,false,null,"x"]""") as List<*>

        assertEquals(1, parsed[0])
        assertEquals(2.5, parsed[1])
        assertEquals(-3, parsed[2])
        assertEquals(true, parsed[3])
        assertEquals(false, parsed[4])
        assertNull(parsed[5])
        assertEquals("x", parsed[6])
    }

    @Test
    fun `decodes escapes including unicode`() {
        val parsed = MiniJson.parse(""""a\"b\\c\nd\u00e9"""") as String

        assertEquals("a\"b\\c\nd\u00e9", parsed)
    }

    @Test
    fun `parses an error response`() {
        val parsed = MiniJson.parse("""{"id":1,"error":{"code":-32601,"message":"unknown method"}}""") as Map<*, *>

        val error = parsed["error"] as Map<*, *>
        assertEquals(-32601, error["code"])
        assertEquals("unknown method", error["message"])
    }

    @Test(expected = JsonParseException::class)
    fun `rejects trailing garbage`() {
        MiniJson.parse("""{"a":1} trailing""")
    }

    @Test(expected = JsonParseException::class)
    fun `rejects malformed input`() {
        MiniJson.parse("""{"a":}""")
    }

    @Test
    fun `encodes a request with escaped strings`() {
        val encoded = MiniJson.encode(
            linkedMapOf<String, Any?>(
                "id" to 1,
                "method" to "server.version",
                "params" to listOf("xbt-wallet 0.0.1", "1.4"),
            ),
        )

        assertEquals("""{"id":1,"method":"server.version","params":["xbt-wallet 0.0.1","1.4"]}""", encoded)
    }

    @Test
    fun `round-trips through encode and parse`() {
        val value = linkedMapOf<String, Any?>("banner" to "line1\nline2 \"quoted\"", "count" to 3, "null" to null)

        assertEquals(value, MiniJson.parse(MiniJson.encode(value)))
    }
}
