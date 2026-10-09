package fr.antoineassocies.galene.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.antoineassocies.galene.R
import fr.antoineassocies.galene.domain.ConnectionState
import fr.antoineassocies.galene.domain.SignalingEvent
import fr.antoineassocies.galene.domain.model.JoinRequest
import fr.antoineassocies.galene.domain.model.PresentMode
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import fr.antoineassocies.galene.ui.components.FullscreenTile
import fr.antoineassocies.galene.ui.components.VideoGrid
import fr.antoineassocies.galene.ui.components.VideoTile
import fr.antoineassocies.galene.ui.viewmodel.RoomViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Room screen: video grid, control bar, and the Activity-bound effects
 * (permissions, foreground / background, keeping the screen on).
 *
 * All call logic lives in [RoomViewModel]; this screen only renders its state and
 * forwards user actions and system events to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    request: JoinRequest,
    onLeave: () -> Unit,
    onJoinFailed: (SignalingEvent.JoinFailed) -> Unit,
    viewModel: RoomViewModel = koinViewModel(
        parameters = { parametersOf(request) }
    )
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val localTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val remoteTracks by viewModel.remoteVideoTracks.collectAsStateWithLifecycle()

    // ─── Microphone + camera permissions ──────────────────────────────────
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.onPermissionsResult(
            mic = results[Manifest.permission.RECORD_AUDIO] == true,
            camera = results[Manifest.permission.CAMERA] == true,
        )
    }
    LaunchedEffect(Unit) {
        // Only what the login form's "publish" choice requires; listen only → nothing to ask
        val present = request.present
        if (present == PresentMode.NONE) {
            viewModel.onPermissionsResult(mic = false, camera = false)
            return@LaunchedEffect
        }
        permissionLauncher.launch(buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (present.usesCamera) add(Manifest.permission.CAMERA)
            // "Call in progress" notification; optional, the service runs even if it is denied
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
    }

    // ─── Join refused → back to the login form with the reason ────────────
    LaunchedEffect(uiState.joinFailure) {
        uiState.joinFailure?.let { failure ->
            viewModel.leave()
            onJoinFailed(failure)
        }
    }

    // ─── Foreground / background → camera off, then back on ───────────────
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP  -> viewModel.onAppBackground()
                Lifecycle.Event.ON_START -> viewModel.onAppForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ─── Screen kept on while in the room ─────────────────────────────────
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // ─── Tiles: me, received streams, participants without streams ────────
    val selfLabel = stringResource(R.string.label_me)
    val tiles = buildList {
        add(VideoTile("self", selfLabel, localTrack.takeIf { !uiState.videoMuted }, mirror = uiState.frontCamera))
        uiState.remoteStreams.forEach { add(VideoTile(it.id, it.username, remoteTracks[it.id])) }
        // Participants without a stream (listen only): avatar tile
        val publishing = uiState.remoteStreams.mapNotNull { it.sourceId }.toSet()
        uiState.participants
            .filter { it.id != uiState.selfId && it.id !in publishing }
            .forEach { add(VideoTile(it.id, it.username, null)) }
    }

    // ─── Fullscreen on a tile (tap); back with a tap or the back button ───
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    // The person left or stopped publishing: back to the grid
    val focused = tiles.firstOrNull { it.key == focusedKey }
        .takeIf { uiState.connectionState == ConnectionState.Connected }
    BackHandler(enabled = focused != null) { focusedKey = null }
    ImmersiveMode(enabled = focused != null)
    if (focused != null) {
        FullscreenTile(focused, viewModel.eglContext, onClick = { focusedKey = null }, Modifier.fillMaxSize())
        return
    }

    // ─── Error (connection lost with a reason) ────────────────────────────
    uiState.errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(onClick = { viewModel.leave(); onLeave() }) { Text("OK") }
            },
            title = { Text("Erreur") },
            text = { Text(msg) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(request.title) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.leave(); onLeave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_leave))
                    }
                }
            )
        },
        bottomBar = {
            RoomControls(
                audioMuted  = uiState.audioMuted,
                videoMuted  = uiState.videoMuted,
                micAvailable = uiState.sendingAudio,
                cameraAvailable = uiState.sendingVideo,
                canSwitchCamera = uiState.sendingVideo && !uiState.videoMuted,
                onToggleAudio = viewModel::toggleAudio,
                onToggleVideo = viewModel::toggleVideo,
                onSwitchCamera = viewModel::switchCamera,
                onLeave = { viewModel.leave(); onLeave() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ─── Connection status bar ────────────────────────────────────
            when (uiState.connectionState) {
                ConnectionState.Connecting    -> LinearProgressIndicator(Modifier.fillMaxWidth())
                ConnectionState.Connected     -> {}
                ConnectionState.Disconnected  -> {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(
                            text = stringResource(R.string.label_disconnected),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            // ─── Debug status line ────────────────────────────────────────
            if (uiState.statusText.isNotEmpty()) {
                Text(
                    text = uiState.statusText,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    maxLines = 2,
                )
            }

            // ─── Main content ─────────────────────────────────────────────
            if (uiState.connectionState == ConnectionState.Connected) {
                VideoGrid(
                    tiles, viewModel.eglContext,
                    onTileClick = { focusedKey = it.key },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.connectionState == ConnectionState.Connecting) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.label_connecting))
                        }
                    } else {
                        Icon(
                            Icons.Default.WifiOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/** Bottom bar: microphone, camera, camera switch, hang up. */
@Composable
private fun RoomControls(
    audioMuted: Boolean,
    videoMuted: Boolean,
    micAvailable: Boolean,
    cameraAvailable: Boolean,
    canSwitchCamera: Boolean,
    onToggleAudio: () -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit,
    onLeave: () -> Unit,
) {
    BottomAppBar {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Disabled when this media is not published (listen only, permission denied)
            IconButton(onClick = onToggleAudio, enabled = micAvailable) {
                Icon(
                    imageVector = if (audioMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = stringResource(if (audioMuted) R.string.action_unmute else R.string.action_mute)
                )
            }
            IconButton(onClick = onToggleVideo, enabled = cameraAvailable) {
                Icon(
                    imageVector = if (videoMuted) Icons.Default.VideocamOff else Icons.Default.Videocam,
                    contentDescription = stringResource(if (videoMuted) R.string.action_video_on else R.string.action_video_off)
                )
            }
            IconButton(onClick = onSwitchCamera, enabled = canSwitchCamera) {
                Icon(Icons.Default.Cameraswitch, contentDescription = stringResource(R.string.action_switch_camera))
            }
            FilledIconButton(
                onClick = onLeave,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Icon(
                    Icons.Default.CallEnd,
                    contentDescription = stringResource(R.string.action_leave),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/**
 * Hides the system bars (status, navigation) while [enabled]: true fullscreen.
 * A swipe from the edge brings them back temporarily.
 */
@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (enabled && controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
