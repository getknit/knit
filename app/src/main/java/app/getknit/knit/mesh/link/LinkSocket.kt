package app.getknit.knit.mesh.link

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The narrow "we have a connected bidirectional byte stream to a peer" seam that [FramedLink] runs over —
 * everything the per-connection read/write loops need and nothing transport-specific. It deliberately does
 * **not** extend [java.net.Socket]: an `android.bluetooth.BluetoothSocket` (RFCOMM or L2CAP CoC) is not a
 * `java.net.Socket` — it only exposes `getInputStream()/getOutputStream()/close()` — so a transport adapts
 * whatever socket it produced (a NAN [java.net.Socket] via [NetSocketLink], a Bluetooth socket via a
 * `BluetoothSocketLink` beside the BT transport) to this interface and hands it to [FramedLink].
 *
 * [input] MUST be a **single stable, buffered** stream: an accept-any responder reads the peer's
 * [LinkFraming.Type.HELLO] from it (via [LinkHandshake.readHello]) *before* handing the socket to
 * [FramedLink], which then keeps reading the same stream — so any bytes the HELLO read buffered past the
 * record must not be stranded on a second wrapper. Implementations cache the buffered stream (see
 * [NetSocketLink]); [FramedLink] therefore does not wrap it again.
 */
interface LinkSocket {
    val input: InputStream
    val output: OutputStream
    fun close()
}

/** Adapts a plain [java.net.Socket] (the Wi-Fi Aware NDP TCP socket) to [LinkSocket]. */
class NetSocketLink(private val socket: java.net.Socket) : LinkSocket {
    // Cached so the HELLO read and FramedLink's read loop share one buffer (see the interface contract).
    override val input: InputStream by lazy { BufferedInputStream(socket.getInputStream()) }
    override val output: OutputStream get() = socket.getOutputStream()
    override fun close() {
        runCatching { socket.close() }
    }
}
