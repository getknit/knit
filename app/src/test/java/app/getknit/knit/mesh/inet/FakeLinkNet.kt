package app.getknit.knit.mesh.inet

import app.getknit.knit.mesh.protocol.LinkSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.random.Random

/**
 * An in-process Internet for direct links (ADR 2026-10.a5yj): phones behind NATs of a chosen [Nat] kind, an IPv6
 * address behind a stateful firewall where asked, a LAN shared by phones behind one NAT, and STUN servers that answer
 * with the mapping they saw. Datagrams cross at once (or are lost, at [loss]), so a session runs on the test's virtual
 * time. Nothing here is Android: it implements the [LinkDatagrams] seam the real link uses.
 */
@OptIn(ExperimentalCoroutinesApi::class) // select's onTimeout
class FakeLinkNet(
    private val loss: Double = 0.0,
    private val random: Random = Random(1),
) {
    /** How a phone's IPv4 NAT maps and filters (RFC 4787's terms). */
    enum class Nat {
        /** A public address on the phone itself: nothing maps, nothing filters. */
        NONE,

        /** Endpoint-independent mapping, address-dependent filtering. */
        ADDRESS_RESTRICTED,

        /** Endpoint-independent mapping, address-and-port-dependent filtering. */
        PORT_RESTRICTED,

        /** A fresh mapping per destination, address-and-port-dependent filtering — most of cellular CGNAT. */
        SYMMETRIC,
    }

    private class Nat4(
        val kind: Nat,
        val external: ByteArray,
    ) {
        var nextPort = 40_000
        val mappings = HashMap<String, Int>() // key → external port
        val inbound = HashMap<Int, Pair<FakePort, String>>() // external port → (port, mapping key)
        val permitted = HashSet<String>() // "extPort|addr" or "extPort|addr|port"
    }

    private val nats = ArrayList<Nat4>()
    private val ports = ArrayList<FakePort>()
    private val stun = HashMap<String, ByteArray>() // name → address
    private var nextHost = 2

    /** A STUN server reachable as [name] at a public [address]. */
    fun stunServer(
        name: String,
        address: ByteArray,
    ) {
        stun[name] = address
    }

    /**
     * A phone behind a NAT of [nat] whose external address is [external] (phones that pass the same [external] share the
     * NAT and its LAN), with an IPv6 [v6] address behind a stateful firewall when given, on a network of [kind].
     */
    fun phone(
        nat: Nat,
        external: ByteArray,
        v6: ByteArray? = null,
        kind: Int = LinkSignal.NET_WIFI,
    ): LinkDatagrams {
        val gateway = nats.firstOrNull { it.external.contentEquals(external) } ?: Nat4(nat, external).also { nats += it }
        val lan = if (nat == Nat.NONE) external else byteArrayOf(192.toByte(), 168.toByte(), 1, (nextHost++).toByte())
        return object : LinkDatagrams {
            override fun open(): LinkPort = FakePort(this@FakeLinkNet, gateway, lan, v6, 50_000 + ports.size).also { ports += it }

            override fun networkKind(): Int = kind
        }
    }

    private class FakePort(
        val net: FakeLinkNet,
        val gateway: Nat4,
        val lan: ByteArray,
        val v6: ByteArray?,
        override val localPort: Int,
    ) : LinkPort {
        val queue = Channel<Datagram>(Channel.UNLIMITED)
        val v6Permitted = HashSet<String>()

        @Volatile var closed = false

        override val candidates: List<LinkCandidate> =
            listOfNotNull(
                v6?.let { LinkCandidate(it, localPort, LinkCandidate.Kind.V6) },
                LinkCandidate(lan, localPort, LinkCandidate.Kind.HOST).takeIf { gateway.kind != Nat.NONE },
            )

        override fun send(
            address: ByteArray,
            port: Int,
            data: ByteArray,
        ): Boolean = !closed && net.route(this, address, port, data)

        // A select, not withTimeoutOrNull around receive: a timeout that fires in the instant a datagram is handed over
        // would cancel the receive and lose the datagram (prompt cancellation), which no socket does.
        override suspend fun receive(timeoutMs: Int): Datagram? =
            if (closed) {
                null
            } else {
                select {
                    queue.onReceiveCatching { it.getOrNull() }
                    onTimeout(timeoutMs.toLong()) { null }
                }
            }

        override fun resolveV4(host: String): List<ByteArray> = listOfNotNull(net.stun[host])

        override fun close() {
            closed = true
            queue.close()
        }
    }

    @Synchronized
    private fun route(
        from: FakePort,
        address: ByteArray,
        port: Int,
        data: ByteArray,
    ): Boolean {
        if (loss > 0 && random.nextDouble() < loss) return true
        if (address.size == V6) return routeV6(from, address, port, data)
        stun.entries.firstOrNull { it.value.contentEquals(address) }?.let { (_, server) ->
            val (srcAddr, srcPort) = outbound(from, server, port)
            answerStun(from, server, port, srcAddr, srcPort, data)
            return true
        }
        // The LAN: another phone behind the same NAT, reached by its private address.
        ports.firstOrNull { it !== from && it.gateway === from.gateway && it.lan.contentEquals(address) && it.localPort == port }?.let {
            deliver(it, Datagram(data, from.lan, from.localPort))
            return true
        }
        val (srcAddr, srcPort) = outbound(from, address, port)
        val gateway = nats.firstOrNull { it.external.contentEquals(address) } ?: return true
        val to =
            if (gateway.kind == Nat.NONE) {
                ports.firstOrNull { it.gateway === gateway && it.localPort == port }
            } else {
                gateway.inbound[port]?.takeIf { admits(gateway, port, srcAddr, srcPort) }?.first
            }
        to?.let { deliver(it, Datagram(data, srcAddr, srcPort)) }
        return true
    }

    // Maps [from]'s port toward [address]:[port] on its NAT, records the filter permission, and returns the source the
    // far end sees.
    private fun outbound(
        from: FakePort,
        address: ByteArray,
        port: Int,
    ): Pair<ByteArray, Int> {
        val gateway = from.gateway
        if (gateway.kind == Nat.NONE) return gateway.external to from.localPort
        val key = if (gateway.kind == Nat.SYMMETRIC) "${from.localPort}|${hex(address)}|$port" else "${from.localPort}"
        val external = gateway.mappings.getOrPut(key) { gateway.nextPort++ }
        gateway.inbound[external] = from to key
        gateway.permitted += "$external|${hex(address)}"
        gateway.permitted += "$external|${hex(address)}|$port"
        return gateway.external to external
    }

    private fun admits(
        gateway: Nat4,
        external: Int,
        srcAddr: ByteArray,
        srcPort: Int,
    ): Boolean =
        when (gateway.kind) {
            Nat.NONE -> true
            Nat.ADDRESS_RESTRICTED -> "$external|${hex(srcAddr)}" in gateway.permitted
            Nat.PORT_RESTRICTED, Nat.SYMMETRIC -> "$external|${hex(srcAddr)}|$srcPort" in gateway.permitted
        }

    private fun routeV6(
        from: FakePort,
        address: ByteArray,
        port: Int,
        data: ByteArray,
    ): Boolean {
        val src = from.v6 ?: return false
        from.v6Permitted += "${hex(address)}|$port"
        val to = ports.firstOrNull { it.v6?.contentEquals(address) == true && it.localPort == port } ?: return true
        if ("${hex(src)}|${from.localPort}" in to.v6Permitted) deliver(to, Datagram(data, src, from.localPort))
        return true
    }

    // A Binding success carrying the XOR-MAPPED-ADDRESS of the source the server saw, sent back through the NAT.
    private fun answerStun(
        from: FakePort,
        server: ByteArray,
        serverPort: Int,
        srcAddr: ByteArray,
        srcPort: Int,
        request: ByteArray,
    ) {
        if (request.size != STUN_HEADER) return
        val answer = ByteArray(STUN_HEADER + 12)
        answer[0] = 0x01
        answer[1] = 0x01
        answer[3] = 12
        request.copyInto(answer, 4, 4, STUN_HEADER)
        answer[STUN_HEADER + 1] = 0x20
        answer[STUN_HEADER + 3] = 8
        answer[STUN_HEADER + 5] = 0x01
        val port = srcPort xor 0x2112
        answer[STUN_HEADER + 6] = (port shr 8).toByte()
        answer[STUN_HEADER + 7] = port.toByte()
        for (i in 0 until 4) answer[STUN_HEADER + 8 + i] = (srcAddr[i].toInt() xor request[4 + i].toInt()).toByte()
        deliver(from, Datagram(answer, server, serverPort))
    }

    private fun deliver(
        to: FakePort,
        datagram: Datagram,
    ) {
        if (!to.closed) to.queue.trySend(datagram)
    }

    private companion object {
        const val V6 = 16
        const val STUN_HEADER = 20

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
