package fr.antoineassocies.galene

import android.app.Application
import fr.antoineassocies.galene.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.webrtc.PeerConnectionFactory

/**
 * Process entry point: initialises libwebrtc, then the Koin graph.
 *
 * Order matters: [PeerConnectionFactory.initialize] must precede the creation of any
 * factory, and the factory is a Koin `single` created on first injection.
 */
class GaleneApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Global WebRTC initialisation — required before any PeerConnectionFactory
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
