package com.xbtwallet.app.ui.addresses

import com.xbtwallet.app.chain.AddressChain
import com.xbtwallet.app.chain.DerivedAddress
import com.xbtwallet.app.chain.ScannedAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge that lets the screen render instantly: locally derived rows appear
 * whether or not a scan has answered, and whatever the scan *does* know annotates
 * the head of the list exactly as reported.
 *
 * The lists here are contiguous from index 0 and ordered by index — the
 * precondition `buildAddressRows` documents — asserted below rather than assumed,
 * so a fixture that stops describing reality fails loudly.
 */
class AddressRowsTest {

    private fun scanned(vararg used: Boolean): List<ScannedAddress> =
        used.mapIndexed { index, isUsed ->
            ScannedAddress(
                chain = AddressChain.RECEIVE,
                index = index,
                address = "scan-$index",
                scripthash = "sh-$index",
                used = isUsed,
            )
        }

    private fun derived(count: Int, from: Int = 0): List<DerivedAddress> =
        (from until from + count).map { index ->
            DerivedAddress(
                chain = AddressChain.RECEIVE,
                index = index,
                address = "derived-$index",
                scripthash = "dsh-$index",
            )
        }

    private fun assertContiguousFromZero(rows: List<AddressRowModel>) {
        assertEquals(
            "row indices must be contiguous from 0 in order",
            rows.indices.toList(),
            rows.map { it.index },
        )
    }

    @Test
    fun `empty scan with one derived page yields all not-scanned rows`() {
        val rows = buildAddressRows(scanned = emptyList(), derived = derived(count = 20))

        assertEquals(20, rows.size)
        assertTrue("every row is beyond the scanned range", rows.all { it.used == null })
        assertContiguousFromZero(rows)
        assertNull("nothing is known-unused, so nothing is highlighted", firstUnusedIndex(rows))
    }

    @Test
    fun `scanned prefix keeps its flags and the tail is not scanned`() {
        val rows = buildAddressRows(
            scanned = scanned(true, false, true),
            derived = derived(count = 20),
        )

        assertEquals(20, rows.size)
        assertContiguousFromZero(rows)
        assertEquals(true, rows[0].used)
        assertEquals(false, rows[1].used)
        assertEquals(true, rows[2].used)
        assertTrue(
            "indices past the scan are not scanned",
            (3 until 20).all { rows[it].used == null },
        )
        assertEquals("scan-0", rows[0].address)
        assertEquals("derived-19", rows[19].address)
    }

    @Test
    fun `scan that visited further than the current page is not truncated`() {
        val rows = buildAddressRows(
            scanned = scanned(true, false, false, false, false, false, true),
            derived = derived(count = 3),
        )

        assertEquals("every scanned row survives", 7, rows.size)
        assertContiguousFromZero(rows)
        assertEquals(true, rows[6].used)
        assertEquals("scan-6", rows[6].address)
    }

    @Test
    fun `firstUnusedIndex returns the first known-unused row`() {
        val rows = buildAddressRows(
            scanned = scanned(true, true, false, false),
            derived = derived(count = 20),
        )

        assertEquals(2, firstUnusedIndex(rows))
    }

    @Test
    fun `firstUnusedIndex is null when every scanned row is used`() {
        val rows = buildAddressRows(
            scanned = scanned(true, true, true),
            derived = derived(count = 20),
        )

        assertNull(firstUnusedIndex(rows))
    }

    @Test
    fun `firstUnusedIndex can point at index zero`() {
        val rows = buildAddressRows(
            scanned = scanned(false, false),
            derived = derived(count = 20),
        )

        assertEquals(0, firstUnusedIndex(rows))
    }

    @Test
    fun `used rows carry the scanned address text, not a freshly derived one`() {
        // Defense against derive/render drift: the address the server was asked
        // about must be the address the row shows, even when an equivalent local
        // derivation exists.
        val rows = buildAddressRows(
            scanned = scanned(true, false),
            derived = derived(count = 20),
        )

        assertEquals("scan-0", rows[0].address)
        assertEquals("scan-1", rows[1].address)
    }
}
