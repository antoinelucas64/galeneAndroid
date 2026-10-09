package fr.antoineassocies.galene.di

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import fr.antoineassocies.galene.data.UserPreferences
import fr.antoineassocies.galene.domain.AudioRouter
import fr.antoineassocies.galene.domain.GaleneDirectory
import fr.antoineassocies.galene.domain.GaleneClient
import fr.antoineassocies.galene.domain.WebRTCManager
import fr.antoineassocies.galene.domain.model.GroupTarget
import fr.antoineassocies.galene.domain.model.JoinRequest
import fr.antoineassocies.galene.ui.viewmodel.GroupLoginViewModel
import fr.antoineassocies.galene.ui.viewmodel.ServerViewModel
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
 * Koin dependency graph.
 *
 * - `single`: expensive or stateless objects, shared by the whole process
 *   (HTTP client, JSON, EGL context, WebRTC factory).
 * - `factory`: objects holding call state, recreated each time a room is entered so
 *   that no state survives from one call to the next.
 */
val appModule = module {

    single {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // WebSocket — no read timeout
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    single {
        Json {
            ignoreUnknownKeys = true        // tolerate future protocol fields
            encodeDefaults = true           // type/kind fields have defaults — without this they are omitted
        }
    }

    // EGL context shared by capture, hardware encoder/decoder and renderers
    single { EglBase.create() }

    single {
        val egl = get<EglBase>().eglBaseContext
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl))
            .createPeerConnectionFactory()
    }

    single { GaleneDirectory(get(), get()) }

    single { UserPreferences(androidContext()) }

    viewModel { ServerViewModel(get(), get()) }

    viewModel { (target: GroupTarget) -> GroupLoginViewModel(target, get(), get()) }

    // factory = new instance per session (a shared instance would leak state)
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
