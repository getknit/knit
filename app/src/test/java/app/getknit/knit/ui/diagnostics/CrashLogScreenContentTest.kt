package app.getknit.knit.ui.diagnostics

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.crash.CrashReportRef
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the stateless `CrashLogScreenContent` on the JVM, asserting on text rather than testTags, as
 * the sibling `DiagnosticsScreenContentTest` does.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrashLogScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val ref =
        CrashReportRef(
            at = 0L,
            summary = "IllegalStateException at BluetoothMeshTransport.kt:552",
            appVersion = "2.3.0 (13) debug",
            device = "Google Pixel 8 (shiba)",
            androidVersion = "16 (SDK 36)",
            file = File("crash-1700000000000-deadbeef.txt"),
        )

    private val trace = "java.lang.IllegalStateException: boom\n\tat app.getknit.knit.mesh.MeshRouter.route(MeshRouter.kt:91)"

    private fun show(
        state: CrashLogUiState,
        onCopy: (String) -> Unit = {},
        onShare: () -> Unit = {},
        onSendInChat: () -> Unit = {},
        onReport: () -> Unit = {},
        onDelete: () -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                CrashLogScreenContent(
                    state = state,
                    now = 0L,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onCopy = onCopy,
                    onShare = onShare,
                    onSendInChat = onSendInChat,
                    onReportOnGitHub = onReport,
                    onDelete = onDelete,
                )
            }
        }
    }

    private fun loaded() = CrashLogUiState(loading = false, ref = ref, text = trace)

    /** Brings a lazy-list item that starts below the fold into composition. */
    private fun scrollTo(text: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))

    @Test
    fun rendersTheTraceAndSaysWhatItCannotCapture() {
        show(loaded())

        compose.onNodeWithText(ref.summary).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.crash_native_note)).assertIsDisplayed()
        // The trace sits below the fold in a LazyColumn, so it is not composed until scrolled to.
        scrollTo("java.lang.IllegalStateException: boom")
        compose.onNodeWithText("java.lang.IllegalStateException: boom").assertIsDisplayed()
    }

    @Test
    fun copyShareAndSendInChatInvokeTheirCallbacks() {
        var copied = ""
        var shares = 0
        var sends = 0
        show(loaded(), onCopy = { copied = it }, onShare = { shares++ }, onSendInChat = { sends++ })

        compose.onNodeWithText(context.getString(R.string.crash_action_copy)).performClick()
        compose.onNodeWithText(context.getString(R.string.crash_action_share)).performClick()
        scrollTo(context.getString(R.string.crash_action_send_in_chat))
        compose.onNodeWithText(context.getString(R.string.crash_action_send_in_chat)).performClick()

        assertEquals(trace, copied)
        assertEquals(1, shares)
        assertEquals(1, sends)
    }

    /**
     * The consent gate. Reporting is the one action that puts the report on a public tracker, so it must
     * not fire until the user has been told that and confirmed.
     */
    @Test
    fun reportingIsGatedBehindThePublicTrackerDialog() {
        var reports = 0
        show(loaded(), onReport = { reports++ })

        compose.onNodeWithText(context.getString(R.string.crash_action_report)).performClick()
        compose.onNodeWithText(context.getString(R.string.crash_report_body)).assertIsDisplayed()
        assertEquals(0, reports)

        compose.onNodeWithText(context.getString(R.string.crash_report_confirm)).performClick()
        assertEquals(1, reports)
    }

    @Test
    fun deletingIsGatedBehindAConfirmation() {
        var deletes = 0
        show(loaded(), onDelete = { deletes++ })

        scrollTo(context.getString(R.string.crash_action_delete))
        compose.onNodeWithText(context.getString(R.string.crash_action_delete)).performClick()
        assertEquals(0, deletes)

        compose.onNodeWithText(context.getString(R.string.crash_delete_confirm)).performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun showsTheEmptyStateWhenNothingWasCaptured() {
        show(CrashLogUiState(loading = false))

        compose.onNodeWithText(context.getString(R.string.crash_empty)).assertIsDisplayed()
    }
}
