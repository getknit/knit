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
import app.getknit.knit.data.changes.DataChangeSink
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone's end of the USB control (debug builds only): serves [UsbControlSession] over an Android Open Accessory
 * stream from knit-cli, protocol v1 (`docs/USB_CONTROL.md`). Off until the bridge's `USBCTL --es op enable` turns it
 * on; with it off, an attached accessory is left alone and `open` can pick it up later. Android's own "Open Knit?"
 * prompt is the only gate: no pairing, no host key. One accessory at a time.
 *
 * It also owns the [changeLog] the repositories report their writes to, kept only while USB control is on.
 *
 * **One worker per link.** The read loop only deframes, into a request queue of [REQUEST_BUFFER]. One worker takes a
 * request first, else the change feed's due work, builds the frame and writes it before taking the next: so for any
 * object a later frame never carries older state than an earlier one. A host that stops reading blocks the worker in
 * the driver's write; the request queue fills, the read loop waits, and the host's own writes stall. The next host run
 * reads the queued frames (its hello drops them) and the worker goes on; nothing times the link out, since a closed
 * accessory can't be reopened without a replug. A read blocked in the accessory driver doesn't return on close; the
 * detach broadcast or the next read ends it.
 */
internal object UsbControlServer : KoinComponent {
    const val TAG = "KnitUsb"
    const val MANUFACTURER = "getknit"
    const val MODEL = "knit-cli"
    private const val PREFS = "knit_usb_control"
    private const val KEY_ENABLED = "enabled"

    private val scope: CoroutineScope by inject()
    private val lock = Any()
    private val random = SecureRandom()

    /** The sends every link has made, by `clientMsgId`, and the budget they share: it outlives a link on purpose. */
    private val ledger = SendLedger(System::currentTimeMillis)

    /** Every write the repositories report, numbered, while USB control is on: what each session's feed reads. */
    val changeLog = ChangeLog { ByteArray(EPOCH_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(Locale.ROOT, it) } }

    // Holds the application context only (Link.app), which lives as long as the process.
    @SuppressLint("StaticFieldLeak")
    private var link: Link? = null

    private var generation = 0L
    private var watch: Job? = null

    @Volatile private var lastClose: String? = null

    @Volatile private var lastError: String? = null

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** The repositories' change sink (`di/DataChangeWiring`): the [changeLog], recording from now if USB control is on. */
    fun changeSink(context: Context): DataChangeSink {
        changeLog.setRecording(enabled(context))
        return changeLog.sink
    }

