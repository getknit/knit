package app.getknit.knit.mesh.wifiaware

import kotlin.math.min

/**
 * Pure admission policy for **concurrent inbound serves** on the accept-any responder
 * (`docs/NAN_CONCURRENCY_REAUDIT.md` §5.1, field-proven in E2/P0). An accept consumes no NDI — the standing
 * responder request officially multiplexes NDPs on its single interface — so on multi-NDP hardware the only
 * hard exclusions are the firmware's session budget and an in-flight *initiator* handshake (which genuinely
 * contends for the one NDI: an inbound NDP arriving mid-initiate is refused by the framework and kills the
 * responder request — the `onUnavailable` re-file exists for exactly that). On 1-NDP-budget hardware
 * (Samsung S.LSI ships `maxNdpSessions = 1`) the policy degrades to the legacy single-slot semantics,
 * byte-equivalent to the pre-P1 `beginAccept`. No Android deps ⇒ JVM-unit-tested ([NanServePolicyTest]).
 */
object NanServePolicy {
    /**
     * Concurrent-serve cap for a device whose firmware supports [ndpBudget] NDP sessions
     * (`Characteristics.getNumberOfSupportedDataPaths()`): reserve one session for this node's own outbound
     * NDP, bound the rest by [sanityCap] (E2 proved 2 concurrent; 8-session Pixels leave headroom nobody has
     * validated past N=2 — the cap and the refused-accept metric exist to observe exactly that). A budget of
     * ≤1 (or an unreadable capability) means single-slot.
     */
    fun capFor(
        ndpBudget: Int,
        sanityCap: Int = SERVE_CAP,
    ): Int = if (ndpBudget <= 1) 1 else min(ndpBudget - 1, sanityCap)

    /**
     * Whether to admit an inbound accept right now. [acceptsInHello] are accepted sockets still reading their
     * identity ([LinkHandshake]) — reserved but not yet live; they count against [cap] so a burst can't
     * overshoot. [singleSlotBusy]/[settleOk] carry the legacy gate's inputs (any live link/handshake/accept;
     * the post-teardown NDI settle) and are consulted only at cap 1, keeping that path byte-equivalent to the
     * pre-P1 single-slot admission.
     */
    fun admitAccept(
        inFlightHandshakes: Int,
        liveInbound: Int,
        acceptsInHello: Int,
        cap: Int,
        singleSlotBusy: Boolean,
        settleOk: Boolean,
    ): Boolean =
        if (cap <= 1) {
            !singleSlotBusy && settleOk
        } else {
            inFlightHandshakes == 0 && liveInbound + acceptsInHello < cap
        }

    /**
     * Sanity bound on concurrent serves regardless of firmware budget. Rollback lever: setting this to 1
     * restores the exact pre-P1 single-slot admission on every device.
     */
    const val SERVE_CAP = 4
}
