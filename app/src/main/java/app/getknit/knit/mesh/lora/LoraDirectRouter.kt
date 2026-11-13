package app.getknit.knit.mesh.lora

/**
 * Which plane a compact DM to a peer goes through (ADR 2026-11.gsqu, Stage 2): what `MeshManager.bindLoraDirect` is
 * bound to, one [LoraDirectSink] per board kind the build constructs ([planes]).
 *
 * **v1's rule: the bound kind's plane** ([boundKind], the kind whose device address is set), and Meshtastic's with no
 * kind bound — today's unconditional answer. A bound kind with no plane in this build answers null, and nothing is
 * offered: no other plane can carry what was sized for that board.
 *
 * It never reads a board map or a holder. Those stay the plane's own job: `directHolder` and `reprobe.onHeld` must
 * still see a DM offered before the map reports, so a router that dropped it there would be a new early return (Stage
 * 1a.4 forbids one). Resolving a peer by map is the two-board router's, which the ADR defers with "both boards at once";
 * [peerId] is in the signature for it, and v1 does not read it.
 *
 * The crypto design pass's rule, kept by the caller: the budget and the offer go to the same plane, resolved **once**
 * per offer with the recipient, and a sealed frame never moves between planes. `MeshManager` calls [sinkFor] once and
 * reads `budgetFor` and `offer` off what it answered. There is no "arrival kind" on the inbound side in this stage: under
 * one bound board it would be a second routing rule that could disagree with this one (ADR 2026-09.zkma).
 */
internal class LoraDirectRouter(
    /** Each built kind's sink, resolved at call time: the transport and the manager construct each other. */
    private val planes: Map<BoardKind, () -> LoraDirectSink>,
    /** The bound kind (`SettingsStore.boundBoardKind`), or null with none. */
    private val boundKind: suspend () -> BoardKind?,
) {
    /** The plane a compact DM to [peerId] goes through now, or null when the bound kind has none in this build. */
    @Suppress("UnusedParameter") // v1 routes by the bound kind alone; the two-board router reads the peer
    suspend fun sinkFor(peerId: String): LoraDirectSink? = planes[boundKind() ?: BoardKind.MESHTASTIC]?.invoke()
}
