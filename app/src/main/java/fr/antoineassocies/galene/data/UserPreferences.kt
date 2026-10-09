package fr.antoineassocies.galene.data

import android.content.Context

/**
 * Préférences locales de l'utilisateur : dernier serveur et dernier nom d'utilisateur,
 * pour ne pas les ressaisir à chaque fois.
 *
 * Volontairement minimal : aucun mot de passe ni token n'est jamais enregistré,
 * et rien ne quitte l'appareil (`allowBackup="false"` au manifeste).
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
