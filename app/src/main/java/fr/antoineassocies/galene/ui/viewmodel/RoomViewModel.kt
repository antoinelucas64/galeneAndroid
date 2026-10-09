package fr.antoineassocies.galene.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import fr.antoineassocies.galene.domain.AudioRouter
import fr.antoineassocies.galene.domain.ConnectionState
import fr.antoineassocies.galene.domain.GaleneClient
import fr.antoineassocies.galene.domain.SignalingEvent
import fr.antoineassocies.galene.domain.WebRTCManager
import fr.antoineassocies.galene.domain.model.JoinRequest
import fr.antoineassocies.galene.domain.model.Participant
import fr.antoineassocies.galene.service.CallForegroundService
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.util.UUID

/** Flux descendant reçu du serveur (un par média publié par un autre participant). */
data class RemoteStream(
    val id: String,
    val sourceId: String?,
    val username: String,
)

/** État complet de l'écran de salle, observé par [fr.antoineassocies.galene.ui.screens.RoomScreen]. */
data class RoomUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val audioMuted: Boolean = false,
    val videoMuted: Boolean = false,
    val participants: List<Participant> = emptyList(),
    val remoteStreams: List<RemoteStream> = emptyList(),
    val selfId: String = "",
    val sendingVideo: Boolean = false,      // une caméra est ouverte et publiée
    val frontCamera: Boolean = true,        // pour le miroir de l'aperçu local
    val selfUsername: String = "",
    val errorMessage: String? = null,
    val statusText: String = "",            // info debug visible dans l'UI
)

/**
 * Chef d'orchestre d'un appel : relie le signaling ([GaleneClient]), les médias
 * ([WebRTCManager]) et le routage audio ([AudioRouter]), et expose l'état à l'UI.
 *
 * Déroulé d'un appel :
 * 1. `init` : connexion WebSocket ;
 * 2. handshake → `join` ; `joined` → configuration ICE, requête des flux à recevoir ;
 * 3. une fois joint ET les permissions répondues → publication du flux montant ;
 * 4. chaque `offer` du serveur → un flux descendant affiché dans la grille ;
 * 5. [leave] (ou destruction du ViewModel) → tout est libéré.
 *
 * Le ViewModel vit aussi longtemps que l'appel : il survit aux rotations d'écran
 * et au passage en arrière-plan (couvert par [CallForegroundService]).
 */
