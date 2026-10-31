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
 * time. A phone can move to another network ([Phone.move]), and the one it is on can go dark under it
 * ([Phone.darkAfter]). Nothing here is Android: it implements the [LinkDatagrams] seam the real link uses.
 */
@OptIn(ExperimentalCoroutinesApi::class) // select's onTimeout
class FakeLinkNet(
    private val loss: Double = 0.0,
    private val random: Random = Random(1),
    // A NAT forgets a mapping this long after the last datagram out through it (RFC 4787's mapping timer, refreshed by
    // outbound traffic only); null, never. Needs [clock].
    private val mappingTtlMs: Long? = null,
    private val clock: () -> Long = { 0L },
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
        val lastOut = HashMap<Int, Long>() // external port → last datagram out
        val inbound = HashMap<Int, Pair<FakePort, String>>() // external port → (port, mapping key)
        val permitted = HashSet<String>() // "extPort|addr" or "extPort|addr|port"
        var sentOut = 0 // datagrams sent to anything but a STUN server, lost ones too
    }

    // One phone's stay on one network: its NAT (shared by every phone behind it), its LAN and IPv6 addresses, and whether
    // it still carries anything. [left]: the phone moved off it ([Phone.move]), so a port on it sends nothing and is off the
    // default network. [dark]: it carries nothing either way, the phone still on it ([Phone.darkAfter]).
    private class Attachment(
        val gateway: Nat4,
        val lan: ByteArray,
        val v6: ByteArray?,
    ) {
        @Volatile var left = false

        @Volatile var dark = false
        var darkAfter: Long? = null // bytes it carries, either way, before it goes dark
    }

    private val nats = ArrayList<Nat4>()
    private val ports = ArrayList<FakePort>()
    private val stun = HashMap<String, ByteArray>() // name → address
    private val silent = HashMap<String, ByteArray>() // name → an address nothing answers at
    private var nextHost = 2

    /** A STUN server reachable as [name] at a public [address]. */
    fun stunServer(
        name: String,
        address: ByteArray,
    ) {
        stun[name] = address
    }

    /** A STUN server's name that resolves to [address] and never answers: a gather asking it waits out its whole wait. */
    fun silentStunServer(
        name: String,
        address: ByteArray,
    ) {
        silent[name] = address
    }

    /**
     * How many datagrams the phones behind the NAT at [external] have sent to anything but a STUN server, lost ones
     * included: a punch's probes and then its link's traffic, so a test sees an attempt has started punching.
     */
    @Synchronized
    fun sentOut(external: ByteArray): Int = nats.firstOrNull { it.external.contentEquals(external) }?.sentOut ?: 0

    /**
     * A phone behind a NAT of [nat] whose external address is [external] (phones that pass the same [external] share the
     * NAT and its LAN), with an IPv6 [v6] address behind a stateful firewall when given, on a network of [kind]. Under
     * [route]'s lock, as [open] is: the mesh lab's nodes add phones and ports from real threads while datagrams cross.
     */
    @Synchronized
    fun phone(
        nat: Nat,
        external: ByteArray,
        v6: ByteArray? = null,
        kind: Int = LinkSignal.NET_WIFI,
    ): Phone = Phone(nat, external, v6, kind)

    /**
     * One phone on this Internet: the network it is on now, where every port it [open]s is bound, as a socket is bound to
     * the default network on Android. Its ports stay on that network when the phone [move]s.
     */
    inner class Phone internal constructor(
        nat: Nat,
        external: ByteArray,
        v6: ByteArray?,
        private val kind: Int,
    ) : LinkDatagrams {
        private var at: Attachment = attach(nat, external, v6)

        /** The external address of the network it is on now. */
        val external: ByteArray get() = synchronized(this@FakeLinkNet) { at.gateway.external }

        /** Whether the network it is on now has gone dark ([darkAfter]). */
        val dark: Boolean get() = synchronized(this@FakeLinkNet) { at.dark }

        // Never null here, but typed as the seam is, so a test that opens one reads as it would against Android.
        override fun open(): LinkPort? = synchronized(this@FakeLinkNet) { open(at) }

        override fun networkKind(): Int = kind

        /**
         * Moves the phone to another network, behind a NAT of [nat] at [external], with [v6] if given: the network it was
         * on is left — a port bound to it sends nothing, nothing reaches it, and it is off the default network
         * ([LinkPort.onDefaultNetwork]) — and every port opened from now on is on the new one.
         */
        fun move(
            nat: Nat,
            external: ByteArray,
            v6: ByteArray? = null,
        ) {
            synchronized(this@FakeLinkNet) {
                at.left = true
                at = attach(nat, external, v6)
            }
        }

        /**
         * The network the phone is on now goes dark once it has carried [bytes] more, either way and STUN aside: from then
         * nothing crosses it, as Wi-Fi does walking out of range, while the phone stays on it until it [move]s.
         */
        fun darkAfter(bytes: Long) {
            synchronized(this@FakeLinkNet) { at.darkAfter = bytes }
        }
    }

    // A phone's stay on the network behind [external], sharing its NAT and LAN with the phones already there.
    private fun attach(
        nat: Nat,
        external: ByteArray,
        v6: ByteArray?,
    ): Attachment {
        val gateway = nats.firstOrNull { it.external.contentEquals(external) } ?: Nat4(nat, external).also { nats += it }
        val lan = if (nat == Nat.NONE) external else byteArrayOf(192.toByte(), 168.toByte(), 1, (nextHost++).toByte())
        return Attachment(gateway, lan, v6)
    }

    private fun open(attachment: Attachment): LinkPort = FakePort(this, attachment, 50_000 + ports.size).also { ports += it }

    private class FakePort(
        val net: FakeLinkNet,
        val attachment: Attachment,
        override val localPort: Int,
    ) : LinkPort {
        val gateway: Nat4 get() = attachment.gateway
        val lan: ByteArray get() = attachment.lan
        val v6: ByteArray? get() = attachment.v6
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
        ): Boolean = !closed && !attachment.left && net.route(this, address, port, data) // a left network fails the send

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

        override fun resolveV4(host: String): List<ByteArray> = listOfNotNull(net.stun[host] ?: net.silent[host])

        override fun onDefaultNetwork(): Boolean = synchronized(net) { !attachment.left }

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
        if (lost(from, address)) return true
        if (address.size == V6) return routeV6(from, address, port, data).also { carry(from.attachment, data.size) }
        stun.entries.firstOrNull { it.value.contentEquals(address) }?.let { (_, server) ->
            val (srcAddr, srcPort) = outbound(from, server, port)
            answerStun(from, server, port, srcAddr, srcPort, data)
            return true
        }
        carry(from.attachment, data.size)
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
                gateway.inbound[port]?.takeIf { !expired(gateway, port) && admits(gateway, port, srcAddr, srcPort) }?.first
            }
        to?.let { deliver(it, Datagram(data, srcAddr, srcPort)) }
        return true
    }

    // Counts a datagram [from] sends to anything but a STUN server ([sentOut]), then rolls whether it is lost on the way;
    // on a dark network it leaves and goes nowhere, uncounted.
    private fun lost(
        from: FakePort,
        address: ByteArray,
    ): Boolean {
        if (from.attachment.dark) return true
        if ((stun.values + silent.values).none { it.contentEquals(address) }) from.gateway.sentOut++
        return loss > 0 && random.nextDouble() < loss
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
        gateway.mappings[key]?.takeIf { expired(gateway, it) }?.let { stale ->
            gateway.mappings.remove(key)
            gateway.inbound.remove(stale)
            gateway.permitted.removeAll { it.startsWith("$stale|") }
        }
        val external = gateway.mappings.getOrPut(key) { gateway.nextPort++ }
        gateway.lastOut[external] = clock()
        gateway.inbound[external] = from to key
        gateway.permitted += "$external|${hex(address)}"
        gateway.permitted += "$external|${hex(address)}|$port"
        return gateway.external to external
    }

    private fun expired(
        gateway: Nat4,
        external: Int,
    ): Boolean = mappingTtlMs != null && clock() - (gateway.lastOut[external] ?: Long.MIN_VALUE / 2) >= mappingTtlMs

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
        if (to.closed || to.attachment.left || to.attachment.dark) return
        if (stun.values.none { it.contentEquals(datagram.address) }) carry(to.attachment, datagram.data.size)
        to.queue.trySend(datagram)
    }

    // [n] bytes crossed [attachment]: it goes dark once it has carried what [Phone.darkAfter] allowed it.
    private fun carry(
        attachment: Attachment,
        n: Int,
    ) {
        val budget = attachment.darkAfter ?: return
        if (budget > n) {
            attachment.darkAfter = budget - n
        } else {
            attachment.darkAfter = null
            attachment.dark = true
        }
    }

    private companion object {
        const val V6 = 16
        const val STUN_HEADER = 20

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
