package app.getknit.knit.mesh.lora

import app.getknit.knit.fieldlog.FieldEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** The field log's 5-minute LoRa sample ([fieldSample], ADR 2026-10.a239): what [LoraStatus] holds, never who. */
class LoraFieldSampleTest {
    private val ready =
        LinkState.Ready(
            board = BoardInfo(myNodeNum = 0x1234u, pioEnv = "heltec-v3", firmwareVersion = "2.8.0"),
            channels = emptyList(),
            mtu = 247,
        )

    @Test
    fun noReadyLinkIsNoSample() {
        assertEquals(emptyList<FieldEvent>(), LoraStatus(state = LinkState.Connecting, queued = 3).fieldSample())
    }

    @Test
    fun theSampleCarriesTheElectionsInputsAndTheBoardsOwnReadingsInTenths() {
        val status =
            LoraStatus(
                state = ready,
                boardName = "Knit abcd",
                boardAddress = "AA:BB:CC:DD:EE:FF",
                boardNodeNum = 0x1234u,
                lastSnr = 6.25f,
                lastRssi = -97,
                queueFree = 14,
                heard = 2,
                boardsHeard = 3,
                battery = BoardBattery(percent = 64, voltage = 3.81f, powered = false),
                boardAir = BoardAir(channelUtilPercent = 12.34f, airUtilTxPercent = 3.26f),
                internetCovered = 2,
                queued = 5,
            )
        assertEquals(
            listOf(
                FieldEvent.Lora(
                    snrTenths = 63,
                    rssi = -97,
                    queued = 5,
                    heard = 2,
                    boardsHeard = 3,
                    airUsedMs = null,
                    airBudgetMs = null,
                    internetCovered = 2,
                    queueFree = 14,
                    batteryPercent = 64,
                    powered = false,
                    channelUtilTenths = 123,
                    airUtilTxTenths = 33,
                ),
            ),
            status.fieldSample(),
        )
    }

    @Test
    fun theFieldLogKeepsItsAirKeysFedFromTheOneLedger() {
        // ADR 2026-10.ug4a: `airUsed` / `airBudget` keep their names and read `usedMs` / `allowanceMs`.
        val air =
            AirtimeSnapshot(
                ModemPreset.LONG_FAST,
                LoraRegion.US,
                known = true,
                usedMs = 12_345,
                allowanceMs = 45_000,
                publicCeilingMs = 22_500,
            )
        val sample = LoraStatus(state = ready, airtime = air).fieldSample().single() as FieldEvent.Lora
        assertEquals(12_345L, sample.airUsedMs)
        assertEquals(45_000L, sample.airBudgetMs)
    }

    @Test
    fun aPoweredBoardHasNoPercentAndAnUnreportedOneNeitherReading() {
        val powered =
            LoraStatus(
                state = ready,
                battery = BoardBattery(percent = null, voltage = 4.2f, powered = true),
            ).fieldSample().single()
        powered as FieldEvent.Lora
        assertEquals(null, powered.batteryPercent)
        assertEquals(true, powered.powered)
        val quiet = LoraStatus(state = ready).fieldSample().single() as FieldEvent.Lora
        assertEquals(null, quiet.powered)
        assertEquals(null, quiet.channelUtilTenths)
        assertEquals(null, quiet.airUtilTxTenths)
        assertEquals(null, quiet.queueFree)
    }
}
