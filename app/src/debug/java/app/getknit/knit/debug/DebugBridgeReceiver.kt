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
import android.net.Uri
import android.os.Build
import android.util.Log
import app.getknit.knit.data.AttachmentStore
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
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.data.webp.WebpTranscode
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.ui.chat.buildReplySnippet
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
 *   the broadcast room. Add `--es replyTo <messageId>` to quote a message already in that thread (the
 *   ReplyRef is built exactly as the UI does; [ACTION_STATE] echoes the stored `replyTo*` fields back).
 * - [ACTION_SENDIMG] — sends a real **image attachment** with no UI (a locked device can't drive the photo
 *   picker): `--es path <file the app can read — e.g. run-as-copied into its filesDir>` plus the same
 *   target extras as [ACTION_SEND] (`conv`/`to`, default the broadcast room) and optional `--es text`.
 *   Runs the exact production pipeline (AttachmentStore.ingest → MeshManager.sendChat), so the image
 *   send→pull→verify loop is fully scriptable; replies with the attachment `hash` to poll for on receivers.
 * - [ACTION_STATE] — self id/name, transport health, the reachable peer set, and the mesh metrics; add
 *   `--es conv <id>` to also dump that thread's most recent messages (`--ei limit N`, default
 *   [DEFAULT_MESSAGE_LIMIT]) with each message's `received` delivery tick — how "verify receipt" works.
 * - [ACTION_STORE] — the store-and-forward carry set (the id set the cue-plane content digest is folded over),
 *   with `digestVersion`, all/live fingerprints, `expiredIds`, the full `allIds`, and capped per-row detail
 *   (`--ei limit N`, default [DEFAULT_STORE_LIMIT]) — to diff why two devices never converge their digests.
 * - [ACTION_REACT] — `--es id <messageId> --es emoji <emoji>` toggles a reaction.
 * - [ACTION_TYPING] — `--es conv <id>` (or `--es to <peerNodeId>`; default the broadcast room) fires one
 *   best-effort "now typing" cue; poll a receiver's [ACTION_STATE] `typing` map to confirm it landed.
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
 * - [ACTION_REQNOTIF] — posts the coalesced "message request received" heads-up: writes `--ei count N`
 *   (default 1) synthetic unaccepted inbound DMs from unknown peers and calls [Notifier.notifyMessageRequests],
 *   so the UIAutomator suite can drive the real system notification + Requests inbox. Needs POST_NOTIFICATIONS.
 * - [ACTION_FLAGMSG] — injects one synthetic **inbound message the on-device text moderator flagged** (the UI
 *   collapses it behind a tap-to-reveal) as the newest row of `--es conv <id>` (default the broadcast room),
 *   from `--es from <peerNodeId>` (default a synthetic sender, named on upsert) with body `--es text <body>`:
 *   the seam the UIAutomator moderation-reveal test drives, since the radio-less build never receives a real
 *   flagged message and the marketing seed deliberately carries none (a hidden bubble would spoil a screenshot).
 * - [ACTION_HEAL] — nudges the transport to rescan/re-advertise.
 *
 * Each action replies as a one-line JSON object: it is returned via the ordered-broadcast result
 * (`am broadcast` prints `Broadcast completed: result=0, data="…"`) and also logged under the [TAG] tag
 * as a size-safe fallback (`adb logcat -d -s KnitBridge:I`).
 */
class DebugBridgeReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val mesh: MeshController by inject()
    private val attachments: AttachmentStore by inject()
    private val messages: MessageRepository by inject()
    private val peers: PeerRepository by inject()
    private val groups: GroupRepository by inject()
    private val metrics: MeshMetrics by inject()
    private val identity: Identity by inject()
    private val settings: SettingsStore by inject()
    private val forwardDao: ForwardDao by inject()
    private val digest: StoreDigest by inject()
    private val notifier: Notifier by inject()
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

                        ACTION_SENDIMG -> {
                            handleSendImg(intent)
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

                        ACTION_TYPING -> {
                            handleTyping(intent)
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

                        ACTION_REQNOTIF -> {
                            handleReqNotif(intent)
                        }

                        ACTION_FLAGMSG -> {
                            handleFlagMsg(intent)
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
        // Optional quoted reply: --es replyTo <messageId> of a message already in this thread. Build the
        // ReplyRef exactly as ChatViewModel does (self-author resolved to our real name, snippet capped).
        val replyTo =
            intent.getStringExtra(EXTRA_REPLY_TO)?.let { replyId ->
                val row =
                    messages.observeMessages(conv).first().firstOrNull { it.id == replyId }
                        ?: return reply("error", "reply target not in $conv: $replyId")
                val authorName =
                    if (row.senderId == identity.nodeId()) {
                        displayNameFor(settings.displayName.first(), row.senderId)
                    } else {
                        displayNameFor(peers.find(row.senderId)?.name, row.senderId)
                    }
                ReplyRef(
                    messageId = row.id,
                    authorId = row.senderId,
                    author = authorName,
                    snippet = buildReplySnippet(row.body, row.moderation == MessageEntity.MODERATION_TEXT_FLAGGED),
                    hasAttachment = row.attachmentHash != null,
                )
            }
        // Route exactly as ChatViewModel.send does, resolving the thread kind from its id.
        val sent =
            when (Conversations.kindFor(conv)) {
                ConversationKind.NEARBY -> {
                    mesh.sendChat(text, recipientId = null, group = null, replyTo = replyTo)
                }

                ConversationKind.DM -> {
                    mesh.sendChat(text, recipientId = conv, replyTo = replyTo)
                }

                ConversationKind.GROUP -> {
                    groups.find(conv)?.let { mesh.sendChat(text, group = it.toGroupInfo(), replyTo = replyTo) }
                }
            }
        return when (sent) {
            null -> reply("error", "unknown group (not joined on this device): $conv")
            true -> reply("ok", "sent to $conv")
            false -> reply("blocked", "blocked by on-device content filter")
        }.put("conversation", conv)
    }

    /**
     * Sends a real image attachment with no UI: ingest the file at `--es path` through the production
     * pipeline ([AttachmentStore.ingest] — downscale/re-encode/moderate) and hand the result to
     * [MeshManager.sendChat] (seal for a DM/group, flood + custody), routed by the same `conv`/`to`
     * extras as [handleSend]. The reply carries the attachment `hash`, so a script can poll the
     * receivers' blob state / logcat for exactly this transfer.
     */
    private suspend fun handleSendImg(intent: Intent): JSONObject {
        val path = intent.getStringExtra(EXTRA_PATH) ?: return reply("error", "missing 'path' extra")
        val file = File(path)
        if (!file.exists()) return reply("error", "no such file: $path")
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val conv = intent.getStringExtra(EXTRA_CONV) ?: intent.getStringExtra(EXTRA_TO) ?: Conversations.NEARBY
        val ingested =
            when (val result = attachments.ingest(Uri.fromFile(file))) {
                is AttachmentStore.IngestResult.Success -> {
                    result.ingested
                }

                AttachmentStore.IngestResult.Failed -> {
                    return reply("error", "ingest failed (decode error or over the size cap): $path")
                }
            }
        val sent =
            when (Conversations.kindFor(conv)) {
                ConversationKind.NEARBY -> {
                    mesh.sendChat(text, attachment = ingested, recipientId = null, group = null)
                }

                ConversationKind.DM -> {
                    mesh.sendChat(text, attachment = ingested, recipientId = conv)
                }

                ConversationKind.GROUP -> {
                    groups.find(conv)?.let { mesh.sendChat(text, attachment = ingested, group = it.toGroupInfo()) }
                }
            }
        return when (sent) {
            null -> reply("error", "unknown group (not joined on this device): $conv")
            true -> reply("ok", "sent image to $conv")
            false -> reply("blocked", "blocked by on-device content filter")
        }.put("conversation", conv).put("hash", ingested.hash).put("mime", ingested.mime)
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

            // WEBP (deprecated at API 30) is the API-29 lossy WebP format; WEBP_LOSSY is API 30.
            @Suppress("DEPRECATION")
            val webpFormat =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
            scaled.compress(webpFormat, quality, fo)
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

    /**
     * Fires one best-effort "now typing" cue for `--es conv <id>` (or the `to` DM shorthand; default the
     * broadcast room), exactly as the chat input's throttle does. Poll a receiver's [ACTION_STATE] `typing`
     * field to confirm it landed. Fire-and-forget: replies `ok` regardless of whether anyone was reachable.
     */
    private suspend fun handleTyping(intent: Intent): JSONObject {
        val conv = intent.getStringExtra(EXTRA_CONV) ?: intent.getStringExtra(EXTRA_TO) ?: Conversations.NEARBY
        mesh.sendTyping(conv)
        return reply("ok", "typing cue sent to $conv").put("conversation", conv)
    }

    private suspend fun handleState(intent: Intent): JSONObject {
        val selfId = identity.nodeId()
        val selfName = settings.displayName.first()
        val nameByNode = peers.observePeers().first().associate { it.nodeId to it.name }

        val reachable = JSONArray()
        mesh.neighbors.value.forEach { peer ->
            reachable.put(JSONObject().put("nodeId", peer.nodeId).put("name", nameByNode[peer.nodeId] ?: ""))
        }

        // Ephemeral "who's typing" state (conversationId -> [senderNodeId, …]), so a receiver can be polled
        // headlessly to confirm a best-effort typing cue landed — the indicator itself is UI-only/transient.
        val typing = JSONObject()
        mesh.typing.value.forEach { (conv, senders) -> typing.put(conv, JSONArray(senders.toList())) }

        val out =
            JSONObject()
                .put("status", "ok")
                .put("self", JSONObject().put("nodeId", selfId).put("name", selfName))
                .put("health", mesh.transportHealth.value.name)
                .put("neighborCount", mesh.neighborCount.value)
                .put("reachable", reachable)
                .put("typing", typing)
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
                    .put("received", m.received)
                    .put("replyToId", m.replyToId ?: JSONObject.NULL)
                    .put("replyToAuthor", m.replyToAuthor ?: JSONObject.NULL)
                    .put("replyToSnippet", m.replyToSnippet ?: JSONObject.NULL),
            )
        }
        return arr
    }

    /**
     * Dumps the store-and-forward carry set — the **live** rows are the exact id set the cue-plane content
     * digest ([StoreDigest.current]) is folded over (work item #8: expired-but-unswept rows are invisible to
     * the digest, quotas, and serves — pure residue awaiting the sweep), so two devices that never converge
     * (each keeps firing NDPs at the other) can be diffed to find the stranded id. Reports:
     *  - `digestVersion` — the live in-memory digest the transport actually cues (read via [StoreDigest.current],
     *    the same lazy-folding accessor the transports use, so a TTL boundary crossed since the transport's
     *    last read can't masquerade as drift);
     *  - `allFingerprint` / `liveFingerprint` — the digest recomputed over *all* rows vs. *non-expired* rows.
     *    **The invariant is `digestVersion == liveFingerprint`, always** — a mismatch means the in-memory
     *    digest drifted from the table (a bug). `allFingerprint` legitimately lags behind by the expired
     *    residue until the sweep, and is **no longer fleet-comparable** at a TTL boundary — cross-device
     *    convergence checks (soak oracles included) must compare `liveFingerprint`;
     *  - `expiredIds` — the residue rows (benign; reclaimed by the next sweep);
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
            .put("digestVersion", digest.current().toString())
            .put("allFingerprint", StoreDigest.fingerprint(rows.map { it.id }).toString())
            .put("liveFingerprint", StoreDigest.fingerprint(liveRows.map { it.id }).toString())
            .put(
                "counts",
                JSONObject().put("total", rows.size).put("live", liveRows.size).put("expired", expiredRows.size),
            ).put("expiredIds", JSONArray(expiredRows.map { it.id }))
            .put("allIds", JSONArray(rows.map { it.id }))
            .put("rows", rowsJson)
    }

    /**
     * Posts the coalesced "message request received" heads-up on demand — the seam the UIAutomator
     * notification test drives ([app.getknit.knit] `uiauto`). The radio-less demo build never runs
     * [app.getknit.knit.mesh.InboundPipeline] (the sole production caller of
     * [Notifier.notifyMessageRequests]) and seeds no requests, so nothing would otherwise post one. This
     * writes `count` synthetic **unaccepted inbound DMs** from fresh unknown peers — each a message request
     * per [Conversations.isAccepted] (not accepted / verified / self-authored) so a tap on the heads-up lands
     * on a populated Requests inbox — then posts the heads-up. The app must hold `POST_NOTIFICATIONS` (the
     * test grants it) or the post silently no-ops. `--ei count N` (default 1, capped at [MAX_REQNOTIF]).
     */
    private suspend fun handleReqNotif(intent: Intent): JSONObject {
        val count = intent.getIntExtra(EXTRA_COUNT, 1).coerceIn(1, MAX_REQNOTIF)
        val me = identity.nodeId()
        val now = System.currentTimeMillis()
        repeat(count) { i ->
            val nodeId = "strngr0${i + 1}"
            val name = if (i == 0) "Alex Stranger" else "Stranger ${i + 1}"
            // A discovered-but-unaccepted peer (no pinned key, not verified) so its DM stays a request.
            peers.upsert(PeerEntity(nodeId = nodeId, name = name, updatedAt = now))
            // One inbound DM: for a received DM the conversationId is the sender's node id and recipientId
            // is us (Conversations.idFor); received=false marks it as not ours.
            messages.save(
                MessageEntity(
                    id = "reqnotif-$nodeId-$now",
                    senderId = nodeId,
                    recipientId = me,
                    conversationId = nodeId,
                    body = "Hey! Mind if I join the hike?",
                    sentAt = now - i * 1_000L,
                    received = false,
                ),
            )
        }
        // The demo build never starts MeshService, so the channels may not exist yet — ensure them first.
        notifier.createChannel()
        // The exact call InboundPipeline makes when it silences a stranger's first contact as a request.
        notifier.notifyMessageRequests(count)
        return reply("ok", "posted $count message request(s)").put("count", count)
    }

    /**
     * Injects one synthetic **inbound message the on-device text moderator flagged** into a conversation, so
     * the UIAutomator suite can drive the received-flagged "tap to reveal" collapse (the
     * [MessageEntity.MODERATION_TEXT_FLAGGED] path in [app.getknit.knit.ui.chat] `ChatScreen`). The radio-less
     * demo build never receives a real flagged message (no [app.getknit.knit.mesh] `InboundPipeline`), and the
     * marketing seed deliberately carries none (a hidden bubble would spoil a screenshot), so this writes one
     * on demand — timestamped `now`, so it's the **newest** row and a `LazyColumn` composes it on screen.
     * `--es conv <id>` (default the broadcast room), `--es from <peerNodeId>` the sender (default
     * [FLAGGED_SENDER_ID], upserted with a name if unknown), `--es text <body>` the hidden body (default
     * [DEFAULT_FLAGGED_BODY]).
     */
    private suspend fun handleFlagMsg(intent: Intent): JSONObject {
        val conv = intent.getStringExtra(EXTRA_CONV) ?: Conversations.NEARBY
        val body = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: DEFAULT_FLAGGED_BODY
        val from = intent.getStringExtra(EXTRA_FROM) ?: FLAGGED_SENDER_ID
        val me = identity.nodeId()
        val now = System.currentTimeMillis()
        // Give the sender a name so the row renders like a real peer message (idempotent upsert).
        if (peers.find(from) == null) {
            peers.upsert(PeerEntity(nodeId = from, name = FLAGGED_SENDER_NAME, updatedAt = now))
        }
        // For a received DM the conversationId is the sender's node id and recipientId is us; the room/group
        // carries no recipient — mirrors DemoWriter.write / handleReqNotif's routing.
        val recipient = if (Conversations.kindFor(conv) == ConversationKind.DM) me else null
        val id = "flagmsg-$now"
        messages.save(
            MessageEntity(
                id = id,
                senderId = from,
                recipientId = recipient,
                conversationId = conv,
                body = body,
                sentAt = now,
                received = false,
                moderation = MessageEntity.MODERATION_TEXT_FLAGGED,
            ),
        )
        return reply("ok", "flagged inbound message injected into $conv").put("conversation", conv).put("id", id)
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
            .put("nanMsgsAcked", snap.nanMsgsAcked)
            .put("nanMsgSendsFailed", snap.nanMsgSendsFailed)
            .put("filesSentNan", snap.filesSentNan)
            .put("filesSentBt", snap.filesSentBt)
            .put("nanBulkGraceTimeouts", snap.nanBulkGraceTimeouts)

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
        const val ACTION_SENDIMG = "app.getknit.knit.debug.SENDIMG"
        const val ACTION_STATE = "app.getknit.knit.debug.STATE"
        const val ACTION_STORE = "app.getknit.knit.debug.STORE"
        const val ACTION_REACT = "app.getknit.knit.debug.REACT"
        const val ACTION_TYPING = "app.getknit.knit.debug.TYPING"
        const val ACTION_SHARE_APK = "app.getknit.knit.debug.SHAREAPK"
        const val ACTION_WEBPPROBE = "app.getknit.knit.debug.WEBPPROBE"
        const val ACTION_WEBPCONV = "app.getknit.knit.debug.WEBPCONV"
        const val ACTION_WEBPCHECK = "app.getknit.knit.debug.WEBPCHECK"
        const val ACTION_HEAL = "app.getknit.knit.debug.HEAL"
        const val ACTION_REQNOTIF = "app.getknit.knit.debug.REQNOTIF"
        const val ACTION_FLAGMSG = "app.getknit.knit.debug.FLAGMSG"

        const val EXTRA_TEXT = "text"
        const val EXTRA_CONV = "conv"
        const val EXTRA_TO = "to"
        const val EXTRA_REPLY_TO = "replyTo"
        const val EXTRA_ID = "id"
        const val EXTRA_EMOJI = "emoji"
        const val EXTRA_LIMIT = "limit"
        const val EXTRA_PATH = "path"
        const val EXTRA_OUT = "out"
        const val EXTRA_COUNT = "count"
        const val EXTRA_FROM = "from"

        /** Default sender + hidden body for [ACTION_FLAGMSG]'s synthetic flagged inbound message. */
        const val FLAGGED_SENDER_ID = "flagger0"
        const val FLAGGED_SENDER_NAME = "Flagged Sender"
        const val DEFAULT_FLAGGED_BODY = "[flagged demo message]"

        /** Cap on the synthetic requests [ACTION_REQNOTIF] injects (keeps each stranger's node-id single-digit). */
        const val MAX_REQNOTIF = 9

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
