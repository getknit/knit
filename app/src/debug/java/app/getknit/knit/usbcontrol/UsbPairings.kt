package app.getknit.knit.usbcontrol

import android.content.Context
import android.util.Log
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.crypto.Loss
import app.getknit.knit.data.crypto.Unwrapped
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.Base64

/** One computer this phone has paired with: what a session needs (its [handle] and [psk]) and what the list shows. */
internal class Pairing(
    val handle: ByteArray,
    val psk: ByteArray,
    val name: String,
    val pairedAt: Long,
    val lastUsedAt: Long,
) {
    /** The handle as the protocol and the bridge write it. */
    val handleText: String get() = B64.encodeToString(handle)

    fun usedAt(at: Long) = Pairing(handle, psk, name, pairedAt, at)
}

/** What a read of the paired computers found. */
internal sealed interface Stored {
    class Ok(
        val pairings: List<Pairing>,
    ) : Stored

    /** The Keystore refused and proved nothing: the pairings may well be intact, so nothing may be written. */
    data object Unavailable : Stored
}

/** What an [PairingStore.update] did. */
internal sealed interface Updated {
    /** The Keystore refused the read: nothing was written. */
    data object Unavailable : Updated

    /** The read held, the write was refused: the old list stands. */
    data object Refused : Updated

    /** [after] is what the store holds now: the same list as [before] when the change wrote nothing. */
    class Done(
        val before: List<Pairing>,
        val after: List<Pairing>,
    ) : Updated
}

/**
 * Where the paired computers live. Several threads change them (a link's worker pairing or opening, a forget from the
 * screen or the bridge), so every change goes through [update]: a read, the change and the write under one lock,
 * never a read and a later write, which would put back a computer forgotten in between.
 */
internal interface PairingStore {
    fun read(): Stored

    /**
     * Applies [change] to the list as it stands and writes the result, atomically against every other change; a
     * [change] that answers null writes nothing. Whatever [change] throws propagates, with nothing written.
     */
    fun update(change: (List<Pairing>) -> List<Pairing>?): Updated
}

/**
 * The paired computers, as one JSON blob wrapped by a hardware-backed Keystore key ([KeystoreSecret], under
 * `filesDir` as the other wraps are, so its guard against a falsely missing key covers this file too). A copy of the
 * file is useless off this phone: the key never leaves it.
 *
 * A wrap proven lost ([Unwrapped.Lost]) is moved aside and read as no pairings: every computer pairs again. One the
 * Keystore merely refused ([Unwrapped.Unavailable]) reads as [Stored.Unavailable], and nothing is written over it.
 */
internal class KeystorePairingStore(
    private val secret: KeystoreSecret,
) : PairingStore {
    constructor(context: Context) : this(KeystoreSecret(context, ALIAS, FILE))

    private var freshKey = false

    @Synchronized
    override fun read(): Stored =
        when (val unwrapped = secret.read()) {
            is Unwrapped.Present -> {
                Stored.Ok(PairingsJson.decode(unwrapped.bytes))
            }

            Unwrapped.Absent -> {
                Stored.Ok(emptyList())
            }

            is Unwrapped.Lost -> {
                Log.w(UsbControlServer.TAG, "usb pairings lost (${unwrapped.reason}): every computer pairs again")
                secret.keepAside()
                freshKey = unwrapped.reason == Loss.KEY_INVALIDATED
                Stored.Ok(emptyList())
            }

            is Unwrapped.Unavailable -> {
                Stored.Unavailable
            }
        }

    @Synchronized
    override fun update(change: (List<Pairing>) -> List<Pairing>?): Updated {
        val before =
            when (val stored = read()) {
                Stored.Unavailable -> return Updated.Unavailable
                is Stored.Ok -> stored.pairings
            }
        val after = change(before) ?: return Updated.Done(before, before)
        return if (write(after)) Updated.Done(before, after) else Updated.Refused
    }

    @Suppress("TooGenericExceptionCaught") // a store the Keystore refuses is a refused write, not a crash
    private fun write(pairings: List<Pairing>): Boolean =
        try {
            secret.store(PairingsJson.encode(pairings).encodeToByteArray(), freshKey = freshKey)
            freshKey = false
            true
        } catch (e: Exception) {
            Log.w(UsbControlServer.TAG, "usb pairings not stored: ${e.javaClass.simpleName}")
            false
        }

    companion object {
        const val ALIAS = "knit_usb_pairings"
        const val FILE = "usb_pairings.key"
    }
}

