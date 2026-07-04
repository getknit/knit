package app.getknit.knit.mesh.bluetooth

/**
 * Pure "should the BLE scan run **Boosted** or **Floored** this iteration?" decision, mirroring
 * [PromotionPolicy] / [ConnectBackoffPolicy]: no Android, an injected clock, JVM-unit-testable.
 *
 * Boost when there is promotion work — a BLE-sighted-but-unlinked candidate, or a foreign-reachable
 * (other-plane, e.g. Wi-Fi Aware) peer we're still *chasing* onto BLE within its per-peer window — or the
 * node is isolated (0 links → keep the aggressive lonely-rejoin cadence). Floor (the energy saver) only once
 * at least one link is held and nothing is pending, so a settled clique stops scanning back-to-back.
 *
 * The chase deadlines bound the NAN early-warning: a peer another plane can see is worth boosting the scan
 * for *briefly* (it may be about to walk into BLE range), but a stationary NAN-only / out-of-BLE-range peer
 * must not pin Boost forever — so each foreign peer gets a `now + chaseMs` deadline, kept while it stays in
 * the set and re-armed only when it leaves and returns.
 */
object ScanDemandPolicy {
    enum class Demand { Boost, Floor }

    /** Opaque per-peer chase deadlines (nodeId → elapsed-clock deadline). Held by the transport, folded here. */
    data class ChaseState(
        val deadlines: Map<String, Long> = emptyMap(),
    )

    /**
     * Fold the latest [foreign] set into [state]: a peer *new* to the set (rising edge — first appearance, or a
     * return after leaving) arms a fresh `now + chaseMs`; a peer *continuously present* keeps its original
     * deadline (so it expires and can't pin Boost forever); a *departed* peer is dropped (so a later return
     * re-arms). Pure — departure is implicit in copying only [foreign]'s keys, so no previous-set is needed.
     */
    fun onForeign(
        state: ChaseState,
        foreign: Set<String>,
        now: Long,
        chaseMs: Long,
    ): ChaseState {
        val next = HashMap<String, Long>(foreign.size)
        for (id in foreign) next[id] = state.deadlines[id] ?: (now + chaseMs)
        return ChaseState(next)
    }

    /**
     * @param linkCount live L2CAP links held right now
     * @param promotableCandidates BLE-sighted peers we would initiate to (not linked, off backoff, above the RSSI
     *   floor) — the real promotion prospects that scanning helps
     * @param chase current per-peer chase deadlines (see [onForeign])
     * @param bleSighted / [bleLinked] so a foreign peer we can now see or already serve on BLE stops counting as a
     *   chase (its promotion is driven by [promotableCandidates] / an existing link instead)
     */
    fun decide(
        linkCount: Int,
        promotableCandidates: Set<String>,
        chase: ChaseState,
        bleSighted: Set<String>,
        bleLinked: Set<String>,
        now: Long,
    ): Demand {
        if (linkCount == 0) return Demand.Boost // isolated → never Floor (lonely rejoin)
        if (promotableCandidates.isNotEmpty()) return Demand.Boost // promotion work right in front of us
        val chasing =
            chase.deadlines.any { (id, until) ->
                now < until && id !in bleSighted && id !in bleLinked
            }
        return if (chasing) Demand.Boost else Demand.Floor
    }
}
