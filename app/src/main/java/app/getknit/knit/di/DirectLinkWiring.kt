package app.getknit.knit.di

import android.os.SystemClock
import android.util.Log
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.fieldlog.FieldLog
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshPause
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.SwitchableTransport
import app.getknit.knit.mesh.inet.DirectLinkTransport
import app.getknit.knit.mesh.inet.DirectLinks
import app.getknit.knit.mesh.inet.LinkDatagrams
import app.getknit.knit.mesh.inet.LinkPeers
import app.getknit.knit.mesh.inet.MeshLinkScope
import app.getknit.knit.mesh.power.LowPowerSource
import app.getknit.knit.mesh.protocol.LinkSignal
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.net.AndroidInternetGate
import app.getknit.knit.net.AndroidLinkDatagrams
import app.getknit.knit.net.InternetGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * Direct Internet links between contacts (ADR 2026-10.a5yj, Proposed): an on-demand UDP link to one contact whose
 * offer and answer are cast through a spool. The one read of `BuildConfig.DIRECT_LINKS` — on in debug, off in release,
 * `-PdirectLinks=` overrides. Off, no capability bit is advertised, no `link` signal is sent or answered and
 * [directLinkModule] is null, so nothing of the link is built.
 */
const val DIRECT_LINKS_WIRED: Boolean = BuildConfig.DIRECT_LINKS

/** The capability bit links add to `Protocol.LOCAL_CAPABILITIES` where they are wired; `MeshManager` OR-s it in. */
val DIRECT_LINK_CAPABILITIES: Long = if (DIRECT_LINKS_WIRED) Protocol.CAP_INET_LINK else 0L

/** The direct links' graph, or null in a build without them ([DIRECT_LINKS_WIRED]). */
fun directLinkModule(): Module? = if (DIRECT_LINKS_WIRED) directLinkGraph() else null

/**
 * Hands one opened signal to the links (`MeshManager`'s `onLinkSignal`): resolved late, like the call hooks, and never
 * allowed to throw into the inbound path (rules/mesh.md). Nothing in a build without links.
 */
fun Scope.onLinkSignal(
    sender: String,
    signal: LinkSignal,
) {
    if (DIRECT_LINKS_WIRED) {
        runCatching { getKoin().getOrNull<DirectLinks>()?.onSignal(sender, signal) }
            .onFailure { Log.w(TAG, "link signal dropped: ${it.javaClass.simpleName}") }
    }
}

/**
 * The composite's child for the links (`MeshModule`'s last): [DirectLinkTransport] behind a switch that holds it off
 * while this phone's links are off or low-power mode is on. Only called where [DIRECT_LINKS_WIRED].
 */
fun Scope.directLinkPlane(): MeshTransport {
    val off = combine(get<SettingsStore>().directLinksEnabled, get<LowPowerSource>().posture) { on, posture -> !on || posture.active }
    return SwitchableTransport(get<DirectLinkTransport>(), off, get(), { msg -> Log.i(TAG, msg) })
}

@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
private fun directLinkGraph(): Module =
    module {
        single<LinkDatagrams> { AndroidLinkDatagrams(get<AndroidInternetGate>()) }
        // The links' transport: each up link's stream, framed. Its use goes back to the links, resolved late.
        single {
            val identity = get<Identity>()
            DirectLinkTransport(
                selfId = { identity.nodeId() },
                linkScope =
                    MeshLinkScope(
                        groups = get(),
                        // The mesh resolved late, like [MeshLinkPeers]: its graph reaches the links through its hook.
                        addressees = { hash -> getKoin().get<MeshManager>().blobAddressees(hash) },
                        ownAvatar = { get<SettingsStore>().ownAvatarHash.first() },
                        custody = get(),
                        clock = System::currentTimeMillis,
                    ),
                scope = get(),
                cacheDir = androidContext().cacheDir,
                partials = get(),
                metrics = get(),
                now = SystemClock::elapsedRealtime,
                onUse = { peer -> getKoin().getOrNull<DirectLinks>()?.noteUse(peer) },
                // The mesh resolved late as above: a link adopted resets custody's push memo for its peer.
                onLinkUp = { peer -> getKoin().getOrNull<MeshManager>()?.onDirectLinkUp(peer) },
                log = { Log.d(TAG, it) },
            )
        }
        single {
            val settings = get<SettingsStore>()
            val scope = get<CoroutineScope>()
            val identity = get<Identity>()
            val on = settings.directLinksEnabled.stateIn(scope, SharingStarted.Eagerly, false)
            val pausedUntil = settings.meshPausedUntil.stateIn(scope, SharingStarted.Eagerly, null)
            val links =
                DirectLinks(
                    selfId = { identity.nodeId() },
                    datagrams = get(),
                    peers = MeshLinkPeers(this),
                    enabled = { on.value && MeshPause.activeDeadline(pausedUntil.value, System.currentTimeMillis()) == null },
                    scope = scope,
                    clock = System::currentTimeMillis,
                    // Each up link's stream goes to the transport, the offerer writing the first HELLO.
                    onStream = { peer, socket, offerer -> get<DirectLinkTransport>().adopt(peer, socket, offerer) },
                    linkBusy = { peer -> get<DirectLinkTransport>().busy(peer) },
                    fieldLog = FieldRecorder { event -> get<FieldLog>().record(event) },
                    log = { Log.d(TAG, it) },
                )
            // Switched off or paused: every link closes and every pending attempt ends. A new default network: every
            // backoff starts over — collected only while the switch is on, so an off phone registers no callback.
            scope.launch { on.collect { if (!it) links.stop() } }
            scope.launch {
                pausedUntil.collect { if (MeshPause.activeDeadline(it, System.currentTimeMillis()) != null) links.stop() }
            }
            val routeChanges = get<InternetGate>().routeChanges
            scope.launch {
                on.flatMapLatest { if (it) routeChanges else emptyFlow() }.collect { links.onRouteChanged() }
            }
            links
        }
    }

// The mesh's half of [LinkPeers], resolved late: the mesh graph builds MeshManager, whose inbound hook reaches the
// links, so neither may need the other to be built first.
private class MeshLinkPeers(
    private val koin: Scope,
) : LinkPeers {
    private val mesh: MeshManager get() = koin.get()

    override suspend fun mayOffer(peerId: String) = mesh.mayOfferLink(peerId)

    override fun canCast(
        peerId: String,
        peerTicketed: Boolean,
    ) = mesh.canCastTalk(peerId, peerTicketed)

    override fun castTicketed() = mesh.castTicketed()

    override fun stunUrls() = mesh.stunUrls()

    override suspend fun send(
        peerId: String,
        signal: LinkSignal,
        peerTicketed: Boolean,
    ) = mesh.sendLinkSignal(peerId, signal, peerTicketed)
}

private const val TAG = "KnitLink"
