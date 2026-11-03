@file:Suppress("MagicNumber")

package app.getknit.knit.demo

import android.content.Context
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.forward.ForwardDao
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.CallRecord
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.StyleStore
import app.getknit.knit.data.message.withReply
import app.getknit.knit.data.message.withVideo
import app.getknit.knit.data.peer.MetPeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.data.video.VideoFrames
import app.getknit.knit.di.CALLS_WIRED
import app.getknit.knit.di.LIVE_CALLS_WIRED
import app.getknit.knit.di.STT_WIRED
import app.getknit.knit.di.WALKIE_WIRED
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.mesh.protocol.DeclaredLangs
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.StyleSpan
import app.getknit.knit.mesh.protocol.StyleSpans
import app.getknit.knit.mesh.protocol.VideoInfo
import app.getknit.knit.ui.whatsnew.WhatsNew
import kotlinx.coroutines.flow.first
import org.koin.core.Koin
import java.security.MessageDigest
import java.util.Locale

/**
 * Shared write primitives for the demo builds: they persist a [DemoScenario]'s content through the same
 * repositories the real app uses, so the reactive flows repopulate every screen with no UI changes. Both
 * the static screenshot seeder ([DemoSeeder]) and the animated trailer ([DemoDirector]) drive these — the
 * seeder writes the whole history up front; the director writes it beat-by-beat with `now` timestamps so it
 * animates in live. All writes are idempotent upserts keyed by stable ids.
 *
 * The recurring `60_000L` is the minutes->millis factor for the fixture offsets, so [MagicNumber] is
 * suppressed for the whole file.
 */
