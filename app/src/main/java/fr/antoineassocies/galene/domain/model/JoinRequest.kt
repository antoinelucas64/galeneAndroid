package fr.antoineassocies.galene.domain.model

/**
 * Everything needed to join a group, once authentication is resolved.
 *
 * @property endpoint signaling WebSocket, given by the server in the group status
 * @property group technical group name (may contain `/` for subgroups)
 * @property title display name of the group, for the title bar
 * @property username user name; null lets the server decide (invitation link that already
 *   sets the name). A token that sets the name wins over the one sent.
 * @property password Galène password (exclusive with [token])
 * @property token invitation token, or token issued by an authentication server
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
    // Keeps secrets out of logs
    override fun toString() =
        "JoinRequest(endpoint=$endpoint, group=$group, username=$username, present=$present)"
}
