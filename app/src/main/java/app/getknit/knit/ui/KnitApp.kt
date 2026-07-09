package app.getknit.knit.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshService
import app.getknit.knit.review.ReviewPrompter
import app.getknit.knit.ui.blocked.BlockedUsersScreen
import app.getknit.knit.ui.chat.ChatScreen
import app.getknit.knit.ui.chatlist.ChatListScreen
import app.getknit.knit.ui.contacts.ContactsScreen
import app.getknit.knit.ui.diagnostics.DiagnosticsScreen
import app.getknit.knit.ui.donate.DonateScreen
import app.getknit.knit.ui.group.GroupDetailsScreen
import app.getknit.knit.ui.onboarding.OnboardingScreen
import app.getknit.knit.ui.profile.ProfileDetailsScreen
import app.getknit.knit.ui.profile.ProfileScreen
import app.getknit.knit.ui.requests.MessageRequestsScreen
import app.getknit.knit.ui.share.ShareInbox
import app.getknit.knit.ui.share.ShareTargetScreen
import app.getknit.knit.ui.verify.VerifyContactScreen
import org.koin.compose.koinInject

private object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT_LIST = "chatlist"
    const val CONTACTS = "contacts"
    const val PROFILE = "profile"
    const val DIAGNOSTICS = "diagnostics"
    const val BLOCKED_USERS = "blocked"
    const val MESSAGE_REQUESTS = "requests"
    const val DONATE = "donate"
    const val VERIFY = "verify"
    const val SHARE = "share"
    const val CHAT = "chat/{conversationId}"

    fun chat(conversationId: String) = "chat/$conversationId"

    const val PROFILE_DETAILS = "profileDetails/{nodeId}"

    fun profileDetails(nodeId: String) = "profileDetails/$nodeId"

    const val GROUP_DETAILS = "groupDetails/{groupId}"

    fun groupDetails(groupId: String) = "groupDetails/$groupId"
}

