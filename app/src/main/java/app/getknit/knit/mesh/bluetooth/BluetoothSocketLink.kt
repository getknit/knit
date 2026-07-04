package app.getknit.knit.mesh.bluetooth

import android.bluetooth.BluetoothSocket
import app.getknit.knit.mesh.link.LinkSocket
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Adapts an `android.bluetooth.BluetoothSocket` (an L2CAP CoC channel) to [LinkSocket] so [FramedLink] runs
 * over it unchanged. `BluetoothSocket` is not a `java.net.Socket` (it only exposes
 * `inputStream`/`outputStream`/`close()`), which is exactly why the shared machinery depends on the narrow
 * [LinkSocket] seam. [input] is cached/buffered so the responder's HELLO read and the read loop share it.
 */
internal class BluetoothSocketLink(
    private val socket: BluetoothSocket,
) : LinkSocket {
    override val input: InputStream by lazy { BufferedInputStream(socket.inputStream) }
    override val output: OutputStream get() = socket.outputStream

    override fun close() {
        runCatching { socket.close() }
    }
}
