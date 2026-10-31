package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.inet.FakeLinkNet
import app.getknit.knit.mesh.inet.LinkArq
import app.getknit.knit.mesh.inet.LinkDatagrams
import app.getknit.knit.mesh.inet.LinkStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.Collections

/**
 * The lab's Internet for direct links (ADR 2026-10.a5yj): [FakeLinkNet]'s NATs and one STUN server, a public address of
 * its own per node, and every link stream the nodes' `DirectLinks` open. The streams run the real ARQ on a scope of the
 * lab's own, on a monotonic clock — a calendar jump ([LabClock.advance]) never moves an ARQ timer, and a node's shutdown,
 * which cancels its session, never strands a reader parked in one ([close] ends them all, as `MeshLab.directLink`'s are).
 * Everything else of a link runs on the lab clock, as `DirectLinks` and `LinkSession` do: a jump past
 * `LinkSession.IDLE_MS` while a link is up idles it out; one of `LinkSession.SILENCE_MS` or more closes it SILENT only
 * while DATA it sent is unanswered, and one of `LinkSession.HOLD_SILENCE_MS` whatever it sent; one of
 * `LinkSession.HOLD_KEEPALIVE_MS` or more sends a keepalive; and any jump while an attempt punches ends it NO_PATH — jump
 * only before an offer, once a link is meant to go, or to end a punch meant to fail without sitting out its ten seconds:
 * once the offerer's first probe is out ([Phone.sentOut] moved), both ends are punching on clocks a jump of
 * `PunchPolicy.GIVE_UP_MS` ends at their next probe round.
 */
class LabInternet {
    val net = FakeLinkNet().apply { stunServer(STUN_HOST, byteArrayOf(198.toByte(), 51, 100, 7)) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = Collections.synchronizedList(ArrayList<LinkArq>())
    private var nextAddress = 1

    /**
     * One node's place on the Internet: its datagrams behind its own NAT ([network]), how its links' streams are made, and
     * how many datagrams it has sent from the network it is on now to anything but the STUN server
     * ([FakeLinkNet.sentOut]).
     */
    inner class Phone internal constructor(
        val network: FakeLinkNet.Phone,
        val streams: LinkStream,
    ) {
        val datagrams: LinkDatagrams get() = network

        fun sentOut(): Int = net.sentOut(network.external)

        /** Moves to another network, behind a NAT of [nat] at a public address no other node shares ([FakeLinkNet.Phone.move]). */
        fun move(nat: FakeLinkNet.Nat) = network.move(nat, address())
    }

    /**
     * One home network several nodes can join ([phone]'s [lan]): one NAT at one public address, and one LAN behind it on
     * which [FakeLinkNet] routes the nodes' host candidates and hairpins their reflexive ones.
     */
    class Lan internal constructor(
        internal val external: ByteArray,
    )

    /** A home network of its own: a public address no node holds yet. */
    fun lan(): Lan = Lan(address())

    /**
     * Whether every [Lan]'s access point isolates its clients ([FakeLinkNet.lanDown]): their host candidates answer
     * nothing, while their NATs still hairpin.
     */
    var lanDown: Boolean
        get() = net.lanDown
        set(value) {
            net.lanDown = value
        }

    /** A phone behind a NAT of [nat]: on [lan], with the nodes already there, or at a public address no other node shares. */
    @Synchronized
    fun phone(
        nat: FakeLinkNet.Nat,
        lan: Lan? = null,
    ): Phone {
        val streams =
            LinkStream { session ->
                LinkStream.arq(scope) { System.nanoTime() / NANOS_PER_MS }.open(session).also { opened += it as LinkArq }
            }
        return Phone(net.phone(nat, lan?.external ?: address()), streams)
    }

    @Synchronized
    private fun address(): ByteArray = byteArrayOf(81, 0, (nextAddress shr 8).toByte(), nextAddress.toByte()).also { nextAddress++ }

    /** Ends every stream a link opened, so no `FramedLink` reader stays parked, and stops their timers. */
    fun close() {
        synchronized(opened) { opened.forEach { it.end() } }
        scope.cancel()
    }

    companion object {
        private const val STUN_HOST = "stun.lab.example"

        /** The STUN server a lab spool advertises in HELLO (`FakeSpool(stun = …)`), which [net] answers. */
        const val STUN_URL = "stun:$STUN_HOST"

        private const val NANOS_PER_MS = 1_000_000L
    }
}
