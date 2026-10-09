package fr.antoineassocies.galene.data

import android.content.Context

/**
 * Local user preferences: last server and last user name, so they need not be retyped.
 *
 * Deliberately minimal: no password or token is ever stored, and nothing leaves the
 * device (`allowBackup="false"` in the manifest).
 */
class UserPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("galene", Context.MODE_PRIVATE)

    var lastServer: String
        get() = prefs.getString(KEY_SERVER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SERVER, value).apply()

    var lastUsername: String
        get() = prefs.getString(KEY_USERNAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    private companion object {
        const val KEY_SERVER = "last_server"
        const val KEY_USERNAME = "last_username"
    }
}
