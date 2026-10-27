package app.getknit.knit.mesh.inet

import app.getknit.knit.mesh.link.LinkSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The byte stream a direct link carries (ADR 2026-10.a5yj): the seam between a [LinkSession] and the [LinkSocket]
 * `FramedLink` runs over, so the reliability layer under it can be our [LinkArq] or another (a QUIC stream) without
 * touching either side. [open] is called before the session starts, so nothing the peer sends once the path is up is
 * missed; the socket works once the session is up and ends when it closes, and closing the socket closes the session.
 */
fun interface LinkStream {
    fun open(session: LinkSession): LinkSocket

    companion object {
        /** Our own ARQ ([LinkArq]) over the session's DATA datagrams, its round trip seeded from the punch's. */
        fun arq(
            scope: CoroutineScope,
            clock: () -> Long,
        ) = LinkStream { session ->
            val arq = LinkArq(send = session::sendData, scope = scope, clock = clock, onClose = { session.close() })
            session.attach(arq::take)
            scope.launch {
                (session.awaitOutcome() as? LinkSession.Outcome.Up)?.let { arq.seedRtt(it.rttMs) }
                session.state.first { it == LinkSession.State.CLOSED }
                arq.end()
            }
            arq
        }
    }
}
