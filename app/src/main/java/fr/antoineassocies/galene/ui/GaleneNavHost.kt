package fr.antoineassocies.galene.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import fr.antoineassocies.galene.domain.SignalingEvent
import fr.antoineassocies.galene.domain.model.GroupTarget
import fr.antoineassocies.galene.domain.model.JoinRequest
import fr.antoineassocies.galene.ui.screens.GroupLoginScreen
import fr.antoineassocies.galene.ui.screens.RoomScreen
import fr.antoineassocies.galene.ui.screens.ServerScreen

object Routes {
    const val SERVER = "server"
    const val GROUP  = "group"
    const val ROOM   = "room"
}

/**
 * Porte les données qui passent d'un écran à l'autre, à l'échelle de l'Activity.
 *
 * Rien ne passe par les routes : les tokens et mots de passe n'ont rien à faire dans
 * l'état sauvegardé du back stack, et un argument vide ne matche pas une route.
 * Survit aux changements de configuration, pas à la mort du process (voulu : aucun
 * secret n'est persisté, et l'appel ne survit de toute façon pas à la mort du process).
 */
class NavigationHolder : ViewModel() {
    /** Groupe choisi sur l'écran d'accueil (avec son éventuel token d'invitation). */
    var target: GroupTarget? = null

    /** Demande d'entrée complète, authentification résolue. */
    var request: JoinRequest? = null

    /** Refus du serveur, à afficher une fois sur le formulaire du groupe. */
    var joinFailure by mutableStateOf<SignalingEvent.JoinFailed?>(null)
}

/**
 * Navigation de l'app, calquée sur le client web Galène :
 * accueil (serveur et groupes publics) → page du groupe (connexion) → salle.
 *
 * Un refus du serveur dans la salle ramène au formulaire du groupe avec la raison.
 */
@Composable
fun GaleneNavHost() {
    val navController = rememberNavController()
    val holder: NavigationHolder = viewModel()

    NavHost(navController = navController, startDestination = Routes.SERVER) {

        composable(Routes.SERVER) {
            ServerScreen(
                onOpenGroup = { target ->
                    holder.target = target
                    navController.navigate(Routes.GROUP)
                }
            )
        }

        composable(Routes.GROUP) {
            val target = holder.target
            if (target == null) {
                // Process recréé sans les paramètres : retour à l'accueil
                LaunchedEffect(Unit) { navController.popBackStack(Routes.SERVER, inclusive = false) }
            } else {
                GroupLoginScreen(
                    target = target,
                    pendingFailure = holder.joinFailure,
                    onFailureShown = { holder.joinFailure = null },
                    onJoin = { request ->
                        holder.request = request
                        navController.navigate(Routes.ROOM)
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(Routes.ROOM) {
            val request = holder.request
            if (request == null) {
                LaunchedEffect(Unit) { navController.popBackStack(Routes.SERVER, inclusive = false) }
            } else {
                RoomScreen(
                    request = request,
                    onLeave = {
                        holder.request = null
                        navController.popBackStack()
                    },
                    onJoinFailed = { failure ->
                        holder.request = null
                        holder.joinFailure = failure
                        navController.popBackStack()
                    },
                )
            }
        }
    }
}
