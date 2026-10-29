package app.getknit.knit.di

import android.os.SystemClock
import android.util.Log
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.GroupRepository
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
import app.getknit.knit.mesh.inet.LinkDemand
import app.getknit.knit.mesh.inet.LinkPeers
import app.getknit.knit.mesh.inet.LinkSession
import app.getknit.knit.mesh.inet.MeshLinkPending
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * Direct Internet links between contacts (ADR 2026-10.a5yj): an on-demand UDP link to one contact whose offer and
 * answer ride a spool's sigs. The one read of `BuildConfig.DIRECT_LINKS` — on in debug, off in release,
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
    sentAt: Long,
) {
    if (DIRECT_LINKS_WIRED) {
        runCatching { getKoin().getOrNull<DirectLinks>()?.onSignal(sender, signal, sentAt) }
            .onFailure { Log.w(TAG, "link signal dropped: ${it.javaClass.simpleName}") }
    }
}

/**
 * The composite's child for the links (`MeshModule`'s last): [DirectLinkTransport] behind a switch that holds it off
 * while this phone's links are off or low-power mode is on. Only called where [DIRECT_LINKS_WIRED].
 */
fun Scope.directLinkPlane(): MeshTransport {
    val off = combine(get<SettingsStore>().directLinksEnabled, get<LowPowerSource>().posture) { on, posture -> !on || posture.active }
    // Built with the plane, so the demand runs from the mesh's first start: nothing else would build it before a signal.
    get<LinkDemand>()
    return SwitchableTransport(get<DirectLinkTransport>(), off, get(), { msg -> Log.i(TAG, msg) })
}

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
                // A walkie's hold (C-3.11-8), resolved late like the rest: a link only for a peer the radios have no path to.
                onHold = { peer -> walkieHold(peer) },
                log = { Log.d(TAG, it) },
            )
        }
        directLinks()
        linkDemand()
    }

// The links themselves: on while the person's switch is, and neither low-power mode nor a pause holds them off.
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
private fun Module.directLinks() =
    single {
        val settings = get<SettingsStore>()
        val scope = get<CoroutineScope>()
        val identity = get<Identity>()
        val on = settings.directLinksEnabled.stateIn(scope, SharingStarted.Eagerly, false)
        val pausedUntil = settings.meshPausedUntil.stateIn(scope, SharingStarted.Eagerly, null)
        val lowPower = get<LowPowerSource>().posture.map { it.active }.stateIn(scope, SharingStarted.Eagerly, false)
        val links =
            DirectLinks(
                selfId = { identity.nodeId() },
                datagrams = get(),
                peers = MeshLinkPeers(this),
                // Low-power mode opens and answers none (ADR 2026-10.a5yj), as it holds the link plane off.
                enabled = {
                    on.value && !lowPower.value && MeshPause.activeDeadline(pausedUntil.value, System.currentTimeMillis()) == null
                },
                scope = scope,
                // The person's switch off: a contact's offer gets a DECLINE (L-2-6); low-power and a pause say nothing.
                declining = { !on.value },
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
        scope.launch { lowPower.collect { if (it) links.stop() } }
        val routeChanges = get<InternetGate>().routeChanges
        scope.launch {
            on.flatMapLatest { if (it) routeChanges else emptyFlow() }.collect { links.onRouteChanged() }
        }
        links
    }

// The spool first, a link as the last resort (L-7-7, L-7-9): offers a contact a link when something of ours to them has
// gone unticked, or a file either way has no other way across, while the link plane runs. The composite is resolved
// late, as everywhere here.
private fun Module.linkDemand() =
    single {
        val links = get<DirectLinks>()
        val transport = get<DirectLinkTransport>()
        val identity = get<Identity>()
        val groups = get<GroupRepository>()
        val pending =
            MeshLinkPending(
                get(),
                get(),
                groups::find,
                stuck = { peer, hash -> getKoin().get<MeshManager>().attachmentStuck(peer, hash) },
                wanted = { _, hash -> getKoin().get<MeshManager>().attachmentWanted(hash) },
            )
        LinkDemand(
            pending = { since, until -> pending.between(identity.nodeId(), since, until) },
            offer = { peer, why -> links.offer(peer, why = why) },
            up = { links.links.value.keys },
            lastUpAt = links::lastUpAt,
            radioLinked = { getKoin().get<MeshTransport>().radioLinked(it) },
            active = { transport.running },
            scope = get(),
            clock = System::currentTimeMillis,
            log = { Log.d(TAG, it) },
            // The sender's proof of a photo the link carried because no spool would take it (L-7-7, ADR 2026-10.vy27).
            prove = { peers -> getKoin().getOrNull<MeshManager>()?.proveStuckOverLinks(peers) },
            declinedAt = links::declinedAt,
            idleSince = links::idleSince,
            yieldLink = { peer -> links.close(peer, LinkSession.EndReason.YIELDED) },
        ).also { it.start() }
    }

// A walkie call's hold on its link to [peerId] (docs/VOICE_CALLS.md C-3.11-8, ADR 2026-10.a5yj's walkie amendment): held
// or raised only while the radios have no path to the peer (`MeshManager.talkRadioPath`), so a link never stands in for a
// radio, and a hold the radios make needless is not renewed and lapses.
private fun Scope.walkieHold(peerId: String): Boolean {
    val mesh = getKoin().getOrNull<MeshManager>() ?: return false
    if (mesh.talkRadioPath(peerId)) return false
    return getKoin().getOrNull<DirectLinks>()?.raise(peerId) ?: false
}

// The mesh's half of [LinkPeers], resolved late: the mesh graph builds MeshManager, whose inbound hook reaches the
// links, so neither may need the other to be built first.
private class MeshLinkPeers(
    private val koin: Scope,
) : LinkPeers {
    private val mesh: MeshManager get() = koin.get()

    override suspend fun mayOffer(peerId: String) = mesh.mayOfferLink(peerId)

    override fun canSignal(peerId: String) = mesh.canSignalLink(peerId)

    override fun queuedAhead(peerId: String) = mesh.linkSignalQueued(peerId)

    override fun stunUrls() = mesh.stunUrls()

    override suspend fun send(
        peerId: String,
        signal: LinkSignal,
    ) = mesh.sendLinkSignal(peerId, signal)
}

private const val TAG = "KnitLink"
