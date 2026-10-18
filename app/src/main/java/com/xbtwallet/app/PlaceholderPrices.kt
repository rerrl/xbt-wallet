package com.xbtwallet.app

/**
 * The fiat rates the balance display uses, and the fact that they are fake.
 *
 * **These are placeholders, not a price feed.** This build makes no market-data
 * request of any kind: the only network traffic the wallet emits is Electrum
 * RPCs to the user's own server (a hard rule — see PLAN.md §2.3 and §2.4, and
 * the "no new permissions" constraint on the signer-screen card). So the
 * signer screen shows the balance in sats and XBT, and dresses it in a fiat
 * figure using the constants below, *clearly marked as a placeholder* wherever
 * they appear on screen.
 *
 * [USD_PER_XBT] is a joke rate on purpose: it is recognisable at a glance, so a
 * screenshot of the screen can never be mistaken for a real quote. [USD_PER_BTC]
 * is a round, plausible-looking stand-in used for the secondary "what this sat
 * balance is worth in BTC" line — BTC and XBT share the satoshi unit, so the
 * same balance priced at the BTC rate is a one-line conversion, not a second
 * data source.
 *
 * When a real feed is wired up, it replaces exactly this file: nothing else in
 * the app knows a rate, they all read it from here.
 */
object PlaceholderPrices {

    /** Placeholder XBT price, USD per 1 XBT. Not a real quote. */
    const val USD_PER_XBT: Double = 420.69

    /** Placeholder BTC price, USD per 1 BTC. Not a real quote. */
    const val USD_PER_BTC: Double = 81_000.0

    /** Satoshis in one coin — BTC and XBT share the unit. */
    const val SATOSHIS_PER_COIN: Long = 100_000_000L
}
