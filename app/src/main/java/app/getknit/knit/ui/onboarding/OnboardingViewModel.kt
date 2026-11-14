package app.getknit.knit.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.TextLimits
import app.getknit.knit.clampToUtf16
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.moderation.NameScreen
import app.getknit.knit.moderation.ShownNames
import app.getknit.knit.normalizeSingleLine
import app.getknit.knit.ui.whatsnew.WhatsNew
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The three pages of the first run, in order. */
enum class OnboardingStep { WELCOME, NAME, PERMISSIONS }

/**
 * Owns what the onboarding pager needs to remember across rotation and between pages: which step is showing,
 * the name being typed, and the alias that stands in for it. The permission probes and launchers are not
 * here — they need an Activity, and live with the screen (`rememberOnboardingPermissions`).
 *
 * The name is held locally and never bound to the DataStore flow (the write→emit round-trip lags a keystroke
 * and resets the field — the `ProfileViewModel` rule). It is persisted once, on leaving the name page, and the
 * same moment records [SettingsStore.onboardingSeen], so a phone that comes back to onboarding with a grant
 * revoked opens on the permissions page rather than being asked its name again. Written there rather than on
 * Start because Start pops this route with `popUpTo(inclusive = true)`, which clears this ViewModel — a write
 * launched on that tap could be cancelled mid-flight. The write itself is [NonCancellable] for the same
 * reason: a fast Continue → Start could still clear the scope before DataStore has run the edit.
 */
class OnboardingViewModel(
    private val settings: SettingsStore,
    identity: Identity,
    // Screens the name before it is saved (ADR 2026-11.y4pc); null in tests that don't exercise it.
    private val names: ShownNames? = null,
) : ViewModel() {
    private val _nameRefused = MutableStateFlow(false)

    /** True after Continue refused the typed name, until the next keystroke: the page says why it stayed. */
    val nameRefused: StateFlow<Boolean> = _nameRefused.asStateFlow()

    // Null until the store has said whether this phone has seen the intro — the pager is built on a fact,
    // not a guess, so a returning phone never flashes "Welcome" before landing on the permissions page.
    private val _step = MutableStateFlow<OnboardingStep?>(null)
    val step: StateFlow<OnboardingStep?> = _step.asStateFlow()

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _alias = MutableStateFlow("")

    /** The auto-alias peers see until a name is set — the field's placeholder and its supporting line. */
    val alias: StateFlow<String> = _alias.asStateFlow()

    private val _nodeId = MutableStateFlow("")

    /** This phone's node id — what keys the preview avatar's tint, so it matches what peers will draw. */
    val nodeId: StateFlow<String> = _nodeId.asStateFlow()

    // What was last written this session, so leaving the name page a second time (Back, then Continue
    // again) only writes when something changed — and clearing a name written a page ago writes the clear.
    private var savedName = ""

    init {
        viewModelScope.launch {
            val id = identity.nodeId()
            _nodeId.value = id
            _alias.value = Alias.aliasFor(id)
            _step.value = if (settings.onboardingSeen.first()) OnboardingStep.PERMISSIONS else OnboardingStep.WELCOME
        }
    }

    fun setName(value: String) {
        // Hold exactly what's typed (capped) so a space *between* words isn't eaten mid-keystroke;
        // normalization happens on commit and on leaving the page.
        _name.value = value.clampToUtf16(TextLimits.DISPLAY_NAME)
        _nameRefused.value = false
    }

    /** Snaps the visible field to its normalized form when it loses focus; nothing is persisted here. */
    fun commitName() {
        _name.value = normalizeSingleLine(_name.value)
    }

    /** Welcome → Name → Permissions. Leaving the name page is what persists the name (blank included). */
    fun next() {
        when (_step.value) {
            OnboardingStep.WELCOME -> {
                _step.value = OnboardingStep.NAME
            }

            OnboardingStep.NAME -> {
                // Screened before it is stored, as in Profile: everyone in range will see it.
                if (names?.flagged(normalizeSingleLine(_name.value), NameScreen.Audience.PUBLIC) == true) {
                    _nameRefused.value = true
                    return
                }
                persistName()
                _step.value = OnboardingStep.PERMISSIONS
            }

            // The permissions page's CTA is Start, owned by the caller; there is no page after it.
            OnboardingStep.PERMISSIONS, null -> {}
        }
    }

    fun back() {
        val current = _step.value ?: return
        _step.value = OnboardingStep.entries.getOrElse(current.ordinal - 1) { OnboardingStep.WELCOME }
    }

    private fun persistName() {
        val normalized = normalizeSingleLine(_name.value).clampToUtf16(TextLimits.DISPLAY_NAME)
        _name.value = normalized
        val changed = normalized != savedName
        savedName = normalized
        viewModelScope.launch {
            withContext(NonCancellable) {
                // The mesh isn't running during onboarding, so nothing re-broadcasts here: the first session's
                // own-profile frame is built from whatever is stored, which is exactly this.
                if (changed) settings.setDisplayName(normalized)
                settings.markOnboardingSeen()
                // A new install has nothing to compare against, so it starts caught up on What's New.
                WhatsNew.latest?.let { settings.markWhatsNewSeen(it.id) }
            }
        }
    }
}
