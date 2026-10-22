package fr.antoineassocies.galene.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.antoineassocies.galene.BuildConfig
import fr.antoineassocies.galene.R
import fr.antoineassocies.galene.domain.AudioRoute
import fr.antoineassocies.galene.domain.ConnectionState
import fr.antoineassocies.galene.domain.SignalingEvent
import fr.antoineassocies.galene.domain.model.JoinRequest
import fr.antoineassocies.galene.domain.model.PresentMode
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import fr.antoineassocies.galene.ui.components.ChatPanel
import fr.antoineassocies.galene.ui.components.ChatPreview
import fr.antoineassocies.galene.ui.components.FullscreenTile
import fr.antoineassocies.galene.ui.components.ParticipantActions
import fr.antoineassocies.galene.ui.components.ParticipantsPanel
import fr.antoineassocies.galene.ui.components.OneToOneLayout
import fr.antoineassocies.galene.ui.components.SpeakerLayout
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
    val raisedHands = uiState.participants.filter { it.raisedHand }.map { it.id }.toSet()
    val tiles = buildList {
        add(
            VideoTile(
                "self", selfLabel, localTrack.takeIf { !uiState.videoMuted }, mirror = uiState.frontCamera,
                speaking = uiState.selfId in uiState.speaking, raisedHand = uiState.handRaised,
                participantId = uiState.selfId,
            )
        )
        uiState.remoteStreams.forEach {
            add(
                VideoTile(
                    it.id, it.username, remoteTracks[it.id],
                    speaking = it.sourceId in uiState.speaking, raisedHand = it.sourceId in raisedHands,
                    participantId = it.sourceId,
                )
            )
        }
        // Participants without a stream (listen only): avatar tile
        val publishing = uiState.remoteStreams.mapNotNull { it.sourceId }.toSet()
        uiState.participants
            .filter { it.id != uiState.selfId && it.id !in publishing }
            .forEach {
                add(VideoTile(it.id, it.username, null, speaking = it.id in uiState.speaking, raisedHand = it.raisedHand, participantId = it.id))
            }
    }

    // ─── Picture-in-Picture: leaving the app shrinks the call into a small window ──
    var videoBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }
    val inPip = rememberCallPictureInPicture(
        enabled = uiState.errorMessage == null && uiState.joinFailure == null &&
            (uiState.connectionState == ConnectionState.Connected || uiState.reconnecting),
        micAvailable = uiState.sendingAudio,
        audioMuted = uiState.audioMuted,
        videoBounds = videoBounds,
        onToggleMic = viewModel::toggleAudio,
        onLeave = { viewModel.leave(); onLeave() },
    )
    if (inPip) {
        // The person speaking, otherwise someone with video, otherwise me
        val speakerTile = uiState.remoteStreams.filter { it.sourceId == uiState.activeSpeaker }
            .firstNotNullOfOrNull { s -> tiles.firstOrNull { it.key == s.id && it.track != null } }
            ?: tiles.firstOrNull { it.key == uiState.activeSpeaker }
        val tile = speakerTile
            ?: tiles.drop(1).firstOrNull { it.track != null }
            ?: tiles.drop(1).firstOrNull()
            ?: tiles.first()
        FullscreenTile(tile, viewModel.eglContext, onClick = {}, Modifier.fillMaxSize(), crop = true)
        return
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
                TextButton(onClick = { viewModel.leave(); onLeave() }) { Text(stringResource(android.R.string.ok)) }
            },
            title = { Text(stringResource(R.string.label_error)) },
            text = { Text(msg.asString()) }
        )
    }

    // ─── Chat, full screen over the call ──────────────────────────────────
    val joined = uiState.connectionState == ConnectionState.Connected && !uiState.reconnecting
    var participantsOpen by rememberSaveable { mutableStateOf(false) }
    // From three people up: the active speaker large (default) or everyone equal
    var gridView by rememberSaveable { mutableStateOf(false) }
    if (uiState.chatOpen) {
        ChatPanel(
            messages = uiState.chat,
            selfId = uiState.selfId,
            canSend = joined,
            others = uiState.participants.filter { it.id != uiState.selfId },
            recipient = uiState.participants.firstOrNull { it.id == uiState.chatRecipient },
            onSelectRecipient = viewModel::selectChatRecipient,
            onSend = viewModel::sendChat,
            onClose = viewModel::closeChat,
        )
        return
    }

    // ─── Invitation link → Android share sheet ─────────────────────────────
    val context = LocalContext.current
    val inviteSubject = stringResource(R.string.invite_share_subject, request.title)
    val inviteChooser = stringResource(R.string.action_invite)
    LaunchedEffect(Unit) {
        viewModel.invites.collect { link ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, inviteSubject)
                putExtra(Intent.EXTRA_TEXT, "$inviteSubject\n$link")
            }
            context.startActivity(Intent.createChooser(send, inviteChooser))
        }
    }

    // ─── Participants, full screen over the call ──────────────────────────
    if (participantsOpen) {
        ParticipantsPanel(
            participants = uiState.participants,
            selfId = uiState.selfId,
            speaking = uiState.speaking,
            isOperator = uiState.isOperator,
            actions = object : ParticipantActions {
                override fun message(id: String) {
                    participantsOpen = false
                    viewModel.messageParticipant(id)
                }
                override fun mute(id: String) = viewModel.muteParticipant(id)
                override fun kick(id: String) = viewModel.kickParticipant(id)
                override fun setPresent(id: String, allowed: Boolean) = viewModel.setParticipantPresent(id, allowed)
                override fun setOperator(id: String, operator: Boolean) = viewModel.setParticipantOperator(id, operator)
            },
            onInvite = viewModel::invite,
            onClose = { participantsOpen = false },
        )
        return
    }

    // ─── Notices (e.g. an operator muted us) ──────────────────────────────
    val snackbar = remember { SnackbarHostState() }
    val notice = uiState.notice?.asString()
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            viewModel.dismissNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(request.title) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.leave(); onLeave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_leave))
                    }
                },
                // Less frequent controls up here, as in Meet: camera switch, audio output
                actions = {
                    IconButton(onClick = viewModel::switchCamera, enabled = uiState.sendingVideo && !uiState.videoMuted) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = stringResource(R.string.action_switch_camera))
                    }
                    AudioOutputButton(uiState.audioRoute, uiState.audioRoutes, viewModel::selectAudioRoute)
                    if (tiles.size > 2) {
                        IconButton(onClick = { gridView = !gridView }) {
                            if (gridView) Icon(Icons.Default.AccountBox, contentDescription = stringResource(R.string.action_speaker_view))
                            else Icon(Icons.Default.GridView, contentDescription = stringResource(R.string.action_grid_view))
                        }
                    }
                    // Badge: number of hands up, to see them even with the list closed
                    IconButton(onClick = { participantsOpen = true }, enabled = joined) {
                        BadgedBox(badge = {
                            if (raisedHands.isNotEmpty()) Badge { Text(raisedHands.size.toString()) }
                        }) {
                            Icon(Icons.Default.People, contentDescription = stringResource(R.string.action_participants))
                        }
                    }
                },
            )
        },
        bottomBar = {
            RoomControls(
                audioMuted  = uiState.audioMuted,
                videoMuted  = uiState.videoMuted,
                micAvailable = uiState.sendingAudio,
                cameraAvailable = uiState.sendingVideo,
                handRaised = uiState.handRaised,
                canRaiseHand = joined,
                onToggleHand = viewModel::toggleRaisedHand,
                unreadChat = uiState.unreadChat,
                onOpenChat = viewModel::openChat,
                onToggleAudio = viewModel::toggleAudio,
                onToggleVideo = viewModel::toggleVideo,
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
            when {
                uiState.reconnecting -> LinearProgressIndicator(Modifier.fillMaxWidth())
                uiState.connectionState == ConnectionState.Connecting -> LinearProgressIndicator(Modifier.fillMaxWidth())
                uiState.connectionState == ConnectionState.Connected -> {}
                else -> {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(
                            text = stringResource(R.string.label_disconnected),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            // ─── Signaling trace, debug builds only ───────────────────────
            if (BuildConfig.DEBUG && uiState.statusText.isNotEmpty()) {
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
            if (joined) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned { videoBounds = it.boundsInWindow().toAndroidRect() }
                ) {
                    val onTileClick: (VideoTile) -> Unit = { focusedKey = it.key }
                    when {
                        tiles.size == 2 -> OneToOneLayout(
                            remote = tiles[1], self = tiles[0], viewModel.eglContext, onTileClick, Modifier.fillMaxSize(),
                        )
                        tiles.size > 2 && !gridView -> {
                            // The active speaker, otherwise someone with video, otherwise the first of the others
                            val main = tiles.firstOrNull { it.participantId == uiState.activeSpeaker && it.track != null }
                                ?: tiles.firstOrNull { it.participantId == uiState.activeSpeaker }
                                ?: tiles.drop(1).firstOrNull { it.track != null }
                                ?: tiles[1]
                            SpeakerLayout(main, tiles - main, viewModel.eglContext, onTileClick, Modifier.fillMaxSize())
                        }
                        else -> VideoGrid(tiles, viewModel.eglContext, onTileClick, Modifier.fillMaxSize())
                    }
                    ChatPreview(
                        uiState.chatPreview,
                        onClick = viewModel::openChat,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            } else {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.reconnecting || uiState.connectionState == ConnectionState.Connecting) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(if (uiState.reconnecting) R.string.label_reconnecting else R.string.label_connecting))
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

/** Bottom bar: microphone, camera, raise hand, chat, hang up. */
@Composable
private fun RoomControls(
    audioMuted: Boolean,
    videoMuted: Boolean,
    micAvailable: Boolean,
    cameraAvailable: Boolean,
    handRaised: Boolean,
    canRaiseHand: Boolean,
    onToggleHand: () -> Unit,
    unreadChat: Int,
    onOpenChat: () -> Unit,
    onToggleAudio: () -> Unit,
    onToggleVideo: () -> Unit,
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
            // Highlighted while the hand is up, so that one remembers to lower it
            IconToggleButton(
                checked = handRaised,
                onCheckedChange = { onToggleHand() },
                enabled = canRaiseHand,
                colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            ) {
                Icon(
                    Icons.Default.PanTool,
                    contentDescription = stringResource(if (handRaised) R.string.action_lower_hand else R.string.action_raise_hand),
                )
            }
            IconButton(onClick = onOpenChat) {
                BadgedBox(badge = {
                    if (unreadChat > 0) Badge { Text(if (unreadChat > 99) "99+" else unreadChat.toString()) }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = stringResource(R.string.action_chat))
                }
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

/** Current audio output; a tap lists the others (loudspeaker, earpiece, headset, Bluetooth). */
@Composable
private fun AudioOutputButton(
    current: AudioRoute,
    routes: List<AudioRoute>,
    onSelect: (AudioRoute) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = routes.size > 1) {
            Icon(current.icon(), contentDescription = stringResource(R.string.action_audio_output))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            routes.forEach { route ->
                DropdownMenuItem(
                    text = { Text(stringResource(route.label())) },
                    leadingIcon = { Icon(route.icon(), contentDescription = null) },
                    trailingIcon = { if (route == current) Icon(Icons.Default.Check, contentDescription = null) },
                    onClick = { expanded = false; onSelect(route) },
                )
            }
        }
    }
}

private fun AudioRoute.icon() = when (this) {
    AudioRoute.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
    AudioRoute.EARPIECE -> Icons.Default.PhoneInTalk
    AudioRoute.WIRED_HEADSET -> Icons.Default.Headset
    AudioRoute.BLUETOOTH -> Icons.Default.BluetoothAudio
}

private fun AudioRoute.label() = when (this) {
    AudioRoute.SPEAKER -> R.string.audio_route_speaker
    AudioRoute.EARPIECE -> R.string.audio_route_earpiece
    AudioRoute.WIRED_HEADSET -> R.string.audio_route_headset
    AudioRoute.BLUETOOTH -> R.string.audio_route_bluetooth
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

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