internal val B64: Base64.Encoder = Base64.getEncoder()

/** The pairings as the store writes and reads them. */
internal object PairingsJson {
    fun encode(pairings: List<Pairing>): String =
        buildJsonObject {
            put("v", 1)
            put(
                "pairings",
                buildJsonArray {
                    pairings.forEach { p ->
                        add(
                            buildJsonObject {
                                put("handle", B64.encodeToString(p.handle))
                                put("psk", B64.encodeToString(p.psk))
                                put("name", p.name)
                                put("pairedAt", p.pairedAt)
                                put("lastUsedAt", p.lastUsedAt)
                            },
                        )
                    }
                },
            )
        }.toString()

    /** The pairings in [bytes]; a blob that does not parse reads as none (the computers pair again), never a crash. */
    fun decode(bytes: ByteArray): List<Pairing> =
        runCatching {
            val decoder = Base64.getDecoder()
            (Json.parseToJsonElement(bytes.decodeToString()) as JsonObject).getValue("pairings").jsonArray.map {
                val p = it.jsonObject
                Pairing(
                    handle = decoder.decode(p.getValue("handle").jsonPrimitive.content),
                    psk = decoder.decode(p.getValue("psk").jsonPrimitive.content),
                    name = p.getValue("name").jsonPrimitive.content,
                    pairedAt = p.getValue("pairedAt").jsonPrimitive.long,
                    lastUsedAt = p.getValue("lastUsedAt").jsonPrimitive.long,
                )
            }
        }.getOrElse { emptyList() }
}

/**
 * A pair exchange the phone is waiting on: the code it shows and what the exchange derived. [owner] is the link's
 * door that asked, so the exchange dies with that link.
 */
internal class PendingPair(
    val owner: Any,
    val name: String,
    val code: String,
    val outputs: UsbPairingCrypto.Outputs,
    val expiresAt: Long,
) {
    @Volatile var attemptsLeft: Int = UsbDoor.CONFIRM_ATTEMPTS
}

/**
 * The one pair exchange the phone holds at a time, shared by the link that asked and the screen that shows the code.
 * [onChange] hears every change (the notification follows it), outside the lock.
 */
internal class PendingPairs(
    private val now: () -> Long,
    private val onChange: (PendingPair?) -> Unit = {},
) {
    private var held: PendingPair? = null

    /** When the recent exchanges started, across every link: a replugging host can't run the phone's PBKDF2 in a loop. */
    private val started = ArrayDeque<Long>()

    /**
     * Whether another exchange may start now: at most [MAX_STARTS] in any [START_WINDOW_MS], whatever link asks. Counts
     * the one it admits.
     */
    @Synchronized
    fun admit(): Boolean {
        val at = now()
        while (started.isNotEmpty() && at - started.first() >= START_WINDOW_MS) started.removeFirst()
        if (started.size >= MAX_STARTS) return false
        started.addLast(at)
        return true
    }

    /** The live exchange, or null; an expired one is dropped on the way. */
    fun live(): PendingPair? {
        val (current, dropped) =
            synchronized(this) {
                val h = held
                if (h != null && now() >= h.expiresAt) {
                    held = null
                    null to true
                } else {
                    h to false
                }
            }
        if (dropped) onChange(null)
        return current
    }

    /** Holds [pair] unless another live one is held; false when one is. */
    fun hold(pair: PendingPair): Boolean {
        if (live() != null) return false
        synchronized(this) {
            if (held != null) return false
            held = pair
        }
        onChange(pair)
        return true
    }

    /** Ends [pair] if it is still the one held. */
    fun end(pair: PendingPair) {
        val ended = synchronized(this) { (held === pair).also { if (it) held = null } }
        if (ended) onChange(null)
    }

    /** Ends whatever [owner] holds: its link went down. */
    fun endFor(owner: Any) {
        val ended = synchronized(this) { (held?.owner === owner).also { if (it) held = null } }
        if (ended) onChange(null)
    }

    companion object {
        const val MAX_STARTS = 6
        const val START_WINDOW_MS = 10 * 60_000L
    }

    /** Ends any exchange: the person cancelled it on the phone. */
    fun cancel() {
        val ended = synchronized(this) { (held != null).also { held = null } }
        if (ended) onChange(null)
    }
}
