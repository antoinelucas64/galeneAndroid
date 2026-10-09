/**
 * JSON messages of Galène's signaling protocol (version 2).
 *
 * Reference: https://galene.org/README.PROTOCOL.html and static/protocol.js in the Galène repository.
 * Client side, the `type`/`kind` fields have default values: the injected [kotlinx.serialization.json.Json]
 * must have `encodeDefaults = true`, otherwise they are omitted and the server ignores the message.
 * Server side, `ignoreUnknownKeys = true` tolerates fields added by future versions.
 */
package fr.antoineassocies.galene.domain.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ─── Client → Server ─────────────────────────────────────────────────────────

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
    val token: String? = null,      // exclusive with password — invitation link or auth server token
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
    val kind: String? = null,       // "renegotiate" for a new offer on an existing stream
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

// ─── ICE candidate (shared up/down) ─────────────────────────────────────────

@Serializable
data class IceCandidateJson(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val usernameFragment: String? = null,
)

// ─── Server → Client ─────────────────────────────────────────────────────────

@Serializable
data class ServerHandshakeMsg(
    val type: String,
    val version: JsonElement,   // ["2"] (array) in the actual server implementation
    val id: String? = null,
)

@Serializable
data class JoinedMsg(
    val type: String,
    val kind: String,           // "join" | "fail" | "change" | "leave"
    val error: String? = null,
    val value: String? = null,  // human-readable error message (e.g. "not authorised")
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
