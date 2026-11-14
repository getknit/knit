@file:Suppress("MagicNumber") // times and budgets are the test's subject

package app.getknit.knit.usbcontrol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendLedgerTest {
    private var now = 0L

    @Test
    fun aBurstGoesAtOnceAndTheRestAtTheRate() {
        val ledger = SendLedger({ now }, burst = 2, perMinute = 30)
        assertNull(ledger.take())
        assertNull(ledger.take())
        assertEquals("one token every 2 s", 2_000L, ledger.take())
        now += 1_000
        assertEquals(1_000L, ledger.take())
        now += 1_000
        assertNull(ledger.take())
    }

    @Test
    fun anIdleBucketRefillsOnlyToItsBurst() {
        val ledger = SendLedger({ now }, burst = 2, perMinute = 60)
        now += 3_600_000
        assertNull(ledger.take())
        assertNull(ledger.take())
        assertEquals(1_000L, ledger.take())
    }

    @Test
    fun statusChangesDrawOnABucketOfTheirOwn() {
        val ledger = SendLedger({ now }, burst = 1, perMinute = 60, profileBurst = 2, profilesPerMinute = 1)
        assertNull(ledger.takeProfile())
        assertNull(ledger.takeProfile())
        assertEquals("one a minute", 60_000L, ledger.takeProfile())
        assertNull("sends untouched", ledger.take())
        assertEquals(1_000L, ledger.take())
        assertEquals("a spent send bucket takes nothing from the profile's", 60_000L, ledger.takeProfile())
        now += 60_000
        assertNull(ledger.takeProfile())
    }

    @Test
    fun theProfileBucketDefaultsToTwoThenOneAMinute() {
        val ledger = SendLedger({ now })
        assertNull(ledger.takeProfile())
        assertNull(ledger.takeProfile())
        assertEquals(60_000L, ledger.takeProfile())
    }

    @Test
    fun aRecordedReplyIsReplayedUntilNewerSendsPushItOut() {
        val ledger = SendLedger({ now }, capacity = 2)
        ledger.record("a", reply("1"))
        ledger.record("b", reply("2"))
        assertEquals(reply("1"), ledger.replay("a")) // a is now the most recently used
        ledger.record("c", reply("3"))
        assertNull("the least recently used went", ledger.replay("b"))
        assertEquals(reply("1"), ledger.replay("a"))
        assertEquals(reply("3"), ledger.replay("c"))
    }

    @Test
    fun aTypingCueGoesOncePerThreadPerIntervalAndEachThreadKeepsItsOwn() {
        val ledger = SendLedger({ now }, typingBurst = 10, typingPerMinute = 60, typingIntervalMs = 8_000)
        assertTrue(ledger.takeTyping("a"))
        assertTrue("b is not held by a's interval", ledger.takeTyping("b"))
        now += 7_999
        assertFalse(ledger.takeTyping("a"))
        now += 1
        assertTrue(ledger.takeTyping("a"))
        assertTrue("b's interval has run out too", ledger.takeTyping("b"))
    }

    @Test
    fun typingCuesDrawOnABucketOfTheirOwnAndARefusedCueSpendsNothing() {
        val ledger = SendLedger({ now }, burst = 1, perMinute = 60, typingBurst = 2, typingPerMinute = 30, typingIntervalMs = 8_000)
        assertTrue(ledger.takeTyping("a"))
        assertTrue(ledger.takeTyping("b"))
        assertFalse("the cue bucket is spent", ledger.takeTyping("c"))
        assertNull("sends untouched", ledger.take())
        now += 2_000 // one cue token back, at 30 a minute
        assertFalse("a is inside its interval, and spends no token on it", ledger.takeTyping("a"))
        assertTrue("c was not held by the empty bucket's refusal, and finds the token", ledger.takeTyping("c"))
        assertFalse("that was the only token", ledger.takeTyping("d"))
    }

    @Test
    fun aClockThatSteppedBackNeitherHoldsAThreadNorDrainsTheBuckets() {
        val ledger = SendLedger({ now }, burst = 1, perMinute = 1, typingIntervalMs = 8_000)
        now = 3_600_000
        assertTrue(ledger.takeTyping("a"))
        assertNull(ledger.take())
        now = 60_000 // an hour back
        assertTrue("a's last cue reads as long ago", ledger.takeTyping("a"))
        assertEquals("the step back refilled nothing", 60_000L, ledger.take())
        now += 60_000
        assertNull("and drained nothing: a minute on, the send bucket has its token back", ledger.take())
    }

    private fun reply(id: String) = buildJsonObject { put("messageId", id) }
}
