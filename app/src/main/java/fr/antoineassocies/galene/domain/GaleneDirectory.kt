package fr.antoineassocies.galene.domain

import fr.antoineassocies.galene.domain.model.GroupStatus
import fr.antoineassocies.galene.domain.model.GroupTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * HTTP access to a Galène server's public information, before any WebSocket connection.
 *
 * Mirrors what the web client does: list of public groups (landing page), status of a
 * group (group page), and the exchange with an authentication server.
 * Every function runs on [Dispatchers.IO] and throws [IOException] with a message
 * meant for the user.
 */
class GaleneDirectory(
    private val http: OkHttpClient,
    private val json: Json,
) {

    /** Groups declared public (`public = true` in their configuration). */
    suspend fun publicGroups(server: HttpUrl): List<GroupStatus> = withContext(Dispatchers.IO) {
        val url = server.resolve("/public-groups.json") ?: throw IOException("Adresse invalide")
        val body = get(url.toString(), notFound = "Ce serveur ne publie pas de liste de groupes")
        json.decodeFromString(ListSerializer(GroupStatus.serializer()), body)
    }

    /**
     * Status of a group. Recent servers serve `.status`; older ones `.status.json`,
     * which is tried second.
     */
    suspend fun groupStatus(target: GroupTarget): GroupStatus = withContext(Dispatchers.IO) {
        val body = runCatching { get(target.statusUrl, notFound = NO_SUCH_GROUP) }
            .recoverCatching { first ->
                if (first.message != NO_SUCH_GROUP) throw first
                get(target.statusUrl + ".json", notFound = NO_SUCH_GROUP)
            }
            .getOrThrow()
        json.decodeFromString(GroupStatus.serializer(), body)
    }

    /** Result of the exchange with an authentication server. */
    sealed class AuthResult {
        /** Token (JWT) to present in the `join` instead of the password. */
        data class Token(val token: String) : AuthResult() {
            override fun toString() = "Token(***)"
        }
        /** The server issues no token (HTTP 204): send the password as-is. */
        data object UsePassword : AuthResult()
    }

    /**
     * Checks [username]/[password] with [authServer] (e.g. YunoHost accounts), using the
     * web client's protocol: POST JSON `{location, username, password}`.
     */
    suspend fun authenticate(
        authServer: String,
        location: String,
        username: String,
        password: String,
    ): AuthResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("location", location)
            put("username", username)
            put("password", password)
        }.toString()
        val request = Request.Builder()
            .url(authServer)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { r ->
            when {
                r.code == 401 || r.code == 403 -> throw IOException("Nom d'utilisateur ou mot de passe incorrect")
                !r.isSuccessful -> throw IOException("Le serveur d'authentification a répondu ${r.code}")
                r.code == 204 -> AuthResult.UsePassword
                r.body?.contentType()?.let { "${it.type}/${it.subtype}" }?.lowercase() == "application/jwt" -> {
                    val token = r.body?.string().orEmpty().trim()
                    if (token.isEmpty()) throw IOException("Le serveur d'authentification a renvoyé un jeton vide")
                    AuthResult.Token(token)
                }
                else -> throw IOException("Réponse inattendue du serveur d'authentification")
            }
        }
    }

    private fun get(url: String, notFound: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw IOException("Serveur injoignable : ${e.message}", e)
        }
        return response.use { r ->
            when {
                r.code == 404 -> throw IOException(notFound)
                !r.isSuccessful -> throw IOException("Le serveur a répondu ${r.code}")
                else -> r.body?.string() ?: throw IOException("Réponse vide du serveur")
            }
        }
    }

    private companion object {
        const val NO_SUCH_GROUP = "Ce groupe n'existe pas"
    }
}
