package fr.antoineassocies.galene.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.antoineassocies.galene.R
import fr.antoineassocies.galene.domain.model.GroupStatus
import fr.antoineassocies.galene.domain.model.GroupTarget
import fr.antoineassocies.galene.ui.viewmodel.ServerViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * Home screen: server address (or a pasted group / invitation link), then the server's
 * public groups and a field for an unlisted group.
 */
@Composable
fun ServerScreen(
    onOpenGroup: (GroupTarget) -> Unit,
    viewModel: ServerViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var otherGroup by remember { mutableStateOf("") }

    LaunchedEffect(viewModel) {
        viewModel.openGroup.collect(onOpenGroup)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // ─── Server or link ───────────────────────────────────────────────
        item {
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                label = { Text(stringResource(R.string.label_server_or_link)) },
                placeholder = { Text("galene.example.org") },
                supportingText = { Text(state.error ?: stringResource(R.string.hint_server_or_link)) },
                isError = state.error != null && state.groups == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.submit() }),
                trailingIcon = {
                    IconButton(onClick = viewModel::submit, enabled = state.input.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.action_continue))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.loading) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }

        // ─── Public groups ────────────────────────────────────────────────
        val groups = state.groups
        if (groups != null && state.server != null) {
            item {
                Text(
                    stringResource(R.string.label_public_groups, state.server!!.host),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (groups.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.label_no_public_groups),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            items(groups, key = { it.name }) { group ->
                GroupRow(group, onClick = { viewModel.openGroup(group) })
            }

            // ─── Unlisted group ───────────────────────────────────────────
            item {
                OutlinedTextField(
                    value = otherGroup,
                    onValueChange = { otherGroup = it },
                    label = { Text(stringResource(R.string.label_other_group)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { viewModel.openGroupByName(otherGroup) }),
                    trailingIcon = {
                        IconButton(onClick = { viewModel.openGroupByName(otherGroup) }, enabled = otherGroup.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.action_continue))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

/** One row of the public group list: name, description, occupancy, lock. */
@Composable
private fun GroupRow(group: GroupStatus, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        ListItem(
            headlineContent = { Text(group.title) },
            supportingContent = group.description?.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 2) } },
            leadingContent = { Icon(Icons.Default.Groups, contentDescription = null) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    group.clientCount?.let {
                        Text(
                            pluralStringResource(R.plurals.label_participants, it, it),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (group.locked) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = stringResource(R.string.label_locked),
                            modifier = Modifier.padding(start = 4.dp).size(16.dp),
                        )
                    }
                }
            },
        )
    }
}
