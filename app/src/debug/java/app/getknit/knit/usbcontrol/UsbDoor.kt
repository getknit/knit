package app.getknit.knit.usbcontrol

import app.getknit.knit.TextLimits
import app.getknit.knit.mesh.crypto.LinkSeal
import app.getknit.knit.usbcontrol.UsbControlSession.Companion.failure
import app.getknit.knit.usbcontrol.UsbControlSession.Companion.int
import app.getknit.knit.usbcontrol.UsbControlSession.Companion.string
import app.getknit.knit.usbcontrol.UsbControlSession.Companion.success
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.util.Base64

/**
 * The clear side of one link (`docs/USB_CONTROL.md`, *Pairing* and *Sessions*): before a session, the phone serves
 * `pair`, `pair.confirm` and `open` and nothing else, so a computer that never paired reads nothing and sends nothing.
 * One per link and one thread, the link's worker; a new link (a replug, a reopen, an app restart) is a new door.
 *
 * **The lock gate** is this door's [unlocked]: false when the link opens, set by an `open` that finds the phone
 * unlocked or by [userPresent] while the link is up, and gone with the link whatever ended it. So a phone plugged in
 * locked serves nothing until it is unlocked once, and after that it serves with the screen locked until the link ends
 * (the user's choice, 2026-11-12); a link that ended any other way than a detach fails closed, never open (the security
 * design pass). A phone with no secure lock reads as unlocked: it has no lock to protect.
 *
 * Every refusal comes before any X25519 or PBKDF2 work, since a computer retries `open` once a second while locked.
 */
