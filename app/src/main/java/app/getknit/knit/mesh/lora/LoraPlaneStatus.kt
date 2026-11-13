package app.getknit.knit.mesh.lora

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The LoRa transport's read-only face for the UI — the settings screen and the connection header's
 * [LoraStatusRepository]: its live [status] plus the one action the settings screen drives. A seam rather
 * than the concrete [LoraMeshTransport] for two reasons: a build with the plane dark binds [Dark] and so
 * never instantiates the GATT/session singletons (`MeshModule` promises release never does, and the
 * chat-header repository is resolved by every open chat), and a ViewModel test hands in a
 * `MutableStateFlow<LoraStatus>` instead of a transport over a fake air.
 */
internal interface LoraPlaneStatus : BoardKindSource {
    /** A snapshot of the plane: link state, bound board, last signal reading, peers heard. */
    val status: StateFlow<LoraStatus>

    /**
     * Which board kind this plane drives (ADR 2026-11.gsqu, Stage 2). Meshtastic unless the plane says otherwise: the
     * [Dark] stand-in and the demo plane are Meshtastic's.
     */
    override val boardKind: BoardKind get() = BoardKind.MESHTASTIC

    /**
     * A compact DM's budget on this plane's kind: its `BoardTraits.directBudget`, a constant, so the composer's DM length
     * hint ([LoraSizeHint.dmBodyBytes]) and `MeshManager`'s inline acks follow the bound board without the UI ever
     * reaching the transport or building a board session. [LoraDirectBudget.MESHTASTIC] unless the plane says otherwise.
     */
    val directBudget: LoraDirectBudget get() = LoraDirectBudget.MESHTASTIC

    /**
     * Sets the connected board up for Knit (`LoraMeshTransport.provisionKnitChannel`), or puts it back the
     * way it was ([ProvisionMode.Restore]) — ADR 045. [previous] carries the intervals a setup recorded, so a
     * restore can put the user's own values back rather than the firmware's defaults.
     */
    suspend fun provisionKnitChannel(
        mode: ProvisionMode = ProvisionMode.Setup,
        previous: BoardSettings? = null,
    ): ProvisionResult

    /** The plane in a build that does not ship it: idle forever, and never provisions. */
    object Dark : LoraPlaneStatus {
        override val status: StateFlow<LoraStatus> = MutableStateFlow(LoraStatus())

        override suspend fun provisionKnitChannel(
            mode: ProvisionMode,
            previous: BoardSettings?,
        ): ProvisionResult = ProvisionResult.NotReady(LinkState.Idle)
    }
}
