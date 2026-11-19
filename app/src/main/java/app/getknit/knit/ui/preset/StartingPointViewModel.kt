package app.getknit.knit.ui.preset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.settings.Preset
import app.getknit.knit.data.settings.PresetChange
import app.getknit.knit.data.settings.PresetStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The preset the person started from, and whether a switch it supplies now holds their own, different value. */
data class StartingPoint(
    val preset: Preset = Preset.STANDARD,
    val customised: Boolean = false,
)

/** The starting point as one flow, for the Settings row and the Starting point screen. */
fun PresetStore.startingPoint(): Flow<StartingPoint> = combine(preset, customised, ::StartingPoint)

/**
 * Settings › Starting point: shows the person's [Preset] and switches it, with or without forgetting their own values,
 * after a confirmation that lists what each choice moves ([asking]).
 */
class StartingPointViewModel(
    private val presets: PresetStore,
    spoolConsented: Flow<Boolean>,
) : ViewModel() {
    val current: StateFlow<StartingPoint> =
        presets.startingPoint().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartingPoint())

    /** Whether the relay disclosure was already accepted; until it was, switching to a relays-on preset shows it. */
    val spoolConsented: StateFlow<Boolean> =
        spoolConsented.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _asking = MutableStateFlow<SwitchPreview?>(null)

    /** The switch being confirmed, with what each of its buttons would move; null while no dialog is open. */
    val asking: StateFlow<SwitchPreview?> = _asking.asStateFlow()

    fun ask(target: Preset) {
        viewModelScope.launch {
            val customised = presets.customised.first()
            _asking.value =
                SwitchPreview(
                    target = target,
                    reset = presets.changes(target, resetOverrides = true),
                    keep = if (customised) presets.changes(target, resetOverrides = false) else null,
                )
        }
    }

    fun dismiss() {
        _asking.value = null
    }

    fun confirm(resetOverrides: Boolean) {
        val target = _asking.value?.target ?: return
        _asking.value = null
        viewModelScope.launch { presets.apply(target, resetOverrides) }
    }
}

/**
 * A switch to [target] awaiting confirmation: what Switch ([reset]) and, where the person has their own values, Keep my
 * changes ([keep], else null) would move, each computed by `PresetStore.changes` from the switches as they read now.
 */
data class SwitchPreview(
    val target: Preset,
    val reset: List<PresetChange>,
    val keep: List<PresetChange>?,
)
