package fr.antoineassocies.galene.domain.model

/**
 * Un utilisateur présent dans le groupe, tel qu'annoncé par les messages `user` du serveur.
 *
 * @property id identifiant de session Galène (change à chaque connexion)
 * @property permissions droits Galène : `present` (publier), `op` (opérateur), `record`, …
 */
data class Participant(
    val id: String,
    val username: String,
    val permissions: List<String> = emptyList(),
) {
    val canPresent: Boolean get() = "present" in permissions
    val isOperator: Boolean get() = "op" in permissions
}
