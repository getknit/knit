package app.getknit.knit.usbcontrol

import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * What one link serves, frame by frame: the clear side through its [door] until a paired computer opens a session,
 * then that session's sealed frames, until it ends. Pure (no streams, no Android but a log line): the link's worker
 * feeds it what came off the cable and writes what it returns, in order, so it is tested on the JVM.
 *
 * **A session ends**, back to the clear side with its keys zeroed and [onSessionEnd] told (the feed stops), when a
 * sealed frame does not open, the stream loses its place, the pairing is forgotten, its counter runs out, the computer
 * says `bye`, or a clear frame arrives. That last one is the next knit-cli run after one that left without a bye; a
 * party on the cable could do the same, as it could by pulling the cable, and the next session needs the pairing's key
 * either way. Ending the session rather than the link keeps the accessory, which can't be reopened without a replug,
 * and keeps the door's lock gate, which belongs to the link.
 */
internal class UsbLinkState(
    private val door: UsbDoor,
    private val newSession: () -> UsbControlSession,
    private val onSessionEnd: (String) -> Unit = {},
) {
    private class Sealed(
        val handle: ByteArray,
        val keys: UsbSeal.Keys,
        val session: UsbControlSession,
    ) {
        val sealer = UsbSeal.Sealer(keys.send)
        val opener = UsbSeal.Opener(keys.recv)
    }

    private var sealed: Sealed? = null

    /** Whether a paired computer's session is up. */
    @Volatile var sessionUp: Boolean = false
        private set

    /** The client name the session's hello gave. */
    @Volatile var client: String? = null
        private set

    /** Why the last session ended. */
    @Volatile var lastSessionEnd: String? = null
        private set

    /** The frames to write for one frame off the cable. */
    suspend fun frame(raw: RawFrame): List<ByteArray> = if (raw.sealed) sealedFrame(raw) else clearFrame(raw)

    /** The stream lost its place (a length out of range): a session can't trust what follows, and its keys go with it. */
    fun framing(reason: String): List<ByteArray> {
        end("framing: $reason", tell = false)
        return listOf(clear(UsbControlSession.framingError(reason)))
    }

    /** These pairings were just forgotten on the phone: a session under one of them ends. */
    fun forgotten(handles: List<ByteArray>): List<ByteArray> {
        val s = sealed ?: return emptyList()
        if (handles.none { it contentEquals s.handle }) return emptyList()
        return end("this computer was forgotten on the phone")
    }

    /** [event] sealed for the session; null with no session up, or for one over the cap (the caller tries its stub). */
    fun event(event: JsonObject): ByteArray? {
        val s = sealed ?: return null
        return seal(s, event)
    }

    /** The link is going down: the session ends with nothing said, and the door's pair exchange with it. */
    fun close() {
        end("link closed", tell = false)
        door.close()
    }

    private fun clearFrame(raw: RawFrame): List<ByteArray> {
        end("a new run opened", tell = false)
        val outcome = door.handle(raw.text())
        val reply = clear(outcome.reply)
        outcome.opened?.let { opened ->
            sealed = Sealed(opened.handle, opened.keys, newSession())
            sessionUp = true
            client = null
        }
        return listOf(reply)
    }

    private suspend fun sealedFrame(raw: RawFrame): List<ByteArray> {
        val s = sealed ?: return listOf(clear(door.sealedWithoutSession()))
        val plain =
            try {
                s.opener.open(raw.bytes)
            } catch (e: UsbSealException) {
                return end("a frame did not open (${e.message})")
            }
        val reply = s.session.handle(plain.decodeToString())
        client = s.session.client
        val bytes = seal(s, reply) ?: seal(s, overCap(reply["id"] ?: JsonNull))
        if (bytes == null) return if (sealed == null) emptyList() else end("a reply could not be sealed")
        if (s.session.ended) end("bye", tell = false)
        return listOf(bytes)
    }

    /** [json] sealed for [s]; null over the cap. A spent counter ends the session. */
    private fun seal(
        s: Sealed,
        json: JsonObject,
    ): ByteArray? =
        try {
            UsbControlFrames.encodeSealed(s.sealer, json.toString())
        } catch (e: IOException) {
            Log.w(UsbControlServer.TAG, "usb control frame refused: ${e.message}")
            null
        } catch (_: UsbSealException) {
            end("counter spent", tell = false)
            null
        }

    /**
     * Ends the session, if one is up. [tell] answers the computer with a clear `session_ended`, so it stops waiting on
     * a reply; the frames to write are returned.
     */
    private fun end(
        reason: String,
        tell: Boolean = true,
    ): List<ByteArray> {
        val s = sealed ?: return emptyList()
        sealed = null
        sessionUp = false
        s.keys.zero()
        s.session.close()
        lastSessionEnd = reason
        onSessionEnd(reason)
        if (!tell) return emptyList()
        return listOf(
            clear(
                buildJsonObject {
                    put("event", "error")
                    put("code", Codes.SESSION_ENDED)
                    put("error", reason)
                },
            ),
        )
    }

    private fun clear(json: JsonObject): ByteArray = UsbControlFrames.encode(json.toString())

    private fun overCap(id: JsonElement): JsonObject =
        buildJsonObject {
            put("id", id)
            put("ok", false)
            put("code", Codes.INTERNAL)
            put("error", "reply over the ${UsbSeal.MAX_PLAINTEXT}-byte frame cap; ask for less")
        }
}
