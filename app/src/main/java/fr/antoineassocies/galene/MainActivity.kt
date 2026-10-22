package fr.antoineassocies.galene

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.antoineassocies.galene.domain.model.GaleneLink
import fr.antoineassocies.galene.ui.GaleneNavHost
import fr.antoineassocies.galene.ui.theme.GaleneTheme

/**
 * The app's single Activity: hosts the Compose navigation ([GaleneNavHost]).
 *
 * Declared `singleTop` in the manifest, so that the call notification brings the user
 * back to the ongoing call instead of opening a second instance.
 *
 * Also opened by a group / invitation link, clicked or shared to the app ([incomingLink]).
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

    /** Group link the app was opened with, until the navigation has handled it. */
    private var incomingLink by mutableStateOf<GaleneLink.Group?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not again after a rotation: the link has already been handled
        if (savedInstanceState == null) incomingLink = groupLink(intent)
        setContent {
            GaleneTheme {
                GaleneNavHost(incomingLink = incomingLink, onLinkHandled = { incomingLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        groupLink(intent)?.let { incomingLink = it }
    }

    /** The group link a clicked link (VIEW) or shared text (SEND) carries, if any. */
    private fun groupLink(intent: Intent?): GaleneLink.Group? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString?.let { GaleneLink.parse(it) as? GaleneLink.Group }
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(GaleneLink::findGroup)
        else -> null
    }

    /** Before Android 12, Picture-in-Picture must be requested when the user leaves the app. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val params = pipParams ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) runCatching { enterPictureInPictureMode(params) }
    }
}
