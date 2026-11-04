package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.bluetooth.AdvertAliases
import app.getknit.knit.mesh.bluetooth.AdvertOrder
import app.getknit.knit.mesh.bluetooth.KeyChurnLimiter
import app.getknit.knit.mesh.crypto.BlindTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [KeyChurnLimiter] (ADR 2026-11.xp6d, step 5a obligation 6; the security pass's follow-up): a contact streaming key
 * generations costs the resolver a bounded number of HMACs, the newest listing applies once the window frees, and
 * nothing is ever held from being unlisted.
 */
class KeyChurnLimiterTest {
    private val random = Random(285)
    private val wall = 1_800_000_000_000L
    private val bob = NodeId.derive("bob")
    private val carol = NodeId.derive("carol")

    private fun key(): ByteArray = ByteArray(BlindTag.KEY_BYTES).also(random::nextBytes)

    @Test
    fun aContactStreamingAThousandGenerationsCostsAtMost420HmacsPerMinuteAndTheNewestApplies() {
        var now = 0L
        val aliases = AdvertAliases<String>(nowMs = { now }, wallMs = { wall })
        val limiter = KeyChurnLimiter()
        var newest: List<ByteArray> = emptyList()

        fun list(listing: Map<String, List<ByteArray>>) = aliases.setKeys(listing.mapValues { (_, keys) -> AdvertAliases.ContactKey(keys) })
        var windowStart = 0L
        var windowHmacs = aliases.counters().tableHmacs
        repeat(1_000) {
            newest = listOf(key(), key(), key())
            limiter.offer(mapOf(bob to newest), now)?.let(::list)
            limiter.release(now)?.let(::list)
            now += 1_000
            if (now - windowStart >= KeyChurnLimiter.MIN_INTERVAL_MS) {
                val spent = aliases.counters().tableHmacs - windowHmacs
                assertTrue("spent $spent in a minute", spent <= 420)
                windowStart = now
                windowHmacs = aliases.counters().tableHmacs
            }
        }
        // The window frees: the newest listing held applies, never the first held.
        val due = limiter.nextDueAt()!!
        val applied = limiter.release(maxOf(now, due))
        assertNotNull(applied)
        list(applied!!)
        val tag = NodeId.fromBytes(BlindTag.tag(newest.first(), BlindTag.slotOf(wall), 0))
        val r = aliases.resolve(AdvertOrder.Form(tag, blinded = true))
        assertEquals(bob, (r as AdvertAliases.Resolution.Resolved).nodeId)
        assertTrue(limiter.held + limiter.coalesced > 0)
    }

    @Test
    fun aContactThatDropsOutAndComesBackIsStillHeldToOneGainAMinute() {
        // The reviews of step 5a: every key colliding with one of ours (the store leaves them out), then a fresh one.
        val limiter = KeyChurnLimiter()
        assertNotNull(limiter.offer(mapOf(bob to listOf(key())), now = 0))
        assertEquals("unlisted at once", emptySet<String>(), limiter.offer(emptyMap(), now = 1_000)!!.keys)
        val back = listOf(key())
        assertNull("its return is a gain inside its window: held", limiter.offer(mapOf(bob to back), now = 2_000))
        assertEquals(60_000L, limiter.nextDueAt())
        assertEquals(back, limiter.release(60_000)!![bob])
        // A day on, a contact that left starts afresh.
        limiter.offer(emptyMap(), now = 70_000)
        assertNotNull(limiter.offer(mapOf(bob to listOf(key())), now = 70_000 + KeyChurnLimiter.DAY_MS))
    }

    @Test
    fun aRemovalAKeyDroppedAndAnUnlistedContactApplyAtOnce() {
        val limiter = KeyChurnLimiter()
        val a = key()
        val b = key()
        assertNotNull(limiter.offer(mapOf(bob to listOf(a, b), carol to listOf(key())), now = 0))
        // Inside the window: a dropped key still applies.
        assertEquals(listOf(a), limiter.offer(mapOf(bob to listOf(a), carol to listOf(key())), now = 1_000)!![bob])
        // A contact leaving the direct contacts is unlisted at once.
        val gone = limiter.offer(mapOf(bob to listOf(a)), now = 2_000)
        assertEquals(setOf(bob), gone!!.keys)
    }

    @Test
    fun aChangeThatDropsAndGainsDropsAtOnceAndHoldsTheGain() {
        val limiter = KeyChurnLimiter()
        val a = key()
        val b = key()
        val c = key()
        limiter.offer(mapOf(bob to listOf(a, b)), now = 0)
        val now = limiter.offer(mapOf(bob to listOf(c, a)), now = 1_000)
        assertEquals("b dropped at once, c held", listOf(a), now!![bob]!!.toList())
        assertNull("nothing new until the window frees", limiter.release(30_000))
        assertEquals(listOf(c, a), limiter.release(60_000)!![bob])
        assertEquals(1L, limiter.held)
    }

    @Test
    fun aDailyCapHoldsPastIt() {
        val limiter = KeyChurnLimiter(minIntervalMs = 1, dailyCap = 3, dayMs = 1_000_000)
        var listed = emptyList<ByteArray>()
        var now = 0L
        repeat(3) {
            listed = listOf(key()) + listed
            assertNotNull(limiter.offer(mapOf(bob to listed), now))
            now += 10
        }
        assertNull("the fourth gain in a day waits", limiter.offer(mapOf(bob to listOf(key()) + listed), now))
        assertEquals(1_000_000L, limiter.nextDueAt())
    }

    @Test
    fun twentyFiveSameKeyReEntriesInADayAreEachListedWithinTheInterval() {
        // Step 5b's W7: a DirectContacts flap that brings a contact back with the keys it had is a relisting, never a gain.
        val limiter = KeyChurnLimiter()
        val keys = listOf(key(), key())
        var now = 0L
        assertNotNull(limiter.offer(mapOf(bob to keys), now))
        repeat(25) { i ->
            now += 50 * 60_000L // an hour less ten minutes: 25 in a day
            assertNotNull("leaving is never delayed", limiter.offer(emptyMap(), now))
            now += 1_000
            val listed =
                limiter.offer(mapOf(bob to keys), now)
                    ?: limiter.release(limiter.nextDueAt()!!.also { assertTrue(it - now <= KeyChurnLimiter.MIN_INTERVAL_MS) })
            assertNotNull("re-entry ${i + 1} listed", listed)
            assertEquals(2, listed!!.getValue(bob).size)
        }
    }

    @Test
    fun aReEntryInsideTheIntervalWaitsForItButNotForTheDailyCap() {
        val limiter = KeyChurnLimiter()
        val keys = listOf(key())
        assertNotNull(limiter.offer(mapOf(bob to keys), 0L))
        assertNotNull(limiter.offer(emptyMap(), 1_000L))
        assertNull("inside the interval: held", limiter.offer(mapOf(bob to keys), 2_000L))
        assertEquals(KeyChurnLimiter.MIN_INTERVAL_MS, limiter.nextDueAt())
        assertNotNull(limiter.release(KeyChurnLimiter.MIN_INTERVAL_MS))
        // A re-entry with a key it never had is a gain, under the interval and the cap as before.
        assertNotNull(limiter.offer(emptyMap(), KeyChurnLimiter.MIN_INTERVAL_MS + 1))
        assertNull(limiter.offer(mapOf(bob to listOf(key())), KeyChurnLimiter.MIN_INTERVAL_MS + 2))
    }
}
