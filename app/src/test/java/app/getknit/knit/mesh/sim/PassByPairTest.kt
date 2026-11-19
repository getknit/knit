package app.getknit.knit.mesh.sim

import app.getknit.knit.mesh.lab.MeshLab
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [PassBySim]'s verdict over one quick pair on the `disc` radio, so the code a sidewalk run leans on is tested on every
 * build: a dial that fits the pass converges, and one that outlasts it never links. Walkers at 1.5 m/s starting a metre
 * out of reach give a pass about 10 s in dial range, the same budget a stack gets on a phone, in about 20 s of wall clock.
 */
@RunWith(RobolectricTestRunner::class)
class PassByPairTest {
    private lateinit var lab: MeshLab

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    // In dial range for 2 × 14.9 m at 3 m/s closing, about 9.9 s, and linked until 21 m: against the dial given.
    private fun quick(dialMs: Long) =
        PassByScenario(
            seed = 1,
            pairs = 1,
            speedMps = FAST_MPS,
            dms = 4,
            radio = DiscRadio.NAME,
            dialMs = dialMs,
            startWindowMs = 0,
            startMarginM = 1.0,
            watchAfterMs = 1_000,
        )

    @Test
    fun aPassLongerThanTheDialConverges() =
        runBlocking {
            val report = PassBySim(lab, quick(dialMs = 1_000)).play()
            val pass = report.passes.single()
            assertEquals("the pass:\n${report.text}", PassVerdict.CONVERGED, pass.verdict)
            assertTrue("a converged pass names when:\n${report.text}", pass.convergeMs != null)
            assertTrue("converged_pct=100 in the summary:\n${report.text}", "converged_pct=100" in report.text)
        }

    @Test
    fun aDialLongerThanThePassNeverLinks() =
        runBlocking {
            val report = PassBySim(lab, quick(dialMs = 30_000)).play()
            val pass = report.passes.single()
            assertEquals("the pass:\n${report.text}", PassVerdict.NEVER_LINKED, pass.verdict)
            assertTrue("it was in range:\n${report.text}", pass.inRangeAtMs != null)
            assertTrue("converged_pct=0 in the summary:\n${report.text}", "converged_pct=0" in report.text)
        }

    private companion object {
        const val FAST_MPS = 1.5
    }
}
