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
 * Client de signaling Galène : une connexion WebSocket par session.
 *
 * Responsabilités :
 * - ouvrir le WebSocket et faire le handshake (protocole v2) ;
 * - sérialiser les messages sortants (`send*`) ;
 * - décoder les messages entrants et les publier comme [SignalingEvent] sur [events].
 *
 * Il ne connaît rien de WebRTC : les SDP et candidats ICE ne font que transiter.
 * Les callbacks OkHttp arrivent sur un thread d'OkHttp ; [events] est un SharedFlow
 * thread-safe, collecté par le ViewModel sur le thread principal.
 */
class GaleneClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    companion object {
        private const val TAG = "GaleneClient"
        private const val PROTOCOL_VERSION = "2"
    }

    /** Identifiant de session envoyé au handshake ; le serveur l'utilise comme `id` utilisateur. */
    val clientId: String = UUID.randomUUID().toString().replace("-", "")

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Replay = 0 car le ViewModel collecte en temps réel ; extraBufferCapacity évite les pertes
    private val _events = MutableSharedFlow<SignalingEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var currentGroup: String? = null

    // ─── Connexion ─────────────────────────────────────────────────────────

    /** Ouvre le WebSocket vers [serverUrl] (`wss://hôte/ws`), en fermant toute connexion précédente. */
    fun connect(serverUrl: String) {
        disconnect()
        _connectionState.value = ConnectionState.Connecting
        Log.d(TAG, "connect() → $serverUrl")

        val request = runCatching { Request.Builder().url(serverUrl).build() }
            .onFailure { e ->
                Log.e(TAG, "URL invalide : $serverUrl — ${e.message}")
                _connectionState.value = ConnectionState.Disconnected
                _events.tryEmit(SignalingEvent.Disconnected("URL invalide : ${e.message}"))
            }
            .getOrNull() ?: return

        webSocket = httpClient.newWebSocket(request, Listener())
    }

    /** Quitte le groupe proprement (`leave`) puis ferme le WebSocket. Idempotent. */
    fun disconnect() {
        currentGroup?.let { group ->
            webSocket?.send(json.encodeToString(LeaveMsg(group = group)))
        }
        webSocket?.close(1000, "User left")
        webSocket = null
        currentGroup = null
        _connectionState.value = ConnectionState.Disconnected
    }

    // ─── Envoi de messages ─────────────────────────────────────────────────

    /** Demande l'entrée dans [group]. Un [token] (lien d'invitation) remplace le mot de passe. */
    fun sendJoin(group: String, username: String?, password: String?, token: String? = null) {
        currentGroup = group
        send(
            JoinMsg(
                group    = group,
                username = username?.ifBlank { null },
                token    = token?.ifBlank { null },
                // token prend la priorité sur le password
                password = if (token.isNullOrBlank()) password?.ifBlank { null } else null,
            )
        )
    }

    /**
     * Indique quels médias on veut recevoir, par label de flux.
     * La clé `""` s'applique à tous les labels ; valeurs : `audio`, `video`, `video-low`.
     */
    fun sendRequest(request: Map<String, List<String>>) {
        send(RequestMsg(request = request))
    }

    /**
     * Publie (ou renégocie) le flux montant [streamId].
     * @param replace id d'un flux précédent que celui-ci remplace côté serveur
     * @param kind `"renegotiate"` pour une re-offre sur un stream déjà établi (ICE restart)
     */
    fun sendOffer(streamId: String, label: String, sdp: String, replace: String? = null, kind: String? = null) {
        send(OfferMsg(kind = kind, id = streamId, label = label, sdp = sdp, replace = replace))
    }

    /** Demande au serveur un ICE restart sur un stream descendant (comme le client web). */
    fun sendRenegotiate(streamId: String) {
        send(RenegotiateMsg(id = streamId))
    }

    /** Répond à une offre de flux descendant. */
    fun sendAnswer(streamId: String, sdp: String) {
        send(AnswerMsg(id = streamId, sdp = sdp))
    }

    /** Transmet un candidat ICE local pour [streamId] (trickle ICE). */
    fun sendIce(streamId: String, candidate: IceCandidateJson) {
        send(IceMsg(id = streamId, candidate = candidate))
    }

    // ─── Listener WebSocket ────────────────────────────────────────────────

    private inner class Listener : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket ouvert → envoi handshake id=$clientId")
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
            Log.d(TAG, "WebSocket fermé: code=$code reason=$reason")
            _connectionState.value = ConnectionState.Disconnected
            _events.tryEmit(SignalingEvent.Disconnected(if (code != 1000) reason else null))
        }
    }

    // ─── Dispatch des messages entrants ────────────────────────────────────

    private fun handleMessage(text: String) {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        when (val type = obj["type"]?.jsonPrimitive?.content) {

            "handshake" -> {
                val msg = decode<ServerHandshakeMsg>(text) ?: return
                // Le serveur renvoie version comme ["2"] (tableau) ou "2" (string)
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
                    "fail" -> _events.tryEmit(SignalingEvent.JoinFailed(msg.value ?: msg.error))
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
            "useraction", "groupaction" -> Unit   // ignoré pour l'instant

            else -> Unit // message inconnu — on ignore silencieusement
        }
    }

    // ─── Utilitaires ───────────────────────────────────────────────────────

    private inline fun <reified T> decode(text: String): T? =
        runCatching { json.decodeFromString<T>(text) }.onFailure {
            Log.e(TAG, "Échec décodage ${T::class.simpleName}: ${it.message}\njson=$text")
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