class DemoWriter(
    koin: Koin,
    private val scenario: DemoScenario,
    private val me: String,
    // Every message in the scenario, keyed by id — so a reply can resolve the message it quotes (for the
    // denormalized author/snippet, mirroring what a real inbound reply carries).
    private val msgById: Map<String, DemoMsg>,
    // Avatar keys read from another key's file: a mirrored scenario's local profile wears the peer's face, and the
    // peer ours (`DemoScenario.mirrored`).
    private val avatarAlias: Map<String, String> = emptyMap(),
) {
    private val peers = koin.get<PeerRepository>()
    private val messages = koin.get<MessageRepository>()
    private val reactions = koin.get<ReactionRepository>()
    private val receipts = koin.get<MessageReceiptRepository>()
    private val groups = koin.get<GroupRepository>()
    private val settings = koin.get<SettingsStore>()
    private val blobs = koin.get<BlobRepository>()
    private val metPeers = koin.get<MetPeerRepository>()
    private val forwardDao = koin.get<ForwardDao>()
    private val context = koin.get<Context>()

    /**
     * Sets the local profile (name/status/avatar), pins self as a peer row, and upserts the scenario cast.
     * The self peer row feeds the self-referential UI (the group details "You" row) that resolves names
     * against the peer table; self is filtered out of the contact/diagnostics lists, so it's harmless there.
     */
    suspend fun seedProfileAndPeers(now: Long) {
        val myAvatar = avatar("me")
        settings.setDisplayName(scenario.meName)
        settings.setStatus(scenario.meStatus)
        myAvatar?.let { settings.setOwnAvatarHash(it) }
        peers.upsert(
            PeerEntity(nodeId = me, name = scenario.meName, status = scenario.meStatus, avatarHash = myAvatar, updatedAt = now),
        )
        scenario.peers.forEach { p ->
            peers.upsert(
                PeerEntity(
                    nodeId = nodeId(p.slot),
                    name = p.name,
                    status = p.status,
                    avatarHash = avatar(nodeId(p.slot)),
                    // A verified peer needs a pinned key + the out-of-band-confirmed flag for the badge.
                    pubKey = if (p.verified) "demo" else null,
                    verified = p.verified,
                    updatedAt = now,
                    openToChat = p.openToChat,
                    // The board a contact's own profile claims, and the key it signs under — what lets a post
                    // heard on the radio channel resolve to them, and the Profile Details "their board" row
                    // have something to name.
                    loraNode = p.loraNode,
                    loraKey = p.loraKey,
                    // Heard from just now: a seeded namesake (the second "Jonas W.") stays a live clash, so the
                    // name-clash fade never quietly un-suffixes a captured screen (ADR 2026-10.k8ge).
                    lastSeenAt = now,
                    // The call kinds this build takes, advertised as a profile would: the chat's Call and Mesh call
                    // place a call only toward a peer that runs one (C-3.1-2, C-3.11-1). None where calls are not wired.
                    capabilities = DEMO_CALL_CAPS.takeIf { it != 0L },
                ),
            )
        }
        // Our own availability flag, on: the Profile screen's switch is a control worth photographing in its
        // set state, and it is the local half of the cue the peer badges are the remote half of. Not a
        // scenario field — it reads the same at a trailhead and on the playa, like the planes.
        settings.setOpenToChat(true)
        // The room's "never sent over the Internet" notice, already dismissed. It is a first-run banner a
        // real user clears once and never sees again, so leaving it up puts a transient state across the top
        // of every capture of the room — and it is the one shot where the messages are the subject.
        settings.dismissRelayRoomNotice()
        // What's New, already read, for the same reason: a store capture of the chat list is about the chats.
        WhatsNew.latest?.let { settings.markWhatsNewSeen(it.id) }
    }

    /** Writes the full Nearby history + a read watermark leaving the latest message unread (a "1" badge). */
    suspend fun seedNearby(now: Long) {
        scenario.nearby.forEach { write(it, Conversations.NEARBY, dmPeer = null, now) }
        settings.setLastReadAt(Conversations.NEARBY, now - scenario.nearbyReadMinsAgo * 60_000L)
    }

    /** Writes each DM thread; a read thread gets a watermark (no badge), an unread one leaves a count. */
    suspend fun seedDms(now: Long) {
        scenario.dms.forEach { thread ->
            val peer = nodeId(thread.peer)
            thread.messages.forEach { write(it, conversationId = peer, dmPeer = peer, now) }
            if (thread.read) settings.setLastReadAt(peer, now)
        }
    }

    /**
     * Upserts [group] and writes its history; returns the group id. [read] false leaves the thread
     * without a read watermark, which is what a group the user has not opened — a **request** — looks
     * like; the accepted group passes true, as it always did.
     */
    suspend fun seedGroup(
        group: DemoGroup,
        now: Long,
        read: Boolean = true,
    ): String {
        val members = group.members.map { nodeId(it) }
        val groupId = Conversations.groupIdFor(members)
        val opener = group.messages.first() // lists are oldest-first -> first = creation
        groups.upsert(
            GroupEntity(
                groupId = groupId,
                name = group.name,
                members = GroupMembersStore.encode(members),
                createdBy = nodeId(opener.from),
                createdAt = now - opener.minsAgo * 60_000L,
                nameUpdatedAt = now - opener.minsAgo * 60_000L,
            ),
        )
        group.messages.forEach { write(it, conversationId = groupId, dmPeer = null, now) }
        if (read) settings.setLastReadAt(groupId, now)
        return groupId
    }

    /**
     * Writes the scenario's stranger threads — the DM requests and the group a stranger added us to —
     * **without** accepting any of them, so `Conversations.isAccepted` keeps them out of the chat list and
     * in the Message Requests inbox (whose badge then appears on the chat list). Deliberately not folded
     * into [seedDms]: the difference between a chat and a request is exactly the absence of an accept, and
     * a single list would make that absence an easy thing to lose.
     */
    suspend fun seedRequests(now: Long) {
        scenario.requests.forEach { thread ->
            val peer = nodeId(thread.peer)
            thread.messages.forEach { write(it, conversationId = peer, dmPeer = peer, now) }
            if (thread.read) settings.setLastReadAt(peer, now)
        }
        scenario.requestGroup?.let { seedGroup(it, now, read = false) }
    }

    /**
     * Writes the scenario's call lines into their DM threads (§3.12) through the same [CallRecord] a finished call
     * leaves, so the thread draws the real row. Only in a build that wires calls: a build without them never has one.
     */
    suspend fun seedCallRecords(now: Long) {
        if (!CALLS_WIRED) return
        scenario.calls.forEach { c ->
            val startedAt = now - c.minsAgo * 60_000L
            // Connected a few seconds after it began: the ring.
            val connectedAt = c.seconds?.let { startedAt + 6_000L }
            val record =
                CallRecord(
                    id = c.id,
                    outgoing = c.outgoing,
                    walkie = c.walkie,
                    reason = CallPayload.REASON_USER,
                    byPeer = !c.outgoing,
                    missed = c.missed,
                    connectedAt = connectedAt,
                    endedAt = c.seconds?.let { secs -> connectedAt?.plus(secs * 1_000L) },
                )
            messages.save(record.toEntity(peerId = nodeId(c.peer), selfId = me, startedAt = startedAt))
        }
    }

    /** Blocks the scenario's blocked slots, so "Blocked users" has rows instead of its empty state. */
    suspend fun seedBlocked() {
        scenario.blocked.forEach { settings.block(nodeId(it), deviceTag = null) }
    }

    /**
     * Gives the Your mesh screen a history: lifetime numbers banked straight into the journal, the whole
     * cast plus a crowd of one-time strangers as met, and a few frames held in custody for other people.
     * The custody rows go in through the DAO with empty bytes: the demo build never starts the mesh, so
     * nothing re-serves them, and the `ForwardRepository` digest is deliberately left untouched — there is
     * no cue plane here for it to drive. Every stat therefore reads non-zero without a radio in the room.
     *
     * The journal is additive by design (no read-before-write, so flushes never race), and the seed runs on
     * every cold start of the demo build — so the lifetime numbers are banked only into an empty journal, or
     * each screenshot launch would add another 212 to the one before it.
     */
    suspend fun seedYourMesh(now: Long) {
        if (settings.contributionTotals.first().passedAlong == 0L) {
            settings.addContributions(
                passedAlong = YOUR_MESH_PASSED_ALONG,
                deliveredToRecipient = YOUR_MESH_HANDED_DIRECT,
                now =
                    now - YOUR_MESH_SINCE_MS,
            )
        }
        val strangers = (1..YOUR_MESH_STRANGERS).map { "stranger-%02d".format(Locale.ROOT, it) }
        metPeers.recordMet(scenario.peers.map { nodeId(it.slot) } + strangers, now)
        repeat(YOUR_MESH_CARRYING) { i ->
            forwardDao.insert(
                ForwardEntity(
                    id = "demo-carried-$i",
                    recipientId = nodeId(Slot.THEO),
                    groupId = null,
                    senderId = nodeId(Slot.SAM),
                    type = FrameType.CHAT,
                    origin = ForwardStore.ORIGIN_RELAY,
                    signed = ByteArray(0),
                    sig = ByteArray(0),
                    sentAt = now - (i + 1) * 60_000L,
                    receivedAt = now - (i + 1) * 60_000L,
                    expiresAt = now + 60 * 60_000L,
                ),
            )
        }
    }

    /**
     * Writes the Meshtastic room's history — the paired board's own primary channel, mirrored into a room on
     * this phone and nowhere else (ADR 2026-09.26q3). Its own writer rather than a [write] call, because
     * almost every column means something different here:
     *
     * - [MessageEntity.senderId] is **this phone** on every row, heard posts included — there is no frame and
     *   no signer, the row is written by the phone whose board heard it — and the `origin*` columns beside it
     *   are what say the words are somebody else's.
     * - A heard post is not "ours" despite that sender, so `received` stays false and `arrivedAt` is stamped;
     *   one of *our* posts leaves through the board and never earns a ✓✓, because nothing on the channel acks,
     *   so it is `received = false` too — the difference is [DemoMeshPost.mine], which clears `originNode`.
     * - Everything the radio reported is denormalized on the row, resolved **once**, at ingest: the channel it
     *   was heard on, the hops and SNR, the contact whose profile claimed the speaker's number, and what the
     *   XEdDSA signature proved. Boards change hands; re-resolving later would put old words under whoever
     *   holds the board now.
     *
     * There are no receipts, reactions or read watermark: the room takes none of them.
     */
    suspend fun seedMeshRoom(now: Long) {
        scenario.meshRoom.forEach { post ->
            val heard = !post.mine
            messages.save(
                MessageEntity(
                    id = post.id,
                    senderId = me,
                    conversationId = Conversations.MESHTASTIC,
                    body = post.body,
                    sentAt = now - post.minsAgo * 60_000L,
                    arrivedAt = if (heard) now - post.minsAgo * 60_000L + 30_000L else null,
                    received = false,
                    receivedVia = DeliveryPlane.LoRa.code,
                    originNode = if (heard) post.node else null,
                    originName = post.name.takeIf { heard },
                    originChannel = MESH_ROOM_CHANNEL.takeIf { heard },
                    originHops = post.hops.takeIf { heard },
                    originSnrDeci = post.snrDeci.takeIf { heard },
                    originViaMqtt = heard && post.viaMqtt,
                    originPeerId = post.peer?.takeIf { heard }?.let { nodeId(it) },
                    originSigned = if (heard) post.signed else MessageEntity.ORIGIN_UNSIGNED,
                ),
            )
        }
    }

    /**
     * Writes one [DemoMsg] (message row + any inline reactions + any per-member delivery receipts). For a
     * DM, [dmPeer] is the other party so the
     * recipient is set per direction; for the room/group it's null. [received] doubles as the delivery tick and
     * is only meaningful for our own outbound messages, so it tracks "is this mine". A [DemoMsg.image] is
     * ingested as a plaintext blob (JPEG scene photo or animated WebP) and pinned via [MessageEntity.attachmentHash].
     *
     * [DemoMsg.via] is written to `receivedVia` for every row and carried onto the seeded receipts, so the
     * plane glyphs (globe / board) render on the bubble, on the ✓✓ tick and on the per-recipient rows of
     * "Message info" from one field.
     */
    suspend fun write(
        m: DemoMsg,
        conversationId: String,
        dmPeer: String?,
        now: Long,
    ) {
        val fromMe = m.from == Slot.ME
        val voice = m.voiceSeconds?.let { voiceBlob(it) }
        val imageHash = if (voice == null) m.image?.let { imageBlob(it, m.imageMime) } else null
        // One attachment per message, in the order the fields are declared: a card only when neither a voice
        // note nor a photo took the slot, and a video only when nothing else did.
        val linkHash = if (voice == null && imageHash == null) m.link?.let { linkBlob(it) } else null
        val video = if (voice == null && imageHash == null && linkHash == null) m.video?.let { videoBlob(it) } else null
        messages.save(
            MessageEntity(
                id = m.id,
                senderId = nodeId(m.from),
                recipientId = dmPeer?.let { if (fromMe) it else me },
                conversationId = conversationId,
                body = m.body,
                sentAt = now - m.minsAgo * 60_000L,
                // Seeded rather than observed, for the same reason the voice metadata below is: a seeded row
                // never arrives, so write what the real inbound path would have written. Half a minute after
                // the send — a plausible one-hop lag that can't land in the future for any minsAgo >= 1.
                arrivedAt = if (fromMe) null else now - m.minsAgo * 60_000L + 30_000L,
                received = fromMe,
                // The plane, in the same place the real path writes it: an inbound row is its own proof,
                // one of ours learns it from the receipt that flipped `received`.
                receivedVia = m.via.code,
                mentions =
                    MentionStore.encode(
                        if (m.mentionsMe) listOf(Mention(me, scenario.meName)) else emptyList(),
                    ),
                attachmentHash = voice?.hash ?: imageHash ?: linkHash ?: video?.hash,
                // Plaintext blob (attachmentKey stays null) → BlobFetcher decodes the bytes directly. A card
                // rides the same slot under its own MIME, which is the whole of how it is told apart.
                attachmentMime =
                    when {
                        voice != null -> VoiceAudio.MIME
                        imageHash != null -> m.imageMime
                        linkHash != null -> LinkPreviewBlob.MIME
                        video != null -> DEMO_VIDEO_MIME
                        else -> null
                    },
                // What a sender's ingest names and sizes a video, so an older build's file bubble has both.
                attachmentName = video?.let { DEMO_VIDEO_NAME },
                attachmentSize = video?.size,
                // Seeded rather than derived: the derivation runs when a blob *arrives*, and a seeded row
                // never arrives. Writing them here is what the real inbound path would have written.
                voiceDurationMs = voice?.durationMs,
                voicePeaks = voice?.peaks,
                // What a Transcribe tap leaves on the row, only where the build can make one (see DemoMsg.transcript).
                voiceTranscript = m.transcript?.takeIf { voice != null && STT_WIRED },
                // What the sender's frame declared, as the inbound path writes it (DemoMsg.lang).
                bodyLang = DeclaredLangs.of(m.lang, null).lang,
                // The formatting, canonical against the body as deliverChat leaves a received one (DemoMsg.styles).
                styles = StyleStore.encode(StyleSpans.normalize(m.body, demoSpans(m))),
            ).withReply(replyRefFor(m)).withVideo(video?.info).let { row ->
                // A seeded reply quotes a seeded original, so it is written as the inbound path would leave it once
                // judged (ADR 2026-10.gpa3) — else every demo quote would wear the "unverified" cue.
                if (row.replyToId != null && !fromMe) row.copy(replyCheck = MessageEntity.REPLY_VERIFIED) else row
            },
        )
        m.reactions.forEach { r ->
            reactions.apply(ReactionEntity(m.id, nodeId(r.reactor), r.emoji, now - r.minsAgo * 60_000L))
        }
        // A receipt lands after the message it acks; a minute is enough to keep the ordering readable.
        m.deliveredTo.forEach { slot ->
            receipts.record(m.id, nodeId(slot), m.via, now - (m.minsAgo - 1) * 60_000L)
        }
    }

    // A message's DemoStyles as UTF-16 ranges over its body: each run where its words first appear, none where they don't.
    private fun demoSpans(m: DemoMsg): List<StyleSpan> =
        m.styles.mapNotNull { run ->
            val at = m.body.indexOf(run.words)
            if (at < 0 || run.words.isEmpty()) null else StyleSpan(at, at + run.words.length, run.style)
        }

    /** Applies a single reaction now — used by the director to pop a reaction onto an already-written message. */
    suspend fun react(
        messageId: String,
        reactor: Slot,
        emoji: String,
        ts: Long,
    ) = reactions.apply(ReactionEntity(messageId, nodeId(reactor), emoji, ts))

    /** Resolves a [Slot] to its node id ([Slot.ME] is this device's runtime id). */
    fun nodeId(slot: Slot): String =
        when (slot) {
            Slot.ME -> me
            Slot.SAM -> DemoSeeder.SAM
            Slot.DANI -> DemoSeeder.DANI
            Slot.THEO -> DemoSeeder.THEO
            Slot.PRIYA -> DemoSeeder.PRIYA
            Slot.JONAS -> DemoSeeder.JONAS
            Slot.LENA -> DemoSeeder.LENA
            Slot.JONAS_TWO -> DemoSeeder.JONAS_TWO
            Slot.RIVER -> DemoSeeder.RIVER
            Slot.NOAH -> DemoSeeder.NOAH
            Slot.MARLO -> DemoSeeder.MARLO
        }

    /** The display name of a [Slot]: the local profile name for [Slot.ME], else the peer's scenario name. */
    private fun displayName(slot: Slot): String =
        if (slot == Slot.ME) scenario.meName else scenario.peers.firstOrNull { it.slot == slot }?.name ?: nodeId(slot)

    /**
     * The denormalized [ReplyRef] for [m] when it quotes another scenario message (by [DemoMsg.replyTo] → the
     * quoted [DemoMsg.id]), else null. The snippet/author are copied onto the row exactly like a real inbound
     * reply, so the quote renders even though the demo never ran the mesh.
     */
    private fun replyRefFor(m: DemoMsg): ReplyRef? =
        m.replyTo?.let { refId ->
            msgById[refId]?.let { ref ->
                ReplyRef(
                    messageId = ref.id,
                    authorId = nodeId(ref.from),
                    author = displayName(ref.from),
                    snippet = ref.body.take(120),
                    hasAttachment = ref.image != null,
                )
            }
        }

    /**
     * Loads the bundled demo avatar for [key] (a node id, or "me") from the active theme's asset folder,
     * stores it as a content blob, and returns its hash — or null if the asset is missing, so the avatar
     * falls back to a letter circle. Content-addressed like the real avatar pipeline.
     */
    private suspend fun avatar(key: String): String? =
        runCatching {
            val bytes = context.assets.open("demo/avatars/${scenario.theme}/${avatarAlias[key] ?: key}.jpg").use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
            blobs.insert(hash, "image/jpeg", bytes)
            hash
        }.getOrNull()

    /**
     * Loads bundled demo image [name] (base name of `demo/images/<theme>/<name>.<ext>`, ext derived from
     * [mime] — `.webp` for an animated WebP, else `.jpg`), stores it as a plaintext content blob, and returns
     * its hash to pin on a message's attachment — or null if the asset is missing (the message then renders
     * text-only, exactly as before).
     */
    private suspend fun imageBlob(
        name: String,
        mime: String,
    ): String? =
        runCatching {
            val ext = if (mime == "image/webp") "webp" else "jpg"
            val bytes = context.assets.open("demo/images/${scenario.theme}/$name.$ext").use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
            blobs.insert(hash, mime, bytes)
            hash
        }.getOrNull()

    /**
     * Encodes [link] as a [LinkPreviewBlob] container, stores it as a plaintext content blob, and returns its
     * hash to pin on a message's attachment — exactly what the sender's own fetch stores after it has drawn
     * the page (ADR 2026-09.n752). The picture is the theme's bundled card asset; it is dropped when the file
     * is missing or over [LinkPreviewBlob.IMAGE_MAX_BYTES], and the card then renders text-only rather than
     * silently failing to decode.
     *
     * The bytes go through the real encoder, so what a capture shows is a container the receiver's own
     * `LinkCardStore` decoded and normalized — never a shape only the demo can produce.
     */
    private suspend fun linkBlob(link: DemoLink): String? =
        runCatching {
            val picture = link.image?.let { assetBytes(it, "jpg") }?.takeIf { it.size <= LinkPreviewBlob.IMAGE_MAX_BYTES }
            val bytes =
                LinkPreviewBlob(
                    v = LinkPreviewBlob.VERSION,
                    url = link.url,
                    title = link.title,
                    description = link.description,
                    image = picture,
                    imageMime = picture?.let { "image/jpeg" },
                ).encode()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
            blobs.insert(hash, LinkPreviewBlob.MIME, bytes)
            hash
        }.getOrNull()

    /** The raw bytes of `demo/images/<theme>/<name>.<ext>`, or null when the asset is not bundled. */
    private fun assetBytes(
        name: String,
        ext: String,
    ): ByteArray? =
        runCatching {
            context.assets.open("demo/images/${scenario.theme}/$name.$ext").use { it.readBytes() }
        }.getOrNull()

    /** A seeded video's blob hash, its size, and the description its sender's ingest would have carried. */
    private class SeededVideo(
        val hash: String,
        val size: Long,
        val info: VideoInfo,
    )

    /**
     * Loads bundled clip [name] (`demo/videos/<theme>/<name>.mp4`), stores it as a plaintext blob under the video MIME,
     * and returns it with the description a sender carries (ADR 2026-10.hmfe) — derived here by the app's own
     * [VideoFrames], the duration and size from the bytes and the poster chosen across the clip, never typed in. Null
     * when the asset is missing or reads as no video, and the line renders text-only.
     */
    private suspend fun videoBlob(name: String): SeededVideo? =
        runCatching {
            val bytes = context.assets.open("demo/videos/${scenario.theme}/$name.mp4").use { it.readBytes() }
            val meta = VideoFrames.meta(bytes) ?: return@runCatching null
            val poster = VideoFrames.poster(bytes) ?: ByteArray(0)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
            blobs.insert(hash, DEMO_VIDEO_MIME, bytes)
            SeededVideo(hash, bytes.size.toLong(), VideoInfo(meta.durationMs, meta.width, meta.height, poster))
        }.getOrNull()

    /** A seeded voice note's blob hash plus the description a real arrival would have derived from it. */
    private class SeededVoice(
        val hash: String,
        val durationMs: Int,
        val peaks: String,
    )

    /**
     * Synthesizes a [seconds]-long **silent** ADTS stream, stores it as a plaintext blob, and returns it
     * with a plausible speech waveform. Silent because the point is to audit the bubble — its layout, its
     * contrast, its TalkBack description — not to play audio; bundling a real recording would add a binary
     * asset to the repo for a control the suites never press.
     *
     * The stream is genuinely well-formed, so `VoiceAudio.durationMs` reads exactly [seconds] back off it
     * and the seeded row is consistent with what the real pipeline would have produced.
     */
    private suspend fun voiceBlob(seconds: Int): SeededVoice? =
        runCatching {
            val frames = seconds * SEEDED_VOICE_SAMPLE_RATE / SEEDED_VOICE_SAMPLES_PER_FRAME
            val bytes = ByteArray(frames * SEEDED_VOICE_FRAME_BYTES)
            for (f in 0 until frames) {
                val o = f * SEEDED_VOICE_FRAME_BYTES
                bytes[o] = 0xFF.toByte()
                bytes[o + 1] = 0xF1.toByte()
                // profile AAC-LC, sampling_frequency_index 7 (22.05 kHz), 1 channel.
                bytes[o + 2] = 0x5C.toByte()
                bytes[o + 3] = (0x40 or ((SEEDED_VOICE_FRAME_BYTES shr 11) and 0x03)).toByte()
                bytes[o + 4] = ((SEEDED_VOICE_FRAME_BYTES shr 3) and 0xFF).toByte()
                bytes[o + 5] = (((SEEDED_VOICE_FRAME_BYTES and 0x07) shl 5) or 0x1F).toByte()
                bytes[o + 6] = 0xFC.toByte()
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
            blobs.insert(hash, VoiceAudio.MIME, bytes)
            val bars =
                ByteArray(VoiceAudio.PEAK_COUNT) { i ->
                    // A repeating syllable-ish envelope; deterministic so captures are reproducible.
                    val phase = kotlin.math.abs(kotlin.math.sin(i / 4.0))
                    (60 + phase * 180).toInt().toByte()
                }
            SeededVoice(
                hash = hash,
                durationMs = VoiceAudio.durationMs(bytes) ?: (seconds * 1000),
                peaks = VoiceAudio.encodePeaks(bars),
            )
        }.getOrNull()

    private companion object {
        /**
         * The channel a seeded heard post says it arrived on. Matches the demo board's own primary — the stock
         * `LongFast`, which is what `DemoLoraPlane` reports and therefore what the room's title reads — so the
         * list row, the thread header and every post's provenance line all name the same channel.
         */
        const val MESH_ROOM_CHANNEL = "LongFast"

        // The Your mesh screen's seeded history: a phone that has been on for a few weeks in a busy place.
        const val YOUR_MESH_PASSED_ALONG = 212L
        const val YOUR_MESH_HANDED_DIRECT = 37L
        const val YOUR_MESH_SINCE_MS = 40L * 24 * 60 * 60_000L
        const val YOUR_MESH_STRANGERS = 40
        const val YOUR_MESH_CARRYING = 6

        // What the app's own ingest stores and names a short video as (AttachmentStore's, which keeps them private).
        const val DEMO_VIDEO_MIME = "video/mp4"
        const val DEMO_VIDEO_NAME = "video.mp4"

        // The call kinds a seeded peer advertises: those this build takes, and none where it takes no calls.
        val DEMO_CALL_CAPS =
            (if (LIVE_CALLS_WIRED) Protocol.CAP_CALL else 0L) or (if (WALKIE_WIRED) Protocol.CAP_WALKIE else 0L)

        // A seeded voice note's synthetic ADTS stream: mono 22.05 kHz AAC-LC, the recorder's own format, so
        // VoiceAudio reads the same duration off it that it would off a real recording.
        const val SEEDED_VOICE_SAMPLE_RATE = 22_050
        const val SEEDED_VOICE_SAMPLES_PER_FRAME = 1024
        const val SEEDED_VOICE_FRAME_BYTES = 64
    }
}
