package app.getknit.knit.ui

import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.getknit.knit.mesh.MeshService
import app.getknit.knit.ui.chat.ChatScreen
import app.getknit.knit.ui.onboarding.OnboardingScreen
import app.getknit.knit.ui.profile.ProfileScreen

private enum class Screen { Onboarding, Chat, Profile }

/**
 * App root: gates on permissions, then hosts a lightweight screen stack (chat ⇄ profile). Starts the
 * mesh foreground service once the user is past onboarding. Navigation is intentionally simple; it
 * can move to Navigation Compose when 1:1 DM threads are added.
 */
@Composable
fun KnitApp() {
    val context = LocalContext.current
    var screen by remember {
        mutableStateOf(if (hasAllMeshPermissions(context)) Screen.Chat else Screen.Onboarding)
    }

    LaunchedEffect(screen) {
        if (screen != Screen.Onboarding) MeshService.start(context)
    }

    Crossfade(targetState = screen, label = "screen") { current ->
        when (current) {
            Screen.Onboarding -> OnboardingScreen(onReady = { screen = Screen.Chat })
            Screen.Chat -> ChatScreen(onOpenProfile = { screen = Screen.Profile })
            Screen.Profile -> ProfileScreen(onBack = { screen = Screen.Chat })
        }
    }
}
