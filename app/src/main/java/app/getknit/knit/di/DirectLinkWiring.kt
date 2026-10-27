package app.getknit.knit.di

import android.util.Log
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.fieldlog.FieldLog
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshPause
import app.getknit.knit.mesh.inet.DirectLinks
import app.getknit.knit.mesh.inet.LinkDatagrams
import app.getknit.knit.mesh.inet.LinkPeers
import app.getknit.knit.mesh.protocol.LinkSignal
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.net.AndroidInternetGate
import app.getknit.knit.net.AndroidLinkDatagrams
import app.getknit.knit.net.InternetGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
private fun directLinkGraph(): Module =
    module {
        single<LinkDatagrams> { AndroidLinkDatagrams(get<AndroidInternetGate>()) }
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
