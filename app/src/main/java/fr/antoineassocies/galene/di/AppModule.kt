package fr.antoineassocies.galene.di

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import fr.antoineassocies.galene.domain.AudioRouter
import fr.antoineassocies.galene.domain.GaleneClient
import fr.antoineassocies.galene.domain.WebRTCManager
import fr.antoineassocies.galene.domain.model.JoinRequest
import fr.antoineassocies.galene.ui.viewmodel.RoomViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.TimeUnit

/**
 * Graphe de dépendances Koin.
 *
 * - `single` : objets coûteux ou sans état, partagés par tout le process
 *   (client HTTP, JSON, contexte EGL, factory WebRTC).
 * - `factory` : objets porteurs d'état d'appel, recréés à chaque entrée en salle pour
 *   qu'aucun état ne survive d'un appel à l'autre.
 */
val appModule = module {

    single {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // WebSocket — pas de timeout lecture
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    single {
        Json {
            ignoreUnknownKeys = true        // tolérance aux champs futurs du protocole
            encodeDefaults = true           // les champs type/kind ont des defaults — sans ça ils sont omis
        }
    }

    // Contexte EGL partagé entre capture, encodeur/décodeur matériels et renderers
    single { EglBase.create() }

    single {
        val egl = get<EglBase>().eglBaseContext
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl))
            .createPeerConnectionFactory()
    }

    // factory = nouvelle instance par session (une instance partagée causerait des leaks)
    factory { GaleneClient(get(), get()) }

    factory { WebRTCManager(androidContext(), get(), get()) }

    factory { AudioRouter(androidContext()) }

    viewModel { (request: JoinRequest) ->
        RoomViewModel(
            appContext = androidContext(),
            request = request,
            galeneClient = get(),
            webRTCManager = get(),
            audioRouter = get(),
        )
    }
}
