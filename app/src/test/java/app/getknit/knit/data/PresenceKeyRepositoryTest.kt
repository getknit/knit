package app.getknit.knit.data

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import app.getknit.knit.data.presencekey.PresenceKeyRepository
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.bluetooth.TagSchedule
import app.getknit.knit.mesh.crypto.PresenceKeyPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * [PresenceKeyRepository.markStore]'s write over the real Room SQL (ADR 2026-11.xp6d, step 5b, A3): it commits only at
 * `synchronous=FULL`, checked inside the committing transaction, puts the connection back to NORMAL after, refuses to run
 * inside an enclosing transaction, and marks only the current key: a generation a fresh pair demoted, the next key or a
 * gone one throws, so nothing airs under it. The JVM runs the framework SQLite in memory, not SQLCipher in WAL: what it
 * pins is the order and the checks; the device count (`presenceMarksDurable` against `presenceMarkWrites`) is the rest.
 */
class PresenceKeyRepositoryTest : RoomDbTest() {
    private val repo by lazy { PresenceKeyRepository(db) }
    private val metrics = MeshMetrics()
    private val marks by lazy { repo.markStore(metrics) }

    @Test
    fun aWriteCommitsAtFullThenLeavesTheConnectionAtNormal() =
        runTest {
            val (current, _) = mintPair()
            setSynchronous(NORMAL) // as KnitDatabase's WAL connections run: the write must raise it, not find it raised

            marks.write(current, TagSchedule.Mark(slot = 40, restart = 2))

            assertEquals(TagSchedule.Mark(40, 2), marks.read(current))
            assertEquals("the in-transaction check saw FULL", 1L, metrics.keys().presenceMarksDurable)
            assertEquals(1L, metrics.keys().presenceMarkWrites)
            assertEquals("back to NORMAL on the committing connection", NORMAL, synchronousInAWriteTransaction())
            assertEquals(0L, metrics.keys().presenceMarkSyncRestoreFailed)
            assertEquals(0L, metrics.keys().presenceMarkNotFull)
            assertEquals(0L, metrics.keys().presenceMarkRefused)
        }

    @Test
    fun aWriteInsideAnOpenTransactionThrowsAndWritesNothing() =
        runTest {
            val (current, _) = mintPair()

            // On a thread of its own, joined with a bound: a write that missed the open transaction would block in
            // `runBlocking` on the connection the transaction holds, which no coroutine timeout can end. The write runs on
            // the transaction's own thread, where the refusal reads its thread-local.
            val outcome = AtomicReference<Throwable?>()
            val writer =
                thread(isDaemon = true, name = "mark-in-transaction") {
                    runBlocking {
                        db.withWriteTransaction {
                            outcome.set(runCatching { marks.write(current, TagSchedule.Mark(slot = 7, restart = 0)) }.exceptionOrNull())
                        }
                    }
                }
            writer.join(WEDGE_MS)
            assertFalse("a mark write inside a transaction wedged instead of throwing", writer.isAlive)
            assertTrue("refused: ${outcome.get()}", outcome.get() is IllegalStateException)

            assertNull("no mark landed", marks.read(current))
            assertEquals(1L, metrics.keys().presenceMarkWrites)
            assertEquals(0L, metrics.keys().presenceMarksDurable)
        }

    @Test
    fun aGenerationAFreshPairDemotedTheNextKeyAndAGoneOneAreNeverMarked() =
        runTest {
            val (old, oldNext) = mintPair()
            marks.write(old, TagSchedule.Mark(slot = 10, restart = 0))
            // A removal: a fresh pair, the old current kept as the previous key (never aired again), its next key discarded.
            val fresh = own(generation = 300, activatedAt = 300)
            val freshNext = own(generation = 301, activatedAt = null)
            repo.applyOwn(listOf(fresh, freshNext), emptyList(), keep = setOf(old, 300L, 301L))

            val demoted = assertThrows(IllegalStateException::class.java) { marks.write(old, TagSchedule.Mark(slot = 11, restart = 0)) }
            assertFalse("a refusal never names the generation: ${demoted.message}", demoted.message.orEmpty().contains(old.toString()))
            assertEquals("the previous key's mark is as it was", TagSchedule.Mark(10, 0), marks.read(old))
            assertThrows(IllegalStateException::class.java) { marks.write(301, TagSchedule.Mark(slot = 11, restart = 0)) }
            assertThrows(IllegalStateException::class.java) { marks.write(oldNext, TagSchedule.Mark(slot = 11, restart = 0)) }
            assertNull(marks.read(301))

            marks.write(300, TagSchedule.Mark(slot = 11, restart = 0))
            assertEquals(TagSchedule.Mark(11, 0), marks.read(300))
            assertEquals("each refusal still put the connection back", NORMAL, synchronousInAWriteTransaction())
            assertEquals(5L, metrics.keys().presenceMarkWrites)
            assertEquals(2L, metrics.keys().presenceMarksDurable)
            assertEquals("a demotion is a refusal, never a FULL fault", 3L, metrics.keys().presenceMarkRefused)
            assertEquals(0L, metrics.keys().presenceMarkNotFull)
        }

    /** A current key (generation 100) and a next one (101), as the engine's first mint writes them. */
    private suspend fun mintPair(): Pair<Long, Long> {
        repo.applyOwn(listOf(own(100, activatedAt = 100), own(101, activatedAt = null)), emptyList(), keep = setOf(100, 101))
        return 100L to 101L
    }

    private fun own(
        generation: Long,
        activatedAt: Long?,
    ) = PresenceKeyPolicy.Own(generation, ByteArray(32) { generation.toByte() }, mintedAt = generation, activatedAt, setOf("alice"))

    private suspend fun setSynchronous(level: Long) =
        db.useWriterConnection { connection -> connection.usePrepared("PRAGMA synchronous=$level") { it.step() } }

    // Read inside a write transaction: the connection a commit would run on.
    private suspend fun synchronousInAWriteTransaction(): Long =
        db.useWriterConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                usePrepared("PRAGMA synchronous") {
                    check(it.step())
                    it.getLong(0)
                }
            }
        }

    private companion object {
        const val NORMAL = 1L

        /** How long a refused write may take before the test calls it a wedge. */
        const val WEDGE_MS = 10_000L
    }
}
