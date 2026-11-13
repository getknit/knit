@file:Suppress("MagicNumber") // times and budgets are the test's subject

package app.getknit.knit.usbcontrol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun reply(id: String) = buildJsonObject { put("messageId", id) }
}
