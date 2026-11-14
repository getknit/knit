package app.getknit.knit.di

import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.settings.LoraPlaneStateStore
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshcoreBoardHolder
import app.getknit.knit.mesh.PublicChannelSink
import app.getknit.knit.mesh.crypto.LoraDirectSeal
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.BoardPlane
import app.getknit.knit.mesh.lora.DirectDm
import app.getknit.knit.mesh.lora.LoraAuthorCheck
import app.getknit.knit.mesh.lora.LoraBoardHolder
import app.getknit.knit.mesh.lora.LoraBoardMap
import app.getknit.knit.mesh.lora.LoraConfig
import app.getknit.knit.mesh.lora.LoraDirectInbox
import app.getknit.knit.mesh.lora.LoraDirectSink
import app.getknit.knit.mesh.lora.LoraDirectSource
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.LoraPlaneStatus
import app.getknit.knit.mesh.lora.meshcore.MeshcoreContacts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope

/**
 * The Koin qualifier of [kind]'s plane instance (ADR 2026-11.gsqu, Stage 2): its link, its transport, its compact-DM sink,
 * its Meshtastic-room sink and its UI face ([app.getknit.knit.mesh.lora.LoraPlaneStatus]) are each defined once per
 * kind under it. What every kind shares is unqualified: the pair seal, the `LoraDirectRouter`, the `LoraStatusRepository`
 * aggregate, the field log.
 */
internal fun loraPlane(kind: BoardKind): Qualifier = named(kind)

/**
 * The board kinds this build constructs a plane for, in the composite's order: one `CompositeMeshTransport` child, one
 * field-log stream and one status each ([builtBoardKinds] over this build's flags).
 */
internal val BUILT_BOARD_KINDS: List<BoardKind> = builtBoardKinds(BuildConfig.LORA_PLANE, BuildConfig.MESHCORE_PLANE)

/**
 * The board kinds a build with these flags constructs a plane for: Meshtastic's where the build ships the LoRa plane
 * ([loraPlane]), and MeshCore's beside it where `BuildConfig.MESHCORE_PLANE` is on (`-PmeshcorePlane`: on in debug, off in
 * release until the board trials pass, ADR 2026-11.gsqu Stage 5a-2). So a release build builds no MeshCore child: no
 * session, no GATT client, no composite child, no field-log stream, no status. A build that turned the MeshCore flag on
 * without the LoRa plane is refused.
 */
internal fun builtBoardKinds(
    loraPlane: Boolean,
    meshcorePlane: Boolean,
): List<BoardKind> =
    buildList {
        check(!meshcorePlane || loraPlane) { "MESHCORE_PLANE requires LORA_PLANE" }
        if (loraPlane) add(BoardKind.MESHTASTIC)
        if (meshcorePlane) add(BoardKind.MESHCORE)
    }

/**
 * The address a board's reported identity is written under (ADR 2026-11.gsqu's Stage 5a-2 amendment, the `dialled` race):
 * the address the reporting session itself dialled, never the plane's configured one when the callback runs — on an A→B
 * rebind A's late Ready would otherwise write A's identity under B's binding. The settings writer then lands it only while
 * that address is still the bound one. Null with no board configured: nothing is written. A session that did not say
 * ([dialled] null, a fake's) falls back to [configured], as before.
 */
internal fun bindingAddress(
    dialled: String?,
    configured: String?,
): String? = configured?.let { dialled ?: it }

/**
 * Whether a room post's signature verifies against its author's **pinned** key, which hashes to their node id
 * ([LoraAuthorCheck], ADR 2026-11.gsqu's Stage 5 decisions): the same check `InboundPipeline.verifyInbound` makes for a
 * pinned sender, never the in-band key a profile carries. A stranger, a key that does not hash to the id, or a bad
 * signature is false.
 */
internal fun pinnedAuthorCheck(peers: () -> PeerRepository): LoraAuthorCheck =
    LoraAuthorCheck { wire, env ->
        val bundle =
            peers().find(env.senderId)?.pubKey?.let { runCatching { PublicKeyBundle.decode(it) }.getOrNull() }
                ?: return@LoraAuthorCheck false
        NodeId.fromPublicKeyBundle(bundle.encoded) == env.senderId && MessageCrypto.verify(bundle, wire.sig, wire.signed)
    }

/**
 * One board kind's plane, as one call (ADR 2026-11.gsqu, Stage 5a-2): the transport, its compact-DM sink, its public-channel
 * sink and its UI face, each under [kind]'s qualifier. What differs per kind is handed in: its [plane] (traits, link and
 * hooks, over the transport's own log), its [config], its [boardMap], and a [face] that stands in for the transport (the
 * demo's). Everything else — the pair seal, the inbox and the unacked source in `MeshManager`, the radio-reached edge, the
 * plane state under the kind's key, the clocks, the process start, the author check — is every kind's.
 */
