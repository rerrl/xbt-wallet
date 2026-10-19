package com.xbtwallet.app.ui.addresses

import com.xbtwallet.app.chain.DerivedAddress
import com.xbtwallet.app.chain.ScannedAddress

/** One rendered row: the address, and what the scan knows about it. */
internal data class AddressRowModel(
    val index: Int,
    val address: String,
    /** `true` used, `false` unused, `null` beyond the range the scan visited. */
    val used: Boolean?,
)

/**
 * Merges what the scan knows with what has been derived locally, positionally.
 *
 * The scan and the derivation answer two different questions about the same
 * index. A [ScannedAddress] is a fact the server gave us — the address and
 * whether it has history; a [DerivedAddress] is arithmetic we can always do,
 * delivered instantly whether or not any server is reachable. Rendering one or
 * the other, but never both, is what lets the screen show cached scan knowledge
 * (used / unused / first unused) *immediately* on open while local derivation
 * fills the tail behind it: a scan only annotates rows, it never gates them.
 *
 * Rows are emitted by index, contiguously from 0. Indices below `scanned.size`
 * take the scanned address and its `used` flag; indices from `scanned.size` up
 * to `derived.size` take the derived address with `used = null` ("not
 * scanned") — a receive screen that guessed "unused" for an address nobody
 * asked the server about is how an address with history gets handed out twice.
 *
 * Precondition (relied on, not checked): both lists are contiguous from index 0
 * and ordered by index, so list position equals address index.
 */
internal fun buildAddressRows(
    scanned: List<ScannedAddress>,
    derived: List<DerivedAddress>,
): List<AddressRowModel> {
    val rows = ArrayList<AddressRowModel>(maxOf(scanned.size, derived.size))
    for (index in scanned.indices) {
        val visited = scanned[index]
        rows += AddressRowModel(index = index, address = visited.address, used = visited.used)
    }
    for (index in scanned.size until derived.size) {
        rows += AddressRowModel(index = index, address = derived[index].address, used = null)
    }
    return rows
}

/**
 * The index of the first row the scan saw as *unused* — the address the next
 * receive would hand out, and the one the screen highlights.
 *
 * Null when no row is known-unused, which is exactly the case before any scan
 * has answered: everything on screen is derived, so nothing is highlighted
 * rather than something being guessed at.
 */
internal fun firstUnusedIndex(rows: List<AddressRowModel>): Int? =
    rows.indexOfFirst { it.used == false }.takeIf { it >= 0 }
