package app.getknit.knit.net

import android.net.Network
import app.getknit.knit.linkpreview.PublicAddressPolicy
import app.getknit.knit.mesh.inet.Datagram
import app.getknit.knit.mesh.inet.LinkCandidate
import app.getknit.knit.mesh.inet.LinkDatagrams
import app.getknit.knit.mesh.inet.LinkPort
import app.getknit.knit.mesh.inet.PunchPolicy
import app.getknit.knit.mesh.protocol.LinkSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * [LinkDatagrams] over Android (ADR 2026-10.a5yj): each port is one UDP socket bound to the validated default network
 * the gate hands out ([AndroidInternetGate.currentNetwork]) — never the process default, never the Wi-Fi Aware link —
 * and its candidates are that network's own addresses at the socket's port, kept only where `PunchPolicy.sane` would
 * keep them on the other end. A name is resolved through the same network and only its public IPv4 addresses are
 * returned (`PublicAddressPolicy`), so a STUN query never leaves for a private or loopback host.
 */
class AndroidLinkDatagrams(
    private val gate: AndroidInternetGate,
) : LinkDatagrams {
    override fun open(): LinkPort? {
        val network = gate.currentNetwork() ?: return null
        return try {
            val socket = DatagramSocket()
            try {
                network.bindSocket(socket)
            } catch (e: IOException) {
                socket.close()
                throw e
            }
            AndroidLinkPort(socket, network, gate)
        } catch (_: IOException) {
            null
        }
    }

    override fun networkKind(): Int =
        when (gate.routeKind()) {
            InternetGate.RouteKind.WIFI -> LinkSignal.NET_WIFI
            InternetGate.RouteKind.CELLULAR -> LinkSignal.NET_CELLULAR
            else -> LinkSignal.NET_OTHER
        }
}

private class AndroidLinkPort(
    private val socket: DatagramSocket,
    private val network: Network,
    private val gate: AndroidInternetGate,
) : LinkPort {
    override val localPort: Int = socket.localPort

    private val own = gate.ownAddresses(network)

    // The network's IPv6 prefixes: a peer's IPv6 address inside one is on this link (`PunchPolicy.preferred`).
    private val prefixes = own.filter { it.bytes.size == V6_BYTES && it.prefixLength > 0 }

    override val candidates: List<LinkCandidate> =
        own.mapNotNull { address ->
            val kind =
                when {
                    address.bytes.size == V6_BYTES && address.temporary -> LinkCandidate.Kind.V6_TEMPORARY
                    address.bytes.size == V6_BYTES -> LinkCandidate.Kind.V6
                    else -> LinkCandidate.Kind.HOST
                }
            LinkCandidate(address.bytes, localPort, kind).takeIf(PunchPolicy::sane)
        }

    override fun send(
        address: ByteArray,
        port: Int,
        data: ByteArray,
    ): Boolean =
        try {
            socket.send(DatagramPacket(data, data.size, InetAddress.getByAddress(address), port))
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    // One reader at a time (the session's), blocking on the I/O pool up to the timeout; a close ends the wait at once.
    override suspend fun receive(timeoutMs: Int): Datagram? =
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_BYTES)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.soTimeout = timeoutMs.coerceAtLeast(1)
                socket.receive(packet)
                Datagram(buffer.copyOf(packet.length), packet.address.address, packet.port)
            } catch (_: SocketTimeoutException) {
                null
            } catch (_: IOException) {
                null
            }
        }

    override fun resolveV4(host: String): List<ByteArray> =
        try {
            network.getAllByName(host).filter { it is Inet4Address && PublicAddressPolicy.isPublic(it.address) }.map { it.address }
        } catch (_: IOException) {
            emptyList()
        }

    override fun onDefaultNetwork(): Boolean = gate.currentNetwork() == network

    override fun onLink(address: ByteArray): Boolean = prefixes.any { PunchPolicy.inPrefix(it.bytes, it.prefixLength, address) }

    override fun close() = socket.close()

    private companion object {
        const val V6_BYTES = 16

        /** Larger than any datagram a link sends, so nothing it receives is ever cut short. */
        const val BUFFER_BYTES = 2048
    }
}
