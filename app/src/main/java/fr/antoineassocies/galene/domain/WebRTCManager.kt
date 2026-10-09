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
 * Manages every WebRTC connection of a Galène session.
 *
 * Galène is an SFU: each stream is a separate PeerConnection with the server.
 * - one **up** stream ([upStream]): our microphone and camera, label `camera`;
 * - one **down** stream per media published by the others ([downStreams]).
 *
 * Threads: public methods are called from the main thread (ViewModel); WebRTC Observer
 * callbacks arrive on libwebrtc's signaling thread, hence StateFlow for everything
 * that flows out to the UI.
 *
 * Lifecycle: one instance per call, released by [release].
 */
class WebRTCManager(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    eglBase: EglBase,
) {

    companion object {
        private const val TAG = "WebRTCManager"

        // 640×480: good quality / mobile bandwidth trade-off, like the web client's normal resolution
        private const val CAPTURE_WIDTH = 640
        private const val CAPTURE_HEIGHT = 480
        private const val CAPTURE_FPS = 30
    }

    /** Shared EGL context, needed by the UI's video renderers. */
    val eglContext: EglBase.Context = eglBase.eglBaseContext

    // ─── Active streams ───────────────────────────────────────────────────

    private val downStreams = mutableMapOf<String, DownStream>()
    private var upStream: UpStream? = null

    // ICE candidates buffered until the remote description is set
    private val pendingCandidates = mutableMapOf<String, MutableList<IceCandidateJson>>()

    // Streams whose remote description is being applied. Candidates received meanwhile belong
    // to the new description: added too early, they would be attached to the old one (ICE
    // restart, renegotiation) or to none at all, and lost.
    private val settingRemote = mutableSetOf<String>()

    /** Local ICE candidate to forward to the server. Called from the signaling thread. */
    var onLocalIceCandidate: IceCallback = { _, _ -> }

    /** ICE failed on a stream; [up] = up stream. Called from the signaling thread. */
    var onIceFailed: (streamId: String, up: Boolean) -> Unit = { _, _ -> }

    private var rtcConfig: PeerConnection.RTCConfiguration = defaultRtcConfig()
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturing = false

    // Video tracks exposed to the UI (renderers subscribe through addSink)
    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    // Key = down stream id; fed from WebRTC's signaling thread
    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = _remoteVideoTracks.asStateFlow()

    // ─── Config RTC ────────────────────────────────────────────────────────

    /** Applies the STUN/TURN servers received in `joined`; affects PeerConnections created afterwards. */
    fun applyRtcConfiguration(config: RtcConfigurationJson?) {
        config?.iceServers.orEmpty().forEach {
            Log.d(TAG, "iceServer urls=${it.urls} username=${it.username != null} credential=${it.credential != null}")
        }
        rtcConfig = buildRtcConfig(config)
    }

    // ─── Local audio ──────────────────────────────────────────────────────

    /** Creates the microphone track, with echo cancellation, noise suppression and gain control. */
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

    /** Mutes / unmutes the microphone (the track stays negotiated and sends silence). */
    fun setLocalAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    // ─── Local video ──────────────────────────────────────────────────────

    /** Opens the front camera (or the first one found). Null if the device has no camera. */
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

    /** Really turns the camera off (indicator light off), rather than sending black frames. */
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

    /** [onDone] receives true if the active camera is now the front one. Called off the main thread. */
    fun switchCamera(onDone: (isFront: Boolean) -> Unit) {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) = onDone(isFrontCamera)
            override fun onCameraSwitchError(error: String) {
                Log.w(TAG, "switchCamera: $error")
            }
        })
    }

    // ─── Uplink (client → server) ─────────────────────────────────────────

    /**
     * Creates the up stream and returns the SDP offer to send to the server.
     * Missing tracks (permission denied, no camera) are simply not added.
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

    /** Answer to Galène's `renegotiate`: ICE restart on the same stream, not a new stream. */
    suspend fun restartUpstreamIce(streamId: String): String? {
        val stream = upStream?.takeIf { it.id == streamId } ?: return null
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        val offer = stream.pc.createOfferSuspend(constraints)
        stream.pc.setLocalDescriptionSuspend(offer)
        return offer.description
    }

    /** Applies the server's answer to our offer, then the ICE candidates received meanwhile. */
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

    // ─── Downlink (server → client) ───────────────────────────────────────

    /**
     * Handles a down stream offer and returns the SDP answer.
     * If the stream already exists, this is a renegotiation (e.g. ICE restart) on the same PeerConnection.
     */
    suspend fun handleRemoteOffer(
        streamId: String,
        sdp: String,
        replace: String?,
    ): String {
        // Close the replaced stream, if any
        replace?.let { closeDownStream(it) }

        val existing = downStreams[streamId]
        val pc = if (existing != null) {
            existing.pc
        } else {
            createPeerConnection(streamId, up = false) ?: throw IOException("Could not create PeerConnection")
        }
        // Registered right away: candidates arriving during createAnswer must find the PC
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

    /** Adds a server candidate, or buffers it if the remote description is not set yet. */
    fun addRemoteIceCandidate(streamId: String, candidate: IceCandidateJson) {
        val iceCandidate = candidate.toIceCandidate()
        val pc = downStreams[streamId]?.pc ?: upStream?.takeIf { it.id == streamId }?.pc

        if (pc != null && streamId !in settingRemote && pc.remoteDescription != null) {
            pc.addIceCandidate(iceCandidate)
        } else {
            pendingCandidates.getOrPut(streamId) { mutableListOf() }.add(candidate)
        }
    }

    // ─── Closing streams ──────────────────────────────────────────────────

    /** Closes a down stream; the UI is notified before the track is released. */
    fun closeDownStream(streamId: String) {
        _remoteVideoTracks.update { it - streamId }
        downStreams.remove(streamId)?.pc?.dispose()
        pendingCandidates.remove(streamId)
    }

    /** Closes the up stream (local tracks stay open and reusable). */
    fun closeUpStream() {
        upStream?.let { pendingCandidates.remove(it.id) }
        upStream?.pc?.dispose()
        upStream = null
    }

    /** Releases all native resources: PeerConnections, tracks, camera. End of call. */
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

    // ─── PeerConnection creation ──────────────────────────────────────────

    private fun createPeerConnection(streamId: String, up: Boolean): PeerConnection? =
        factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "[$streamId] local candidate: ${candidate.sdp}")
                onLocalIceCandidate(streamId, candidate.toJson())
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track() ?: return
                track.setEnabled(true)
                if (track is VideoTrack) {
                    Log.d(TAG, "[$streamId] remote video track received")
                    _remoteVideoTracks.update { it + (streamId to track) }
                }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "[$streamId] connection: $state")
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "[$streamId] ICE: $state")
                if (state == PeerConnection.IceConnectionState.FAILED) onIceFailed(streamId, up)
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "[$streamId] ICE gathering: $state")
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
            // A single transport for audio + video: half as many ports to get through
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

    // ─── Internal data ────────────────────────────────────────────────────

    private data class DownStream(val id: String, val pc: PeerConnection)
    private data class UpStream(val id: String, val label: String, val pc: PeerConnection)
}

// ─── Coroutine extensions for SDP ────────────────────────────────────────────

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