class RoomViewModel(
    private val appContext: Context,
    private val request: JoinRequest,
    private val galeneClient: GaleneClient,
    private val webRTCManager: WebRTCManager,
    private val audioRouter: AudioRouter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoomUiState(selfId = galeneClient.clientId))
    val uiState: StateFlow<RoomUiState> = _uiState.asStateFlow()

    // Exposés tels quels pour les renderers vidéo de l'UI
    val eglContext: EglBase.Context get() = webRTCManager.eglContext
    val localVideoTrack: StateFlow<VideoTrack?> = webRTCManager.localVideoTrack
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = webRTCManager.remoteVideoTracks

    // Maintenu localement pour savoir si on a le droit d'émettre
    private var selfPermissions: List<String> = emptyList()

    // ID du stream uplink actif
    private var uplinkStreamId: String? = null

    // L'uplink démarre seulement quand on est joint ET que les permissions ont été répondues,
    // quel que soit l'ordre d'arrivée des deux (le joined précède souvent la réponse à la popup)
    private var joined = false
    private var permissionsAnswered = false
    private var micGranted = false
    private var cameraGranted = false
    private var uplinkRequested = false

    // Caméra coupée par le passage en arrière-plan (et non par l'utilisateur) → à rallumer au retour
    private var videoPausedByBackground = false

    init {
        _uiState.update { it.copy(statusText = "Connexion à ${request.serverUrl}…") }
        observeSignaling()
        observeConnectionState()
        webRTCManager.onLocalIceCandidate = { streamId, candidate ->
            galeneClient.sendIce(streamId, candidate)
        }
        // Même stratégie que le client web : on relance sans attendre le renegotiate du serveur
        webRTCManager.onIceFailed = { streamId, up ->
            if (up) restartUplinkIce(streamId) else galeneClient.sendRenegotiate(streamId)
        }
        galeneClient.connect(request.serverUrl)
        startConnectionTimeout()
    }

    private fun startConnectionTimeout() {
        viewModelScope.launch {
            delay(15_000)
            if (_uiState.value.connectionState == ConnectionState.Connecting) {
                val msg = "Pas de réponse après 15 s — vérifier l'URL et le réseau\n${request.serverUrl}"
                _uiState.update { it.copy(statusText = msg, errorMessage = msg) }
                galeneClient.disconnect()
            }
        }
    }

    // ─── Signaling loop ────────────────────────────────────────────────────

    private fun observeSignaling() {
        viewModelScope.launch {
            galeneClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.Connected -> {
                        _uiState.update { it.copy(statusText = "Handshake OK → join ${request.roomName}…") }
                        galeneClient.sendJoin(request.roomName, request.displayName, request.password, request.token)
                    }

                    is SignalingEvent.Joined -> {
                        _uiState.update { it.copy(statusText = "Connecté · ${event.permissions.joinToString()}") }
                        selfPermissions = event.permissions
                        webRTCManager.applyRtcConfiguration(event.rtcConfiguration)
                        audioRouter.start()
                        _uiState.update { it.copy(selfUsername = event.username) }

                        // Déclarer ce qu'on veut recevoir (audio pour Phase 1)
                        galeneClient.sendRequest(mapOf("" to listOf("audio", "video")))

                        joined = true
                        maybeStartUplink()
                    }

                    is SignalingEvent.JoinFailed -> {
                        _uiState.update {
                            it.copy(
                                statusText = "Join refusé : ${event.error}",
                                errorMessage = "Connexion refusée : ${event.error ?: "inconnu"}"
                            )
                        }
                    }

                    is SignalingEvent.JoinLeft -> {
                        _uiState.update { it.copy(participants = emptyList()) }
                    }

                    is SignalingEvent.UserUpdate -> handleUserUpdate(event)

                    is SignalingEvent.RemoteOffer -> handleRemoteOffer(event)

                    is SignalingEvent.RemoteAnswer -> {
                        // launch sur Main.immediate : settingRemote est posé avant que l'ICE suivant soit traité
                        viewModelScope.launch {
                            runCatching { webRTCManager.handleUpstreamAnswer(event.id, event.sdp) }
                                .onFailure { _uiState.update { s -> s.copy(statusText = "Answer rejetée : ${it.message}") } }
                        }
                    }

                    is SignalingEvent.RemoteIce -> {
                        webRTCManager.addRemoteIceCandidate(event.id, event.candidate)
                    }

                    is SignalingEvent.StreamClose -> {
                        webRTCManager.closeDownStream(event.id)
                        _uiState.update { s -> s.copy(remoteStreams = s.remoteStreams.filterNot { it.id == event.id }) }
                    }

                    is SignalingEvent.StreamAbort -> {
                        if (event.id == uplinkStreamId) {
                            webRTCManager.closeUpStream()
                            uplinkStreamId = null
                            uplinkRequested = false
                            _uiState.update { it.copy(sendingVideo = false) }
                        }
                    }

                    is SignalingEvent.Renegotiate -> {
                        // Galène signale un échec ICE sur ce stream → ICE restart
                        restartUplinkIce(event.id)
                    }

                    is SignalingEvent.Disconnected -> {
                        val msg = event.reason ?: "Connexion fermée"
                        _uiState.update {
                            it.copy(
                                statusText = "Déconnecté : $msg",
                                errorMessage = if (event.reason != null) "Connexion perdue : $msg" else null
                            )
                        }
                    }
                }
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            galeneClient.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
    }

    // ─── Gestion participants ──────────────────────────────────────────────

    private fun handleUserUpdate(event: SignalingEvent.UserUpdate) {
        _uiState.update { state ->
            val participants = state.participants.toMutableList()
            when (event.kind) {
                "add" -> participants.add(
                    Participant(
                        id = event.id,
                        username = event.username ?: event.id,
                        permissions = event.permissions ?: emptyList(),
                    )
                )
                "change" -> {
                    val idx = participants.indexOfFirst { it.id == event.id }
                    if (idx >= 0) {
                        participants[idx] = participants[idx].copy(
                            username = event.username ?: participants[idx].username,
                            permissions = event.permissions ?: participants[idx].permissions,
                        )
                    }
                }
                "delete" -> participants.removeAll { it.id == event.id }
            }
            state.copy(participants = participants)
        }
    }

    // ─── WebRTC uplink ─────────────────────────────────────────────────────

    /**
     * Réponse de l'utilisateur à la demande de permissions. Démarre le service de premier plan
     * si le micro est autorisé, et la publication dès qu'on est joint.
     */
    fun onPermissionsResult(mic: Boolean, camera: Boolean) {
        permissionsAnswered = true
        micGranted = mic
        cameraGranted = camera
        val refused = listOfNotNull("micro".takeIf { !mic }, "caméra".takeIf { !camera })
        if (refused.isNotEmpty()) {
            _uiState.update { it.copy(statusText = "Refusé : ${refused.joinToString()}") }
        }
        // Le micro est autorisé et l'app est visible : seul moment où Android 14 accepte
        // de démarrer un service de premier plan « microphone »
        if (mic) CallForegroundService.start(appContext)
        maybeStartUplink()
    }

    private fun maybeStartUplink() {
        if (!joined || !permissionsAnswered || "present" !in selfPermissions || uplinkRequested) return
        if (!micGranted && !cameraGranted) return   // rien à publier → écoute seule
        uplinkRequested = true
        startUplink()
    }

    private fun startUplink() {
        val streamId = UUID.randomUUID().toString().replace("-", "")
        viewModelScope.launch {
            runCatching {
                val sdp = webRTCManager.startUpstream(streamId, "camera", withAudio = micGranted, withVideo = cameraGranted)
                galeneClient.sendOffer(streamId, "camera", sdp)
                uplinkStreamId = streamId
                _uiState.update { it.copy(sendingVideo = webRTCManager.localVideoTrack.value != null) }
            }.onFailure {
                uplinkRequested = false
                _uiState.update { s -> s.copy(errorMessage = "Erreur micro : ${it.message}") }
            }
        }
    }

    private fun restartUplinkIce(streamId: String) {
        viewModelScope.launch {
            if (streamId != uplinkStreamId) return@launch
            runCatching {
                val sdp = webRTCManager.restartUpstreamIce(streamId) ?: return@launch
                galeneClient.sendOffer(streamId, "camera", sdp, kind = "renegotiate")
            }.onFailure {
                _uiState.update { s -> s.copy(statusText = "Échec ICE restart : ${it.message}") }
            }
        }
    }

    // ─── WebRTC downlink ───────────────────────────────────────────────────

    private fun handleRemoteOffer(event: SignalingEvent.RemoteOffer) {
        _uiState.update { s ->
            val others = s.remoteStreams.filterNot { it.id == event.id || it.id == event.replace }
            s.copy(remoteStreams = others + RemoteStream(event.id, event.source, event.username ?: "?"))
        }
        viewModelScope.launch {
            runCatching {
                val answerSdp = webRTCManager.handleRemoteOffer(event.id, event.sdp, event.replace)
                galeneClient.sendAnswer(event.id, answerSdp)
            }.onFailure {
                _uiState.update { s -> s.copy(errorMessage = "Erreur réception flux : ${it.message}") }
            }
        }
    }

    // ─── Contrôles UI ─────────────────────────────────────────────────────

    /** Coupe / rétablit le micro. */
    fun toggleAudio() {
        val muted = !_uiState.value.audioMuted
        webRTCManager.setLocalAudioEnabled(!muted)
        _uiState.update { it.copy(audioMuted = muted) }
    }

    /** Coupe / rétablit la caméra (arrêt réel de la capture). */
    fun toggleVideo() {
        val muted = !_uiState.value.videoMuted
        webRTCManager.setLocalVideoEnabled(!muted)
        _uiState.update { it.copy(videoMuted = muted) }
    }

    /** L'app n'est plus visible : on coupe la caméra (le son continue grâce au service). */
    fun onAppBackground() {
        val state = _uiState.value
        if (state.sendingVideo && !state.videoMuted) {
            webRTCManager.setLocalVideoEnabled(false)
            videoPausedByBackground = true
        }
    }

    /** Retour au premier plan : on rallume la caméra si c'est nous qui l'avions coupée. */
    fun onAppForeground() {
        if (videoPausedByBackground) {
            videoPausedByBackground = false
            if (!_uiState.value.videoMuted) webRTCManager.setLocalVideoEnabled(true)
        }
    }

    /** Bascule entre caméra avant et arrière. */
    fun switchCamera() {
        webRTCManager.switchCamera { isFront -> _uiState.update { it.copy(frontCamera = isFront) } }
    }

    /** Quitte la salle et libère tout. Idempotent (appelé aussi par [onCleared]). */
    fun leave() {
        galeneClient.disconnect()
        webRTCManager.release()
        audioRouter.stop()
        CallForegroundService.stop(appContext)
    }

    override fun onCleared() {
        super.onCleared()
        leave()
    }
}