    fun setEnabled(
        context: Context,
        on: Boolean,
    ) {
        prefs(context).edit { putBoolean(KEY_ENABLED, on) }
        changeLog.setRecording(on)
        if (!on) {
            close("disabled")
            synchronized(lock) {
                watch?.cancel()
                watch = null
            }
        }
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
            val cursor = changeLog.latest()
            JSONObject()
                .put("enabled", enabled(context))
                .put("open", l != null)
                .put("generation", generation)
                .put("recording", changeLog.recording)
                .put("log", "${cursor.epoch}:${cursor.seq}")
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
            startWatchLocked()
            val l = Link(++generation, accessory, pfd, app, scope)
            link = l
            l.start()
            return note("open (generation ${l.generation}, ${accessory.description} ${accessory.version})")
        }
    }

    /** The settings half of the change log, from the first link on: before one, no session has a cursor to keep. */
    private fun startWatchLocked() {
        if (watch != null || !changeLog.recording) return
        watch = ChangeWatch.launch(scope, get<SettingsStore>(), get<CommonsRepository>(), changeLog)
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

    /** What the read loop hands the worker. */
    private sealed interface Inbound {
        class Frame(
            val text: String,
        ) : Inbound

        class Framing(
            val reason: String,
        ) : Inbound
    }

    /** One opened accessory: its streams, its session, its change feed and the two loops that serve them. */
    private class Link(
        val generation: Long,
        val accessory: UsbAccessory,
        private val pfd: ParcelFileDescriptor,
        private val app: Context,
        private val scope: CoroutineScope,
    ) {
        private val api = AppUsbControlApi()
        private val feed = ChangeFeed(changeLog, api, System::currentTimeMillis)
        private val session =
            UsbControlSession(
                api,
                object : FeedControl {
                    override fun subscribe(since: ChangeCursor?): SubscribeResult = feed.start(since)

                    override fun unsubscribe() = feed.stop()
                },
                ledger,
            )
        private val input = FileInputStream(pfd.fileDescriptor)
        private val output = FileOutputStream(pfd.fileDescriptor)
        private val inbound = Channel<Inbound>(REQUEST_BUFFER)
        private val statusSignal = Channel<Unit>(Channel.CONFLATED)
        private val jobs = mutableListOf<Job>()
        private val openedAt = System.currentTimeMillis()
        private val framesIn = AtomicLong()
        private val framesOut = AtomicLong()
        private val bytesIn = AtomicLong()
        private val bytesOut = AtomicLong()
        private val eventsOut = AtomicLong()

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
            jobs += scope.launch(Dispatchers.IO) { workLoop() }
            jobs += scope.launch { api.statusChanges().collect { statusSignal.trySend(Unit) } }
        }

        fun shutdown() {
            closed = true
            runCatching { app.unregisterReceiver(detach) }
            inbound.close()
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
                .put("eventsOut", eventsOut.get())

        private suspend fun readLoop() {
            val deframer = UsbControlDeframer()
            val buf = ByteArray(READ_BUFFER)
            val reason =
                try {
                    var n = input.read(buf)
                    while (n >= 0 && !closed) {
                        if (n > 0) {
                            bytesIn.addAndGet(n.toLong())
                            deframe(deframer, buf, n)
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

        private suspend fun deframe(
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
                    inbound.send(Inbound.Framing(e.message.orEmpty()))
                    return
                }
            for (frame in frames) {
                framesIn.incrementAndGet()
                // Suspends while the worker is stuck behind a host that stopped reading: the read loop stops taking
                // requests, so the host's own writes stall instead of this queue growing.
                inbound.send(Inbound.Frame(frame))
            }
        }

        /** Requests first, then the change feed: each frame built and written before the next is taken. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun workLoop() {
            val logSignal = changeLog.listen()
            try {
                while (!closed) {
                    val due = feed.nextDueAt()
                    select {
                        inbound.onReceive { serve(it) }
                        if (feed.active) {
                            logSignal.onReceive { feed.pull() }
                            statusSignal.onReceive { feed.touch(ChangeKey.Status) }
                        }
                        if (due != null) {
                            onTimeout((due - System.currentTimeMillis()).coerceAtLeast(0)) {
                                feed.runDue().forEach { event ->
                                    // An object over the cap goes as its stub (FrameBudget.stub), as a list reply's
                                    // would; an event that is still over it is dropped rather than taking the link down.
                                    val bytes =
                                        runCatching { UsbControlFrames.encode(event.toString()) }.getOrNull()
                                            ?: runCatching { UsbControlFrames.encode(stubbed(event).toString()) }.getOrNull()
                                    if (bytes == null) {
                                        Log.w(TAG, "usb control event over the frame cap, dropped")
                                    } else {
                                        write(bytes)
                                        eventsOut.incrementAndGet()
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                // Shut down: the read loop or the detach has already said why.
            } catch (e: IOException) {
                down(this, "write: ${e.message}")
            } finally {
                changeLog.unlisten(logSignal)
            }
        }

        private suspend fun serve(item: Inbound) {
            val reply =
                when (item) {
                    is Inbound.Frame -> session.handle(item.text)
                    is Inbound.Framing -> UsbControlSession.framingError(item.reason)
                }
            write(frameOf(reply, reply["id"] ?: JsonNull))
        }

        private fun write(bytes: ByteArray) {
            output.write(bytes)
            framesOut.incrementAndGet()
            bytesOut.addAndGet(bytes.size.toLong())
        }

        private fun stubbed(event: JsonObject): JsonObject =
            JsonObject(event.mapValues { (key, value) -> if (key != "event" && value is JsonObject) FrameBudget.stub(value) else value })

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
                        put("code", Codes.INTERNAL)
                        put("error", "reply over the ${UsbControlFrames.MAX_FRAME}-byte frame cap; ask for less")
                    }.toString(),
                )
            }
    }

    private const val READ_BUFFER = 16 * 1024
    private const val REQUEST_BUFFER = 8
    private const val EPOCH_BYTES = 8
}
