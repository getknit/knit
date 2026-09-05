package app.getknit.knit.ui.lora

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.settings.KnitBoardSetup
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.lora.AirtimeSnapshot
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.BoardFilter
import app.getknit.knit.mesh.lora.BoardName
import app.getknit.knit.mesh.lora.BoardOwner
import app.getknit.knit.mesh.lora.BoardRef
import app.getknit.knit.mesh.lora.BoardSettings
import app.getknit.knit.mesh.lora.KnitChannel
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.LoraGatewayPolicy
import app.getknit.knit.mesh.lora.LoraPlaneStatus
import app.getknit.knit.mesh.lora.LoraSlot
import app.getknit.knit.mesh.lora.ProvisionMode
import app.getknit.knit.mesh.lora.ProvisionResult
import app.getknit.knit.mesh.lora.PublicChannelPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A bonded board the user can bind the LoRa plane to. */
data class BoardOption(
    val address: String,
    val name: String,
    val selected: Boolean,
)

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
    val bridgeEnabled: Boolean = true,
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
    /** True when another board in this BLE/NAN clique won the gateway election and this one only listens. */
    val bridgePassive: Boolean = false,
    /** Airtime spent this hour as a percentage of the plane's own budget, or null before the link is up. */
    val airtimePercent: Int? = null,
    /** The board's region and modem preset, once its config stream has reported them. */
    val radioConfig: String? = null,
    /** The name the connected board gives the selected [channel] slot; null while not connected or when unnamed. */
    val channelName: String? = null,
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
     * Whether the debug-only dedicated-frequency setup is offered at all (ADR 067). False in every release
     * build — the shared public frequency is the shipping bargain and the only one a release user is given.
     */
    val dedicatedOffered: Boolean = false,
    /**
     * The RF slot the dedicated setup would pin this board to, or null when Knit will not place one in its
     * region — which is also what greys the action out rather than letting it fail at the board.
     */
    val dedicatedSlot: Int? = null,
    /** The board is on a dedicated slot right now, so the airtime budget is off the politeness ceiling. */
    val dedicated: Boolean = false,
    /** The open confirmation is the dedicated one, which is a different bargain and says so. */
    val confirmDedicated: Boolean = false,
)

/**
 * The LoRa radio settings screen: the master switch, the bonded-board picker, the channel index, and the
 * live link status. Mirrors [app.getknit.knit.ui.relay.InternetRelayViewModel]; there is no consent sheet
 * because a LoRa board is the user's own hardware, not a third-party relay.
 */
