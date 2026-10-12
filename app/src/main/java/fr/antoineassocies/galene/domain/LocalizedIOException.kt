package fr.antoineassocies.galene.domain

import androidx.annotation.StringRes
import java.io.IOException

/**
 * [IOException] whose explanation is a string resource, translated by the UI
 * (see `ui.UiText`). [getMessage] stays in English for logs.
 */
class LocalizedIOException(
    @StringRes val messageRes: Int,
    val args: List<Any> = emptyList(),
    logMessage: String,
    cause: Throwable? = null,
) : IOException(logMessage, cause)
