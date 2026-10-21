package com.xbtwallet.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The `crypto-psbt` round trip, frame by frame.
 *
 * One dependency, both directions: the same hummingbird that reads a signer's
 * animated `crypto-account` export writes the unsigned PSBT animation and reads
 * the signed one back. These tests drive the whole thing on the JVM — encode a
 * PSBT, hand its frames to the assembler in order and out of order, and get the
 * exact bytes back.
 */
class UrPsbtRoundTripTest {

    /** A payload of [size] bytes; the UR layer treats a PSBT as opaque bytes. */
    private fun payload(size: Int): ByteArray = ByteArray(size) { index -> ((index * 7) + 1).toByte() }

    @Test
    fun `a payload that fits one frame is a single static part`() {
        val encoder = UrPsbtEncoder(payload(64))
        assertTrue(encoder.isSingleFrame)
        assertEquals(1, encoder.parts.size)
    }

    @Test
    fun `a single-frame payload decodes back to the same bytes`() {
        val original = payload(64)
        val encoder = UrPsbtEncoder(original)
        val outcome = UrPsbtAssembler().receive(encoder.parts.single())
        assertTrue("expected Complete, got $outcome", outcome is UrPsbtOutcome.Complete)
        assertArrayEquals(original, (outcome as UrPsbtOutcome.Complete).psbtBytes)
    }

    @Test
    fun `a larger payload is split into several frames`() {
        val encoder = UrPsbtEncoder(payload(500))
        assertFalse(encoder.isSingleFrame)
        assertTrue("expected an animation, got ${encoder.parts.size} frame(s)", encoder.parts.size >= 3)
    }

    @Test
    fun `a multi-frame payload reassembles from its parts in order`() {
        val original = payload(500)
        val encoder = UrPsbtEncoder(original)
        val assembler = UrPsbtAssembler()

        var complete: ByteArray? = null
        encoder.parts.forEach { part ->
            when (val outcome = assembler.receive(part)) {
                is UrPsbtOutcome.Progress -> Unit
                is UrPsbtOutcome.Complete -> complete = outcome.psbtBytes
                is UrPsbtOutcome.Rejected -> fail("frame rejected: ${outcome.message}")
            }
        }
        assertArrayEquals(original, complete)
    }

    @Test
    fun `a fountain code reassembles from its parts in any order`() {
        val original = payload(500)
        val encoder = UrPsbtEncoder(original)
        val assembler = UrPsbtAssembler()

        var complete: ByteArray? = null
        encoder.parts.reversed().forEach { part ->
            when (val outcome = assembler.receive(part)) {
                is UrPsbtOutcome.Progress -> Unit
                is UrPsbtOutcome.Complete -> complete = outcome.psbtBytes
                is UrPsbtOutcome.Rejected -> fail("frame rejected: ${outcome.message}")
            }
        }
        assertArrayEquals(original, complete)
    }

    @Test
    fun `an incomplete animation reports progress between 0 and 100`() {
        val encoder = UrPsbtEncoder(payload(500))
        val assembler = UrPsbtAssembler()
        val outcome = assembler.receive(encoder.parts.first())
        assertTrue("expected Progress, got $outcome", outcome is UrPsbtOutcome.Progress)
        assertTrue(assembler.percent in 0..100)
    }

    @Test
    fun `an xpub export scanned here is rejected with its own sentence`() {
        val outcome = UrPsbtAssembler().receive("ur:crypto-account/1-2/abcd")
        assertTrue(outcome is UrPsbtOutcome.Rejected)
        assertEquals(
            "That's an xpub export, not a signed transaction.",
            (outcome as UrPsbtOutcome.Rejected).message,
        )
    }

    @Test
    fun `a UR of another type names the type it actually saw`() {
        val outcome = UrPsbtAssembler().receive("ur:crypto-seed/1-1/abcd")
        assertTrue(outcome is UrPsbtOutcome.Rejected)
        assertEquals(
            "That is a crypto-seed QR, not a signed transaction.",
            (outcome as UrPsbtOutcome.Rejected).message,
        )
    }

    @Test
    fun `a payload that is not a UR at all is rejected as not a transaction`() {
        val outcome = UrPsbtAssembler().receive("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
        assertTrue(outcome is UrPsbtOutcome.Rejected)
        assertEquals(
            UrPsbtAssembler.NOT_A_TRANSACTION_QR,
            (outcome as UrPsbtOutcome.Rejected).message,
        )
    }

    @Test
    fun `reset clears a half-assembled scan so the next one starts clean`() {
        val encoder = UrPsbtEncoder(payload(500))
        val assembler = UrPsbtAssembler()
        assembler.receive(encoder.parts.first())
        assembler.reset()
        assertEquals(0, assembler.percent)

        var complete: ByteArray? = null
        encoder.parts.forEach { part ->
            (assembler.receive(part) as? UrPsbtOutcome.Complete)?.let { complete = it.psbtBytes }
        }
        assertArrayEquals(payload(500), complete)
    }
}
