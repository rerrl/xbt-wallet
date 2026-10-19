package com.xbtwallet.app.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Reading a block's time off its own header.
 *
 * The anchor is the **mainnet genesis header**, whose timestamp (1231006505,
 * 2009-01-03 18:15:05 UTC) is a published constant — so the little-endian read
 * at byte offset 68 is pinned against a fact, not against this implementation.
 * The adversarial cases (wrong length, non-hex) are the ones a caller could
 * actually hit by handing over a transaction or a truncated line.
 */
class BlockTimestampTest {

    @Test
    fun `the genesis header yields the genesis timestamp`() {
        assertEquals(1231006505L, blockTimestampFromHeaderHex(GENESIS_HEADER))
    }

    @Test
    fun `the timestamp field is read little-endian at the documented offset`() {
        // Bytes 68..72 are 78 56 34 12 → 0x12345678 as a little-endian uint32.
        val header = "00".repeat(BLOCK_HEADER_TIMESTAMP_OFFSET) + "78563412" + "00".repeat(8)
        assertEquals(160, header.length)

        assertEquals(0x12345678L, blockTimestampFromHeaderHex(header))
    }

    @Test
    fun `an upper-case header is accepted as the same bytes`() {
        assertEquals(
            blockTimestampFromHeaderHex(GENESIS_HEADER),
            blockTimestampFromHeaderHex(GENESIS_HEADER.uppercase()),
        )
    }

    @Test
    fun `a header that is not 160 characters is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            blockTimestampFromHeaderHex("01000000")
        }
    }

    @Test
    fun `a header with a non-hex character is rejected, not silently zeroed`() {
        // Right length, one bad character in the (untouched) version field — the
        // whole string is validated, so it cannot slip through.
        val corrupt = "z" + GENESIS_HEADER.substring(1)
        assertThrows(IllegalArgumentException::class.java) {
            blockTimestampFromHeaderHex(corrupt)
        }
    }

    private companion object {
        /** The mainnet genesis block header (80 bytes, 160 hex characters). */
        const val GENESIS_HEADER =
            "0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a29ab5f49ffff001d1dac2b7c"
    }
}
