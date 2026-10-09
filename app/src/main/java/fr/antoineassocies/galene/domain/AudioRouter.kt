package fr.antoineassocies.galene.domain

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Bascule l'audio en mode communication sur haut-parleur pendant l'appel.
 * Sans ça, Android joue le son distant dans l'écouteur, à peine audible.
 * Version minimale — le routage bluetooth/casque viendra en Phase 3.
 */
class AudioRouter(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var savedMode = AudioManager.MODE_NORMAL
    private var active = false

    fun start() {
        if (active) return
        active = true
        savedMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(true)
    }

    fun stop() {
        if (!active) return
        active = false
        setSpeaker(false)
        audioManager.mode = savedMode
    }

    private fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audioManager.setCommunicationDevice(it) }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }
}
