package fr.antoineassocies.galene.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.antoineassocies.galene.R
import fr.antoineassocies.galene.domain.model.Participant

/** What can be done to another participant from the list. */
interface ParticipantActions {
    fun message(id: String)
    fun mute(id: String)
    fun kick(id: String)
    fun setPresent(id: String, allowed: Boolean)
    fun setOperator(id: String, operator: Boolean)
}

/**
 * Full-screen list of the people in the group, as in Meet: raised hands first (in the
 * order they arrive), then me, then the others by name. Each row offers a private
 * message, and to operators the web client's actions: mute, allow / forbid presenting,
 * operator rights, removal (confirmed). An "invite" row at the top shares a link to the group.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParticipantsPanel(
    participants: List<Participant>,
    selfId: String,
    speaking: Set<String>,
    isOperator: Boolean,
    actions: ParticipantActions,
    onInvite: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val sorted = remember(participants, selfId) {
        participants.sortedWith(
            compareBy<Participant>({ !it.raisedHand }, { it.id != selfId }, { it.username.lowercase() })
        )
    }
    var toKick by remember { mutableStateOf<Participant?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${stringResource(R.string.action_participants)} (${participants.size})") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item(key = "invite") {
                ListItem(
                    leadingContent = {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.action_invite)) },
                    modifier = Modifier.clickable(onClick = onInvite),
                )
            }
            items(sorted, key = { it.id }) { p ->
                ParticipantRow(
                    p,
                    self = p.id == selfId,
                    speaking = p.id in speaking,
                    isOperator = isOperator,
                    actions = actions,
                    onKick = { toKick = p },
                )
            }
        }
    }

    toKick?.let { p ->
        AlertDialog(
            onDismissRequest = { toKick = null },
            text = { Text(stringResource(R.string.confirm_kick, p.username)) },
            confirmButton = {
                TextButton(onClick = { actions.kick(p.id); toKick = null }) {
                    Text(stringResource(R.string.action_kick), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { toKick = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ParticipantRow(
    p: Participant,
    self: Boolean,
    speaking: Boolean,
    isOperator: Boolean,
    actions: ParticipantActions,
    onKick: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = if (speaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        p.username.take(1).uppercase(),
                        color = if (speaking) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
        headlineContent = {
            Text(if (self) "${p.username} (${stringResource(R.string.label_me)})" else p.username)
        },
        supportingContent = if (p.isOperator) {
            { Text(stringResource(R.string.label_operator)) }
        } else null,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.raisedHand) {
                    Icon(
                        Icons.Default.PanTool,
                        contentDescription = stringResource(R.string.label_hand_raised),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (!self) ParticipantMenu(p, isOperator, actions, onKick)
            }
        },
    )
}

@Composable
private fun ParticipantMenu(p: Participant, isOperator: Boolean, actions: ParticipantActions, onKick: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuItem(R.string.action_private_message, Icons.AutoMirrored.Filled.Chat) {
                expanded = false; actions.message(p.id)
            }
            if (isOperator) {
                HorizontalDivider()
                MenuItem(R.string.action_mute, Icons.Default.MicOff) {
                    expanded = false; actions.mute(p.id)
                }
                if (p.canPresent) {
                    MenuItem(R.string.action_forbid_present, Icons.Default.VideocamOff) {
                        expanded = false; actions.setPresent(p.id, false)
                    }
                } else {
                    MenuItem(R.string.action_allow_present, Icons.Default.Videocam) {
                        expanded = false; actions.setPresent(p.id, true)
                    }
                }
                if (p.isOperator) {
                    MenuItem(R.string.action_remove_op, Icons.Default.RemoveModerator) {
                        expanded = false; actions.setOperator(p.id, false)
                    }
                } else {
                    MenuItem(R.string.action_make_op, Icons.Default.AddModerator) {
                        expanded = false; actions.setOperator(p.id, true)
                    }
                }
                MenuItem(R.string.action_kick, Icons.Default.PersonRemove) {
                    expanded = false; onKick()
                }
            }
        }
    }
}

@Composable
private fun MenuItem(@StringRes label: Int, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}
