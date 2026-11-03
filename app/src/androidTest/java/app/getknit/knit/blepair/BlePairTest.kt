package app.getknit.knit.blepair

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshService
import app.getknit.knit.mesh.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import org.koin.core.context.GlobalContext
import java.io.File
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The plumbing every BLE pair class shares. Two emulators mesh over **real Bluetooth** and nothing else: each one's
 * host stack drives one of knit-ios's nRF52840 dongles through `scripts/hci-bridge.py --isolate`, which keeps the
 * pair from hearing the lab phones (and them from hearing the pair), so every frame a test waits on crossed real air
 * between two real controllers. `scripts/ble-pair-test.sh` boots the pair, installs, and runs the classes on both
 * emulators at once, in the same order.
 *
 * Both sides run the same methods and each asserts what it sees itself; they meet through the mesh (each waits for
 * the other's message), and every test ends in a [rendezvous], so neither runs ahead into the next one — or out of
 * the run: `am instrument` kills the app when its last test returns, and a side that left first took the link, and
 * the ✓✓ it still owed, with it (the first run, 2026-10-05). Each class then lingers [LINGER_MS] before it returns,
 * so the last frames either side sent get out. Arguments, from `am instrument -e`:
 * - `knitBlePeer` — the other emulator's node id. Without it every class skips, so the ordinary
 *   `connectedDebugAndroidTest` and Firebase Test Lab runs never wait on a peer that isn't there.
 * - `knitBleRun` — a tag unique to the run, in every body, so a run never matches an older run's rows.
 * - `knitBleBridge` — the guest-side port `adb reverse` maps to this emulator's bridge control (cuts and deafness).
 *
 * A test that does something one-sided gives it to one side by id ([isSmaller]): the smaller id never dials, so a
 * fault it makes is healed by the other side's ordinary dial.
 */
abstract class BlePairTest {
    private val args = InstrumentationRegistry.getArguments()
    protected val context = InstrumentationRegistry.getInstrumentation().targetContext
    protected val peer: String = args.getString(ARG_PEER).orEmpty()
    protected val run: String = args.getString(ARG_RUN) ?: "adhoc"
    protected val bridgePort: Int? = args.getString(ARG_BRIDGE)?.toIntOrNull()

    protected val mesh: MeshController by lazy { GlobalContext.get().get() }
    protected val messages: MessageRepository by lazy { GlobalContext.get().get() }
    protected val blobs: BlobRepository by lazy { GlobalContext.get().get() }
    protected val attachments: AttachmentStore by lazy { GlobalContext.get().get() }
    protected val peers: PeerRepository by lazy { GlobalContext.get().get() }
    protected val groups: GroupRepository by lazy { GlobalContext.get().get() }
    protected val reactions: ReactionRepository by lazy { GlobalContext.get().get() }
    protected val settings: SettingsStore by lazy { GlobalContext.get().get() }
    protected val avatars: AvatarStore by lazy { GlobalContext.get().get() }
    private val identity: Identity by lazy { GlobalContext.get().get() }

    @get:Rule
    val name = TestName()

    protected lateinit var self: String

    /** Work that lasts the whole test, rendezvous included (a typing loop): cancelled after it. */
    protected val watch = CoroutineScope(Dispatchers.Default + Job())
    private val strangers = ConcurrentHashMap.newKeySet<String>()

    /** True on the side with the smaller node id, the one that never dials. */
    protected val isSmaller: Boolean get() = self < peer

    @Before
    fun startTheMesh() {
        assumeTrue("not a BLE pair run (no $ARG_PEER argument)", peer.isNotEmpty())
        assumeFalse("a seeded build fakes its peers", BuildConfig.SEED_DEMO)
        // Once the pair has lost each other, every later test would sit out its own link timeout and then the
        // rendezvous's; one failure says it, and the rest skip.
        assumeTrue("the pair lost each other in ${lostIn.orEmpty()}", lostIn == null)
        // `am instrument` restarts the process, so the service the launch started is gone; the runner exempts the
        // battery optimisation (ble-pair-test.sh), which is what lets an off-screen start claim the foreground.
        assertTrue("MeshService.start refused (is Knit battery-exempt?)", MeshService.start(context))
        self = runBlocking { identity.nodeId() }
        // "Nobody else" holds for the whole test, not one instant of it: anyone besides the peer the mesh ever
        // counts as nearby fails it below.
        watch.launch { mesh.neighbors.collect { set -> set.forEach { if (it.nodeId != peer) strangers += it.nodeId } } }
    }

    @After
    fun meetThePeerThenCheckNoStrangerWasNearby() {
        try {
            if (::self.isInitialized && lostIn == null) {
                val step = "${this::class.simpleName}.${name.methodName} end"
                runCatching { runBlocking { rendezvous(step) } }.onFailure {
                    lostIn = step
                    throw it
                }
            }
        } finally {
            watch.cancel()
        }
        assertTrue("the pair heard a node besides its peer; isolation leaked: $strangers", strangers.isEmpty())
    }

    protected fun body(what: String) = "$TAG $run $what"

    /** Sends [what] (tagged) to the peer as a DM; the text it went out as. */
    protected suspend fun sendDm(what: String): String {
        val text = body(what)
        assertTrue("sendChat refused the DM '$what'", mesh.sendChat(text, recipientId = peer))
        return text
    }

    /** The peer nearby and a Bluetooth link up: the mesh can carry a frame to it now. */
    protected suspend fun awaitLink() {
        runCatching {
            withTimeout(LINK_TIMEOUT_MS) {
                mesh.neighbors.first { set -> set.any { it.nodeId == peer } }
                mesh.transportStatuses.first { s -> s.any { it.kind == TransportKind.Bluetooth && it.linked > 0 } }
            }
        }.onFailure {
            lostIn = "${this::class.simpleName}.${name.methodName}"
            throw AssertionError(
                "no Bluetooth link to $peer within $LINK_TIMEOUT_MS ms; nearby ${mesh.neighbors.value}, " +
                    "radios ${mesh.transportStatuses.value}",
                it,
            )
        }
    }

    /** The first row of [conversation] (its newest window) that [match] accepts, within [timeoutMs]. */
    protected suspend fun awaitRow(
        conversation: String,
        timeoutMs: Long,
        what: String,
        match: (MessageEntity) -> Boolean,
    ): MessageEntity {
        val found =
            runCatching {
                withTimeout(timeoutMs) {
                    messages.observeNewestMessages(conversation, WINDOW).first { rows -> rows.any(match) }.first(match)
                }
            }
        return found.getOrElse {
            val rows = messages.observeNewestMessages(conversation, WINDOW).first()
            val seen = rows.joinToString("\n") { row -> "  ${row.senderId.take(8)} received=${row.received} ${row.body.take(80)}" }
            throw AssertionError("no $what within $timeoutMs ms; $conversation holds:\n$seen", it)
        }
    }

    /** The peer's DM whose body is [what] (tagged), within [timeoutMs]. */
    protected suspend fun awaitPeerDm(
        what: String,
        timeoutMs: Long = DM_TIMEOUT_MS,
    ): MessageEntity = awaitRow(peer, timeoutMs, "the peer's '$what'") { it.senderId == peer && it.body == body(what) }

    /** Our own DM [text] once its ✓✓ is home: the peer stored it and said so. */
    protected suspend fun awaitTick(
        text: String,
        timeoutMs: Long = DM_TIMEOUT_MS,
    ): MessageEntity = awaitRow(peer, timeoutMs, "the ✓✓ on '$text'") { it.senderId == self && it.body == text && it.received }

    /** Waits until the blob [hash] is held here (fetched, screened and stored). */
    protected suspend fun awaitHeld(
        hash: String,
        timeoutMs: Long = ATTACHMENT_TIMEOUT_MS,
    ) {
        runCatching { withTimeout(timeoutMs) { while (!blobs.exists(hash)) delay(POLL_MS) } }
            .onFailure { throw AssertionError("blob $hash not held within $timeoutMs ms", it) }
    }

    /** Both sides past [step]: each says so in the DM thread and waits to hear the other say it. */
    protected suspend fun rendezvous(step: String) {
        sendDm("$step done by $self")
        awaitPeerDm("$step done by $peer")
    }

    /** One request to this emulator's bridge control (a JSON line each way), over the port `adb reverse` maps. */
    protected suspend fun bridge(request: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            Socket("127.0.0.1", checkNotNull(bridgePort) { "no $ARG_BRIDGE argument" }).use { socket ->
                socket.soTimeout = BRIDGE_TIMEOUT_MS
                socket.getOutputStream().write("$request\n".toByteArray())
                JSONObject(socket.getInputStream().bufferedReader().readLine())
            }
        }

    /** A square of noise, so no encoder can shrink it to nothing; the same [seed] draws the same picture. */
    protected fun noiseBitmap(
        side: Int,
        seed: String,
    ): Bitmap {
        val random = Random(seed.hashCode())
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        for (y in 0 until side) {
            for (x in 0 until side) bitmap.setPixel(x, y, Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
        }
        return bitmap
    }

    /** [noiseBitmap] as a JPEG file the app can read, for a door that takes a Uri. */
    protected fun noisePhoto(
        side: Int,
        seed: String,
    ): File {
        val bitmap = noiseBitmap(side, seed)
        val file = File(context.cacheDir, "ble-pair-$seed.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        return file
    }

    protected fun note(line: String) = Log.i(TAG, "$run ${self.take(8)} ${name.methodName}: $line")

    companion object {
        /** The test the pair stopped hearing each other in (no link, or a rendezvous that never came back), if any. */
        @Volatile
        private var lostIn: String? = null

        /** Keeps the process, so the mesh, up past the last rendezvous until its ✓✓ and frames are out. */
        @JvmStatic
        @AfterClass
        fun lingerForTheLastFrames() {
            val pairRun = InstrumentationRegistry.getArguments().getString(ARG_PEER) != null
            if (pairRun && lostIn == null) runBlocking { delay(LINGER_MS) }
        }

        internal const val TAG = "BlePair"
        internal const val ARG_PEER = "knitBlePeer"
        internal const val ARG_RUN = "knitBleRun"
        internal const val ARG_BRIDGE = "knitBleBridge"
        internal const val WINDOW = 100
        internal const val POLL_MS = 500L
        internal const val BRIDGE_TIMEOUT_MS = 5_000
        internal const val LINK_TIMEOUT_MS = 300_000L
        internal const val DM_TIMEOUT_MS = 120_000L
        internal const val ATTACHMENT_TIMEOUT_MS = 240_000L
        internal const val RELINK_TIMEOUT_MS = 300_000L
        internal const val JPEG_QUALITY = 90
        internal const val LINGER_MS = 5_000L
    }
}