internal class UsbDoor(
    private val store: PairingStore,
    private val pending: PendingPairs,
    private val enabled: () -> Boolean,
    private val deviceLocked: () -> Boolean,
    private val now: () -> Long,
    private val drawCode: () -> String = { UsbPairingCrypto.drawCode() },
    private val ephemeral: () -> LinkSeal.Ephemeral = { LinkSeal.Ephemeral.fresh() },
) {
    /** Whether the phone has been unlocked since this link opened. */
    @Volatile var unlocked: Boolean = false
        private set

    private var pairRequests = 0

    /** A session this door opened: the pairing it is under and this end's keys. */
    class Opened(
        val handle: ByteArray,
        val keys: UsbSeal.Keys,
    )

    /** A clear reply, and the session to switch to once it is written. */
    class Outcome(
        val reply: JsonObject,
        val opened: Opened? = null,
    )

    /** The phone was unlocked while this link was up (`ACTION_USER_PRESENT`). */
    fun userPresent() {
        unlocked = true
    }

    /** The link went down: a pair exchange it started dies with it. */
    fun close() {
        pending.endFor(this)
    }

    fun handle(text: String): Outcome {
        val request =
            runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return Outcome(failure(JsonNull, UsbControlError(Codes.BAD_REQUEST, "not a JSON object")))
        val id = request["id"] ?: JsonNull
        // A refusal echoes the nonce too, so a computer tells its own answer from one a previous run left queued.
        val echo =
            request
                .string("nonce")
                ?.takeIf {
                    it.length in 1..UsbControlSession.MAX_NONCE
                }?.let { mapOf("nonce" to JsonPrimitive(it)) }
                .orEmpty()
        return try {
            when (request.string("op")) {
                OP_PAIR -> Outcome(success(id, pair(request)))
                OP_CONFIRM -> Outcome(success(id, confirm(request)))
                OP_OPEN -> open(id, request)
                else -> Outcome(failure(id, UsbControlError(Codes.SESSION_FIRST, "pair this computer, then open a session", echo)))
            }
        } catch (e: UsbControlError) {
            Outcome(failure(id, UsbControlError(e.code, e.message.orEmpty(), e.extra + echo)))
        }
    }

    /** The answer to a sealed frame with no session up: it is from a session that ended, or from nobody. */
    fun sealedWithoutSession(): JsonObject = failure(JsonNull, UsbControlError(Codes.SESSION_FIRST, "no session: open one"))

    private fun pair(request: JsonObject): Map<String, JsonElement> {
        if (!enabled()) fail(Codes.OFF, "USB control is off on the phone")
        version(request)
        val nonce = nonce(request)
        val client = request.string("client") ?: refuse("missing client")
        val clientBytes = client.encodeToByteArray()
        if (clientBytes.size !in 1..UsbPairingCrypto.MAX_CLIENT_BYTES) refuse("client is 1..${UsbPairingCrypto.MAX_CLIENT_BYTES} bytes")
        val eH = key(request, "e", UsbPairingCrypto.KEY_BYTES)
        if (pending.live() != null) fail(Codes.PAIR_BUSY, "another pairing is waiting on the phone")
        if (pairRequests >= MAX_PAIR_REQUESTS) fail(Codes.PAIR_LIMIT, "too many pairings on this plug-in: replug")
        if (!pending.admit()) fail(Codes.PAIR_LIMIT, "too many pairings lately: try again in a few minutes")
        when (val stored = store.read()) {
            Stored.Unavailable -> unavailable()
            is Stored.Ok -> if (stored.pairings.size >= MAX_PAIRINGS) full()
        }
        pairRequests++
        val own = ephemeral()
        val eP = own.pub
        val ss = shared(own, eH)
        val code = drawCode()
        val outputs =
            try {
                UsbPairingCrypto.schedule(ss, code, eH, eP, clientBytes)
            } finally {
                ss.fill(0)
            }
        val name = TextLimits.cleanReceived(client, NAME_CHARS).ifBlank { UNNAMED }
        val held = PendingPair(owner = this, name = name, code = code, outputs = outputs, expiresAt = now() + PAIR_WINDOW_MS)
        if (!pending.hold(held)) fail(Codes.PAIR_BUSY, "another pairing is waiting on the phone")
        return mapOf(
            "nonce" to JsonPrimitive(nonce),
            "e" to JsonPrimitive(B64.encodeToString(eP)),
            "expiresInMs" to JsonPrimitive(PAIR_WINDOW_MS),
        )
    }

    private fun confirm(request: JsonObject): Map<String, JsonElement> {
        val nonce = nonce(request)
        val held = pending.live()?.takeIf { it.owner === this } ?: fail(Codes.EXPIRED, "no pairing waiting: pair again")
        val confirm = key(request, "confirm", UsbPairingCrypto.KEY_BYTES)
        if (!MessageDigest.isEqual(confirm, held.outputs.confirmHost)) {
            held.attemptsLeft -= 1
            if (held.attemptsLeft <= 0) pending.end(held)
            fail(
                Codes.BAD_CODE,
                if (held.attemptsLeft > 0) "that is not the code the phone shows" else "wrong code three times: pair again",
                mapOf("attemptsLeft" to JsonPrimitive(held.attemptsLeft.coerceAtLeast(0))),
            )
        }
        pending.end(held)
        val at = now()
        val outputs = held.outputs
        val added =
            store.update { pairings ->
                val kept = pairings.filterNot { it.handle contentEquals outputs.handle }
                if (kept.size >= MAX_PAIRINGS) full()
                kept + Pairing(outputs.handle, outputs.psk, held.name, at, at)
            }
        if (added !is Updated.Done) unavailable()
        return mapOf("nonce" to JsonPrimitive(nonce), "confirm" to JsonPrimitive(B64.encodeToString(outputs.confirmPhone)))
    }

    private fun open(
        id: JsonElement,
        request: JsonObject,
    ): Outcome {
        if (!enabled()) fail(Codes.OFF, "USB control is off on the phone")
        version(request)
        val nonce = nonce(request)
        val handles = handles(request)
        val eH = key(request, "e", UsbPairingCrypto.KEY_BYTES)
        if (!unlocked) {
            if (deviceLocked()) fail(Codes.LOCKED, "unlock the phone")
            unlocked = true
        }
        val pairings =
            when (val stored = store.read()) {
                Stored.Unavailable -> unavailable()
                is Stored.Ok -> stored.pairings
            }
        val pairing =
            handles.firstNotNullOfOrNull { h -> pairings.firstOrNull { it.handle contentEquals h } }
                ?: fail(Codes.NOT_PAIRED, "this computer is not paired with the phone")
        val own = ephemeral()
        val eP = own.pub
        val ss = shared(own, eH)
        val keys =
            try {
                UsbSeal.sessionKeys(UsbSeal.Role.PHONE, pairing.psk, ss, eH, eP, pairing.handle)
            } finally {
                ss.fill(0)
            }
        val at = now()
        if (at - pairing.lastUsedAt >= TOUCH_EVERY_MS) {
            // Best effort: a list that can't be rewritten keeps its older time, and the session opens all the same. Only
            // this record moves, and only if it is still there: a computer forgotten since the read above stays gone.
            store.update { now ->
                if (now.none { it.handle contentEquals pairing.handle }) {
                    null
                } else {
                    now.map { if (it.handle contentEquals pairing.handle) it.usedAt(at) else it }
                }
            }
        }
        val reply =
            success(
                id,
                mapOf(
                    "nonce" to JsonPrimitive(nonce),
                    "handle" to JsonPrimitive(pairing.handleText),
                    "e" to JsonPrimitive(B64.encodeToString(eP)),
                ),
            )
        return Outcome(reply, Opened(pairing.handle, keys))
    }

    private fun shared(
        own: LinkSeal.Ephemeral,
        peer: ByteArray,
    ): ByteArray =
        try {
            UsbSeal.sharedSecret(own, peer)
        } catch (_: UsbSealException) {
            refuse("rejected key share")
        }

    private fun version(request: JsonObject) {
        val v = request.int("v") ?: refuse("missing v")
        if (v != VERSION) fail(Codes.BAD_VERSION, "unsupported version $v; this phone speaks $VERSION")
    }

    private fun nonce(request: JsonObject): String =
        request.string("nonce")?.takeIf { it.length in 1..UsbControlSession.MAX_NONCE } ?: refuse("missing nonce")

    private fun handles(request: JsonObject): List<ByteArray> {
        val list = request["handles"] as? JsonArray ?: refuse("missing handles")
        if (list.size !in 1..MAX_HANDLES) refuse("handles holds 1..$MAX_HANDLES")
        return list.map {
            bytesOf(
                (it as? JsonPrimitive)
                    ?.takeIf { p ->
                        p.isString
                    }?.contentOrNull,
                UsbPairingCrypto.HANDLE_BYTES,
                "handle",
            )
        }
    }

    private fun key(
        request: JsonObject,
        name: String,
        size: Int,
    ): ByteArray = bytesOf(request.string(name), size, name)

    private fun bytesOf(
        text: String?,
        size: Int,
        name: String,
    ): ByteArray {
        val bytes = text?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        if (bytes == null || bytes.size != size) refuse("$name is $size bytes of base64")
        return bytes
    }

    private fun refuse(message: String): Nothing = fail(Codes.BAD_REQUEST, message)

    private fun fail(
        code: String,
        message: String,
        extra: Map<String, JsonElement> = emptyMap(),
    ): Nothing = throw UsbControlError(code, message, extra)

    private fun unavailable(): Nothing = fail(Codes.STORE_UNAVAILABLE, "the phone can't read its pairings right now")

    private fun full(): Nothing = fail(Codes.PAIRINGS_FULL, "the phone holds $MAX_PAIRINGS computers: forget one")

    companion object {
        const val VERSION = 2
        const val OP_PAIR = "pair"
        const val OP_CONFIRM = "pair.confirm"
        const val OP_OPEN = "open"

        /** How long a pair exchange lives, on each side's own clock, from its `pair`. */
        const val PAIR_WINDOW_MS = 120_000L
        const val CONFIRM_ATTEMPTS = 3
        const val MAX_PAIR_REQUESTS = 3
        const val MAX_PAIRINGS = 8
        const val MAX_HANDLES = 8

        /** The most of a computer's claimed name the phone shows and keeps. */
        const val NAME_CHARS = 40
        const val TOUCH_EVERY_MS = 60_000L
        const val UNNAMED = "a computer with no name"
    }
}
