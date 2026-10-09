package fr.antoineassocies.galene.domain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import fr.antoineassocies.galene.domain.protocol.IceCandidateJson
import fr.antoineassocies.galene.domain.protocol.IceServerJson
import fr.antoineassocies.galene.domain.protocol.RtcConfigurationJson
import org.webrtc.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

typealias IceCallback = (streamId: String, candidate: IceCandidateJson) -> Unit

/**
 * Gère toutes les connexions WebRTC d'une session Galène.
 *
 * Galène est un SFU : chaque flux est une PeerConnection distincte avec le serveur.
 * - un flux **montant** ([upStream]) : notre micro et notre caméra, label `camera` ;
 * - un flux **descendant** par média publié par les autres ([downStreams]).
 *
 * Threads : les méthodes publiques sont appelées depuis le thread principal (ViewModel) ;
 * les callbacks de l'Observer WebRTC arrivent sur le thread signaling de libwebrtc,
 * d'où l'usage de StateFlow pour tout ce qui en sort vers l'UI.
 *
 * Cycle de vie : une instance par appel, libérée par [release].
 */
class WebRTCManager(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    eglBase: EglBase,
) {

    companion object {
        private const val TAG = "WebRTCManager"

        // 640×480 : bon compromis qualité / débit mobile, comme le client web en résolution normale
        private const val CAPTURE_WIDTH = 640
        private const val CAPTURE_HEIGHT = 480
        private const val CAPTURE_FPS = 30
    }

    /** Contexte EGL partagé, nécessaire aux renderers vidéo de l'UI. */
    val eglContext: EglBase.Context = eglBase.eglBaseContext

    // ─── Streams actifs ───────────────────────────────────────────────────

    private val downStreams = mutableMapOf<String, DownStream>()
    private var upStream: UpStream? = null

    // Candidats ICE mis en buffer avant que la remote description soit posée
    private val pendingCandidates = mutableMapOf<String, MutableList<IceCandidateJson>>()

    // Streams dont une remote description est en cours d'application. Les candidats reçus
    // pendant ce temps appartiennent à la nouvelle description : ajoutés trop tôt, ils seraient
    // rattachés à l'ancienne (ICE restart, renégociation) ou à aucune, et perdus.
    private val settingRemote = mutableSetOf<String>()

    /** Candidat ICE local à transmettre au serveur. Appelé depuis le thread signaling. */
    var onLocalIceCandidate: IceCallback = { _, _ -> }

    /** ICE en échec sur un stream ; [up] = stream montant. Appelé depuis le thread signaling. */
    var onIceFailed: (streamId: String, up: Boolean) -> Unit = { _, _ -> }

    private var rtcConfig: PeerConnection.RTCConfiguration = defaultRtcConfig()
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturing = false

    // Pistes vidéo exposées à l'UI (les renderers s'y abonnent via addSink)
    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    // Clé = id du stream descendant ; alimenté depuis le thread signaling de WebRTC
    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = _remoteVideoTracks.asStateFlow()

    // ─── Config RTC ────────────────────────────────────────────────────────

    /** Applique les serveurs STUN/TURN reçus dans `joined` ; vaut pour les PeerConnections créées ensuite. */
    fun applyRtcConfiguration(config: RtcConfigurationJson?) {
        config?.iceServers.orEmpty().forEach {
            Log.d(TAG, "iceServer urls=${it.urls} username=${it.username != null} credential=${it.credential != null}")
        }
        rtcConfig = buildRtcConfig(config)
    }

    // ─── Audio local ──────────────────────────────────────────────────────

    /** Crée la piste micro, avec annulation d'écho, réduction de bruit et contrôle de gain. */
    fun createLocalAudioTrack(): AudioTrack {
        val constraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val source = factory.createAudioSource(constraints).also { audioSource = it }
        return factory.createAudioTrack("ARDAMSa0", source).also {
            it.setEnabled(true)
            localAudioTrack = it
        }
    }

    /** Coupe / rétablit le micro (la piste reste négociée, elle envoie du silence). */
    fun setLocalAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    // ─── Vidéo locale ─────────────────────────────────────────────────────

    /** Ouvre la caméra frontale (à défaut, la première trouvée). Null si l'appareil n'a pas de caméra. */
    private fun createLocalVideoTrack(): VideoTrack? {
        val enumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context)
                         else Camera1Enumerator(true)
        val names = enumerator.deviceNames
        val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull() ?: return null
        val capturer = enumerator.createCapturer(name, null) ?: return null

        val helper = SurfaceTextureHelper.create("CaptureThread", eglContext)
        val source = factory.createVideoSource(capturer.isScreencast)
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)

        videoCapturer = capturer
        videoSource = source
        surfaceTextureHelper = helper
        capturing = true

        return factory.createVideoTrack("ARDAMSv0", source).also {
            it.setEnabled(true)
            _localVideoTrack.value = it
        }
    }

    /** Coupe la caméra pour de vrai (voyant éteint), pas seulement des images noires. */
    fun setLocalVideoEnabled(enabled: Boolean) {
        val capturer = videoCapturer ?: return
        _localVideoTrack.value?.setEnabled(enabled)
        if (enabled && !capturing) {
            capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
            capturing = true
        } else if (!enabled && capturing) {
            runCatching { capturer.stopCapture() }
            capturing = false
        }
    }

    /** [onDone] reçoit true si la caméra active est désormais la frontale. Appelé hors thread principal. */
    fun switchCamera(onDone: (isFront: Boolean) -> Unit) {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) = onDone(isFrontCamera)
            override fun onCameraSwitchError(error: String) {
                Log.w(TAG, "switchCamera: $error")
            }
        })
    }

    // ─── Uplink (client → serveur) ────────────────────────────────────────

    /**
     * Crée le flux montant et renvoie l'offre SDP à envoyer au serveur.
     * Les pistes absentes (permission refusée, pas de caméra) ne sont simplement pas ajoutées.
     */
    suspend fun startUpstream(streamId: String, label: String, withAudio: Boolean, withVideo: Boolean): String {
        val pc = createPeerConnection(streamId, up = true) ?: throw IOException("Could not create PeerConnection")

        if (withAudio) {
            val track = localAudioTrack ?: createLocalAudioTrack()
            pc.addTrack(track, listOf(streamId))
        }
        if (withVideo) {
            (_localVideoTrack.value ?: createLocalVideoTrack())?.let { pc.addTrack(it, listOf(streamId)) }
        }

        val offer = pc.createOfferSuspend(MediaConstraints())
        pc.setLocalDescriptionSuspend(offer)

        upStream = UpStream(id = streamId, label = label, pc = pc)
        return offer.description
    }

    /** Réponse à un `renegotiate` de Galène : ICE restart sur le même stream, pas un nouveau stream. */
    suspend fun restartUpstreamIce(streamId: String): String? {
        val stream = upStream?.takeIf { it.id == streamId } ?: return null
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        val offer = stream.pc.createOfferSuspend(constraints)
        stream.pc.setLocalDescriptionSuspend(offer)
        return offer.description
    }

    /** Applique la réponse du serveur à notre offre, puis les candidats ICE arrivés entre-temps. */
    suspend fun handleUpstreamAnswer(streamId: String, sdp: String) {
        val stream = upStream?.takeIf { it.id == streamId } ?: return
        val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        settingRemote += streamId
        try {
            stream.pc.setRemoteDescriptionSuspend(remoteSdp)
        } finally {
            settingRemote -= streamId
        }
        drainPendingCandidates(streamId, stream.pc)
    }

    // ─── Downlink (serveur → client) ──────────────────────────────────────

    /**
     * Traite une offre de flux descendant et renvoie la réponse SDP.
     * Si le stream existe déjà, c'est une renégociation (ex. ICE restart) sur la même PeerConnection.
     */
    suspend fun handleRemoteOffer(
        streamId: String,
        sdp: String,
        replace: String?,
    ): String {
        // Ferme le stream remplacé si présent
        replace?.let { closeDownStream(it) }

        val existing = downStreams[streamId]
        val pc = if (existing != null) {
            existing.pc
        } else {
            createPeerConnection(streamId, up = false) ?: throw IOException("Could not create PeerConnection")
        }
        // Enregistré tout de suite : les candidats arrivant pendant createAnswer doivent trouver la PC
        downStreams[streamId] = DownStream(id = streamId, pc = pc)

        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, sdp)
        settingRemote += streamId
        try {
            pc.setRemoteDescriptionSuspend(remoteSdp)
        } finally {
            settingRemote -= streamId
        }
        drainPendingCandidates(streamId, pc)

        val answer = pc.createAnswerSuspend(MediaConstraints())
        pc.setLocalDescriptionSuspend(answer)
        return answer.description
    }

    /** Ajoute un candidat du serveur, ou le met en attente si la remote description n'est pas encore posée. */
    fun addRemoteIceCandidate(streamId: String, candidate: IceCandidateJson) {
        val iceCandidate = candidate.toIceCandidate()
        val pc = downStreams[streamId]?.pc ?: upStream?.takeIf { it.id == streamId }?.pc

        if (pc != null && streamId !in settingRemote && pc.remoteDescription != null) {
            pc.addIceCandidate(iceCandidate)
        } else {
            pendingCandidates.getOrPut(streamId) { mutableListOf() }.add(candidate)
        }
    }

    // ─── Fermeture de streams ─────────────────────────────────────────────

    /** Ferme un flux descendant ; l'UI est prévenue avant la libération de la piste. */
    fun closeDownStream(streamId: String) {
        _remoteVideoTracks.update { it - streamId }
        downStreams.remove(streamId)?.pc?.dispose()
        pendingCandidates.remove(streamId)
    }

    /** Ferme le flux montant (les pistes locales restent ouvertes, réutilisables). */
    fun closeUpStream() {
        upStream?.let { pendingCandidates.remove(it.id) }
        upStream?.pc?.dispose()
        upStream = null
    }

    /** Libère toutes les ressources natives : PeerConnections, pistes, caméra. Fin d'appel. */
    fun release() {
        _remoteVideoTracks.value = emptyMap()
        downStreams.values.forEach { it.pc.dispose() }
        downStreams.clear()
        closeUpStream()
        localAudioTrack?.dispose()
        audioSource?.dispose()
        localAudioTrack = null
        audioSource = null

        val videoTrack = _localVideoTrack.value
        _localVideoTrack.value = null
        if (capturing) runCatching { videoCapturer?.stopCapture() }
        capturing = false
        videoCapturer?.dispose()
        videoTrack?.dispose()
        videoSource?.dispose()
        surfaceTextureHelper?.dispose()
        videoCapturer = null
        videoSource = null
        surfaceTextureHelper = null
    }

    // ─── Création PeerConnection ──────────────────────────────────────────

    private fun createPeerConnection(streamId: String, up: Boolean): PeerConnection? =
        factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "[$streamId] candidat local: ${candidate.sdp}")
                onLocalIceCandidate(streamId, candidate.toJson())
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track() ?: return
                track.setEnabled(true)
                if (track is VideoTrack) {
                    Log.d(TAG, "[$streamId] piste vidéo distante reçue")
                    _remoteVideoTracks.update { it + (streamId to track) }
                }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "[$streamId] connexion: $state")
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "[$streamId] ICE: $state")
                if (state == PeerConnection.IceConnectionState.FAILED) onIceFailed(streamId, up)
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "[$streamId] collecte ICE: $state")
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {}
        })

    // ─── Candidates buffered ──────────────────────────────────────────────

    private fun drainPendingCandidates(streamId: String, pc: PeerConnection) {
        pendingCandidates.remove(streamId)?.forEach { c ->
            pc.addIceCandidate(c.toIceCandidate())
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun IceCandidate.toJson() = IceCandidateJson(
        candidate = sdp,
        sdpMid = sdpMid,
        sdpMLineIndex = sdpMLineIndex,
    )

    private fun IceCandidateJson.toIceCandidate() = IceCandidate(
        sdpMid ?: "",
        sdpMLineIndex ?: 0,
        candidate,
    )

    private fun buildRtcConfig(cfg: RtcConfigurationJson?): PeerConnection.RTCConfiguration {
        val servers = cfg?.iceServers?.map { it.toPeerConnectionIceServer() } ?: emptyList()
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Un seul transport pour audio + vidéo : moitié moins de ports à traverser
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
    }

    private fun defaultRtcConfig() = PeerConnection.RTCConfiguration(emptyList()).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }

    private fun IceServerJson.toPeerConnectionIceServer(): PeerConnection.IceServer =
        PeerConnection.IceServer.builder(urls)
            .apply {
                username?.let { setUsername(it) }
                credential?.let { setPassword(it) }
            }
            .createIceServer()

    // ─── Données internes ────────────────────────────────────────────────

    private data class DownStream(val id: String, val pc: PeerConnection)
    private data class UpStream(val id: String, val label: String, val pc: PeerConnection)
}

// ─── Extensions coroutines pour SDP ──────────────────────────────────────────

private suspend fun PeerConnection.createOfferSuspend(constraints: MediaConstraints): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(err: String) = cont.resumeWithException(IOException("createOffer: $err"))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(err: String) = Unit
        }, constraints)
    }

private suspend fun PeerConnection.createAnswerSuspend(constraints: MediaConstraints): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(err: String) = cont.resumeWithException(IOException("createAnswer: $err"))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(err: String) = Unit
        }, constraints)
    }

private suspend fun PeerConnection.setLocalDescriptionSuspend(sdp: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = Unit
            override fun onCreateFailure(err: String) = Unit
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(err: String) = cont.resumeWithException(IOException("setLocalDesc: $err"))
        }, sdp)
    }

private suspend fun PeerConnection.setRemoteDescriptionSuspend(sdp: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = Unit
            override fun onCreateFailure(err: String) = Unit
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(err: String) = cont.resumeWithException(IOException("setRemoteDesc: $err"))
        }, sdp)
    }


