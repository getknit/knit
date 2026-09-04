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
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import app.getknit.knit.BuildConfig
import app.getknit.knit.crash.ProcessExitReasons
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.decodeBoundedFromBytes
import app.getknit.knit.data.downscale
import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.KnitBoardSetup
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.data.webp.WebpTranscode
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshStartGate
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.lora.BoardOwner
import app.getknit.knit.mesh.lora.BoardSettings
import app.getknit.knit.mesh.lora.ProvisionMode
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.wifiaware.NanFaultInjector
import app.getknit.knit.moderation.ModelLoadGuard
import app.getknit.knit.moderation.ModelLoadPolicy
import app.getknit.knit.moderation.modelGuardStamp
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.review.ReviewPromptPolicy
import app.getknit.knit.review.ReviewPrompter
import app.getknit.knit.ui.chat.buildReplySnippet
import app.getknit.knit.ui.invite.prepareKnitApk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
 * - [ACTION_REVIEW] — dumps the rate-prompt gate state (installer, message counts, engagement watermark,
 *   attempts, `shouldPrompt`, and the installer-aware `rateUrl`/`feedbackUrl`) via the same [ReviewPrompter]
 *   reads the real prompt uses. `--ez reset true` clears the persisted review state; `--ez arm true`
 *   additionally backdates the engagement watermark past the age gate and forgets prior attempts, so the
 *   next chat-list visit prompts as soon as the (real) message-count gates hold.
 * - [ACTION_MODEL] — dumps the on-device model poison-pill (ADR 037): the current build stamp, and per
 *   model the stored stamp, `pendingSince` marker, unexplained-death count and whether it is latched —
 *   plus what the platform says about the **previous process exit**, which is what decides a 1-strike
 *   latch. `--ez reset true` clears every model's record. The fault itself is a build flag, not a bridge
 *   op (`-PmodelFaultOnLoad=segv|kill`), so nothing in `src/main` carries an arming seam.
 * - [ACTION_REQNOTIF] — posts the coalesced "message request received" heads-up: writes `--ei count N`
 *   (default 1) synthetic unaccepted inbound DMs from unknown peers and calls [Notifier.notifyMessageRequests],
 *   so the UIAutomator suite can drive the real system notification + Requests inbox. Needs POST_NOTIFICATIONS.
 * - [ACTION_FLAGMSG] — injects one synthetic **inbound message the on-device text moderator flagged** (the UI
 *   collapses it behind a tap-to-reveal) as the newest row of `--es conv <id>` (default the broadcast room),
 *   from `--es from <peerNodeId>` (default a synthetic sender, named on upsert) with body `--es text <body>`:
 *   the seam the UIAutomator moderation-reveal test drives, since the radio-less build never receives a real
 *   flagged message and the marketing seed deliberately carries none (a hidden bubble would spoil a screenshot).
 * - [ACTION_SPOOL] — configures and inspects the Internet (spool) plane, which has no UI beyond a
 *   debug-only on/off switch: `--es url <ws(s)://…/spool/v1>` adds a spool, `--es drop <url>` removes one,
 *   `--ez on <bool>` flips the global opt-in, and no extras at all just dumps state. The per-scope
 *   `local` vs `spool` counts are the convergence oracle, the way `liveFingerprint` parity is for custody.
 * - [ACTION_RATCHET] — dumps the DM ratchet's per-peer state (X3DH inputs, session/confirmed, send epoch,
 *   the reset floor's anchor) and, with `--es reset <peerNodeId>`, forces a session reset past the
 *   heuristic. Every gate in the recovery path returns silently, so a peer we hold no prekey for, one
 *   inside its 6 h floor, and one whose heuristic has not counted three distinct failures all look the
 *   same from outside; the dump says which, and the force unwedges a pair that broke before a fix shipped.
 * - [ACTION_NANFAIL] — arms `--ei count N` Wi-Fi Aware attaches to take their failure path without ever
 *   reaching `mgr.attach` (0 disarms). The lab stand-in for a chipset that cannot produce a NAN interface;
 *   see `NanFaultInjector` for what it does and does not reproduce.
 * - [ACTION_NANSTORM] — replays getknit/Knit#9's availability storm: `--ei count N` (default 100) synthetic
 *   Aware availability notifications at `--ei hz H` (default 300). Repeats `true` by default, which must
 *   refund nothing; `--ez cycle true` alternates false/true instead — genuine radio recoveries, the negative
 *   control that must still refund and reattach. The reply's `failuresBefore`/`failuresAfter` is the
 *   measurement: how many attaches the bounds actually let through.
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
    private val reactions: ReactionRepository by inject()
    private val peers: PeerRepository by inject()
    private val groups: GroupRepository by inject()
    private val metrics: MeshMetrics by inject()
    private val startGate: MeshStartGate by inject()
    private val identity: Identity by inject()
    private val settings: SettingsStore by inject()
    private val contactCards: app.getknit.knit.contacts.ContactCards by inject()
    private val contactImporter: app.getknit.knit.contacts.ContactImporter by inject()
    private val forwardDao: ForwardDao by inject()
    private val digest: StoreDigest by inject()
    private val reviewPrompter: ReviewPrompter by inject()
    private val modelGuard: ModelLoadGuard by inject()
    private val exits: ProcessExitReasons by inject()
    private val notifier: Notifier by inject()
    private val scope: CoroutineScope by inject()
    private val lora: app.getknit.knit.mesh.lora.LoraMeshTransport by inject()
    private val loraLink: app.getknit.knit.mesh.lora.MeshtasticLink by inject()

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

                        ACTION_MKGROUP -> {
                            handleMkGroup(intent)
                        }

                        ACTION_REVIEW -> {
                            handleReview(context, intent)
                        }

                        ACTION_MODEL -> {
                            handleModel(intent)
                        }

                        ACTION_SPOOL -> {
                            handleSpool(intent)
                        }

                        ACTION_INTRO -> {
                            handleIntro(intent)
                        }

                        ACTION_RATCHET -> {
                            handleRatchet(intent)
                        }

                        ACTION_LORA -> {
                            handleLora(intent)
                        }

                        ACTION_LORATX -> {
                            handleLoraTx(intent)
                        }

                        ACTION_LORAPROV -> {
                            handleLoraProv(intent)
                        }

                        ACTION_NANFAIL -> {
                            handleNanFail(intent)
                        }

                        ACTION_NANSTORM -> {
                            handleNanStorm(intent)
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
            pending.resultCode = 0
            pending.resultData = json
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

                // The bridged Meshtastic room is read-only — nothing this device sends could reach it.
                ConversationKind.MESHTASTIC -> {
                    return reply("error", "the bridged Meshtastic room is receive-only: $conv")
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

                is AttachmentStore.IngestResult.Failed -> {
                    return reply("error", "ingest failed (${result.reason}): $path")
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

                // Read-only, and it would carry no attachment even if it were not.
                ConversationKind.MESHTASTIC -> {
                    return reply("error", "the bridged Meshtastic room is receive-only: $conv")
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
        val frameBuffer = createBitmap(movie.width(), movie.height())
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
        // Resolve the thread context from the stored row, exactly as ChatViewModel.react passes it —
        // without it every bridge reaction is broadcast-shaped and rides the cleartext frame, which
        // would make a sealed-reaction smoke test pass vacuously.
        val conv = messages.conversationOf(messageId)
        val group = conv?.takeIf { Conversations.kindFor(it) == ConversationKind.GROUP }?.let { groups.find(it)?.toGroupInfo() }
        val recipientId = conv?.takeIf { group == null && Conversations.kindFor(it) == ConversationKind.DM }
        mesh.sendReaction(messageId, emoji, recipientId, group)
        return reply("ok", "reacted $emoji to $messageId").put("conversation", conv ?: "unknown")
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
                // True when a MeshService.start was refused (backgrounded, unexempted) and is still owed the
                // retry KnitApp's ON_RESUME observer performs — otherwise a dead mesh is indistinguishable
                // from a live one with no peers. Work item #32.
                .put("meshStartDeferred", startGate.deferred.value)
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

    private suspend fun messagesJson(
        rows: List<MessageEntity>,
        selfId: String,
        selfName: String,
        nameByNode: Map<String, String>,
    ): JSONArray {
        // Per-message reaction rows (reactor -> emoji), so a smoke/soak run can verify a reaction
        // APPLIED on the receiving device — the chips render on screen but don't surface any text to
        // `uiautomator dump`, and the sealed forms otherwise have no machine-checkable landing signal.
        val reactionsByMessage = reactions.observeReactions().first().groupBy { it.messageId }
        val arr = JSONArray()
        rows.forEach { m ->
            val from = if (m.senderId == selfId) selfName else nameByNode[m.senderId] ?: ""
            val rx = JSONObject()
            reactionsByMessage[m.id].orEmpty().forEach { r -> r.emoji?.let { rx.put(r.reactorNodeId, it) } }
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("from", m.senderId)
                    .put("fromName", from)
                    .put("mine", m.senderId == selfId)
                    .put("body", m.body)
                    .put("sentAt", m.sentAt)
                    .put("received", m.received)
                    .put("reactions", rx)
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
     * Dumps the rate-prompt gate as the real prompt would evaluate it right now — the inputs come from
     * [ReviewPrompter.gateInputs] / [ReviewPrompter.installedFromPlay], so this can't drift from the
     * production gate, plus the installer-aware [ReviewPrompter.rateUrl] the positive button would open.
     * `--ez reset true` clears the persisted state first; `--ez arm true` additionally backdates the
     * engagement watermark past the age gate (the message-count gates still need real rows, e.g. via
     * [ACTION_SEND] from a second device).
     */
    private suspend fun handleReview(
        context: Context,
        intent: Intent,
    ): JSONObject {
        val now = System.currentTimeMillis()
        if (intent.getBooleanExtra(EXTRA_RESET, false)) settings.clearReviewState()
        if (intent.getBooleanExtra(EXTRA_ARM, false)) {
            settings.clearReviewState()
            settings.setReviewEngagementStartedAt(now - ReviewPromptPolicy.MIN_ENGAGEMENT_AGE_MS - ARM_MARGIN_MS)
        }
        @Suppress("DEPRECATION")
        val installer =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    context.packageManager.getInstallerPackageName(context.packageName)
                }
            }.getOrNull()
        val inputs = reviewPrompter.gateInputs(now)
        return JSONObject()
            .put("status", "ok")
            .put("installer", installer ?: JSONObject.NULL)
            .put("playInstall", reviewPrompter.installedFromPlay())
            .put("rateUrl", reviewPrompter.rateUrl())
            .put("feedbackUrl", reviewPrompter.feedbackUrl)
            .put("peerMessages", inputs.peerMessageCount)
            .put("sentMessages", inputs.sentMessageCount)
            .put("engagementStartedAt", inputs.engagementStartedAt)
            .put("lastAttemptAt", inputs.lastAttemptAt)
            .put("attemptCount", inputs.attemptCount)
            .put("shouldPrompt", ReviewPromptPolicy.shouldPrompt(inputs))
    }

    /**
     * Reads (and optionally clears) the model poison-pill's persisted state — the seam that makes the
     * acceptance test observable without `adb shell dumpsys activity exit-info`, and the only way to see
     * *why* a latch did or did not fire, since the decision turns on a platform exit record the app
     * otherwise never shows.
     */
    private suspend fun handleModel(intent: Intent): JSONObject {
        if (intent.getBooleanExtra(EXTRA_RESET, false)) ModelLoadGuard.ALL.forEach { modelGuard.clear(it) }
        val stamp = modelGuardStamp(BuildConfig.VERSION_CODE, Build.FINGERPRINT.orEmpty())
        val models = JSONArray()
        for (model in ModelLoadGuard.ALL) {
            val state = settings.modelLoadState(model)
            models.put(
                JSONObject()
                    .put("model", model)
                    .put("stamp", state.stamp)
                    .put("stale", state.stamp != stamp)
                    .put("pendingSince", state.pendingSince)
                    .put("fails", state.fails)
                    .put("latched", modelGuard.observeLatched(model).first()),
            )
        }
        val exit = exits.lastExit()
        return JSONObject()
            .put("status", "ok")
            .put("stamp", stamp)
            .put("maxFails", ModelLoadPolicy.MAX_FAILS)
            .put("faultOnLoad", BuildConfig.MODEL_FAULT_ON_LOAD)
            .put("models", models)
            .put(
                "lastExit",
                exit?.let {
                    JSONObject()
                        .put("at", it.at)
                        .put("nativeFault", it.nativeFault)
                        .put("explained", it.explained)
                } ?: JSONObject.NULL,
            )
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
                    // Inbound, so it carries the arrival stamp the real pipeline would have written.
                    arrivedAt = now - i * 1_000L,
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
                // Inbound, so it carries the arrival stamp the real pipeline would have written.
                arrivedAt = now,
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
            .put("dmSealedV2", snap.dmSealedV2)
            .put("dmSealedV3", snap.dmSealedV3)
            .put("ticksUnsigned", snap.ticksUnsigned)
            .put("groupSealedRatchet", snap.groupSealedRatchet)
            .put("groupSealedV1Fallback", snap.groupSealedV1Fallback)
            .put("groupSeedsSent", snap.groupSeedsSent)
            .put("groupSeedsAdopted", snap.groupSeedsAdopted)
            // Read these together: a trickle of adoptions with no local mint is healthy gossip, while
            // mint/adopt alternating on one device is a lineage that is not collapsing (SPOOL_PROTOCOL §3.2).
            .put("groupRootsMinted", snap.groupRootsMinted)
            .put("groupRootsAdopted", snap.groupRootsAdopted)
            .put("dmSealedV1Fallback", snap.dmSealedV1Fallback)
            .put("receiptsSealed", snap.receiptsSealed)
            .put("receiptsSealedFallback", snap.receiptsSealedFallback)
            .put("receiptsCustodied", snap.receiptsCustodied)
            .put("receiptsCoalesced", snap.receiptsCoalesced)
            .put("reactionsSealed", snap.reactionsSealed)
            .put("reactionsSealedFallback", snap.reactionsSealedFallback)
            .put("dropsByReason", JSONObject(snap.dropsByReason.mapKeys { it.key.name }))
            .put("nanServesPeak", snap.nanServesPeak)
            .put("nanAcceptsRefused", snap.nanAcceptsRefused)
            .put("nanIcmKeepaliveFailed", snap.nanIcmKeepaliveFailed)
            .put("nanMsgsAcked", snap.nanMsgsAcked)
            .put("nanMsgSendsFailed", snap.nanMsgSendsFailed)
            .put("filesSentNan", snap.filesSentNan)
            .put("filesSentBt", snap.filesSentBt)
            .put("nanBulkGraceTimeouts", snap.nanBulkGraceTimeouts)
            .put("fastCompactSent", snap.fastCompactSent)
            .put("fastLegacySent", snap.fastLegacySent)
            .put("fastFragSent", snap.fastFragSent)
            .put("fastReassembled", snap.fastReassembled)
            .put("fastTooBig", snap.fastTooBig)
            .put("fastTranscodedSent", snap.fastTranscodedSent)
            .put("transcodeFallbacks", snap.transcodeFallbacks)
            .put("fastDropsByReason", JSONObject(snap.fastDropsByReason.mapKeys { it.key.name }))
            .put("spoolPushed", snap.spoolPushed)
            .put("spoolPulled", snap.spoolPulled)
            .put("spoolBridged", snap.spoolBridged)
            .put("spoolInvalid", snap.spoolInvalid)
            .put("spoolAccounted", snap.spoolAccounted)
            .put("spoolErrors", snap.spoolErrors)
            .put("spoolAttachPushed", snap.spoolAttachPushed)
            .put("spoolAttachPulled", snap.spoolAttachPulled)
            // The two-island photo trial reads as `spoolAttachDeferred` climbing while the devices are
            // together and `spoolAttachPushed` starting within a heal round of separating them.
            .put("spoolAttachDeferred", snap.spoolAttachDeferred)
            .put("loraSent", snap.loraSent)
            .put("loraFragSent", snap.loraFragSent)
            .put("loraTranscoded", snap.loraTranscoded)
            .put("loraPadded", snap.loraPadded)
            .put("loraReceived", snap.loraReceived)
            .put("loraReassembled", snap.loraReassembled)
            .put("loraTooBig", snap.loraTooBig)
            .put("loraDroppedQueue", snap.loraDroppedQueue)
            .put("loraSuppressed", snap.loraSuppressed)
            .put("loraNak", snap.loraNak)
            .put("loraNakByReason", JSONObject(snap.loraNakByReason))
            .put("loraSessionUps", snap.loraSessionUps)
            .put("loraDmSent", snap.loraDmSent)
            .put("loraDmReceived", snap.loraDmReceived)
            .put("loraReoffered", snap.loraReoffered)
            .put("loraProfileRefanSkipped", snap.loraProfileRefanSkipped)
            .put("loraOfferSent", snap.loraOfferSent)
            .put("loraOfferReceived", snap.loraOfferReceived)
            .put("loraBridged", snap.loraBridged)
            .put("loraBridgeRefused", snap.loraBridgeRefused)
            .put("loraPassive", snap.loraPassive)
            .put("loraSkippedLinked", snap.loraSkippedLinked)
            .put("loraTickDeferred", snap.loraTickDeferred)
            // The LongFast bridge's inbound half — the receive-only trial's whole output. `heard` is every
            // chat packet on the public channel, `ingested` what survived the filters, and the difference is
            // itemised in `refusedByReason`; `viaMqtt` is the share that came off somebody's uplink rather
            // than off the air, which is what decides whether hiding those is a setting or the default.
            .put("meshPostHeard", snap.meshPostHeard)
            .put("meshPostIngested", snap.meshPostIngested)
            .put("meshPostViaMqtt", snap.meshPostViaMqtt)
            .put("meshPostPassive", snap.meshPostPassive)
            .put("meshPostRefusedByReason", JSONObject(snap.meshPostRefusedByReason))

    /**
     * Dumps the DM ratchet's per-peer state and, with `--es reset <peerNodeId>`, forces a session reset
     * past the heuristic that normally guards it.
     *
     * Both halves exist because every gate in the recovery path returns **silently**. A peer we hold no
     * prekey for, a peer whose reset floor has not elapsed, and a peer whose heuristic simply has not
     * counted three distinct failures yet all present identically from outside — as a session that does
     * not heal — and they need opposite remedies. The dump names which one it is; the force is the escape
     * hatch for a pair that wedged before a fix shipped, since the recovery path only runs when the
     * heuristic fires and a stuck pair may not be able to produce countable failures at all.
     */
    private suspend fun handleRatchet(intent: Intent): JSONObject {
        val forced = intent.getStringExtra(EXTRA_RESET_PEER)?.takeIf { it.isNotBlank() }?.trim()
        val reply = reply("ok", if (forced == null) "ratchet state" else "reset requested for $forced")
        if (forced != null) {
            val declined = mesh.forceRatchetReset(forced)
            reply.put("forcedReset", forced).put("declined", declined ?: JSONObject.NULL)
        }
        val peers = JSONArray()
        mesh.ratchetState().forEach { p ->
            peers.put(
                JSONObject()
                    .put("peer", p.peerId)
                    .put("name", p.name)
                    // The X3DH inputs. Any false/null here and a reset from THIS side is impossible,
                    // whatever the heuristic decides — the peer's profile has to land first.
                    .put("capRatchet", p.capRatchet)
                    .put("peerPrekeyId", p.peerPrekeyId ?: JSONObject.NULL)
                    .put("peerPrekeyPinned", p.peerPrekeyPinned)
                    // `hasSession` without `confirmed` is a session the scope table will not export, so
                    // the thread also reads "Not covered by relays yet" while looking otherwise healthy.
                    .put("hasSession", p.hasSession)
                    .put("confirmed", p.confirmed)
                    .put("sendEpoch", p.sendEpoch)
                    // The per-peer floor's anchor: a recent value is why an otherwise-eligible reset is
                    // not being sent, and is exactly when `--es reset` is the right tool.
                    .put("lastResetSentAt", p.lastResetSentAt)
                    // Era forensics: two devices whose `rootHash` disagree are in different eras, and
                    // `establishedAt` says whose is older. `localEpochs` is the EPOCH_GONE ground truth —
                    // each entry is one of OUR epoch privs as "epoch@createdAt", so a peer sealing against
                    // an epoch number missing here (or one whose createdAt predates the current era) is the
                    // diagnosis in one line.
                    .put("establishedAt", p.establishedAt)
                    .put("weAreInitiator", p.weAreInitiator)
                    .put("highestPeAcked", p.highestPeAcked)
                    .put("rootHash", p.rootHash ?: JSONObject.NULL)
                    .put("prevRootExpiresAt", p.prevRootExpiresAt)
                    .put("hasPeerInitAnchor", p.hasPeerInitAnchor)
                    .put("localEpochs", JSONArray(p.localEpochs.map { (epoch, at) -> "$epoch@$at" })),
            )
        }
        return reply.put("peers", peers)
    }

    /**
     * Drives the contact-card flow (docs/CONTACT_CARD.md) on a locked lab device: `--ez card true` mints
     * and prints this device's link, `--es import '<link>'` previews + imports one (single-quoted — the
     * shell splits on spaces), and no extras dumps the intro driver's pending/grace sets plus counters.
     */
    private suspend fun handleIntro(intent: Intent): JSONObject {
        val result = JSONObject().put("status", "ok")
        if (intent.getBooleanExtra(EXTRA_CARD, false)) {
            val minted = contactCards.mint()
            result.put("url", minted.url).put("schemeUrl", minted.schemeUrl)
        }
        intent.getStringExtra(EXTRA_IMPORT)?.takeIf { it.isNotBlank() }?.let { text ->
            val preview =
                contactImporter.preview(
                    app.getknit.knit.mesh.crypto.ContactCard
                        .parse(text),
                )
            result.put("preview", preview.toString())
            if (preview is app.getknit.knit.contacts.ContactImporter.Preview.Ready) {
                contactImporter.import(preview, unblock = false)
                result.put("imported", preview.nodeId)
            }
        }
        return result
            .put("pending", JSONArray(settings.pendingIntros.first().toList()))
            .put("grace", JSONArray(settings.introGrace.first().toList()))
            .put("counters", metricsJson(metrics.snapshot()))
    }

    /**
     * Configures and inspects the Internet (spool) plane — the only way to drive it on a locked lab
     * device, since there is no spool-list editor in the UI yet. With no extras it just dumps state.
     *
     * `--es url <ws(s)://host:port/spool/v1[?k=token]>` adds a spool, `--es drop <url>` removes one, and
     * `--ez on <true|false>` flips the global opt-in. `--es park <url>` / `--es unpark <url>` flip one
     * relay's own switch, which is how a soak run takes a single spool out of the rotation without losing
     * its bearer token — the dump's `disabled` array reports the result, and the parked URL leaves
     * `spools[]` within one `ScopeSync` reconcile tick as its worker stops. Debug builds accept plain
     * `ws://` (a LAN daemon terminates no TLS of its own); release refuses it at dial time regardless of
     * what is stored here.
     *
     * The dump's `local` and `spool` counts per scope are the convergence oracle: they agree once the
     * heal loop has settled, exactly like `liveFingerprint` parity for mesh custody.
     */

    private suspend fun handleSpool(intent: Intent): JSONObject {
        intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }?.let { settings.addSpoolUrl(it.trim()) }
        intent.getStringExtra(EXTRA_DROP)?.takeIf { it.isNotBlank() }?.let { settings.removeSpoolUrl(it.trim()) }
        intent.getStringExtra(EXTRA_PARK)?.takeIf { it.isNotBlank() }?.let { settings.setSpoolUrlEnabled(it.trim(), false) }
        intent.getStringExtra(EXTRA_UNPARK)?.takeIf { it.isNotBlank() }?.let { settings.setSpoolUrlEnabled(it.trim(), true) }
        if (intent.hasExtra(EXTRA_ON)) settings.setSpoolEnabled(intent.getBooleanExtra(EXTRA_ON, false))

        val spools = JSONArray()
        mesh.spoolStatus().forEach { spool ->
            val scopes = JSONArray()
            spool.scopes.forEach { scope ->
                scopes.put(
                    JSONObject()
                        .put("scope", scope.scopeHex)
                        // A DM peer's node id or, for a group scope, the group id.
                        .put("of", scope.label)
                        .put("local", scope.localCount)
                        .put("spool", scope.spoolCount)
                        .put("converged", scope.converged)
                        .put("invalid", scope.invalidCount)
                        // How much of `local` is the §9.6 accounted band rather than custody — blobs the
                        // spool still holds that our custody has aged out. Counted in `local` on purpose,
                        // so `local == spool` keeps meaning converged.
                        .put("accounted", scope.accountedCount)
                        // A retiring scope is drained, never refilled, so local > spool for its whole
                        // drain window — expected, not divergence.
                        .put("retiring", scope.retiring)
                        // Millis since this scope's own peer last put a recent frame into it, or -1 if it
                        // never has. The ONLY field here that says anything about the peer: everything
                        // above is equally true of a scope whose peer has been switched off for weeks,
                        // because a scope is derived from the pairwise root and stays subscribed and
                        // converged regardless (ADR 2026-09.2ajk).
                        .put("peerSeenAgoMs", scope.peerSeenAt?.let { System.currentTimeMillis() - it } ?: -1L),
                )
            }
            spools.put(
                JSONObject()
                    .put("url", spool.url)
                    .put("connected", spool.connected)
                    .put("powBits", spool.powBits)
                    .put("lastError", spool.lastError ?: JSONObject.NULL)
                    // null ⇒ this spool advertised no attachment support at all (spec §7.3), which is
                    // also what makes the UI mark a photo "nearby only" — worth being able to confirm
                    // from the bridge when a field test sees that marker.
                    .put("maxAttachBytes", spool.maxAttachBytes ?: JSONObject.NULL)
                    .put("scopes", scopes),
            )
        }
        return JSONObject()
            .put("status", "ok")
            .put("enabled", settings.spoolEnabled.first())
            .put("configured", JSONArray(settings.spoolUrls.first().toList()))
            .put("disabled", JSONArray(settings.disabledSpoolUrls.first().toList()))
            .put("spools", spools)
            .put("counters", metricsJson(metrics.snapshot()))
    }

    /**
     * Configures and inspects the LoRa (Meshtastic) plane — the only way to drive it on a locked lab
     * device. `--es address <MAC>` + `--es name <n>` binds a bonded board, `--ei channel <idx>` sets the
     * channel index, `--ez on <true|false>` flips the switch, `--ez dms <true|false>` the private-messages
     * switch (ADR 039); no extras just dumps status. The reply's
     * `state`/`heard`/counters are the field oracle for the two-board range trial.
     */
    private suspend fun handleLora(intent: Intent): JSONObject {
        val address = intent.getStringExtra(EXTRA_ADDRESS)?.takeIf { it.isNotBlank() }
        if (address != null) settings.setLoraDevice(address.trim(), intent.getStringExtra("name")?.trim() ?: address.trim())
        if (intent.hasExtra("channel")) settings.setLoraChannelIndex(intent.getIntExtra("channel", 0))
        if (intent.hasExtra(EXTRA_ON)) settings.setLoraEnabled(intent.getBooleanExtra(EXTRA_ON, false))
        if (intent.hasExtra("dms")) settings.setLoraDmEnabled(intent.getBooleanExtra("dms", true))
        if (intent.hasExtra("bridge")) settings.setLoraBridgeEnabled(intent.getBooleanExtra("bridge", true))

        val status = lora.status.value
        return JSONObject()
            .put("status", "ok")
            .put("enabled", settings.loraEnabled.first())
            .put("dms", settings.loraDmEnabled.first())
            .put("bridge", settings.loraBridgeEnabled.first())
            .put("address", settings.loraDeviceAddress.first() ?: JSONObject.NULL)
            .put("channel", settings.loraChannelIndex.first())
            .put("state", status.state::class.simpleName)
            .put("boardNodeNum", status.boardNodeNum?.let { "!%08x".format(it.toInt()) } ?: JSONObject.NULL)
            .put("snr", status.lastSnr?.toDouble() ?: JSONObject.NULL)
            .put("rssi", status.lastRssi ?: JSONObject.NULL)
            .put("queueFree", status.queueFree ?: JSONObject.NULL)
            .put("heard", status.heard) // frame authors, relayed and backfilled ones included
            .put("boardsHeard", status.boardsHeard) // radios on air — "how many boards can I hear"
            // The bridge's own oracle (ADR 044): which board here speaks for the pocket, what the radio is,
            // and how much of the hour's airtime allowance has gone — the numbers a two-pocket trial reads.
            .put("role", status.role.name)
            // The election's inputs, not just its verdict: "why is this board listening?" is otherwise
            // unanswerable in the field, which is exactly how the reachable-vs-linked bug survived review.
            .put("pocketLinks", status.pocketLinks)
            .put("gatewaysHeard", status.gatewaysHeard)
            // pocketSightings > pocketLinks is the shape of the field failure: peers heard but not linked.
            .put("pocketSightings", status.pocketSightings)
            .put("radio", status.airtime?.let { "${it.region}/${it.preset}${if (it.known) "" else " (assumed)"}" } ?: JSONObject.NULL)
            // ADR 067: true means the board is on its own RF slot, so the budgets below are off the
            // politeness ceiling and bounded only by the region's duty cycle.
            .put("dedicated", status.airtime?.dedicated ?: JSONObject.NULL)
            // ADR 2026-09.mhs5: whether this board's firmware signs what we hand it, and so whether the budget charges
            // for that signature and the codec pads past its cliff. Without it neither is observable here.
            .put("signing", status.airtime?.signing ?: JSONObject.NULL)
            .put(
                "airtime",
                status.airtime?.let { air ->
                    JSONObject()
                        .put("liveMs", air.liveUsedMs)
                        .put("liveBudgetMs", air.liveBudgetMs)
                        .put("bridgeMs", air.bridgeUsedMs)
                        .put("bridgeBudgetMs", air.bridgeBudgetMs)
                        .put("bootstrapMs", air.bootstrapUsedMs)
                        .put("bootstrapBudgetMs", air.bootstrapBudgetMs)
                } ?: JSONObject.NULL,
            ).put("counters", metricsJson(metrics.snapshot()))
    }

    /**
     * Sends a raw UTF-8 payload straight to the board on the configured channel (bypassing the frame
     * codec), so a board's LoRa transmission can be confirmed from the other end (`meshtastic --noproto`)
     * without the whole mesh. Requires the plane enabled and the board Ready.
     *
     * `--ei hop <n>` sets `MeshPacket.hop_limit` explicitly instead of leaving it out. The production path
     * omits the field, on the documented assumption that the firmware fills in the node's configured
     * default; on 2.8 it does not, and every Knit packet reaches the air with `hop_limit = 0` — unrelayable
     * by the stock nodes ADR 045 borrows hops from. This is the knob that tells a firmware that will not
     * relay a secondary-channel packet apart from a packet that was never relayable to begin with.
     */
    private suspend fun handleLoraTx(intent: Intent): JSONObject {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return reply("error", "missing --es text")
        val channel = settings.loraChannelIndex.first()
        val hop = if (intent.hasExtra("hop")) intent.getIntExtra("hop", 0) else null
        val result = loraLink.send(text.encodeToByteArray(), channel, hopLimit = hop)
        return reply("ok", "sent")
            .put("result", result::class.simpleName)
            .put("channel", channel)
            .put("hopLimit", hop ?: JSONObject.NULL)
    }

    /**
     * Sets the connected board up for Knit over the Meshtastic admin API (the headless equivalent of the
     * settings screen's one setup button), and — on success — binds the plane to it. Requires the board
     * Ready.
     *
     * `--es mode restore` puts the board back the way it was instead. This is the two-board oracle for the
     * ADR 045 trial, so it persists exactly what the settings screen would.
     */
    private suspend fun handleLoraProv(intent: Intent): JSONObject {
        val mode =
            when (intent.getStringExtra(EXTRA_MODE)?.lowercase()) {
                "restore" -> ProvisionMode.Restore

                // ADR 067's debug-only setup, so that a dedicated-frequency trial is drivable headlessly
                // exactly like the shared-frequency one.
                "dedicated" -> ProvisionMode.SetupDedicated

                else -> ProvisionMode.Setup
            }
        val recorded = settings.loraBoardSetup.first()
        val result =
            lora.provisionKnitChannel(
                mode,
                recorded?.let {
                    BoardSettings(
                        nodeInfoSecs = it.nodeInfoSecs,
                        positionSecs = it.positionSecs,
                        smartPosition = it.smartPosition,
                        telemetrySecs = it.telemetrySecs,
                        rebroadcastMode = it.rebroadcastMode,
                        owner =
                            if (it.longName.isEmpty() && it.shortName.isEmpty()) {
                                null
                            } else {
                                BoardOwner(it.longName, it.shortName)
                            },
                        channelNum = it.channelNum,
                    )
                },
            )
        val address = settings.loraDeviceAddress.first()
        when (result) {
            is app.getknit.knit.mesh.lora.ProvisionResult.Provisioned -> {
                settings.setLoraChannelIndex(result.index)
                val previous = result.previous
                if (previous != null && address != null) {
                    settings.setLoraBoardSetup(
                        KnitBoardSetup(
                            address = address,
                            nodeInfoSecs = previous.nodeInfoSecs,
                            positionSecs = previous.positionSecs,
                            smartPosition = previous.smartPosition,
                            telemetrySecs = previous.telemetrySecs,
                            rebroadcastMode = previous.rebroadcastMode,
                            longName = previous.owner?.longName.orEmpty(),
                            shortName = previous.owner?.shortName.orEmpty(),
                            channelNum = previous.channelNum,
                        ),
                    )
                }
            }

            // Restoring leaves no Knit channel on the board, so the plane goes off with it — same as the UI.
            app.getknit.knit.mesh.lora.ProvisionResult.Restored -> {
                settings.clearLoraBoardSetup()
                settings.setLoraEnabled(false)
            }

            else -> {
                // NoDedicatedSlot / Failed — nothing to persist; the reply below carries the outcome.
            }
        }
        val index =
            when (result) {
                is app.getknit.knit.mesh.lora.ProvisionResult.Provisioned -> result.index
                else -> -1
            }
        return reply("ok", "provision requested")
            .put("mode", mode.name)
            .put("result", result::class.simpleName)
            .put("channel", if (index >= 0) index else JSONObject.NULL)
            .put("state", lora.status.value.state::class.simpleName)
    }

    private fun reply(
        status: String,
        message: String,
    ): JSONObject = JSONObject().put("status", status).put("message", message)

    /**
     * Creates (or reopens) a group locally from `--es members <comma-separated peer nodeIds>` (self is
     * added automatically) — the [app.getknit.knit.ui.contacts.ContactsViewModel.createGroup] mechanics
     * without the picker UI, which cannot express a 2-member group (one selection opens a DM). Purely
     * local, exactly like UI creation: other members learn of the group from its first message. The
     * reply carries the derived `groupId` for follow-up `SEND --es conv` calls.
     */
    private suspend fun handleMkGroup(intent: Intent): JSONObject {
        val others =
            intent
                .getStringExtra("members")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        if (others.isEmpty()) return reply("error", "missing --es members <nodeId,nodeId,...>")
        val me = identity.nodeId()
        val members = (others + me).distinct()
        val groupId = Conversations.groupIdFor(members)
        val existing = groups.find(groupId)
        if (existing == null || existing.left) {
            groups.upsert(
                GroupEntity(
                    groupId = groupId,
                    name = intent.getStringExtra("name").orEmpty(),
                    members = GroupMembersStore.encode(members),
                    createdBy = me,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        return reply("ok", "group ready").put("groupId", groupId).put("members", members.size)
    }

    /**
     * Arms (or disarms, with `count` 0 or absent) forced Wi-Fi Aware attach failures — the lab stand-in for
     * getknit/Knit#9's chipset. Pair it with [ACTION_NANSTORM]: the failures are what make the attach path
     * reachable at all, since a transport that is attached returns from `attach()` before any of it.
     */
    private fun handleNanFail(intent: Intent): JSONObject {
        val count = intent.getIntExtra("count", 0)
        val armed = NanFaultInjector.armFailures(count)
        val what = if (armed > 0) "armed $armed forced attach failures" else "fault injection disarmed"
        return reply("ok", what).put("armed", armed).withNanState()
    }

    /**
     * Replays the availability storm from getknit/Knit#9 (ADR 055) against the running transport, and reports
     * how many attaches got through. Pre-fix that number tracked the broadcast count; post-fix the rate floor
     * holds it near `elapsedMs / 3000`, whatever the storm does.
     */
    private suspend fun handleNanStorm(intent: Intent): JSONObject {
        if (!NanFaultInjector.bound) return reply("error", "Wi-Fi Aware transport is not running")
        val count = intent.getIntExtra("count", 100).coerceIn(1, 100_000)
        val hz = intent.getIntExtra("hz", 300).coerceIn(1, 10_000)
        val cycle = intent.getBooleanExtra("cycle", false)
        val before = NanFaultInjector.status()
        val periodMs = 1_000L / hz
        val startedAt = SystemClock.elapsedRealtime()
        repeat(count) { i ->
            // Repeating `true` is the bug's shape; alternating is a run of genuine recoveries.
            NanFaultInjector.notifyAvailability(!cycle || i % 2 == 1)
            if (periodMs > 0) delay(periodMs)
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val after = NanFaultInjector.status()
        return reply("ok", if (cycle) "storm sent (alternating edges)" else "storm sent (repeated available)")
            .put("sent", count)
            .put("elapsedMs", elapsed)
            .put("ratePerSec", if (elapsed > 0) count * 1_000L / elapsed else -1)
            .put("failuresBefore", before?.total ?: -1)
            .put("failuresAfter", after?.total ?: -1)
            .put("attachesAllowed", (after?.total ?: 0) - (before?.total ?: 0))
            .withNanState()
    }

    /**
     * Folds the attach budget and this process's Binder-object counts into a reply. The local count is the
     * closest in-process proxy for what AMS is actually watching, which is the count *system_server* holds
     * against our uid — for that, read `adb shell dumpsys activity binder-proxies`.
     */
    private fun JSONObject.withNanState(): JSONObject {
        val nan = NanFaultInjector.status()
        return put("attached", nan?.attached ?: false)
            .put("streak", nan?.streak ?: -1)
            .put("failTotal", nan?.total ?: -1)
            .put("abandoned", nan?.abandoned ?: false)
            .put("retryInMs", nan?.retryInMs ?: -1)
            .put("localBinders", Debug.getBinderLocalObjectCount())
            .put("binderDeathRecipients", Debug.getBinderDeathObjectCount())
    }

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
        const val ACTION_NANFAIL = "app.getknit.knit.debug.NANFAIL"
        const val ACTION_NANSTORM = "app.getknit.knit.debug.NANSTORM"
        const val ACTION_REQNOTIF = "app.getknit.knit.debug.REQNOTIF"
        const val ACTION_FLAGMSG = "app.getknit.knit.debug.FLAGMSG"
        const val ACTION_MKGROUP = "app.getknit.knit.debug.MKGROUP"
        const val ACTION_REVIEW = "app.getknit.knit.debug.REVIEW"
        const val ACTION_MODEL = "app.getknit.knit.debug.MODEL"
        const val ACTION_SPOOL = "app.getknit.knit.debug.SPOOL"
        const val ACTION_INTRO = "app.getknit.knit.debug.INTRO"
        const val ACTION_RATCHET = "app.getknit.knit.debug.RATCHET"
        const val ACTION_LORA = "app.getknit.knit.debug.LORA"
        const val ACTION_LORATX = "app.getknit.knit.debug.LORATX"
        const val ACTION_LORAPROV = "app.getknit.knit.debug.LORAPROV"

        const val EXTRA_TEXT = "text"
        const val EXTRA_ADDRESS = "address"
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
        const val EXTRA_RESET = "reset"
        const val EXTRA_ARM = "arm"
        const val EXTRA_URL = "url"
        const val EXTRA_CARD = "card"
        const val EXTRA_IMPORT = "import"
        const val EXTRA_ON = "on"
        const val EXTRA_MODE = "mode"
        const val EXTRA_DROP = "drop"
        const val EXTRA_PARK = "park"
        const val EXTRA_UNPARK = "unpark"
        const val EXTRA_RESET_PEER = "reset"

        /** Default sender + hidden body for [ACTION_FLAGMSG]'s synthetic flagged inbound message. */
        const val FLAGGED_SENDER_ID = "flagger0"
        const val FLAGGED_SENDER_NAME = "Flagged Sender"
        const val DEFAULT_FLAGGED_BODY = "[flagged demo message]"

        /** Cap on the synthetic requests [ACTION_REQNOTIF] injects (keeps each stranger's node-id single-digit). */
        const val MAX_REQNOTIF = 9

        /** How far past the age gate [ACTION_REVIEW]'s arm backdates the watermark (clock-skew slack). */
        const val ARM_MARGIN_MS = 60_000L

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
