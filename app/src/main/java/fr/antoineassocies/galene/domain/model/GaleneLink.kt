package fr.antoineassocies.galene.domain.model

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What the user typed on the home screen, once interpreted.
 */
sealed class GaleneLink {
    /** A bare server address: its public groups will be listed. */
    data class Server(val base: HttpUrl) : GaleneLink()

    /** Link to a specific group on server [base], possibly with an invitation token. */
    data class Group(val base: HttpUrl, val target: GroupTarget) : GaleneLink()

    companion object {
        /**
         * Interprets free-form input:
         * - `galene.example.org` or `https://galene.example.org` → [Server];
         * - `https://galene.example.org/group/room/` → [Group];
         * - `https://galene.example.org/group/room/?token=ABC` → [Group] with a token.
         *
         * Without a scheme, `https://` is assumed. Returns null if this is not a usable URL.
         */
        fun parse(input: String): GaleneLink? {
            val raw = input.trim()
            if (raw.isEmpty()) return null
            val withScheme = if ("://" in raw) raw else "https://$raw"
            val url = withScheme.toHttpUrlOrNull() ?: return null

            val segments = url.pathSegments.filter { it.isNotEmpty() }
            val groupIdx = segments.indexOf("group")
            val base = url.newBuilder().encodedPath("/").query(null).fragment(null).build()

            if (groupIdx < 0 || groupIdx == segments.lastIndex) return Server(base)

            // Subgroups are written with slashes: everything after "group" is the name
            val name = segments.drop(groupIdx + 1).joinToString("/")
            val token = url.queryParameter("token")?.takeIf { it.isNotBlank() }
            return Group(base, GroupTarget.of(base, name, token))
        }

        private val URL_IN_TEXT = Regex("""https?://\S+""")

        /**
         * First group link found in [text], e.g. an invitation shared from a messaging app
         * ("Join me in … on Galène\nhttps://…/group/room/?token=…"). Null if there is none.
         */
        fun findGroup(text: String): Group? =
            URL_IN_TEXT.findAll(text).firstNotNullOfOrNull { parse(it.value) as? Group }
    }
}

/**
 * A group to join: its web URL (base for `.status`, and `location` for the authServer)
 * and, if it comes from an invitation link, the associated token.
 */
data class GroupTarget(
    val location: String,
    val token: String?,
) {
    /** URL of the group status (`<location>.status`). */
    val statusUrl: String get() = location + ".status"

    // The token is a secret: never in logs
    override fun toString() = "GroupTarget(location=$location, token=${if (token != null) "***" else "null"})"

    companion object {
        /** Builds the target for group [name] on server [base]. */
        fun of(base: HttpUrl, name: String, token: String? = null): GroupTarget {
            val url = base.newBuilder().addPathSegment("group")
                .apply { name.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }
                .addPathSegment("")     // trailing slash, like Galène's group URLs
                .build()
            return GroupTarget(url.toString(), token)
        }
    }
}
