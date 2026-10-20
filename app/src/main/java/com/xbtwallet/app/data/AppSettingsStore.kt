package com.xbtwallet.app.data

/**
 * The app's own preferences: behaviour flags that are about the app rather than
 * about one signer or one server.
 *
 * Deliberately tiny, and deliberately behind [KeyValueStore] rather than
 * `Context`, for the same reason as every other store here — it makes the rules
 * testable on the JVM with an in-memory map, while the Android implementation
 * ([SharedPreferencesKeyValueStore]) stays a five-line shell.
 *
 * **One key today.** [keepScreenAwakeWhileSyncing] is the only preference. It
 * exists because a scan can outlast the screen's idle timeout: with it on, the
 * signer screen holds the display on for exactly as long as a scan is in flight
 * (`XbtWalletApp`'s keep-awake effect), so the socket is not torn down and the
 * scan can finish. It is on by default — a user who never opens Preferences gets
 * the behaviour that makes syncing work — and off is one tap away for anyone who
 * would rather have the screen sleep.
 *
 * **Defaults live here, not at the call sites.** [getString] returns null for an
 * absent key, and a value that cannot be parsed as a boolean is treated the same
 * way: both read as the default, so a first launch, a cleared prefs file and a
 * corrupt value all agree. Nothing here throws.
 */
class AppSettingsStore(private val storage: KeyValueStore) {

    /**
     * Whether the screen should be kept awake while a scan is in flight.
     *
     * Persisted on every write. Reads back `true` when the key is absent (the
     * default) or holds anything that is not a boolean.
     */
    var keepScreenAwakeWhileSyncing: Boolean
        get() = storage.getString(KEY_KEEP_SCREEN_AWAKE)?.toBooleanStrictOrNull() ?: DEFAULT_KEEP_SCREEN_AWAKE
        set(value) {
            storage.putString(KEY_KEEP_SCREEN_AWAKE, value.toString())
        }

    companion object {
        /** SharedPreferences key for [keepScreenAwakeWhileSyncing]. */
        const val KEY_KEEP_SCREEN_AWAKE = "keepScreenAwakeWhileSyncing"

        /**
         * What [keepScreenAwakeWhileSyncing] reads as before the user has ever
         * expressed a preference — on, because a screen that sleeps mid-sync is
         * exactly the failure this flag exists to prevent.
         */
        const val DEFAULT_KEEP_SCREEN_AWAKE = true
    }
}
