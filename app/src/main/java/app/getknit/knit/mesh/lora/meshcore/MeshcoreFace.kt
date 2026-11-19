package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.BoardSetupState
import app.getknit.knit.mesh.lora.BoardState
import app.getknit.knit.mesh.lora.MeshcoreBoardFace
import kotlin.math.roundToInt

private const val MV_PER_VOLT = 1_000f

/**
 * The radio screen's facts for the MeshCore board bound at [bound] (ADR 2026-11.gsqu's Stage 7 amendment; lora L8, L9, security
 * N4), from the board [state], its [battery] reading, the session's setup [view] and the last position flag read for an address
 * ([lastShares]). The Ready board's firmware (cleaned), radio, default scope and battery — in millivolts, the only reading
 * MeshCore gives — only while it is Ready on the bound address; whether it airs a position as last read there, kept while it
 * is away; and the setup state off this phone's record for the bound address, away too, [BoardSetupState.UNKNOWN] for anything
 * else. Counts and booleans, and the Ready board's own advert name, its owner's handle (ADR 2026-11.f4d3), with its default
 * and whether it may lead a post: never a key, a digest or a record's field. Pure; `MeshcoreHooks.statusOf` calls it under
 * the core's status lock.
 */
internal fun meshcoreFaceOf(
    bound: String?,
    state: BoardState,
    battery: BoardBattery?,
    view: MeshcoreSetupView?,
    lastShares: Pair<String, Boolean>?,
): MeshcoreBoardFace {
    val ready = state as? BoardState.Ready
    val detail = (ready?.detail as? MeshcoreDetail)?.takeIf { bound != null && (it.dialled == null || it.dialled == bound) }
    val radio = ready?.radio?.takeIf { detail != null }
    return MeshcoreBoardFace(
        firmware = detail?.version?.let(MeshcoreHooks::cleanVersion),
        freqKhz = detail?.freqKhz,
        spreadFactor = radio?.spreadFactor,
        bandwidthHz = radio?.bandwidthHz,
        codingRate = radio?.codingRate,
        defaultScope = detail?.defaultScope,
        batteryMv = battery?.voltage?.takeIf { detail != null }?.let { (it * MV_PER_VOLT).roundToInt() },
        sharesPosition = lastShares?.takeIf { bound != null && it.first == bound }?.second,
        setup = view?.takeIf { bound != null && it.address == bound }?.state ?: BoardSetupState.UNKNOWN,
        handle = detail?.handle,
        namedDefault = detail?.namedDefault == true,
        handleUnpostable = detail != null && !detail.namePostable,
        defaultName = detail?.let { MeshcoreNames.forKey(it.publicKey) },
    )
}
