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
 * only before an offer or once a link is meant to go.
 */
class LabInternet {
    val net = FakeLinkNet().apply { stunServer(STUN_HOST, byteArrayOf(198.toByte(), 51, 100, 7)) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = Collections.synchronizedList(ArrayList<LinkArq>())
    private var nextAddress = 1

    /** One node's place on the Internet: its datagrams behind its own NAT, and how its links' streams are made. */
    class Phone(
        val nat: FakeLinkNet.Nat,
        val datagrams: LinkDatagrams,
        val streams: LinkStream,
    )

    /** A phone behind a NAT of [nat] at a public address no other node shares. */
    @Synchronized
    fun phone(nat: FakeLinkNet.Nat): Phone {
        val external = byteArrayOf(81, 0, (nextAddress shr 8).toByte(), nextAddress.toByte()).also { nextAddress++ }
        val streams =
            LinkStream { session ->
                LinkStream.arq(scope) { System.nanoTime() / NANOS_PER_MS }.open(session).also { opened += it as LinkArq }
            }
        return Phone(nat, net.phone(nat, external), streams)
    }

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
