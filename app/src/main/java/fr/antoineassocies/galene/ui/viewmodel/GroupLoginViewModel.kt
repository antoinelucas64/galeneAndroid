package fr.antoineassocies.galene.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.antoineassocies.galene.R
import fr.antoineassocies.galene.data.UserPreferences
import fr.antoineassocies.galene.domain.GaleneDirectory
import fr.antoineassocies.galene.domain.SignalingEvent
import fr.antoineassocies.galene.domain.model.GroupStatus
import fr.antoineassocies.galene.domain.model.GroupTarget
import fr.antoineassocies.galene.domain.model.JoinRequest
import fr.antoineassocies.galene.domain.model.PresentMode
import fr.antoineassocies.galene.ui.UiText
import fr.antoineassocies.galene.ui.toUiText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** State of a group's login form. */
data class GroupLoginUiState(
    val loading: Boolean = true,
    val status: GroupStatus? = null,
    val loadError: UiText? = null,
    val username: String = "",
    val password: String = "",
    val present: PresentMode = PresentMode.MIC_AND_CAMERA,
    /** Invitation token in use; dropped after a refusal, as on the web. */
    val token: String? = null,
    /** The server answered `need-username`: the invitation link requires a name. */
    val usernameRequiredByToken: Boolean = false,
    val joining: Boolean = false,
    val error: UiText? = null,
    /** The server refused the name / password: the fields are flagged until edited. */
    val credentialsRejected: Boolean = false,
) {
    /** With a token the name is optional (the link may set it); otherwise it is required. */
    val usernameRequired: Boolean get() = token == null || usernameRequiredByToken
    val showPassword: Boolean get() = token == null

    /** Web portal authentication (SSO): not supported by the app yet. */
    val portalOnly: Boolean get() = token == null && status?.authPortal != null

    val canJoin: Boolean get() =
        status != null && !joining && !portalOnly && (!usernameRequired || username.isNotBlank())
}

/**
 * Logging into a group, the equivalent of the Galène web client's group page:
 *
 * 1. read the group status (`.status`): name, description, endpoint, authentication mode;
 * 2. form: name / password / "what to publish";
 * 3. resolve authentication:
 *    - invitation token → sent as-is in the `join`;
 *    - `authServer` (e.g. YunoHost) → the password is exchanged for a token;
 *    - otherwise → the Galène password is sent in the `join`;
 * 4. a complete [JoinRequest] is emitted on [join] for the room screen.
 *
 * If the server refuses the join, the room screen comes back here and [onJoinFailed]
 * shows the reason (or asks for a name if the invitation link requires one).
 */
class GroupLoginViewModel(
    private val target: GroupTarget,
    private val directory: GaleneDirectory,
    private val prefs: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        GroupLoginUiState(username = prefs.lastUsername, token = target.token)
    )
    val uiState: StateFlow<GroupLoginUiState> = _uiState.asStateFlow()

    private val _join = Channel<JoinRequest>(Channel.BUFFERED)
    val join: Flow<JoinRequest> = _join.receiveAsFlow()

    init {
        loadStatus()
    }

    fun loadStatus() {
        _uiState.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            runCatching { directory.groupStatus(target) }
                .onSuccess { status -> _uiState.update { it.copy(loading = false, status = status) } }
                .onFailure { e -> _uiState.update { it.copy(loading = false, loadError = e.toUiText()) } }
        }
    }

    fun onUsernameChange(value: String) =
        _uiState.update { it.copy(username = value, error = null, credentialsRejected = false) }
    fun onPasswordChange(value: String) =
        _uiState.update { it.copy(password = value, error = null, credentialsRejected = false) }
    fun onPresentChange(value: PresentMode) = _uiState.update { it.copy(present = value) }

    /** Resolves authentication, then emits the join request. */
    fun submit() {
        val state = _uiState.value
        val status = state.status ?: return
        if (!state.canJoin) return
        _uiState.update { it.copy(joining = true, error = null, credentialsRejected = false) }

        viewModelScope.launch {
            runCatching {
                val username = state.username.trim()
                var password: String? = state.password.ifEmpty { null }
                var token = state.token

                if (token == null && status.authServer != null) {
                    val location = status.location ?: target.location
                    when (val r = directory.authenticate(status.authServer, location, username, state.password)) {
                        is GaleneDirectory.AuthResult.Token -> { token = r.token; password = null }
                        GaleneDirectory.AuthResult.UsePassword -> Unit
                    }
                }
                if (token != null) password = null

                if (username.isNotEmpty()) prefs.lastUsername = username
                JoinRequest(
                    endpoint = status.endpoint ?: defaultEndpoint(status.location ?: target.location),
                    group = status.name,
                    title = status.title,
                    username = username.ifEmpty { null },
                    password = password,
                    token = token,
                    present = state.present,
                )
            }.onSuccess { request ->
                // The password does not stay in the screen's memory once used
                _uiState.update { it.copy(joining = false, password = "") }
                _join.trySend(request)
            }.onFailure { e ->
                _uiState.update { it.copy(joining = false, error = e.toUiText()) }
            }
        }
    }

    /** The server refused the join: show why. */
    fun onJoinFailed(failure: SignalingEvent.JoinFailed) {
        _uiState.update { s ->
            if (failure.needsUsername) {
                s.copy(usernameRequiredByToken = true, error = UiText.Res(R.string.error_token_needs_username))
            } else {
                val notAuthorised = failure.message == NOT_AUTHORISED
                // As the web client does: a refused token is dropped, back to password login
                s.copy(
                    token = null,
                    credentialsRejected = notAuthorised && s.token == null,
                    error = when {
                        notAuthorised && s.token != null -> UiText.Res(R.string.error_invalid_invitation)
                        notAuthorised -> UiText.Res(R.string.error_wrong_credentials)
                        else -> UiText.Res(
                            R.string.error_join_refused,
                            failure.message ?: failure.error ?: UiText.Res(R.string.error_unknown),
                        )
                    },
                )
            }
        }
    }

    private companion object {
        /** Galène's `group.ErrNotAuthorised`: wrong name / password, or refused token. */
        const val NOT_AUTHORISED = "not authorised"
    }

    /** Default endpoint when the status gives none: `wss://host/ws`, as the web client does. */
    private fun defaultEndpoint(location: String): String {
        val url = location.toHttpUrlOrNull() ?: return location
        val scheme = if (url.isHttps) "wss" else "ws"
        val port = if (url.port == okhttp3.HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        return "$scheme://${url.host}$port/ws"
    }
}
