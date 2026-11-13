package app.getknit.knit.usbcontrol

import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.settings.SettingsStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChangeWatchTest {
    private val filtering = MutableStateFlow(true)
    private val status = MutableStateFlow("")
    private val settings =
        mockk<SettingsStore>(relaxed = true) {
            every { lastReadAll } returns MutableStateFlow(emptyMap())
            every { acceptedConversations } returns MutableStateFlow(emptySet())
            every { blockedNodeIds } returns MutableStateFlow(emptySet())
            every { displayName } returns MutableStateFlow("Me")
            every { this@mockk.status } returns this@ChangeWatchTest.status
            every { ownAvatarHash } returns MutableStateFlow(null)
            every { contentFilteringEnabled } returns filtering
        }
    private val commons = mockk<CommonsRepository> { every { observeAll() } returns flowOf(emptyList()) }

    @OptIn(ExperimentalCoroutinesApi::class) // runCurrent: the watcher's collectors run on the test's scheduler
    @Test
    fun aFilterSwitchIsAResyncAndEachVerdictThatMovedIsItsAttachment() =
        runTest {
            val flagged = MutableStateFlow(listOf("a"))
            val log = ChangeLog { "e1" }.apply { setRecording(true) }
            ChangeWatch.launch(backgroundScope, settings, commons, flagged, log)
            runCurrent()
            val start = log.latest()
            assertEquals("the baselines record nothing", emptyList<Pair<Long, ChangeKey>>(), log.after(start))
            flagged.value = listOf("b")
            runCurrent()
            assertEquals(setOf(ChangeKey.Attachment("a"), ChangeKey.Attachment("b")), log.after(start)?.map { it.second }?.toSet())
            val beforeSwitch = log.latest()
            filtering.value = false
            runCurrent()
            assertEquals(listOf(ChangeKey.Resync("content filtering switched")), log.after(beforeSwitch)?.map { it.second })
        }

    @OptIn(ExperimentalCoroutinesApi::class) // runCurrent: the watcher's collectors run on the test's scheduler
    @Test
    fun aStatusThatMovedIsTheSelfObject() =
        runTest {
            val log = ChangeLog { "e1" }.apply { setRecording(true) }
            ChangeWatch.launch(backgroundScope, settings, commons, MutableStateFlow(emptyList()), log)
            runCurrent()
            val start = log.latest()
            status.value = "at the lake"
            runCurrent()
            assertEquals(listOf(ChangeKey.Self), log.after(start)?.map { it.second })
        }

    @Test
    fun aMapDiffNamesEveryKeyAddedRemovedOrMoved() {
        val old = mapOf("a" to 1L, "b" to 2L, "c" to 3L)
        val new = mapOf("a" to 1L, "b" to 5L, "d" to 4L)
        assertEquals(setOf("b", "c", "d"), ChangeWatch.changedKeys(old, new))
        assertEquals(emptySet<String>(), ChangeWatch.changedKeys(old, old))
    }

    @Test
    fun aMapDiffTellsANullValueFromAMissingKey() {
        assertEquals(setOf("a"), ChangeWatch.changedKeys(mapOf("a" to null), emptyMap<String, String?>()))
    }

    @Test
    fun aSetDiffNamesWhatJoinedAndWhatLeft() {
        assertEquals(setOf("x", "z"), ChangeWatch.changedMembers(setOf("x", "y"), setOf("y", "z")))
    }
}
