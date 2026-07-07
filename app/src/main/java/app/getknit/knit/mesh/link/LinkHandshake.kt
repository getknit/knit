package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.protocol.Protocol
import java.io.InputStream
import java.io.OutputStream

/**
 * The transport-neutral identity handshake over a fresh [LinkSocket], a **two-way** HELLO exchange. The
 * initiator writes its advert as the first [LinkFraming.Type.HELLO] record so an accept-any responder —
 * which accepts a connection without knowing who dialed in — learns the peer; the responder then
 * [replyHello]s its own advert so the **initiator** can confirm it reached the peer it intended (link
 * identity is confirmed over the socket, never taken solely from the unauthenticated discovery advert).
 * Both Wi-Fi Aware (NDP) and Bluetooth (L2CAP) links use it.
 *
 * Ordering is deadlock-free: the initiator writes-then-reads, the responder reads-then-replies, so neither
 * blocks on a read before the other has written. Each side must **bound** its [readHello] blocking read (so
 * a stalled peer can't pin a slot): Wi-Fi Aware sets `socket.soTimeout`, Bluetooth closes the socket from a
 * watchdog (a `BluetoothSocket` has no `soTimeout`).
 */
object LinkHandshake {
    /** Initiator: write our identity ([Protocol.advertise] bytes) as the first record, then flush. */
    fun writeHello(
        output: OutputStream,
        localNodeId: String,
    ) {
        LinkFraming.write(output, LinkFraming.Type.HELLO, Protocol.advertise(localNodeId).encodeToByteArray())
        output.flush()
    }

    /**
     * Responder: reply with our own identity (symmetric to [writeHello]) so the initiator can confirm the
     * peer it reached. Sent only after the responder has read and accepted the initiator's HELLO.
     */
    fun replyHello(
        output: OutputStream,
        localNodeId: String,
    ) = writeHello(output, localNodeId)

    /**
     * Responder: read exactly one record and require it to be a [LinkFraming.Type.HELLO], returning the peer's
     * parsed advert — or null if the first record is missing / not a HELLO (reject and close the socket). Reads
     * from a plain [InputStream] so the caller can pass whichever buffered stream it will keep reading from.
     */
    fun readHello(input: InputStream): Protocol.PeerWire? {
        val first = runCatching { LinkFraming.read(input) }.getOrNull() ?: return null
        if (first.type != LinkFraming.Type.HELLO) return null
        return Protocol.parse(first.payload.decodeToString())
    }
}
