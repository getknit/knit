package app.getknit.knit.ui.call

import app.getknit.knit.call.StunBinding
import app.getknit.knit.call.TurnProbe
import app.getknit.knit.linkpreview.PublicAddressPolicy
import app.getknit.knit.mesh.spool.TurnHosts
import app.getknit.knit.net.AndroidInternetGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * [TurnProbe] over Android (C-7.6-52): a UDP socket bound to the validated default network the gate hands out, never
 * the process default and never the Wi-Fi Aware link, the name resolved through that network, and three [StunBinding]
 * requests to the first address the link preview's guard calls public — never loopback, link-local, private or
 * multicast. The score is the least round trip among the answers [StunBinding.accepts], each counted only inside its
 * own [TIMEOUT_MS]. Twelve 20-byte packets at most per spool per network change, and none of what comes back is kept.
 */
class UdpTurnProbe(
    private val gate: AndroidInternetGate,
    private val random: SecureRandom = SecureRandom(),
) : TurnProbe {
    override suspend fun measure(entry: TurnHosts.Entry): TurnProbe.Result =
        withContext(Dispatchers.IO) {
            val network = gate.currentNetwork() ?: return@withContext TurnProbe.Result.NoRoute
            val address =
                try {
                    network.getAllByName(entry.host).firstOrNull { PublicAddressPolicy.isPublic(it.address) }
                } catch (_: IOException) {
                    null
                } ?: return@withContext TurnProbe.Result.Silent
            try {
                DatagramSocket().use { socket ->
                    network.bindSocket(socket)
                    socket.connect(InetSocketAddress(address, entry.port))
                    exchange(socket)?.let { TurnProbe.Result.Answered(it) } ?: TurnProbe.Result.Silent
                }
            } catch (_: IOException) {
                TurnProbe.Result.Silent
            }
        }

    // Sends the requests on their schedule and reads answers in between; the least accepted round trip, or null.
    private suspend fun exchange(socket: DatagramSocket): Long? {
        val transactions = List(REQUESTS) { ByteArray(StunBinding.TRANSACTION_BYTES).also(random::nextBytes) }
        val sentAt = LongArray(REQUESTS)
        val start = nowMs()
        var best: Long? = null
        for (i in 0 until REQUESTS) {
            best = least(best, listen(socket, until = start + i * SPACING_MS, transactions, sentAt, sent = i))
            val request = StunBinding.request(transactions[i])
            socket.send(DatagramPacket(request, request.size))
            sentAt[i] = nowMs()
        }
        val last = sentAt[REQUESTS - 1] + TIMEOUT_MS
        return least(best, listen(socket, until = last, transactions, sentAt, sent = REQUESTS))
    }

    // Reads answers until [until] to the first [sent] requests: the least accepted round trip among them, or null.
    private suspend fun listen(
        socket: DatagramSocket,
        until: Long,
        transactions: List<ByteArray>,
        sentAt: LongArray,
        sent: Int,
    ): Long? {
        val buffer = ByteArray(BUFFER_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        var best: Long? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val wait = until - nowMs()
            if (wait <= 0) return best
            socket.soTimeout = wait.coerceIn(1L, SLICE_MS).toInt()
            packet.length = buffer.size
            val heard =
                try {
                    socket.receive(packet)
                    true
                } catch (_: SocketTimeoutException) {
                    false
                }
            if (heard) best = least(best, roundTrip(buffer, packet.length, transactions, sentAt, sent))
        }
    }

    // The round trip of the request [buffer] answers, among the first [sent], inside its own window; else null.
    private fun roundTrip(
        buffer: ByteArray,
        length: Int,
        transactions: List<ByteArray>,
        sentAt: LongArray,
        sent: Int,
    ): Long? {
        val at = nowMs()
        return (0 until sent)
            .filter { at - sentAt[it] <= TIMEOUT_MS && StunBinding.accepts(buffer, length, transactions[it]) }
            .minOfOrNull { at - sentAt[it] }
    }

    private fun least(
        a: Long?,
        b: Long?,
    ): Long? = listOfNotNull(a, b).minOrNull()

    private fun nowMs(): Long = System.nanoTime() / NANOS_PER_MS

    private companion object {
        const val REQUESTS = 3
        const val SPACING_MS = 200L
        const val TIMEOUT_MS = 1_000L

        // A blocking read never outlasts this, so a cancelled probe stops within it.
        const val SLICE_MS = 100L

        // A Binding success with an XOR-MAPPED-ADDRESS and the usual few attributes is well under this.
        const val BUFFER_BYTES = 548
        const val NANOS_PER_MS = 1_000_000L
    }
}
