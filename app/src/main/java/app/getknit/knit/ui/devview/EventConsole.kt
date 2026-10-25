package app.getknit.knit.ui.devview

import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.fieldlog.FieldTap
import app.getknit.knit.fieldlog.FieldWriter
import app.getknit.knit.mesh.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One event the console holds: the field log's own event and the wall-clock time it was recorded. */
data class ConsoleEvent(
    val at: Long,
    val event: FieldEvent,
)

/**
 * The developer view's event console (ADR 2026-10.s7wg, its slice-2 amendment): the newest [capacity] events the mesh
 * recorded, in memory only, while the view is on. It is the field log's [FieldTap], so it sees each event as recorded,
 * whether or not the field log is switched on. It keeps nothing while the view is off, and switching it off empties it.
 * Nothing here is written, shared or exported.
 *
 * [saw] runs on whatever thread recorded the event, often a radio's, so it does constant work and copies nothing:
 * it moves [version], and a reader takes its [snapshot] when it wants one.
 */
class EventConsole(
    private val capacity: Int = CAPACITY,
) : FieldTap {
    private val lock = Any()
    private val ring = ArrayDeque<ConsoleEvent>()
    private var active = false
    private val _version = MutableStateFlow(0L)

    /** Moves on every change; a reader takes a [snapshot] on it. */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** Follows the developer view's switch on [scope] for the process. */
    fun follow(
        on: Flow<Boolean>,
        scope: CoroutineScope,
    ) {
        scope.launch { on.distinctUntilChanged().collect(::setActive) }
    }

    /** On keeps what comes next; off empties what was kept and keeps nothing more. */
    fun setActive(on: Boolean) {
        synchronized(lock) {
            active = on
            if (!on) ring.clear()
            _version.value++
        }
    }

    override fun saw(
        at: Long,
        event: FieldEvent,
    ) {
        synchronized(lock) {
            if (!active) return
            if (ring.size >= capacity) ring.removeLast()
            ring.addFirst(ConsoleEvent(at, event))
            // Under the lock, so two radios recording at once cannot both write the same next version and leave a
            // reader one event behind.
            _version.value++
        }
    }

    /** What is kept now, newest first. */
    fun snapshot(): List<ConsoleEvent> = synchronized(lock) { ring.toList() }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            _version.value++
        }
    }

    companion object {
        const val CAPACITY = 2_000
    }
}

/** The console's filter: which part of the mesh an event is about, by the code word the HUD uses for it. */
enum class ConsolePlane(
    val label: String,
) {
    Bluetooth("BLE"),
    WifiAware("NAN"),
    LoRa("LoRa"),
    Relay("relay"),
    Calls("calls"),
    Mesh("mesh"),
    ;

    companion object {
        /** The plane [event] is about: a radio status by its radio, everything else by its kind's prefix. */
        fun of(event: FieldEvent): ConsolePlane =
            when {
                event is FieldEvent.Radio -> (event.radio as? TransportKind)?.let(::ofRadio) ?: Mesh
                event.kind.startsWith("bt_") -> Bluetooth
                event.kind.startsWith("nan_") || event.kind == "aware_switch" -> WifiAware
                event.kind.startsWith("lora") -> LoRa
                event.kind.startsWith("spool") || event.kind == "net" -> Relay
                event.kind.startsWith("call_") || event.kind == "talk" || event.kind == "walkie_end" -> Calls
                else -> Mesh
            }

        private fun ofRadio(kind: TransportKind): ConsolePlane =
            when (kind) {
                TransportKind.Bluetooth -> Bluetooth
                TransportKind.WifiAware -> WifiAware
                TransportKind.LoRa -> LoRa
                TransportKind.Other -> Mesh
            }
    }
}

/**
 * Renders a field event's fields for the console, as `name=value` pairs in the order the event writes them. It is a
 * [FieldWriter] of its own, so the field log's writer and its redaction are untouched: here a peer reads by [label]
 * and a relay by its host, on this phone's screen only. Deliberately English, like the rest of the view's rows.
 */
object ConsoleLine {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun fields(
        event: FieldEvent,
        label: (nodeId: String) -> String,
    ): String {
        val out = StringBuilder()
        event.write(
            object : FieldWriter {
                override fun num(
                    name: String,
                    value: Long,
                ) {
                    pair(name, value.toString())
                }

                override fun flag(
                    name: String,
                    value: Boolean,
                ) {
                    pair(name, value.toString())
                }

                override fun enum(
                    name: String,
                    value: Enum<*>,
                ) {
                    pair(name, value.name)
                }

                override fun peer(
                    name: String,
                    nodeId: String,
                ) {
                    pair(name, label(nodeId))
                }

                override fun relay(
                    name: String,
                    url: String,
                ) {
                    pair(name, host(url))
                }

                private fun pair(
                    name: String,
                    value: String,
                ) {
                    if (out.isNotEmpty()) out.append(' ')
                    out.append(name).append('=').append(value)
                }
            },
        )
        return out.toString()
    }

    /** A relay's host, from its redacted URL; the URL itself if it has none. */
    fun host(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

    /** A peer as the console shows it: the reader's label for it, then the start of its node id. */
    fun peer(
        label: String,
        nodeId: String,
    ): String = "$label (${nodeId.take(ID_CHARS)})"

    /** The time of day an event was recorded, to the millisecond, in [zone]. */
    fun time(
        at: Long,
        zone: ZoneId,
    ): String = TIME.format(Instant.ofEpochMilli(at).atZone(zone))

    private const val ID_CHARS = 8
}
