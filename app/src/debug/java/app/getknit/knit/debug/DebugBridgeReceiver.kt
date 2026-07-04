package app.getknit.knit.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.util.Log
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.decodeBoundedFromBytes
import app.getknit.knit.data.downscale
import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.data.webp.WebpTranscode
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.ui.invite.prepareKnitApk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Debug-only ADB "bridge" that lets an automation agent drive the app headlessly — originating a chat
 * message straight through [MeshManager.sendChat] and reading message/peer/health state back as JSON —
 * so the send→verify loop needs no screenshots or pixel-hunting for the (unlabeled) send button.
 *
 * **This class and its manifest entry live in `src/debug/` only**, so the manifest merger includes them
 * exclusively in debug variants; the release APK contains neither. No runtime `BuildConfig` guard is
 * needed. The receiver is exported so the `shell` uid (adb) can reach it.
 *
 * Actions (fire with `am broadcast`, target the package with `-p app.getknit.knit`):
 * - [ACTION_SEND] — `--es text <body>` plus a target: `--es conv <conversationId>` (the `nearby` room, a
 *   peer node id for a DM, or a `g-…` group id) or `--es to <peerNodeId>` (a DM shorthand). No target ⇒
 *   the broadcast room.
 * - [ACTION_STATE] — self id/name, transport health, the reachable peer set, and the mesh metrics; add
 *   `--es conv <id>` to also dump that thread's most recent messages (`--ei limit N`, default
 *   [DEFAULT_MESSAGE_LIMIT]) with each message's `received` delivery tick — how "verify receipt" works.
 * - [ACTION_STORE] — the store-and-forward carry set (the id set the cue-plane content digest is folded over),
 *   with `digestVersion`, all/live fingerprints, `expiredIds`, the full `allIds`, and capped per-row detail
 *   (`--ei limit N`, default [DEFAULT_STORE_LIMIT]) — to diff why two devices never converge their digests.
 * - [ACTION_REACT] — `--es id <messageId> --es emoji <emoji>` toggles a reaction.
 * - [ACTION_SHARE_APK] — runs the offline "Share Knit app" prepare step (merging split installs into one
 *   re-signed APK) headlessly and reports the staged `cacheDir/apk` files, so the result can be pulled +
 *   verified without the share sheet.
 * - [ACTION_WEBPCONV] — `--es path <gifFile>` runs the real send-side GIF → animated-WebP transcode and
 *   reports the byte reduction (`origBytes`/`outBytes`/`pctSmaller`); add `--es out <file>` to write the
 *   WebP out. Optional `--ei dim <px>` / `--ei fps <n>` / `--ei q <quality>` override the bounds.
 * - [ACTION_WEBPCHECK] — `--es path <file>` decodes an image through Android's `ImageDecoder` (Coil's
 *   engine) and reports `animated`/`width`/`height` — the in-app proof a muxed WebP actually plays.
 * - [ACTION_WEBPPROBE] — `--es path <gifFile>` estimates an animated-WebP re-encode's size (sums each
 *   frame's built-in `WEBP_LOSSY` bytes); `--ei dim`/`--ei fps`/`--ei q <quality>` tune it. Feasibility
 *   probe only — reports `webpAnimEstBytes`/`pctSmaller`, writes nothing.
 * - [ACTION_HEAL] — nudges the transport to rescan/re-advertise.
 *
 * Each action replies as a one-line JSON object: it is returned via the ordered-broadcast result
 * (`am broadcast` prints `Broadcast completed: result=0, data="…"`) and also logged under the [TAG] tag
 * as a size-safe fallback (`adb logcat -d -s KnitBridge:I`).
 */
class DebugBridgeReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val mesh: MeshManager by inject()
    private val messages: MessageRepository by inject()
    private val peers: PeerRepository by inject()
    private val groups: GroupRepository by inject()
    private val metrics: MeshMetrics by inject()
    private val identity: Identity by inject()
    private val settings: SettingsStore by inject()
    private val forwardDao: ForwardDao by inject()
    private val digest: StoreDigest by inject()
    private val scope: CoroutineScope by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        // The work is suspending (send + repo reads), so keep the broadcast alive past onReceive and
        // report the JSON result once it completes.
        val pending = goAsync()
        scope.launch {
            val result =
                runCatching {
                    when (action) {
                        ACTION_SEND -> {
                            handleSend(intent)
                        }

                        ACTION_STATE -> {
                            handleState(intent)
                        }

                        ACTION_STORE -> {
                            handleStore(intent)
                        }

                        ACTION_REACT -> {
                            handleReact(intent)
                        }

                        ACTION_SHARE_APK -> {
                            handleShareApk(context)
                        }

                        ACTION_WEBPPROBE -> {
                            handleWebpProbe(intent)
                        }

                        ACTION_WEBPCONV -> {
                            handleWebpConv(intent)
                        }

                        ACTION_WEBPCHECK -> {
                            handleWebpCheck(intent)
                        }

                        ACTION_HEAL -> {
                            mesh.heal()
                            reply("ok", "healed")
                        }

                        else -> {
                            reply("error", "unknown action: $action")
                        }
                    }
                }.getOrElse { t ->
                    Log.e(TAG, "bridge action $action failed", t)
                    reply("error", t.message ?: t.javaClass.simpleName)
                }
            val json = result.toString()
            Log.i(TAG, json)
            pending.setResultCode(0)
            pending.setResultData(json)
            pending.finish()
        }
    }

    private suspend fun handleSend(intent: Intent): JSONObject {
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        if (text.isBlank()) return reply("error", "missing 'text' extra")
        // A DM shorthand (`to`) or an explicit conversation id (`conv`); default to the broadcast room.
        val conv = intent.getStringExtra(EXTRA_CONV) ?: intent.getStringExtra(EXTRA_TO) ?: Conversations.NEARBY
        // Route exactly as ChatViewModel.send does, resolving the thread kind from its id.
        val sent =
            when (Conversations.kindFor(conv)) {
                ConversationKind.NEARBY -> mesh.sendChat(text, recipientId = null, group = null)
                ConversationKind.DM -> mesh.sendChat(text, recipientId = conv)
                ConversationKind.GROUP -> groups.find(conv)?.let { mesh.sendChat(text, group = it.toGroupInfo()) }
            }
        return when (sent) {
            null -> reply("error", "unknown group (not joined on this device): $conv")
            true -> reply("ok", "sent to $conv")
            false -> reply("blocked", "blocked by on-device content filter")
        }.put("conversation", conv)
    }

    /**
     * Decodes a WebP (or any image) through the exact `android.graphics.ImageDecoder` path Coil's
     * `AnimatedImageDecoder` uses, and reports whether it's animated + its dimensions — the definitive
     * in-app proof that a muxed animated WebP actually plays. A decode failure surfaces as an error reply.
     */
    private fun handleWebpCheck(intent: Intent): JSONObject {
        val path = intent.getStringExtra(EXTRA_PATH) ?: return reply("error", "missing 'path' extra")
        val file = File(path)
        if (!file.exists()) return reply("error", "no such file: $path")
        val bytes = file.readBytes()
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        // Also exercise moderation's first-frame decode (BitmapFactory via decodeBoundedFromBytes), the
        // receive-side screening path — it must yield a frame so an animated WebP can still be screened.
        val modFrame = decodeBoundedFromBytes(bytes, WEBP_CHECK_MOD_DIM)
        return JSONObject()
            .put("status", "ok")
            .put("path", path)
            .put("bytes", bytes.size)
            .put("decoded", true)
            .put("animated", drawable is AnimatedImageDrawable)
            .put("drawable", drawable.javaClass.simpleName)
            .put("width", drawable.intrinsicWidth)
            .put("height", drawable.intrinsicHeight)
            .put("moderationDecodes", modFrame != null)
            .put("moderationFrame", if (modFrame != null) "${modFrame.width}x${modFrame.height}" else JSONObject.NULL)
    }

    /**
     * Runs the real send-side GIF → animated-WebP transcode ([WebpTranscode.shrink]) and writes the WebP
     * to `--es out <file>`, so the output can be pulled and validated end-to-end. `--ei dim`/`--ei fps`/
     * `--ei q` override the bounds (default production). Reports `origBytes`/`outBytes`/`pctSmaller`.
     */
    private fun handleWebpConv(intent: Intent): JSONObject {
        val path = intent.getStringExtra(EXTRA_PATH) ?: return reply("error", "missing 'path' extra")
        val file = File(path)
        if (!file.exists()) return reply("error", "no such file: $path")
        val bytes = file.readBytes()
        val dim = intent.getIntExtra("dim", GIF_MAX_DIMENSION)
        val fps = intent.getIntExtra("fps", GIF_MAX_FPS)
        val quality = intent.getIntExtra("q", WEBP_PROBE_QUALITY)
        val webp = WebpTranscode.shrink(bytes, dim, fps, quality)
        val outPath = intent.getStringExtra(EXTRA_OUT)
        if (webp != null && outPath != null) File(outPath).writeBytes(webp)
        val outBytes = webp?.size ?: bytes.size
        val pct = if (bytes.isNotEmpty()) 100 - (outBytes.toLong() * 100 / bytes.size) else 0L
        return JSONObject()
            .put("status", "ok")
            .put("path", path)
            .put("dim", dim)
            .put("fps", fps)
            .put("quality", quality)
            .put("shrunk", webp != null)
            .put("origBytes", bytes.size)
            .put("outBytes", outBytes)
            .put("pctSmaller", pct)
            .put("wroteTo", if (webp != null) outPath ?: JSONObject.NULL else JSONObject.NULL)
    }

    /**
     * Measures what an **animated-WebP** re-encode of a GIF would weigh: decodes frames at `--ei dim` /
     * `--ei fps` (default production bounds) and sums each frame's built-in `WEBP_LOSSY` size at
     * `--ei q` (default [WEBP_PROBE_QUALITY]), plus a small per-frame ANMF mux estimate. This is a
     * feasibility probe for the "GIF → animated WebP via Bitmap.compress + a pure-Kotlin RIFF muxer"
     * path — it does not write a WebP (Android can't mux one yet), just reports the projected bytes.
     */
    @Suppress("DEPRECATION") // Movie is the only built-in GIF frame sampler; still functional.
    private fun handleWebpProbe(intent: Intent): JSONObject {
        val path = intent.getStringExtra(EXTRA_PATH) ?: return reply("error", "missing 'path' extra")
        val file = File(path)
        if (!file.exists()) return reply("error", "no such file: $path")
        val bytes = file.readBytes()
        val dim = intent.getIntExtra("dim", GIF_MAX_DIMENSION)
        val fps = intent.getIntExtra("fps", GIF_MAX_FPS)
        val quality = intent.getIntExtra("q", WEBP_PROBE_QUALITY)
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
        if (movie == null || movie.width() <= 0 || movie.height() <= 0 || movie.duration() <= 0) {
            return reply("error", "Movie could not decode it / unknown size/timing")
        }

        val interval = (MILLIS_PER_SEC.toInt() / fps).coerceAtLeast(1)
        val frameBuffer = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frameBuffer)
        var frames = 0
        var lossyBytes = 0L
        var outDims = "?"
        var t = 0
        while (t < movie.duration()) {
            frameBuffer.eraseColor(Color.TRANSPARENT)
            movie.setTime(t)
            movie.draw(canvas, 0f, 0f)
            val scaled = downscale(frameBuffer, dim)
            outDims = "${scaled.width}x${scaled.height}"
            val fo = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, fo)
            lossyBytes += fo.size()
            if (scaled !== frameBuffer) scaled.recycle()
            frames++
            t += interval
        }
        frameBuffer.recycle()

        // Animated-WebP muxing is ~+WEBP_ANMF_OVERHEAD B/frame net (a ~24 B ANMF header per frame, less
        // the ~20 B RIFF/WEBP wrapper we'd strip off each single-frame compress) + a small VP8X/ANIM head.
        val est = lossyBytes + frames * WEBP_ANMF_OVERHEAD + WEBP_HEADER_OVERHEAD
        val pct = if (bytes.isNotEmpty()) 100 - (est * 100 / bytes.size) else 0L
        return JSONObject()
            .put("status", "ok")
            .put("path", path)
            .put("dim", dim)
            .put("fps", fps)
            .put("quality", quality)
            .put("frames", frames)
            .put("outDims", outDims)
            .put("origBytes", bytes.size)
            .put("webpAnimEstBytes", est)
            .put("pctSmaller", pct)
    }

    /**
     * Drives the offline "Share Knit app" prepare step headlessly (no share sheet): runs
     * [prepareKnitApk], which for a Play App Bundle install merges the on-disk splits into one re-signed
     * APK, and reports the staged `cacheDir/apk` files so the merged APK can be pulled and verified —
     * `adb shell run-as app.getknit.knit cat cache/apk/<name>`. `splitInstall` says which path ran.
     */
    private suspend fun handleShareApk(context: Context): JSONObject {
        val splitDirs =
            context.applicationInfo.splitSourceDirs
                ?.toList()
                .orEmpty()
        val uri = prepareKnitApk(context)
        val files = JSONArray()
        File(context.cacheDir, "apk")
            .listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { files.put(JSONObject().put("name", it.name).put("bytes", it.length())) }
        return JSONObject()
            .put("status", "ok")
            .put("splitInstall", splitDirs.isNotEmpty())
            .put("splitCount", splitDirs.size)
            .put("uri", uri.toString())
            .put("files", files)
    }

    private suspend fun handleReact(intent: Intent): JSONObject {
        val messageId = intent.getStringExtra(EXTRA_ID) ?: return reply("error", "missing 'id' extra")
        val emoji = intent.getStringExtra(EXTRA_EMOJI) ?: return reply("error", "missing 'emoji' extra")
        mesh.sendReaction(messageId, emoji)
        return reply("ok", "reacted $emoji to $messageId")
    }

    private suspend fun handleState(intent: Intent): JSONObject {
        val selfId = identity.nodeId()
        val selfName = settings.displayName.first()
        val nameByNode = peers.observePeers().first().associate { it.nodeId to it.name }

        val reachable = JSONArray()
        mesh.neighbors.value.forEach { peer ->
            reachable.put(JSONObject().put("nodeId", peer.nodeId).put("name", nameByNode[peer.nodeId] ?: ""))
        }

        val out =
            JSONObject()
                .put("status", "ok")
                .put("self", JSONObject().put("nodeId", selfId).put("name", selfName))
                .put("health", mesh.transportHealth.value.name)
                .put("neighborCount", mesh.neighborCount.value)
                .put("reachable", reachable)
                .put("metrics", metricsJson(metrics.snapshot()))

        intent.getStringExtra(EXTRA_CONV)?.let { conv ->
            val limit = intent.getIntExtra(EXTRA_LIMIT, DEFAULT_MESSAGE_LIMIT)
            val recent = messages.observeMessages(conv).first().takeLast(limit)
            out.put("conversation", conv).put("messages", messagesJson(recent, selfId, selfName, nameByNode))
        }
        return out
    }

    private fun messagesJson(
        rows: List<MessageEntity>,
        selfId: String,
        selfName: String,
        nameByNode: Map<String, String>,
    ): JSONArray {
        val arr = JSONArray()
        rows.forEach { m ->
            val from = if (m.senderId == selfId) selfName else nameByNode[m.senderId] ?: ""
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("from", m.senderId)
                    .put("fromName", from)
                    .put("mine", m.senderId == selfId)
                    .put("body", m.body)
                    .put("sentAt", m.sentAt)
                    .put("received", m.received),
            )
        }
        return arr
    }

    /**
     * Dumps the store-and-forward carry set — the exact id set the cue-plane content digest
     * ([StoreDigest.version]) is folded over, so two devices that never converge (each keeps firing NDPs at the
     * other) can be diffed to find the stranded id. Reports:
     *  - `digestVersion` — the live in-memory digest the transport actually cues;
     *  - `allFingerprint` / `liveFingerprint` — the digest recomputed over *all* rows vs. *non-expired* rows.
     *    `allFingerprint != digestVersion` ⇒ the in-memory digest drifted from the table; `liveFingerprint !=
     *    digestVersion` (but `allFingerprint ==`) ⇒ expired-but-unswept rows inflate the digest yet are never
     *    advertised/exchanged (a sync can't reconcile them until the next TTL sweep — the "syncs succeed but
     *    never converge" case);
     *  - `expiredIds` — the offending rows for that case;
     *  - `allIds` — the full set, for a cross-device diff (`comm`/`diff` the sorted arrays across P7/P8/P9);
     *  - `rows` — per-frame detail (expired first, then newest), capped by `--ei limit` ([DEFAULT_STORE_LIMIT]).
     */
    private suspend fun handleStore(intent: Intent): JSONObject {
        val now = System.currentTimeMillis()
        val limit = intent.getIntExtra(EXTRA_LIMIT, DEFAULT_STORE_LIMIT)
        val rows = forwardDao.allRows()
        val liveRows = rows.filter { it.expiresAt >= now }
        val expiredRows = rows.filter { it.expiresAt < now }

        val rowsJson = JSONArray()
        // Expired first, then newest, so the diagnostically-interesting rows survive a truncated dump.
        rows.sortedWith(compareBy({ it.expiresAt >= now }, { -it.receivedAt })).take(limit).forEach { r ->
            rowsJson.put(
                JSONObject()
                    .put("id", r.id)
                    .put("type", r.type)
                    .put("sender", r.senderId)
                    .put("origin", if (r.origin == ForwardStore.ORIGIN_SELF) "self" else "relay")
                    .put("recipient", r.recipientId ?: JSONObject.NULL)
                    .put("group", r.groupId ?: JSONObject.NULL)
                    // The image blob this frame custodies (see forward_store v19), so a device diff can show
                    // whether a carrier is holding the referenced attachment for a late joiner.
                    .put("attachmentHash", r.attachmentHash ?: JSONObject.NULL)
                    .put("ageSec", (now - r.receivedAt) / MILLIS_PER_SEC)
                    .put("ttlLeftSec", (r.expiresAt - now) / MILLIS_PER_SEC)
                    .put("expired", r.expiresAt < now),
            )
        }

        return JSONObject()
            .put("status", "ok")
            .put("self", JSONObject().put("nodeId", identity.nodeId()).put("name", settings.displayName.first()))
            .put("digestVersion", digest.version.value.toString())
            .put("allFingerprint", StoreDigest.fingerprint(rows.map { it.id }).toString())
            .put("liveFingerprint", StoreDigest.fingerprint(liveRows.map { it.id }).toString())
            .put(
                "counts",
                JSONObject().put("total", rows.size).put("live", liveRows.size).put("expired", expiredRows.size),
            ).put("expiredIds", JSONArray(expiredRows.map { it.id }))
            .put("allIds", JSONArray(rows.map { it.id }))
            .put("rows", rowsJson)
    }

    private fun metricsJson(snap: MeshMetrics.Snapshot): JSONObject =
        JSONObject()
            .put("originated", snap.framesOriginated)
            .put("delivered", snap.framesDelivered)
            .put("relayed", snap.framesRelayed)
            .put("dropped", snap.framesDropped)
            .put("keyRequestsSent", snap.keyRequestsSent)
            .put("keysServed", snap.keysServed)
            .put("keysRecovered", snap.keysRecovered)
            .put("framesHeld", snap.framesHeld)
            .put("framesReplayed", snap.framesReplayed)
            .put("receiptsResent", snap.receiptsResent)
            .put("nanServesPeak", snap.nanServesPeak)
            .put("nanAcceptsRefused", snap.nanAcceptsRefused)
            .put("nanIcmKeepaliveFailed", snap.nanIcmKeepaliveFailed)

    private fun reply(
        status: String,
        message: String,
    ): JSONObject = JSONObject().put("status", status).put("message", message)

    /** Rebuilds the self-describing [GroupInfo] from the local row (mirrors ChatViewModel's private helper). */
    private fun GroupEntity.toGroupInfo(): GroupInfo =
        GroupInfo(
            id = groupId,
            name = name.takeIf { it.isNotBlank() },
            members = GroupMembersStore.decode(members),
            createdBy = createdBy,
            photoHash = photoHash,
            photoUpdatedAt = photoUpdatedAt.takeIf { it > 0L },
        )

    private companion object {
        const val TAG = "KnitBridge"

        const val ACTION_SEND = "app.getknit.knit.debug.SEND"
        const val ACTION_STATE = "app.getknit.knit.debug.STATE"
        const val ACTION_STORE = "app.getknit.knit.debug.STORE"
        const val ACTION_REACT = "app.getknit.knit.debug.REACT"
        const val ACTION_SHARE_APK = "app.getknit.knit.debug.SHAREAPK"
        const val ACTION_WEBPPROBE = "app.getknit.knit.debug.WEBPPROBE"
        const val ACTION_WEBPCONV = "app.getknit.knit.debug.WEBPCONV"
        const val ACTION_WEBPCHECK = "app.getknit.knit.debug.WEBPCHECK"
        const val ACTION_HEAL = "app.getknit.knit.debug.HEAL"

        const val EXTRA_TEXT = "text"
        const val EXTRA_CONV = "conv"
        const val EXTRA_TO = "to"
        const val EXTRA_ID = "id"
        const val EXTRA_EMOJI = "emoji"
        const val EXTRA_LIMIT = "limit"
        const val EXTRA_PATH = "path"
        const val EXTRA_OUT = "out"

        // Mirror AttachmentStore.GIF_MAX_DIMENSION / GIF_MAX_FPS (private there) so this diagnostic
        // shrinks a GIF with the same bounds the real ingest path uses.
        const val GIF_MAX_DIMENSION = 480
        const val GIF_MAX_FPS = 15

        // ACTION_WEBPPROBE tunables: default per-frame WEBP_LOSSY quality + the animated-WebP mux
        // overhead we add to the summed per-frame bytes to estimate the final container size.
        const val WEBP_PROBE_QUALITY = 75
        const val WEBP_ANMF_OVERHEAD = 4
        const val WEBP_HEADER_OVERHEAD = 40

        /** Bound for ACTION_WEBPCHECK's moderation first-frame decode (mirrors the screening path). */
        const val WEBP_CHECK_MOD_DIM = 640

        const val DEFAULT_MESSAGE_LIMIT = 20

        /** Default cap on per-row detail in the [ACTION_STORE] dump (`allIds`/`expiredIds` are always complete). */
        const val DEFAULT_STORE_LIMIT = 100

        const val MILLIS_PER_SEC = 1000L
    }
}
