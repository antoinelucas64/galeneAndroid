package fr.antoineassocies.galene.domain.model

import kotlinx.serialization.Serializable

/**
 * Description publique d'un groupe Galène, telle que servie par `/group/<nom>/.status`
 * et, sous forme de liste, par `/public-groups.json`.
 *
 * Seuls [name] et [location] sont garantis ; le reste dépend de la configuration du groupe.
 *
 * @property location URL web du groupe (`https://hôte/group/<nom>/`)
 * @property endpoint URL du WebSocket de signaling à utiliser (`wss://hôte/ws`)
 * @property authServer si présent, le mot de passe est vérifié par ce serveur
 *   (ex. comptes YunoHost), qui délivre un jeton pour le `join`
 * @property authPortal si présent, l'authentification se fait sur ce portail web (SSO)
 * @property locked groupe verrouillé par un opérateur : seuls les opérateurs peuvent entrer
 */
@Serializable
data class GroupStatus(
    val name: String,
    val location: String? = null,
    val endpoint: String? = null,
    val displayName: String? = null,
    val description: String? = null,
    val authServer: String? = null,
    val authPortal: String? = null,
    val locked: Boolean = false,
    val clientCount: Int? = null,
) {
    /** Nom à afficher : celui choisi par l'administrateur, sinon le nom technique. */
    val title: String get() = displayName?.takeIf { it.isNotBlank() } ?: name
}
