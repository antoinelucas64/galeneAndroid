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
import fr.antoineassocies.galene.R
import fr.antoineassocies.galene.domain.AudioRouter
import fr.antoineassocies.galene.domain.ConnectionState
import fr.antoineassocies.galene.domain.GaleneClient
import fr.antoineassocies.galene.domain.SignalingEvent
import fr.antoineassocies.galene.domain.WebRTCManager
import fr.antoineassocies.galene.domain.model.JoinRequest
import fr.antoineassocies.galene.domain.model.Participant
import fr.antoineassocies.galene.service.CallForegroundService
import fr.antoineassocies.galene.ui.UiText
import fr.antoineassocies.galene.ui.toUiText
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.util.UUID

/** Down stream received from the server (one per media published by another participant). */
data class RemoteStream(
    val id: String,
    val sourceId: String?,
    val username: String,
)

/** Complete state of the room screen, observed by [fr.antoineassocies.galene.ui.screens.RoomScreen]. */
data class RoomUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val audioMuted: Boolean = false,
    val videoMuted: Boolean = false,
    val participants: List<Participant> = emptyList(),
    val remoteStreams: List<RemoteStream> = emptyList(),
    val selfId: String = "",
    val sendingAudio: Boolean = false,      // the microphone is published
    val sendingVideo: Boolean = false,      // a camera is open and published
    val frontCamera: Boolean = true,        // to mirror the local preview
    val selfUsername: String = "",
    val errorMessage: UiText? = null,
    /** Join refused by the server: the screen goes back to the login form. */
    val joinFailure: SignalingEvent.JoinFailed? = null,
    val statusText: String = "",            // debug line shown in the UI
)

