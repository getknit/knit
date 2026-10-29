package app.getknit.knit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.call.OwnIceServers
import app.getknit.knit.data.relay.RelayFacts
import app.getknit.knit.data.settings.AwareSwitch
import app.getknit.knit.data.settings.CallIntake
import app.getknit.knit.data.settings.LowPowerSwitch
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.power.LowPowerPosture
import app.getknit.knit.mesh.power.LowPowerReason
import app.getknit.knit.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The LoRa plane, as the Settings row summarises it before handing off to its own screen. */
data class LoraSummary(
    val enabled: Boolean = false,
    val boardName: String? = null,
    /** The live link, so the row can say "connected" rather than only "on". */
    val plane: LoraPlane = LoraPlane.Off,
    /** The connected board's battery, once it has reported one. */
    val battery: BoardBattery? = null,
)

/**
 * The Calls row's subtitle (docs/VOICE_CALLS.md §3.14): which of the person's own servers a call uses, relay-all, and
 * which wired kinds of incoming call ([live], [walkie]) the person switched off (C-3.3-7).
 */
data class CallsSummary(
    val relayAll: Boolean = false,
    val ownStun: Boolean = false,
    val ownTurn: Boolean = false,
    val live: Boolean = true,
    val walkie: Boolean = false,
    val intake: CallIntake = CallIntake(),
) {
    /** Live calls are wired and switched off here. */
    val refusesLive: Boolean get() = live && !intake.live

    /** Mesh calls are wired and switched off here. */
    val refusesWalkie: Boolean get() = walkie && !intake.walkie

    /** No wired kind rings. */
    val refusesAll: Boolean get() = (!live || refusesLive) && (!walkie || refusesWalkie)
}

/**
 * Low-power mode as its Settings row shows it (ADR 2026-10.d44b): the user's own [switchOn], and the [reason] the
 * posture is on, null while it is off. The two differ when Battery Saver holds the mode on with the switch off, and
 * when the charger has lifted it with the switch on.
 */
data class LowPowerSummary(
    val switchOn: Boolean = false,
    val reason: LowPowerReason? = null,
)

/** The Internet-relay plane, as the Settings row summarises it before handing off to its own screen. */
data class RelaySummary(
    val enabled: Boolean = false,
    val configured: Int = 0,
    val active: Int = 0,
    val connected: Int = 0,
)

/**
 * You, as the Settings screen's header row shows you before handing off to the profile editor.
 *
 * [name] is already resolved through [displayNameFor], so it falls back to the alias on a device whose
 * owner has not set a name yet. [alias] is null in exactly that case — the name *is* the alias then, and
 * printing it twice reads as broken and makes TalkBack say it twice. With a name set it is always shown,
 * because it is what tells two same-named people apart (ADR 058).
 */
data class ProfileHeader(
    val name: String = "",
    val alias: String? = null,
    val avatarHash: String? = null,
    /** Keys the letter avatar's tint; empty until the id resolves, like [name]. */
    val nodeId: String = "",
)

