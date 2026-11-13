package app.getknit.knit.mesh.lora

import app.getknit.knit.BuildConfig
import app.getknit.knit.data.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * The LoRa plane's state as the UI needs it: the settings that arm it folded with the transport's live
 * status. The sibling of `RelayStatusRepository`, but **pushed** — [LoraPlaneStatus.status] is already a
 * `StateFlow`, so there is no ticker and nothing to poll.
 *
 * One [LoraPlaneStatus] per board kind the build constructs ([planes], ADR 2026-11.gsqu Stage 2): [perKind] holds each
 * kind's facts, and [facts] is the aggregate the chat header, the composer, Wear and Diagnostics read — the bound
 * kind's (v1 binds one board at a time, `SettingsStore.boundBoardKind`), Meshtastic's with none bound. With one plane
 * there is nothing to choose and [facts] is that plane's, read exactly as before. Each kind's facts carry its compact-DM
 * budget ([LoraFacts.directBudget]) off its plane's constant ([LoraPlaneStatus.directBudget]), never off a transport.
 *
 * In a build where the plane is dark (`BuildConfig.LORA_PLANE` false) the flow is a single `Off`: the
 * settings already read false there and the status is [LoraPlaneStatus.Dark], so combining them would only
 * keep three collectors alive per open chat for a value that cannot change.
 */
internal class LoraStatusRepository(
    private val settings: SettingsStore,
    planes: Map<BoardKind, LoraPlaneStatus>,
) {
    /** The one-plane form: [lora] keyed by its own kind. */
    constructor(settings: SettingsStore, lora: LoraPlaneStatus) : this(settings, mapOf(lora.boardKind to lora))

    /** Each built kind's facts, keyed by kind (Stage 2). Empty in a dark build. */
    val perKind: Flow<Map<BoardKind, LoraFacts>> =
        if (!BuildConfig.LORA_PLANE || planes.isEmpty()) {
            flowOf(emptyMap())
        } else {
            val kinds = planes.keys.toList()
            combine(kinds.map { factsOf(it, planes.getValue(it)) }) { all -> kinds.zip(all).toMap() }.distinctUntilChanged()
        }

    /** The flattened facts the chat header, the DM notice and the composer hint read: the bound kind's. */
    val facts: Flow<LoraFacts> =
        when {
            !BuildConfig.LORA_PLANE || planes.isEmpty() -> {
                flowOf(LoraFacts())
            }

            planes.size == 1 -> {
                planes.entries.single().let { (kind, plane) -> factsOf(kind, plane) }
            }

            else -> {
                combine(settings.boundBoardKind, perKind) { bound, all ->
                    all[bound ?: BoardKind.MESHTASTIC] ?: all[BoardKind.MESHTASTIC] ?: LoraFacts()
                }.distinctUntilChanged()
            }
        }

    /** [kind]'s facts: its own binding and room switch, the shared plane and DM switches, and its plane's status. */
    private fun factsOf(
        kind: BoardKind,
        lora: LoraPlaneStatus,
    ): Flow<LoraFacts> {
        val (boundAddress, roomEnabled) =
            when (kind) {
                BoardKind.MESHTASTIC -> settings.loraDeviceAddress to settings.loraRoomEnabled
                BoardKind.MESHCORE -> settings.meshcoreDeviceAddress to settings.meshcoreRoomEnabled
            }
        return combine(
            settings.loraEnabled,
            boundAddress,
            settings.loraDmEnabled,
            roomEnabled,
            lora.status,
        ) { enabled, address, dms, room, status ->
            val plane = loraPlaneFor(enabled, bound = address != null, state = status.state)
            val ready = (status.state as? LinkState.Ready)?.takeIf { plane == LoraPlane.Live }
            // A board pinned to a dedicated RF slot (ADR 067) hears no public channel, so there is no room
            // to mirror on it: hidden, history and all, until the board is restored. Read off the airtime
            // governor's sticky answer rather than the live radio config, so the row does not come back
            // through the reboot the setup itself causes.
            val roomHere = room && status.airtime?.dedicated != true
            LoraFacts(
                plane = plane,
                dms = enabled && dms,
                // Not `enabled && room`, unlike the line above: the room's row is drawn from history as
                // well as from a live board, so switching the *plane* off must not hide it (see LoraFacts).
                room = roomHere,
                battery = status.battery.takeIf { plane == LoraPlane.Live },
                airtimeSpent = plane == LoraPlane.Live && status.airtime?.let(::saturated) == true,
                // Already a boolean on the status, so a busy band never re-emits the facts per reading.
                bandBusy = plane == LoraPlane.Live && status.braked,
                // Both change only on a link transition (the channel table reloads after a setup
                // reboot), so the per-send status republish never churns them.
                primaryChannel = ready?.let { PublicChannelPolicy.primaryName(it.channels, it.radio) },
                // The room switch is folded in here rather than at every reader: a room the user has
                // switched off has nothing to post from, and the transport refuses such a post anyway.
                canPost = roomHere && ready != null && !PublicChannelPolicy.isKnitPrimary(ready.channels),
                // A board we cannot read the table of answers "public", which is the safe half of the
                // claim rather than the accurate one — see the policy's own note.
                primaryKeyIsPublic = ready == null || PublicChannelPolicy.primaryKeyIsPublic(ready.channels),
                // Deliberately not gated on `ready`: the composer stays mounted through a BLE flap, and
                // the budget should follow the board it will post through, not flip on every reconnect.
                roomSignedUpTo = status.airtime?.roomSignedUpTo ?: PublicPostPolicy.MAX_SIGNED_TEXT_BYTES,
                board = kind,
                // A constant of the kind (its traits'), so it never re-emits the facts.
                directBudget = lora.directBudget,
            )
        }.distinctUntilChanged()
    }

    private fun saturated(air: AirtimeSnapshot): Boolean = air.usedMs >= air.allowanceMs * AIRTIME_SPENT_SHARE

    companion object {
        /** How full the window must be before a LoRa-only DM is told it will wait — a whole packet short of refusal. */
        const val AIRTIME_SPENT_SHARE = 0.9
    }
}
