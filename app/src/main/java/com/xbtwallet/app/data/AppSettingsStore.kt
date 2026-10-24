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
 * **One key today.** [keepScreenOnWhileOpen] is the only preference. It holds
 * the display on whenever the app is open in the foreground: `LocalView`'s
 * `keepScreenOn` flag applies only while the app's window is on screen, so the
 * preference means "do not let the device sleep out from under me while I am
 * looking at the app" — no wakelock (which would keep the CPU up with the app
 * hidden) and no permission. It used to mean "keep awake while a scan is in
 * flight"; the display now stays on the whole time the app is open, which still
 * covers that case and is the simpler rule to explain. It is on by default — a
 * user who never opens Preferences gets the behaviour — and off is one tap away
 * for anyone who would rather have the screen sleep.
 *
 * **Defaults live here, not at the call sites.** [getString] returns null for an
 * absent key, and a value that cannot be parsed as a boolean is treated the same
 * way: both read as the default, so a first launch, a cleared prefs file and a
 * corrupt value all agree. Nothing here throws.
 */
class AppSettingsStore(private val storage: KeyValueStore) {

    /**
     * Whether the screen should be kept on while the app is open.
     *
     * Persisted on every write, under [KEY_KEEP_SCREEN_ON] — a key name frozen
     * at the old "keep awake while syncing" wording so that a user's existing
     * on/off choice carries over to the new semantics untouched.
     *
     * Reads back `true` when the key is absent (the default) or holds anything
     * that is not a boolean.
     */
    var keepScreenOnWhileOpen: Boolean
        get() = storage.getString(KEY_KEEP_SCREEN_ON)?.toBooleanStrictOrNull() ?: DEFAULT_KEEP_SCREEN_ON
        set(value) {
            storage.putString(KEY_KEEP_SCREEN_ON, value.toString())
        }

    /**
     * Whether the user has already dismissed the app lock's no-device-lock
     * warning.
     *
     * The warning exists because a device with no lock screen cannot have an app
     * lock, which the user should be told once. It is dismissible and the
     * dismissal is remembered, so it is a one-time notice rather than a dialog on
     * every launch; a device that *does* have a lock screen never shows it and
     * never reads this flag.
     *
     * Reads back `false` when the key is absent (the default) or holds anything
     * that is not a boolean, so a first launch and a corrupt value agree.
     */
    var lockWarningDismissed: Boolean
        get() = storage.getString(KEY_LOCK_WARNING_DISMISSED)?.toBooleanStrictOrNull()
            ?: DEFAULT_LOCK_WARNING_DISMISSED
        set(value) {
            storage.putString(KEY_LOCK_WARNING_DISMISSED, value.toString())
        }

    companion object {
        /**
         * SharedPreferences key for [lockWarningDismissed].
         *
         * Its own key, and a name that says what it records — nothing depends on
         * a legacy value here, so it is free to be legible.
         */
        const val KEY_LOCK_WARNING_DISMISSED = "appLockWarningDismissed"

        /** Shown until the user has dismissed it: the warning is on by default. */
        const val DEFAULT_LOCK_WARNING_DISMISSED = false

        /**
         * SharedPreferences key for [keepScreenOnWhileOpen].
         *
         * **The value is deliberately the retired wording and must not change.**
         * The name is frozen at `"keepScreenAwakeWhileSyncing"` purely so that a
         * stored preference written by an older build is read back by this one:
         * renaming the key would silently reset every user's choice to the
         * default. Only the *semantics* changed with the label.
         */
        const val KEY_KEEP_SCREEN_ON = "keepScreenAwakeWhileSyncing"

        /**
         * What [keepScreenOnWhileOpen] reads as before the user has ever
         * expressed a preference — on, because a screen that sleeps while the
         * app is open is exactly the failure this flag exists to prevent.
         */
        const val DEFAULT_KEEP_SCREEN_ON = true
    }
}
