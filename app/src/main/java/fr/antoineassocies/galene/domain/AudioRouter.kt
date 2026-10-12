package fr.antoineassocies.galene.domain

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the call's audio goes (and, for headsets, where the microphone comes from). */
enum class AudioRoute { SPEAKER, EARPIECE, WIRED_HEADSET, BLUETOOTH }

/**
 * Audio routing during a call.
 *
 * Automatic choice, most specific first: Bluetooth headset > wired / USB headset >
 * loudspeaker. A video call is held at arm's length, hence the loudspeaker rather than
 * the earpiece by default. The choice follows devices being plugged in or out during the
 * call; [select] lets the user force another route until that device disappears.
 *
 * Bluetooth goes through the "phone" profile (HFP / SCO), the only one that also carries
 * the headset's microphone; the "music" profile (A2DP) is output only.
 *
 * Android 12+ has a dedicated API (`setCommunicationDevice`); before that, speakerphone
 * and Bluetooth SCO are switched by hand, and wired headsets are taken by the system
 * as soon as the speakerphone is off.
 */
class AudioRouter(context: Context) {

    private companion object {
        const val TAG = "AudioRouter"
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _available = MutableStateFlow<List<AudioRoute>>(emptyList())
    /** Routes currently possible, in order of automatic preference. */
    val available: StateFlow<List<AudioRoute>> = _available.asStateFlow()

    private val _current = MutableStateFlow(AudioRoute.SPEAKER)
    val current: StateFlow<AudioRoute> = _current.asStateFlow()

    private var savedMode = AudioManager.MODE_NORMAL
    private var active = false
    private var userChoice: AudioRoute? = null
    private var focusRequest: AudioFocusRequest? = null
    private var scoStarted = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
    }

    fun start() {
        if (active) return
        active = true
        savedMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        requestFocus()
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        refresh()
    }

    fun stop() {
        if (!active) return
        active = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            stopSco()
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        audioManager.mode = savedMode
        userChoice = null
    }

    /** User's choice; kept as long as that route exists. */
    fun select(route: AudioRoute) {
        userChoice = route
        if (active) refresh()
    }

    /** Recomputes the available routes and applies the chosen one. */
    private fun refresh() {
        if (!active) return
        val routes = detectRoutes()
        _available.value = routes
        if (userChoice !in routes) userChoice = null
        val target = userChoice ?: routes.first()
        apply(target)
        _current.value = target
        Log.d(TAG, "routes=$routes → $target")
    }

    private fun detectRoutes(): List<AudioRoute> {
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.map { it.type }
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        }.toSet()
        return buildList {
            if (AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types) add(AudioRoute.BLUETOOTH)
            val headset = types.any { it in HEADSET_TYPES }
            if (headset) add(AudioRoute.WIRED_HEADSET)
            add(AudioRoute.SPEAKER)
            // Before Android 12 the system always prefers a plugged headset to the earpiece
            val earpieceReachable = !headset || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            if (AudioDeviceInfo.TYPE_BUILTIN_EARPIECE in types && earpieceReachable) add(AudioRoute.EARPIECE)
        }
    }

    private fun apply(route: AudioRoute) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wanted = when (route) {
                AudioRoute.SPEAKER -> setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                AudioRoute.EARPIECE -> setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                AudioRoute.WIRED_HEADSET -> HEADSET_TYPES
                AudioRoute.BLUETOOTH -> setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            }
            val device = audioManager.availableCommunicationDevices.firstOrNull { it.type in wanted }
            if (device != null && audioManager.communicationDevice?.id != device.id) {
                if (!audioManager.setCommunicationDevice(device)) Log.w(TAG, "setCommunicationDevice($route) refused")
            }
        } else {
            @Suppress("DEPRECATION")
            when (route) {
                AudioRoute.BLUETOOTH -> {
                    audioManager.isSpeakerphoneOn = false
                    startSco()
                }
                AudioRoute.SPEAKER -> {
                    stopSco()
                    audioManager.isSpeakerphoneOn = true
                }
                // With the speakerphone off, the system picks the wired headset if there is one
                AudioRoute.WIRED_HEADSET, AudioRoute.EARPIECE -> {
                    stopSco()
                    audioManager.isSpeakerphoneOn = false
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startSco() {
        if (scoStarted) return
        scoStarted = true
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        if (!scoStarted) return
        scoStarted = false
        audioManager.isBluetoothScoOn = false
        audioManager.stopBluetoothSco()
    }

    /** Pauses music players etc. for the duration of the call. */
    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener({ Log.d(TAG, "audio focus: $it") }, handler)
            .build()
        audioManager.requestAudioFocus(request)
        focusRequest = request
    }
}

private val HEADSET_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
)
