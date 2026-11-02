package app.getknit.knit.ui.diagnostics

import androidx.datastore.preferences.core.Preferences
import app.getknit.knit.R
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.fieldlog.FieldLog
import app.getknit.knit.fieldlog.FieldLogDocument
import app.getknit.knit.fieldlog.FieldLogExport
import app.getknit.knit.ui.share.ShareInbox
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The Field log screen's driver (ADR 2026-10.a239): it shows the file exactly as the user would send it — its line
 * count, its size and its head — re-reads it after the switch or Clear, and transmits nothing itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FieldLogViewModelTest {
    private val enabled = MutableStateFlow(false)
    private val settings =
        mockk<SettingsStore>(relaxed = true) {
            every { fieldLogEnabled } returns enabled
            coEvery { setFieldLogEnabled(any()) } coAnswers {
                enabled.value = firstArg()
                mockk<Preferences>()
            }
        }
    private val log =
        mockk<FieldLog>(relaxed = true) {
            // The log writes the switch through the persist callback it is handed; run it as the real one does.
            coEvery { setEnabled(any(), any()) } coAnswers { secondArg<suspend (Boolean) -> Unit>().invoke(firstArg()) }
        }
    private val export = mockk<FieldLogExport>(relaxed = true)
    private val inbox = ShareInbox()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun document(lines: Int): FieldLogDocument.Built {
        val text = buildString { repeat(lines) { appendLine("""{"t":$it,"k":"tick"}""") } }
        return FieldLogDocument.Built(text = text, events = lines, dropped = 0)
    }

    /** Subscribes before the act: the event channel has no replay. */
    private fun TestScope.events(vm: FieldLogViewModel): List<Int> {
        val seen = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { seen += it } }
        return seen
    }

    @Test
    fun theScreenShowsTheFileAsItWouldBeShared() =
        runTest {
            val doc = document(3)
            coEvery { export.render() } returns doc

            val vm = FieldLogViewModel(log, export, settings, inbox)
            advanceUntilIdle()

            val state = vm.state.value
            assertFalse(state.loading)
            assertFalse(state.enabled)
            assertEquals(3, state.events)
            assertEquals(doc.text.length.toLong(), state.bytes)
            assertEquals(listOf("""{"t":0,"k":"tick"}""", """{"t":1,"k":"tick"}""", """{"t":2,"k":"tick"}"""), state.preview)
            assertEquals(0, state.more)
        }

    @Test
    fun aLongFileShowsItsFirstTwoHundredLinesAndCountsTheRest() =
        runTest {
            coEvery { export.render() } returns document(250)

            val vm = FieldLogViewModel(log, export, settings, inbox)
            advanceUntilIdle()

            assertEquals(200, vm.state.value.preview.size)
            assertEquals(
                """{"t":199,"k":"tick"}""",
                vm.state.value.preview
                    .last(),
            )
            assertEquals(50, vm.state.value.more)
        }

    @Test
    fun switchingTheLogOnPersistsTheChoiceAndReReadsTheFile() =
        runTest {
            coEvery { export.render() } returns document(0) andThen document(1)
            val vm = FieldLogViewModel(log, export, settings, inbox)
            advanceUntilIdle()
            assertFalse(vm.state.value.enabled)

            vm.setEnabled(true)
            advanceUntilIdle()

            coVerify { settings.setFieldLogEnabled(true) }
            assertTrue(vm.state.value.enabled)
            assertEquals(1, vm.state.value.events)
        }

    @Test
    fun clearEmptiesTheLogSaysSoAndShowsTheEmptiedFile() =
        runTest {
            coEvery { export.render() } returns document(5) andThen document(0)
            val vm = FieldLogViewModel(log, export, settings, inbox)
            val events = events(vm)
            advanceUntilIdle()
            assertEquals(5, vm.state.value.events)

            vm.clear()
            advanceUntilIdle()

            coVerify { log.clear() }
            assertEquals(listOf(R.string.field_log_cleared), events)
            assertEquals(0, vm.state.value.events)
            assertTrue(
                vm.state.value.preview
                    .isEmpty(),
            )
        }

    @Test
    fun aShareTheSystemCouldNotStartSaysSo() =
        runTest {
            coEvery { export.render() } returns document(1)
            val vm = FieldLogViewModel(log, export, settings, inbox)
            val events = events(vm)
            advanceUntilIdle()

            vm.onShareFailed()

            assertEquals(listOf(R.string.crash_share_error), events)
        }

    /**
     * Send in a chat (#287) hands the chat picker the file Share would write, as bytes and never as a URI: the chat
     * refuses Knit's own provider (ADR 2026-11.vdx4).
     */
    @Test
    fun sendInChatOffersTheFileAsBytes() =
        runTest {
            val doc = document(2)
            coEvery { export.render() } returns doc
            every { export.fileName() } returns "knit-fieldlog-2.10.2-20261102-1432.jsonl"
            val vm = FieldLogViewModel(log, export, settings, inbox)
            advanceUntilIdle()

            vm.sendInChat()
            advanceUntilIdle()

            val shared = requireNotNull(inbox.pending.value)
            val file = requireNotNull(shared.ownFile)
            assertEquals("knit-fieldlog-2.10.2-20261102-1432.jsonl", file.name)
            assertEquals("text/plain", file.mime)
            assertArrayEquals(doc.text.encodeToByteArray(), file.bytes)
            assertNull(shared.fileUri)
            assertNull(shared.imageUri)
            assertTrue(shared.fileOnly)
        }

    @Test
    fun aLogThatCannotBeRenderedForAChatSaysSoAndOffersNothing() =
        runTest {
            coEvery { export.render() } returns document(1)
            val vm = FieldLogViewModel(log, export, settings, inbox)
            val events = events(vm)
            advanceUntilIdle()
            coEvery { export.render() } throws IOException("journal gone")

            vm.sendInChat()
            advanceUntilIdle()

            assertEquals(listOf(R.string.crash_share_error), events)
            assertNull(inbox.pending.value)
        }
}
