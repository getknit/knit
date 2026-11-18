package app.getknit.knit.mesh

import app.getknit.knit.data.receipt.ReadMarkEntity
import app.getknit.knit.mesh.protocol.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReadSync], the reader's half of read receipts (ADR 2026-11.z6jw): when a read is told, by which route, and what
 * stays owed. Pure, over an in-memory [ReadMarkStore] and recording routes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadSyncTest {
    private class FakeMarks : ReadMarkStore {
        val rows = LinkedHashMap<String, ReadMarkEntity>()

        /** Holds an [owedFor] after its list was read, until completed: a store read that resumes late. One-shot. */
        var owedForGate: CompletableDeferred<Unit>? = null

        /** How many [owedFor] calls pass before [owedForGate] holds one. */
        var owedForSkip = 0

        override suspend fun mark(marks: List<ReadMarkEntity>) = marks.forEach { rows.putIfAbsent(it.messageId, it) }

        override suspend fun readAmong(ids: Collection<String>) = ids.filterTo(HashSet()) { it in rows }

        override suspend fun owedAmong(ids: Collection<String>) =
            ids.filterTo(HashSet()) { rows[it]?.let { r -> r.toldAt == null } == true }

        override suspend fun owedFor(
            authorId: String,
            limit: Int,
        ): List<String> {
            val owed =
                rows.values
                    .filter { it.authorId == authorId && it.toldAt == null }
                    .sortedBy { it.readAt }
                    .take(limit)
                    .map { it.messageId }
            owedForGate?.let { gate ->
                if (owedForSkip > 0) {
                    owedForSkip--
                } else {
                    owedForGate = null
                    gate.await()
                }
            }
            return owed
        }

        override suspend fun owedAuthors() = rows.values.filter { it.toldAt == null }.mapTo(HashSet()) { it.authorId }

        override suspend fun markTold(
            ids: Collection<String>,
            at: Long,
            rootTag: Long?,
        ) = ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(toldAt = at, toldRoot = rootTag) } }

        override suspend fun reOweNotUnder(
            authorId: String,
            liveRoot: Long,
            since: Long,
        ): Int {
            val back =
                rows.values.filter {
                    it.authorId == authorId && it.toldAt != null && it.toldRoot != liveRoot && it.readAt >= since
                }
            back.forEach { rows[it.messageId] = it.copy(toldAt = null, toldRoot = null) }
            return back.size
        }

        override suspend fun dropOwed() {
            rows.values.filter { it.toldAt == null }.forEach { rows.remove(it.messageId) }
        }

        override suspend fun dropOwedFor(authorId: String) {
            rows.values.filter { it.toldAt == null && it.authorId == authorId }.forEach { rows.remove(it.messageId) }
        }

        override suspend fun prune(
            owedBefore: Long,
            toldBefore: Long,
        ) {
            rows.values
                .filter { (it.toldAt == null && it.readAt < owedBefore) || (it.toldAt != null && it.readAt < toldBefore) }
                .forEach { rows.remove(it.messageId) }
        }

        fun owed(): Set<String> = rows.values.filter { it.toldAt == null }.mapTo(HashSet()) { it.messageId }

        fun toldRoot(id: String): Long? = rows[id]?.toldRoot
    }

    private class Rig(
        budget: Int = ReadSync.DAILY_BUDGET,
        // The wake scope: null (the default) arms nothing, so only flushDue sends; a quick test passes the test's own.
        var wake: CoroutineScope? = null,
    ) {
        var now = 1_000_000L
        var on = true
        val linkedTo = HashSet<String>()
        val near = HashSet<String>()
        val quicked = mutableListOf<Pair<String, List<String>>>()
        var quickAnswer = true
        val spoolCovers = HashSet<String>()
        val marks = FakeMarks()
        val metrics = MeshMetrics()
        val originated = mutableListOf<Pair<String, List<String>>>()
        val pushed = mutableListOf<Pair<String, List<String>>>()
        var originateAnswer: ReadSync.Sent = ReadSync.Sent.Told(ROOT)
        var spoolAnswer: ReadSync.Sent = ReadSync.Sent.Told(ROOT)
        var originateThrows = false

        /** Holds every originate until completed: a hand-off that suspends. Null = returns at once. */
        var originateGate: CompletableDeferred<Unit>? = null

        /** The root the author's session holds now, as `RatchetSessions.rootTagFor` would answer. */
        var liveRoot: Long? = ROOT

        /** Holds every root read after it was taken until completed: a read that resumes late. Null = returns at once. */
        var liveRootGate: CompletableDeferred<Unit>? = null

        val sync =
            ReadSync(
                marks = marks,
                enabled = { on },
                linked = { it in linkedTo },
                spoolPresent = { it in spoolCovers },
                originate = { author, ids ->
                    originated += author to ids
                    originateGate?.await()
                    if (originateThrows) error("process died after the hand-off")
                    originateAnswer
                },
                spoolPush = { author, ids ->
                    pushed += author to ids
                    spoolAnswer
                },
                now = { now },
                nearby = { it in near },
                quick = { author, ids ->
                    quicked += author to ids
                    quickAnswer
                },
                liveRoot = {
                    val root = liveRoot
                    liveRootGate?.await()
                    root
                },
                wakeScope = { wake },
                metrics = metrics,
                budget = budget,
            )

        suspend fun read(
            vararg ids: String,
            author: String = ALICE,
        ) = sync.onRead(ids.map { ReadMarkEntity(it, author, author, readAt = now) })
    }

    @Test
    fun `an absent author waits fifteen minutes, then one custodied batch tells every owed read`() =
        runTest {
            val rig = Rig()
            rig.read("m1", "m2")
            rig.now += ReadSync.ABSENT_BASE_MS - 1
            rig.sync.flushDue()
            assertTrue(rig.originated.isEmpty())
            rig.now += 1
            rig.sync.flushDue()
            assertEquals(listOf(ALICE to listOf("m1", "m2")), rig.originated)
            assertEquals(emptySet<String>(), rig.marks.owed())
            assertEquals(ROOT, rig.marks.toldRoot("m1"))
            assertEquals(1L, rig.metrics.receipts().readsCustodied)
            // Told: nothing re-sends it, ever (custody retries the frame, never this class).
            rig.now += 48 * 60 * 60_000L
            rig.sync.flushDue()
            assertEquals(1, rig.originated.size)
        }

    @Test
    fun `a linked author gets the batch forty-five seconds after the oldest read`() =
        runTest {
            val rig = Rig()
            rig.linkedTo += ALICE
            rig.read("m1")
            rig.now += ReadSync.LINKED_DEADLINE_MS
            rig.sync.flushDue()
            assertEquals(listOf(ALICE to listOf("m1")), rig.originated)
        }

    @Test
    fun `the absent deadline doubles per standalone send and a ride or a link resets it`() =
        runTest {
            val rig = Rig()
            rig.read("m1")
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.sync.flushDue()
            rig.read("m2")
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.sync.flushDue()
            assertEquals(1, rig.originated.size) // second batch waits 30 min
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.sync.flushDue()
            assertEquals(2, rig.originated.size)
            // A ride resets the doubling.
            rig.read("m3")
            rig.sync.told(ALICE, listOf("m3"), ROOT, riders = 1)
            rig.read("m4")
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.sync.flushDue()
            assertEquals(3, rig.originated.size)
            assertEquals(listOf("m4"), rig.originated.last().second)
        }

    @Test
    fun `a read that rides is told and never sent on its own`() =
        runTest {
            val rig = Rig()
            rig.read("m1", "m2")
            val riders = rig.sync.takeRiders(ALICE, CAPS, limit = 12, exclude = listOf("m2"))
            assertEquals(listOf("m1"), riders)
            // Claimed: a second seal in flight does not take it too.
            assertEquals(listOf("m2"), rig.sync.takeRiders(ALICE, CAPS, limit = 12, exclude = emptyList()))
            rig.sync.told(ALICE, listOf("m1", "m2"), ROOT, riders = 2)
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.sync.flushDue()
            assertTrue(rig.originated.isEmpty())
            assertEquals(2L, rig.metrics.receipts().readsRidden)
        }

    @Test
    fun `a read told while another caller listed it is not taken again`() =
        runTest {
            val rig = Rig()
            rig.read("m1")
            // Listed as owed, then told by another frame before the claim's re-read: nothing to carry.
            rig.marks.markTold(listOf("m1"), rig.now, ROOT)
            assertTrue(rig.sync.takeRiders(ALICE, CAPS, limit = 12, exclude = emptyList()).isEmpty())
        }

    @Test
    fun `a batch due while another flush holds the read goes once that flush lets go`() =
        runTest {
            val rig = Rig(wake = backgroundScope)
            rig.linkedTo += ALICE
            rig.read("m1")
            // The deadline's wake starts sleeping now, on virtual time no step advances: only a release can send early.
            runCurrent()
            rig.now += ReadSync.LINKED_DEADLINE_MS
            // A batch under the old root told m1 and is still settling, its root read taken before the reset and resumed
            // late (#336): it holds m1's claim until it ends.
            rig.liveRootGate = CompletableDeferred()
            val settling = launch { rig.sync.flushDue() }
            runCurrent()
            assertEquals(1, rig.originated.size)
            // The reset commits meanwhile: m1 is owed again, and its batch is due a deadline later.
            rig.liveRoot = OTHER_ROOT
            rig.originateAnswer = ReadSync.Sent.Told(OTHER_ROOT)
            rig.sync.onRootChanged(ALICE, OTHER_ROOT)
            assertEquals(setOf("m1"), rig.marks.owed())
            rig.now += ReadSync.LINKED_DEADLINE_MS
            rig.sync.flushDue()
            // The old batch lets go: the due batch goes now, not a deadline after the old one ended.
            rig.liveRootGate?.complete(Unit)
            settling.join()
            runCurrent()
            assertEquals(listOf(ALICE to listOf("m1"), ALICE to listOf("m1")), rig.originated)
            assertEquals(emptySet<String>(), rig.marks.owed())
            assertEquals(OTHER_ROOT, rig.marks.toldRoot("m1"))
        }

    @Test
    fun `a batch due while a seal holds every owed read goes once that seal gives them back`() =
        runTest {
            val rig = Rig(wake = backgroundScope)
            rig.linkedTo += ALICE
            rig.read("m1")
            // The deadline's wake starts sleeping now, on virtual time no step advances: only a release can send early.
            runCurrent()
            // A seal toward alice claims the read as a rider and is still in flight at the deadline.
            val riders = rig.sync.takeRiders(ALICE, CAPS, limit = 12, exclude = emptyList())
            assertEquals(listOf("m1"), riders)
            rig.now += ReadSync.LINKED_DEADLINE_MS
            rig.sync.flushDue()
            assertTrue("held by the seal: nothing to send yet", rig.originated.isEmpty())
            // The seal declined: the rider goes back, still owed, and the due batch goes at once.
            rig.sync.giveBack(riders)
            runCurrent()
            assertEquals(listOf(ALICE to listOf("m1")), rig.originated)
            assertEquals(emptySet<String>(), rig.marks.owed())
        }

    @Test
    fun `a batch whose listed read was told meanwhile still waits for a read a seal holds`() =
        runTest {
            val rig = Rig(wake = backgroundScope)
            rig.linkedTo += ALICE
            rig.read("m1", "m2")
            // The deadline's wake starts sleeping now, on virtual time no step advances: only a release can send early.
            runCurrent()
            // Two seals in flight: one carries m2 as a rider, the other m1.
            assertEquals(listOf("m2"), rig.sync.takeRiders(ALICE, CAPS, limit = 1, exclude = listOf("m1")))
            assertEquals(listOf("m1"), rig.sync.takeRiders(ALICE, CAPS, limit = 1, exclude = emptyList()))
            rig.now += ReadSync.LINKED_DEADLINE_MS
            // The due batch lists m1 and m2, and its store read resumes late.
            rig.marks.owedForGate = CompletableDeferred()
            val gate = checkNotNull(rig.marks.owedForGate)
            val due = launch { rig.sync.flushDue() }
            runCurrent()
            // Meanwhile m1's seal tells and lets go: the batch claims m1, finds it told, and has taken nothing.
            rig.sync.told(ALICE, listOf("m1"), ROOT, riders = 1)
            gate.complete(Unit)
            due.join()
            assertTrue("m2 is held: nothing to send yet", rig.originated.isEmpty())
            // m2's seal falls through untold: the due batch goes now, not at the next read or restart (#336).
            rig.sync.giveBack(listOf("m2"))
            runCurrent()
            assertEquals(listOf(ALICE to listOf("m2")), rig.originated)
            assertEquals(emptySet<String>(), rig.marks.owed())
        }

    @Test
    fun `a batch whose held read is let go while it rechecks what is owed keeps its deadline`() =
        runTest {
            val rig = Rig()
            rig.linkedTo += ALICE
            rig.read("m1")
            val riders = rig.sync.takeRiders(ALICE, CAPS, limit = 12, exclude = emptyList())
            rig.now += ReadSync.LINKED_DEADLINE_MS
            // The due batch finds m1 held and takes nothing; its second store read, what is still owed, resumes late.
            rig.marks.owedForSkip = 1
            rig.marks.owedForGate = CompletableDeferred()
            val gate = checkNotNull(rig.marks.owedForGate)
            val due = launch { rig.sync.flushDue() }
            runCurrent()
            // The seal lets go untold before the batch could defer to it: the release itself sends nothing.
            rig.sync.giveBack(riders)
            gate.complete(Unit)
            due.join()
            assertTrue(rig.originated.isEmpty())
            // m1 is owed and free by then: the deadline stays, and the next due pass sends it.
            rig.sync.flushDue()
            assertEquals(listOf(ALICE to listOf("m1")), rig.originated)
        }

    @Test
    fun `without a wake scope a batch due while a seal holds its reads keeps its deadline for the next due pass`() =
        runTest {
            val rig = Rig()
            rig.linkedTo += ALICE
            rig.read("m1")
            val riders = rig.sync.takeRiders(ALICE, CAPS, limit = 12, exclude = emptyList())
            rig.now += ReadSync.LINKED_DEADLINE_MS
            rig.sync.flushDue()
            rig.sync.giveBack(riders)
            assertTrue("no wake scope: the release sends nothing itself", rig.originated.isEmpty())
            rig.sync.flushDue()
            assertEquals(listOf(ALICE to listOf("m1")), rig.originated)
        }

    @Test
    fun `riders given back stay owed and ride the next frame`() =
        runTest {
            val rig = Rig()
            rig.read("m1")
            val riders = rig.sync.takeRiders(ALICE, CAPS, limit = 12, exclude = emptyList())
            rig.sync.giveBack(riders)
            assertEquals(setOf("m1"), rig.marks.owed())
            assertEquals(listOf("m1"), rig.sync.takeRiders(ALICE, CAPS, limit = 12, exclude = emptyList()))
        }

    @Test
    fun `the spool takes it when it covers the author, and a push no spool took falls to custody`() =
        runTest {
            val rig = Rig()
            rig.spoolCovers += ALICE
            rig.read("m1")
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.sync.flushDue()
            assertEquals(1, rig.pushed.size)
            assertTrue(rig.originated.isEmpty())
            assertEquals(emptySet<String>(), rig.marks.owed())
            assertEquals(1L, rig.metrics.receipts().readsSpooled)

            rig.spoolAnswer = ReadSync.Sent.NotPushed
            rig.read("m2")
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.sync.flushDue()
            assertEquals(listOf(ALICE to listOf("m2")), rig.originated)
        }

    @Test
    fun `a sealed batch nothing carried leaves the marks owed`() =
        runTest {
            val rig = Rig(budget = 0)
            rig.spoolCovers += ALICE
            rig.spoolAnswer = ReadSync.Sent.NotPushed
            rig.read("m1")
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.sync.flushDue()
            assertEquals(setOf("m1"), rig.marks.owed())
            assertEquals(1L, rig.metrics.receipts().readsBudgetHeld)
        }

    @Test
    fun `a decline stays owed and goes when the root moves`() =
        runTest {
            val rig = Rig()
            rig.originateAnswer = ReadSync.Sent.Declined
            rig.read("m1")
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.sync.flushDue()
            assertEquals(setOf("m1"), rig.marks.owed())
            rig.originateAnswer = ReadSync.Sent.Told(ROOT)
            rig.sync.onRootChanged(ALICE, ROOT)
            assertEquals(2, rig.originated.size)
            assertEquals(emptySet<String>(), rig.marks.owed())
        }

    @Test
    fun `a root change owes again what was told under another root, once`() =
        runTest {
            val rig = Rig()
            rig.read("m1")
            rig.sync.told(ALICE, listOf("m1"), ROOT, riders = 0)
            rig.sync.onRootChanged(ALICE, ROOT) // same root: nothing
            assertEquals(emptySet<String>(), rig.marks.owed())
            rig.sync.onRootChanged(ALICE, OTHER_ROOT)
            assertEquals(setOf("m1"), rig.marks.owed())
            assertEquals(1L, rig.metrics.receipts().readsReOwed)
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.sync.flushDue()
            assertEquals(listOf(ALICE to listOf("m1")), rig.originated)
            rig.sync.onRootChanged(ALICE, ROOT) // the frame above was told under ROOT in the rig
            assertEquals(emptySet<String>(), rig.marks.owed())
        }

    /** B2: a reset that commits while the standalone hand-off is suspended leaves no read told under the dead root. */
    @Test
    fun `a root that moves during the hand-off owes the read again`() =
        runTest {
            val rig = Rig()
            rig.read("m1")
            rig.now += ABSENT
            val gate = CompletableDeferred<Unit>()
            rig.originateGate = gate
            val flush = launch { rig.sync.flushDue() }
            runCurrent()
            assertEquals("sealed under ROOT and in the hand-off", 1, rig.originated.size)
            // The author's reset commits now: nothing is told yet, so the root move alone re-owes nothing.
            rig.liveRoot = OTHER_ROOT
            rig.sync.onRootChanged(ALICE, OTHER_ROOT)
            gate.complete(Unit)
            flush.join()
            assertEquals("told under ROOT, then owed again under the live root", setOf("m1"), rig.marks.owed())
            assertEquals(1L, rig.metrics.receipts().readsReOwed)
            // And sent again: rescheduled at once, on the doubled deadline the custodied send earned.
            rig.originateGate = null
            rig.now += 2 * ABSENT
            rig.sync.flushDue()
            assertEquals(ALICE to listOf("m1"), rig.originated.last())
            assertEquals(2, rig.originated.size)
        }

    /** B2's ride half: a rider told under a root the author has since left is owed again at once. */
    @Test
    fun `a rider told under a root since replaced is owed again`() =
        runTest {
            val rig = Rig()
            rig.read("m1")
            val riders = rig.sync.takeRiders(ALICE, CAPS, limit = 12, exclude = emptyList())
            rig.liveRoot = OTHER_ROOT
            rig.sync.told(ALICE, riders, ROOT, riders = riders.size)
            assertEquals(setOf("m1"), rig.marks.owed())
            // Told under the live root, it stays told.
            rig.sync.told(ALICE, listOf("m1"), OTHER_ROOT, riders = 0)
            assertEquals(emptySet<String>(), rig.marks.owed())
        }

    @Test
    fun `a death between the hand-off and told costs one duplicate`() =
        runTest {
            val rig = Rig()
            rig.read("m1")
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.originateThrows = true
            runCatching { rig.sync.flushDue() }
            assertEquals(setOf("m1"), rig.marks.owed())
            // The restart: a fresh ReadSync over the same marks re-batches and sends it once more.
            rig.originateThrows = false
            val restarted =
                ReadSync(
                    rig.marks,
                    { true },
                    { false },
                    { false },
                    { author, ids ->
                        rig.originated += author to ids
                        ReadSync.Sent.Told(ROOT)
                    },
                    { _, _ -> ReadSync.Sent.NotPushed },
                    { rig.now },
                )
            restarted.restore()
            rig.now += ReadSync.ABSENT_BASE_MS
            restarted.flushDue()
            assertEquals(2, rig.originated.size)
            assertEquals(emptySet<String>(), rig.marks.owed())
        }

    @Test
    fun `the setting off drops what is owed and zeroes every mask`() =
        runTest {
            val rig = Rig()
            rig.read("m1")
            rig.read("m0")
            rig.sync.told(ALICE, listOf("m0"), ROOT, riders = 0)
            rig.on = false
            rig.sync.onSettingChanged(false)
            assertEquals(emptySet<String>(), rig.marks.owed())
            assertEquals(emptySet<String>(), rig.sync.readSet(CAPS, listOf("m0", "m1")))
            assertTrue(rig.sync.takeRiders(ALICE, CAPS, 12, emptyList()).isEmpty())
            rig.read("m2")
            assertEquals(emptySet<String>(), rig.marks.owed())
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.sync.flushDue()
            assertTrue(rig.originated.isEmpty())
            // On again: nothing retroactive.
            rig.on = true
            rig.sync.onSettingChanged(true)
            rig.sync.flushDue()
            assertTrue(rig.originated.isEmpty())
        }

    @Test
    fun `a blocked or removed author is told nothing more`() =
        runTest {
            val rig = Rig()
            rig.read("a1", author = ALICE)
            rig.read("b1", author = BOB)
            rig.sync.onAuthorGone(ALICE)
            rig.now += ABSENT
            rig.sync.flushDue()
            assertEquals(listOf(BOB to listOf("b1")), rig.originated)
            assertTrue(
                rig.marks.rows.keys
                    .none { it == "a1" },
            )
        }

    @Test
    fun `nothing read-related toward an author who reads no mask`() =
        runTest {
            val rig = Rig()
            rig.read("m1")
            assertNull(rig.sync.readSet(Protocol.CAP_RATCHET, listOf("m1")))
            assertNull(rig.sync.readSet(Protocol.CAP_READ_RECEIPTS, listOf("m1"))) // no ratchet, no seal
            assertTrue(rig.sync.takeRiders(ALICE, Protocol.CAP_RATCHET, 12, emptyList()).isEmpty())
            assertEquals(setOf("m1"), rig.sync.readSet(CAPS, listOf("m1", "x")))
            assertNull(ReadRiding.NONE.readSet(CAPS, listOf("m1")))
        }

    @Test
    fun `a batch holds at most sixty-four ids and the rest follow`() =
        runTest {
            val rig = Rig()
            rig.linkedTo += ALICE
            val ids = (0 until 70).map { "m$it" }
            ids.forEach {
                rig.read(it)
                rig.now += 1
            }
            rig.now += ReadSync.LINKED_DEADLINE_MS
            rig.sync.flushDue()
            assertEquals(
                64,
                rig.originated
                    .single()
                    .second.size,
            )
            rig.now += ReadSync.LINKED_DEADLINE_MS
            rig.sync.flushDue()
            assertEquals(6, rig.originated[1].second.size)
            assertEquals(emptySet<String>(), rig.marks.owed())
        }

    @Test
    fun `the daily budget holds standalone batches past it and frees after a day`() =
        runTest {
            val rig = Rig(budget = 2)
            listOf(ALICE, BOB, CAROL).forEach { rig.read("m-$it", author = it) }
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.sync.flushDue()
            assertEquals(2, rig.originated.size)
            assertEquals(1L, rig.metrics.receipts().readsBudgetHeld)
            assertEquals(1, rig.marks.owed().size)
            rig.now += 24 * 60 * 60_000L
            rig.sync.flushDue()
            assertEquals(3, rig.originated.size)
        }

    @Test
    fun `a restart re-batches every author still owed`() =
        runTest {
            val rig = Rig()
            rig.read("a1", author = ALICE)
            rig.read("b1", author = BOB)
            val again =
                ReadSync(rig.marks, { true }, { false }, { false }, { author, ids ->
                    rig.originated += author to ids
                    ReadSync.Sent.Told(ROOT)
                }, { _, _ -> ReadSync.Sent.NotPushed }, { rig.now })
            again.restore()
            rig.now += ReadSync.ABSENT_BASE_MS
            again.flushDue()
            assertEquals(setOf(ALICE, BOB), rig.originated.map { it.first }.toSet())
        }

    @Test
    fun `a near author gets one quick copy a second after the read, and the standalone form still runs`() =
        runTest {
            val rig = Rig(wake = backgroundScope)
            rig.near += ALICE
            rig.linkedTo += ALICE
            rig.read("m1", "m2")
            advanceTimeBy(ReadSync.QUICK_DELAY_MS - 1)
            runCurrent()
            assertTrue(rig.quicked.isEmpty())
            // A read inside the window joins the same copy.
            rig.read("m3")
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(ALICE to listOf("m1", "m2", "m3")), rig.quicked)
            assertEquals(1L, rig.metrics.receipts().readsQuick)
            // Best effort: nothing told, nothing custodied, and the deadline's frame still carries every read.
            assertEquals(setOf("m1", "m2", "m3"), rig.marks.owed())
            assertEquals(0L, rig.metrics.receipts().readsSealed)
            rig.now += ReadSync.LINKED_DEADLINE_MS
            rig.sync.flushDue()
            assertEquals(listOf(ALICE to listOf("m1", "m2", "m3")), rig.originated)
        }

    @Test
    fun `a later burst sends only its own reads quick, and a copy nothing sealed is tried again`() =
        runTest {
            val rig = Rig(wake = backgroundScope)
            rig.near += ALICE
            rig.read("m1")
            advanceTimeBy(ReadSync.QUICK_DELAY_MS)
            runCurrent()
            rig.read("m2")
            advanceTimeBy(ReadSync.QUICK_DELAY_MS)
            runCurrent()
            assertEquals(listOf(ALICE to listOf("m1"), ALICE to listOf("m2")), rig.quicked)

            // No session to seal under: the ids are not remembered, so the next burst carries them too.
            rig.quickAnswer = false
            rig.read("m3")
            advanceTimeBy(ReadSync.QUICK_DELAY_MS)
            runCurrent()
            rig.quickAnswer = true
            rig.read("m4")
            advanceTimeBy(ReadSync.QUICK_DELAY_MS)
            runCurrent()
            assertEquals(ALICE to listOf("m3", "m4"), rig.quicked.last())
            assertEquals(3L, rig.metrics.receipts().readsQuick)
        }

    @Test
    fun `no quick copy toward an author out of reach, with the setting off, or once a ride told the reads`() =
        runTest {
            val rig = Rig(wake = backgroundScope)
            rig.read("far")
            advanceTimeBy(ReadSync.QUICK_DELAY_MS)
            runCurrent()

            // The setting turned off with a wake armed: the wake's own check is all that stops it.
            rig.near += ALICE
            rig.read("off")
            rig.on = false
            advanceTimeBy(ReadSync.QUICK_DELAY_MS)
            runCurrent()

            rig.on = true
            rig.read("rode")
            rig.sync.told(ALICE, listOf("far", "off", "rode"), ROOT, riders = 3)
            advanceTimeBy(ReadSync.QUICK_DELAY_MS)
            runCurrent()
            assertTrue(rig.quicked.isEmpty())
            assertEquals(0L, rig.metrics.receipts().readsQuick)
        }

    @Test
    fun `a spool-covered author gets the push a second after the read, told, with no custody`() =
        runTest {
            val rig = Rig(wake = backgroundScope)
            rig.spoolCovers += ALICE
            rig.read("m1", "m2")
            advanceTimeBy(ReadSync.QUICK_DELAY_MS - 1)
            runCurrent()
            assertTrue(rig.pushed.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(ALICE to listOf("m1", "m2")), rig.pushed)
            assertEquals(emptySet<String>(), rig.marks.owed())
            assertEquals(ROOT, rig.marks.toldRoot("m1"))
            val r = rig.metrics.receipts()
            assertEquals(1L, r.readsSpooled)
            assertEquals(0L, r.readsQuick)
            // Told: the deadline has nothing left to send, by the spool or by custody.
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.sync.flushDue()
            assertEquals(1, rig.pushed.size)
            assertTrue(rig.originated.isEmpty())
        }

    @Test
    fun `a quick push no spool took leaves the reads to the deadline, which may fall to custody`() =
        runTest {
            val rig = Rig(wake = backgroundScope)
            rig.spoolCovers += ALICE
            rig.spoolAnswer = ReadSync.Sent.NotPushed
            rig.read("m1")
            advanceTimeBy(ReadSync.QUICK_DELAY_MS)
            runCurrent()
            assertEquals(1, rig.pushed.size)
            assertEquals(setOf("m1"), rig.marks.owed())
            // Nothing custodied from the wake: the budget is the deadline's alone.
            assertTrue(rig.originated.isEmpty())
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.sync.flushDue()
            assertEquals(listOf(ALICE to listOf("m1")), rig.originated)
        }

    @Test
    fun `a quick push that finds every read held by a seal leaves them to the deadline, not to the seal's release`() =
        runTest {
            val rig = Rig(wake = backgroundScope)
            rig.spoolCovers += ALICE
            rig.spoolAnswer = ReadSync.Sent.NotPushed
            rig.read("m1")
            // A seal toward alice carries the read as a rider across the quick wake.
            val riders = rig.sync.takeRiders(ALICE, CAPS, limit = 12, exclude = emptyList())
            assertEquals(listOf("m1"), riders)
            advanceTimeBy(ReadSync.QUICK_DELAY_MS)
            runCurrent()
            assertTrue(rig.pushed.isEmpty())
            // The seal gives it back: the wake was best effort, so its release sends no standalone batch early.
            rig.sync.giveBack(riders)
            runCurrent()
            assertTrue(rig.pushed.isEmpty())
            assertTrue(rig.originated.isEmpty())
            rig.now += ReadSync.ABSENT_BASE_MS
            rig.sync.flushDue()
            assertEquals(listOf(ALICE to listOf("m1")), rig.pushed)
            assertEquals(listOf(ALICE to listOf("m1")), rig.originated)
        }

    @Test
    fun `a near author a spool also covers gets both the copy and the push`() =
        runTest {
            val rig = Rig(wake = backgroundScope)
            rig.near += ALICE
            rig.spoolCovers += ALICE
            rig.read("m1")
            advanceTimeBy(ReadSync.QUICK_DELAY_MS)
            runCurrent()
            // The link copy first (best effort, the fastest), then the push that tells.
            assertEquals(listOf(ALICE to listOf("m1")), rig.quicked)
            assertEquals(listOf(ALICE to listOf("m1")), rig.pushed)
            assertEquals(emptySet<String>(), rig.marks.owed())
        }

    @Test
    fun `an author blocked inside the window gets no quick copy, and a later read arms again`() =
        runTest {
            val rig = Rig(wake = backgroundScope)
            rig.near += ALICE
            rig.read("m1")
            rig.sync.onAuthorGone(ALICE)
            advanceTimeBy(ReadSync.QUICK_DELAY_MS)
            runCurrent()
            assertTrue(rig.quicked.isEmpty())

            rig.read("m2")
            advanceTimeBy(ReadSync.QUICK_DELAY_MS)
            runCurrent()
            assertEquals(listOf(ALICE to listOf("m2")), rig.quicked)
        }

    @Test
    fun `a session stopped inside the window leaves the author armable`() =
        runTest {
            val session = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val rig = Rig(wake = session)
            rig.near += ALICE
            rig.read("m1")
            session.cancel()
            runCurrent()
            // The next session's first read arms a fresh wake, carrying the read the stopped one never sent.
            rig.wake = backgroundScope
            rig.read("m2")
            advanceTimeBy(ReadSync.QUICK_DELAY_MS)
            runCurrent()
            assertEquals(listOf(ALICE to listOf("m1", "m2")), rig.quicked)
        }

    @Test
    fun `prune drops owed marks past a day and told ones past two`() =
        runTest {
            val rig = Rig()
            rig.read("owed")
            rig.read("told")
            rig.sync.told(ALICE, listOf("told"), ROOT, riders = 0)
            rig.now += ReadSync.OWED_TTL_MS + 1
            rig.sync.prune()
            assertEquals(setOf("told"), rig.marks.rows.keys)
            rig.now += ReadSync.OWED_TTL_MS
            rig.sync.prune()
            assertTrue(rig.marks.rows.isEmpty())
        }

    private companion object {
        const val ALICE = "alice"
        const val BOB = "bob"
        const val CAROL = "carol"
        const val ROOT = 11L
        const val OTHER_ROOT = 22L
        val CAPS = Protocol.CAP_RATCHET or Protocol.CAP_READ_RECEIPTS
        const val ABSENT = ReadSync.ABSENT_BASE_MS
    }
}
