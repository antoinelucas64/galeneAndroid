package fr.antoineassocies.galene.domain.model

/** One line of the group chat. */
data class ChatMessage(
    /** Server-side message id, used by an operator's targeted `clearchat`; may be absent. */
    val id: String?,
    /** Sender's client id; null for a message from the server itself. */
    val source: String?,
    val username: String?,
    val text: String,
    /** Milliseconds since the epoch. */
    val time: Long,
    val kind: Kind = Kind.NORMAL,
    /** Sent to us only (`dest` set), not to the whole group. */
    val isPrivate: Boolean = false,
    /** Sent by an operator. */
    val privileged: Boolean = false,
    /** Replayed from the chat history when joining, not new. */
    val history: Boolean = false,
    /** Recipient's name, for the private messages we sent. */
    val to: String? = null,
) {
    enum class Kind {
        NORMAL,
        /** IRC-style action, `/me waves` → « * Alice waves ». */
        ME,
        /** Information from the server or an operator (warning, error, removal…). */
        NOTICE,
    }
}
