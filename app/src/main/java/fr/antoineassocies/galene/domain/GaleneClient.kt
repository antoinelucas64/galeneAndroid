package fr.antoineassocies.galene.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.util.Log
import fr.antoineassocies.galene.domain.model.ChatMessage
import fr.antoineassocies.galene.domain.protocol.*
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Galène signaling client: one WebSocket connection per session.
 *
 * Responsibilities:
 * - open the WebSocket and perform the handshake (protocol v2);
 * - serialise outgoing messages (`send*`);
 * - decode incoming messages and publish them as [SignalingEvent]s on [events].
 *
 * It knows nothing about WebRTC: SDPs and ICE candidates just pass through.
 * OkHttp callbacks arrive on an OkHttp thread; [events] is a thread-safe SharedFlow,
 * collected by the ViewModel on the main thread.
 */
class GaleneClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    companion object {
        private const val TAG = "GaleneClient"
        private const val PROTOCOL_VERSION = "2"
    }

    /**
     * Identifier sent in the handshake; the server uses it as our user `id`. New for each
     * connection: after a network loss the server may still hold the previous session.
     */
    var clientId: String = newId()
        private set

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Replay = 0 because the ViewModel collects in real time; extraBufferCapacity avoids losses
    private val _events = MutableSharedFlow<SignalingEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    // Callbacks from any other socket (one we closed or replaced) are ignored
    @Volatile private var webSocket: WebSocket? = null
    private var currentGroup: String? = null
    // Name given by the server in `joined`, repeated in our chat messages
    private var username: String? = null

    // ─── Connection ────────────────────────────────────────────────────────

    /** Opens the WebSocket to [serverUrl] (`wss://host/ws`), closing any previous connection. */
    fun connect(serverUrl: String) {
        disconnect()
        clientId = newId()
        _connectionState.value = ConnectionState.Connecting
        Log.d(TAG, "connect() → $serverUrl")

        val request = runCatching { Request.Builder().url(serverUrl).build() }
            .onFailure { e ->
                Log.e(TAG, "Invalid URL: $serverUrl — ${e.message}")
                _connectionState.value = ConnectionState.Disconnected
                _events.tryEmit(SignalingEvent.Disconnected("Invalid URL: ${e.message}"))
            }
            .getOrNull() ?: return

        webSocket = httpClient.newWebSocket(request, Listener())
    }

    /**
     * Leaves the group cleanly (`leave`), then closes the WebSocket. Idempotent.
     * Emits no [SignalingEvent.Disconnected]: that event only reports unwanted losses.
     */
    fun disconnect() {
        val ws = webSocket
        webSocket = null
        currentGroup?.let { group ->
            ws?.send(json.encodeToString(LeaveMsg(group = group)))
        }
        ws?.close(1000, "User left")
        currentGroup = null
        _connectionState.value = ConnectionState.Disconnected
    }

    // ─── Sending messages ──────────────────────────────────────────────────

    /** Asks to join [group]. A [token] (invitation link or auth server token) replaces the password. */
    fun sendJoin(group: String, username: String?, password: String?, token: String? = null) {
        currentGroup = group
        send(
            JoinMsg(
                group    = group,
                username = username?.ifBlank { null },
                token    = token?.ifBlank { null },
                // the token takes precedence over the password
                password = if (token.isNullOrBlank()) password?.ifBlank { null } else null,
            )
        )
    }

    /**
     * Declares which media we want to receive, per stream label.
     * The `""` key applies to every label; values: `audio`, `video`, `video-low`.
     */
    fun sendRequest(request: Map<String, List<String>>) {
        send(RequestMsg(request = request))
    }

    /**
     * Publishes (or renegotiates) the up stream [streamId].
     * @param replace id of a previous stream that this one replaces on the server
     * @param kind `"renegotiate"` for a new offer on an established stream (ICE restart)
     */
    fun sendOffer(streamId: String, label: String, sdp: String, replace: String? = null, kind: String? = null) {
        send(OfferMsg(kind = kind, id = streamId, label = label, sdp = sdp, replace = replace))
    }

    /** Asks the server for an ICE restart on a down stream (as the web client does). */
    fun sendRenegotiate(streamId: String) {
        send(RenegotiateMsg(id = streamId))
    }

    /** Answers a down stream offer. */
    fun sendAnswer(streamId: String, sdp: String) {
        send(AnswerMsg(id = streamId, sdp = sdp))
    }

    /**
     * Sends [text] to the whole group, or to the user [dest] only; [me] for an action (`/me`).
     * The server echoes group messages back to us, not private ones.
     */
    fun sendChat(text: String, me: Boolean = false, dest: String? = null) {
        send(ChatMsg(kind = if (me) "me" else null, source = clientId, username = username, dest = dest, value = text))
    }

    /** Raises or lowers our hand: `setdata` on ourselves, seen by everyone in the user list. */
    fun setRaisedHand(raised: Boolean) {
        val value = JsonObject(mapOf("raisehand" to if (raised) JsonPrimitive(true) else JsonNull))
        sendUserAction("setdata", clientId, value)
    }

    /**
     * Acts on the user [dest]. Operator actions: `kick`, `op`, `unop`, `present`, `unpresent`;
     * the server refuses them if we lack the `op` permission.
     */
    fun sendUserAction(kind: String, dest: String, value: JsonElement? = null) {
        send(UserActionMsg(kind = kind, source = clientId, dest = dest, username = username, value = value))
    }

    /** Sends a non-chat message to [dest], e.g. `mute` (an operator asks them to mute). */
    fun sendUserMessage(kind: String, dest: String, value: JsonElement? = null) {
        send(UserMessageMsg(kind = kind, source = clientId, dest = dest, username = username, value = value))
    }

    /** Forwards a local ICE candidate for [streamId] (trickle ICE). */
    fun sendIce(streamId: String, candidate: IceCandidateJson) {
        send(IceMsg(id = streamId, candidate = candidate))
    }

    // ─── WebSocket listener ────────────────────────────────────────────────

    private inner class Listener : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            if (ws !== webSocket) return
            Log.d(TAG, "WebSocket open → sending handshake id=$clientId")
            ws.send(json.encodeToString(HandshakeMsg(id = clientId)))
        }

        override fun onMessage(ws: WebSocket, text: String) {
            if (ws !== webSocket) return
            Log.d(TAG, "← ${text.take(300)}")
            handleMessage(text)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message} / HTTP ${response?.code}", t)
            if (ws !== webSocket) return
            webSocket = null
            _connectionState.value = ConnectionState.Disconnected
            _events.tryEmit(SignalingEvent.Disconnected(t.message ?: t.javaClass.simpleName))
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: code=$code reason=$reason")
            if (ws !== webSocket) return
            webSocket = null
            _connectionState.value = ConnectionState.Disconnected
            _events.tryEmit(SignalingEvent.Disconnected(reason.ifEmpty { "closed by server ($code)" }))
        }
    }

    // ─── Incoming message dispatch ─────────────────────────────────────────

    private fun handleMessage(text: String) {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        when (val type = obj["type"]?.jsonPrimitive?.content) {

            "handshake" -> {
                val msg = decode<ServerHandshakeMsg>(text) ?: return
                // The server sends version either as ["2"] (array) or "2" (string)
                val versions = when (val v = msg.version) {
                    is JsonArray -> v.map { it.jsonPrimitive.content }
                    else         -> listOf(v.jsonPrimitive.content)
                }
                if (PROTOCOL_VERSION !in versions) {
                    disconnect()
                    _events.tryEmit(SignalingEvent.Disconnected("unsupported protocol version $versions"))
                    return
                }
                _connectionState.value = ConnectionState.Connected
                _events.tryEmit(SignalingEvent.Connected(msg.id))
            }

            "joined" -> {
                val msg = decode<JoinedMsg>(text) ?: return
                when (msg.kind) {
                    "join", "change" -> {
                        username = msg.username
                        _events.tryEmit(
                            SignalingEvent.Joined(
                                group = msg.group ?: currentGroup ?: "",
                                username = msg.username ?: "",
                                permissions = msg.permissions ?: emptyList(),
                                rtcConfiguration = msg.rtcConfiguration,
                                change = msg.kind == "change",
                            )
                        )
                    }
                    "fail" -> _events.tryEmit(SignalingEvent.JoinFailed(error = msg.error, message = msg.value))
                    "leave" -> _events.tryEmit(SignalingEvent.JoinLeft(msg.group ?: currentGroup ?: ""))
                }
            }

            "user" -> {
                val msg = decode<UserMsg>(text) ?: return
                _events.tryEmit(
                    SignalingEvent.UserUpdate(
                        kind = msg.kind,
                        id = msg.id,
                        username = msg.username,
                        permissions = msg.permissions,
                        data = msg.data as? JsonObject,
                    )
                )
            }

            "offer" -> {
                val msg = decode<ServerOfferMsg>(text) ?: return
                _events.tryEmit(
                    SignalingEvent.RemoteOffer(
                        id = msg.id,
                        label = msg.label,
                        source = msg.source,
                        username = msg.username,
                        sdp = msg.sdp,
                        replace = msg.replace,
                    )
                )
            }

            "answer" -> {
                val msg = decode<ServerAnswerMsg>(text) ?: return
                _events.tryEmit(SignalingEvent.RemoteAnswer(msg.id, msg.sdp))
            }

            "ice" -> {
                val msg = decode<ServerIceMsg>(text) ?: return
                _events.tryEmit(SignalingEvent.RemoteIce(msg.id, msg.candidate))
            }

            "close" -> {
                val msg = decode<CloseMsg>(text) ?: return
                _events.tryEmit(SignalingEvent.StreamClose(msg.id))
            }

            "abort" -> {
                val msg = decode<AbortMsg>(text) ?: return
                _events.tryEmit(SignalingEvent.StreamAbort(msg.id))
            }

            "renegotiate" -> {
                val msg = decode<RenegotiateMsg>(text) ?: return
                _events.tryEmit(SignalingEvent.Renegotiate(msg.id))
            }

            "ping" -> send(PongMsg())

            "chat", "chathistory" -> {
                val msg = decode<ServerChatMsg>(text) ?: return
                if (msg.kind == "caption") return   // subtitles: not shown yet
                _events.tryEmit(
                    SignalingEvent.Chat(
                        ChatMessage(
                            id = msg.id,
                            source = msg.source?.ifEmpty { null },
                            username = msg.username?.ifEmpty { null },
                            text = msg.value.asText(),
                            time = parseTime(msg.time),
                            kind = if (msg.kind == "me") ChatMessage.Kind.ME else ChatMessage.Kind.NORMAL,
                            isPrivate = !msg.dest.isNullOrEmpty(),
                            privileged = msg.privileged,
                            history = type == "chathistory",
                        )
                    )
                )
            }

            "usermessage" -> {
                val msg = decode<ServerUserMsg>(text) ?: return
                val kind = msg.kind ?: return
                _events.tryEmit(SignalingEvent.UserMessage(kind, msg.username, msg.privileged, msg.value))
            }

            "pong", "useraction", "groupaction" -> Unit   // ignored for now

            else -> Unit // unknown message — silently ignored
        }
    }

    // ─── Utilities ─────────────────────────────────────────────────────────

    private fun newId() = UUID.randomUUID().toString().replace("-", "")

    /** ISO 8601 string (protocol v2) or milliseconds (v1); now if absent or unreadable. */
    private fun parseTime(time: JsonElement?): Long {
        val p = time as? JsonPrimitive ?: return System.currentTimeMillis()
        if (!p.isString) return p.longOrNull ?: System.currentTimeMillis()
        return runCatching { OffsetDateTime.parse(p.content).toInstant().toEpochMilli() }
            .getOrDefault(System.currentTimeMillis())
    }

    private inline fun <reified T> decode(text: String): T? =
        runCatching { json.decodeFromString<T>(text) }.onFailure {
            Log.e(TAG, "Failed to decode ${T::class.simpleName}: ${it.message}\njson=$text")
        }.getOrNull()

    private fun send(msg: Any) {
        val text = when (msg) {
            is HandshakeMsg    -> json.encodeToString(msg)
            is JoinMsg         -> json.encodeToString(msg)
            is LeaveMsg        -> json.encodeToString(msg)
            is RequestMsg      -> json.encodeToString(msg)
            is OfferMsg        -> json.encodeToString(msg)
            is AnswerMsg       -> json.encodeToString(msg)
            is IceMsg          -> json.encodeToString(msg)
            is RenegotiateMsg  -> json.encodeToString(msg)
            is PongMsg         -> json.encodeToString(msg)
            is ChatMsg         -> json.encodeToString(msg)
            is UserActionMsg   -> json.encodeToString(msg)
            is UserMessageMsg  -> json.encodeToString(msg)
            else               -> return
        }
        Log.d(TAG, "→ ${text.take(300)}")
        webSocket?.send(text)
    }
}

/** Text of a message `value`: usually a string, anything else shown as JSON. */
internal fun JsonElement?.asText(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> content
    else -> toString()
}
