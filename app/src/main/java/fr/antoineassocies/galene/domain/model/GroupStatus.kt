package fr.antoineassocies.galene.domain.model

import kotlinx.serialization.Serializable

/**
 * Public description of a Galène group, as served by `/group/<name>/.status`
 * and, as a list, by `/public-groups.json`.
 *
 * Only [name] and [location] are guaranteed; the rest depends on the group's configuration.
 *
 * @property location web URL of the group (`https://host/group/<name>/`)
 * @property endpoint URL of the signaling WebSocket to use (`wss://host/ws`)
 * @property authServer if present, the password is checked by this server
 *   (e.g. YunoHost accounts), which issues a token for the `join`
 * @property authPortal if present, authentication happens on this web portal (SSO)
 * @property locked group locked by an operator: only operators may join
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
    /** Name to display: the one chosen by the administrator, or else the technical name. */
    val title: String get() = displayName?.takeIf { it.isNotBlank() } ?: name
}
