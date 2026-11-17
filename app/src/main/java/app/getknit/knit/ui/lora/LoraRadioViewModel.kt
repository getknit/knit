package app.getknit.knit.ui.lora

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.settings.KnitBoardSetup
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.lora.AirtimeSnapshot
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.BoardFilter
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.BoardName
import app.getknit.knit.mesh.lora.BoardOwner
import app.getknit.knit.mesh.lora.BoardRef
import app.getknit.knit.mesh.lora.BoardSettings
import app.getknit.knit.mesh.lora.BoardSetupState
import app.getknit.knit.mesh.lora.KnitChannel
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.LoraPlaneStatus
import app.getknit.knit.mesh.lora.LoraSlot
import app.getknit.knit.mesh.lora.LoraStatus
import app.getknit.knit.mesh.lora.MeshcoreBoardFace
import app.getknit.knit.mesh.lora.PairingCause
import app.getknit.knit.mesh.lora.ProvisionMode
import app.getknit.knit.mesh.lora.ProvisionResult
import app.getknit.knit.mesh.lora.PublicChannelPolicy
import app.getknit.knit.mesh.lora.ReplaceToken
import app.getknit.knit.mesh.lora.meshcore.MeshcoreForget
import app.getknit.knit.mesh.lora.meshcore.MeshcoreForgetResult
import app.getknit.knit.notifications.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * A bonded board the user can bind the LoRa plane to, of [kind]: the directory's guess, with a device it cannot place offered
 * as Meshtastic, as before there was a second kind. [pickable] is false for a MeshCore board in a build without the MeshCore
 * plane (`BuildConfig.MESHCORE_PLANE`, ADR 2026-11.gsqu): listed, but binding it as Meshtastic would only dial the wrong
 * firmware.
 */
data class BoardOption(
    val address: String,
    val name: String,
    val selected: Boolean,
    val pickable: Boolean = true,
    val kind: BoardKind = BoardKind.MESHTASTIC,
)

/** Whether this phone set the bound MeshCore board up for Knit, as the radio screen words it (`BoardSetupState`). */
enum class MeshcoreSetup { None, SetUp, Pending, Unknown }

/**
 * What the radio screen shows of a bound MeshCore board (ADR 2026-11.gsqu's Stage 7 amendment). Every fact but [setup] and
 * [sharesPosition] is the Ready board's and null while it is away; [setup] is this phone's record, shown away too.
 */
data class MeshcoreRows(
    val firmware: String? = null,
    val freqKhz: Long? = null,
    val spreadFactor: Int? = null,
    val bandwidthHz: Int? = null,
    /** The coding rate's denominator: 5 for 4/5. */
    val codingRate: Int? = null,
    /** Whether the board floods under a default region scope its own app set; Knit never writes one. */
    val defaultScope: Boolean? = null,
    val batteryMv: Int? = null,
    /** Whether the board may put where it is on the air (null: not read yet); the setup confirmation says so unless false. */
    val sharesPosition: Boolean? = null,
    val setup: MeshcoreSetup = MeshcoreSetup.Unknown,
)

/** What a MeshCore setup would erase, read when its confirmation opens: counts, null for one the board did not answer. */
data class MeshcoreErase(
    val contacts: Long?,
    val slots: Int?,
)

/** A MeshCore Forget's answer worth a line: it failed (ask again), or the board was not the one Knit set up. */
enum class MeshcoreForgetNotice { Failed, Mismatch }

/**
 * A board on a modem preset other than the one a stock board in its region picks. Two radios on different
 * presets are mutually deaf — the same total, silent coupling a renamed primary has — so this is what every
 * other Knit board has to match, and the one 2.8 made easy to trip over by defaulting new US boards to
 * `LongTurbo`.
 *
 * **It is a notice, not a verdict.** The right preset is whatever the *local* mesh runs, and Knit has no way
 * to see that: ADR 045's whole bargain is borrowing relays from stock nodes, and `rebroadcast_mode = ALL`
 * repeats only traffic "from another mesh with the same lora params" — so a region whose community settled on
 * `MediumFast` is a region where `MediumFast` is correct and this region's `LongFast` would leave the board
 * alone on a slot of its own. Hence [stock] is context for why the notice appeared, never an instruction: the
 * message it renders tells the user what their *other* boards must match, and does not ask them to change
 * this one. Both names are the firmware's own, so they read exactly as the Meshtastic app writes them.
 */
data class PresetMismatch(
    val board: String,
    val stock: String,
)

/** How the LoRa link is doing, as a UI-facing enum decoupled from the internal link state. */
enum class LoraConnState { Off, Connecting, Ready, Reconnecting, NeedsPairing, Unavailable }

