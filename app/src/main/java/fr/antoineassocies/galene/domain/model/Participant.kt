package fr.antoineassocies.galene.domain.model

/**
 * A user present in the group, as announced by the server's `user` messages.
 *
 * @property id Galène session identifier (changes on every connection)
 * @property permissions Galène permissions: `present` (publish), `op` (operator), `record`, …
 */
data class Participant(
    val id: String,
    val username: String,
    val permissions: List<String> = emptyList(),
    /** Hand raised (user data `raisehand`), as in the web client's user list. */
    val raisedHand: Boolean = false,
) {
    val canPresent: Boolean get() = "present" in permissions
    val isOperator: Boolean get() = "op" in permissions
}
