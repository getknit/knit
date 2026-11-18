package app.getknit.knit.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenSetTest {
    @Test
    fun anIdIsNewAgainOnceItsWindowHasPassed() {
        var now = 0L
        val seen = SeenSet(ttlMillis = 1_000, clock = { now })
        assertTrue(seen.add("a"))
        assertFalse("inside the window", seen.add("a"))
        now = 1_000
        assertTrue("the window lapsed", seen.add("a"))
    }

    @Test
    fun aRestoredSetRemembersWhatTheLastProcessSaw() {
        // The LoRa plane's profile re-fan gate is the case: a 12-hour window kept by a process that may live
        // minutes, so without this every launch re-fanned every profile it heard.
        var now = 0L
        val before = SeenSet(ttlMillis = 10_000, clock = { now })
        before.add("published-once")
        now = 5_000

        val after = SeenSet(ttlMillis = 10_000, clock = { now })
        after.restore(before.stamps())

        assertFalse("still inside the original window", after.add("published-once"))
        now = 10_001
        assertTrue("and expires on the original stamp, not the restore", after.add("published-once"))
    }

    @Test
    fun anExpiredEntryIsNeitherExportedNorRestored() {
        var now = 0L
        val before = SeenSet(ttlMillis = 1_000, clock = { now })
        before.add("stale")
        now = 2_000
        assertEquals("an expired entry is new again by definition", emptyList<Pair<String, Long>>(), before.stamps())

        val after = SeenSet(ttlMillis = 1_000, clock = { now })
        after.restore(listOf("stale" to 0L))
        assertTrue(after.add("stale"))
    }

    @Test
    fun aRestoreLongerThanTheSetKeepsTheNewest() {
        val now = 1_000L
        val seen = SeenSet(maxSize = 2, ttlMillis = 10_000, clock = { now })
        seen.restore(listOf("oldest" to 0L, "middle" to 100L, "newest" to 200L))
        assertTrue("the oldest was evicted, as it would have been while running", seen.add("oldest"))
        assertTrue("the newest is what a bounded set keeps", seen.contains("newest"))
    }

    @Test
    fun anIdCanBeReopenedOnceInsideItsWindowAndNotAgain() {
        var now = 1_000L
        val set = SeenSet(clock = { now })
        assertTrue(set.add("dm"))
        assertFalse("the copy custody served is a duplicate", set.add("dm"))
        assertTrue("the first reopen admits the sender's re-seal", set.reopen("dm", "alice"))
        set.settle("dm", "alice")
        assertFalse("a second reopen inside the window is refused", set.reopen("dm", "alice"))
        assertFalse(set.add("dm"))
        assertFalse("an id the set never saw has nothing to reopen", set.reopen("other", "alice"))
        now += SeenSet.DEFAULT_TTL_MS + 1
        assertTrue("a new window, a new reopen", set.add("dm"))
        assertTrue(set.reopen("dm", "alice"))
    }

    @Test
    fun aReopenedIdStaysUnmarkedUntilItSettles() {
        var now = 1_000L
        val set = SeenSet(clock = { now })
        set.add("dm")
        assertTrue(set.reopen("dm", "alice"))
        val guard = checkNotNull(set.guardFor("dm")) { "the reopen leaves a guard" }
        assertEquals("alice", guard.sender)
        assertEquals(1_000L, guard.at)
        // #347: a copy admitted here used to take the mark again, and the second-pass re-seal behind it dropped as seen.
        assertFalse("no copy marks a guarded id", set.add("dm"))
        assertFalse(set.contains("dm"))
        assertNull("an id never reopened has no guard", set.guardFor("other"))

        now += 60_000L
        set.settle("dm", "alice")
        assertNull("a copy opened: the guard ends", set.guardFor("dm"))
        assertTrue("and the id is marked", set.contains("dm"))
        assertFalse(set.add("dm"))
    }

    @Test
    fun aGuardNoCopyOpensLapsesAndTheNormalPathResumes() {
        var now = 1_000L
        val set = SeenSet(clock = { now })
        set.add("dm")
        set.reopen("dm", "alice")
        now += SeenSet.REOPEN_GUARD_MS - 1
        assertTrue("still guarded just short of the lapse", set.guardFor("dm") != null)
        now += 1
        assertNull("lapsed", set.guardFor("dm"))
        assertFalse("a lapsed guard has no tries to spend", set.spendTry("dm", "sig"))
        assertTrue("the next copy is marked as it always was", set.add("dm"))
        assertTrue("and fails into a fresh reopen", set.reopen("dm", "alice"))
        set.settle("dm", "alice")
        assertTrue(set.contains("dm"))
    }

    @Test
    fun aSettledGuardStillRefusesASecondReopenInsideTheWindow() {
        var now = 1_000L
        val set = SeenSet(clock = { now })
        set.add("dm")
        set.reopen("dm", "alice")
        now += 5 * 60_000L
        set.settle("dm", "alice")
        // A stale copy that failed beside the one that opened: its reopen is refused, so the mark the settle took stands.
        assertFalse(set.reopen("dm", "alice"))
        assertTrue(set.contains("dm"))
        now = 1_000L + SeenSet.DEFAULT_TTL_MS
        assertTrue("a window on from the first reopen, a new one", set.reopen("dm", "alice"))
    }

    @Test
    fun aSettleOfAnIdWithNoGuardIsANoOp() {
        val set = SeenSet(clock = { 1_000L })
        set.settle("never-seen", "alice")
        assertFalse(set.contains("never-seen"))
        set.add("dm")
        set.settle("dm", "alice")
        assertTrue(set.contains("dm"))
    }

    @Test
    fun aSignatureHasThreeTriesAWindowAndRefillsAfterIt() {
        var now = 1_000L
        val set = SeenSet(clock = { now })
        assertFalse("an id with no guard has no tries", set.spendTry("dm", "sig"))
        set.add("dm")
        set.reopen("dm", "alice")
        repeat(SeenSet.REOPEN_TRIES) { assertTrue("try ${it + 1}", set.spendTry("dm", "sig")) }
        assertFalse("the fourth inside the window", set.spendTry("dm", "sig"))
        assertTrue("another signature has tries of its own", set.spendTry("dm", "other"))
        now += SeenSet.DEFAULT_TTL_MS - 1
        assertFalse(set.spendTry("dm", "sig"))
        now += 1
        assertTrue("a window on from the first try, the count refills", set.spendTry("dm", "sig"))
        set.settle("dm", "alice")
        assertFalse("none once settled", set.spendTry("dm", "other"))
    }

    @Test
    fun aGuardKeepsEightSignaturesAndTheNewestAlwaysPasses() {
        val set = SeenSet(clock = { 1_000L })
        set.add("dm")
        set.reopen("dm", "alice")
        repeat(SeenSet.REOPEN_TRIES) { set.spendTry("dm", "spent") }
        (1 until SeenSet.REOPEN_KEYS).forEach { assertTrue("a new signature passes", set.spendTry("dm", "sig$it")) }
        // Eight held. Tried again, the spent one is the most recently used, so the next new signature evicts sig1.
        assertFalse(set.spendTry("dm", "spent"))
        assertTrue("a ninth signature still passes", set.spendTry("dm", "sig${SeenSet.REOPEN_KEYS}"))
        assertFalse("the spent signature kept its count", set.spendTry("dm", "spent"))
        // Eight newer ones push it out: the least recently tried goes, and its count with it.
        (1..SeenSet.REOPEN_KEYS).forEach { assertTrue(set.spendTry("dm", "new$it")) }
        assertTrue("an evicted signature starts again", set.spendTry("dm", "spent"))
    }

    @Test
    fun aSettleBySomeoneElseLeavesTheGuardStanding() {
        val set = SeenSet(clock = { 1_000L })
        set.add("dm")
        set.reopen("dm", "mallory")
        // #347: a copy of the id from its true sender opening must not end a guard another sender's frame set, nor may the
        // other way round.
        set.settle("dm", "alice")
        assertTrue("still guarded", set.guardFor("dm") != null)
        assertFalse(set.contains("dm"))
        set.settle("dm", "mallory")
        assertNull(set.guardFor("dm"))
        assertTrue(set.contains("dm"))
    }

    @Test
    fun aFullGuardTableDropsWhatNoLongerGuardsFirst() {
        var now = 1_000L
        val set = SeenSet(clock = { now })
        reopenAll(set, listOf("settled"), "alice")
        set.settle("settled", "alice")
        reopenAll(set, listOf("lapsing"), "carol")
        now += SeenSet.REOPEN_GUARD_MS
        reopenAll(set, listOf("x"), "bob")
        reopenAll(set, (1..SeenSet.REOPENED_MAX - 3).map { "m$it" }, "mallory")
        // Full. Neither earlier entry guards any more (both have lapsed, the settled one too), so the next two reopens take
        // their places and no live guard's. Settled without a lapse, and the order among such entries: the case below.
        reopenAll(set, listOf("m-next", "m-after"), "mallory")
        assertTrue("bob's guard stands", set.guardFor("x") != null)
        assertTrue("every one of mallory's stands", (1..SeenSet.REOPENED_MAX - 3).all { set.guardFor("m$it") != null })
        assertTrue(set.guardFor("m-next") != null && set.guardFor("m-after") != null)
    }

    @Test
    fun aFullGuardTableDropsTheOldestSettledEntryBeforeAnyLiveGuard() {
        val set = SeenSet(clock = { 1_000L }, reopenedMax = SMALL_TABLE)
        reopenAll(set, listOf("s1", "s2"), "alice")
        set.settle("s1", "alice")
        set.settle("s2", "alice")
        reopenAll(set, listOf("x"), "bob")
        reopenAll(set, (1..SMALL_TABLE - 3).map { "m$it" }, "mallory")
        // Full, nothing lapsed: two settled entries and live guards. One more reopen drops the older settled entry.
        reopenAll(set, listOf("m-next"), "mallory")
        assertTrue("bob's guard stands", set.guardFor("x") != null)
        assertTrue("every one of mallory's stands", (1..SMALL_TABLE - 3).all { set.guardFor("m$it") != null })
        assertTrue("her newest too", set.guardFor("m-next") != null)
        // An entry still held refuses a second reopen inside its window; a dropped one has no refusal left.
        assertFalse("the newer settled entry is kept", set.reopen("s2", "alice"))
        assertTrue("the older settled entry made room", set.reopen("s1", "alice"))
    }

    @Test
    fun aFullGuardTableEvictsTheNewestGuardOfTheSenderHoldingTheMost() {
        val set = SeenSet(clock = { 1_000L })
        reopenAll(set, listOf("x"), "bob")
        reopenAll(set, listOf("y1", "y2"), "carol")
        val flood = (1..SeenSet.REOPENED_MAX - 3).map { "m$it" }
        reopenAll(set, flood, "mallory")
        // Full, every guard live: one sender's reopens push out that sender's own newest guard, never another sender's.
        reopenAll(set, (1..SeenSet.REOPENED_MAX).map { "more$it" }, "mallory")
        assertTrue("bob's guard stands", set.guardFor("x") != null)
        assertTrue("carol's stand", set.guardFor("y1") != null && set.guardFor("y2") != null)
        assertTrue("mallory's newest is kept", set.guardFor("more${SeenSet.REOPENED_MAX}") != null)
        assertNull("the one before it made room", set.guardFor("more${SeenSet.REOPENED_MAX - 1}"))
        assertTrue("and her oldest are untouched", set.guardFor("m1") != null)
        // An evicted guard leaves its id unmarked, so its next copy is admitted and can reopen it afresh.
        assertFalse(set.contains("more${SeenSet.REOPENED_MAX - 1}"))
    }

    @Test
    fun aTieGoesToTheSenderWhoseGuardIsNewest() {
        val set = SeenSet(clock = { 1_000L })
        (1..SeenSet.REOPENED_MAX).forEach { reopenAll(set, listOf("id$it"), "sender$it") }
        reopenAll(set, listOf("late"), "late-sender")
        assertNull("the newest of the tied made room", set.guardFor("id${SeenSet.REOPENED_MAX}"))
        assertTrue("the oldest stands", set.guardFor("id1") != null)
        assertTrue(set.guardFor("late") != null)
    }

    /** Marks each of [ids] seen and reopens it for [sender], as a copy that failed would. */
    private fun reopenAll(
        set: SeenSet,
        ids: List<String>,
        sender: String,
    ) = ids.forEach {
        set.add(it)
        assertTrue("reopened $it", set.reopen(it, sender))
    }

    @Test
    fun theGuardOutlastsTheResetsSecondPass() {
        // The second pass leaves SECOND_PASS_MS after the first, which can leave up to a window after the failure that
        // set the guard; a guard shorter than that lets a copy between the passes mark the id again (#347).
        assertTrue(SeenSet.REOPEN_GUARD_MS >= SeenSet.DEFAULT_TTL_MS + ResetReseal.SECOND_PASS_MS + 60_000L)
    }

    private companion object {
        /** A guard table small enough to fill by hand. */
        const val SMALL_TABLE = 8
    }
}
