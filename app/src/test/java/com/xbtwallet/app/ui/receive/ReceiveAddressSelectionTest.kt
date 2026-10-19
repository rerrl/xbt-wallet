package com.xbtwallet.app.ui.receive

import com.xbtwallet.app.chain.AddressChain
import com.xbtwallet.app.chain.ScannedAddress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that picks the address a receive hands out when the user has not
 * tapped one, pinned on the JVM: it is a pure function of the scan's receive
 * chain, so it needs no snapshot, no scanner and no Android.
 *
 * The three cases are the whole contract — nothing scanned (offer index 0, and
 * say it is unverified), a scanned prefix with a known-unused entry (offer it),
 * and a scan whose every address is used (offer the one past the range).
 */
class ReceiveAddressSelectionTest {

    private fun scanned(vararg used: Boolean): List<ScannedAddress> =
        used.mapIndexed { index, isUsed ->
            ScannedAddress(
                chain = AddressChain.RECEIVE,
                index = index,
                address = "addr-$index",
                scripthash = "sh-$index",
                used = isUsed,
            )
        }

    @Test
    fun `nothing scanned offers index zero, unverified`() {
        assertEquals(0 to false, resolveReceiveIndex(emptyList()))
    }

    @Test
    fun `first unused mid-list is the address handed out`() {
        val scan = scanned(true, true, false, false)

        assertEquals(2 to true, resolveReceiveIndex(scan))
    }

    @Test
    fun `an unused index zero is still the first unused`() {
        val scan = scanned(false, false)

        assertEquals(0 to true, resolveReceiveIndex(scan))
    }

    @Test
    fun `an all-used scan hands out the address past the scanned range`() {
        val scan = scanned(true, true, true)

        assertEquals(3 to true, resolveReceiveIndex(scan))
    }

    @Test
    fun `a later unused entry wins over an earlier one further down`() {
        // The gap-limit walk ends on an unused address; if a run in the middle is
        // used and the tail is not, the *first* unused is the honest answer, not
        // the last — handing out a later address would skip a fresh one.
        val scan = scanned(true, false, false, true)

        assertEquals(1 to true, resolveReceiveIndex(scan))
    }
}