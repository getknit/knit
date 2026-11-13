package app.getknit.knit.usbcontrol

import android.annotation.SuppressLint
import android.app.KeyguardManager
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
import app.getknit.knit.data.blob.BlobVerdictDao
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
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
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
 * on; with it off, an attached accessory is left alone and `open` can pick it up later. One accessory at a time.
 *
 * **Paired computers only.** Before a session a link serves the clear `pair`, `pair.confirm` and `open` and nothing
 * else ([UsbDoor]); a computer pairs with a code the phone shows ([UsbPairActivity]), and its sessions are sealed
 * ([UsbSeal]) under the key the pairing left on both ends ([KeystorePairingStore]). Android's "Open Knit?" prompt still
 * comes first, but it no longer stands guard: "Use by default" lets any host that claims knit-cli through it.
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

    @Volatile private var store: PairingStore? = null

    @Volatile private var pendingPairs: PendingPairs? = null

    /** The paired computers: one store for the process. */
    fun pairings(context: Context): PairingStore =
        store ?: synchronized(lock) { store ?: KeystorePairingStore(context.applicationContext).also { store = it } }

    /** The pair exchange waiting on the phone, shared by the link that asked and [UsbPairActivity]; the notice follows it. */
    fun pending(context: Context): PendingPairs {
        pendingPairs?.let { return it }
        val app = context.applicationContext
        return synchronized(lock) {
            pendingPairs ?: PendingPairs(System::currentTimeMillis) { UsbPairNotice.update(app, it) }.also { pendingPairs = it }
        }
    }

    /**
     * Forgets the paired computers whose handle starts with [prefix] (every one for `all`), and ends a session under
     * one. The count forgotten, or null when the store can't be read or rewritten (nothing is forgotten then).
     */
    fun forget(
        context: Context,
        prefix: String,
    ): Int? {
        val matches = { p: Pairing -> prefix == FORGET_ALL || (prefix.isNotEmpty() && p.handleText.startsWith(prefix)) }
        val done =
            pairings(context).update { held -> if (held.none(matches)) null else held.filterNot(matches) } as? Updated.Done ?: return null
        val gone = done.before.filter(matches)
        if (gone.isEmpty()) return 0
        synchronized(lock) { link?.forgotten(gone.map { it.handle }) }
        note("forgot ${gone.size} paired computer(s)")
        return gone.size
    }

    /** The paired computers as the bridge and the screen list them: never a key. */
    fun pairingsJson(context: Context): JSONObject {
        val list = JSONArray()
        when (val stored = pairings(context).read()) {
            Stored.Unavailable -> {
                return JSONObject().put("unavailable", true)
            }

            is Stored.Ok -> {
                stored.pairings.forEach {
                    list.put(
                        JSONObject()
                            .put("handle", it.handleText.take(HANDLE_PREFIX))
                            .put("name", it.name)
                            .put("pairedAt", it.pairedAt)
                            .put("lastUsedAt", it.lastUsedAt),
                    )
                }
            }
        }
        return JSONObject().put("pairings", list)
    }

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
                .put("pairings", (pairings(context).read() as? Stored.Ok)?.pairings?.size ?: JSONObject.NULL)
                .put("pairPending", pending(context).live() != null)
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
        watch =
            ChangeWatch.launch(
                scope,
                get<SettingsStore>(),
                get<CommonsRepository>(),
                get<BlobVerdictDao>().observeFlaggedHashes(),
                changeLog,
            )
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

    /** What the read loop hands the worker, and the server's own word for it. */
    private sealed interface Inbound {
        class Frame(
            val raw: RawFrame,
        ) : Inbound

        class Framing(
            val reason: String,
        ) : Inbound

        /** These pairings were forgotten on the phone: a session under one of them ends. */
        class Forgotten(
            val handles: List<ByteArray>,
        ) : Inbound
    }

    /**
     * One opened accessory: its streams, its [door] (the clear side: pairing, opening, the lock gate) and the [state]
     * that serves a paired computer's session behind it, its change feed and the two loops that serve them.
     */
    private class Link(
        val generation: Long,
        val accessory: UsbAccessory,
        private val pfd: ParcelFileDescriptor,
        private val app: Context,
        private val scope: CoroutineScope,
    ) {
        private val api = AppUsbControlApi()
        private val feed = ChangeFeed(changeLog, api, System::currentTimeMillis)
        private val feedControl =
            object : FeedControl {
                override fun subscribe(since: ChangeCursor?): SubscribeResult = feed.start(since)

                override fun unsubscribe() = feed.stop()
            }
        private val keyguard = app.getSystemService(KeyguardManager::class.java)
        val door =
            UsbDoor(
                store = pairings(app),
                pending = pending(app),
                enabled = { enabled(app) },
                deviceLocked = { keyguard.isDeviceLocked },
                now = System::currentTimeMillis,
            )
        private val state =
            UsbLinkState(door, newSession = { UsbControlSession(api, feedControl, ledger) }, onSessionEnd = { reason ->
                feed.stop()
                Log.i(TAG, "usb control session ended (generation $generation): $reason")
            })
        private val input = FileInputStream(pfd.fileDescriptor)
        private val output = FileOutputStream(pfd.fileDescriptor)
        private val inbound = Channel<Inbound>(REQUEST_BUFFER)
        private val control = Channel<Inbound>(Channel.UNLIMITED)
        private val statusSignal = Channel<Unit>(Channel.CONFLATED)
        private val jobs = mutableListOf<Job>()
        private val openedAt = System.currentTimeMillis()
        private val framesIn = AtomicLong()
        private val framesOut = AtomicLong()
        private val bytesIn = AtomicLong()
        private val bytesOut = AtomicLong()
        private val eventsOut = AtomicLong()

        @Volatile private var closed = false

        private val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        Intent.ACTION_USER_PRESENT -> {
                            door.userPresent()
                        }

                        UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                            val gone = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                            if (gone == null || gone == accessory) down(this@Link, "detached")
                        }
                    }
                }
            }

        fun start() {
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            jobs += scope.launch(Dispatchers.IO) { readLoop() }
            jobs += scope.launch(Dispatchers.IO) { workLoop() }
            jobs += scope.launch { api.statusChanges().collect { statusSignal.trySend(Unit) } }
        }

        fun shutdown() {
            closed = true
            runCatching { app.unregisterReceiver(receiver) }
            inbound.close()
            control.close()
            jobs.forEach { it.cancel() }
            runCatching { pfd.close() }
        }

        /** Hands the worker the handles just forgotten, so a session under one of them ends. */
        fun forgotten(handles: List<ByteArray>) {
            control.trySend(Inbound.Forgotten(handles))
        }

        fun stateJson(): JSONObject =
            JSONObject()
                .put("generation", generation)
                .put("accessory", "${accessory.manufacturer}/${accessory.model} ${accessory.version} ${accessory.serial}")
                .put("sealed", state.sessionUp)
                .put("unlockedSinceAttach", door.unlocked)
                .put("client", state.client ?: JSONObject.NULL)
                .put("lastSessionEnd", state.lastSessionEnd ?: JSONObject.NULL)
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

        /** The server's word first, then requests, then the change feed: each frame built and written before the next. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun workLoop() {
            val logSignal = changeLog.listen()
            try {
                while (!closed) {
                    val due = feed.nextDueAt()
                    select {
                        control.onReceive { serve(it) }
                        inbound.onReceive { serve(it) }
                        if (feed.active) {
                            logSignal.onReceive { feed.pull() }
                            statusSignal.onReceive { feed.touch(ChangeKey.Status) }
                        }
                        if (due != null) {
                            onTimeout((due - System.currentTimeMillis()).coerceAtLeast(0)) { sendDue() }
                        }
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                // Shut down: the read loop or the detach has already said why.
            } catch (e: IOException) {
                down(this, "write: ${e.message}")
            } finally {
                changeLog.unlisten(logSignal)
                state.close()
            }
        }

        private suspend fun sendDue() {
            for (event in feed.runDue()) {
                // An object over the cap goes as its stub (FrameBudget.stub), as a list reply's would; an event that
                // is still over it is dropped rather than taking the session down.
                val bytes = state.event(event) ?: state.event(stubbed(event))
                if (bytes == null) {
                    if (state.sessionUp) Log.w(TAG, "usb control event over the frame cap, dropped")
                } else {
                    write(bytes)
                    eventsOut.incrementAndGet()
                }
            }
        }

        private suspend fun serve(item: Inbound) {
            val out =
                when (item) {
                    is Inbound.Frame -> state.frame(item.raw)
                    is Inbound.Framing -> state.framing(item.reason)
                    is Inbound.Forgotten -> state.forgotten(item.handles)
                }
            out.forEach(::write)
        }

        private fun write(bytes: ByteArray) {
            output.write(bytes)
            framesOut.incrementAndGet()
            bytesOut.addAndGet(bytes.size.toLong())
        }

        private fun stubbed(event: JsonObject): JsonObject =
            JsonObject(event.mapValues { (key, value) -> if (key != "event" && value is JsonObject) FrameBudget.stub(value) else value })
    }

    const val FORGET_ALL = "all"

    /** How much of a handle the bridge and the screen show: enough to tell two apart, and to forget one by. */
    const val HANDLE_PREFIX = 8
    private const val READ_BUFFER = 16 * 1024
    private const val REQUEST_BUFFER = 8
    private const val EPOCH_BYTES = 8
}
