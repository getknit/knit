package app.getknit.knit.ui.profile

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.ui.Reach
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * The profile's call buttons beside Message: the live Call and the Mesh call each where the state offers it, each
 * placing its own kind, and a press that has to ask for the microphone placing its call once the grant lands. Its own
 * class, as the chat's `ChatCallButtonsTest` is, since both need a microphone feature.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileCallButtonsTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val placed = mutableListOf<String>()
    private lateinit var activity: Activity

    @Before
    fun aPhoneWithAMicrophone() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_MICROPHONE, true)
    }

    private fun show(
        canCall: Boolean,
        canMeshCall: Boolean,
        registry: ActivityResultRegistry? = null,
    ) {
        compose.setContent {
            activity = LocalActivity.current!!
            // The activity's own registry unless the test holds the launches itself.
            val owner =
                registry?.let { held -> RegistryOwner(held) } ?: LocalActivityResultRegistryOwner.current!!
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                KnitTheme {
                    ProfileDetailsScreenContent(
                        state =
                            ProfileDetailsUiState(
                                nodeId = "8f3a2b1c9d4e",
                                displayName = "Ada Lovelace",
                                status = "",
                                avatarHash = null,
                                reach = Reach.Direct,
                                isBlocked = false,
                                canCall = canCall,
                                canMeshCall = canMeshCall,
                            ),
                        snackbarHostState = SnackbarHostState(),
                        onBack = {},
                        onMessage = {},
                        onOpenGroup = {},
                        onScan = {},
                        onBlock = {},
                        onUnblock = {},
                        onRemoveContact = {},
                        onMarkVerified = {},
                        onClearVerification = {},
                        onCall = { placed += "live" },
                        onMeshCall = { placed += "mesh" },
                    )
                }
            }
        }
    }

    private fun assertButtons(
        live: Int,
        mesh: Int,
    ) {
        compose.onAllNodesWithTag("profile_details_call").assertCountEquals(live)
        compose.onAllNodesWithTag("profile_details_mesh_call").assertCountEquals(mesh)
    }

    @Test
    fun aPeerWhoTakesBothKindsGetsBothButtonsEachPlacingItsOwnKind() {
        // The test holds the grant the gate asks for, so a press places the call rather than asking.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        show(canCall = true, canMeshCall = true)
        assertButtons(live = 1, mesh = 1)

        compose.onNodeWithTag("profile_details_mesh_call").performClick()
        compose.onNodeWithTag("profile_details_call").performClick()
        compose.waitForIdle()
        assertEquals(listOf("mesh", "live"), placed)
    }

    @Test
    fun aPeerWhoTakesMeshCallsAloneGetsTheMeshCallOnly() {
        show(canCall = false, canMeshCall = true)
        assertButtons(live = 0, mesh = 1)
    }

    @Test
    fun aPeerWhoTakesLiveCallsAloneGetsTheLiveCallOnly() {
        show(canCall = true, canMeshCall = false)
        assertButtons(live = 1, mesh = 0)
    }

    @Test
    fun aPeerWhoTakesNeitherGetsNoCall() {
        show(canCall = false, canMeshCall = false)
        assertButtons(live = 0, mesh = 0)
    }

    @Test
    fun aCallPressedBeforeTheMicIsGrantedIsPlacedOnTheGrant() {
        show(canCall = true, canMeshCall = true)
        compose.onNodeWithTag("profile_details_mesh_call").performClick()
        compose.waitForIdle()
        // The press asked rather than placed: the call waits on the person's answer.
        assertEquals(emptyList<String>(), placed)

        answerTheMicRequest(granted = true)
        // The call the press asked for, without a second tap.
        assertEquals(listOf("mesh"), placed)
    }

    @Test
    fun aRefusedMicPlacesNoCall() {
        show(canCall = true, canMeshCall = false)
        compose.onNodeWithTag("profile_details_call").performClick()
        compose.waitForIdle()

        answerTheMicRequest(granted = false)
        // The denial's snackbar is the barrier: the result was handled, and it placed nothing.
        val denied = activity.getString(R.string.chat_call_mic_denied)
        compose.onNodeWithText(denied).assertExists()
        assertEquals(emptyList<String>(), placed)
    }

    @Test
    fun aSecondTapWhileTheDialogIsUpAsksOnceAndTheGrantPlacesTheCall() {
        // Android answers a second request while the first's dialog is up with an empty result, read as a refusal:
        // the gate must not send one, or the first tap's call is dropped behind a denial nobody gave.
        val registry = HeldRegistry()
        show(canCall = false, canMeshCall = true, registry = registry)
        compose.onNodeWithTag("profile_details_mesh_call").performClick()
        compose.onNodeWithTag("profile_details_mesh_call").performClick()
        compose.waitForIdle()
        assertEquals(1, registry.launches.size)

        registry.dispatchResult(registry.launches.single(), true)
        compose.waitForIdle()
        assertEquals(listOf("mesh"), placed)
    }

    /** Answers the system dialog the last press opened, as the person would, and lets the screen settle. */
    private fun answerTheMicRequest(granted: Boolean) {
        val request = shadowOf(activity).lastRequestedPermission
        assertNotNull("the press asked for the microphone", request)
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), request.requestedPermissions.toList())
        if (granted) {
            shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
                .grantPermissions(Manifest.permission.RECORD_AUDIO)
        }
        val result = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        @Suppress("DEPRECATION") // ComponentActivity still routes the platform's answer through it to the launcher
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(result))
        compose.waitForIdle()
    }
}

/**
 * A result registry that records each launch and leaves it open, as a permission dialog the person has not answered
 * yet; the test answers it with [dispatchResult].
 */
private class HeldRegistry : ActivityResultRegistry() {
    val launches = mutableListOf<Int>()

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        launches += requestCode
    }
}

private class RegistryOwner(
    override val activityResultRegistry: ActivityResultRegistry,
) : ActivityResultRegistryOwner
