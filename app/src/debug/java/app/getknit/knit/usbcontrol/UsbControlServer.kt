package app.getknit.knit.usbcontrol

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone's end of the USB control spike (debug builds only): serves [UsbControlSession] over an Android Open
 * Accessory stream from knit-cli. Off until the bridge's `USBCTL --es op enable` turns it on; with it off, an attached
 * accessory is left alone and `open` can pick it up later. Android's own "Open Knit?" prompt is the only gate: no
 * pairing, no host key. One accessory at a time.
 *
 * The streams follow `gmrs/GmrsRadio`: a read loop on IO feeding a deframer, one writer draining a small reply queue
 * ahead of an event queue that drops its oldest, and a generation check so a link that went down can't close its
 * successor. A host that stops reading costs one IO thread blocked in the driver's write and nothing more: events
 * are dropped, and once the reply queue is full the read loop waits, so the host's own writes stall. The next host
 * run reads the queued frames (its hello drops them) and the writer goes on; nothing times the link out, since a
 * closed accessory can't be reopened without a replug. A read blocked in the accessory driver doesn't return on
 * close; the detach broadcast or the next read ends it.
 */
internal object UsbControlServer : KoinComponent {
    const val TAG = "KnitUsb"
    const val MANUFACTURER = "getknit"
    const val MODEL = "knit-cli"
    private const val PREFS = "knit_usb_control"
    private const val KEY_ENABLED = "enabled"

    private val scope: CoroutineScope by inject()
    private val lock = Any()

    // Holds the application context only (Link.app), which lives as long as the process.
    @SuppressLint("StaticFieldLeak")
    private var link: Link? = null

    private var generation = 0L

    @Volatile private var lastClose: String? = null

    @Volatile private var lastError: String? = null

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(
        context: Context,
        on: Boolean,
    ) {
        prefs(context).edit { putBoolean(KEY_ENABLED, on) }
        if (!on) close("disabled")
    }

    /**
     * The accessory activity's door: knit-cli just switched the phone into accessory mode and the user said yes. The
     * activity is exported and its extra is whatever the launcher put there, so [extra] only names which accessory to
     * look for: what opens is the one the system holds as attached, and only with Knit's grant for it.
     */
    fun onAttached(
        context: Context,
        extra: UsbAccessory,
    ): String {
        if (!isOurs(extra)) return note("ignored accessory ${extra.manufacturer}/${extra.model}")
        if (!enabled(context)) return note("knit-cli attached; USB control is off (USBCTL --es op enable, then open)")
        return openAttached(context, extra)
    }

    /**
     * Opens the knit-cli accessory the system holds as attached ([wanted] narrows it to that one), only if Knit holds
     * the grant for it: the attach prompt's, which also stands for one attached before the switch went on.
     */
    fun openAttached(
        context: Context,
        wanted: UsbAccessory? = null,
    ): String {
        val usb = context.getSystemService(UsbManager::class.java)
        val accessory =
            usb.accessoryList?.firstOrNull { isOurs(it) && (wanted == null || it == wanted) }
                ?: return note("no matching knit-cli accessory attached")
        if (!usb.hasPermission(accessory)) return note("no permission: replug the cable and accept the prompt")
        return open(context, accessory)
    }

    fun close(reason: String) {
        synchronized(lock) { closeLocked(reason) }
    }

    fun state(context: Context): JSONObject =
        synchronized(lock) {
            val l = link
            JSONObject()
                .put("enabled", enabled(context))
                .put("open", l != null)
                .put("generation", generation)
                .put("lastClose", lastClose ?: JSONObject.NULL)
                .put("lastError", lastError ?: JSONObject.NULL)
                .also { json -> l?.let { json.put("link", it.stateJson()) } }
        }

    @Suppress("TooGenericExceptionCaught") // openAccessory refuses by throwing; a refusal must not crash the app
    private fun open(
        context: Context,
        accessory: UsbAccessory,
    ): String {
        val app = context.applicationContext
        val usb = app.getSystemService(UsbManager::class.java)
        synchronized(lock) {
            link?.let { if (it.accessory == accessory) return "already open" else closeLocked("replaced") }
            val pfd =
                try {
                    usb.openAccessory(accessory)
                } catch (e: RuntimeException) {
                    // SecurityException without the grant, IllegalArgumentException for one no longer attached.
                    lastError = "openAccessory: ${e.javaClass.simpleName}: ${e.message}"
                    null
                } ?: return note("openAccessory gave nothing (${lastError ?: "busy: a stuck write may hold it; replug"})")
            val l = Link(++generation, accessory, pfd, app, scope)
            link = l
            l.start()
            return note("open (generation ${l.generation}, ${accessory.description} ${accessory.version})")
        }
    }

    private fun down(
        l: Link,
        reason: String,
    ) {
        synchronized(lock) { if (link === l) closeLocked(reason) }
    }

    private fun closeLocked(reason: String) {
        val l = link ?: return
        link = null
        lastClose = reason
        l.shutdown()
        Log.i(TAG, "usb control closed (generation ${l.generation}): $reason")
    }

    private fun isOurs(accessory: UsbAccessory): Boolean = accessory.manufacturer == MANUFACTURER && accessory.model == MODEL

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun note(message: String): String = message.also { Log.i(TAG, it) }

