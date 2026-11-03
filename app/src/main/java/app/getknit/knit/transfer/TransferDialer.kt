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
}
