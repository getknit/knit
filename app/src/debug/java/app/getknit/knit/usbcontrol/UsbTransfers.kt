package app.getknit.knit.usbcontrol

import app.getknit.knit.mesh.isValidBlobHash
import app.getknit.knit.mesh.sha256Hex
import kotlinx.serialization.json.JsonPrimitive

/**
 * One session's bytes in flight: the last file a download opened, and the one upload it holds. Pure, so the session
 * tests it without an app; confined to the server's worker, like the session.
 *
 * **Downloads** are read in [chunk]-byte slices. The opened plain bytes are kept under the key the caller names them by
 * (the message and its blob's hash), so a file is opened (a DM's decrypted) once rather than once per slice; the
 * caller still checks on every slice that the file may be read.
 *
 * **Uploads** arrive in order, at most [maxUpload] bytes, into one buffer of the declared size (the computer is the
 * paired one, and the cap bounds it); at that size the bytes are checked against the declared SHA-256.
 *
 * [wipe] drops both, zeroing the arrays it holds, at a hello, a bye and the session's end. Copies made on the way
 * (the base64 text, the frames, a decode) are left to the collector.
 */
internal class UsbTransfers(
    private val newId: () -> String,
    private val chunk: Int = MAX_CHUNK,
    private val maxUpload: Int = MAX_UPLOAD,
) {
    /** One slice of an opened file, and the whole file's [size] and [sha256] (lowercase hex). */
    class Slice(
        val offset: Int,
        val bytes: ByteArray,
        val size: Int,
        val sha256: String,
    ) {
        val done: Boolean get() = offset + bytes.size >= size
    }

    /** Where an upload stands: [received] bytes of [size]; [done] once all arrived and their SHA-256 matched. */
    class Progress(
        val received: Int,
        val done: Boolean,
    )

    private class Opened(
        val key: String,
        val bytes: ByteArray,
        val sha256: String,
    )

    private class Upload(
        val id: String,
        val size: Int,
        val sha256: String,
        val buffer: ByteArray,
        var received: Int = 0,
        var done: Boolean = false,
    )

    private var opened: Opened? = null
    private var upload: Upload? = null

    /**
     * The slice of the file known as [key] from [offset], opening it with [open] when it is not the one held. An
     * [offset] outside the file is refused.
     */
    suspend fun read(
        key: String,
        offset: Int,
        open: suspend () -> ByteArray,
    ): Slice {
        val file =
            opened?.takeIf { it.key == key } ?: run {
                wipeOpened()
                val bytes = open()
                Opened(key, bytes, sha256Hex(bytes)).also { opened = it }
            }
        val size = file.bytes.size
        // Offset 0 of an empty file is the one slice it has.
        if (offset !in 0 until size && offset != 0) refuse("offset $offset is outside the file ($size bytes)")
        val end = minOf(size, offset + chunk)
        return Slice(offset, file.bytes.copyOfRange(offset, end), size, file.sha256)
    }

    /** Opens an upload of [size] bytes whose SHA-256 is [sha256], replacing the one held; answers its id. */
    fun start(
        size: Long,
        sha256: String,
    ): String {
        if (size < 1) refuse("size is at least 1")
        if (size > maxUpload) throw UsbControlError(Codes.TOO_LARGE, "an upload is at most $maxUpload bytes")
        if (!isValidBlobHash(sha256)) refuse("sha256 is 64 lowercase hex digits")
        wipeUpload()
        val id = newId()
        upload = Upload(id, size.toInt(), sha256, ByteArray(size.toInt()))
        return id
    }

    /** Adds [data] at [offset] to upload [id]: the next bytes in order, never past its size. */
    fun add(
        id: String,
        offset: Long,
        data: ByteArray,
    ): Progress {
        val u = upload?.takeIf { it.id == id } ?: notFound()
        if (u.done) refuse("upload $id is complete")
        if (offset != u.received.toLong()) {
            throw UsbControlError(
                Codes.BAD_REQUEST,
                "offset $offset is not the next byte (${u.received})",
                mapOf("received" to JsonPrimitive(u.received)),
            )
        }
        if (data.isEmpty() || data.size > chunk) refuse("a chunk is 1..$chunk bytes")
        if (u.received + data.size > u.size) refuse("the chunk runs past the upload's ${u.size} bytes")
        data.copyInto(u.buffer, u.received)
        u.received += data.size
        if (u.received == u.size) {
            if (sha256Hex(u.buffer) != u.sha256) {
                wipeUpload()
                refuse("the upload's bytes do not match its sha256; upload again")
            }
            u.done = true
        }
        return Progress(u.received, u.done)
    }

    /**
     * A copy of complete upload [id]'s bytes, which stays held until [consume]: a send that fails can be asked again.
     * A copy, so the wipe never reaches an array the picker's pipeline kept.
     */
    fun complete(id: String): ByteArray {
        val u = upload?.takeIf { it.id == id } ?: notFound()
        if (!u.done) refuse("upload $id has ${u.received} of its ${u.size} bytes")
        return u.buffer.copyOf()
    }

    /** Drops upload [id], if it is the one held. */
    fun consume(id: String) {
        if (upload?.id == id) wipeUpload()
    }

    fun wipe() {
        wipeOpened()
        wipeUpload()
    }

    /** Drops the opened file: a slice of it was just refused, so nothing may be served from it again. */
    fun forgetOpened() = wipeOpened()

    private fun wipeOpened() {
        opened?.bytes?.fill(0)
        opened = null
    }

    private fun wipeUpload() {
        upload?.buffer?.fill(0)
        upload = null
    }

    private fun refuse(message: String): Nothing = throw UsbControlError(Codes.BAD_REQUEST, message)

    private fun notFound(): Nothing = throw UsbControlError(Codes.NOT_FOUND, "no such upload; upload again")

    companion object {
        /**
         * The most bytes a slice or an upload chunk carries: 60,000 base64 characters, leaving over 5 KB of a 65,520-byte
         * sealed frame for the reply's other fields (a name is at most 128 characters).
         */
        const val MAX_CHUNK = 45_000

        /** The most an upload may be: what the chat's picker reads of a picture (`AttachmentStore.MAX_BYTES`). */
        const val MAX_UPLOAD = 8 * 1024 * 1024
    }
}
