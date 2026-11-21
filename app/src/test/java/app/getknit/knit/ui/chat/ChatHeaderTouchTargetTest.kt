package app.getknit.knit.ui.chat

import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The header's lock badge keeps a 48 dp touch target on a 360 dp-wide phone (GitLab #354). The name before it takes
 * the rest of the title slot, so a target the badge only borrows from its neighbours is cut where the slot ends: the
 * Galaxy S26's ATF run measured it at 41 dp beside "Sam Rivera". The test reads the accessibility node's bounds, the
 * rect ATF checks: Compose cuts them where the title slot ends and the top bar's actions begin.
 * `SemanticsNode.touchBoundsInRoot` is not cut that way, and passes on the bug.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class ChatHeaderTouchTargetTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun aPhoneWithAMicrophone() {
        // The seeded DM on the S26 had its call button beside the overflow, narrowing the title slot.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_MICROPHONE, true)
    }

    private fun show(state: ChatUiState) {
        compose.setContent {
            KnitTheme {
                ChatScreenContent(
                    conversationId = "bob",
                    state = state,
                    inputState = TextFieldState(""),
                    pendingAttachment = null,
                    replyingTo = null,
                    now = 1_700_000_060_000L,
                    onBack = {},
                    onOpenProfile = {},
                    onOpenGroupDetails = {},
                    onSend = {},
                    onAttachClick = {},
                    onClearAttachment = {},
                    onReceiveImage = {},
                    onTyping = {},
                    onMentionAdded = {},
                    onStartReply = {},
                    onCancelReply = {},
                    onReact = { _, _ -> },
                    onDeleteMessage = {},
                    onBlock = {},
                    onUnblock = {},
                    onCopy = {},
                    onSaveAttachment = { _, _, _ -> },
                )
            }
        }
    }

    private fun View.composeHost(): View? =
        if (accessibilityNodeProvider != null) {
            this
        } else {
            (this as? ViewGroup)?.let { group ->
                (0 until group.childCount).firstNotNullOfOrNull { group.getChildAt(it).composeHost() }
            }
        }

    private fun assertLockIsAFullTarget() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val desc = context.getString(R.string.chat_encrypted_desc)
        val node = compose.onNodeWithContentDescription(desc).assertHasClickAction().fetchSemanticsNode()
        // The bounds ATF checks: the accessibility node's, which Compose cuts where the title slot ends and the top
        // bar's actions begin. `touchBoundsInRoot` keeps the full 48 dp there.
        val decor = compose.activity.window.decorView
        val host = requireNotNull(decor.composeHost()) { "no Compose host view" }
        val info =
            requireNotNull(host.accessibilityNodeProvider.createAccessibilityNodeInfo(node.id)) { "no node info" }
        val bounds = Rect().also { info.getBoundsInScreen(it) }
        val floor = with(compose.density) { 48.dp.toPx() }
        // Half a pixel of slack for the dp → px rounding a 48 dp layout takes at a fractional density.
        val shown = "touch target ${bounds.width()} x ${bounds.height()} px, under 48 dp ($floor px)"
        assertTrue(shown, bounds.width() >= floor - 0.5f)
        assertTrue(shown, bounds.height() >= floor - 0.5f)
    }

    @Test
    fun aDmsLockIsAFullTargetBesideTheSeededName() {
        show(ChatUiState(myNodeId = "me", isRoom = false, title = "Sam Rivera", canCall = true))
        assertLockIsAFullTarget()
    }

    @Test
    fun aDmsLockIsAFullTargetBesideANameThatFillsTheSlot() {
        show(ChatUiState(myNodeId = "me", isRoom = false, title = "Bartholomew Montgomery-Fairweather", canCall = true))
        assertLockIsAFullTarget()
    }

    @Test
    fun aVerifiedDmsLockIsAFullTargetBesideANameThatFillsTheSlot() {
        show(
            ChatUiState(
                myNodeId = "me",
                isRoom = false,
                title = "Bartholomew Montgomery-Fairweather",
                verified = true,
                canCall = true,
            ),
        )
        assertLockIsAFullTarget()
    }

    @Test
    fun aGroupsLockIsAFullTargetBesideANameThatFillsTheSlot() {
        show(
            ChatUiState(
                myNodeId = "me",
                isRoom = false,
                isGroup = true,
                memberCount = 5,
                title = "The Saturday Morning Hiking and Birdwatching Crew",
            ),
        )
        assertLockIsAFullTarget()
    }
}
