package app.getknit.knit.transfer

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The receiver's way onto the sender's group and socket: a join retried until the join window closes, then a TCP
 * connect retried a few times. The counterpart of [TransferListener] on the host's side, kept apart from
 * [TransferManager] for detekt's LargeClass.
 */
internal class TransferDialer(
    private val wifi: DirectWifi,
    private val timings: TransferTimings,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
) {
    suspend fun join(
        id: String,
        credentials: GroupCredentials,
    ): JoinedGroup {
        val deadline = clock() + timings.joinWindowMs
        while (true) {
            try {
                return wifi.join(credentials, timings.joinAttemptMs)
            } catch (e: DirectWifiException) {
                if (clock() >= deadline) throw e
                log("xfer $id join attempt failed (${e.message}); retrying")
                delay(timings.joinRetryDelayMs)
            }
        }
    }

    suspend fun connect(
        group: JoinedGroup,
        port: Int,
    ): Socket {
        var last: IOException? = null
        repeat(timings.tcpConnectTries) {
            try {
                return Socket().apply {
                    receiveBufferSize = TransferManager.SOCKET_BUFFER_BYTES
                    sendBufferSize = TransferManager.SOCKET_BUFFER_BYTES
                    connect(InetSocketAddress(group.ownerAddress, port), timings.tcpConnectMs)
                }
            } catch (e: IOException) {
                last = e
                delay(timings.tcpConnectRetryMs)
            }
        }
        throw last ?: IOException("could not connect")
    }

    /**
     * Sends the receiver's verdict on [socket] and holds the link until the host has read it. The verdict is the last
     * byte of the stream, and releasing the radio straight after writing it can take the group down with the byte still
     * in flight: in the 2026-11-20 trial a staged update committed within milliseconds, the Pixel 7 left the group 5 ms
     * after its verdict, and the Pixel 9 waited out its verdict wait to fail a transfer that had landed. The host closes
     * once it has the verdict, so its close is the receipt; [TransferTimings.verdictLingerMs] bounds a host that never
     * closes, or keeps writing.
     */
    fun answer(
        socket: Socket,
        ok: Boolean,
    ) {
        TransferStream.writeVerdict(socket.getOutputStream(), ok)
        val until = clock() + timings.verdictLingerMs
        try {
            socket.shutdownOutput()
            val input = socket.getInputStream()
            val drain = ByteArray(DRAIN_BYTES)
            // An honest host sends nothing after the trailer: each read gets only the time left, and the bytes have a cap
            // of their own, so neither a trickle nor a clock stepping back holds the radio past the bound.
            var drained = 0
            var left = until - clock()
            while (drained < DRAIN_CAP_BYTES && left > 0) {
                socket.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val n = input.read(drain)
                drained = if (n == -1) DRAIN_CAP_BYTES else drained + n
                left = until - clock()
            }
        } catch (_: IOException) {
            // A host that reset the link, or never closed in time: the verdict went as far as it could.
        }
    }

    private companion object {
        const val DRAIN_BYTES = 512
        const val DRAIN_CAP_BYTES = 64 * 1024
    }
}
