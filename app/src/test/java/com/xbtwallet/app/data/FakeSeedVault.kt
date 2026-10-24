package com.xbtwallet.app.data

/**
 * An in-memory [SeedVault] for JVM tests.
 *
 * The real vault is a thin shell over the Android keystore, which no unit test
 * can stand up — and which is not what the interesting rules are about anyway.
 * Everything that has to hold for a hot wallet *because* its entropy lives in
 * the vault (the write-before-record ordering, deleting a wallet taking its seed
 * with it, the vault holding entropy rather than a seed) is about the
 * [SeedVault] contract, so a map is enough to test all of it.
 *
 * Two deliberate details:
 *
 *  - **[copyOf] in and out.** The vault must never alias a caller's array: the
 *    provisioner wipes its copy of the entropy as soon as it has been stored, so
 *    an implementation that kept the reference would appear to lose the seed.
 *    Copying on both sides is what makes this fake behave like the real one,
 *    which encrypts immediately and never holds a caller's array either.
 *  - **A call log.** [deleted] records every [delete] and [stores] every
 *    [store], so a test can assert that a *watch-only* signer's removal touched
 *    the vault not at all — which is only observable as "no call was made".
 *
 * It never challenges: a read is always [SeedLoad.Loaded] or [SeedLoad.Absent].
 * The authentication path belongs to [KeystoreSeedVault] and is tested there,
 * against a fake key provider.
 */
class FakeSeedVault : SeedVault {

    private val entries = mutableMapOf<String, ByteArray>()

    /** Every id [delete] has been called with, in order — including ones with no entry. */
    val deleted = mutableListOf<String>()

    /** Every `(signerId, entropy)` [store] has been called with, in order. */
    val stores = mutableListOf<Pair<String, ByteArray>>()

    override fun store(signerId: String, entropy: ByteArray) {
        entries[signerId] = entropy.copyOf()
        stores += signerId to entropy.copyOf()
    }

    override fun load(signerId: String): SeedLoad =
        entries[signerId]?.let { SeedLoad.Loaded(it.copyOf()) } ?: SeedLoad.Absent

    override fun delete(signerId: String) {
        entries.remove(signerId)
        deleted += signerId
    }

    override fun has(signerId: String): Boolean = entries.containsKey(signerId)

    /** What is stored, without the defensive copy — for asserting on the bytes themselves. */
    fun peek(signerId: String): ByteArray? = entries[signerId]
}
