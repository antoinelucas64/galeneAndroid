package fr.antoineassocies.galene.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fr.antoineassocies.galene.R
import fr.antoineassocies.galene.domain.model.JoinRequest

private data class InvitationParams(
    val serverUrl: String,
    val roomName: String,
    val token: String,
)

/**
 * Extrait serveur, salle et token d'un lien d'invitation Galène,
 * ex. `https://galene.example.org/group/ma-salle/?token=ABCDEF` → `wss://galene.example.org/ws`.
 * Renvoie null si le lien n'a pas cette forme.
 */
private fun parseInvitationUrl(raw: String): InvitationParams? {
    // https://galene.example.org/group/ma-salle/?token=ABCDEF
    return runCatching {
        val uri = Uri.parse(raw.trim())
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        val segments = uri.pathSegments
        val groupIdx = segments.indexOf("group")
        if (groupIdx < 0) return null
        val roomName = segments.getOrNull(groupIdx + 1)?.takeIf { it.isNotEmpty() } ?: return null
        val token = uri.getQueryParameter("token")?.takeIf { it.isNotEmpty() } ?: return null
        val scheme = if (uri.scheme == "https") "wss" else "ws"
        val port = if (uri.port != -1) ":${uri.port}" else ""
        InvitationParams(serverUrl = "$scheme://$host$port/ws", roomName = roomName, token = token)
    }.getOrNull()
}

/**
 * Écran d'accueil : saisie des paramètres de connexion, ou collage d'un lien d'invitation
 * qui remplit automatiquement les champs.
 */
@Composable
fun ConnectScreen(
    onJoin: (JoinRequest) -> Unit
) {
    var inviteLink   by remember { mutableStateOf("") }
    var serverUrl    by remember { mutableStateOf("wss://") }
    var roomName     by remember { mutableStateOf("") }
    var displayName  by remember { mutableStateOf("") }
    var password     by remember { mutableStateOf("") }
    var token        by remember { mutableStateOf("") }
    var linkError    by remember { mutableStateOf(false) }

    // Auto-fill quand un lien d'invitation valide est collé
    LaunchedEffect(inviteLink) {
        if (inviteLink.startsWith("http")) {
            val parsed = parseInvitationUrl(inviteLink)
            if (parsed != null) {
                serverUrl = parsed.serverUrl
                roomName  = parsed.roomName
                token     = parsed.token
                password  = ""
                linkError = false
            } else {
                linkError = true
            }
        } else {
            linkError = false
        }
    }

    val canJoin = serverUrl.isNotBlank() && roomName.isNotBlank() && displayName.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(4.dp))

        // ─── Lien d'invitation ──────────────────────────────────────────
        OutlinedTextField(
            value = inviteLink,
            onValueChange = { inviteLink = it },
            label = { Text(stringResource(R.string.label_invite_link)) },
            placeholder = { Text("https://galene.example.org/group/salle/?token=…") },
            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
            isError = linkError,
            supportingText = if (linkError) {
                { Text("Lien invalide — remplissez les champs manuellement") }
            } else if (token.isNotBlank()) {
                { Text("Token extrait ✓", color = MaterialTheme.colorScheme.primary) }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ─── Champs manuels ─────────────────────────────────────────────
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it; token = "" },
            label = { Text(stringResource(R.string.label_server_url)) },
            placeholder = { Text("wss://galene.example.org/ws") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = roomName,
            onValueChange = { roomName = it },
            label = { Text(stringResource(R.string.label_room)) },
            placeholder = { Text("ma-salle") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(stringResource(R.string.label_display_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Token (prioritaire sur password si renseigné)
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; password = "" },
            label = { Text("Token d'invitation") },
            placeholder = { Text("ABCDEF") },
            supportingText = { Text("Laisser vide si connexion par mot de passe") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Mot de passe (uniquement si pas de token)
        if (token.isBlank()) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.label_password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                onJoin(
                    JoinRequest(
                        serverUrl   = serverUrl.trim(),
                        roomName    = roomName.trim(),
                        displayName = displayName.trim(),
                        password    = password.ifBlank { null },
                        token       = token.trim().ifBlank { null },
                    )
                )
            },
            enabled = canJoin,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(stringResource(R.string.action_join))
        }
    }
}
