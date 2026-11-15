package app.getknit.knit.mesh.lora

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OrderedRoomSink] (ADR 2026-11.gsqu's Stage 6 amendment, part 6c): one post at a time, in order, bounded and counted. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderedRoomSinkTest {
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val delivered = mutableListOf<Int>()
    private var dropped = 0
    private val failures = mutableListOf<Exception>()

    private fun sink(
        capacity: Int = OrderedRoomSink.CAPACITY,
        deliver: suspend (Int) -> Unit,
    ) = OrderedRoomSink(scope, onDropped = { dropped++ }, onFailed = { failures += it }, capacity = capacity, deliver = deliver)

    /** The room takes the first post slowest: each later one still waits for the one before. */
    @Test
    fun aSlowRoomTakesEveryPostInTheOrderOffered() {
        val sink =
            sink { n ->
                delay((N - n) * SLOW_MS)
                delivered += n
            }
        (0 until N).forEach { assertTrue(sink.offer(it)) }
        scope.testScheduler.advanceUntilIdle()
        assertEquals((0 until N).toList(), delivered)
    }

    /**
     * Offers never wait on the room, and past the capacity the newest is refused and counted, not lost silently; once the
     * room catches up the sink takes posts again, and a fresh drain starts after the last one ended.
     */
    @Test
    fun pastItsCapacityTheNewestIsRefusedAndCountedWithoutWaiting() {
        val gate = CompletableDeferred<Unit>()
        val sink =
            sink(capacity = 2) { n ->
                gate.await()
                delivered += n
            }
        assertTrue(sink.offer(0)) // taken up by the drain at once, and held there by the room
        assertTrue(sink.offer(1))
        assertTrue(sink.offer(2))
        assertFalse("the queue holds two", sink.offer(3))
        assertEquals(1, dropped)
        gate.complete(Unit)
        scope.testScheduler.advanceUntilIdle()
        assertEquals(listOf(0, 1, 2), delivered)
        assertTrue("an idle sink starts a drain again", sink.offer(4))
        scope.testScheduler.advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 4), delivered)
    }

    /** One post the room throws on is reported, and the next still lands. */
    @Test
    fun aPostTheRoomThrowsOnCostsThatPostAlone() {
        val sink =
            sink { n ->
                check(n != 1) { "the room failed" }
                delivered += n
            }
        (0 until 3).forEach { sink.offer(it) }
        scope.testScheduler.advanceUntilIdle()
        assertEquals(listOf(0, 2), delivered)
        assertEquals(1, failures.size)
    }

    /**
     * An `Error` out of the room (a linkage error in a model, an OOM) is not a post's failure: it ends that drain and reaches
     * the scope's handler. The sink stays live — the drain's flag is reset and a new drain takes up what waits — so the
     * room loses that post alone and the rest still land, in order.
     */
    @Test
    fun anErrorOutOfTheRoomCostsThatPostAndTheNextStillLandInOrder() {
        val dispatcher = UnconfinedTestDispatcher()
        val uncaught = mutableListOf<Throwable>()
        val supervised = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, t -> uncaught += t })
        val gate = CompletableDeferred<Unit>()
        val sink =
            OrderedRoomSink<Int>(supervised, onDropped = { dropped++ }, onFailed = { failures += it }) { n ->
                if (n == 0) gate.await()
                if (n == 1) throw LinkageError("the room's model failed to load")
                delivered += n
            }
        (0 until 3).forEach { assertTrue(sink.offer(it)) } // 1 and 2 wait behind 0
        gate.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("the post behind the error still lands", listOf(0, 2), delivered)
        assertTrue("and a later one too", sink.offer(3))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(0, 2, 3), delivered)
        assertEquals("the error reached the scope's handler", 1, uncaught.count { it is LinkageError })
        assertEquals(0, dropped)
    }

    private companion object {
        const val N = 8
        const val SLOW_MS = 10L
    }
}
