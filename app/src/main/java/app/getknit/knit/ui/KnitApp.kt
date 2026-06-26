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
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.MeshService
import app.getknit.knit.ui.blocked.BlockedUsersScreen
import app.getknit.knit.ui.chat.ChatScreen
import app.getknit.knit.ui.chatlist.ChatListScreen
import app.getknit.knit.ui.contacts.ContactsScreen
import app.getknit.knit.ui.diagnostics.DiagnosticsScreen
import app.getknit.knit.ui.onboarding.OnboardingScreen
import app.getknit.knit.ui.profile.ProfileDetailsScreen
import app.getknit.knit.ui.profile.ProfileScreen

private object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT_LIST = "chatlist"
    const val CONTACTS = "contacts"
    const val PROFILE = "profile"
    const val DIAGNOSTICS = "diagnostics"
    const val BLOCKED_USERS = "blocked"
    const val CHAT = "chat/{conversationId}"
    fun chat(conversationId: String) = "chat/$conversationId"
    const val PROFILE_DETAILS = "profileDetails/{nodeId}"
    fun profileDetails(nodeId: String) = "profileDetails/$nodeId"
}

/**
 * App root: gates on permissions, then hosts the screen graph (chat list ⇄ contacts ⇄ chat ⇄ profile)
 * with Navigation Compose. The chat route carries a `conversationId` — the "Nearby" broadcast room or
 * a peer's node id for a 1:1 DM. Starts the mesh foreground service once the user is past onboarding.
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
                onNewMessage = { navController.navigate(Routes.CONTACTS) },
                onOpenProfile = { navController.navigate(Routes.PROFILE) },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onOpenBlockedUsers = { navController.navigate(Routes.BLOCKED_USERS) },
            )
        }
        composable(Routes.CONTACTS) {
            ContactsScreen(
                onBack = { navController.popBackStack() },
                // Open the DM thread (keyed by the peer's node id) and drop the picker from the back
                // stack, so Back from the chat returns to the conversation list.
                onPick = { peerId ->
                    navController.navigate(Routes.chat(peerId)) {
                        popUpTo(Routes.CONTACTS) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) { backStackEntry ->
            // conversationId is the Nearby room or a peer's node id (a 1:1 DM thread).
            val conversationId =
                backStackEntry.arguments?.getString("conversationId") ?: Conversations.NEARBY
            ChatScreen(
                conversationId = conversationId,
                onBack = { navController.popBackStack() },
                onOpenProfile = { id -> navController.navigate(Routes.profileDetails(id)) },
            )
        }
        composable(
            route = Routes.PROFILE_DETAILS,
            arguments = listOf(navArgument("nodeId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val nodeId = backStackEntry.arguments?.getString("nodeId") ?: return@composable
            ProfileDetailsScreen(
                nodeId = nodeId,
                onBack = { navController.popBackStack() },
                onMessage = { id ->
                    navController.navigate(Routes.chat(id)) {
                        // Replace this details screen so Back from the DM returns to the chat you came
                        // from, and don't stack a duplicate DM if it was opened from that same thread.
                        popUpTo(Routes.PROFILE_DETAILS) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.PROFILE) {
            ProfileScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.BLOCKED_USERS) {
            BlockedUsersScreen(onBack = { navController.popBackStack() })
        }
    }
}
