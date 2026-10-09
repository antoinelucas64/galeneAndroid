/**
 * Messages JSON du protocole de signaling Galène (version 2).
 *
 * Référence : https://galene.org/README.PROTOCOL.html et static/protocol.js du dépôt Galène.
 * Côté client, les champs `type`/`kind` ont des valeurs par défaut : le [kotlinx.serialization.json.Json]
 * injecté doit avoir `encodeDefaults = true`, sinon ils sont omis et le serveur ignore le message.
 * Côté serveur, `ignoreUnknownKeys = true` tolère les champs ajoutés par les futures versions.
 */
package fr.antoineassocies.galene.domain.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ─── Client → Serveur ────────────────────────────────────────────────────────

@Serializable
data class HandshakeMsg(
    val type: String = "handshake",
    val version: List<String> = listOf("2"),
    val id: String,
)

@Serializable
data class JoinMsg(
    val type: String = "join",
    val kind: String = "join",
    val group: String,
    val username: String? = null,
    val password: String? = null,
    val token: String? = null,      // exclusif avec password — invitation link
)

@Serializable
data class LeaveMsg(
    val type: String = "join",
    val kind: String = "leave",
    val group: String,
)

@Serializable
data class RequestMsg(
    val type: String = "request",
    val request: Map<String, List<String>>,
)

@Serializable
data class OfferMsg(
    val type: String = "offer",
    val kind: String? = null,       // "renegotiate" pour une re-offre sur un stream existant
    val id: String,
    val label: String,
    val sdp: String,
    val replace: String? = null,
)

@Serializable
data class AnswerMsg(
    val type: String = "answer",
    val id: String,
    val sdp: String,
)

@Serializable
data class IceMsg(
    val type: String = "ice",
    val id: String,
    val candidate: IceCandidateJson,
)

@Serializable
data class PongMsg(val type: String = "pong")

// ─── ICE candidate (partagé up/down) ────────────────────────────────────────

@Serializable
data class IceCandidateJson(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val usernameFragment: String? = null,
)

// ─── Serveur → Client ────────────────────────────────────────────────────────

@Serializable
data class ServerHandshakeMsg(
    val type: String,
    val version: JsonElement,   // ["2"] (tableau) selon impl réelle du serveur
    val id: String? = null,
)

@Serializable
data class JoinedMsg(
    val type: String,
    val kind: String,           // "join" | "fail" | "change" | "leave"
    val error: String? = null,
    val value: String? = null,  // message d'erreur humain (ex: "not authorised")
    val group: String? = null,
    val username: String? = null,
    val permissions: List<String>? = null,
    val status: JsonElement? = null,
    val data: JsonElement? = null,
    val rtcConfiguration: RtcConfigurationJson? = null,
)

@Serializable
data class RtcConfigurationJson(
    val iceServers: List<IceServerJson>? = null,
)

@Serializable
data class IceServerJson(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class UserMsg(
    val type: String,
    val kind: String,           // "add" | "change" | "delete"
    val id: String,
    val username: String? = null,
    val permissions: List<String>? = null,
    val status: JsonElement? = null,
)

@Serializable
data class ServerOfferMsg(
    val type: String,
    val id: String,
    val label: String? = null,
    val replace: String? = null,
    val source: String? = null,
    val username: String? = null,
    val sdp: String,
)

@Serializable
data class ServerAnswerMsg(
    val type: String,
    val id: String,
    val sdp: String,
)

@Serializable
data class ServerIceMsg(
    val type: String,
    val id: String,
    val candidate: IceCandidateJson,
)

@Serializable
data class CloseMsg(val type: String, val id: String)

@Serializable
data class AbortMsg(val type: String, val id: String)

@Serializable
data class RenegotiateMsg(val type: String = "renegotiate", val id: String)
