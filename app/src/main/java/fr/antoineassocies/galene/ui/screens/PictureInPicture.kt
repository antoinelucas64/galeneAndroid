package fr.antoineassocies.galene.ui.screens

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import fr.antoineassocies.galene.MainActivity
import fr.antoineassocies.galene.R

private const val ACTION_PIP_CONTROL = "fr.antoineassocies.galene.PIP_CONTROL"
private const val EXTRA_CONTROL = "control"
private const val CONTROL_MIC = 1
private const val CONTROL_LEAVE = 2

/** Portrait, like a phone's camera picture: the shape of most tiles. */
private val PIP_ASPECT = Rational(3, 4)

/**
 * Lets the call shrink into a floating window when the user leaves the app, as Meet does,
 * while [enabled]. The window has two buttons: microphone (when [micAvailable]) and hang up.
 *
 * @return true while the activity is in Picture-in-Picture: the screen then shows only video.
 */
@Composable
fun rememberCallPictureInPicture(
    enabled: Boolean,
    micAvailable: Boolean,
    audioMuted: Boolean,
    /** Where the videos are on screen: the window grows from there (smoother animation). */
    videoBounds: android.graphics.Rect?,
    onToggleMic: () -> Unit,
    onLeave: () -> Unit,
): Boolean {
    val activity = LocalContext.current.findActivity() as? MainActivity ?: return false
    val supported = remember {
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    var inPip by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }

    // Buttons of the floating window → broadcast to ourselves only
    val toggleMic by rememberUpdatedState(onToggleMic)
    val leave by rememberUpdatedState(onLeave)
    DisposableEffect(activity) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(EXTRA_CONTROL, 0)) {
                    CONTROL_MIC -> toggleMic()
                    CONTROL_LEAVE -> {
                        leave()
                        // Nothing left to show in the small window
                        activity.moveTaskToBack(false)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            activity, receiver, IntentFilter(ACTION_PIP_CONTROL), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { activity.unregisterReceiver(receiver) }
    }

    val micLabel = stringResource(if (audioMuted) R.string.action_unmute else R.string.action_mute)
    val leaveLabel = stringResource(R.string.action_leave)
    DisposableEffect(activity, supported, enabled, micAvailable, audioMuted, videoBounds) {
        activity.pipParams = if (supported && enabled) {
            val actions = buildList {
                if (micAvailable) {
                    add(activity.pipAction(CONTROL_MIC, if (audioMuted) R.drawable.ic_pip_mic_off else R.drawable.ic_pip_mic, micLabel))
                }
                add(activity.pipAction(CONTROL_LEAVE, R.drawable.ic_pip_call_end, leaveLabel))
            }
            PictureInPictureParams.Builder()
                .setAspectRatio(PIP_ASPECT)
                .setActions(actions)
                .apply { videoBounds?.takeIf { !it.isEmpty }?.let { setSourceRectHint(it) } }
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(true) }
                .build()
        } else null
        onDispose { activity.pipParams = null }
    }
    return inPip
}

private fun ComponentActivity.pipAction(control: Int, icon: Int, label: String): RemoteAction {
    val intent = Intent(ACTION_PIP_CONTROL).setPackage(packageName).putExtra(EXTRA_CONTROL, control)
    val pending = PendingIntent.getBroadcast(
        this, control, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return RemoteAction(Icon.createWithResource(this, icon), label, label, pending)
}
