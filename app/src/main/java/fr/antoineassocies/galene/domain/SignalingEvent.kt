package fr.antoineassocies.galene.domain

import fr.antoineassocies.galene.domain.protocol.IceCandidateJson
import fr.antoineassocies.galene.domain.protocol.RtcConfigurationJson

/**
 * Événements de haut niveau produits par [GaleneClient] à partir des messages du serveur.
 *
 * Le ViewModel ne voit jamais le JSON du protocole : il réagit uniquement à ces événements.
 */
sealed class SignalingEvent {
    /** Handshake accepté ; on peut envoyer le `join`. */
    data class Connected(val serverId: String?) : SignalingEvent()

    /** Entrée dans le groupe acceptée (`joined` kind=join), ou droits modifiés (kind=change). */
    data class Joined(
        val group: String,
        val username: String,
        val permissions: List<String>,
        val rtcConfiguration: RtcConfigurationJson?,
    ) : SignalingEvent()

    /** Entrée refusée (mauvais mot de passe, groupe inexistant…) ; [error] est lisible par un humain. */
    data class JoinFailed(val error: String?) : SignalingEvent()

    /** Le serveur nous a fait sortir du groupe. */
    data class JoinLeft(val group: String) : SignalingEvent()

    /** Arrivée, modification ou départ d'un participant. */
    data class UserUpdate(
        val kind: String,       // "add" | "change" | "delete"
        val id: String,
        val username: String?,
        val permissions: List<String>?,
    ) : SignalingEvent()

    /**
     * Le serveur propose un flux descendant (média publié par un autre participant).
     * [replace] désigne un ancien flux que celui-ci remplace.
     */
    data class RemoteOffer(
        val id: String,
        val label: String?,
        val source: String?,
        val username: String?,
        val sdp: String,
        val replace: String?,
    ) : SignalingEvent()

    /** Réponse du serveur à notre offre de flux montant. */
    data class RemoteAnswer(val id: String, val sdp: String) : SignalingEvent()

    /** Candidat ICE du serveur pour le stream [id]. */
    data class RemoteIce(val id: String, val candidate: IceCandidateJson) : SignalingEvent()

    /** Le serveur ferme un flux descendant (l'émetteur a arrêté de publier ou est parti). */
    data class StreamClose(val id: String) : SignalingEvent()

    /** Le serveur refuse ou interrompt notre flux montant. */
    data class StreamAbort(val id: String) : SignalingEvent()

    /** Le serveur constate un échec ICE sur notre flux montant et demande un ICE restart. */
    data class Renegotiate(val id: String) : SignalingEvent()

    /** WebSocket fermé ; [reason] est null pour une fermeture normale demandée par nous. */
    data class Disconnected(val reason: String? = null) : SignalingEvent()
}
