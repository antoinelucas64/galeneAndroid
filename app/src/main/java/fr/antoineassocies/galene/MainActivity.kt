package fr.antoineassocies.galene

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GaleneTheme {
                GaleneNavHost()
            }
        }
    }
}
