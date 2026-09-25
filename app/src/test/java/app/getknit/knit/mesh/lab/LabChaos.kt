package app.getknit.knit.mesh.lab

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * **Seeded scheduling noise for the mesh lab** — the slow CI runner, brought to the workstation. Every lab
 * flake so far was a window that a 24-core box closes before anyone looks at it: a counter bumped after the
 * `send` a scenario waited on, an emission before a late subscriber, a digest exchange that ran before its
 * row existed. CI's one or four vCPUs open those windows at random, one run in thirty; the throttled-core
 * loop opens them too, twenty minutes a run. This opens them on purpose, in seconds.
 *
 * **The one rule: only schedules the lab can already produce.** Chaos never changes what a transport means —
 * a `send` still returns after the far end has the frame, a pipe still delivers in order, a hold still
 * holds. It only stretches the time between two steps, at the three places a loaded machine stretches it:
 *
 * - **Dispatch** ([dispatcher]): a coroutine resumed on the node's session dispatcher, its settings scope or
 *   its Room queries waits a few ms before it runs — a busy pool. Order among dispatched tasks is not a
 *   contract on a multi-threaded pool, so a reordering here is one `Dispatchers.Default` may hand out too.
 * - **Send** ([jitter], in [LabTransport]): the sender is descheduled before and after a pipe delivers — the
 *   window between "the far end has it" and the sender's own bookkeeping that the 2026-09-16 audit catalogued.
 * - **Non-suspending fast path** ([stall]): the worker thread itself is descheduled, as a preemption does.
 *
 * Each of those draws is mostly short (a few ms, a busy pool) with a **heavy tail**: now and then one
 * coroutine stalls for 40–400 ms while every other one carries on — a thread the one-vCPU runner left off the
 * core for a stretch. Uniform noise slows everything alike and almost never flips an order; the tail is what
 * does. The two known windows it was tuned on (a heal basket still running past `unlink`'s settle, a
 * router's queued ask outliving a re-link) each need one stall of that size. How often the tail fires is the
 * seed's own draw (2–15 ‰ of draws), so a sweep covers mild and harsh runs alike.
 *
 * So a scenario that fails under chaos is a scenario a slow runner fails too, given the same luck: a latent
 * flake, never a chaos artefact. A seed does **not** replay a schedule — the threads still race each other
 * for the draws — it replays a *distribution* of delays, which is what makes a failing seed worth re-running.
 *
 * Off unless asked for. `-Pknit.labChaos=<seed>` (or `random`) turns it on; `-Pknit.labChaosRuns=<n>` runs
 * each scenario n times in the same JVM on seeds `seed`, `seed+1`, … — the fast local sweep
 * (`scripts/lab-chaos.sh`). A failure names its seed (stderr, the suppressed cause, and [MeshLab.report]).
 * See `.agents/context/testing.md`, "Chaos mode".
 */
class LabChaos private constructor(
    val seed: Long,
) {
    private val random = Random(seed)

    /** This seed's tail rate, in draws per thousand: some seeds are mild, some harsh. */
    private val tailPermille: Int = Random(seed).nextInt(TAIL_PERMILLE_MIN, TAIL_PERMILLE_MAX + 1)

    /**
     * One draw: a long stall ([TAIL_MIN_MS]..[TAIL_MAX_MS]) at the seed's tail rate, else a delay in `1..maxMs`
     * with probability [percent] %, else 0. Draws are shared across threads.
     */
    private fun draw(
        percent: Int,
        maxMs: Int,
    ): Long =
        synchronized(random) {
            when {
                random.nextInt(PERMILLE) < tailPermille -> random.nextInt(TAIL_MIN_MS, TAIL_MAX_MS + 1).toLong()
                random.nextInt(PERCENT) < percent -> random.nextInt(1, maxMs + 1).toLong()
                else -> 0L
            }
        }

    /** A dispatcher that delays some dispatches of [delegate] by a few ms — a busy pool. */
    fun dispatcher(delegate: CoroutineDispatcher): CoroutineDispatcher = ChaosDispatcher(delegate, this)

    /** A suspension point that sometimes lasts: the calling coroutine descheduled for a moment. */
    suspend fun jitter() {
        val ms = draw(JITTER_PERCENT, JITTER_MAX_MS)
        if (ms > 0) delay(ms) else yield()
    }

    /**
     * The non-suspending form: the worker thread itself preempted, for the fast path's `tryEmit` callers. Short
     * only — no tail: a sleeping worker is a worker the pool does not have, and a long one would starve it.
     */
    fun stall() {
        val ms = synchronized(random) { if (random.nextInt(PERCENT) < STALL_PERCENT) random.nextInt(1, STALL_MAX_MS + 1).toLong() else 0L }
        if (ms > 0) Thread.sleep(ms)
    }

    internal fun dispatchLag(): Long = draw(DISPATCH_PERCENT, DISPATCH_MAX_MS)

    override fun toString() = "chaos seed=$seed tail=$tailPermille‰ (reproduce: scripts/lab-chaos.sh --seed $seed --tests <class>)"

    private class ChaosDispatcher(
        private val delegate: CoroutineDispatcher,
        private val chaos: LabChaos,
    ) : CoroutineDispatcher() {
        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            val lag = chaos.dispatchLag()
            if (lag == 0L) {
                delegate.dispatch(context, block)
            } else {
                timer.schedule({ delegate.dispatch(context, block) }, lag, TimeUnit.MILLISECONDS)
            }
        }

        override fun toString() = "Chaos($delegate)"
    }

    companion object {
        private const val PERCENT = 100
        private const val PERMILLE = 1000
        private const val TAIL_PERMILLE_MIN = 2
        private const val TAIL_PERMILLE_MAX = 15
        private const val TAIL_MIN_MS = 40
        private const val TAIL_MAX_MS = 400
        private const val DISPATCH_PERCENT = 15
        private const val DISPATCH_MAX_MS = 8
        private const val JITTER_PERCENT = 30
        private const val JITTER_MAX_MS = 15
        private const val STALL_PERCENT = 5
        private const val STALL_MAX_MS = 3

        /** Gradle forwards `-Pknit.labChaos` / `-Pknit.labChaosRuns` as these (app/build.gradle.kts). */
        const val SEED_PROPERTY = "knit.labChaos"
        const val RUNS_PROPERTY = "knit.labChaosRuns"

        /** One timer thread for every lagged dispatch in the JVM; daemon, so it never holds a test run open. */
        private val timer: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lab-chaos").apply { isDaemon = true } }

        /** The chaos the scenario running now was handed by [rule], or null when chaos is off. */
        @Volatile
        internal var current: LabChaos? = null
            private set

        /** The base seed asked for, or null when chaos is off. `random` draws one per JVM. */
        private val baseSeed: Long? by lazy {
            when (val raw = System.getProperty(SEED_PROPERTY)?.trim().orEmpty()) {
                "", "off", "false" -> null
                "random" -> System.nanoTime()
                else -> requireNotNull(raw.toLongOrNull()) { "$SEED_PROPERTY=$raw: a seed, `random` or `off`" }
            }
        }

        private val runs: Int by lazy { System.getProperty(RUNS_PROPERTY)?.toIntOrNull()?.coerceAtLeast(1) ?: 1 }

        /**
         * The rule every `*LabTest` carries (`LabChaosCoverageTest` checks): with chaos off it runs the scenario
         * once, as before; with chaos on it runs it [runs] times on consecutive seeds, stopping at the first
         * failure and naming its seed. It wraps `@Before`/`@After`, so each run gets a fresh [MeshLab].
         */
        fun rule(): TestRule = TestRule { base, description -> statement(base, description) }

        private fun statement(
            base: Statement,
            description: Description,
        ) = object : Statement() {
            override fun evaluate() {
                val first = baseSeed ?: return base.evaluate()
                repeat(runs) { i ->
                    val chaos = LabChaos(first + i)
                    current = chaos
                    // Robolectric resets the log per test, outside this rule: without this a failing run's
                    // report would quote the previous run's warnings (and its cancelled nodes' stragglers).
                    ShadowLog.clear()
                    try {
                        base.evaluate()
                    } catch (skip: AssumptionViolatedException) {
                        throw skip
                    } catch (t: Throwable) {
                        val where = "${description.className}.${description.methodName} failed under $chaos (run ${i + 1}/$runs)"
                        System.err.println("MESHLAB-CHAOS-FAIL $where")
                        t.addSuppressed(AssertionError(where))
                        throw t
                    } finally {
                        current = null
                    }
                }
            }
        }
    }
}
