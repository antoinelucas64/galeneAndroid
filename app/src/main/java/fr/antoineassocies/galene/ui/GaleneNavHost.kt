package fr.antoineassocies.galene.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import fr.antoineassocies.galene.domain.model.JoinRequest
import fr.antoineassocies.galene.ui.screens.ConnectScreen
import fr.antoineassocies.galene.ui.screens.RoomScreen

object Routes {
    const val CONNECT = "connect"
    const val ROOM    = "room"
}

/**
 * Porte la [JoinRequest] entre ConnectScreen et RoomScreen, à l'échelle de l'Activity.
 *
 * Les paramètres ne passent plus par la route : un argument vide (password ou token,
 * toujours exclusifs) ne matchait pas la route et faisait crasher la navigation, et le
 * mot de passe se retrouvait dans l'état sauvegardé du back stack.
 * Survit aux changements de configuration, pas à la mort du process (voulu : rien
 * de secret n'est persisté, et l'appel ne survit de toute façon pas à la mort du process).
 */
class JoinRequestHolder : ViewModel() {
    var request: JoinRequest? = null
}

@Composable
fun GaleneNavHost() {
    val navController = rememberNavController()
    val holder: JoinRequestHolder = viewModel()

    NavHost(navController = navController, startDestination = Routes.CONNECT) {

        composable(Routes.CONNECT) {
            ConnectScreen(
                onJoin = { request ->
                    holder.request = request
                    navController.navigate(Routes.ROOM)
                }
            )
        }

        composable(Routes.ROOM) {
            val request = holder.request
            if (request == null) {
                // Process recréé sans les paramètres : retour à l'écran de connexion
                LaunchedEffect(Unit) { navController.popBackStack(Routes.CONNECT, inclusive = false) }
            } else {
                RoomScreen(
                    request = request,
                    onLeave = {
                        holder.request = null
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
