package app.getknit.knit.ui.diagnostics

import app.getknit.knit.R
import app.getknit.knit.crash.CrashReportRef
import app.getknit.knit.crash.CrashReports
import app.getknit.knit.crash.shareFileName
import app.getknit.knit.ui.share.ShareInbox
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CrashLogViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val inbox = ShareInbox()

    private val ref =
        CrashReportRef(
            at = 1_700_000_000_000L,
            summary = "IllegalStateException at MeshRouter.kt:91",
            appVersion = "2.2.0 (11) release",
            device = "Google Pixel 8 (shiba)",
            androidVersion = "16 (SDK 36)",
            file = File("crash-1700000000000-deadbeef.txt"),
        )

    private fun reports(
        latest: CrashReportRef? = ref,
        text: String? = "java.lang.IllegalStateException: boom",
    ): CrashReports =
        mockk<CrashReports>(relaxed = true).also {
            coEvery { it.latest() } returns latest
            coEvery { it.read(any()) } returns text
        }

    /**
     * Subscribes before the action under test. The event channel has no replay, so a collector started
     * afterwards would never see the emission.
     */
    private fun TestScope.events(viewModel: CrashLogViewModel): List<Int> {
        val seen = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { seen += it } }
        return seen
    }

    @Test
    fun `loads the newest report`() =
        runTest {
            val state = CrashLogViewModel(reports(), inbox).state.value
            assertEquals(false, state.loading)
            assertEquals(ref, state.ref)
            assertTrue(state.text.contains("boom"))
        }

    @Test
    fun `shows the empty state when there is nothing to read`() =
        runTest {
            val state = CrashLogViewModel(reports(latest = null), inbox).state.value
            assertEquals(false, state.loading)
            assertNull(state.ref)
        }

    @Test
    fun `shows the empty state when the report file has gone unreadable`() =
        runTest {
            val state = CrashLogViewModel(reports(text = null), inbox).state.value
            assertNull(state.ref)
        }

    @Test
    fun `delete clears the store and empties the state`() =
        runTest {
            val crashes = reports()
            val viewModel = CrashLogViewModel(crashes, inbox)

            val seen = events(viewModel)

            viewModel.delete()

            coVerify { crashes.clear() }
            assertNull(viewModel.state.value.ref)
            assertEquals(listOf(R.string.crash_deleted), seen)
        }

    @Test
    fun `onCopied emits the copied confirmation`() =
        runTest {
            val viewModel = CrashLogViewModel(reports(), inbox)
            val seen = events(viewModel)

            viewModel.onCopied()

            assertEquals(listOf(R.string.crash_copied), seen)
        }

    /**
     * A report can outlive an app update, so the form must name the version that **crashed** — the one
     * parsed out of the stored header — not whatever is running now.
     */
    @Test
    fun `the issue url carries the version that crashed`() =
        runTest {
            val url = requireNotNull(CrashLogViewModel(reports(), inbox).issueUrl())
            assertTrue(url.contains("2.2.0"))
            assertTrue(url.contains("template=bug_report.yml"))
        }

    @Test
    fun `there is no issue url without a report`() =
        runTest {
            assertNull(CrashLogViewModel(reports(latest = null), inbox).issueUrl())
        }

    /**
     * Send in a chat (#287): the report is read again, as the share sheet's copy is, and handed to the chat picker
     * as bytes under the share sheet's file name, never as a URI (ADR 2026-11.vdx4).
     */
    @Test
    fun `send in a chat offers the report as bytes`() =
        runTest {
            val crashes = reports()
            coEvery { crashes.read(any()) } returnsMany listOf("first read", "read again")
            val viewModel = CrashLogViewModel(crashes, inbox)

            viewModel.sendInChat()

            val shared = requireNotNull(inbox.pending.value)
            val file = requireNotNull(shared.ownFile)
            assertEquals(shareFileName(ref.appVersion, ref.at), file.name)
            assertEquals("text/plain", file.mime)
            assertArrayEquals("read again".encodeToByteArray(), file.bytes)
            assertNull(shared.fileUri)
            assertTrue(shared.fileOnly)
        }

    @Test
    fun `send in a chat with no report says so and offers nothing`() =
        runTest {
            val viewModel = CrashLogViewModel(reports(latest = null), inbox)
            val seen = events(viewModel)

            viewModel.sendInChat()

            assertEquals(listOf(R.string.crash_share_error), seen)
            assertNull(inbox.pending.value)
        }

    @Test
    fun `send in a chat that cannot read the report says so and offers nothing`() =
        runTest {
            val crashes = reports()
            coEvery { crashes.read(any()) } returnsMany listOf("first read", null)
            val viewModel = CrashLogViewModel(crashes, inbox)
            val seen = events(viewModel)

            viewModel.sendInChat()

            assertEquals(listOf(R.string.crash_share_error), seen)
            assertNull(inbox.pending.value)
        }
}
