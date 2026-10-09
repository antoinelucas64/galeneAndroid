package fr.antoineassocies.galene.ui.screens

import android.Manifest
import android.os.Build
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
import fr.antoineassocies.galene.domain.model.JoinRequest
import fr.antoineassocies.galene.ui.components.VideoGrid
import fr.antoineassocies.galene.ui.components.VideoTile
import fr.antoineassocies.galene.ui.viewmodel.RoomViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Écran de salle : grille vidéo, barre de contrôles, et gestion des effets liés à
 * l'Activity (permissions, premier plan / arrière-plan, écran maintenu allumé).
 *
 * Toute la logique d'appel est dans [RoomViewModel] ; cet écran ne fait qu'afficher
 * son état et lui transmettre les actions et événements système.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    request: JoinRequest,
    onLeave: () -> Unit,
    viewModel: RoomViewModel = koinViewModel(
        parameters = { parametersOf(request) }
    )
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val localTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val remoteTracks by viewModel.remoteVideoTracks.collectAsStateWithLifecycle()

    // ─── Permissions micro + caméra ────────────────────────────────────────
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.onPermissionsResult(
            mic = results[Manifest.permission.RECORD_AUDIO] == true,
            camera = results[Manifest.permission.CAMERA] == true,
        )
    }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            // Notification « appel en cours » ; facultative, le service tourne même si refusée
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
    }

    // ─── Premier plan / arrière-plan → caméra coupée puis rallumée ────────
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

    // ─── Écran allumé tant qu'on est dans la salle ────────────────────────
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // ─── Erreur (JoinFailed / Disconnected avec raison) ───────────────────
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
                title = { Text(request.roomName) },
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
            // ─── Barre d'état connexion ───────────────────────────────────
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

            // ─── Ligne de statut debug ────────────────────────────────────
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

            // ─── Contenu principal ────────────────────────────────────────
            if (uiState.connectionState == ConnectionState.Connected) {
                val selfLabel = stringResource(R.string.label_me)
                val tiles = buildList {
                    add(VideoTile("self", selfLabel, localTrack.takeIf { !uiState.videoMuted }, mirror = uiState.frontCamera))
                    uiState.remoteStreams.forEach { add(VideoTile(it.id, it.username, remoteTracks[it.id])) }
                    // Participants sans flux (écoute seule) : tuile avatar
                    val publishing = uiState.remoteStreams.mapNotNull { it.sourceId }.toSet()
                    uiState.participants
                        .filter { it.id != uiState.selfId && it.id !in publishing }
                        .forEach { add(VideoTile(it.id, it.username, null)) }
                }
                VideoGrid(tiles, viewModel.eglContext, Modifier.weight(1f).fillMaxWidth())
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

/** Barre du bas : micro, caméra, bascule de caméra, raccrocher. */
@Composable
private fun RoomControls(
    audioMuted: Boolean,
    videoMuted: Boolean,
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
            IconButton(onClick = onToggleAudio) {
                Icon(
                    imageVector = if (audioMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = stringResource(if (audioMuted) R.string.action_unmute else R.string.action_mute)
                )
            }
            IconButton(onClick = onToggleVideo) {
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
