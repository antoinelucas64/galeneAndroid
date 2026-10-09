package fr.antoineassocies.galene.domain.model

/**
 * Tout ce qu'il faut pour entrer dans un groupe, une fois l'authentification résolue.
 *
 * @property endpoint WebSocket de signaling, fourni par le serveur dans le statut du groupe
 * @property group nom technique du groupe (peut contenir des `/` pour les sous-groupes)
 * @property title nom affiché du groupe, pour la barre de titre
 * @property username nom d'utilisateur ; null laisse le serveur décider (lien d'invitation
 *   qui fixe déjà le nom). Un token qui fixe le nom l'emporte sur celui envoyé.
 * @property password mot de passe Galène (exclusif avec [token])
 * @property token jeton d'invitation ou jeton délivré par un serveur d'authentification
 */
data class JoinRequest(
    val endpoint: String,
    val group: String,
    val title: String,
    val username: String?,
    val password: String?,
    val token: String?,
    val present: PresentMode,
) {
    // Évite de faire fuiter les secrets dans les logs
    override fun toString() =
        "JoinRequest(endpoint=$endpoint, group=$group, username=$username, present=$present)"
}
