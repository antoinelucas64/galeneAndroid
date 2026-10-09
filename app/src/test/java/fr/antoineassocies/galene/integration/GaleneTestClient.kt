package fr.antoineassocies.galene.integration

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import fr.antoineassocies.galene.domain.SignalingEvent
import fr.antoineassocies.galene.domain.protocol.*
import java.util.UUID

/**
 * Lightweight Galène signaling client for integration tests.
 * Pure JVM — deliberately avoids android.util.Log so tests run without a device.
 */
class GaleneTestClient(private val wsUrl: String) : AutoCloseable {

    private val httpClient = OkHttpClient()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true   // obligatoire : type/kind ont des valeurs par défaut qui doivent être sérialisées
    }

    val clientId: String = UUID.randomUUID().toString().replace("-", "").take(16)
    val events = Channel<SignalingEvent>(capacity = Channel.UNLIMITED)

    private var ws: WebSocket? = null
    private var currentGroup: String? = null

    fun connect() {
        val request = Request.Builder().url(wsUrl).build()
        ws = httpClient.newWebSocket(request, WsListener())
    }

    fun sendJoin(group: String, username: String, token: String? = null, password: String? = null) {
        currentGroup = group
        send(JoinMsg(
            group    = group,
            username = username,
            token    = token?.ifBlank { null },
            password = if (token.isNullOrBlank()) password?.ifBlank { null } else null,
        ))
    }

    fun sendOffer(streamId: String, label: String, sdp: String) = send(OfferMsg(id = streamId, label = label, sdp = sdp))
    fun sendAnswer(streamId: String, sdp: String) = send(AnswerMsg(id = streamId, sdp = sdp))
    fun sendIce(streamId: String, candidate: IceCandidateJson) = send(IceMsg(id = streamId, candidate = candidate))
    fun sendRequest(request: Map<String, List<String>>) = send(RequestMsg(request = request))

    /** Suspends until an event matching [predicate] arrives, consuming non-matching events. */
    suspend fun awaitEvent(
        timeoutMs: Long = 8_000L,
        predicate: (SignalingEvent) -> Boolean,
    ): SignalingEvent = withTimeout(timeoutMs) {
        for (event in events) {
            if (predicate(event)) return@withTimeout event
        }
        error("Channel closed before a matching event was received")
    }

    override fun close() {
        currentGroup?.let { ws?.send(json.encodeToString(LeaveMsg(group = it))) }
        ws?.close(1000, "test done")
        events.close()
    }

    // ─── WebSocket listener ────────────────────────────────────────────────────

    private inner class WsListener : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            ws.send(json.encodeToString(HandshakeMsg(id = clientId)))
        }

        override fun onMessage(ws: WebSocket, text: String) = handleMessage(text)

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            events.trySend(SignalingEvent.Disconnected(t.message ?: "network error"))
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            events.trySend(SignalingEvent.Disconnected(if (code != 1000) reason else null))
        }
    }

    // ─── Message dispatch ──────────────────────────────────────────────────────

    private fun handleMessage(text: String) {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.content) {
            "handshake" -> {
                val msg = decode<ServerHandshakeMsg>(text) ?: return
                val versions = when (val v = msg.version) {
                    is JsonArray -> v.map { it.jsonPrimitive.content }
                    else         -> listOf(v.jsonPrimitive.content)
                }
                if ("2" !in versions) {
                    events.trySend(SignalingEvent.Disconnected("Incompatible server protocol: $versions"))
                    return
                }
                events.trySend(SignalingEvent.Connected(msg.id))
            }
            "joined" -> {
                val msg = decode<JoinedMsg>(text) ?: return
                when (msg.kind) {
                    "join", "change" -> events.trySend(SignalingEvent.Joined(
                        group            = msg.group ?: currentGroup ?: "",
                        username         = msg.username ?: "",
                        permissions      = msg.permissions ?: emptyList(),
                        rtcConfiguration = msg.rtcConfiguration,
                    ))
                    "fail"  -> events.trySend(SignalingEvent.JoinFailed(msg.value ?: msg.error))
                    "leave" -> events.trySend(SignalingEvent.JoinLeft(msg.group ?: ""))
                }
            }
            "user" -> {
                val msg = decode<UserMsg>(text) ?: return
                events.trySend(SignalingEvent.UserUpdate(msg.kind, msg.id, msg.username, msg.permissions))
            }
            "offer" -> {
                val msg = decode<ServerOfferMsg>(text) ?: return
                events.trySend(SignalingEvent.RemoteOffer(
                    id = msg.id, label = msg.label, source = msg.source,
                    username = msg.username, sdp = msg.sdp, replace = msg.replace,
                ))
            }
            "answer" -> {
                val msg = decode<ServerAnswerMsg>(text) ?: return
                events.trySend(SignalingEvent.RemoteAnswer(msg.id, msg.sdp))
            }
            "ice" -> {
                val msg = decode<ServerIceMsg>(text) ?: return
                events.trySend(SignalingEvent.RemoteIce(msg.id, msg.candidate))
            }
            "close"       -> decode<CloseMsg>(text)?.let       { events.trySend(SignalingEvent.StreamClose(it.id)) }
            "abort"       -> decode<AbortMsg>(text)?.let       { events.trySend(SignalingEvent.StreamAbort(it.id)) }
            "renegotiate" -> decode<RenegotiateMsg>(text)?.let { events.trySend(SignalingEvent.Renegotiate(it.id)) }
            "ping"        -> ws?.send(json.encodeToString(PongMsg()))
        }
    }

    private fun send(msg: Any) {
        val text = when (msg) {
            is HandshakeMsg -> json.encodeToString(msg)
            is JoinMsg      -> json.encodeToString(msg)
            is LeaveMsg     -> json.encodeToString(msg)
            is RequestMsg   -> json.encodeToString(msg)
            is OfferMsg     -> json.encodeToString(msg)
            is AnswerMsg    -> json.encodeToString(msg)
            is IceMsg       -> json.encodeToString(msg)
            is PongMsg      -> json.encodeToString(msg)
            else            -> return
        }
        ws?.send(text)
    }

    private inline fun <reified T> decode(text: String): T? =
        runCatching { json.decodeFromString<T>(text) }.getOrNull()
}
