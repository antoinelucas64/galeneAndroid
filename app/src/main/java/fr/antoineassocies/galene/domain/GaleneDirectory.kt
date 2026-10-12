package fr.antoineassocies.galene.domain

import androidx.annotation.StringRes
import fr.antoineassocies.galene.R
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
 * Every function runs on [Dispatchers.IO] and throws [LocalizedIOException], whose
 * message the UI shows in the user's language.
 */
class GaleneDirectory(
    private val http: OkHttpClient,
    private val json: Json,
) {

    /** Groups declared public (`public = true` in their configuration). */
    suspend fun publicGroups(server: HttpUrl): List<GroupStatus> = withContext(Dispatchers.IO) {
        val url = server.resolve("/public-groups.json") ?: throw error(R.string.error_invalid_address, "Invalid address")
        val body = get(url.toString(), notFound = R.string.error_no_public_list)
        json.decodeFromString(ListSerializer(GroupStatus.serializer()), body)
    }

    /**
     * Status of a group. Recent servers serve `.status`; older ones `.status.json`,
     * which is tried second.
     */
    suspend fun groupStatus(target: GroupTarget): GroupStatus = withContext(Dispatchers.IO) {
        val body = runCatching { get(target.statusUrl, notFound = R.string.error_no_such_group) }
            .recoverCatching { first ->
                if ((first as? LocalizedIOException)?.messageRes != R.string.error_no_such_group) throw first
                get(target.statusUrl + ".json", notFound = R.string.error_no_such_group)
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
                r.code == 401 || r.code == 403 -> throw error(R.string.error_bad_credentials, "Bad credentials")
                !r.isSuccessful -> throw error(R.string.error_auth_server_http, "Auth server HTTP ${r.code}", r.code)
                r.code == 204 -> AuthResult.UsePassword
                r.body?.contentType()?.let { "${it.type}/${it.subtype}" }?.lowercase() == "application/jwt" -> {
                    val token = r.body?.string().orEmpty().trim()
                    if (token.isEmpty()) throw error(R.string.error_auth_empty_token, "Empty token")
                    AuthResult.Token(token)
                }
                else -> throw error(R.string.error_auth_unexpected, "Unexpected auth server response")
            }
        }
    }

    private fun get(url: String, @StringRes notFound: Int): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw LocalizedIOException(
                R.string.error_unreachable, listOf(e.message ?: e.javaClass.simpleName), "Unreachable: ${e.message}", e
            )
        }
        return response.use { r ->
            when {
                r.code == 404 -> throw error(notFound, "HTTP 404 $url")
                !r.isSuccessful -> throw error(R.string.error_server_http, "HTTP ${r.code} $url", r.code)
                else -> r.body?.string() ?: throw error(R.string.error_empty_response, "Empty body $url")
            }
        }
    }

    private fun error(@StringRes res: Int, log: String, vararg args: Any) =
        LocalizedIOException(res, args.toList(), log)
}