@Suppress("LongParameterList") // one parameter per thing a kind's plane differs by
internal fun Module.loraPlaneOf(
    kind: BoardKind,
    logTag: String,
    plane: Scope.(log: (String) -> Unit) -> BoardPlane,
    config: (SettingsStore) -> Flow<LoraConfig?>,
    boardMap: Scope.() -> LoraBoardMap?,
    face: Scope.() -> LoraPlaneStatus? = { null },
) {
    single(loraPlane(kind)) {
        val settings = get<SettingsStore>()
        val log: (String) -> Unit = { line -> Log.d(logTag, line) }
        LoraMeshTransport(
            selfId = { get<Identity>().nodeId() },
            plane = plane(log),
            config = config(settings),
            // The compact-DM seams (ADR 2026-10.x9fa). Everything MeshManager owns is reached late, at call time, since the
            // manager is built over the composite this transport is a child of.
            crypto = get<LoraDirectSeal>(),
            inbox = LoraDirectInbox { frame -> get<MeshManager>().deliverLoraDirect(frame) },
            source =
                object : LoraDirectSource {
                    override suspend fun unackedTo(
                        peerId: String,
                        limit: Int,
                        untakenOnly: Boolean,
                    ): List<DirectDm> = get<MeshManager>().loraDirectSource().unackedTo(peerId, limit, untakenOnly)

                    // The plane's own budget, passed through (ADR 2026-11.gsqu, Stage 1a.4).
                    override suspend fun unackedTo(
                        peerId: String,
                        limit: Int,
                        untakenOnly: Boolean,
                        maxBytes: Int,
                    ): List<DirectDm> = get<MeshManager>().loraDirectSource().unackedTo(peerId, limit, untakenOnly, maxBytes)

                    override suspend fun isUnacked(frameId: String): Boolean = get<MeshManager>().loraDirectSource().isUnacked(frameId)
                },
            // Collected at start(), and only while a board is configured, when the manager exists.
            boardMap = boardMap(),
            // The destination's board acked one of our compact DMs: the chat's "reached their LoRa radio" (ADR 040's
            // 2026-10-31 amendment). Runs on the plane's outcome collector; the manager's side is one lock-free update.
            onRadioReached = { frameId, late -> get<MeshManager>().onLoraRadioReached(frameId, late) },
            scope = get(),
            metrics = get(),
            // The re-probe's backoff, the auto-reply's memory (Meshtastic) and the duty law's ring (MeshCore), kept across
            // process death under the kind's own key. The airtime ledger is not kept (ADR 2026-10.ug4a).
            state = LoraPlaneStateStore(settings, kind),
            clock = SystemClock::elapsedRealtime,
            wallClock = System::currentTimeMillis,
            // This process's start on the wall clock, not this single's: an own room post written before it is a re-serve and
            // never re-airs (ADR 2026-10.ug4a), while one typed before the plane was first resolved still goes.
            processStartWall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()),
            log = log,
            // A hearing on a kind whose packets name no sender it vouches for (MeshCore); Meshtastic never asks it.
            authorCheck = pinnedAuthorCheck { get<PeerRepository>() },
        )
    }
    // Where MeshManager offers the compact copy of a DM it sealed (ADR 2026-10.x9fa), resolved late by the router, since the
    // transport and the manager construct each other.
    single<LoraDirectSink>(loraPlane(kind)) { get<LoraMeshTransport>(loraPlane(kind)) }
    // The kind's public-channel half, reached from MeshManager through a lambda for the same reason.
    single<PublicChannelSink>(loraPlane(kind)) { get<LoraMeshTransport>(loraPlane(kind)) }
    // The plane's UI face: the stand-in where there is one, the transport where the build ships the plane, a dark stand-in
    // otherwise — so the chat header's repository never instantiates a board session in a build without it.
    single<LoraPlaneStatus>(loraPlane(kind)) {
        face() ?: if (BuildConfig.LORA_PLANE) get<LoraMeshTransport>(loraPlane(kind)) else LoraPlaneStatus.Dark
    }
}

/**
 * The MeshCore plane's board map, in the core's form ([MeshcoreContacts.holdersOf]), from [source]
 * (`MeshManager.meshcoreBoardMap`). Adds no start value: the first map the plane sees is [source]'s first, the stores' real
 * state — an empty placeholder would be a taught set of nothing, which the Ready reconcile turns into removals (ADR
 * 2026-11.gsqu's Stage 5b-2 amendment).
 */
internal fun meshcoreBoardHolders(source: Flow<Map<String, MeshcoreBoardHolder>>): Flow<Map<String, LoraBoardHolder>> =
    source.map(MeshcoreContacts::holdersOf)

/** Whether the MeshCore DM door is bound ([MeshManager.bindMeshcoreDirect]): only where the MeshCore child is built. */
internal fun bindsMeshcoreDirect(kinds: List<BoardKind>): Boolean = BoardKind.MESHCORE in kinds