/**
 * Conductor of a call: ties together signaling ([GaleneClient]), media ([WebRTCManager])
 * and audio routing ([AudioRouter]), and exposes the state to the UI.
 *
 * Course of a call:
 * 1. `init`: WebSocket connection;
 * 2. handshake → `join`; `joined` → ICE configuration, request for the streams to receive;
 * 3. once joined AND permissions answered → the up stream is published;
 * 4. each server `offer` → a down stream shown in the grid;
 * 5. [leave] (or ViewModel destruction) → everything is released.
 *
 * The ViewModel lives as long as the call: it survives screen rotations and going
 * to the background (covered by [CallForegroundService]).
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

    // Exposed as-is for the UI's video renderers
    val eglContext: EglBase.Context get() = webRTCManager.eglContext
    val localVideoTrack: StateFlow<VideoTrack?> = webRTCManager.localVideoTrack
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = webRTCManager.remoteVideoTracks

    // Kept locally to know whether we are allowed to publish
    private var selfPermissions: List<String> = emptyList()

    // ID of the active up stream
    private var uplinkStreamId: String? = null

    // The uplink starts only once joined AND permissions have been answered, in whichever
    // order these arrive (`joined` often comes before the answer to the permission dialog)
    private var joined = false
    private var permissionsAnswered = false
    private var micGranted = false
    private var cameraGranted = false
    private var uplinkRequested = false

    // Camera turned off by going to the background (not by the user) → turn back on when returning
    private var videoPausedByBackground = false

    init {
        _uiState.update { it.copy(statusText = "Connecting to ${request.endpoint}…") }
        observeSignaling()
        observeConnectionState()
        webRTCManager.onLocalIceCandidate = { streamId, candidate ->
            galeneClient.sendIce(streamId, candidate)
        }
        // Same strategy as the web client: restart without waiting for the server's renegotiate
        webRTCManager.onIceFailed = { streamId, up ->
            if (up) restartUplinkIce(streamId) else galeneClient.sendRenegotiate(streamId)
        }
        galeneClient.connect(request.endpoint)
        startConnectionTimeout()
    }

    private fun startConnectionTimeout() {
        viewModelScope.launch {
            delay(15_000)
            if (_uiState.value.connectionState == ConnectionState.Connecting) {
                _uiState.update {
                    it.copy(
                        statusText = "No answer after 15 s",
                        errorMessage = UiText.Res(R.string.error_timeout, request.endpoint),
                    )
                }
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
                        _uiState.update { it.copy(statusText = "Handshake OK → join ${request.group}…") }
                        galeneClient.sendJoin(request.group, request.username, request.password, request.token)
                    }

                    is SignalingEvent.Joined -> {
                        _uiState.update { it.copy(statusText = "Joined · ${event.permissions.joinToString()}") }
                        selfPermissions = event.permissions
                        webRTCManager.applyRtcConfiguration(event.rtcConfiguration)
                        audioRouter.start()
                        _uiState.update { it.copy(selfUsername = event.username) }

                        // Declare what we want to receive
                        galeneClient.sendRequest(mapOf("" to listOf("audio", "video")))

                        joined = true
                        maybeStartUplink()
                    }

                    is SignalingEvent.JoinFailed -> {
                        _uiState.update { it.copy(joinFailure = event) }
                    }

                    is SignalingEvent.JoinLeft -> {
                        _uiState.update { it.copy(participants = emptyList()) }
                    }

                    is SignalingEvent.UserUpdate -> handleUserUpdate(event)

                    is SignalingEvent.RemoteOffer -> handleRemoteOffer(event)

                    is SignalingEvent.RemoteAnswer -> {
                        // launch on Main.immediate: settingRemote is set before the next ICE event is handled
                        viewModelScope.launch {
                            runCatching { webRTCManager.handleUpstreamAnswer(event.id, event.sdp) }
                                .onFailure { _uiState.update { s -> s.copy(statusText = "Answer rejected: ${it.message}") } }
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
                        // Galène reports an ICE failure on this stream → ICE restart
                        restartUplinkIce(event.id)
                    }

                    is SignalingEvent.Disconnected -> {
                        _uiState.update {
                            it.copy(
                                statusText = "Disconnected: ${event.reason ?: "closed"}",
                                errorMessage = event.reason?.let { r -> UiText.Res(R.string.error_connection_lost, r) }
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

    // ─── Participants ──────────────────────────────────────────────────────

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
     * The user's answer to the permission request. Starts the foreground service if the
     * microphone is allowed, and publishing as soon as we are joined.
     */
    fun onPermissionsResult(mic: Boolean, camera: Boolean) {
        permissionsAnswered = true
        // Only publish what the user chose in the login form, even if more is allowed
        micGranted = mic && request.present.usesMic
        cameraGranted = camera && request.present.usesCamera
        val refused = listOfNotNull(
            "microphone".takeIf { request.present.usesMic && !mic },
            "camera".takeIf { request.present.usesCamera && !camera },
        )
        if (refused.isNotEmpty()) {
            _uiState.update { it.copy(statusText = "Denied: ${refused.joinToString()}") }
        }
        // The microphone is allowed and the app is visible: the only moment Android 14
        // accepts starting a "microphone" foreground service
        if (micGranted) CallForegroundService.start(appContext)
        maybeStartUplink()
    }

    private fun maybeStartUplink() {
        if (!joined || !permissionsAnswered || "present" !in selfPermissions || uplinkRequested) return
        if (!micGranted && !cameraGranted) return   // nothing to publish → listen only
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
                _uiState.update {
                    it.copy(sendingAudio = micGranted, sendingVideo = webRTCManager.localVideoTrack.value != null)
                }
            }.onFailure {
                uplinkRequested = false
                _uiState.update { s -> s.copy(errorMessage = UiText.Res(R.string.error_microphone, it.toUiText())) }
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
                _uiState.update { s -> s.copy(statusText = "ICE restart failed: ${it.message}") }
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
                _uiState.update { s -> s.copy(errorMessage = UiText.Res(R.string.error_downstream, it.toUiText())) }
            }
        }
    }

    // ─── UI controls ──────────────────────────────────────────────────────

    /** Mutes / unmutes the microphone. */
    fun toggleAudio() {
        val muted = !_uiState.value.audioMuted
        webRTCManager.setLocalAudioEnabled(!muted)
        _uiState.update { it.copy(audioMuted = muted) }
    }

    /** Turns the camera off / on (capture really stops). */
    fun toggleVideo() {
        val muted = !_uiState.value.videoMuted
        webRTCManager.setLocalVideoEnabled(!muted)
        _uiState.update { it.copy(videoMuted = muted) }
    }

    /** The app is no longer visible: turn the camera off (sound goes on thanks to the service). */
    fun onAppBackground() {
        val state = _uiState.value
        if (state.sendingVideo && !state.videoMuted) {
            webRTCManager.setLocalVideoEnabled(false)
            videoPausedByBackground = true
        }
    }

    /** Back to the foreground: turn the camera back on if we were the ones who turned it off. */
    fun onAppForeground() {
        if (videoPausedByBackground) {
            videoPausedByBackground = false
            if (!_uiState.value.videoMuted) webRTCManager.setLocalVideoEnabled(true)
        }
    }

    /** Switches between front and back cameras. */
    fun switchCamera() {
        webRTCManager.switchCamera { isFront -> _uiState.update { it.copy(frontCamera = isFront) } }
    }

    /** Leaves the room and releases everything. Idempotent (also called by [onCleared]). */
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
