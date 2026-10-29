package app.getknit.knit.mesh.lora

import app.getknit.knit.fieldlog.FieldEvent
import kotlin.math.roundToInt

/**
 * The field log's LoRa sample (ADR 2026-10.a239): nothing while no board's link is ready, else one
 * [FieldEvent.Lora]. Lives beside [LinkState], which the field log cannot name, and leaves the board's identity out.
 * The board's own battery and duty-cycle readings ride beside Knit's airtime estimate, the percentages as tenths.
 */
internal fun LoraStatus.fieldSample(): List<FieldEvent> {
    if (state !is LinkState.Ready) return emptyList()
    return listOf(
        FieldEvent.Lora(
            snrTenths = lastSnr?.let { (it * TENTHS).roundToInt() },
            rssi = lastRssi,
            queued = queued,
            heard = heard,
            boardsHeard = boardsHeard,
            airUsedMs = airtime?.liveUsedMs,
            airBudgetMs = airtime?.liveBudgetMs,
            internetCovered = internetCovered,
            queueFree = queueFree,
            batteryPercent = battery?.percent,
            powered = battery?.powered,
            channelUtilTenths = boardAir?.channelUtilPercent?.let { (it * TENTHS).roundToInt() },
            airUtilTxTenths = boardAir?.airUtilTxPercent?.let { (it * TENTHS).roundToInt() },
        ),
    )
}

private const val TENTHS = 10