internal class LoraRadioViewModel(
    private val settings: SettingsStore,
    private val lora: LoraPlaneStatus,
    private val boards: BoardDirectory,
) : ViewModel() {
    // Transient, action-driven UI state (the provisioning spinner + its outcome) that isn't in a settings flow.
    private val provisionState = MutableStateFlow(ProvisionState())

    // Bumped to re-read the bonded list (the screen does so on resume, after the user pairs a board in the
    // system settings); the toggle that reveals the devices the board filter hides.
    private val refresh = MutableStateFlow(0)
    private val showAll = MutableStateFlow(false)

    /** The bonded list is a Binder call into the Bluetooth service, so it is read on its own arm — never on link churn. */
    private data class Picker(
        val address: String?,
        val bonded: List<BoardRef>,
        val showAll: Boolean,
    )

    private val picker =
        combine(settings.loraDeviceAddress, refresh, showAll) { address, _, all ->
            Picker(address, runCatching { boards.bonded() }.getOrDefault(emptyList()), all)
        }

    val state: StateFlow<LoraRadioUiState> =
        combine(
            combine(settings.loraEnabled, settings.loraDmEnabled, settings.loraBridgeEnabled, ::Triple),
            picker,
            settings.loraChannelIndex,
            lora.status,
            provisionState,
        ) { (enabled, dmEnabled, bridgeEnabled), picker, channel, status, provision ->
            val address = picker.address
            val ready = status.state as? LinkState.Ready
            // The name the board gives the bound slot: "Knit" once the setup has written it there.
            val channelName =
                ready
                    ?.channels
                    ?.firstOrNull { it.index == channel }
                    ?.name
                    ?.takeIf { it.isNotEmpty() }
            val setUp = ready?.channels?.any { it.name == KnitChannel.NAME } == true
            // The identity the board should carry, which the firmware it runs is half of: a board too old
            // to store the unmonitored mark is wanted exactly as it is (ADR 2026-09.emd7).
            val wanted = status.boardNodeNum?.let { BoardName.forNode(it, ready?.board?.firmwareVersion) }
            LoraRadioUiState(
                enabled = enabled,
                dmEnabled = dmEnabled,
                bridgeEnabled = bridgeEnabled,
                boardName = picker.bonded.firstOrNull { it.address == address }?.name ?: address,
                boardAddress = address,
                channel = channel,
                connection = status.state.toConnState(),
                boardNodeNum = status.boardNodeNum?.let { "!%08x".format(it.toInt()) },
                snr = status.lastSnr,
                rssi = status.lastRssi,
                heard = status.heard,
                boardsHeard = status.boardsHeard,
                firmware = ready?.board?.firmwareVersion,
                battery = ready?.let { status.battery },
                bridgePassive = status.role == LoraGatewayPolicy.Role.PASSIVE,
                airtimePercent = ready?.let { status.airtime?.let(::airtimePercent) },
                radioConfig = ready?.radio?.let { "${it.region} ${it.modemPreset}" },
                channelName = channelName,
                boardSetUp = setUp,
                meshName = ready?.board?.owner?.longName,
                knitName = wanted?.longName,
                // Only a *known* identity asks to be finished: firmware that never sends its own NodeInfo
                // gets the benefit of the doubt, exactly as an unreadable channel table does. Two things
                // can be unfinished here — the name (ADR 049) and the unmonitored mark (ADR 2026-09.emd7) —
                // and the screen tells them apart by comparing [meshName] against [knitName].
                needsRename = setUp && wanted != null && ready.board.owner?.let { it != wanted } == true,
                customPrimary = ready?.let { isCustomPrimary(it) } == true,
                presetMismatch = ready?.let(::presetMismatch),
                confirmSetup = provision.confirm,
                boards =
                    BoardFilter
                        .visible(picker.bonded, address, picker.showAll)
                        .map { BoardOption(it.address, it.name, it.address == address) },
                hiddenBoards = BoardFilter.hidden(picker.bonded, address),
                showAllBoards = picker.showAll,
                anyBonded = picker.bonded.isNotEmpty(),
                provisioning = provision.running,
                provisionOutcome = provision.outcome,
                dedicatedOffered = BuildConfig.DEBUG,
                dedicatedSlot = ready?.radio?.let { LoraSlot.forRegion(it.region, it.modemPreset) },
                dedicated = ready?.radio?.dedicatedSlot == true,
                confirmDedicated = provision.dedicated,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LoraRadioUiState())

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
     * Airtime spent this window as a percentage of what the plane allows itself — the LIVE budget, which is
     * the whole allowance, so this reads as "how much of my radio time Knit has used". Every bucket counts,
     * the bootstrap included: it is judged against its own share on admission, but the air it spends is the
     * same air. Rounded up, so any spending at all shows as at least 1 %.
     */
    private fun airtimePercent(air: AirtimeSnapshot): Int {
        val budget = air.liveBudgetMs
        if (budget <= 0) return 0
        val used = air.liveUsedMs + air.bridgeUsedMs + air.bootstrapUsedMs
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

    fun onToggleBridge(on: Boolean) {
        viewModelScope.launch { settings.setLoraBridgeEnabled(on) }
    }

    fun onToggleDms(on: Boolean) {
        viewModelScope.launch { settings.setLoraDmEnabled(on) }
    }

    fun pickBoard(board: BoardOption) {
        viewModelScope.launch { settings.setLoraDevice(board.address, board.name) }
    }

    fun forgetBoard() {
        viewModelScope.launch { settings.clearLoraDevice() }
    }

    /** Opens the setup confirmation; what it costs the board is spelled out there, not here. */
    fun askSetup() {
        provisionState.update { it.copy(confirm = true, dedicated = false, outcome = null) }
    }

    /**
     * Opens the confirmation for the debug-only dedicated-frequency setup (ADR 067). Inert in a release
     * build, checked here rather than only in the UI so the action cannot be reached by any route.
     */
    fun askSetupDedicated() {
        if (!BuildConfig.DEBUG) return
        provisionState.update { it.copy(confirm = true, dedicated = true, outcome = null) }
    }

    fun dismissSetup() {
        provisionState.update { it.copy(confirm = false) }
    }

    /**
     * Sets the connected board up for Knit over the Meshtastic admin API (ADR 045): the Knit channel goes
     * into a free secondary slot, the board is renamed for Knit (ADR 049) and its housekeeping broadcasts are
     * stretched. The intervals and the name the board had before come back in the result and are persisted
     * here; they are the only way a restore can put back what was actually there.
     */
    fun setUpBoard() {
        val dedicated = provisionState.value.dedicated && BuildConfig.DEBUG
        provision(if (dedicated) ProvisionMode.SetupDedicated else ProvisionMode.Setup)
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
            provisionState.update { it.copy(running = true, outcome = null, confirm = false) }
            val recorded = settings.loraBoardSetup.first()
            when (val result = lora.provisionKnitChannel(mode, recorded?.toIntervals())) {
                is ProvisionResult.Provisioned -> {
                    settings.setLoraChannelIndex(result.index)
                    settings.rememberSetup(result)
                    provisionState.value = ProvisionState(outcome = result.toOutcome())
                }

                ProvisionResult.Restored -> {
                    settings.clearLoraBoardSetup()
                    settings.setLoraEnabled(false)
                    provisionState.value = ProvisionState(outcome = LoraProvisionOutcome.Restored)
                }

                else -> {
                    provisionState.value = ProvisionState(outcome = result.toOutcome())
                }
            }
        }
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

    /** Dismisses the last provisioning outcome banner. */
    fun dismissProvisionOutcome() {
        provisionState.update { it.copy(outcome = null) }
    }

    private fun ProvisionResult.toOutcome(): LoraProvisionOutcome =
        when (this) {
            is ProvisionResult.Provisioned -> if (alreadyPresent) LoraProvisionOutcome.AlreadyPresent else LoraProvisionOutcome.Provisioned
            ProvisionResult.Restored -> LoraProvisionOutcome.Restored
            ProvisionResult.NoFreeSlot -> LoraProvisionOutcome.NoFreeSlot
            is ProvisionResult.NoDedicatedSlot -> LoraProvisionOutcome.NoDedicatedSlot
            is ProvisionResult.Failed -> LoraProvisionOutcome.Failed
            is ProvisionResult.NotReady -> LoraProvisionOutcome.NotReady
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
        /** The pending confirmation is for the debug-only dedicated setup rather than the shared one. */
        val dedicated: Boolean = false,
    )

    private companion object {
        private const val PERCENT = 100L

        const val STOP_TIMEOUT_MS = 5_000L
    }
}
