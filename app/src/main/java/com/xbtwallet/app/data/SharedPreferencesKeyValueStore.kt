package com.xbtwallet.app.data

import android.content.Context

/**
 * The Android side of [KeyValueStore]: one SharedPreferences file.
 *
 * Deliberately this small — all of the wallet logic lives in [WalletStore],
 * which never sees a `Context`. `apply()` writes asynchronously, which is what
 * a settings-style write wants.
 */
class SharedPreferencesKeyValueStore(
    context: Context,
    fileName: String = PREFS_FILE,
) : KeyValueStore {

    private val prefs = context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    companion object {
        /** Separate file from the Electrum server settings, so each can be reset alone. */
        const val PREFS_FILE = "xbt_wallet_store"
    }
}
