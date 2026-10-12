package fr.antoineassocies.galene

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fr.antoineassocies.galene.ui.GaleneNavHost
import fr.antoineassocies.galene.ui.theme.GaleneTheme

/**
 * The app's single Activity: hosts the Compose navigation ([GaleneNavHost]).
 *
 * Declared `singleTop` in the manifest, so that the call notification brings the user
 * back to the ongoing call instead of opening a second instance.
 */
class MainActivity : ComponentActivity() {

    /**
     * Picture-in-Picture settings while a call is on screen, null otherwise. Set by the
     * room screen: leaving the app (home button, gesture) then shrinks the call into a
     * small floating window instead of hiding it.
     */
    var pipParams: PictureInPictureParams? = null
        set(value) {
            field = value
            // Android 12+: the system enters Picture-in-Picture by itself (smoother animation)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setPictureInPictureParams(value ?: PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GaleneTheme {
                GaleneNavHost()
            }
        }
    }

    /** Before Android 12, Picture-in-Picture must be requested when the user leaves the app. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val params = pipParams ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) runCatching { enterPictureInPictureMode(params) }
    }
}
