package fr.antoineassocies.galene

import android.app.Application
import fr.antoineassocies.galene.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.webrtc.PeerConnectionFactory

/**
 * Point d'entrée du process : initialise libwebrtc puis le graphe Koin.
 *
 * L'ordre compte : [PeerConnectionFactory.initialize] doit précéder la création de toute
 * factory, et la factory est un `single` Koin créé à la première injection.
 */
class GaleneApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialisation globale WebRTC — obligatoire avant tout PeerConnectionFactory
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        startKoin {
            androidLogger()
            androidContext(this@GaleneApp)
            modules(appModule)
        }
    }
}
