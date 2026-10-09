package fr.antoineassocies.galene.domain.model

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Ce que l'utilisateur a saisi sur l'écran d'accueil, une fois interprété.
 */
sealed class GaleneLink {
    /** Adresse d'un serveur seul : on affichera ses groupes publics. */
    data class Server(val base: HttpUrl) : GaleneLink()

    /** Lien vers un groupe précis du serveur [base], éventuellement avec un token d'invitation. */
    data class Group(val base: HttpUrl, val target: GroupTarget) : GaleneLink()

    companion object {
        /**
         * Interprète une saisie libre :
         * - `galene.example.org` ou `https://galene.example.org` → [Server] ;
         * - `https://galene.example.org/group/salle/` → [Group] ;
         * - `https://galene.example.org/group/salle/?token=ABC` → [Group] avec token.
         *
         * Sans schéma, `https://` est supposé. Renvoie null si ce n'est pas une URL exploitable.
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

            // Les sous-groupes s'écrivent avec des / : tout ce qui suit « group » forme le nom
            val name = segments.drop(groupIdx + 1).joinToString("/")
            val token = url.queryParameter("token")?.takeIf { it.isNotBlank() }
            return Group(base, GroupTarget.of(base, name, token))
        }
    }
}

/**
 * Un groupe à rejoindre : son URL web (base de `.status` et `location` pour l'authServer)
 * et, s'il vient d'un lien d'invitation, le token associé.
 */
data class GroupTarget(
    val location: String,
    val token: String?,
) {
    /** URL du statut du groupe (`<location>.status`). */
    val statusUrl: String get() = location + ".status"

    // Le token est un secret : jamais dans les logs
    override fun toString() = "GroupTarget(location=$location, token=${if (token != null) "***" else "null"})"

    companion object {
        /** Construit la cible du groupe [name] sur le serveur [base]. */
        fun of(base: HttpUrl, name: String, token: String? = null): GroupTarget {
            val url = base.newBuilder().addPathSegment("group")
                .apply { name.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }
                .addPathSegment("")     // barre finale, comme les URL de groupe de Galène
                .build()
            return GroupTarget(url.toString(), token)
        }
    }
}
