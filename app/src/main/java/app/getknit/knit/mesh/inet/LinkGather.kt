package app.getknit.knit.mesh.inet

import app.getknit.knit.call.StunBinding
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.SecureRandom

/**
 * This phone's candidates for one link attempt (ADR 2026-10.a5yj): the port's own addresses ([LinkPort.candidates]),
 * then its IPv4 reflexive address, learnt by a STUN Binding sent **from that same port** to the spools' STUN servers
 * (`ScopeSync.stunUrls`, already narrowed by `call/StunServers`). The mapping a server sees is the one the peer's
 * probes then meet, which a query from any other socket would not tell. One request to each of at most
 * [MAX_SERVERS] servers, the first answer taken, nothing waited past [WAIT_MS]: an attempt without a reflexive address
 * still has its IPv6 and LAN ones.
 */
object LinkGather {
    const val MAX_SERVERS = 2
    const val WAIT_MS = 1_500

    /** [port]'s candidates, the reflexive one last when a server answered within [WAIT_MS] by [clock]. */
    suspend fun candidates(
        port: LinkPort,
        stunUrls: List<String>,
        clock: () -> Long,
        random: java.util.Random = SecureRandom(),
    ): List<LinkCandidate> {
        val reflexive = reflexive(port, stunUrls, clock, random)
        return port.candidates + listOfNotNull(reflexive)
    }

    @Suppress("ReturnCount") // nothing to ask, nobody resolved, or the wait ran out
    private suspend fun reflexive(
        port: LinkPort,
        stunUrls: List<String>,
        clock: () -> Long,
        random: java.util.Random,
    ): LinkCandidate? {
        val servers =
            stunUrls
                .asSequence()
                .mapNotNull(StunUrl::parse)
                .mapNotNull { (host, p) -> port.resolveV4(host).firstOrNull()?.let { it to p } }
                .take(MAX_SERVERS)
                .toList()
        if (servers.isEmpty()) return null
        val transaction = ByteArray(StunBinding.TRANSACTION_BYTES).also(random::nextBytes)
        val request = StunBinding.request(transaction)
        servers.forEach { (address, p) -> port.send(address, p, request) }
        val until = clock() + WAIT_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            val wait = (until - clock()).toInt()
            if (wait <= 0) return null
            val datagram = port.receive(wait) ?: return null
            answer(datagram, servers, transaction)?.let { return it }
        }
    }

    // The reflexive candidate [datagram] names, when it is one of [servers]' answers to [transaction].
    private fun answer(
        datagram: Datagram,
        servers: List<Pair<ByteArray, Int>>,
        transaction: ByteArray,
    ): LinkCandidate? {
        val fromServer = servers.any { (address, p) -> address.contentEquals(datagram.address) && p == datagram.port }
        val mapped = StunMapped.mapped(datagram.data, datagram.data.size, transaction)?.takeIf { fromServer } ?: return null
        if (mapped.address.size != LinkCandidate.V4_BYTES) return null
        return LinkCandidate(mapped.address, mapped.port, LinkCandidate.Kind.V4_REFLEXIVE).takeIf(PunchPolicy::sane)
    }
}
