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
 * The adversarial cases (too short, an odd number of characters, non-hex) are
 * the ones a caller could actually hit by handing over a transaction or a
 * truncated line; the longer-than-80-bytes case is the one the real test server
 * produced, from the BLAKE2b hardfork's activation height on.
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
    fun `a header shorter than eighty bytes is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            blockTimestampFromHeaderHex("01000000")
        }
        // 79 bytes: one byte short of a header, which is what a truncated reply
        // or a wrong field would look like.
        assertThrows(IllegalArgumentException::class.java) {
            blockTimestampFromHeaderHex(GENESIS_HEADER.dropLast(2))
        }
    }

    @Test
    fun `an odd number of characters is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            blockTimestampFromHeaderHex(GENESIS_HEADER + "0")
        }
    }

    @Test
    fun `a header longer than eighty bytes is read off its first eighty bytes`() {
        // The shape the test server really answers with from the BLAKE2b
        // hardfork's activation height on: the 80-byte header with an 84-byte
        // proof-of-work extension appended — 164 bytes, 328 hex characters. The
        // extension below is real reply data (public chain data); the prefix is
        // the mainnet block-100000 header, whose timestamp is 1293623863.
        val postFork = BLOCK_100000_HEADER + POST_FORK_EXTENSION
        assertEquals(328, postFork.length)

        assertEquals(1293623863L, blockTimestampFromHeaderHex(postFork))
        // Reading the prefix and reading the whole thing are the same number:
        // the extension is after the fields, never inside them.
        assertEquals(
            blockTimestampFromHeaderHex(BLOCK_100000_HEADER),
            blockTimestampFromHeaderHex(postFork),
        )
    }

    @Test
    fun `a header with a non-hex character is rejected, not silently zeroed`() {
        // Right length, one bad character in the (untouched) version field — the
        // whole string is validated, so it cannot slip through.
        val corrupt = "z" + GENESIS_HEADER.substring(1)
        assertThrows(IllegalArgumentException::class.java) {
            blockTimestampFromHeaderHex(corrupt)
        }
        // ... and the same holds for an extended header's trailing bytes.
        assertThrows(IllegalArgumentException::class.java) {
            blockTimestampFromHeaderHex(BLOCK_100000_HEADER + "z".repeat(168))
        }
    }

    private companion object {
        /** The mainnet genesis block header (80 bytes, 160 hex characters). */
        const val GENESIS_HEADER =
            "0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a29ab5f49ffff001d1dac2b7c"

        /** The mainnet block-100000 header, whose timestamp is 1293623863. */
        const val BLOCK_100000_HEADER =
            "0100000050120119172a610421a6c3011dd330d9df07b63616c2cc1f1cd00200000000006657a9252aacd5c0b2940996ecff952228c3067cc38d4885efb5a4ac4247e9f337221b4d4c86041b0f2b5710"

        /**
         * The 84-byte proof-of-work extension a post-hardfork header carries
         * after its 80-byte prefix, copied from a real `blockchain.block.header`
         * reply (public chain data). Its contents are irrelevant here; its
         * length is what used to make the parse throw.
         */
        const val POST_FORK_EXTENSION =
            "e361d50be798a06a00000000b1ccf00f010000000000000000000000" +
                "61000033024b85992956d664c320c43104b60c7510cd0e0000000000" +
                "00000000000000000000000000000000000000000000000000000000"
    }
}
