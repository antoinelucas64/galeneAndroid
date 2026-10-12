package fr.antoineassocies.galene.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.antoineassocies.galene.R
import fr.antoineassocies.galene.domain.LocalizedIOException

/**
 * Text destined for the user, resolved in the phone's language only when displayed.
 *
 * ViewModels hold no Context: they keep a resource id (+ arguments) and the screen
 * resolves it, so a language change applies without restarting the call.
 */
sealed interface UiText {
    /** Text coming from outside (server message, exception detail): shown as-is. */
    data class Raw(val text: String) : UiText

    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    @Composable
    fun asString(): String = when (this) {
        is Raw -> text
        is Res -> stringResource(id, *args.map { if (it is UiText) it.asString() else it }.toTypedArray())
    }
}

/** User-facing description of a failure: translated if the domain layer provided a resource. */
fun Throwable.toUiText(): UiText = when (this) {
    is LocalizedIOException -> UiText.Res(messageRes, args)
    else -> message?.let { UiText.Raw(it) } ?: UiText.Res(R.string.error_unknown)
}
