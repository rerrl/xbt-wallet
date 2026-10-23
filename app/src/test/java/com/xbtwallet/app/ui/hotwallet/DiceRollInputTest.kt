package com.xbtwallet.app.ui.hotwallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rules of the dice-roll input: what a legal roll is, when the input is
 * full, and how many rolls each word count needs.
 *
 * These are the only parts of the dice screen that can be checked without a
 * device, which is exactly why they live in [DiceRollInput] rather than inside
 * the composable.
 */
class DiceRollInputTest {

    @Test
    fun `a fresh input has no rolls`() {
        val input = DiceRollInput(targetRolls = 50)

        assertEquals("", input.rolls)
        assertEquals(0, input.count)
        assertFalse(input.isFull)
    }

    @Test
    fun `appended rolls accumulate in order`() {
        val input = DiceRollInput(targetRolls = 50)

        input.append(3)
        input.append(1)
        input.append(6)

        assertEquals("316", input.rolls)
        assertEquals(3, input.count)
    }

    /**
     * A die has six faces. Zero and seven are not rolls, and a wrong digit must
     * never be coerced into the entropy — it is ignored, so `rolls` stays exactly
     * as long as the real rolls were.
     */
    @Test
    fun `a digit outside one to six is not a roll`() {
        val input = DiceRollInput(targetRolls = 50)

        listOf(0, 7, -1, 9, 100).forEach { input.append(it) }

        assertEquals("", input.rolls)
        assertEquals(0, input.count)
    }

    @Test
    fun `the input accepts rolls up to the target and no more`() {
        val target = 5
        val input = DiceRollInput(targetRolls = target)

        repeat(target) { input.append(1 + it % 6) }

        assertEquals(target, input.count)
        assertTrue(input.isFull)
        assertEquals("12345", input.rolls)

        // Once full, another roll — even a legal one — is ignored: the input is
        // exactly as long as the word count was built for.
        input.append(6)
        assertEquals("12345", input.rolls)
        assertEquals(target, input.count)
        assertTrue(input.isFull)
    }

    @Test
    fun `backspace removes the last roll and reopens the input`() {
        val target = 3
        val input = DiceRollInput(targetRolls = target)

        input.append(4)
        input.append(5)
        input.append(6)
        assertTrue(input.isFull)

        input.backspace()

        assertEquals("45", input.rolls)
        assertEquals(2, input.count)
        assertFalse("no longer full, so it accepts rolls again", input.isFull)
        assertEquals("45", input.rolls)

        input.append(1)
        assertEquals("451", input.rolls)
        assertTrue(input.isFull)
    }

    @Test
    fun `backspace on an empty input does nothing`() {
        val input = DiceRollInput(targetRolls = 50)

        input.backspace()
        input.backspace()

        assertEquals("", input.rolls)
        assertEquals(0, input.count)
        assertFalse(input.isFull)
    }

    @Test
    fun `the roll count follows the word count`() {
        assertEquals(50, DiceRollInput.targetRollsFor(12))
        assertEquals(99, DiceRollInput.targetRollsFor(24))
    }

    @Test
    fun `a word count the app does not offer has no roll count`() {
        val thrown = runCatching { DiceRollInput.targetRollsFor(15) }.exceptionOrNull()

        assertTrue(
            "an unsupported word count is a programming error, not a user input",
            thrown is IllegalArgumentException,
        )
    }

    @Test
    fun `fifty rolls fill a twelve-word input and not a twenty-four-word one`() {
        val twelve = DiceRollInput(DiceRollInput.targetRollsFor(12))
        val twentyFour = DiceRollInput(DiceRollInput.targetRollsFor(24))

        repeat(DiceRollInput.targetRollsFor(12)) { twelve.append(1 + it % 6) }
        repeat(DiceRollInput.targetRollsFor(12)) { twentyFour.append(1 + it % 6) }

        assertTrue("fifty rolls are enough for twelve words", twelve.isFull)
        assertFalse("but not for twenty-four", twentyFour.isFull)
        assertEquals(50, twentyFour.count)
    }
}
