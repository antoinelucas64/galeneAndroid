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
 * Accès HTTP aux informations publiques d'un serveur Galène, avant toute connexion WebSocket.
 *
 * Reproduit ce que fait le client web : liste des groupes publics (page d'accueil),
 * statut d'un groupe (page du groupe), et échange avec un serveur d'authentification.
 * Toutes les fonctions s'exécutent sur [Dispatchers.IO] et lèvent [IOException]
 * avec un message lisible par l'utilisateur.
 */
class GaleneDirectory(
    private val http: OkHttpClient,
    private val json: Json,
) {

    /** Groupes déclarés publics (`public = true` dans leur configuration). */
    suspend fun publicGroups(server: HttpUrl): List<GroupStatus> = withContext(Dispatchers.IO) {
        val url = server.resolve("/public-groups.json") ?: throw IOException("Adresse invalide")
        val body = get(url.toString(), notFound = "Ce serveur ne publie pas de liste de groupes")
        json.decodeFromString(ListSerializer(GroupStatus.serializer()), body)
    }

    /**
     * Statut d'un groupe. Les serveurs récents servent `.status` ; les plus anciens
     * `.status.json`, essayé en second.
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

    /** Résultat de l'échange avec un serveur d'authentification. */
    sealed class AuthResult {
        /** Jeton (JWT) à présenter au `join` à la place du mot de passe. */
        data class Token(val token: String) : AuthResult() {
            override fun toString() = "Token(***)"
        }
        /** Le serveur ne délivre pas de jeton (HTTP 204) : envoyer le mot de passe tel quel. */
        data object UsePassword : AuthResult()
    }

    /**
     * Vérifie [username]/[password] auprès de [authServer] (ex. comptes YunoHost),
     * selon le protocole du client web : POST JSON `{location, username, password}`.
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
