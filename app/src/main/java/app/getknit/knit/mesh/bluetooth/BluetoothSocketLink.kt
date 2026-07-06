package app.getknit.knit.mesh.bluetooth

import android.bluetooth.BluetoothSocket
import app.getknit.knit.mesh.link.LinkSocket
import app.getknit.knit.mesh.link.SegmentingOutputStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Adapts an `android.bluetooth.BluetoothSocket` (an L2CAP CoC channel) to [LinkSocket] so [FramedLink] runs
 * over it unchanged. `BluetoothSocket` is not a `java.net.Socket` (it only exposes
 * `inputStream`/`outputStream`/`close()`), which is exactly why the shared machinery depends on the narrow
 * [LinkSocket] seam. [input] is cached/buffered so the responder's HELLO read and the read loop share it.
 *
 * [output] is wrapped in a [SegmentingOutputStream] so no single write exceeds one L2CAP SDU — a whole
 * 64 KiB FILE_CHUNK record otherwise emitted a 65541-byte write past the 16-bit (65535) SDU ceiling, which a
 * weaker vendor stack (Moto G `cherokee`, API 30) crashed reassembling. See [SegmentingOutputStream].
 */
internal class BluetoothSocketLink(
    private val socket: BluetoothSocket,
) : LinkSocket {
    override val input: InputStream by lazy { BufferedInputStream(socket.inputStream) }

    // Segment each L2CAP write to the negotiated per-packet size, capped well below the 65535-byte SDU
    // ceiling. getMaxTransmitPacketSize() is API 23 (safe at minSdk 29); some stacks report the 65535 SDU
    // max here rather than the true MPS, so the cap — not the report — is the guarantee. Smaller SDUs cost
    // no real throughput (the controller fragments to MPS K-frames regardless).
    private val txChunk: Int =
        socket.maxTransmitPacketSize.let { if (it in 1 until L2CAP_TX_CHUNK) it else L2CAP_TX_CHUNK }

    override val output: OutputStream by lazy { SegmentingOutputStream(socket.outputStream, txChunk) }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        /** Hard cap on a single L2CAP write, comfortably below the 65535-byte L2CAP SDU ceiling. */
        const val L2CAP_TX_CHUNK = 8 * 1024
    }
}
