package fr.antoineassocies.galene.domain.model

/** Paramètres saisis sur l'écran de connexion pour rejoindre une salle. */
data class JoinRequest(
    val serverUrl: String,
    val roomName: String,
    val displayName: String,
    val password: String?,
    val token: String?,      // prioritaire sur password
) {
    // Évite de faire fuiter les secrets dans les logs
    override fun toString() =
        "JoinRequest(serverUrl=$serverUrl, roomName=$roomName, displayName=$displayName)"
}
