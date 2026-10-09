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
 * Carries data from one screen to the next, scoped to the Activity.
 *
 * Nothing goes through routes: tokens and passwords have no business in the back stack's
 * saved state, and an empty argument does not match a route.
 * Survives configuration changes, not process death (on purpose: no secret is persisted,
 * and the call would not survive process death anyway).
 */
class NavigationHolder : ViewModel() {
    /** Group picked on the home screen (with its invitation token, if any). */
    var target: GroupTarget? = null

    /** Complete join request, authentication resolved. */
    var request: JoinRequest? = null

    /** Server refusal, to be shown once on the group's login form. */
    var joinFailure by mutableStateOf<SignalingEvent.JoinFailed?>(null)
}

/**
 * App navigation, modelled on Galène's web client:
 * home (server and public groups) → group page (login) → room.
 *
 * A server refusal in the room goes back to the group's login form with the reason.
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
                // Process recreated without parameters: back to the home screen
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
