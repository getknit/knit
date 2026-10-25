package app.getknit.knit.ui.devview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshPause
import app.getknit.knit.mesh.MeshStartGate
import app.getknit.knit.mesh.indirectPeers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Backs the mesh HUD (ADR 2026-10.s7wg): one [HudReading] a tick, read only while the pill is composed and started.
 * Every read is of state the mesh already keeps; nothing is written. The custody count is a SQLCipher query, so it is
 * read on every [custodyEvery]th tick, not every tick. A new subscription starts a new [RateWindow], so a HUD that was
 * off screen never reads the gap as a minute.
 */
class MeshHudViewModel(
    private val mesh: MeshController,
    private val metrics: MeshMetrics,
    private val forward: ForwardDao,
    private val settings: SettingsStore,
    private val startGate: MeshStartGate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = TICK_MS,
    private val custodyEvery: Int = CUSTODY_EVERY,
) : ViewModel() {
    val reading: StateFlow<HudReading?> =
        flow {
            val window = RateWindow()
            var custody: Int? = null
            var tick = 0
            while (true) {
                val now = clock()
                if (tick % custodyEvery == 0) custody = forward.count(now)
                emit(read(now, window, custody))
                tick++
                delay(tickMs)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private suspend fun read(
        now: Long,
        window: RateWindow,
        custody: Int?,
    ): HudReading {
        val posture =
            when {
                !settings.meshEnabled.first() -> MeshPosture.Stopped
                MeshPause.activeDeadline(settings.meshPausedUntil.first(), now) != null -> MeshPosture.Paused
                startGate.deferred.value -> MeshPosture.StartDeferred
                else -> MeshPosture.Running
            }
        val spools = mesh.spoolStatus()
        return HudReading(
            posture = posture,
            statuses = mesh.transportStatuses.value,
            neighbors = mesh.neighbors.value.size,
            indirect = indirectPeers(mesh.heardIndirectly.value, now).size,
            spoolsConnected = spools.count { it.connected },
            spools = spools.size,
            custody = custody,
            rates = window.add(now, FrameCounts.of(metrics.frames())),
        )
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val CUSTODY_EVERY = 5
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
