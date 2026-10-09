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

/** État de l'écran d'accueil (choix du serveur et du groupe). */
data class ServerUiState(
    val input: String = "",
    val loading: Boolean = false,
    /** Serveur dont les groupes publics sont affichés. */
    val server: HttpUrl? = null,
    /** null tant que la liste n'a pas été chargée ; vide si le serveur n'en publie aucun. */
    val groups: List<GroupStatus>? = null,
    val error: String? = null,
)

/**
 * Écran d'accueil, équivalent de la page d'accueil du client web Galène :
 * on saisit un serveur (→ liste de ses groupes publics) ou on colle directement
 * un lien de groupe / d'invitation (→ écran de connexion du groupe).
 *
 * Le dernier serveur utilisé est mémorisé et ses groupes rechargés à l'ouverture.
 */
class ServerViewModel(
    private val directory: GaleneDirectory,
    private val prefs: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerUiState(input = prefs.lastServer))
    val uiState: StateFlow<ServerUiState> = _uiState.asStateFlow()

    // Événement de navigation à consommer une seule fois (pas un état : pas de rejeu après rotation)
    private val _openGroup = Channel<GroupTarget>(Channel.BUFFERED)
    val openGroup: Flow<GroupTarget> = _openGroup.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        if (prefs.lastServer.isNotEmpty()) submit()
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value, error = null) }
    }

    /** Valide la saisie : un serveur affiche ses groupes, un lien de groupe ouvre directement celui-ci. */
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

    /** Ouvre un groupe de la liste publique. */
    fun openGroup(group: GroupStatus) {
        val server = _uiState.value.server ?: return
        _openGroup.trySend(GroupTarget(group.location ?: GroupTarget.of(server, group.name).location, token = null))
    }

    /** Ouvre un groupe non public dont on connaît le nom. */
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
                    // Le serveur peut exister sans publier de liste : on garde la saisie d'un nom de groupe
                    _uiState.update { it.copy(loading = false, groups = emptyList(), error = e.message) }
                }
        }
    }

    /** Mémorise le serveur (sans chemin ni token) pour la prochaine ouverture. */
    private fun remember(base: HttpUrl) {
        prefs.lastServer = base.toString().removeSuffix("/")
    }
}
