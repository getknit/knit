package app.getknit.knit.di

import app.getknit.knit.BuildConfig
import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.ui.devview.EventConsole
import app.getknit.knit.ui.devview.EventConsoleViewModel
import app.getknit.knit.ui.devview.MeshHudViewModel
import app.getknit.knit.ui.devview.MessageInspectViewModel
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The developer view (ADR 2026-10.s7wg): the message inspector at the foot of Message details and the mesh HUD over
 * every screen. The one read of `BuildConfig.DEV_VIEW` — on in debug, off in release, `-PdevView=` overrides. Off,
 * [devViewModule] is null, the Diagnostics switch row is not offered and R8 drops every class only this graph reaches.
 * On, both surfaces still wait for the switch (`SettingsStore.debugDevView`), which is off by default.
 */
const val DEV_VIEW_WIRED: Boolean = BuildConfig.DEV_VIEW

/** The developer view's graph, or null in a build without it ([DEV_VIEW_WIRED]). */
fun devViewModule(): Module? = if (DEV_VIEW_WIRED) devViewGraph() else null

private fun devViewGraph(): Module =
    module {
        // Keyed by the long-pressed message's id, like MessageDetailsViewModel beside it.
        viewModel { params ->
            MessageInspectViewModel(params.get(), get(), get(), get<ForwardDao>(), get(), get(), get(), get(), get(), get<Identity>())
        }
        viewModel { MeshHudViewModel(get(), get(), get<ForwardDao>(), get(), get()) }
        // The event console (the ADR's slice 2): the field log's tap, following the view's switch for the process.
        single { EventConsole().also { it.follow(get<SettingsStore>().debugDevView, get<CoroutineScope>()) } }
        viewModel { EventConsoleViewModel(get(), get()) }
    }