class SettingsViewModel(
    private val settings: SettingsStore,
    identity: Identity,
    // The facts flow, not the repository: the production flow polls forever, and a `runTest` virtual
    // clock makes its `delay` instant, so a ViewModel test that drives this with `advanceUntilIdle()`
    // would spin. Taking the flow lets a test supply a finite one.
    relayFacts: Flow<RelayFacts>,
    loraFacts: Flow<LoraFacts>,
    // The person's own call servers (§3.14), for the Calls row's summary.
    ownIce: Flow<OwnIceServers>,
    private val lowPowerSwitch: LowPowerSwitch,
    // `LowPowerSource.posture`: the switch with Battery Saver and the charger applied.
    lowPowerPosture: Flow<LowPowerPosture>,
    // C-3.3-7: which incoming calls the person takes, and the kinds this build wires, for the Calls row.
    intake: Flow<CallIntake> = flowOf(CallIntake()),
    private val liveCalls: Boolean = true,
    private val meshCalls: Boolean = false,
    // The person's Settings › Wi-Fi Aware switch (GitHub #44); null reads on and writes nothing (tests that skip it).
    private val awareSwitch: AwareSwitch? = null,
) : ViewModel() {
    private val nodeId = MutableStateFlow("")
    private val alias = MutableStateFlow("")

    /**
     * The header row's contents. Unlike the profile editor's fields — which are write-through local state,
     * loaded once — this is read-only, so it observes the store continuously: renaming yourself on the
     * screen behind this row must be visible the moment you come back to it.
     */
    val header: StateFlow<ProfileHeader> =
        combine(settings.displayName, settings.ownAvatarHash, nodeId, alias) { stored, hash, id, alias ->
            ProfileHeader(
                // Blank until the node id resolves: displayNameFor would otherwise alias the empty string.
                name = if (id.isEmpty()) "" else displayNameFor(stored, id),
                alias = alias.takeIf { it.isNotEmpty() && stored.isNotBlank() },
                avatarHash = hash,
                nodeId = id,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileHeader())

    /**
     * What the Calls row says: "Relay all calls" (C-3.7-3) and the own servers a call actually uses — switched on and
     * valid, as [app.getknit.knit.call.OwnIceServers.effective] reads them — and the incoming calls switched off.
     */
    val callsSummary: StateFlow<CallsSummary> =
        combine(settings.callsRelayAll, ownIce, intake) { relayAll, own, intake ->
            val ice = own.effective()
            CallsSummary(
                relayAll = relayAll,
                ownStun = ice.stun.isNotEmpty(),
                ownTurn = ice.turn != null,
                live = liveCalls,
                walkie = meshCalls,
                intake = intake,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CallsSummary(live = liveCalls, walkie = meshCalls))

    /** Whether to colour the app from the wallpaper. Off by default; hidden entirely below API 31. */
    val dynamicColor: StateFlow<Boolean> =
        settings.dynamicColor
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Light, dark, or the system's answer. Follows the system by default; hidden entirely below API 31,
     * where there is no per-app night mode to set.
     *
     * Read from the store rather than from the configuration so the selector shows what the *user* chose:
     * a device in dark mode with the mode left on [ThemeMode.System] must not read back as [ThemeMode.Dark].
     */
    val themeMode: StateFlow<ThemeMode> =
        settings.themeMode
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.System)

    /**
     * Summary of the Internet (spool) plane for the row that navigates to its own screen — the switch
     * itself lives there, with the relay-list editor it needs to be actionable.
     */
    val relaySummary: StateFlow<RelaySummary> =
        relayFacts
            .map { RelaySummary(it.enabled, it.configured, it.active, it.connected) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RelaySummary())

    /**
     * Whether this identity was seen running on another phone since the user last dismissed the notice
     * (`SettingsStore.cloneSeenAt` past `cloneDismissedAt`, ADR 2026-09.ypcc) — the sign-out row, shown
     * exactly while the chat list's banner would be.
     */
    val cloneVisible: StateFlow<Boolean> =
        combine(settings.cloneSeenAt, settings.cloneDismissedAt) { seen, dismissed -> seen > dismissed }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Summary of the LoRa plane for the row that navigates to its own screen: settings + the live link. */
    val loraSummary: StateFlow<LoraSummary> =
        combine(settings.loraEnabled, settings.loraDeviceName, loraFacts) { enabled, name, lora ->
            LoraSummary(enabled, name, lora.plane, lora.battery)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoraSummary())

    /** Low-power mode's switch and what holds the posture on, for the row's subtitle. */
    val lowPower: StateFlow<LowPowerSummary> =
        combine(lowPowerSwitch.on, lowPowerPosture) { on, posture -> LowPowerSummary(on, posture.reason) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LowPowerSummary())

    /** Whether Wi-Fi Aware is on (Settings › Wi-Fi Aware). On by default; off stops the plane outright. */
    val wifiAwareOn: StateFlow<Boolean> =
        (awareSwitch?.on ?: flowOf(true))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        viewModelScope.launch {
            val id = identity.nodeId()
            nodeId.value = id
            alias.value = Alias.aliasFor(id)
        }
    }

    fun setLowPowerMode(value: Boolean) {
        viewModelScope.launch { lowPowerSwitch.set(value) }
    }

    fun setWifiAwareOn(value: Boolean) {
        val switch = awareSwitch ?: return
        viewModelScope.launch { switch.set(value) }
    }

    fun setDynamicColor(value: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(value) }
    }

    /**
     * Persist the choice; `ThemePreferences` collects it and hands it to the platform, which applies it as a
     * configuration change — so this Activity is recreated, exactly as it is when the system's own dark-theme
     * switch is flipped.
     */
    fun setThemeMode(value: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(value) }
    }
}
