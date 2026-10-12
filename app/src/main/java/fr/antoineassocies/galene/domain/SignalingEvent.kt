package fr.antoineassocies.galene.domain

import fr.antoineassocies.galene.domain.model.ChatMessage
import fr.antoineassocies.galene.domain.protocol.IceCandidateJson
import fr.antoineassocies.galene.domain.protocol.RtcConfigurationJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * High-level events produced by [GaleneClient] from the server's messages.
 *
 * The ViewModel never sees the protocol's JSON: it only reacts to these events.
 */
sealed class SignalingEvent {
    /** Handshake accepted; the `join` can be sent. */
    data class Connected(val serverId: String?) : SignalingEvent()

    /** Group join accepted (`joined` kind=join), or permissions changed ([change], kind=change). */
    data class Joined(
        val group: String,
        val username: String,
        val permissions: List<String>,
        val rtcConfiguration: RtcConfigurationJson?,
        val change: Boolean = false,
    ) : SignalingEvent()

    /**
     * Join refused.
     * @property error optional machine-readable code, e.g. `need-username` (the invitation link requires a name)
     * @property message human-readable explanation sent by the server ("not authorised"…)
     */
    data class JoinFailed(val error: String?, val message: String?) : SignalingEvent() {
        val needsUsername: Boolean get() = error == "need-username"
    }

    /** The server made us leave the group. */
    data class JoinLeft(val group: String) : SignalingEvent()

    /** A participant arrived, changed or left. */
    data class UserUpdate(
        val kind: String,       // "add" | "change" | "delete"
        val id: String,
        val username: String?,
        val permissions: List<String>?,
        /** User data, replaced as a whole on each change; `raisehand` set when the hand is up. */
        val data: JsonObject? = null,
    ) : SignalingEvent()

    /**
     * The server offers a down stream (media published by another participant).
     * [replace] names an older stream that this one replaces.
     */
    data class RemoteOffer(
        val id: String,
        val label: String?,
        val source: String?,
        val username: String?,
        val sdp: String,
        val replace: String?,
    ) : SignalingEvent()

    /** The server's answer to our up stream offer. */
    data class RemoteAnswer(val id: String, val sdp: String) : SignalingEvent()

    /** Server ICE candidate for stream [id]. */
    data class RemoteIce(val id: String, val candidate: IceCandidateJson) : SignalingEvent()

    /** The server closes a down stream (the sender stopped publishing or left). */
    data class StreamClose(val id: String) : SignalingEvent()

    /** The server refuses or interrupts our up stream. */
    data class StreamAbort(val id: String) : SignalingEvent()

    /** The server saw an ICE failure on our up stream and asks for an ICE restart. */
    data class Renegotiate(val id: String) : SignalingEvent()

    /** Chat message, new or from the history; captions are not reported. */
    data class Chat(val message: ChatMessage) : SignalingEvent()

    /**
     * Message from another client or the server that is not chat: [kind] `error`, `warning`,
     * `info`, `kicked` (shown to the user), `mute` (we are asked to mute), `clearchat`.
     * Only honoured when [privileged] (sent by an operator or the server), as the web client does.
     */
    data class UserMessage(
        val kind: String,
        val username: String?,
        val privileged: Boolean,
        val value: JsonElement?,
    ) : SignalingEvent()

    /** Connection lost or closed by the server (never emitted for our own [GaleneClient.disconnect]). */
    data class Disconnected(val reason: String) : SignalingEvent()
}