/** The result of the last provisioning tap, mapped off the internal provision result for the screen. */
enum class LoraProvisionOutcome {
    Provisioned,
    AlreadyPresent,
    Restored,

    /** A pinned board is back on the shared public frequency, still set up for Knit (ADR 067). */
    Shared,
    NoFreeSlot,

    /** The dedicated setup was refused: Knit will not place an RF slot in this board's region (ADR 067). */
    NoDedicatedSlot,
    Failed,
    NotReady,
}

data class LoraRadioUiState(
    val enabled: Boolean = false,
    /** Whether private messages ride LoRa too (`SettingsStore.loraDmEnabled`; meaningful only while [enabled]). */
    val dmEnabled: Boolean = true,
    /**
     * Whether the Meshtastic room is mirrored at all (`SettingsStore.loraRoomEnabled`; meaningful only while
     * [enabled]). Off, slot 0 is never read into a room and the room's row leaves the chat list.
     */
    val roomEnabled: Boolean = true,
    /**
     * Whether the MeshCore room is mirrored at all (`SettingsStore.meshcoreRoomEnabled`, ADR 2026-11.gsqu's Stage 6
     * amendment), and whether a MeshCore board is the bound one ([meshcoreBound]), which is when its switch is drawn.
     */
    val meshcoreRoomEnabled: Boolean = true,
    val meshcoreBound: Boolean = false,
    val boardName: String? = null,
    val boardAddress: String? = null,
    val channel: Int = 0,
    val connection: LoraConnState = LoraConnState.Off,
    val boardNodeNum: String? = null,
    val snr: Float? = null,
    val rssi: Int? = null,
    val heard: Int = 0,
    /** Radios heard on our channel — the honest answer to "is the other board in range". */
    val boardsHeard: Int = 0,
    /** The board's firmware, once the handshake has told us. */
    val firmware: String? = null,
    /** The board's own battery, once it has reported one; null while not connected. */
    val battery: BoardBattery? = null,
    /** Airtime spent this window as a percentage of the plane's own allowance, or null before the link is up. */
    val airtimePercent: Int? = null,
    /** The board's region and modem preset, once its config stream has reported them. */
    val radioConfig: String? = null,
    /** The name the connected board gives the selected [channel] slot; null while not connected or when unnamed. */
    val channelName: String? = null,
    /** The board's primary channel as the Meshtastic room names it (its own name, else the preset's); null until known. */
    val publicChannel: String? = null,
    /**
     * The board is set up for Knit: it carries the Knit channel in a secondary slot, it is named for Knit and
     * its housekeeping is quiet. The only other state is "a stock Meshtastic board" — there is no middle one.
     */
    val boardSetUp: Boolean = false,
    /** What the board currently calls itself on the mesh — the name on its own screen; null on firmware that never says. */
    val meshName: String? = null,
    /** What Knit names a board ([BoardName]); the label of the rename button, and null until the board is known. */
    val knitName: String? = null,
    /**
     * The board carries the Knit channel but is still under its old name — every board set up before ADR 049,
     * and the only case where a set-up board is offered the setup action again (a rename, and nothing else).
     */
    val needsRename: Boolean = false,
    /** The setup confirmation is open — it changes settings on the user's hardware, so the tap is never the action. */
    val confirmSetup: Boolean = false,
    /**
     * The board's main channel has been renamed, which puts its radio on a different frequency from a stock
     * board's — so it will not meet other Knit boards however well it is set up. Rare, silent, and total, so
     * it is the one thing worth saying out loud on this screen.
     */
    val customPrimary: Boolean = false,
    /**
     * The board is on a preset other than its region's stock one, so every other Knit board has to match it —
     * see [PresetMismatch]. Null whenever there is nothing to say, which includes every region whose stock
     * preset Knit does not claim to know exactly ([app.getknit.knit.mesh.lora.LoraRegion.defaultPreset]).
     */
    val presetMismatch: PresetMismatch? = null,
    val boards: List<BoardOption> = emptyList(),
    /** Bonded devices the picker hides as not board-like (`BoardFilter`); the "show all" toggle reveals them. */
    val hiddenBoards: Int = 0,
    val showAllBoards: Boolean = false,
    /** Whether the phone has *any* bonded device — splits "pair one first" from "none of these looks like a board". */
    val anyBonded: Boolean = false,
    val provisioning: Boolean = false,
    val provisionOutcome: LoraProvisionOutcome? = null,
    /**
     * The RF slot the dedicated setup would pin this board to, or null when Knit will not place one in its
     * region — which is also what greys the action out rather than letting it fail at the board.
     */
    val dedicatedSlot: Int? = null,
    /**
     * The board is on a dedicated slot, so the airtime budget is off the politeness ceiling and the Meshtastic
     * room is hidden. Read off the airtime governor's sticky answer rather than the live radio config, so it
     * holds through the reboot the setup itself causes and the room switch does not wake up in between.
     */
    val dedicated: Boolean = false,
    /** The open confirmation is the dedicated one, which is a different bargain and says so. */
    val confirmDedicated: Boolean = false,
    /**
     * The build carries a second board kind (`BuildConfig.MESHCORE_PLANE`), so the picker's wording names both, never
     * Meshtastic alone.
     */
    val anyKind: Boolean = false,
    /** The bound MeshCore board's rows, in a build carrying its plane; null otherwise, which hides every MeshCore row. */
    val meshcore: MeshcoreRows? = null,
    /** The board twice showed no service of the bound kind: the wrong firmware, or reflashed since it was paired. */
    val wrongFirmware: Boolean = false,
    /** What the open MeshCore setup confirmation would erase, once read; [eraseLoading] while it is being read. */
    val erase: MeshcoreErase? = null,
    val eraseLoading: Boolean = false,
    /** A MeshCore setup found another board's record at this address: the screen asks before setting it up over it. */
    val confirmReplace: Boolean = false,
    /** A MeshCore Forget could not clean the board now: Keep it (the default) or Forget anyway. */
    val forgetPending: Boolean = false,
    /** A MeshCore Forget is running (it may wait on the board's session). */
    val forgetting: Boolean = false,
    val forgetNotice: MeshcoreForgetNotice? = null,
)

