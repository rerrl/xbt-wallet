package com.xbtwallet.app.ui.receive

import com.xbtwallet.app.chain.AccountSnapshot
import com.xbtwallet.app.chain.AddressChain
import com.xbtwallet.app.chain.ScannedAddress
import com.xbtwallet.app.chain.ScannedUtxo

/**
 * The text a receive QR carries.
 *
 * BIP-21 wants the scheme in lower case (`bitcoin:`), but a QR *encoder* packs
 * upper-case alphanumerics far more densely than lower-case ones — the ZXing
 * alphanumeric mode covers `0-9 A-Z $%*+-./:` and nothing else, so
 * `BITCOIN:BC1Q…` fits in a much smaller symbol than `bitcoin:bc1q…`. The scheme
 * is case-insensitive (BIP-21 §"URI scheme"), so an upper-case payload is the
 * same URI, in fewer QR modules. That is the trade this wallet makes, and the
 * decision is recorded here rather than at the call site.
 *
 * The uppercase() is applied to the whole payload, not just the scheme: a bech32
 * address uppercased is still that address (bech32 is case-insensitive as a
 * whole, and mixed case is what is invalid), so uppercasing both halves keeps the
 * string uniformly alphanumeric.
 */
internal fun qrPayload(address: String): String = "BITCOIN:" + address.uppercase()

/**
 * Which receive address the screen hands out when the user has not tapped a
 * specific one, and whether that choice is backed by the scan.
 *
 * [scanned] is the receive chain of the signer's snapshot — the addresses the
 * gap-limit walk actually asked the server about, contiguous from index 0 (the
 * same precondition [com.xbtwallet.app.ui.addresses.buildAddressRows] relies on).
 * The walk visits a run of addresses and stops past the gap limit, so it always
 * ends on an unused address: **the first entry the server said is unused is the
 * address the next receive hands out**, which is what "first unused" means.
 *
 * Three cases, and the second value of the pair says which:
 *
 * - **No snapshot at all** (the signer has never been scanned): nothing is
 *   known, so index 0 is offered — the address a fresh wallet hands out — and
 *   the second value is `false`: *not verified*. The screen says so rather than
 *   pretending the scan cleared it.
 * - **A scanned prefix with an unused entry**: that index, `true`. The server
 *   answered about this address and it has no history.
 * - **Every scanned address used** (a rescan that only saw used addresses): the
 *   address past the scanned range, index `scanned.size`, `true`. There is no
 *   history to show for it because nothing has been asked — but the wallet knows
 *   it has run past every address it *has* asked about, so it hands out the next
 *   one rather than re-offering a used address.
 *
 * Deliberately a pure function of the scanned list: no snapshot, no scanner, no
 * Android. What the caller does with the index — derive it — is the caller's
 * business, and is what makes this JVM-testable on its own.
 */
internal fun resolveReceiveIndex(scanned: List<ScannedAddress>): Pair<Int, Boolean> {
    if (scanned.isEmpty()) return 0 to false
    val firstUnused = scanned.indexOfFirst { !it.used }
    return if (firstUnused >= 0) firstUnused to true else scanned.size to true
}

/**
 * What the scan knows about one address, for the receive screen to warn about.
 *
 * [used] is the address-reuse signal: paying into an address that already has
 * history links the two payments on chain, which is exactly the privacy leak a
 * fresh address avoids. [utxos] is that address's spendable outputs, so a user
 * about to hand it out can see it is not empty.
 */
data class ReceiveAddressInfo(
    val chain: AddressChain,
    val index: Int,
    val used: Boolean,
    val utxos: List<ScannedUtxo>,
)

/**
 * The scan's record of [address], or null when the wallet never asked about it.
 *
 * The lookup is over both chains — a `change` address is still a real address,
 * and the receive screen shows whatever the caller handed it — matched on the
 * address string, because that is all the navigation carries. The UTXOs are
 * narrowed to the ones found at exactly this chain *and* index: `listunspent`
 * is per scripthash, so an output belongs to one address and one index, never
 * to the chain as a whole.
 *
 * **Null is honest.** An address outside the scanned range was never queried, so
 * there is nothing to warn about and nothing to list — a screen that guessed
 * "unused" for it is how a used address gets handed out again. A null snapshot
 * (nothing scanned yet) is the same case: unknown.
 */
fun receiveAddressInfo(snapshot: AccountSnapshot?, address: String): ReceiveAddressInfo? {
    if (snapshot == null) return null
    val match = (snapshot.receive + snapshot.change).firstOrNull { it.address == address } ?: return null
    return ReceiveAddressInfo(
        chain = match.chain,
        index = match.index,
        used = match.used,
        utxos = snapshot.utxos.filter { it.chain == match.chain && it.addressIndex == match.index },
    )
}