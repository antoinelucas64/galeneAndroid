package fr.antoineassocies.galene.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.os.Build

private val LightColors = lightColorScheme(
    primary = GalenePrimary,
    onPrimary = GaleneOnPrimary,
    primaryContainer = GalenePrimaryContainer,
    secondary = GaleneSecondary,
    background = GaleneBackground,
    surface = GaleneSurface,
)

private val DarkColors = darkColorScheme(
    primary = GalenePrimaryDark,
    onPrimary = GaleneOnPrimaryDark,
    primaryContainer = GalenePrimaryContainerDark,
    secondary = GaleneSecondaryDark,
)

@Composable
fun GaleneTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Dynamic color (Android 12+) — respecte le thème système sans télémétrie
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = GaleneTypography,
        content = content
    )
}
