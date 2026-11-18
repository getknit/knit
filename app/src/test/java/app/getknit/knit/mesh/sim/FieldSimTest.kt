package app.getknit.knit.mesh.sim

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.ceil

/**
 * The field alone ([FieldRun]): the outing [ParkWalkSimTest] would walk under the same properties ([SimProperties]),
 * its walk and its radio with no stacks, measured by the [FieldMeter] in seconds rather than the outing's own minutes.
 * Its contact numbers are the outing's exactly, so a mobility model is measured over hours and hundreds of walkers before
 * any outing with stacks is spent on it.
 *
 * **A measurement, not a check**, run only when asked, as [ParkWalkSimTest] is: `-Pknit.meshSim=<seed>` with
 * `--tests '*FieldSimTest'`. `knit.meshSimTrace` replays a `.movements` file ([TraceMobility]) in place of a motion: its
 * walkers, field and length are the trace's unless `knit.meshSimMinutes` cuts it shorter. The report goes beside the
 * outings', as `field-s<seed>-w<walkers>-m<minutes>`, and ends in the same `summary:` block.
 */
class FieldSimTest {
    @Test
    fun theFieldAloneOverAnOuting() {
        val asked = System.getProperty(SimProperties.SEED) != null
        assumeTrue("the mesh simulator runs only when asked: -P${SimProperties.SEED}=<seed>", asked)
        val seed = SimProperties.seed()
        val trace = System.getProperty(SimProperties.TRACE)?.let { TraceMobility.read(File(it)) }
        val scenario =
            if (trace == null) {
                SimProperties.scenario(seed, SimProperties.walkers(), SimProperties.minutes())
            } else {
                val minutes = System.getProperty(SimProperties.MINUTES)?.toInt() ?: ceil(trace.durationMs / MS_PER_MINUTE).toInt()
                SimProperties.scenario(seed, trace.walkers, minutes, fieldM = maxOf(trace.widthM, trace.heightM), motion = Motion.TRACE)
            }
        val text = FieldRun(SimWorld(scenario, trace)).play()
        println(text)
        val minutes = scenario.durationMs / SimProperties.MS_PER_MINUTE
        freshReport(SimProperties.out(), "field-s$seed-w${scenario.walkers}-m$minutes${SimProperties.tag()}").writeText(text + "\n")
    }

    private companion object {
        const val MS_PER_MINUTE = 60_000.0
    }
}
