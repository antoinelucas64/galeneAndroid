package fr.antoineassocies.galene.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.antoineassocies.galene.R
import fr.antoineassocies.galene.domain.SignalingEvent
import fr.antoineassocies.galene.domain.model.GroupTarget
import fr.antoineassocies.galene.domain.model.JoinRequest
import fr.antoineassocies.galene.domain.model.PresentMode
import fr.antoineassocies.galene.ui.viewmodel.GroupLoginViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Group login form: name, password and what to publish, adapted to the authentication
 * mode announced by the server.
 *
 * @param pendingFailure server refusal reported by the room screen, to be shown once
 * @param onFailureShown called once [pendingFailure] has been taken into account
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupLoginScreen(
    target: GroupTarget,
    pendingFailure: SignalingEvent.JoinFailed?,
    onFailureShown: () -> Unit,
    onJoin: (JoinRequest) -> Unit,
    onBack: () -> Unit,
    viewModel: GroupLoginViewModel = koinViewModel(parameters = { parametersOf(target) }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.join.collect(onJoin) }
    LaunchedEffect(pendingFailure) {
        if (pendingFailure != null) {
            viewModel.onJoinFailed(pendingFailure)
            onFailureShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.status?.title ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.loadError != null -> {
                    Text(state.loadError!!.asString(), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = viewModel::loadStatus) { Text(stringResource(R.string.action_retry)) }
                }
                else -> LoginForm(state = state, viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.LoginForm(
    state: fr.antoineassocies.galene.ui.viewmodel.GroupLoginUiState,
    viewModel: GroupLoginViewModel,
) {
    val status = state.status ?: return

    // ─── Group description ────────────────────────────────────────────────
    status.description?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodyLarge)
    }
    if (status.locked) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.label_group_locked), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (state.token != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.label_invitation_link), style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (state.portalOnly) {
        // The SSO portal sends the user to the browser; coming back to the app is not handled yet
        Text(stringResource(R.string.label_portal_unsupported), color = MaterialTheme.colorScheme.error)
        return
    }

    // ─── Credentials ──────────────────────────────────────────────────────
    OutlinedTextField(
        value = state.username,
        onValueChange = viewModel::onUsernameChange,
        label = {
            Text(stringResource(if (state.usernameRequired) R.string.label_username else R.string.label_username_optional))
        },
        isError = state.credentialsRejected,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = if (state.showPassword) ImeAction.Next else ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { viewModel.submit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.showPassword) {
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text(stringResource(R.string.label_password)) },
            supportingText = status.authServer?.let { { Text(stringResource(R.string.hint_auth_server)) } },
            isError = state.credentialsRejected,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { viewModel.submit() }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    state.error?.let { ErrorCard(it.asString()) }

    // ─── What to publish on joining ───────────────────────────────────────
    Text(stringResource(R.string.label_present), style = MaterialTheme.typography.titleSmall)
    val options = listOf(
        PresentMode.NONE to R.string.present_none,
        PresentMode.MIC to R.string.present_mic,
        PresentMode.MIC_AND_CAMERA to R.string.present_both,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = state.present == mode,
                onClick = { viewModel.onPresentChange(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(stringResource(label), maxLines = 1) }
        }
    }

    Button(
        onClick = viewModel::submit,
        enabled = state.canJoin,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        if (state.joining) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Text(stringResource(R.string.action_join))
    }
}

/** Join error, prominent enough to be noticed when coming back from the room screen. */
@Composable
private fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
