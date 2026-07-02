package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.protocol.Protocol
import java.io.InputStream
import java.io.OutputStream

/**
 * The transport-neutral identity handshake over a fresh [LinkSocket]: the initiator writes its advert as the
 * first [LinkFraming.Type.HELLO] record so an accept-any responder — which accepts a connection without
 * knowing who dialed in — learns the peer. Both Wi-Fi Aware (NDP) and Bluetooth (L2CAP) links use it.
 *
 * The responder must **bound** the [readHello] blocking read itself (so a stalled connector can't pin a slot):
 * Wi-Fi Aware sets `socket.soTimeout`, Bluetooth wraps the call in `withTimeoutOrNull` (a `BluetoothSocket`
 * has no `soTimeout`).
 */
object LinkHandshake {

    /** Initiator: write our identity ([Protocol.advertise] bytes) as the first record, then flush. */
    fun writeHello(output: OutputStream, localNodeId: String) {
        LinkFraming.write(output, LinkFraming.Type.HELLO, Protocol.advertise(localNodeId).encodeToByteArray())
        output.flush()
    }

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
