package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.lora.meshcore.MeshcoreTraits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LoraBand]: each row's edges, the strictest for what no row holds, and an allowance that never passes the law. */
class LoraBandTest {
    private val narrow = 62_500L

    @Test
    fun aChannelFitsARowOnlyWhenItsWholeWidthIsInside() {
        for (band in LoraBand.entries) {
            val half = narrow / 2
            val lowest = band.startHz + half
            val highest = band.endHz - half
            assertTrue("$band: at its lower edge", band in LoraBand.holding(lowest, narrow))
            assertTrue("$band: at its upper edge", band in LoraBand.holding(highest, narrow))
            assertTrue("$band: a hertz below straddles it", band !in LoraBand.holding(lowest - 1, narrow))
            assertTrue("$band: a hertz above straddles it", band !in LoraBand.holding(highest + 1, narrow))
        }
    }

    @Test
    fun eachRowsDuty() {
        fun duty(
            mhz: Double,
            bw: Long = narrow,
        ) = LoraBand.dutyPercent((mhz * 1_000_000).toLong(), bw)
        assertEquals(0.1, duty(864.0), 0.0)
        assertEquals(1.0, duty(866.0), 0.0)
        assertEquals(1.0, duty(868.3), 0.0)
        assertEquals(0.1, duty(869.0), 0.0)
        assertEquals(10.0, duty(869.525, 250_000), 0.0)
        assertEquals(1.0, duty(869.85), 0.0)
        assertEquals(10.0, duty(433.875), 0.0)
        assertEquals(100.0, duty(906.875), 0.0)
        assertEquals("US and ANZ overlap: both 100", 100.0, duty(920.0), 0.0)
    }

    @Test
    fun meshcoresOwnPresetsAllLandInARow() {
        // The MeshCore App API's presets (`docs/radio_presets.md`, `docs/faq.md`) and the firmware's build default.
        for ((mhz, bw) in listOf(869.618 to narrow, 869.525 to 250_000L, 910.525 to narrow, 916.575 to narrow, 917.375 to narrow)) {
            assertTrue("$mhz MHz", LoraBand.holding((mhz * 1_000_000).toLong(), bw).isNotEmpty())
        }
    }

    @Test
    fun aChannelNoRowHoldsReadsTheStrictest() {
        assertEquals(0.1, LoraBand.STRICTEST_PERCENT, 0.0)
        // Gaps between EU sub-bands, a straddle across two rows, and bands the table does not know.
        for (hz in listOf(868_650_000L, 869_300_000L, 869_675_000L, 868_000_000L, 2_450_000_000L, 470_000_000L)) {
            assertTrue("$hz is in no row", LoraBand.holding(hz, narrow).isEmpty())
            assertEquals("$hz", LoraBand.STRICTEST_PERCENT, LoraBand.dutyPercent(hz, narrow), 0.0)
        }
        assertEquals("straddles 865/868", LoraBand.STRICTEST_PERCENT, LoraBand.dutyPercent(868_000_000L, narrow), 0.0)
        assertEquals(
            "a 500 kHz channel at 869.5 spills out of the 10 % row",
            LoraBand.STRICTEST_PERCENT,
            LoraBand.dutyPercent(869_500_000L, 500_000),
            0.0,
        )
        assertEquals(LoraBand.STRICTEST_PERCENT, LoraBand.dutyPercent(2_450_000_000L, narrow), 0.0)
    }

    @Test
    fun theAllowanceNeverPassesTheBandsDuty() {
        // Windows straddle, so the worst hour holds 5/4 of four windows' allowance (ADR 054): still within the law.
        for (band in LoraBand.entries) {
            val air = LoraAirtime(traits = MeshcoreTraits)
            val freqKhz = (band.startHz + (band.endHz - band.startHz) / 2) / 1_000
            air.onRadio(RadioParams(7, 62_500, 5), MeshcoreTraits.dutyPolicy(freqKhz, 62_500))
            val worstHourMs = air.allowanceMs() * 4 * 5 / 4
            assertTrue("$band: $worstHourMs ms an hour", worstHourMs <= 3_600_000 * band.dutyPercent / 100)
        }
    }
}
