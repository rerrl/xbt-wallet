package com.xbtwallet.app.ui.signers

import com.xbtwallet.app.chain.TransactionDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact strings a transaction row shows, pinned as logic.
 *
 * Same reasoning as [BalancePresentationTest]: an amount a user reads is not a
 * rendering detail. Eight decimals, a sign that matches the wallet's net effect,
 * and the words "in"/"out" — no locale, no rounding, no ambiguity about whether
 * the fee counts (it does: the fee left the wallet).
 */
class TransactionPresentationTest {

    @Test
    fun `incoming amounts are signed plus and say in`() {
        assertEquals("+0.12345678 XBT in", transactionAmountLabel(12_345_678))
        assertEquals("+0.00000001 XBT in", transactionAmountLabel(1))
    }

    @Test
    fun `outgoing amounts are signed minus and say out`() {
        assertEquals("-0.00312457 XBT out", transactionAmountLabel(-312_457))
        assertEquals("-0.00000001 XBT out", transactionAmountLabel(-1))
    }

    @Test
    fun `a net-zero transaction has no sign and no direction word`() {
        assertEquals("0.00000000 XBT", transactionAmountLabel(0))
    }

    @Test
    fun `direction follows the sign of the net amount`() {
        assertEquals(TransactionDirection.IN, transactionDirection(1))
        assertEquals(TransactionDirection.OUT, transactionDirection(-1))
        assertEquals(TransactionDirection.NEUTRAL, transactionDirection(0))
    }
}