/**
 * The LoRa radio settings screen: the master switch, the bonded-board picker, the channel index, and the
 * live link status. Mirrors [app.getknit.knit.ui.relay.InternetRelayViewModel]; there is no consent sheet
 * because a LoRa board is the user's own hardware, not a third-party relay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LoraRadioViewModel(
    private val settings: SettingsStore,
    /** The Meshtastic plane. */
    private val lora: LoraPlaneStatus,
    boards: BoardDirectory,
    private val notifier: Notifier,
    /** The MeshCore plane (ADR 2026-11.gsqu's Stage 7 amendment), only where the build carries it. */
    private val meshcore: LoraPlaneStatus? = null,
    /** The one Forget of a bound MeshCore board (`MeshcoreForget`), beside [meshcore]. */
    private val forget: MeshcoreForget? = null,
    meshcorePlane: Boolean = BuildConfig.MESHCORE_PLANE,
) : ViewModel() {
    /**
     * Whether this screen offers MeshCore at all: the flag, its plane and its Forget. Off, the screen reads exactly as a
     * Meshtastic-only build's: no MeshCore row, no MeshCore pick, no MeshCore action.
     */
    private val withMeshcore = meshcorePlane && meshcore != null && forget != null

    // Transient, action-driven UI state (the provisioning spinner + its outcome) that isn't in a settings flow.
    private val provisionState = MutableStateFlow(ProvisionState())

    /** The erase preview's read, cancelled when its confirmation closes. */
    private var previewJob: Job? = null

    // Bumped to re-read the bonded list (the screen does so on resume, after the user pairs a board in the
    // system settings); the toggle that reveals the devices the board filter hides.
    private val refresh = MutableStateFlow(0)
    private val showAll = MutableStateFlow(false)

    /**
     * The per-plane switches — and the MeshCore room's, with whether a MeshCore board is bound — folded onto one arm so the
     * state combine stays inside its five-flow arity.
     */
    private data class Switches(
        val enabled: Boolean,
        val dms: Boolean,
        val room: Boolean,
        val meshcoreRoom: Boolean,
        val meshcoreBound: Boolean,
    ) {
        /** A MeshCore board is bound and this screen drives it: the MeshCore rows and actions, never Meshtastic's. */
        fun meshcoreRows(withMeshcore: Boolean): Boolean = withMeshcore && meshcoreBound
    }

    private val switches =
        combine(
            settings.loraEnabled,
            settings.loraDmEnabled,
            settings.loraRoomEnabled,
            settings.meshcoreRoomEnabled,
            settings.boundBoardKind,
        ) { enabled, dms, room, meshcoreRoom, bound -> Switches(enabled, dms, room, meshcoreRoom, bound == BoardKind.MESHCORE) }

    /** The bonded list is a Binder call into the Bluetooth service, so it is read on its own arm — never on link churn. */
    private data class Picker(
        val address: String?,
        val bonded: List<BoardRef>,
        val showAll: Boolean,
    )

    /** The bound board's address, whichever kind it is (v1 binds one board at a time); MeshCore's only where it is offered. */
    private val boundAddress: Flow<String?> =
        if (withMeshcore) {
            combine(settings.loraDeviceAddress, settings.meshcoreDeviceAddress) { meshtastic, meshcore -> meshtastic ?: meshcore }
        } else {
            settings.loraDeviceAddress
        }

    private val picker =
        combine(boundAddress, refresh, showAll) { address, _, all ->
            Picker(address, runCatching { boards.bonded() }.getOrDefault(emptyList()), all)
        }

    /** The bound kind's plane status: MeshCore's while a MeshCore board is bound and offered, else Meshtastic's. */
    private val status: Flow<LoraStatus> =
        meshcore?.takeIf { withMeshcore }?.let { plane ->
            settings.boundBoardKind.flatMapLatest { kind -> if (kind == BoardKind.MESHCORE) plane.status else lora.status }
        } ?: lora.status

    val state: StateFlow<LoraRadioUiState> =
        combine(
            switches,
            picker,
            settings.loraChannelIndex,
            status,
            provisionState,
        ) { switches, picker, channel, status, provision ->
            val address = picker.address
            val meshcoreRows = switches.meshcoreRows(withMeshcore)
            // A MeshCore board's link carries none of Meshtastic's channel table, owner or radio config: its rows are its own.
            val ready = (status.state as? LinkState.Ready)?.takeUnless { meshcoreRows }
            // The name the board gives the bound slot: "Knit" once the setup has written it there.
            val channelName =
                ready
                    ?.channels
                    ?.firstOrNull { it.index == channel }
                    ?.name
                    ?.takeIf { it.isNotEmpty() }
            // Not while another client has switched the Knit slot to AEAD (#243): no other Knit board shares that channel,
            // and running the setup again clears it, so the screen offers the action.
            val setUp = ready?.channels?.any { it.name == KnitChannel.NAME && !it.useAead } == true
            // The identity the board should carry, which the firmware it runs is half of: a board too old
            // to store the unmonitored mark is wanted exactly as it is (ADR 2026-09.emd7).
            val wanted = status.boardNodeNum?.let { BoardName.forNode(it, ready?.board?.firmwareVersion) }
            LoraRadioUiState(
                enabled = switches.enabled,
                dmEnabled = switches.dms,
                roomEnabled = switches.room,
                meshcoreRoomEnabled = switches.meshcoreRoom,
                meshcoreBound = switches.meshcoreBound,
                boardName = picker.bonded.firstOrNull { it.address == address }?.name ?: address,
                boardAddress = address,
                channel = channel,
                connection = status.state.toConnState(),
                boardNodeNum = status.boardNodeNum?.takeUnless { meshcoreRows }?.let { "!%08x".format(Locale.ROOT, it.toInt()) },
                snr = status.lastSnr.takeUnless { meshcoreRows },
                rssi = status.lastRssi.takeUnless { meshcoreRows },
                heard = status.heard,
                boardsHeard = status.boardsHeard,
                firmware = if (meshcoreRows) status.meshcore?.firmware else ready?.board?.firmwareVersion,
                battery = ready?.let { status.battery },
                airtimePercent = (status.state as? LinkState.Ready)?.let { status.airtime?.let(::airtimePercent) },
                radioConfig = ready?.radio?.let { "${it.region} ${it.modemPreset}" },
                channelName = channelName,
                publicChannel = ready?.let { PublicChannelPolicy.primaryName(it.channels, it.radio) },
                boardSetUp = if (meshcoreRows) status.meshcore?.setup == BoardSetupState.SET_UP else setUp,
                meshName = ready?.board?.owner?.longName,
                knitName = wanted?.longName,
                // Only a *known* identity asks to be finished: firmware that never sends its own NodeInfo
                // gets the benefit of the doubt, exactly as an unreadable channel table does. Two things
                // can be unfinished here — the name (ADR 049) and the unmonitored mark (ADR 2026-09.emd7) —
                // and the screen tells them apart by comparing [meshName] against [knitName].
                needsRename = setUp && wanted != null && ready.board.owner?.let { !it.carries(wanted) } == true,
                customPrimary = ready?.let { isCustomPrimary(it) } == true,
                presetMismatch = ready?.let(::presetMismatch),
                confirmSetup = provision.confirm,
                boards =
                    BoardFilter
                        .visible(picker.bonded, address, picker.showAll)
                        .map {
                            BoardOption(
                                address = it.address,
                                name = it.name,
                                selected = it.address == address,
                                pickable = it.kind != BoardKind.MESHCORE || withMeshcore,
                                kind = it.kind ?: BoardKind.MESHTASTIC,
                            )
                        },
                hiddenBoards = BoardFilter.hidden(picker.bonded, address),
                showAllBoards = picker.showAll,
                anyBonded = picker.bonded.isNotEmpty(),
                provisioning = provision.running,
                provisionOutcome = provision.outcome,
                dedicatedSlot = ready?.radio?.let { LoraSlot.forRegion(it.region, it.modemPreset) },
                dedicated = !meshcoreRows && status.airtime?.dedicated == true,
                confirmDedicated = provision.dedicated,
                anyKind = withMeshcore,
                meshcore = if (meshcoreRows) (status.meshcore ?: MeshcoreBoardFace()).toRows() else null,
                wrongFirmware = status.state.wrongFirmware(),
                erase = provision.erase,
                eraseLoading = provision.eraseLoading,
                confirmReplace = provision.replace != null,
                forgetPending = provision.forgetPending,
                forgetting = provision.forgetting,
                forgetNotice = provision.forgetNotice,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LoraRadioUiState())

    private fun MeshcoreBoardFace.toRows(): MeshcoreRows =
        MeshcoreRows(
            firmware = firmware,
            freqKhz = freqKhz,
            spreadFactor = spreadFactor,
            bandwidthHz = bandwidthHz,
            codingRate = codingRate,
            defaultScope = defaultScope,
            batteryMv = batteryMv,
            sharesPosition = sharesPosition,
            setup =
                when (setup) {
                    BoardSetupState.NONE -> MeshcoreSetup.None
                    BoardSetupState.SET_UP -> MeshcoreSetup.SetUp
                    BoardSetupState.PENDING -> MeshcoreSetup.Pending
                    BoardSetupState.UNKNOWN -> MeshcoreSetup.Unknown
                },
        )

    /** The session ended because the board twice showed no service of the bound kind ([PairingCause.NO_SERVICE]). */
    private fun LinkState.wrongFirmware(): Boolean =
        when (this) {
            is LinkState.NeedsPairing -> cause == PairingCause.NO_SERVICE
            is LinkState.StaleBond -> cause == PairingCause.NO_SERVICE
            else -> false
        }

    /** Whether a MeshCore board is bound now and this screen drives it. */
    private suspend fun meshcoreBound(): Boolean = withMeshcore && settings.boundBoardKind.first() == BoardKind.MESHCORE

    /**
     * Whether the board's primary channel has been renamed away from its preset's default. The firmware hashes
     * that name into the radio's frequency, so a renamed primary silently parks the board on a slot no stock
     * board — and so no other Knit board — is listening to. Unknown until the board reports its radio config,
     * which reads as "fine": a spurious warning is worse than a late one.
     */
    private fun isCustomPrimary(ready: LinkState.Ready): Boolean {
        // A board that has not reported its preset cannot be judged: crying "renamed" at a board that is fine
        // is worse than staying quiet, so the unknown case is handled here rather than read off
        // [PublicChannelPolicy.hasStockName]'s false.
        if (ready.radio == null) return false
        val primary = ready.channels.firstOrNull { it.index == PublicChannelPolicy.PRIMARY_INDEX } ?: return false
        return !PublicChannelPolicy.hasStockName(primary, ready.radio)
    }

    /**
     * Whether the board sits on a different modem preset from the one a stock board in its region picks by
     * itself — the point at which the preset stops being something the user can leave alone, since every
     * board they want to reach now has to be set to match. Silent on the two cases where the answer would be
     * a guess: a region whose stock preset Knit does not state exactly, and a board on hand-rolled radio
     * settings, whose `modem_preset` field says nothing about what it actually transmits.
     */
    private fun presetMismatch(ready: LinkState.Ready): PresetMismatch? {
        val radio = ready.radio ?: return null
        if (!radio.usePreset) return null
        val stock = radio.region.defaultPreset ?: return null
        if (radio.modemPreset == stock) return null
        return PresetMismatch(board = radio.modemPreset.defaultChannelName, stock = stock.defaultChannelName)
    }

    /**
     * Airtime spent this window as a percentage of what the plane allows itself — `usedMs / allowanceMs`, one ledger
     * against one allowance (ADR 2026-10.ug4a), so this reads as "how much of my radio time Knit has used". Rounded up,
     * so any spending at all shows as at least 1 %.
     */
    private fun airtimePercent(air: AirtimeSnapshot): Int {
        val budget = air.allowanceMs
        if (budget <= 0) return 0
        val used = air.usedMs
        return ((used * PERCENT + budget - 1) / budget).toInt().coerceIn(0, PERCENT.toInt())
    }

    /** Re-reads the bonded list — the screen calls this on resume, so a board paired in Settings shows up on return. */
    fun refreshBoards() {
        refresh.update { it + 1 }
    }

    /** Reveals (or re-hides) the bonded devices the board filter keeps out of the picker. */
    fun setShowAllBoards(on: Boolean) {
        showAll.value = on
    }

    fun onToggle(on: Boolean) {
        viewModelScope.launch { settings.setLoraEnabled(on) }
    }

    fun onToggleDms(on: Boolean) {
        viewModelScope.launch { settings.setLoraDmEnabled(on) }
    }

    /**
     * Switches the Meshtastic room on or off. Switching it off also clears whatever the room has already put
     * on the shade: the transport stops delivering from this moment, so nothing *new* can notify, but a post
     * heard a minute ago would otherwise sit there pointing at a thread that no longer has a row.
     */
    fun onToggleRoom(on: Boolean) {
        viewModelScope.launch {
            settings.setLoraRoomEnabled(on)
            if (!on) notifier.clearConversation(Conversations.MESHTASTIC)
        }
    }

    /**
     * Switches the MeshCore room on or off (ADR 2026-11.gsqu's Stage 6 amendment): the plane stops delivering from this
     * moment, and switching it off clears what the room already put on the shade, as [onToggleRoom] does for its room.
     */
    fun onToggleMeshcoreRoom(on: Boolean) {
        viewModelScope.launch {
            settings.setMeshcoreRoomEnabled(on)
            if (!on) notifier.clearConversation(Conversations.MESHCORE_PUBLIC)
        }
    }

    /**
     * Binds [board]. Never a MeshCore board in a build without its plane (ADR 2026-11.gsqu, Stage 3d): the screen greys it
     * out too. With a MeshCore board bound, it is forgotten first ([MeshcoreForget], ADR 2026-11.gsqu's Stage 7 amendment), so
     * its cleanup and the withdrawal of its published key run before another board takes its place; a cleanup the board is
     * away for asks Keep it or Forget anyway, the pick waiting on the answer.
     */
    fun pickBoard(board: BoardOption) {
        if (!board.pickable || board.selected || provisionState.value.forgetting) return
        viewModelScope.launch {
            if (meshcoreBound()) forgetThen(anyway = false, pick = board) else bind(board)
        }
    }

    /** Forgets the bound board: a MeshCore one through its Forget ([pickBoard]'s rules), a Meshtastic one at once. */
    fun forgetBoard() {
        if (provisionState.value.forgetting) return
        viewModelScope.launch {
            if (meshcoreBound()) forgetThen(anyway = false, pick = null) else settings.clearLoraDevice()
        }
    }

    /** "Keep it": the MeshCore board stays bound so its next connection runs the cleanup; a pick waiting on it is dropped. */
    fun keepBoard() {
        provisionState.update { it.copy(forgetPending = false, pendingPick = null) }
    }

    /** "Forget anyway": the binding goes with the cleanup still owed (a later binding of that board runs it), then the pick. */
    fun forgetAnyway() {
        val pick = provisionState.value.pendingPick
        provisionState.update { it.copy(forgetPending = false, pendingPick = null) }
        viewModelScope.launch { forgetThen(anyway = true, pick = pick) }
    }

    private suspend fun forgetThen(
        anyway: Boolean,
        pick: BoardOption?,
    ) {
        val meshcoreForget = forget ?: return
        if (provisionState.value.forgetting) return
        provisionState.update { it.copy(forgetting = true, forgetNotice = null, outcome = null) }
        // The Forget switches the plane off with the board gone; a board picked in its place keeps the switch as it was.
        val wasOn = settings.loraEnabled.first()
        val answer =
            try {
                meshcoreForget.forget(anyway)
            } finally {
                provisionState.update { it.copy(forgetting = false) }
            }
        when (answer) {
            MeshcoreForgetResult.CLEARED, MeshcoreForgetResult.FORGOTTEN_PENDING, MeshcoreForgetResult.NOT_BOUND -> {
                pick?.let { bind(it, keepOn = wasOn) }
            }

            MeshcoreForgetResult.MISMATCH -> {
                provisionState.update { it.copy(forgetNotice = MeshcoreForgetNotice.Mismatch) }
                pick?.let { bind(it, keepOn = wasOn) }
            }

            MeshcoreForgetResult.PENDING -> {
                provisionState.update { it.copy(forgetPending = true, pendingPick = pick) }
            }

            MeshcoreForgetResult.FAILED -> {
                provisionState.update { it.copy(forgetNotice = MeshcoreForgetNotice.Failed) }
            }
        }
    }

    private suspend fun bind(
        board: BoardOption,
        keepOn: Boolean = false,
    ) {
        if (board.kind == BoardKind.MESHCORE) {
            settings.bindBoard(BoardKind.MESHCORE, board.address, board.name)
        } else {
            settings.setLoraDevice(board.address, board.name)
        }
        if (keepOn) settings.setLoraEnabled(true)
    }

    /**
     * Opens the setup confirmation; what it costs the board is spelled out there, not here. A MeshCore board's also reads
     * what the setup would erase ([LoraPlaneStatus.setupPreview]), fresh, while it is open.
     */
    fun askSetup() {
        provisionState.update { it.copy(confirm = true, dedicated = false, outcome = null, erase = null, eraseLoading = false) }
        previewJob?.cancel()
        previewJob =
            viewModelScope.launch {
                if (!meshcoreBound()) return@launch
                provisionState.update { it.copy(eraseLoading = true) }
                try {
                    val preview =
                        runCatching { meshcore?.setupPreview() }
                            .onFailure { if (it is CancellationException) throw it }
                            .getOrNull()
                    provisionState.update { it.copy(erase = preview?.let { p -> MeshcoreErase(p.contacts, p.slots) }) }
                } finally {
                    provisionState.update { it.copy(eraseLoading = false) }
                }
            }
    }

    /** Opens the confirmation for the dedicated-frequency setup (ADR 067), which spells out the trade. */
    fun askSetupDedicated() {
        provisionState.update { it.copy(confirm = true, dedicated = true, outcome = null) }
    }

    fun dismissSetup() {
        previewJob?.cancel()
        provisionState.update { it.copy(confirm = false, erase = null, eraseLoading = false) }
    }

    /**
     * Sets a MeshCore board up over the other board's record its setup found ([ProvisionResult.DifferentBoard]): refused again
     * if that record changed since.
     */
    fun replaceBoard() {
        val token = provisionState.value.replace ?: return
        if (provisionState.value.running) return
        viewModelScope.launch {
            provisionState.update { it.copy(running = true, outcome = null, replace = null) }
            provisionMeshcore(ProvisionMode.Setup, replace = token)
        }
    }

    fun dismissReplace() {
        provisionState.update { it.copy(replace = null) }
    }

    /**
     * Sets the connected board up for Knit over the Meshtastic admin API (ADR 045): the Knit channel goes
     * into a free secondary slot, the board is renamed for Knit (ADR 049) and its housekeeping broadcasts are
     * stretched. The intervals and the name the board had before come back in the result and are persisted
     * here; they are the only way a restore can put back what was actually there.
     */
    fun setUpBoard() {
        val dedicated = provisionState.value.dedicated
        provision(if (dedicated) ProvisionMode.SetupDedicated else ProvisionMode.Setup)
    }

    /**
     * Moves a board off its dedicated RF slot and back onto the shared public frequency in one step, leaving
     * the setup itself alone (ADR 067). Unconfirmed, like [restoreBoard], which moves the radio the same way
     * and more: nothing here is lost, and the shared frequency is the bargain the board was offered first.
     */
    fun switchToShared() {
        provision(ProvisionMode.SetupShared)
    }

    /**
     * Puts the board back the way it was. It carries no Knit channel afterwards, so the plane goes off with
     * it — left on, it would fan Knit's frames out over whatever channel the board landed back on.
     */
    fun restoreBoard() {
        provision(ProvisionMode.Restore)
    }

    private fun provision(mode: ProvisionMode) {
        if (provisionState.value.running) return
        viewModelScope.launch {
            provisionState.update { it.copy(running = true, outcome = null, confirm = false, forgetNotice = null) }
            if (meshcoreBound()) provisionMeshcore(mode) else provisionMeshtastic(mode)
        }
    }

    private suspend fun provisionMeshtastic(mode: ProvisionMode) {
        val recorded = settings.loraBoardSetup.first()
        when (val result = lora.provisionKnitChannel(mode, recorded?.toIntervals())) {
            is ProvisionResult.Provisioned -> {
                settings.setLoraChannelIndex(result.index)
                settings.rememberSetup(result)
                // The board's channel table is unchanged either way, so the result cannot say which
                // direction the radio moved; the mode can.
                val outcome = if (mode == ProvisionMode.SetupShared) LoraProvisionOutcome.Shared else result.toOutcome()
                settled(outcome)
            }

            ProvisionResult.Restored -> {
                settings.clearLoraBoardSetup()
                settings.setLoraEnabled(false)
                settled(LoraProvisionOutcome.Restored)
            }

            else -> {
                settled(result.toOutcome())
            }
        }
    }

    /**
     * A MeshCore setup or restore (ADR 2026-11.gsqu's Stage 7 amendment): the record is the plane's own (SQLCipher), so nothing
     * here is written to settings; only a restore switches the plane off, as Meshtastic's does. A setup that finds another
     * board's record at the address ([ProvisionResult.DifferentBoard]) asks before [replaceBoard] sets it up over it.
     */
    private suspend fun provisionMeshcore(
        mode: ProvisionMode,
        replace: ReplaceToken? = null,
    ) {
        val plane = meshcore ?: return settled(LoraProvisionOutcome.Failed)
        val result = if (replace != null) plane.provisionReplacing(replace) else plane.provisionKnitChannel(mode)
        when (result) {
            is ProvisionResult.DifferentBoard -> {
                provisionState.update { it.copy(running = false, replace = result.token) }
            }

            ProvisionResult.Restored -> {
                settings.setLoraEnabled(false)
                settled(LoraProvisionOutcome.Restored)
            }

            else -> {
                settled(result.toOutcome())
            }
        }
    }

    /** Ends a provisioning tap on [outcome], leaving a Forget's state alone. */
    private fun settled(outcome: LoraProvisionOutcome) {
        provisionState.update { it.copy(running = false, outcome = outcome, confirm = false, dedicated = false) }
    }

    /**
     * Records the setup against the bound board's address. Skipped when nothing was written
     * ([ProvisionResult.Provisioned.previous] is null then) — overwriting the stored intervals with nothing
     * would throw away the only copy of what the board looked like before Knit took it over.
     */
    private suspend fun SettingsStore.rememberSetup(result: ProvisionResult.Provisioned) {
        val address = loraDeviceAddress.first() ?: return
        val previous = result.previous ?: return
        setLoraBoardSetup(
            KnitBoardSetup(
                address = address,
                nodeInfoSecs = previous.nodeInfoSecs,
                positionSecs = previous.positionSecs,
                smartPosition = previous.smartPosition,
                telemetrySecs = previous.telemetrySecs,
                rebroadcastMode = previous.rebroadcastMode,
                longName = previous.owner?.longName.orEmpty(),
                shortName = previous.owner?.shortName.orEmpty(),
                channelNum = previous.channelNum,
            ),
        )
    }

    private fun KnitBoardSetup.toIntervals(): BoardSettings =
        BoardSettings(
            nodeInfoSecs = nodeInfoSecs,
            positionSecs = positionSecs,
            smartPosition = smartPosition,
            telemetrySecs = telemetrySecs,
            rebroadcastMode = rebroadcastMode,
            // Both empty means no name was ever recorded — the restore then writes the firmware's own.
            owner = if (longName.isEmpty() && shortName.isEmpty()) null else BoardOwner(longName, shortName),
            channelNum = channelNum,
        )

    /** Dismisses the last provisioning outcome banner, and a Forget's notice. */
    fun dismissProvisionOutcome() {
        provisionState.update { it.copy(outcome = null, forgetNotice = null) }
    }

    private fun ProvisionResult.toOutcome(): LoraProvisionOutcome =
        when (this) {
            is ProvisionResult.Provisioned -> if (alreadyPresent) LoraProvisionOutcome.AlreadyPresent else LoraProvisionOutcome.Provisioned

            ProvisionResult.Restored -> LoraProvisionOutcome.Restored

            ProvisionResult.NoFreeSlot -> LoraProvisionOutcome.NoFreeSlot

            is ProvisionResult.NoDedicatedSlot -> LoraProvisionOutcome.NoDedicatedSlot

            is ProvisionResult.Failed -> LoraProvisionOutcome.Failed

            is ProvisionResult.NotReady -> LoraProvisionOutcome.NotReady

            // Handled before this: [provisionMeshcore] asks before a replace.
            is ProvisionResult.DifferentBoard -> LoraProvisionOutcome.Failed
        }

    private fun LinkState.toConnState(): LoraConnState =
        when (this) {
            is LinkState.Ready -> LoraConnState.Ready
            LinkState.Connecting, LinkState.Bonding, is LinkState.Handshaking -> LoraConnState.Connecting
            is LinkState.Disconnected -> LoraConnState.Reconnecting
            is LinkState.NeedsPairing, is LinkState.StaleBond -> LoraConnState.NeedsPairing
            LinkState.Unavailable -> LoraConnState.Unavailable
            LinkState.Idle -> LoraConnState.Off
        }

    private data class ProvisionState(
        val running: Boolean = false,
        val outcome: LoraProvisionOutcome? = null,
        val confirm: Boolean = false,
        /** The pending confirmation is for the dedicated setup rather than the shared one. */
        val dedicated: Boolean = false,
        /** What the open MeshCore confirmation would erase, and whether it is being read. */
        val erase: MeshcoreErase? = null,
        val eraseLoading: Boolean = false,
        /** The other board's record a MeshCore setup found, for [replaceBoard]; the replace dialog is open while set. */
        val replace: ReplaceToken? = null,
        val forgetting: Boolean = false,
        val forgetPending: Boolean = false,
        /** The board picked while the bound MeshCore one's cleanup could not run: bound after "Forget anyway". */
        val pendingPick: BoardOption? = null,
        val forgetNotice: MeshcoreForgetNotice? = null,
    )

    private companion object {
        private const val PERCENT = 100L

        const val STOP_TIMEOUT_MS = 5_000L
    }
}