/**
 * App root: gates on permissions, then hosts the screen graph (chat list ⇄ contacts ⇄ chat ⇄ profile)
 * with Navigation Compose. The chat route carries a `conversationId` — the "Nearby" broadcast room, a
 * peer's node id for a 1:1 DM, or a group id. Starts the mesh foreground service once past onboarding.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KnitApp(startRoute: String? = null) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val shareInbox = koinInject<ShareInbox>()
    val pendingShare by shareInbox.pending.collectAsStateWithLifecycle()
    val routeInbox = koinInject<RouteInbox>()
    val pendingRoute by routeInbox.pending.collectAsStateWithLifecycle()
    // Past onboarding once mesh permissions are granted (demo builds skip the gate).
    val onboarded = BuildConfig.SEED_DEMO || hasAllMeshPermissions(context)
    // Demo-screenshot mode skips the permission gate (and an optional [startRoute] jumps straight to a
    // screen for deterministic capture); otherwise gate on permissions as usual.
    val start =
        startRoute
            ?: if (onboarded) Routes.CHAT_LIST else Routes.ONBOARDING

    // Start the mesh service whenever the user is past onboarding (guard kept broad on purpose). Demo
    // builds never start it — there is no real mesh and the seeded data needs no transport.
    val backStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(backStackEntry?.destination?.route) {
        val route = backStackEntry?.destination?.route
        if (!BuildConfig.SEED_DEMO && route != null && route != Routes.ONBOARDING) MeshService.start(context)
    }

    // Nudge the mesh to rescan / re-advertise whenever the app returns to the foreground, so it
    // recovers quickly after another app (e.g. Quick Share) briefly seized the Nearby radios. heal()
    // no-ops when the mesh isn't running, so this is safe before onboarding; demo builds skip it.
    if (!BuildConfig.SEED_DEMO) {
        val meshManager = koinInject<MeshController>()
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) meshManager.heal()
                }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    // A share arrived (cold start: pending at first composition; warm start: onNewIntent flips it).
    // Open the target picker over the chat list — so Back/abandon returns there. A share that lands
    // before onboarding is dropped rather than left to leak into a later chat. launchSingleTop keeps
    // the cold-start navigate from stacking a second picker.
    LaunchedEffect(pendingShare != null) {
        if (pendingShare == null) return@LaunchedEffect
        if (!onboarded) {
            shareInbox.clear()
            return@LaunchedEffect
        }
        navController.navigate(Routes.SHARE) { launchSingleTop = true }
    }

    // A notification tap deep-links to a thread (cold start: pending at first composition; warm start:
    // onNewIntent flips it). The chat opens over the chat list (the start destination), so Back returns
    // there; launchSingleTop avoids stacking a duplicate when the thread is already on top. A deep-link
    // that lands before onboarding is dropped. Re-fires even while the app is foregrounded in another chat.
    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        if (!onboarded) {
            routeInbox.clear()
            return@LaunchedEffect
        }
        navController.navigate(route) { launchSingleTop = true }
        routeInbox.consume()
    }

    NavHost(
        navController = navController,
        startDestination = start,
        // Surface Compose testTags as uiautomator resource-ids across the whole screen graph, so an
        // automation agent can locate elements (send button, message input, conversation rows) by a
        // stable id instead of pixel bounds. Set once at the root; the whole subtree inherits it.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
    ) {
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
            // Play in-app review: evaluated on each landing on the chat list — including returning from
            // a thread, right after the mesh visibly worked — and never mid-conversation. ReviewPrompter
            // self-gates (Play installer, engagement policy, once per process) and swallows Play API
            // failures, so this is a no-op on de-googled phones and sideloads; demo builds skip it.
            if (!BuildConfig.SEED_DEMO) {
                val activity = LocalActivity.current
                val reviewPrompter = koinInject<ReviewPrompter>()
                LaunchedEffect(Unit) { activity?.let { reviewPrompter.maybePrompt(it) } }
            }
            ChatListScreen(
                onOpenConversation = { id -> navController.navigate(Routes.chat(id)) },
                onNewMessage = { navController.navigate(Routes.CONTACTS) },
                onOpenProfile = { navController.navigate(Routes.PROFILE) },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onOpenBlockedUsers = { navController.navigate(Routes.BLOCKED_USERS) },
                onOpenMessageRequests = { navController.navigate(Routes.MESSAGE_REQUESTS) },
                onOpenDonate = { navController.navigate(Routes.DONATE) },
                onOpenVerify = { navController.navigate(Routes.VERIFY) },
            )
        }
        composable(Routes.CONTACTS) {
            ContactsScreen(
                onBack = { navController.popBackStack() },
                // Open the chosen conversation (a peer's node id for a DM, or a freshly created group's
                // id) and drop the picker from the back stack, so Back from the chat returns to the list.
                onPick = { conversationId ->
                    navController.navigate(Routes.chat(conversationId)) {
                        popUpTo(Routes.CONTACTS) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.SHARE) {
            ShareTargetScreen(
                // Abandoning the share clears the inbox so it can't prefill a later chat; the picker
                // always sits over the chat list, so popping returns there.
                onBack = {
                    shareInbox.clear()
                    navController.popBackStack()
                },
                // Open the chosen conversation and drop the picker; ChatScreen drains the inbox into
                // its draft on arrival.
                onPick = { conversationId ->
                    navController.navigate(Routes.chat(conversationId)) {
                        popUpTo(Routes.SHARE) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
        ) { backStackEntry ->
            // conversationId is the Nearby room, a peer's node id (a 1:1 DM), or a group id.
            val conversationId =
                backStackEntry.arguments?.getString("conversationId") ?: Conversations.NEARBY
            ChatScreen(
                conversationId = conversationId,
                onBack = { navController.popBackStack() },
                onOpenProfile = { id -> navController.navigate(Routes.profileDetails(id)) },
                onOpenGroupDetails = { id -> navController.navigate(Routes.groupDetails(id)) },
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
                    // Nearby, groups, and DMs all share the chat/{conversationId} destination, so
                    // launchSingleTop here would reuse whatever chat sits under this profile — its
                    // retained ChatViewModel is still bound to that conversation — instead of opening
                    // the peer's DM (the reported "Message just returns to Nearby" bug). If we arrived
                    // straight from this peer's own DM, just return to it so we don't stack a duplicate;
                    // otherwise open it, replacing the profile so Back lands on the chat we came from.
                    val parent = navController.previousBackStackEntry
                    val fromSameDm =
                        parent?.destination?.route == Routes.CHAT &&
                            parent.arguments?.getString("conversationId") == id
                    if (fromSameDm) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Routes.chat(id)) {
                            popUpTo(Routes.PROFILE_DETAILS) { inclusive = true }
                        }
                    }
                },
            )
        }
        composable(
            route = Routes.GROUP_DETAILS,
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
            GroupDetailsScreen(
                groupId = groupId,
                onBack = { navController.popBackStack() },
                onOpenMemberProfile = { id -> navController.navigate(Routes.profileDetails(id)) },
                // Leaving deletes the thread, so pop past this screen AND the chat, back to the list.
                onLeft = { navController.popBackStack(Routes.CHAT_LIST, inclusive = false) },
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
        composable(Routes.MESSAGE_REQUESTS) {
            MessageRequestsScreen(
                onBack = { navController.popBackStack() },
                // Tapping a request's avatar opens the sender's profile; its Message action accepts the
                // request and opens the DM (see ProfileDetailsScreen.onMessage).
                onOpenProfile = { navController.navigate(Routes.profileDetails(it)) },
            )
        }
        composable(Routes.DONATE) {
            DonateScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.VERIFY) {
            VerifyContactScreen(onBack = { navController.popBackStack() })
        }
    }
}
