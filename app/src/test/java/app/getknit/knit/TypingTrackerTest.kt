package app.getknit.knit

import app.getknit.knit.mesh.TypingTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [TypingTracker]'s receiver-side typing state on the JVM with virtual time. The auto-clear is a
 * delayed coroutine on [backgroundScope] (cancelled when the test ends), so `advanceTimeBy` drives the TTL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TypingTrackerTest {
    private val ttl = 10_000L

    @Test
    fun onTypingShowsSenderInConversation() =
        runTest(UnconfinedTestDispatcher()) {
            val tracker = TypingTracker(backgroundScope, ttlMs = ttl)
            tracker.onTyping("nearby", "alice")
            assertEquals(setOf("alice"), tracker.typing.value["nearby"])
        }

    @Test
    fun indicatorAutoClearsAfterTtl() =
        runTest(UnconfinedTestDispatcher()) {
            val tracker = TypingTracker(backgroundScope, ttlMs = ttl)
            tracker.onTyping("nearby", "alice")
            advanceTimeBy(ttl - 1)
            assertEquals("still shown just before the TTL", setOf("alice"), tracker.typing.value["nearby"])
            advanceTimeBy(2) // now past the TTL
            assertTrue("cleared after the TTL", tracker.typing.value["nearby"].isNullOrEmpty())
        }

    @Test
    fun messageFromSenderClearsIndicatorImmediately() =
        runTest(UnconfinedTestDispatcher()) {
            val tracker = TypingTracker(backgroundScope, ttlMs = ttl)
            tracker.onTyping("bob", "bob") // a DM, keyed by the other party
            tracker.onMessageFrom("bob", "bob")
            assertTrue("a real message clears typing at once", tracker.typing.value["bob"].isNullOrEmpty())
        }

    @Test
    fun refreshExtendsTheTtl() =
        runTest(UnconfinedTestDispatcher()) {
            val tracker = TypingTracker(backgroundScope, ttlMs = ttl)
            tracker.onTyping("nearby", "alice")
            advanceTimeBy(ttl - 2) // almost expired
            tracker.onTyping("nearby", "alice") // refresh → a fresh TTL window; the first schedule is now stale
            advanceTimeBy(ttl - 2) // past the first window, but not the second
            assertEquals("a refresh keeps it shown past the first window", setOf("alice"), tracker.typing.value["nearby"])
            advanceTimeBy(4) // past the second window
            assertTrue("cleared once the refreshed TTL elapses", tracker.typing.value["nearby"].isNullOrEmpty())
        }

    @Test
    fun distinctSendersAndConversationsAreIndependent() =
        runTest(UnconfinedTestDispatcher()) {
            val tracker = TypingTracker(backgroundScope, ttlMs = ttl)
            tracker.onTyping("nearby", "alice")
            tracker.onTyping("nearby", "carol")
            tracker.onTyping("g-1", "alice")
            assertEquals(setOf("alice", "carol"), tracker.typing.value["nearby"])
            assertEquals(setOf("alice"), tracker.typing.value["g-1"])

            tracker.onMessageFrom("nearby", "alice")
            assertEquals("only alice clears from nearby", setOf("carol"), tracker.typing.value["nearby"])
            assertEquals("the same sender typing in another thread is untouched", setOf("alice"), tracker.typing.value["g-1"])
        }
}
