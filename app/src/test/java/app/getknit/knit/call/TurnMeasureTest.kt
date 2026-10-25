package app.getknit.knit.call

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the phone measures its TURN servers (C-7.6-52): each target once per network, again after a network change,
 * never while a call is being set up, and nothing at all with no target. All on virtual time: the probe is a fake that
 * answers when the test says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnMeasureTest {
    private val a = "a.example:3478"
    private val b = "b.example:3478"
    private val c = "c.example:3478"

    private class Rig(
        test: TestScope,
    ) {
        val ranking = TurnRanking(clock = { 0L })
        val targets = MutableStateFlow<List<String>>(emptyList())
        val routes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val settingUp = MutableStateFlow(false)
        val asked = mutableListOf<String>()

        // Per entry, what the next probe of it answers: an immediate result, or one the test completes later.
        val answers = mutableMapOf<String, ArrayDeque<CompletableDeferred<TurnProbe.Result>>>()

        fun answer(
            entry: String,
            result: TurnProbe.Result,
        ) = answers.getOrPut(entry) { ArrayDeque() }.addLast(CompletableDeferred(result))

        fun hold(entry: String): CompletableDeferred<TurnProbe.Result> =
            CompletableDeferred<TurnProbe.Result>().also { answers.getOrPut(entry) { ArrayDeque() }.addLast(it) }

        val measure =
            TurnMeasure(
                ranking = ranking,
                probe =
                    TurnProbe { entry ->
                        val name = "${entry.host}:${entry.port}"
                        asked += name
                        answers[name]?.removeFirstOrNull()?.await() ?: TurnProbe.Result.Silent
                    },
                targets = targets,
                routeChanges = routes,
                callWaiting = settingUp,
                scope = test.backgroundScope,
            )

        // Started, and its two collectors subscribed: a route change emitted before that would reach nobody.
        fun start(test: TestScope) {
            measure.start()
            test.runCurrent()
            check(routes.subscriptionCount.value == 1) { "the route collector is not listening" }
        }
    }

    @Test
    fun eachTargetIsMeasuredOncePerNetworkAndANewOneWhenAHelloBringsIt() =
        runTest {
            val rig = Rig(this)
            rig.start(this)
            rig.answer(a, TurnProbe.Result.Answered(40))
            rig.answer(b, TurnProbe.Result.Answered(25))
            rig.targets.value = listOf(a, b)
            runCurrent()
            assertEquals(listOf(a, b), rig.asked.sorted())
            assertEquals(40L, rig.ranking.score(a))
            assertEquals(25L, rig.ranking.score(b))

            // The same entries again (another spool connecting with the same list) measure nothing; a new one is.
            rig.answer(c, TurnProbe.Result.Silent)
            rig.targets.value = listOf(b, a, c)
            runCurrent()
            assertEquals(listOf(a, b, c), rig.asked.sorted())
            assertTrue(rig.ranking.measured(c))
            assertNull(rig.ranking.score(c))
        }

    @Test
    fun aNetworkChangeDropsTheScoresAndMeasuresAgain() =
        runTest {
            val rig = Rig(this)
            rig.start(this)
            rig.answer(a, TurnProbe.Result.Answered(40))
            rig.targets.value = listOf(a)
            runCurrent()
            assertEquals(40L, rig.ranking.score(a))

            rig.answer(a, TurnProbe.Result.Answered(90))
            rig.routes.emit(Unit)
            runCurrent()
            assertEquals(listOf(a, a), rig.asked)
            assertEquals(90L, rig.ranking.score(a))
        }

    @Test
    fun nothingIsMeasuredWhileACallIsBeingSetUp() =
        runTest {
            val rig = Rig(this)
            rig.start(this)
            rig.settingUp.value = true
            rig.answer(a, TurnProbe.Result.Answered(40))
            rig.targets.value = listOf(a)
            runCurrent()
            assertTrue(rig.asked.isEmpty())

            rig.settingUp.value = false
            runCurrent()
            assertEquals(listOf(a), rig.asked)
            assertEquals(40L, rig.ranking.score(a))
        }

    @Test
    fun withNoTargetNothingIsSentEvenOnANetworkChange() =
        runTest {
            // A fresh install: no spool would issue TURN, so the egress check sees no probe.
            val rig = Rig(this)
            rig.start(this)
            rig.routes.emit(Unit)
            runCurrent()
            rig.targets.value = emptyList()
            runCurrent()
            assertTrue(rig.asked.isEmpty())
        }

    @Test
    fun aProbeWithNoNetworkLearnsNothingAndIsTriedAgainOnTheNextEvent() =
        runTest {
            val rig = Rig(this)
            rig.start(this)
            rig.answer(a, TurnProbe.Result.NoRoute)
            rig.targets.value = listOf(a)
            runCurrent()
            assertFalse(rig.ranking.measured(a))

            rig.answer(a, TurnProbe.Result.Answered(30))
            rig.answer(b, TurnProbe.Result.Answered(50))
            rig.targets.value = listOf(a, b)
            runCurrent()
            assertEquals(listOf(a, a, b), rig.asked.sortedBy { it })
            assertEquals(30L, rig.ranking.score(a))
        }

    @Test
    fun aRoundANetworkChangeOvertookIsDroppedWhole() =
        runTest {
            val rig = Rig(this)
            rig.start(this)
            val old = rig.hold(a)
            rig.targets.value = listOf(a)
            runCurrent()
            assertEquals(listOf(a), rig.asked)

            // The network changes while the first round waits on its answer; the change's own round answers first.
            rig.answer(a, TurnProbe.Result.Answered(20))
            rig.routes.emit(Unit)
            runCurrent()
            assertEquals(20L, rig.ranking.score(a))

            // The old network's answer comes last, and is not this network's score.
            old.complete(TurnProbe.Result.Answered(300))
            runCurrent()
            assertEquals(20L, rig.ranking.score(a))
        }

    @Test
    fun aTargetThatIsNoEntryIsSilentAndNeverProbed() =
        runTest {
            val rig = Rig(this)
            rig.start(this)
            rig.targets.value = listOf("not an entry")
            runCurrent()
            assertTrue(rig.asked.isEmpty())
            assertTrue(rig.ranking.measured("not an entry"))
        }
}
