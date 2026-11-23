package app.getknit.knit.notifications

import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.settings.ChatMutes
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.ui.chat.EVERYONE_NAME
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** The per-chat mute's rules (ADR 2026-11.sp7k), which the notification gate, the chat list and the details screens share. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMuteTest {
    private val now = 1_700_000_000_000L
    private val hour = TimeUnit.HOURS.toMillis(1)
    private val day = TimeUnit.DAYS.toMillis(1)

    @Test
    fun `each span ends its own length after now, and Always never ends`() {
        assertEquals(now + hour, MuteFor.HOUR.deadline(now))
        assertEquals(now + 8 * hour, MuteFor.EIGHT_HOURS.deadline(now))
        assertEquals(now + day, MuteFor.DAY.deadline(now))
        assertEquals(now + 7 * day, MuteFor.WEEK.deadline(now))
        assertEquals(ChatMutes.ALWAYS, MuteFor.ALWAYS.deadline(now))
    }

    @Test
    fun `only a deadline ahead of the clock is a mute`() {
        assertNull(ChatMute.activeUntil(null, now))
        assertNull(ChatMute.activeUntil(now - 1, now))
        assertNull(ChatMute.activeUntil(now, now))
        assertEquals(now + 1, ChatMute.activeUntil(now + 1, now))
        assertEquals(ChatMutes.ALWAYS, ChatMute.activeUntil(ChatMutes.ALWAYS, now))
    }

    @Test
    fun `a live mute silences every kind of chat, and a lapsed or absent one silences none`() {
        ConversationKind.entries.forEach { kind ->
            assertTrue("$kind", ChatMute.silences(now + hour, now, personalMention = false, kind = kind))
            assertTrue("$kind", ChatMute.silences(ChatMutes.ALWAYS, now, personalMention = false, kind = kind))
            assertFalse("$kind", ChatMute.silences(now, now, personalMention = false, kind = kind))
            assertFalse("$kind", ChatMute.silences(null, now, personalMention = true, kind = kind))
        }
    }

    @Test
    fun `a personal mention gets through a muted group or commons and nowhere else`() {
        val through = setOf(ConversationKind.GROUP, ConversationKind.COMMONS)
        ConversationKind.entries.forEach { kind ->
            assertEquals("$kind", kind !in through, ChatMute.silences(ChatMutes.ALWAYS, now, personalMention = true, kind = kind))
        }
    }

    @Test
    fun `everyone is a mention of the room, not of the person`() {
        assertTrue(ChatMute.personalMention(listOf(Mention("me", "Ada")), "me"))
        assertFalse(ChatMute.personalMention(listOf(Mention("me", EVERYONE_NAME)), "me"))
        assertFalse(ChatMute.personalMention(listOf(Mention("someone-else", "Bo")), "me"))
        assertFalse(ChatMute.personalMention(emptyList(), "me"))
        // A real mention beside an @everyone still names the person.
        assertTrue(ChatMute.personalMention(listOf(Mention("me", EVERYONE_NAME), Mention("me", "Ada")), "me"))
    }

    @Test
    fun `a deadline names the time today, the weekday this week, and the date past six days`() {
        val zone = ZoneId.of("America/Los_Angeles")
        val morning = ZonedDateTime.of(2026, 11, 23, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(ChatMute.LabelStyle.TIME, ChatMute.labelStyle(morning + hour, morning, zone))
        assertEquals(ChatMute.LabelStyle.TIME, ChatMute.labelStyle(morning + 8 * hour, morning, zone))
        assertEquals(ChatMute.LabelStyle.WEEKDAY_TIME, ChatMute.labelStyle(morning + day, morning, zone))
        assertEquals(ChatMute.LabelStyle.WEEKDAY_TIME, ChatMute.labelStyle(morning + 6 * day - 1, morning, zone))
        // Seven days on is the same weekday: "Mon 9:00 AM" set on a Monday would read as today.
        assertEquals(ChatMute.LabelStyle.DATE_TIME, ChatMute.labelStyle(morning + 7 * day, morning, zone))
    }

    @Test
    fun `a timed mute leaves the live set as its deadline passes, and Always stays`() =
        runTest {
            val source = MutableStateFlow(mapOf("a" to 1_000L, "b" to ChatMutes.ALWAYS))
            val seen = mutableListOf<Map<String, Long>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ChatMute.active(source) { testScheduler.currentTime }.toList(seen)
            }
            assertEquals(listOf(mapOf("a" to 1_000L, "b" to ChatMutes.ALWAYS)), seen)

            advanceTimeBy(999)
            runCurrent()
            assertEquals("nothing lapses early", 1, seen.size)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(mapOf("b" to ChatMutes.ALWAYS), seen.last())
            assertEquals(2, seen.size)
        }

    @Test
    fun `an entry already past is never live`() =
        runTest {
            val source = MutableStateFlow(mapOf("old" to 5L, "new" to 50_000L))
            val seen = mutableListOf<Map<String, Long>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ChatMute.active(source) { 10L }.toList(seen)
            }
            assertEquals(listOf(mapOf("new" to 50_000L)), seen)
        }

    @Test
    fun `a wall clock that ran on while the phone slept lapses the mute at the next re-check`() =
        runTest {
            // The wall clock and the scheduler part ways, as a sleeping phone's do: `delay` stops, the clock does not.
            var wall = 0L
            val deadline = TimeUnit.MINUTES.toMillis(10)
            val source = MutableStateFlow(mapOf("a" to deadline))
            val seen = mutableListOf<Map<String, Long>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ChatMute.active(source) { wall }.toList(seen)
            }
            wall = deadline + 1
            advanceTimeBy(ChatMute.RECHECK_MS)
            runCurrent()
            assertEquals(listOf(mapOf("a" to deadline), emptyMap()), seen)
        }

    @Test
    fun `an unmute leaves the live set at once, without waiting on the old deadline`() =
        runTest {
            val source = MutableStateFlow(mapOf("a" to TimeUnit.DAYS.toMillis(1)))
            val seen = mutableListOf<Map<String, Long>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ChatMute.active(source) { testScheduler.currentTime }.toList(seen)
            }
            source.value = emptyMap()
            assertEquals(listOf(mapOf("a" to TimeUnit.DAYS.toMillis(1)), emptyMap()), seen)
        }
}
