package app.getknit.knit.fieldlog

import app.getknit.knit.mesh.MeshMetrics

/**
 * The [MeshMetrics] counters a field log carries, flattened under fixed names. Hand-listed rather than
 * reflected because R8 renames the snapshot's properties in a release build, and because this is the
 * schema: radio, link, file and plane behaviour. The crypto and roster counters (seals, keys, seeds, roots)
 * stay out — they say nothing about how the radios behaved.
 *
 * A reason map is spread as `<prefix>_<REASON>`. The `…Peak…` values are high-water marks, not counts;
 * [deltas] passes them through as they stand.
 */
object FieldCounters {
    /** Spreads a reason map as `<prefix>_<REASON>`. */
    private fun <E : Enum<E>> MutableMap<String, Long>.spread(
        prefix: String,
        byReason: Map<E, Long>,
    ) = byReason.forEach { (reason, n) -> put("${prefix}_${reason.name}", n) }

    @Suppress("LongMethod") // One line per counter: the schema reads top to bottom.
    fun flatten(s: MeshMetrics.Snapshot): Map<String, Long> =
        buildMap {
            with(s.frames) {
                put("originated", framesOriginated)
                put("delivered", framesDelivered)
                put("relayed", framesRelayed)
                put("handedOn", framesHandedOn)
                put("suppressed", framesSuppressed)
                put("deduped", framesDeduped)
                put("bytesSent", bytesSent)
                put("dropped", framesDropped)
                put("digestsReplaced", digestsReplaced)
                put("healsCompleted", healsCompleted)
                dropsByReason.forEach { (reason, n) -> put("drop_${reason.name}", n) }
                roundsFailedBy.forEach { (round, n) -> put("roundFail_${round.name}", n) }
            }
            with(s.receipts) {
                put("receiptsResent", receiptsResent)
                put("receiptsCustodied", receiptsCustodied)
                put("receiptsRidden", receiptsRidden)
                put("receiptsSpooled", receiptsSpooled)
                put("ridesUnrouted", ridesUnrouted)
                put("receiptsHeld", receiptsHeld)
            }
            with(s.files) {
                put("filesSentNan", filesSentNan)
                put("filesSentBt", filesSentBt)
                put("blobAsksHandled", blobAsksHandled)
                put("carrierPullsDeferred", carrierPullsDeferred)
                put("filesResumedOut", filesResumedOut)
                put("filesResumedIn", filesResumedIn)
                put("splicesRefused", splicesRefused)
                put("blobQueriesSent", blobQueriesSent)
                put("blobQueriesAnswered", blobQueriesAnswered)
                put("blobQueriesRefused", blobQueriesRefused)
                put("blobAnswersTaken", blobAnswersTaken)
                put("blobAnswersIgnored", blobAnswersIgnored)
                put("blobFetches", blobFetches)
                put("blobConfirms", blobConfirms)
                put("blobFallbacks", blobFallbacks)
                put("blobBlindAsks", blobBlindAsks)
                put("blobPullThroughs", blobPullThroughs)
                put("blobAsksParked", blobAsksParked)
            }
            with(s.bluetooth) {
                put("btConnectFails", btConnectFails)
                btConnectFailsByReason.forEach { (reason, n) -> put("btFail_${reason.name}", n) }
                put("btLinksEstablished", btLinksEstablished)
                put("bleLinkDupSkipped", bleLinkDupSkipped)
                put("bleCodedSightings", bleCodedSightings)
                put("bleCodedDials", bleCodedDials)
                put("blePhyStepsDown", blePhyStepsDown)
                put("blePhyStepsUp", blePhyStepsUp)
                put("blePhyGiveUps", blePhyGiveUps)
                put("bleCodedAdvertDark", bleCodedAdvertDark)
                put("bleSideOffered", bleSideOffered)
                put("bleSidePartsAired", bleSidePartsAired)
                put("bleSideTooBig", bleSideTooBig)
                put("bleSideHeard", bleSideHeard)
                put("bleSideReassembled", bleSideReassembled)
                put("bleSideDeduped", bleSideDeduped)
                spread("bleSideDrop", bleSideDropsByReason)
                spread("bleBlindDrop", bleBlindDropsByReason)
            }
            with(s.nan) {
                put("nanServesPeak", nanServesPeak)
                put("nanOwedNoLinkPeakMs", nanOwedNoLinkPeakMs)
                put("nanAcceptsRefused", nanAcceptsRefused)
                put("nanIcmKeepaliveFailed", nanIcmKeepaliveFailed)
                put("nanOffScreenRefusals", nanOffScreenRefusals)
                put("nanMsgsAcked", nanMsgsAcked)
                put("nanMsgSendsFailed", nanMsgSendsFailed)
                put("nanMsgPlaneStalledPeakMs", nanMsgPlaneStalledPeakMs)
                put("nanMsgPlaneCycles", nanMsgPlaneCycles)
                put("nanBulkGraceTimeouts", nanBulkGraceTimeouts)
            }
            with(s.fast) {
                put("fastCompactSent", fastCompactSent)
                put("fastLegacySent", fastLegacySent)
                put("fastFragSent", fastFragSent)
                put("fastReassembled", fastReassembled)
                put("fastTooBig", fastTooBig)
                put("fastTranscodedSent", fastTranscodedSent)
                put("transcodeFallbacks", transcodeFallbacks)
                fastDropsByReason.forEach { (reason, n) -> put("fastDrop_${reason.name}", n) }
            }
            with(s.spool) {
                put("spoolPushed", spoolPushed)
                put("spoolPulled", spoolPulled)
                put("spoolErrors", spoolErrors)
                put("spoolAttachPushed", spoolAttachPushed)
                put("spoolAttachPulled", spoolAttachPulled)
                put("spoolAttachDeferred", spoolAttachDeferred)
                put("spoolAttachProved", spoolAttachProved)
            }
            with(s.lora) {
                put("loraSent", loraSent)
                put("loraFragSent", loraFragSent)
                put("loraReceived", loraReceived)
                put("loraReassembled", loraReassembled)
                put("loraTooBig", loraTooBig)
                put("loraDroppedQueue", loraDroppedQueue)
                put("loraAirtimeHeld", loraAirtimeHeld)
                put("loraBandBraked", loraBandBraked)
                put("loraSuppressed", loraSuppressed)
                put("loraNak", loraNak)
                put("loraSessionUps", loraSessionUps)
                put("loraDmReceived", loraDmReceived)
                put("loraSkippedLinked", loraSkippedLinked)
                put("loraSkippedInternet", loraSkippedInternet)
                put("loraStaleAtSend", loraStaleAtSend)
                put("loraTickDeferred", loraTickDeferred)
                // Compact DMs (ADR 2026-10.x9fa): what aired, what a board acked, what nothing answered.
                put("loraDirectSent", loraDirectSent)
                put("loraDirectAcked", loraDirectAcked)
                put("loraDirectUnreachable", loraDirectUnreachable)
                put("loraDirectNoOutcome", loraDirectNoOutcome)
                put("loraDirectDelivered", loraDirectDelivered)
                put("loraDirectResent", loraDirectResent)
            }
        }

    /**
     * What moved between [before] and [after]: a count's increase, or a peak's new value. Zero movement is left
     * out, so a quiet quarter-hour writes an empty `counters` line (and [FieldLog] skips it).
     */
    fun deltas(
        before: Map<String, Long>,
        after: Map<String, Long>,
    ): Map<String, Long> =
        buildMap {
            after.forEach { (name, value) ->
                val was = before[name] ?: 0L
                when {
                    name.contains(PEAK) -> if (value != was) put(name, value)
                    value > was -> put(name, value - was)
                }
            }
        }

    private const val PEAK = "Peak"
}
