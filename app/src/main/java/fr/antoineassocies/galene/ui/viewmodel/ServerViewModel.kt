package fr.antoineassocies.galene.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.antoineassocies.galene.data.UserPreferences
import fr.antoineassocies.galene.domain.GaleneDirectory
import fr.antoineassocies.galene.domain.model.GaleneLink
import fr.antoineassocies.galene.domain.model.GroupStatus
import fr.antoineassocies.galene.domain.model.GroupTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

/** State of the home screen (server and group choice). */
data class ServerUiState(
    val input: String = "",
    val loading: Boolean = false,
    /** Server whose public groups are shown. */
    val server: HttpUrl? = null,
    /** null until the list is loaded; empty if the server publishes none. */
    val groups: List<GroupStatus>? = null,
    val error: String? = null,
)

/**
 * Home screen, the equivalent of the Galène web client's landing page: type a server
 * (→ list of its public groups) or paste a group / invitation link directly
 * (→ the group's login screen).
 *
 * The last server used is remembered and its groups reloaded on start.
 */
class ServerViewModel(
    private val directory: GaleneDirectory,
    private val prefs: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerUiState(input = prefs.lastServer))
    val uiState: StateFlow<ServerUiState> = _uiState.asStateFlow()

    // Navigation event consumed once (not state: no replay after rotation)
    private val _openGroup = Channel<GroupTarget>(Channel.BUFFERED)
    val openGroup: Flow<GroupTarget> = _openGroup.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        if (prefs.lastServer.isNotEmpty()) submit()
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value, error = null) }
    }

    /** Validates the input: a server shows its groups, a group link opens that group directly. */
    fun submit() {
        when (val link = GaleneLink.parse(_uiState.value.input)) {
            null -> _uiState.update { it.copy(error = "Adresse invalide") }
            is GaleneLink.Group -> {
                remember(link.base)
                _openGroup.trySend(link.target)
            }
            is GaleneLink.Server -> loadGroups(link.base)
        }
    }

    /** Opens a group from the public list. */
    fun openGroup(group: GroupStatus) {
        val server = _uiState.value.server ?: return
        _openGroup.trySend(GroupTarget(group.location ?: GroupTarget.of(server, group.name).location, token = null))
    }

    /** Opens a non-public group by name. */
    fun openGroupByName(name: String) {
        val server = _uiState.value.server ?: return
        val trimmed = name.trim().trim('/')
        if (trimmed.isNotEmpty()) _openGroup.trySend(GroupTarget.of(server, trimmed))
    }

    private fun loadGroups(server: HttpUrl) {
        loadJob?.cancel()
        _uiState.update { it.copy(loading = true, error = null, server = server, groups = null) }
        loadJob = viewModelScope.launch {
            runCatching { directory.publicGroups(server) }
                .onSuccess { groups ->
                    remember(server)
                    _uiState.update { it.copy(loading = false, groups = groups.sortedBy { g -> g.title.lowercase() }) }
                }
                .onFailure { e ->
                    // The server may exist without publishing a list: keep the group-name field available
                    _uiState.update { it.copy(loading = false, groups = emptyList(), error = e.message) }
                }
        }
    }

    /** Remembers the server (without path or token) for next time. */
    private fun remember(base: HttpUrl) {
        prefs.lastServer = base.toString().removeSuffix("/")
    }
}
