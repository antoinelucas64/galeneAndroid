package fr.antoineassocies.galene.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.antoineassocies.galene.data.UserPreferences
import fr.antoineassocies.galene.domain.GaleneDirectory
import fr.antoineassocies.galene.domain.SignalingEvent
import fr.antoineassocies.galene.domain.model.GroupStatus
import fr.antoineassocies.galene.domain.model.GroupTarget
import fr.antoineassocies.galene.domain.model.JoinRequest
import fr.antoineassocies.galene.domain.model.PresentMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** État du formulaire de connexion à un groupe. */
data class GroupLoginUiState(
    val loading: Boolean = true,
    val status: GroupStatus? = null,
    val loadError: String? = null,
    val username: String = "",
    val password: String = "",
    val present: PresentMode = PresentMode.MIC_AND_CAMERA,
    /** Token d'invitation en cours d'usage ; abandonné après un refus, comme sur le web. */
    val token: String? = null,
    /** Le serveur a répondu `need-username` : le lien d'invitation exige un nom. */
    val usernameRequiredByToken: Boolean = false,
    val joining: Boolean = false,
    val error: String? = null,
) {
    /** Avec un token, le nom est facultatif (le lien peut le fixer) ; sinon il est obligatoire. */
    val usernameRequired: Boolean get() = token == null || usernameRequiredByToken
    val showPassword: Boolean get() = token == null

    /** Authentification par portail web (SSO) : pas encore prise en charge par l'app. */
    val portalOnly: Boolean get() = token == null && status?.authPortal != null

    val canJoin: Boolean get() =
        status != null && !joining && !portalOnly && (!usernameRequired || username.isNotBlank())
}

/**
 * Connexion à un groupe, équivalent de la page de groupe du client web Galène :
 *
 * 1. lecture du statut du groupe (`.status`) : nom, description, endpoint, mode d'authentification ;
 * 2. formulaire nom / mot de passe / « que publier » ;
 * 3. résolution de l'authentification :
 *    - token d'invitation → envoyé tel quel au `join` ;
 *    - `authServer` (ex. YunoHost) → échange du mot de passe contre un jeton ;
 *    - sinon → mot de passe Galène envoyé au `join` ;
 * 4. une [JoinRequest] complète est émise sur [join] pour l'écran de salle.
 *
 * Si le serveur refuse l'entrée, l'écran de salle revient ici et [onJoinFailed]
 * affiche la raison (ou demande un nom si le lien d'invitation l'exige).
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
                .onFailure { e -> _uiState.update { it.copy(loading = false, loadError = e.message) } }
        }
    }

    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }
    fun onPresentChange(value: PresentMode) = _uiState.update { it.copy(present = value) }

    /** Résout l'authentification puis émet la demande d'entrée en salle. */
    fun submit() {
        val state = _uiState.value
        val status = state.status ?: return
        if (!state.canJoin) return
        _uiState.update { it.copy(joining = true, error = null) }

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
                // Le mot de passe ne reste pas en mémoire de l'écran une fois utilisé
                _uiState.update { it.copy(joining = false, password = "") }
                _join.trySend(request)
            }.onFailure { e ->
                _uiState.update { it.copy(joining = false, error = e.message) }
            }
        }
    }

    /** L'écran de salle a été refusé par le serveur : on affiche pourquoi. */
    fun onJoinFailed(failure: SignalingEvent.JoinFailed) {
        _uiState.update { s ->
            if (failure.needsUsername) {
                s.copy(usernameRequiredByToken = true, error = "Ce lien d'invitation demande un nom d'utilisateur")
            } else {
                // Comme le client web : un token refusé est abandonné, on repasse au mot de passe
                s.copy(
                    token = null,
                    error = "Le serveur a refusé l'entrée : ${failure.message ?: failure.error ?: "raison inconnue"}",
                )
            }
        }
    }

    /** Endpoint par défaut quand le statut n'en donne pas : `wss://hôte/ws`, comme le client web. */
    private fun defaultEndpoint(location: String): String {
        val url = location.toHttpUrlOrNull() ?: return location
        val scheme = if (url.isHttps) "wss" else "ws"
        val port = if (url.port == okhttp3.HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        return "$scheme://${url.host}$port/ws"
    }
}
