package app.getknit.knit.di

import app.getknit.knit.BuildConfig
import app.getknit.knit.mesh.lora.BoardKind
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named

/**
 * The Koin qualifier of [kind]'s plane instance (ADR 2026-11.gsqu, Stage 2): its link, its transport, its compact-DM sink,
 * its Meshtastic-room sink and its UI face ([app.getknit.knit.mesh.lora.LoraPlaneStatus]) are each defined once per
 * kind under it. What every kind shares is unqualified: the pair seal, the `LoraDirectRouter`, the `LoraStatusRepository`
 * aggregate, the field log.
 */
internal fun loraPlane(kind: BoardKind): Qualifier = named(kind)

/**
 * The board kinds this build constructs a plane for, in the composite's order: one `CompositeMeshTransport` child, one
 * field-log stream and one status each. Meshtastic's where the build ships the LoRa plane, and nothing else yet.
 *
 * `BuildConfig.MESHCORE_PLANE` (`-PmeshcorePlane`, on in debug and off in release until the board trials pass, and
 * never on without `LORA_PLANE`) is where Stage 5 adds [BoardKind.MESHCORE]. Until its plane exists the flag builds
 * nothing: the guard below is its only reader, and it only refuses a build that turned it on without the LoRa plane.
 */
internal val BUILT_BOARD_KINDS: List<BoardKind> =
    buildList {
        check(!BuildConfig.MESHCORE_PLANE || BuildConfig.LORA_PLANE) { "MESHCORE_PLANE requires LORA_PLANE" }
        if (BuildConfig.LORA_PLANE) add(BoardKind.MESHTASTIC)
    }