    /** One opened accessory: its streams, its session and the loops that serve it. */
    private class Link(
        val generation: Long,
        val accessory: UsbAccessory,
        private val pfd: ParcelFileDescriptor,
        private val app: Context,
        private val scope: CoroutineScope,
    ) {
        private val api = AppUsbControlApi()
        private val session = UsbControlSession(api)
        private val input = FileInputStream(pfd.fileDescriptor)
        private val output = FileOutputStream(pfd.fileDescriptor)
        private val replies = Channel<ByteArray>(REPLY_BUFFER)
        private val events = Channel<ByteArray>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)
        private val jobs = mutableListOf<Job>()
        private val openedAt = System.currentTimeMillis()
        private val framesIn = AtomicLong()
        private val framesOut = AtomicLong()
        private val bytesIn = AtomicLong()
        private val bytesOut = AtomicLong()
        private val eventsQueued = AtomicLong()

        @Volatile private var eventJob: Job? = null

        @Volatile private var closed = false

        private val detach =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val gone = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                    if (gone == null || gone == accessory) down(this@Link, "detached")
                }
            }

        fun start() {
            ContextCompat.registerReceiver(
                app,
                detach,
                IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            jobs += scope.launch(Dispatchers.IO) { readLoop() }
            jobs += scope.launch(Dispatchers.IO) { writeLoop() }
        }

        fun shutdown() {
            closed = true
            runCatching { app.unregisterReceiver(detach) }
            eventJob?.cancel()
            replies.close()
            events.close()
            jobs.forEach { it.cancel() }
            runCatching { pfd.close() }
        }

        fun stateJson(): JSONObject =
            JSONObject()
                .put("generation", generation)
                .put("accessory", "${accessory.manufacturer}/${accessory.model} ${accessory.version} ${accessory.serial}")
                .put("client", session.client ?: JSONObject.NULL)
                .put("subscribed", session.subscribed)
                .put("openedAt", openedAt)
                .put("framesIn", framesIn.get())
                .put("framesOut", framesOut.get())
                .put("bytesIn", bytesIn.get())
                .put("bytesOut", bytesOut.get())
                .put("eventsQueued", eventsQueued.get())

        private suspend fun readLoop() {
            val deframer = UsbControlDeframer()
            val buf = ByteArray(READ_BUFFER)
            val reason =
                try {
                    var n = input.read(buf)
                    while (n >= 0 && !closed) {
                        if (n > 0) {
                            bytesIn.addAndGet(n.toLong())
                            serve(deframer, buf, n)
                        }
                        n = input.read(buf)
                    }
                    "end of stream"
                } catch (e: IOException) {
                    "read: ${e.message}"
                } catch (e: ClosedSendChannelException) {
                    "closed: ${e.message}"
                }
            down(this, reason)
        }

        private suspend fun serve(
            deframer: UsbControlDeframer,
            buf: ByteArray,
            n: Int,
        ) {
            val frames =
                try {
                    deframer.feed(buf, 0, n)
                } catch (e: IOException) {
                    Log.w(TAG, "usb control framing: ${e.message}")
                    deframer.reset()
                    replies.send(UsbControlFrames.encode(UsbControlSession.framingError(e.message.orEmpty()).toString()))
                    return
                }
            for (frame in frames) {
                framesIn.incrementAndGet()
                val reply = session.handle(frame)
                // Suspends while the writer is stuck behind a host that stopped reading: the read loop stops taking
                // requests, so the host's own writes stall instead of this queue growing.
                replies.send(frameOf(reply, reply["id"] ?: JsonNull))
                followSubscription()
            }
        }

        private fun followSubscription() {
            if (session.subscribed && eventJob == null) {
                eventJob =
                    scope.launch {
                        api.messageEvents().collect { event ->
                            val bytes = runCatching { UsbControlFrames.encode(event.toString()) }.getOrNull()
                            if (bytes == null) {
                                Log.w(TAG, "usb control event over the frame cap, dropped")
                            } else if (events.trySend(bytes).isSuccess) {
                                eventsQueued.incrementAndGet()
                            }
                        }
                    }
            } else if (!session.subscribed) {
                eventJob?.cancel()
                eventJob = null
            }
        }

        private suspend fun writeLoop() {
            try {
                while (!closed) {
                    // Replies first: select prefers its first clause. Both queues close together at shutdown.
                    val bytes =
                        select<ByteArray?> {
                            replies.onReceiveCatching { it.getOrNull() }
                            events.onReceiveCatching { it.getOrNull() }
                        } ?: return
                    output.write(bytes)
                    framesOut.incrementAndGet()
                    bytesOut.addAndGet(bytes.size.toLong())
                }
            } catch (e: IOException) {
                down(this, "write: ${e.message}")
            }
        }

        /** A reply as a frame, or, past the cap, an error asking for less. */
        private fun frameOf(
            reply: JsonObject,
            id: JsonElement,
        ): ByteArray =
            try {
                UsbControlFrames.encode(reply.toString())
            } catch (e: IOException) {
                Log.w(TAG, "usb control reply refused", e)
                UsbControlFrames.encode(
                    buildJsonObject {
                        put("id", id)
                        put("ok", false)
                        put("error", "reply over the ${UsbControlFrames.MAX_FRAME}-byte frame cap; ask for less")
                    }.toString(),
                )
            }
    }

    private const val READ_BUFFER = 16 * 1024
    private const val REPLY_BUFFER = 8
    private const val EVENT_BUFFER = 64
}
