package com.xbtwallet.app.ui.hotwallet

/**
 * The create-flow's intermediate secrets, held in memory and nowhere else.
 *
 * The walk that creates a hot wallet produces two things that must never be
 * written down: the recovery words (the seed, in [setMnemonic]) and the
 * passphrase (the optional 25th word, in [setPassphrase]). Neither can ride a
 * [com.xbtwallet.app.ui.Route]: the navigation back stack is a
 * `rememberSaveable`, so its entries are encoded to strings and written into the
 * saved instance state — which the system keeps across a process restart, and
 * which is exactly where a seed must not be. They cannot ride
 * `rememberSaveable` for the same reason, and they are never logged.
 *
 * So they live in this one object instead. What makes it safe is not a method
 * on it but its shape: plain Kotlin, no Android import, no `Parcelable`, no
 * `Serializable`, no `Saver`, no field that outlives the process, and no method
 * that writes anything anywhere. A single instance is held by `remember {}` in
 * [com.xbtwallet.app.ui.XbtWalletApp] — a plain remember, so it survives
 * recomposition and rotation but dies with the process. **Process death in the
 * middle of the walk is therefore a restart of the walk, with nothing stored.**
 *
 * [clear] is the other half of the contract, and it is called in two places:
 * after a successful provision (the seed is in the keystore by then, and the
 * words have no reason to stay in memory) and when the last create-flow screen
 * leaves composition without completing — a back-out mid-flow must not leave the
 * words sitting in memory for the rest of the session.
 *
 * Being free of Android and Compose is deliberate: the whole contract is
 * JVM-testable, and it is pinned by
 * [com.xbtwallet.app.ui.hotwallet.HotWalletCreateFlowTest].
 */
class HotWalletCreateFlow {

    /**
     * The recovery words the dice rolls produced, or null before the dice screen
     * has produced them — and null again after [clear]. Immutable, and
     * defensively copied by [setMnemonic], so nothing outside this class can
     * reach into it.
     */
    var mnemonic: List<String>? = null
        private set

    /** The optional passphrase, `""` until the finish screen sets one. */
    var passphrase: String = ""
        private set

    /**
     * Records the words the dice screen derived.
     *
     * The list is copied: the caller's list may be mutable (a `toMutableList`,
     * a `buildList`), and a holder that aliased it would hold words the caller
     * could still change from under it.
     */
    fun setMnemonic(words: List<String>) {
        mnemonic = words.toList()
    }

    /** Records the passphrase the user typed on the finish screen. */
    fun setPassphrase(value: String) {
        passphrase = value
    }

    /**
     * Drops both secrets.
     *
     * Called after a successful provision and when the walk is abandoned, and
     * idempotent so the two can overlap (the finish screen clears, then the
     * navigation reset leaves the flow — which clears again).
     */
    fun clear() {
        mnemonic = null
        passphrase = ""
    }
}
