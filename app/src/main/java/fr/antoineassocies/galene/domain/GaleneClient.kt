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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.util.Log
import fr.antoineassocies.galene.domain.protocol.*
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

    /** Session identifier sent in the handshake; the server uses it as our user `id`. */
    val clientId: String = UUID.randomUUID().toString().replace("-", "")

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Replay = 0 because the ViewModel collects in real time; extraBufferCapacity avoids losses
    private val _events = MutableSharedFlow<SignalingEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var currentGroup: String? = null

    // ─── Connection ────────────────────────────────────────────────────────

    /** Opens the WebSocket to [serverUrl] (`wss://host/ws`), closing any previous connection. */
    fun connect(serverUrl: String) {
        disconnect()
        _connectionState.value = ConnectionState.Connecting
        Log.d(TAG, "connect() → $serverUrl")

        val request = runCatching { Request.Builder().url(serverUrl).build() }
            .onFailure { e ->
                Log.e(TAG, "Invalid URL: $serverUrl — ${e.message}")
                _connectionState.value = ConnectionState.Disconnected
                _events.tryEmit(SignalingEvent.Disconnected("URL invalide : ${e.message}"))
            }
            .getOrNull() ?: return

        webSocket = httpClient.newWebSocket(request, Listener())
    }

    /** Leaves the group cleanly (`leave`), then closes the WebSocket. Idempotent. */
    fun disconnect() {
        currentGroup?.let { group ->
            webSocket?.send(json.encodeToString(LeaveMsg(group = group)))
        }
        webSocket?.close(1000, "User left")
        webSocket = null
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

    /** Forwards a local ICE candidate for [streamId] (trickle ICE). */
    fun sendIce(streamId: String, candidate: IceCandidateJson) {
        send(IceMsg(id = streamId, candidate = candidate))
    }

    // ─── WebSocket listener ────────────────────────────────────────────────

    private inner class Listener : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket open → sending handshake id=$clientId")
            ws.send(json.encodeToString(HandshakeMsg(id = clientId)))
        }

        override fun onMessage(ws: WebSocket, text: String) {
            Log.d(TAG, "← ${text.take(300)}")
            handleMessage(text)
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message} / HTTP ${response?.code}", t)
            _connectionState.value = ConnectionState.Disconnected
            _events.tryEmit(SignalingEvent.Disconnected(t.message ?: "Erreur réseau"))
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: code=$code reason=$reason")
            _connectionState.value = ConnectionState.Disconnected
            _events.tryEmit(SignalingEvent.Disconnected(if (code != 1000) reason else null))
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
                    return
                }
                _connectionState.value = ConnectionState.Connected
                _events.tryEmit(SignalingEvent.Connected(msg.id))
            }

            "joined" -> {
                val msg = decode<JoinedMsg>(text) ?: return
                when (msg.kind) {
                    "join", "change" -> _events.tryEmit(
                        SignalingEvent.Joined(
                            group = msg.group ?: currentGroup ?: "",
                            username = msg.username ?: "",
                            permissions = msg.permissions ?: emptyList(),
                            rtcConfiguration = msg.rtcConfiguration,
                        )
                    )
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

            "pong", "chat", "chathistory", "usermessage",
            "useraction", "groupaction" -> Unit   // ignored for now

            else -> Unit // unknown message — silently ignored
        }
    }

    // ─── Utilities ─────────────────────────────────────────────────────────

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
            else               -> return
        }
        Log.d(TAG, "→ ${text.take(300)}")
        webSocket?.send(text)
    }
}
