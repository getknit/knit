package app.getknit.knit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.getknit.knit.mesh.MeshService
import app.getknit.knit.ui.chat.ChatScreen
import app.getknit.knit.ui.chatlist.ChatListScreen
import app.getknit.knit.ui.onboarding.OnboardingScreen
import app.getknit.knit.ui.profile.ProfileScreen

private object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT_LIST = "chatlist"
    const val PROFILE = "profile"
    const val CHAT = "chat/{conversationId}"
    fun chat(conversationId: String) = "chat/$conversationId"
}

/**
 * App root: gates on permissions, then hosts the screen graph (chat list ⇄ chat ⇄ profile) with
 * Navigation Compose. The chat route carries a `conversationId` so 1:1 DM threads slot in later;
 * today the only value is the "Nearby" broadcast room. Starts the mesh foreground service once the
 * user is past onboarding.
 */
@Composable
fun KnitApp() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val start = if (hasAllMeshPermissions(context)) Routes.CHAT_LIST else Routes.ONBOARDING

    // Start the mesh service whenever the user is past onboarding (guard kept broad on purpose).
    val backStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(backStackEntry?.destination?.route) {
        val route = backStackEntry?.destination?.route
        if (route != null && route != Routes.ONBOARDING) MeshService.start(context)
    }

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onReady = {
                    navController.navigate(Routes.CHAT_LIST) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.CHAT_LIST) {
            ChatListScreen(
                onOpenConversation = { id -> navController.navigate(Routes.chat(id)) },
                onOpenProfile = { navController.navigate(Routes.PROFILE) },
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) {
            // conversationId (currently always the Nearby room) will route ChatViewModel to a specific
            // DM thread once DMs land; for now the chat always shows the broadcast room.
            ChatScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PROFILE) {
            ProfileScreen(onBack = { navController.popBackStack() })
        }
    }
}
