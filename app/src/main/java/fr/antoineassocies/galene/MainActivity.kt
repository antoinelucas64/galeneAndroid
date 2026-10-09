package fr.antoineassocies.galene

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fr.antoineassocies.galene.ui.GaleneNavHost
import fr.antoineassocies.galene.ui.theme.GaleneTheme

/**
 * Unique Activity de l'app : héberge la navigation Compose ([GaleneNavHost]).
 *
 * Déclarée `singleTop` au manifeste, pour que la notification d'appel en cours ramène
 * dans l'appel existant au lieu d'ouvrir une seconde instance.
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
