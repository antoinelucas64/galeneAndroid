package fr.antoineassocies.galene.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import fr.antoineassocies.galene.R
import fr.antoineassocies.galene.domain.AudioEnergy
import fr.antoineassocies.galene.domain.AudioRoute
import fr.antoineassocies.galene.domain.AudioRouter
import fr.antoineassocies.galene.domain.ConnectionState
import fr.antoineassocies.galene.domain.GaleneClient
import fr.antoineassocies.galene.domain.NetworkMonitor
import fr.antoineassocies.galene.domain.SignalingEvent
import fr.antoineassocies.galene.domain.WebRTCManager
import fr.antoineassocies.galene.domain.asText
import fr.antoineassocies.galene.domain.model.ChatMessage
import fr.antoineassocies.galene.domain.model.JoinRequest
import fr.antoineassocies.galene.domain.model.Participant
import fr.antoineassocies.galene.service.CallForegroundService
import fr.antoineassocies.galene.ui.UiText
import fr.antoineassocies.galene.ui.toUiText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
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
    val statusText: String = "",            // signaling trace, shown in debug builds only
    /** Connection lost after joining: the session is being rebuilt. */
    val reconnecting: Boolean = false,
    val audioRoute: AudioRoute = AudioRoute.SPEAKER,
    val audioRoutes: List<AudioRoute> = emptyList(),
    val chat: List<ChatMessage> = emptyList(),
    val chatOpen: Boolean = false,
    /** Messages received while the chat panel was closed. */
    val unreadChat: Int = 0,
    /** Last message received with the chat closed, shown briefly over the videos. */
    val chatPreview: ChatMessage? = null,
    /** Short-lived information (e.g. an operator muted us), shown as a snackbar. */
    val notice: UiText? = null,
    /** Participant ids of the people speaking right now ([selfId] included). */
    val speaking: Set<String> = emptySet(),
    /** Other participant shown large (speaker layout, Picture-in-Picture): the loudest recent speaker. */
    val activeSpeaker: String? = null,
    /** Participant the chat sends to privately; null = the whole group. */
    val chatRecipient: String? = null,
    /** We have the `op` permission: operator actions on the others are offered. */
    val isOperator: Boolean = false,
    /** Mobile data (metered network): the others' video is received in low quality. */
    val dataSaver: Boolean = false,
) {
    val handRaised: Boolean get() = participants.any { it.id == selfId && it.raisedHand }
}

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
 * Connection lost once joined (network change, server restart): the session — WebSocket
 * and PeerConnections — is rebuilt from step 1, keeping the microphone and camera open.
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
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        /** Beyond this, reconnection is abandoned and the call ends with an error. */
        const val RECONNECT_GIVE_UP_MS = 120_000L
        val RECONNECT_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
        const val CHAT_PREVIEW_MS = 4_000L
        /** Oldest messages are dropped beyond this, to bound memory on long calls. */
        const val CHAT_MAX = 500

        // Voice activity, as in the web client: level above 0.2 → speaking, for at least 0.7 s
        const val ACTIVITY_INTERVAL_MS = 250L
        const val ACTIVITY_HOLD_MS = 700L
        const val ACTIVITY_THRESHOLD = 0.2 * 0.2   // energy is the integral of the squared level
        /** The active speaker (big tile, PiP) stays at least this long, so the layout does not jump. */
        const val ACTIVE_SPEAKER_MIN_MS = 2_000L
    }

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

    // Coroutines bound to one WebSocket session (SDP exchanges): cancelled when it is torn down,
    // so that none of them touches a disposed PeerConnection
    private var session = SupervisorJob(viewModelScope.coroutineContext[Job])

    private var everJoined = false
    private var leaving = false
    private var reconnectJob: Job? = null
    private val networkChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var chatPreviewJob: Job? = null

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
        observeAudioRoutes()
        observeNetwork()
        observeMeteredNetwork()
        detectVoiceActivity()
        galeneClient.connect(request.endpoint)
        startConnectionTimeout()
    }

    private fun startConnectionTimeout() {
        viewModelScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!everJoined && _uiState.value.connectionState == ConnectionState.Connecting) {
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
                        _uiState.update {
                            it.copy(selfId = galeneClient.clientId, statusText = "Handshake OK → join ${request.group}…")
                        }
                        galeneClient.sendJoin(request.group, request.username, request.password, request.token)
                    }

                    is SignalingEvent.Joined -> {
                        _uiState.update {
                            it.copy(
                                statusText = "Joined · ${event.permissions.joinToString()}",
                                isOperator = "op" in event.permissions,
                            )
                        }
                        selfPermissions = event.permissions
                        // The server replays the history after each join: start from an empty chat
                        if (!event.change) _uiState.update { it.copy(chat = emptyList(), unreadChat = 0) }
                        webRTCManager.applyRtcConfiguration(event.rtcConfiguration)
                        audioRouter.start()
                        _uiState.update { it.copy(selfUsername = event.username) }

                        joined = true
                        sendMediaRequest()
                        everJoined = true
                        _uiState.update { it.copy(reconnecting = false) }
                        maybeStartUplink()
                    }

                    is SignalingEvent.JoinFailed -> {
                        _uiState.update { it.copy(joinFailure = event) }
                    }

                    is SignalingEvent.JoinLeft -> {
                        // Removed by the server (kicked, group closed): no reconnection
                        leaving = true
                        _uiState.update {
                            it.copy(participants = emptyList(), errorMessage = UiText.Res(R.string.error_left_group))
                        }
                    }

                    is SignalingEvent.UserUpdate -> handleUserUpdate(event)

                    is SignalingEvent.Chat -> addChatMessage(event.message)

                    is SignalingEvent.UserMessage -> handleUserMessage(event)

                    is SignalingEvent.RemoteOffer -> handleRemoteOffer(event)

                    is SignalingEvent.RemoteAnswer -> {
                        // launch on Main.immediate: settingRemote is set before the next ICE event is handled
                        viewModelScope.launch(session) {
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
                        _uiState.update { it.copy(statusText = "Disconnected: ${event.reason}") }
                        when {
                            leaving -> Unit
                            everJoined -> reconnect()
                            else -> _uiState.update {
                                it.copy(errorMessage = UiText.Res(R.string.error_connection_lost, event.reason))
                            }
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

    // ─── Reconnection ──────────────────────────────────────────────────────

    /**
     * Rebuilds the session after a connection loss: new WebSocket, new join, streams
     * renegotiated from scratch. Retries with growing delays, and at once when a network
     * comes back. Does nothing if a reconnection is already under way.
     */
    private fun reconnect() {
        if (leaving || reconnectJob?.isActive == true) return
        tearDownSession()
        _uiState.update {
            it.copy(
                reconnecting = true, participants = emptyList(), remoteStreams = emptyList(),
                speaking = emptySet(), activeSpeaker = null, chatRecipient = null,
            )
        }

        reconnectJob = viewModelScope.launch {
            val start = System.currentTimeMillis()
            var attempt = 0
            while (System.currentTimeMillis() - start < RECONNECT_GIVE_UP_MS) {
                _uiState.update { it.copy(statusText = "Reconnecting (${attempt + 1})…") }
                galeneClient.connect(request.endpoint)
                val state = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    galeneClient.connectionState.first { it != ConnectionState.Connecting }
                }
                // Connected: the usual handshake → join → publish sequence takes over
                if (state == ConnectionState.Connected) return@launch
                galeneClient.disconnect()
                val wait = RECONNECT_DELAYS_MS[attempt.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
                attempt++
                withTimeoutOrNull(wait) { networkChanged.first() }
            }
            _uiState.update {
                it.copy(reconnecting = false, errorMessage = UiText.Res(R.string.error_reconnect_failed))
            }
        }
    }

    /** Forgets everything tied to the lost session; local microphone and camera stay open. */
    private fun tearDownSession() {
        session.cancel()
        session = SupervisorJob(viewModelScope.coroutineContext[Job])
        webRTCManager.closeAllStreams()
        joined = false
        uplinkRequested = false
        uplinkStreamId = null
    }

    /**
     * A new default network (Wi-Fi ↔ mobile data): the current WebSocket is bound to the
     * old one, so rebuild now rather than wait for it to time out.
     */
    private fun observeNetwork() {
        viewModelScope.launch {
            var previous: android.net.Network? = null
            networkMonitor.defaultNetwork.collect { network ->
                val changed = previous != null && network != null && network != previous
                if (network != null) previous = network
                if (network == null || leaving || !everJoined) return@collect
                if (reconnectJob?.isActive == true) {
                    networkChanged.tryEmit(Unit)
                } else if (changed) {
                    galeneClient.disconnect()
                    reconnect()
                }
            }
        }
    }

    /**
     * Mobile data: ask the server for the others' low-quality video (`video-low`, the small
     * layer of senders that publish several), as the web client's "low" setting does.
     * Asked again whenever the network switches between Wi-Fi and mobile data.
     */
    private fun observeMeteredNetwork() {
        viewModelScope.launch {
            networkMonitor.metered.collect { metered ->
                _uiState.update { it.copy(dataSaver = metered) }
                if (joined) sendMediaRequest()
            }
        }
    }

    /** Declares what we want to receive from the others. */
    private fun sendMediaRequest() {
        val video = if (_uiState.value.dataSaver) "video-low" else "video"
        galeneClient.sendRequest(mapOf("" to listOf("audio", video)))
    }

    // ─── Audio output ──────────────────────────────────────────────────────

    private fun observeAudioRoutes() {
        viewModelScope.launch {
            audioRouter.current.collect { route -> _uiState.update { it.copy(audioRoute = route) } }
        }
        viewModelScope.launch {
            audioRouter.available.collect { routes -> _uiState.update { it.copy(audioRoutes = routes) } }
        }
    }

    /** The user picks where the sound goes. */
    fun selectAudioRoute(route: AudioRoute) = audioRouter.select(route)

    // ─── Chat ──────────────────────────────────────────────────────────────

    fun openChat() {
        chatPreviewJob?.cancel()
        _uiState.update { it.copy(chatOpen = true, unreadChat = 0, chatPreview = null) }
    }

    fun closeChat() = _uiState.update { it.copy(chatOpen = false) }

    /**
     * Sends to the whole group, or privately to [RoomUiState.chatRecipient];
     * `/me does something` is sent as an action.
     */
    fun sendChat(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val action = trimmed.removePrefix("/me ").takeIf { trimmed.startsWith("/me ") }?.trim()
        val state = _uiState.value
        val recipient = state.participants.firstOrNull { it.id == state.chatRecipient }
        // The recipient just left: never fall back to sending a private message to everyone
        if (state.chatRecipient != null && recipient == null) return
        galeneClient.sendChat(action ?: trimmed, me = action != null, dest = recipient?.id)
        // The server does not echo private messages: show our copy ourselves
        if (recipient != null) {
            addChatMessage(
                ChatMessage(
                    id = null, source = galeneClient.clientId, username = state.selfUsername,
                    text = action ?: trimmed, time = System.currentTimeMillis(),
                    kind = if (action != null) ChatMessage.Kind.ME else ChatMessage.Kind.NORMAL,
                    isPrivate = true, to = recipient.username,
                )
            )
        }
    }

    /** Chooses who the chat sends to: a participant id, or null for everyone. */
    fun selectChatRecipient(id: String?) = _uiState.update { it.copy(chatRecipient = id) }

    fun dismissNotice() = _uiState.update { it.copy(notice = null) }

    private fun addChatMessage(message: ChatMessage) {
        // Our own messages come back from the server: no notification for those
        val notify = !message.history && message.source != galeneClient.clientId && !_uiState.value.chatOpen
        _uiState.update {
            it.copy(
                chat = (it.chat + message).takeLast(CHAT_MAX),
                unreadChat = if (notify) it.unreadChat + 1 else it.unreadChat,
                chatPreview = if (notify) message else it.chatPreview,
            )
        }
        if (notify) {
            chatPreviewJob?.cancel()
            chatPreviewJob = viewModelScope.launch {
                delay(CHAT_PREVIEW_MS)
                _uiState.update { it.copy(chatPreview = null) }
            }
        }
    }

    /** Like the web client, only operators and the server may notify, mute or clear the chat. */
    private fun handleUserMessage(event: SignalingEvent.UserMessage) {
        if (!event.privileged) return
        when (event.kind) {
            "error", "warning", "info", "kicked" -> addChatMessage(
                ChatMessage(
                    id = null, source = null, username = event.username, text = event.value.asText(),
                    time = System.currentTimeMillis(), kind = ChatMessage.Kind.NOTICE, privileged = true,
                )
            )
            "mute" -> {
                val state = _uiState.value
                if (state.sendingAudio && !state.audioMuted) toggleAudio()
                _uiState.update {
                    it.copy(notice = UiText.Res(R.string.chat_muted_by, event.username ?: UiText.Res(R.string.chat_server)))
                }
            }
            "clearchat" -> clearChat(event.value)
        }
    }

    /** No target: everything; otherwise one message (`id`) or one user's messages (`userId`). */
    private fun clearChat(value: JsonElement?) {
        val target = value as? JsonObject
        val id = target?.get("id")?.asText()?.ifEmpty { null }
        val userId = target?.get("userId")?.asText()?.ifEmpty { null }
        _uiState.update { s ->
            val kept = if (id == null && userId == null) emptyList()
                       else s.chat.filterNot { (id != null && it.id == id) || (userId != null && it.source == userId) }
            s.copy(chat = kept, chatPreview = s.chatPreview?.takeIf { it in kept })
        }
    }

    // ─── Participants ──────────────────────────────────────────────────────

    private fun handleUserUpdate(event: SignalingEvent.UserUpdate) {
        val raised = event.data?.get("raisehand").isTruthy()
        val before = _uiState.value.participants.firstOrNull { it.id == event.id }
        _uiState.update { state ->
            val participants = state.participants.toMutableList()
            when (event.kind) {
                "add" -> participants.add(
                    Participant(
                        id = event.id,
                        username = event.username ?: event.id,
                        permissions = event.permissions ?: emptyList(),
                        raisedHand = raised,
                    )
                )
                "change" -> {
                    val idx = participants.indexOfFirst { it.id == event.id }
                    if (idx >= 0) {
                        participants[idx] = participants[idx].copy(
                            username = event.username ?: participants[idx].username,
                            permissions = event.permissions ?: participants[idx].permissions,
                            raisedHand = raised,
                        )
                    }
                }
                "delete" -> participants.removeAll { it.id == event.id }
            }
            state.copy(
                participants = participants,
                chatRecipient = state.chatRecipient.takeIf { id -> participants.any { it.id == id } },
            )
        }
        // Someone else just raised their hand (not the hands already up when we joined)
        if (event.kind == "change" && raised && before?.raisedHand == false && event.id != galeneClient.clientId) {
            _uiState.update { it.copy(notice = UiText.Res(R.string.notice_hand_raised, before.username)) }
        }
    }

    /** Raises or lowers our hand; the server's `user` change updates the state. */
    fun toggleRaisedHand() = galeneClient.setRaisedHand(!_uiState.value.handRaised)

    // ─── Operator actions (refused by the server without the `op` permission) ──

    /** Asks [id] to mute their microphone; their client obeys, as ours does. */
    fun muteParticipant(id: String) = galeneClient.sendUserMessage("mute", id)

    /** Removes [id] from the group. */
    fun kickParticipant(id: String) = galeneClient.sendUserAction("kick", id)

    /** Gives or takes away the right to publish audio / video. */
    fun setParticipantPresent(id: String, allowed: Boolean) =
        galeneClient.sendUserAction(if (allowed) "present" else "unpresent", id)

    /** Gives or takes away the operator rights. */
    fun setParticipantOperator(id: String, operator: Boolean) =
        galeneClient.sendUserAction(if (operator) "op" else "unop", id)

    /** Opens the chat on a private conversation with [id]. */
    fun messageParticipant(id: String) {
        selectChatRecipient(id)
        openChat()
    }

    // ─── Voice activity ────────────────────────────────────────────────────

    /**
     * Who is speaking, from the audio energy received on each stream (and sent, for us),
     * every [ACTIVITY_INTERVAL_MS]: a speaker stays "speaking" [ACTIVITY_HOLD_MS] after the
     * last loud sample, so that the indicator does not blink between words.
     */
    private fun detectVoiceActivity() {
        viewModelScope.launch {
            var previous = emptyMap<String, AudioEnergy>()
            val lastLoud = mutableMapOf<String, Long>()
            var activeSince = 0L
            while (true) {
                delay(ACTIVITY_INTERVAL_MS)
                if (!joined) {
                    previous = emptyMap()
                    continue
                }
                val current = webRTCManager.audioEnergies()
                val now = System.currentTimeMillis()
                // Mean power over the last interval, per stream
                val power = mutableMapOf<String, Double>()
                current.forEach { (id, sample) ->
                    val old = previous[id] ?: return@forEach
                    val seconds = (sample.timestampUs - old.timestampUs) / 1e6
                    if (seconds <= 0) return@forEach
                    power[id] = (sample.total - old.total) / seconds
                    if (power.getValue(id) > ACTIVITY_THRESHOLD) lastLoud[id] = now
                }
                previous = current
                lastLoud.keys.retainAll(current.keys)

                val state = _uiState.value
                fun participantOf(streamId: String): String? =
                    if (streamId == uplinkStreamId) state.selfId.takeIf { !state.audioMuted }
                    else state.remoteStreams.firstOrNull { it.id == streamId }?.sourceId
                val speakingStreams = lastLoud.filterValues { now - it < ACTIVITY_HOLD_MS }.keys
                val speaking = speakingStreams.mapNotNull(::participantOf).toSet()

                // Active speaker: kept while still speaking and for a minimum time; otherwise
                // the loudest of the others speaking right now
                val previousActive = state.activeSpeaker?.takeIf { id -> state.participants.any { it.id == id } }
                val keep = previousActive != null && (previousActive in speaking || now - activeSince < ACTIVE_SPEAKER_MIN_MS)
                val loudest = speakingStreams
                    .mapNotNull { id -> participantOf(id)?.takeIf { it != state.selfId }?.let { it to (power[id] ?: 0.0) } }
                    .maxByOrNull { it.second }?.first
                val active = if (keep) previousActive else loudest ?: previousActive
                if (active != state.activeSpeaker) activeSince = now
                if (speaking != state.speaking || active != state.activeSpeaker) {
                    _uiState.update { it.copy(speaking = speaking, activeSpeaker = active) }
                }
            }
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
        viewModelScope.launch(session) {
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
        viewModelScope.launch(session) {
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
        viewModelScope.launch(session) {
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
        leaving = true
        reconnectJob?.cancel()
        session.cancel()
        galeneClient.disconnect()
        webRTCManager.release()
        audioRouter.stop()
        CallForegroundService.stop(appContext)
    }

    /** A JSON value counts as set like in JavaScript: anything but absent, null, false, 0 or "". */
    private fun JsonElement?.isTruthy(): Boolean {
        val p = this as? JsonPrimitive ?: return this != null && this !is JsonNull
        if (p is JsonNull) return false
        return p.booleanOrNull ?: p.doubleOrNull?.let { it != 0.0 } ?: p.content.isNotEmpty()
    }

    override fun onCleared() {
        super.onCleared()
        leave()
    }
}
