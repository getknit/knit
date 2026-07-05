package app.getknit.knit.mesh

import android.util.Log
import app.getknit.knit.TextLimits
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.data.message.replyRef
import app.getknit.knit.data.message.withReply
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.GroupLeaveContent
import app.getknit.knit.mesh.protocol.KeyReqContent
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.TypingContent
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.protocol.isStorable
import app.getknit.knit.mesh.protocol.mention
import app.getknit.knit.moderation.ScopedTextModerator
import app.getknit.knit.normalizeSingleLine
import app.getknit.knit.notifications.NotifConversation
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.notifications.incomingNotification
import app.getknit.knit.notifications.mentionNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates the mesh: owns the [MeshTransport] and [MeshRouter], handles delivery of new frames
 * (persist chat, ack delivery, cache profiles/avatars, mark receipts), broadcasts this device's
 * profile, and exposes the send/start API used by the foreground service and UI. A process singleton
 * (provided by Koin) so the bound service and the UI share one instance.
 *
 * The central mesh orchestrator: many small frame handlers, and many collaborators injected by design.
 */
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class MeshManager(
    private val transport: MeshTransport,
    private val messages: MessageRepository,
    private val groups: GroupRepository,
    private val reactions: ReactionRepository,
    private val peers: PeerRepository,
    private val identity: Identity,
    private val settings: SettingsStore,
    private val blobs: BlobRepository,
    private val blobStore: MeshBlobStore,
    private val forwardStore: ForwardStore,
    private val notifier: Notifier,
    private val textModeration: ScopedTextModerator,
    private val messageCrypto: MessageCrypto,
    private val scope: CoroutineScope,
    private val metrics: MeshMetrics,
) {
    // Reconstructed per session so its inbound collector + relay jobs live on the session scope and
    // are cancelled by stop() (rather than leaking on the never-cancelled app scope).
    private var router = MeshRouter(transport, scope, metrics = metrics, onDeliver = ::onDeliver)

    // Per-session scope for the collectors + metrics loop + router; cancelled in stop() so they don't
    // accumulate across start/stop cycles (e.g. a Diagnostics-triggered restart()).
    private var sessionScope: CoroutineScope? = null

    // Content-addressed image fetch over the mesh, backed by the encrypted blob store.
    private val blobExchange =
        BlobExchange(
            transport = transport,
            store = blobStore,
            selfId = { identity.nodeId() },
            // The chat list observes the blobs table for presence, so no per-message path write is needed
            // when an attachment arrives. A pulled blob may also be a (multi-hop) peer's avatar or a group
            // photo, so attribute it back to whoever advertised it, and — for an E2E attachment — screen its
            // decrypted bytes now that both the ciphertext and (from the delivered message) its key are on hand.
            onObtained = { hash, _ ->
                adoptAdvertisedAvatar(hash)
                adoptAdvertisedGroupPhoto(hash)
                screenObtainedAttachment(hash)
            },
        )

    // Store-and-forward DM custody: carries DMs we originate/relay and re-offers them to neighbors that
    // join later, so a message reaches a recipient that wasn't connected when it was first flooded.
    private val forwardSync =
        ForwardSync(
            transport = transport,
            store = forwardStore,
            authenticate = ::canCarry,
            // Fired once when a chat frame is actually carried: eager-pull its image blob so a custodied image
            // survives to a late joiner (the carrier holds ciphertext it can't read, like the frame itself).
            onCarried = ::onCarriedFrame,
            // (The carry store grew → the store impl folds the id into StoreDigest, whose version change re-cues.)
        )

    // Demand-driven recovery of a peer's key/profile: a frame dropped for a missing sender key (the
    // NO_SENDER_KEY case in verifyInbound) triggers a signed, point-to-point request that walks hop-by-hop
    // to a holder, which re-serves the peer's cached signed profile so future frames from it verify.
    private val keyExchange =
        KeyExchange(
            transport = transport,
            selfId = { identity.nodeId() },
            signRaw = messageCrypto::signRaw,
            isBlocked = { it in settings.blockedNodeIds.first() },
            metrics = metrics,
        )

    // Delay-tolerant "delivered" tick for broadcast/group messages: their receipt is a unicast, non-custodied
    // best-effort frame, so it's lost if the author isn't reachable at delivery time (the message converges via
    // custody, but the tick doesn't). AckSync remembers the ticks we owe and re-sends them — still unicast, never
    // flooded — until the author is reachable or the entry ages out. DM receipts stay on the flood+custody path.
    private val ackSync =
        AckSync(
            transport = transport,
            selfId = { identity.nodeId() },
            signRaw = messageCrypto::signRaw,
            metrics = metrics,
        )

    // Bounded in-memory buffer of frames dropped for a missing sender key: parked alongside the key
    // request in verifyInbound and replayed through the deliver path once handleProfile pins the key, so
    // a frame that raced ahead of its sender's profile still lands. The inbound complement of flushPendingFor.
    private val pendingInbound = PendingInbound(metrics = metrics)

    // Receiver-side state for the best-effort "now typing" indicator: which senders are typing in which
    // conversation. Ephemeral and never custodied — a typing cue is fire-and-forget, so nothing is persisted
    // (a live typer re-cues within the TTL). Populated by handleTyping, cleared by deliverChat on a real message.
    private val typingTracker = TypingTracker(scope)

    @Volatile
    private var started = false

    // nodeId -> avatar hash we last sent that neighbor, so we don't re-push an unchanged avatar on
    // every profile edit or reconnect. Cleared per-peer when they disconnect (see watchNeighbors).
    private val sentAvatarHashes = ConcurrentHashMap<String, String>()

    // nodeId -> avatar hash a non-direct peer advertised but whose bytes we're still pulling, so a blob
    // arriving via the multi-hop BlobExchange can be attributed back to the peer that advertised it.
    private val advertisedAvatars = ConcurrentHashMap<String, String>()

    // groupId -> the group photo (hash + its last-writer-wins clock) a group frame advertised but whose
    // bytes we're still pulling, so a blob arriving via the multi-hop BlobExchange can be adopted onto the
    // right group (and only if still current — the clock guards against a superseded photo, see
    // [adoptAdvertisedGroupPhoto]). The group analogue of [advertisedAvatars].
    private val advertisedGroupPhotos = ConcurrentHashMap<String, AdvertisedPhoto>()

    /**
     * Number of nearby peers for the UI status header — the smoothed [MeshTransport.reachable] set (seen
     * over the coordination plane), not the ≤1 live data-path link, so the header doesn't blink as the
     * cue-driven transport rotates through ephemeral syncs.
     */
    val neighborCount: StateFlow<Int> =
        transport.reachable
            .map { it.size }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    /** Nearby peers for the contact picker (message someone nearby) — the smoothed [MeshTransport.reachable] set. */
    val neighbors: StateFlow<Set<Peer>> get() = transport.reachable

    /** Radio health for the Diagnostics screen (Healthy vs Degraded — e.g. radios seized by Quick Share). */
    val transportHealth: StateFlow<TransportHealth> get() = transport.health

    /**
     * Per-radio status for the Diagnostics screen (Bluetooth vs Wi-Fi Aware: health + live-link/nearby counts),
     * so the merged [transportHealth]/[neighbors] can be broken back out by plane. In production the transport is
     * always a [CompositeMeshTransport]; the fallback describes a single non-composite transport (demo/fakes) as
     * one entry.
     */
    val transportStatuses: StateFlow<List<TransportStatus>> =
        (transport as? CompositeMeshTransport)?.statuses
            ?: combine(transport.neighbors, transport.reachable, transport.health) { linked, nearby, health ->
                listOf(TransportStatus(transport.kind, health, linked.size, nearby.size))
            }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** nodeId → the radios each node is reachable over, so Diagnostics can tag a connected node BLE / NAN. */
    val peerTransports: StateFlow<Map<String, Set<TransportKind>>> =
        (transport as? CompositeMeshTransport)?.peerTransports
            ?: transport.reachable
                .map { set -> set.associate { it.nodeId to setOf(transport.kind) } }
                .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** conversationId → the set of peers currently shown as "typing" there, for the chat UI. Ephemeral (TTL'd). */
    val typing: StateFlow<Map<String, Set<String>>> get() = typingTracker.typing

    fun start() {
        if (started) return
        started = true
        blobStore.clearTransfers() // drop any plaintext transfer temp files left by a previous session
        // Child of the app Job so app-scope cancellation still propagates; SupervisorJob isolates a
        // single collector's failure from the rest of the session. The shared handler logs any uncaught
        // throw in a top-level session collector instead of letting it vanish silently.
        val session =
            CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default + meshExceptionHandler)
        sessionScope = session
        router = MeshRouter(transport, session, metrics = metrics, onDeliver = ::onDeliver)
        router.start()
        transport.start()
        watchNeighbors(session)
        watchReachable(session)
        seedOwnProfileCustody(session)
        watchProfileChanges(session)
        watchIncomingFiles(session)
        watchIncomingDigests(session)
        resumePendingFetches(session)
        pruneForwardStorePeriodically(session)
        reofferToNeighborsPeriodically(session)
        logMetricsPeriodically(session)
    }

    fun stop() {
        if (!started) return
        started = false
        transport.stop()
        // Clear this session's pending relays on the app scope (the session is about to die); capture
        // the instance so a fast restart reassigning `router` can't retarget the wrong one. Then tear
        // the session down — stopping the inbound collector, the metrics loop, and the watch* collectors.
        val session = sessionScope
        val sessionRouter = router
        scope.launch { sessionRouter.stop() }
        session?.cancel()
        sessionScope = null
    }

    /** Triggers an immediate rescan/reconnect (heartbeat alarm, device motion) and sweeps stale carry. */
    fun heal() {
        if (!started) return
        transport.heal()
        // Piggyback the forward-store TTL sweep on the 15-min heartbeat so it runs while backgrounded.
        // Also re-ask neighbors for any key we're still missing, in case the holder is reachable now but
        // never arrived as a fresh neighbor (so onNeighborAdded didn't fire) — belt-and-suspenders for the
        // ongoing-drops retry already driven by want()'s cooldown.
        sessionScope?.launch {
            forwardSync.sweepExpired()
            pendingInbound.sweepExpired()
            keyExchange.sweepExpired() // age out stale (unauthenticated) key-wants; blob fetches that never arrived
            blobExchange.sweepExpired()
            keyExchange.retryMissing()
            ackSync.retryPending() // re-send broadcast/group ticks we still owe absent authors (+ age out old ones)
        }
    }

    /** Tears down and re-establishes the transport (e.g. after Bluetooth toggles back on). */
    fun restart() {
        if (!started) return
        transport.stop()
        transport.start()
    }

    /**
     * Composes a chat message (optionally with an already-ingested image [attachment]), stores it
     * locally (unacked), and floods it to the mesh. The sender already holds the blob, so direct
     * neighbors will pull it by hash and it propagates outward from there.
     *
     * [recipientId] null sends to the broadcast room; a node id sends a 1:1 DM addressed to that
     * peer. A non-null [group] sends a group message (recipientId stays null) — the [GroupInfo] rides
     * on the frame so members can (re)build the group from it. All three still flood (no routing table
     * yet); only the addressed recipient(s) deliver/ack, while relays forward the frame untouched.
     *
     * Returns false without sending or storing anything if on-device content filtering flags [text] as
     * abusive (block-on-send); true once the message is stored locally and flooded. The [attachment] is
     * screened separately at ingest time, so by here it is already clean.
     *
     * `@Suppress("LongMethod")`: ktlint's one-arg-per-line wrapping of the two parallel MessageEntity /
     * content builds inflates the raw line count past detekt's LongMethod=60; the logic is two
     * straight-line branches, not complex.
     */
    @Suppress("LongMethod")
    suspend fun sendChat(
        text: String,
        attachment: AttachmentStore.Ingested? = null,
        mentions: List<Mention> = emptyList(),
        recipientId: String? = null,
        group: GroupInfo? = null,
        replyTo: ReplyRef? = null,
    ): Boolean {
        if (isTextFlagged(text, "outgoing", isRoom = recipientId == null && group == null)) return false
        val me = identity.nodeId()
        val id = FrameId.new()
        val sentAt = System.currentTimeMillis()
        val conversationId = Conversations.idFor(me, recipientId, me, group?.id)

        // Broadcast room: plaintext (no fixed recipient set to encrypt to) — the legacy path, unchanged.
        if (recipientId == null && group == null) {
            messages.save(
                MessageEntity(
                    id = id,
                    senderId = me,
                    recipientId = null,
                    conversationId = conversationId,
                    body = text,
                    sentAt = sentAt,
                    received = false,
                    mentions = MentionStore.encode(mentions),
                    attachmentHash = attachment?.hash,
                    attachmentMime = attachment?.mime,
                ).withReply(replyTo),
            )
            val content =
                ChatContent(
                    body = text,
                    mentions = mentions,
                    attachmentHash = attachment?.hash,
                    attachmentMime = attachment?.mime,
                    replyTo = replyTo,
                )
            originateSigned(chatEnvelope(id, me, sentAt, recipientId = null, group = null, content))
            return true
        }

        // DM or group: end-to-end encrypt. The attachment (if any) is encrypted to its own key and
        // re-addressed by its ciphertext hash; body/mentions/attachment refs go into the sealed content.
        val sealedAttachment = attachment?.let { sealAttachment(it) }
        val content =
            MessageContent(
                body = text,
                mentions = mentions,
                attachmentHash = sealedAttachment?.hash,
                attachmentMime = attachment?.mime,
                attachmentKey = sealedAttachment?.key,
                replyTo = replyTo,
            )
        val thread = group?.id ?: recipientId.orEmpty()
        val header = MessageCrypto.header(id, me, sentAt, thread)
        val envelope = messageCrypto.seal(content.encode(), header, recipientBundles(recipientId, group, me))
        // Persist our own plaintext copy regardless, so the sender always sees their message. A DM whose
        // recipient key isn't known yet is flagged pendingKey so handleProfile can retransmit it when the
        // recipient's profile (carrying the key) finally arrives (groups stay unsent, as before).
        messages.save(
            MessageEntity(
                id = id,
                senderId = me,
                recipientId = recipientId,
                conversationId = conversationId,
                body = text,
                sentAt = sentAt,
                received = false,
                mentions = MentionStore.encode(mentions),
                attachmentHash = sealedAttachment?.hash,
                attachmentMime = attachment?.mime,
                attachmentKey = sealedAttachment?.key,
                pendingKey = envelope == null && group == null,
            ).withReply(replyTo),
        )
        if (envelope == null) {
            // No recipient's key is known yet — nothing can decrypt this. Saved locally above; a DM is
            // marked pendingKey and retransmitted on key arrival, a group message stays unsent.
            Log.w(TAG, "no known keys for recipient(s) of chat $id; not flooded yet")
            return true
        }
        // Expose the (ciphertext) attachment hash + mime in the cleartext frame alongside the sealed content, so
        // a relaying carrier — blind to the encrypted refs — can custody the blob. The decryption key stays
        // sealed in MessageContent; a fresh per-send key means the ciphertext hash never correlates identical
        // images across sends, so this leaks only "this message carries an image (~size)".
        originateSigned(
            chatEnvelope(
                id,
                me,
                sentAt,
                recipientId,
                group,
                ChatContent(
                    enc = envelope,
                    attachmentHash = sealedAttachment?.hash,
                    attachmentMime = attachment?.mime,
                ),
            ),
        )
        return true
    }

    /** Builds a [FrameType.CHAT] routing envelope wrapping the given [content] payload. */
    private fun chatEnvelope(
        id: String,
        senderId: String,
        sentAt: Long,
        recipientId: String?,
        group: GroupInfo?,
        content: ChatContent,
    ): RelayEnvelope =
        RelayEnvelope(
            type = FrameType.CHAT,
            id = id,
            senderId = senderId,
            sentAt = sentAt,
            recipientId = recipientId,
            group = group,
            payload = WireCodec.encodePayload(content),
        )

    /** Resolves the published key bundles for a DM recipient or a group's members (excluding us). */
    private suspend fun recipientBundles(
        recipientId: String?,
        group: GroupInfo?,
        me: String,
    ): Map<String, PublicKeyBundle> {
        val targets =
            when {
                group != null -> group.members.filter { it != me }
                recipientId != null -> listOf(recipientId)
                else -> emptyList()
            }
        return targets
            .mapNotNull { nodeId ->
                peers
                    .find(nodeId)
                    ?.pubKey
                    ?.let { PublicKeyBundle.decode(it) }
                    ?.let { nodeId to it }
            }.toMap()
    }

    /** Encrypted, content-addressed copy of a just-ingested attachment, plus its base64 key. */
    private data class SealedAttachment(
        val hash: String,
        val key: String,
    )

    /**
     * Encrypts the ingested (plaintext) attachment to a fresh key, stores the ciphertext blob under its
     * ciphertext hash (so the existing content-addressed pull/dedup still works), and drops the now-
     * unreferenced plaintext blob.
     */
    private suspend fun sealAttachment(attachment: AttachmentStore.Ingested): SealedAttachment? {
        val plain = blobs.bytes(attachment.hash) ?: return null
        val sealed = AttachmentCrypto.seal(plain)
        val ctHash = sha256Hex(sealed.blob)
        blobs.insert(ctHash, attachment.mime, sealed.blob)
        blobs.deleteIfUnreferenced(attachment.hash)
        return SealedAttachment(ctHash, b64(sealed.key))
    }

    /**
     * Floods a group metadata update (e.g. a rename) immediately, independent of any chat message, so
     * members converge without waiting for the next message. The receiver applies it last-writer-wins on
     * the group-update frame's `sentAt`; the local store has already been updated by the caller.
     */
    suspend fun sendGroupUpdate(group: GroupInfo) {
        originateSigned(
            RelayEnvelope(
                type = FrameType.GROUP_UPDATE,
                id = FrameId.new(),
                senderId = identity.nodeId(),
                sentAt = System.currentTimeMillis(),
                group = group,
                payload = EMPTY_PAYLOAD, // the roster rides in `group`; no per-type content
            ),
        )
    }

    /**
     * Floods a signed `groupleave` frame announcing that we've left [groupId], so the remaining members
     * drop us from their roster and show a status notice. Sent on departure (before the local tombstone);
     * the leaver is the signer, so a forged leave can't evict anyone else. Flooded once (not custodied),
     * like [sendGroupUpdate].
     */
    suspend fun sendGroupLeave(groupId: String) {
        val me = identity.nodeId()
        originateSigned(
            RelayEnvelope(
                type = FrameType.GROUP_LEAVE,
                id = FrameId.new(),
                senderId = me,
                sentAt = System.currentTimeMillis(),
                payload = WireCodec.encodePayload(GroupLeaveContent(groupId)),
            ),
        )
    }

    /**
     * Toggles this device's emoji reaction on [messageId] and floods the change. Tapping the emoji you
     * already chose retracts it; tapping a different one replaces it (one reaction per person). The
     * change is stored optimistically and propagates as a `reaction` frame; `sentAt` is the wall clock
     * used for last-writer-wins so concurrent reactors across the mesh converge.
     */
    suspend fun sendReaction(
        messageId: String,
        emoji: String,
    ) {
        val me = identity.nodeId()
        val next = if (reactions.currentEmoji(messageId, me) == emoji) null else emoji
        val now = System.currentTimeMillis()
        reactions.apply(ReactionEntity(messageId, me, next, now))
        originateSigned(
            RelayEnvelope(
                type = FrameType.REACTION,
                id = FrameId.new(),
                senderId = me,
                sentAt = now,
                payload = WireCodec.encodePayload(ReactionContent(messageId, next)),
            ),
        )
    }

    /**
     * Broadcasts a best-effort "now typing" cue for [conversationId] to nearby peers — the presence ping behind
     * the chat typing indicator. Deliberately NOT [originateSigned]: it must never flood, be custodied, or be
     * parked, so it is signed with `relay = false` (single-hop, like a blob request) and sent only over the
     * coordination-plane fast path — targeted [MeshTransport.fastSend] to a DM recipient, [MeshTransport.fastFanout]
     * to every neighbor for the broadcast room / a group. A fresh [FrameId] each time so the receiver's SeenSet
     * never dedups the next cue. Fire-and-forget: if it doesn't fit the ~255 B channel or no one is reachable it
     * simply no-ops. Scope rides the frame the same way a chat does — [RelayEnvelope.recipientId] for a DM, a
     * compact [TypingContent.groupId] for a group (not the heavy [RelayEnvelope.group] roster, which could blow
     * the coordination-plane size cap).
     */
    suspend fun sendTyping(conversationId: String) {
        val me = identity.nodeId()
        val kind = Conversations.kindFor(conversationId)
        val recipientId = if (kind == ConversationKind.DM) conversationId else null
        val groupId = if (kind == ConversationKind.GROUP) conversationId else null
        val env =
            RelayEnvelope(
                type = FrameType.TYPING,
                id = FrameId.new(),
                senderId = me,
                sentAt = System.currentTimeMillis(),
                recipientId = recipientId,
                payload = WireCodec.encodePayload(TypingContent(groupId)),
            )
        val wire = sign(env, relay = false)
        if (recipientId != null) transport.fastSend(wire, Peer(recipientId)) else transport.fastFanout(wire)
    }

    /** On startup, sweep orphaned blobs/reactions and re-request attachment blobs we're still missing. */
    private fun resumePendingFetches(session: CoroutineScope) {
        session.launch {
            blobs.deleteOrphans() // reclaim blobs left by attachments staged but never sent (keeps carried ones)
            reactions.deleteOrphans(System.currentTimeMillis()) // reclaim reactions left by deleted messages
            forwardSync.sweepExpired() // drop carried DMs whose TTL elapsed while we were down
            pendingInbound.sweepExpired() // and any key-wait frames whose TTL lapsed (in-memory, so usually a no-op)
            keyExchange.sweepExpired() // stale unauthenticated key-wants
            blobExchange.sweepExpired() // never-arriving blob fetches
            // Own/received message attachments: always re-pull (uncapped, kept alive by their message row).
            val ownHashes = messages.hashesNeedingFetch()
            ownHashes.forEach { blobExchange.want(it) }
            // Carrier-only custody blobs backfill only while under the byte budget (the same pull-time soft cap
            // onCarriedFrame applies), so a restart re-attempts pulls that a live-session over-budget skip left
            // missing; skip ones already re-requested above as our own/received attachments.
            if (blobs.carrierOnlyBlobBytes() < CARRIER_BLOB_BUDGET_BYTES) {
                val own = ownHashes.toHashSet()
                forwardStore.attachmentHashesNeedingFetch().forEach { if (it !in own) blobExchange.want(it) }
            }
        }
    }

    /** Periodically reclaims expired carried DMs, bounding the forward store between heartbeat sweeps. */
    private fun pruneForwardStorePeriodically(session: CoroutineScope) {
        session.launch {
            while (true) {
                delay(FORWARD_SWEEP_INTERVAL_MS)
                forwardSync.sweepExpired()
                pendingInbound.sweepExpired()
                keyExchange.sweepExpired()
                blobExchange.sweepExpired()
            }
        }
    }

    /**
     * Periodically re-runs the neighbor-join re-offer hooks for **currently-linked** neighbors — the timer-driven
     * anti-entropy a persistent link needs. [watchNeighbors] fires these once per newcomer, which suffices for the
     * cue-driven Wi-Fi Aware transport (its ephemeral links re-join on every sync, so the re-offer re-runs for
     * free), but a Bluetooth link stays up continuously — and the composite masks a NAN→BT handoff as one
     * continuous neighbor — so without this a custody divergence that appears (or an offer lost to a race) after
     * link-up would never reconcile: the peer keeps advertising a differing store digest and no sync ever closes
     * it, which also leaves the Wi-Fi Aware plane forever *wanting* a sync it can't complete. Cheap and idempotent:
     * [ForwardSync.onDigest] returns only the set difference, a duplicate is dropped by the receiver's SeenSet, and
     * a peer no longer holding a live link is a no-op (the send routes to no transport).
     */
    private fun reofferToNeighborsPeriodically(session: CoroutineScope) {
        session.launch {
            while (true) {
                delay(NEIGHBOR_REOFFER_INTERVAL_MS)
                transport.neighbors.value.forEach { peer ->
                    forwardSync.onNeighborAdded(peer) // re-advertise our custody digest → pull anything we lack
                    blobExchange.onNeighborAdded(peer) // re-ask for blobs we still need
                    keyExchange.onNeighborAdded(peer) // re-ask for keys we still need
                    ackSync.onNeighborAdded(peer) // re-send any broadcast/group delivery tick we owe it
                }
            }
        }
    }

    // --- Profile broadcasting ---

    private fun watchNeighbors(session: CoroutineScope) {
        session.launch {
            var known = emptySet<String>()
            transport.neighbors.collect { current ->
                val currentIds = current.map { it.nodeId }.toSet()
                val newcomers = current.filter { it.nodeId !in known }
                // No departure cleanup: under the cue-driven transport a data-path link is ephemeral and
                // reconnects on every sync, so we deliberately keep sentAvatarHashes across the flap —
                // clearing it would re-push every avatar on each brief contact. A peer that truly leaves
                // ages out by TTL. (ForwardSync, by contrast, *does* re-offer its whole carried set on each
                // join now: the digest gate makes a fresh link mean the stores differ, so re-pushing is how
                // an offer lost to a torn-down link self-heals — see ForwardSync.onNeighborAdded. A persistent
                // link (Bluetooth) only joins once, so reofferToNeighborsPeriodically re-runs these hooks on a
                // timer for currently-linked neighbors — the anti-entropy a non-flapping link needs.)
                known = currentIds
                newcomers.forEach {
                    pushProfileTo(it)
                    blobExchange.onNeighborAdded(it) // re-ask the new neighbor for blobs we still need
                    forwardSync.onNeighborAdded(it) // re-offer carried DMs addressed to / routable via it
                    keyExchange.onNeighborAdded(it) // re-ask the new neighbor for keys we're still missing
                    ackSync.onNeighborAdded(it) // re-send any broadcast/group delivery tick we owe it, over the link
                }
            }
        }
    }

    /**
     * Seed our own profile frame into custody at startup (idempotent: the persisted profileVersion keeps the
     * frame id stable, so a later launch re-seeds the same frame and the store no-ops). Closes the NAN-only
     * cold-start deadlock (`docs/NAN_CONCURRENCY_REAUDIT.md` §3.3): with every custody store empty all
     * digests read 0 ⇒ equal ⇒ no sync is ever wanted, DMs park `pendingKey`, and profiles historically
     * moved only on link-up — which never came. A seeded store gives each node a one-frame set whose id
     * differs per node, so first contact diverges the digests, a link forms, profiles/keys exchange, and
     * parked DMs flush.
     */
    private fun seedOwnProfileCustody(session: CoroutineScope) {
        session.launch {
            val env = currentProfileEnvelope()
            forwardSync.onSeen(sign(env), env, ForwardStore.ORIGIN_SELF)
        }
    }

    /**
     * Flood our profile once per peer-epoch on **first coordination-plane contact** ([MeshTransport.reachable]
     * newcomers) — not only on link-up ([watchNeighbors]) — so the key exchange bootstraps over NAN alone
     * (`docs/NAN_CONCURRENCY_REAUDIT.md` §5.5): `reachable` needs no data path, a small profile rides the
     * fast plane immediately, and a larger one is already in custody (seeded above) where its digest
     * divergence pulls a link up. Coalesced to one origination per [PROFILE_REFLOOD_MIN_MS] no matter how
     * many newcomers arrive (receivers dedupe by the stable frame id + SeenSet, and the custody path covers
     * anyone the flood missed). A peer's departure from `reachable` and later return is the epoch boundary —
     * it re-enters as a newcomer and gets one fresh flood. BLE-driven newcomers trigger it too, harmlessly.
     */
    private fun watchReachable(session: CoroutineScope) {
        session.launch {
            var known = emptySet<String>()
            var lastFloodAt = 0L
            transport.reachable.collect { current ->
                val ids = current.mapTo(HashSet()) { it.nodeId }
                val newcomers = ids - known
                known = ids
                if (newcomers.isEmpty()) return@collect
                val now = System.currentTimeMillis()
                if (now - lastFloodAt < PROFILE_REFLOOD_MIN_MS) return@collect
                lastFloodAt = now
                originateSigned(currentProfileEnvelope())
            }
        }
    }

    private fun watchProfileChanges(session: CoroutineScope) {
        session.launch {
            combine(settings.displayName, settings.status, settings.avatarUpdatedAt) { name, status, avatarAt ->
                Triple(name, status, avatarAt)
            }.drop(1) // skip the initial stored value; only react to real edits
                // A Save writes name+status in one transaction; without this the duplicate flow
                // re-emits would broadcast more than once. Also drops no-op saves.
                .distinctUntilChanged()
                .collect {
                    // Monotonic bump, persisted: a version that's stable across restarts is what keeps a
                    // custodied profile from minting a new frame every launch (see SettingsStore.profileVersion).
                    settings.setProfileVersion(maxOf(System.currentTimeMillis(), settings.profileVersion.first() + 1))
                    broadcastProfile()
                }
        }
    }

    private fun watchIncomingFiles(session: CoroutineScope) {
        session.launch {
            transport.incomingFiles.collect { file ->
                when (file.kind) {
                    FileKind.AVATAR -> {
                        onAvatarReceived(file.fromNodeId, file.key, file.mime, file.path)
                    }

                    FileKind.ATTACHMENT -> {
                        blobExchange.onReceived(file.key, file.mime, file.path, file.fromNodeId)
                    }
                }
            }
        }
    }

    private fun watchIncomingDigests(session: CoroutineScope) {
        session.launch {
            // A neighbor advertised the custody ids it holds → push it just the frames it lacks (the id-diff).
            transport.incomingDigests.collect { forwardSync.onDigest(it.fromNodeId, it.ids) }
        }
    }

    private suspend fun pushProfileTo(peer: Peer) {
        val env = currentProfileEnvelope()
        val wire = sign(env)
        // Custody our own profile (ORIGIN_SELF), exactly as a peer that receives it carries it (ORIGIN_RELAY).
        // Without this our store is permanently missing our own profile while every peer holds it, so the
        // store-and-forward digests never converge and the mesh churns NDPs forever. Idempotent on the (now
        // persisted, restart-stable) version, so repeated connects don't re-store it.
        forwardSync.onSeen(wire, env, ForwardStore.ORIGIN_SELF)
        router.sendOwn(wire, env.id, peer)
        sendAvatarIfNeeded(peer)
    }

    private suspend fun broadcastProfile() {
        originateSigned(currentProfileEnvelope())
        transport.neighbors.value.forEach { sendAvatarIfNeeded(it) }
    }

    /**
     * Sends our avatar file to [peer] only if we haven't already sent them this exact avatar. Profile
     * edits that don't touch the avatar (e.g. a status change) re-broadcast the profile frame but no
     * longer re-ship the (unchanged) avatar JPEG to every neighbor.
     */
    private suspend fun sendAvatarIfNeeded(peer: Peer) {
        val hash = settings.ownAvatarHash.first() ?: return
        if (sentAvatarHashes[peer.nodeId] == hash) return
        val avatar = blobStore.fileFor(hash) ?: return
        transport.sendFile(avatar, peer, avatarMeta(hash))
        sentAvatarHashes[peer.nodeId] = hash
    }

    private fun avatarMeta(hash: String): FileMeta = FileMeta(FileKind.AVATAR, key = hash, mime = "image/jpeg")

    private suspend fun currentProfileEnvelope(): RelayEnvelope {
        val me = identity.nodeId()
        // Persisted, so the profile frame's id + sentAt are stable across restarts — an unchanged profile
        // re-broadcasts as the *same* custodied frame instead of a new one, letting the digests converge.
        val version = settings.profileVersion.first()
        val content =
            ProfileContent(
                // Normalize/cap defensively: covers legacy values stored before the field gained a cap and
                // the rare process-death-before-the-blur-commit case, so peers never receive an oversized name.
                name = normalizeSingleLine(settings.displayName.first()).take(TextLimits.DISPLAY_NAME),
                status = normalizeSingleLine(settings.status.first()).take(TextLimits.STATUS),
                avatarHash = settings.ownAvatarHash.first(),
                pubKey = identity.publicKeyBundle(),
                deviceTag = identity.deviceTag(),
                protoVersion = Protocol.VERSION,
                capabilities = Protocol.LOCAL_CAPABILITIES,
            )
        return RelayEnvelope(
            type = FrameType.PROFILE,
            id = "profile-$me-$version",
            senderId = me,
            sentAt = version,
            payload = WireCodec.encodePayload(content),
        )
    }

    // --- Signed origination ---

    /**
     * Signs [env] and floods it to the mesh, capturing a carriable DM/group message in the forward store
     * so we re-offer it to neighbors that join later. The single origination choke; non-storable frames
     * are simply not carried.
     */
    private suspend fun originateSigned(env: RelayEnvelope) {
        val wire = sign(env)
        router.originate(wire, env.id)
        forwardSync.onSeen(wire, env, ForwardStore.ORIGIN_SELF)
        if (shouldFastFanout(env)) transport.fastFanout(wire)
    }

    /**
     * Wraps [env] in a signed [WireEnvelope]: the canonical envelope bytes plus our raw Ed25519 signature
     * over exactly those bytes (so every relay reproduces them verbatim and the signature holds mesh-wide).
     */
    private fun sign(
        env: RelayEnvelope,
        relay: Boolean = true,
    ): WireEnvelope {
        val signed = WireCodec.encodeEnvelope(env)
        return WireEnvelope(relay = relay, sig = messageCrypto.signRaw(signed), signed = signed)
    }

    // --- Delivery of inbound frames ---

    private suspend fun onDeliver(
        wire: WireEnvelope,
        env: RelayEnvelope,
        fromNodeId: String,
    ) {
        // Strict authentication gate: a flooded frame that isn't signed by the key its senderId binds
        // to is dropped (not delivered locally). We still return normally so MeshRouter relays it
        // onward — other peers verify independently, and we don't become a propagation black hole.
        if (!verifyInbound(env, wire, fromNodeId)) return
        // Carry a floodable frame we're relaying so we can re-offer it to a neighbor that joins later —
        // store-and-forward. A DM is carried only when relaying it *toward* someone else (skip ones
        // addressed to us — we're the destination, so deliver, don't carry); a group message is always
        // carried, for other members who may be offline, whether or not we're a member ourselves; a
        // broadcast-room message and the cleartext metadata frames (reaction/receipt/group-meta/profile,
        // all recipient/group null) are always carried, so a passing phone backfills our ambient state.
        // Runs before handleChat returns early and before the router schedules the relay, so the copy is
        // durable pre-flood. Only flood frames (relay = true) are custodied — a point-to-point frame
        // (relay = false, e.g. a broadcast/group delivery receipt) is delivered to its addressee and stops.
        if (env.isStorable() && wire.relay) {
            val isBroadcast = env.recipientId == null && env.group == null
            val carry = isBroadcast || env.group != null || !Conversations.isForMe(env.recipientId, identity.nodeId())
            if (carry) forwardSync.onSeen(wire, env, ForwardStore.ORIGIN_RELAY)
        }
        // Multi-hop coordination-plane fan-out: re-fan a small flood frame to our own neighbors so it spreads
        // across the mesh at message-plane speed (no data path), not just one hop from the originator. onDeliver
        // runs once per first-seen frame (MeshRouter gates on its SeenSet), so each node re-fans it exactly once
        // and the echo dies out — no storm; fastFanout size-gates, so anything too big just no-ops. Only flood
        // frames re-fan; a point-to-point frame (relay = false, e.g. a broadcast receipt) reaches its addressee
        // and goes no further.
        if (wire.relay && shouldFastFanout(env)) transport.fastFanout(wire)
        dispatchByType(env, wire, fromNodeId)
    }

    /**
     * Routes a verified inbound frame to its type handler. A plain `when` over the type string: an unknown
     * future type that decoded (the discriminator is a string, so it doesn't throw) hits `else` — not
     * delivered locally, but the router still relays it verbatim, so an old build is never a black hole for
     * a frame type it doesn't understand.
     */
    private suspend fun dispatchByType(
        env: RelayEnvelope,
        wire: WireEnvelope,
        fromNodeId: String,
    ) {
        when (env.type) {
            FrameType.CHAT -> {
                handleChat(env)
            }

            FrameType.GROUP_UPDATE -> {
                handleGroupUpdate(env)
            }

            FrameType.GROUP_LEAVE -> {
                handleGroupLeave(env)
            }

            FrameType.PROFILE -> {
                handleProfile(env, wire)
            }

            FrameType.RECEIPT -> {
                handleReceipt(env)
            }

            FrameType.REACTION -> {
                handleReaction(env)
            }

            FrameType.BLOB_REQ -> {
                WireCodec.decodePayload<BlobReqContent>(env.payload)?.let { blobExchange.onRequest(it.hash, fromNodeId) }
            }

            FrameType.KEY_REQ -> {
                WireCodec.decodePayload<KeyReqContent>(env.payload)?.let { keyExchange.onRequest(it.nodeIds, fromNodeId) }
            }

            FrameType.TYPING -> {
                handleTyping(env)
            }

            else -> {
                Unit
            }
        }
    }

    /**
     * Records a best-effort "now typing" cue in [typingTracker] so the chat UI can show it. The conversation is
     * derived the same way [deliverChat] scopes a message: a group by the compact [TypingContent.groupId] (a group
     * we don't know locally is ignored — nothing to show it in), a DM addressed to us keyed by the other party, and
     * the broadcast room otherwise. A DM not addressed to us and our own cue looping back are dropped. Auto-expires
     * in the tracker; a real message from the same sender clears it early ([deliverChat] → [TypingTracker.onMessageFrom]).
     */
    private suspend fun handleTyping(env: RelayEnvelope) {
        val me = identity.nodeId()
        if (env.senderId == me) return
        val groupId = WireCodec.decodePayload<TypingContent>(env.payload)?.groupId
        val conversationId =
            when {
                groupId != null -> if (groups.find(groupId) == null) return else groupId
                env.recipientId == null -> Conversations.NEARBY
                !Conversations.isForMe(env.recipientId, me) -> return
                else -> env.senderId
            }
        typingTracker.onTyping(conversationId, env.senderId)
    }

    /**
     * Applies a delivery receipt. [verifyInbound] has already proven it is signed by the receipt's senderId;
     * we additionally require that sender to be the acked message's DM recipient before flipping the tick
     * or purging the carried copy — otherwise any node could forge a receipt to spoof delivery or evict an
     * undelivered DM. A broadcast/group message (recipientId null) keeps the legacy best-effort tick, and a
     * message we don't hold makes [MessageRepository.markReceived] a harmless no-op.
     */
    private suspend fun handleReceipt(env: RelayEnvelope) {
        val ackId = WireCodec.decodePayload<ReceiptContent>(env.payload)?.ackId ?: return
        val recipientOfAcked = messages.recipientOf(ackId)
        if (recipientOfAcked == null || recipientOfAcked == env.senderId) {
            messages.markReceived(ackId)
        }
        forwardSync.onAck(ackId, env.senderId)
    }

    /**
     * Resolves the public-key bundle a flooded frame's signature must verify against: a [FrameType.PROFILE]
     * carries its bundle in-band (first contact arrives before any pin), every other type uses the sender's
     * pinned key. Shared by [verifyInbound] (the delivery gate) and [canCarry] (the custody gate) so both
     * authenticate a profile the same way — via its own key — instead of a pin it may not have yet. Null when
     * there is no key to verify with (an unpinned non-profile sender, or a malformed in-band key).
     */
    private suspend fun verifierBundle(env: RelayEnvelope): PublicKeyBundle? =
        when {
            // Our own frame looping back (a neighbor carried it and re-served it): verify against our identity's
            // own bundle, which we always have. We never pin our own key in `peers`, so without this branch a
            // re-served self frame fails the pinned-key lookup below and is dropped — and after a custody wipe
            // (a destructive DB migration) we can then never re-carry our own sends, so the content digest never
            // reconverges with peers who still hold them. Checked first: it out-ranks the PROFILE in-band path.
            env.senderId == identity.nodeId() -> {
                PublicKeyBundle.decode(identity.publicKeyBundle())
            }

            env.type == FrameType.PROFILE -> {
                WireCodec.decodePayload<ProfileContent>(env.payload)?.pubKey?.let { PublicKeyBundle.decode(it) }
            }

            else -> {
                peers.find(env.senderId)?.pubKey?.let { PublicKeyBundle.decode(it) }
            }
        }

    /**
     * Authenticates a flooded frame: the frame [WireEnvelope.sig] must verify (byte-exact, over the
     * received [WireEnvelope.signed]) against a public-key bundle that derives back to the
     * [RelayEnvelope.senderId]. A profile carries that bundle in-band (first contact arrives before any
     * pin); every other type uses the sender's pinned key, so a frame from a peer whose profile we
     * haven't received yet is dropped. The point-to-point blob request is unsigned by design. An unknown
     * future type falls through to the pinned-key path: if it verifies we still don't deliver it (the
     * [onDeliver] dispatch has no handler) but the router relays it onward.
     *
     * Wrapped in [runCatching] so it NEVER throws out of [onDeliver]: any failure returns false =
     * "drop locally", and the router still schedules the relay (it runs after onDeliver returns).
     */
    private suspend fun verifyInbound(
        env: RelayEnvelope,
        wire: WireEnvelope,
        fromNodeId: String,
    ): Boolean =
        runCatching {
            if (env.type == FrameType.BLOB_REQ) return true
            // A self frame — one of OUR own frames looping back, after a neighbor carried it and (once our SeenSet
            // window lapsed) re-served it — is authenticated like any other via [verifierBundle], which resolves
            // our identity's own bundle for it. It USED to be dropped here as a silent no-op ("we already have
            // it"), but that assumption breaks after a destructive DB migration wipes custody: peers still hold
            // our sends and re-serve them, yet the drop stopped [onDeliver]'s carry from re-custodying them, so
            // the content digest never reconverged. Letting it through re-carries it; delivery is idempotent
            // ([deliverChat]'s isNew gate, own-message notifications already skipped), so a duplicate is harmless.
            val bundle = verifierBundle(env)
            if (bundle == null) {
                metrics.onDropped(DropReason.NO_SENDER_KEY)
                // Try to recover the sender's key so future frames from it verify (the inbound key-request
                // path). Excludes a key request itself (don't request keys for key-requesters — no recursion),
                // a profile (its key rides in-band, so a null bundle there means a malformed key, not an absent
                // pin that a request could fill), and a typing cue (ephemeral best-effort presence — with no
                // pinned key we can't render the peer's avatar anyway, so drop it silently rather than spend a
                // key request / park slot on a frame that's worthless a moment later). Safe inside
                // verifyInbound's runCatching — never throws.
                if (env.type != FrameType.KEY_REQ && env.type != FrameType.PROFILE && env.type != FrameType.TYPING) {
                    keyExchange.want(env.senderId)
                    // Park a deliverable frame so it's replayed once the key arrives (handleProfile), instead of
                    // being lost — the inbound complement of the outbound pendingKey/flushPendingFor retransmit.
                    if (FrameType.isReplayable(env.type)) pendingInbound.hold(wire, env, fromNodeId)
                }
                Log.w(TAG, "drop ${env.type} ${env.id} from ${env.senderId}: no key to verify it")
                return false
            }
            // The verifying key must provably belong to the claimed senderId (a nodeId IS the hash of the
            // bundle). Mirrors the pin check in handleProfile; also rejects stale device-derived pins.
            if (NodeId.fromPublicKeyBundle(bundle.encoded) != env.senderId) {
                metrics.onDropped(DropReason.KEY_NODEID_MISMATCH)
                Log.w(TAG, "drop ${env.type} ${env.id} from ${env.senderId}: key does not match nodeId")
                return false
            }
            if (!MessageCrypto.verify(bundle, wire.sig, wire.signed)) {
                metrics.onDropped(DropReason.SIG_INVALID)
                Log.w(TAG, "drop ${env.type} ${env.id} from ${env.senderId}: bad/missing signature")
                return false
            }
            true
        }.getOrElse {
            metrics.onDropped(DropReason.VERIFY_ERROR)
            Log.w(TAG, "drop frame ${env.id} from ${env.senderId}: verification error ${it.message}")
            false
        }

    /**
     * Applies an inbound reaction. [ReactionRepository.apply] is last-writer-wins, so duplicates and
     * out-of-order add/retract/replace frames are idempotent. The target message may not exist yet
     * (reactions can outrun the message over the mesh) — the row persists regardless and the UI joins.
     */
    private suspend fun handleReaction(env: RelayEnvelope) {
        // Blocked sender: drop the reaction (the router still relays it onward to other peers).
        if (env.senderId in settings.blockedNodeIds.first()) return
        val content = WireCodec.decodePayload<ReactionContent>(env.payload) ?: return
        reactions.apply(
            ReactionEntity(
                messageId = content.messageId,
                reactorNodeId = env.senderId,
                emoji = content.emoji,
                updatedAt = env.sentAt,
            ),
        )
    }

    private suspend fun handleChat(env: RelayEnvelope) {
        val me = identity.nodeId()
        // Blocked sender: never persist, notify, or ack — but the router still relays it onward, so a
        // blocked user stays a working mesh peer (we just don't surface anything from them).
        if (env.senderId in settings.blockedNodeIds.first()) return
        val content = WireCodec.decodePayload<ChatContent>(env.payload) ?: return
        // Group messages take the membership-gated path; they carry recipientId null, so they must be
        // handled before the DM check below (which would otherwise treat them as broadcast).
        val group = env.group
        if (group != null) {
            if (reconcileGroup(group, env.senderId, env.sentAt, me)) decryptAndDeliver(env, content, me, group.id)
            return
        }
        // A DM addressed to someone else: we're only relaying it (the router floods it onward). It
        // isn't ours, so don't persist, notify, or ack it.
        if (!Conversations.isForMe(env.recipientId, me)) return
        decryptAndDeliver(env, content, me, Conversations.idFor(env.senderId, env.recipientId, me))
    }

    /**
     * For an encrypted DM/group frame, decrypts it (dropping it on any failure) and delivers the
     * plaintext; a plaintext (broadcast) frame is delivered as-is. The sender's signature was already
     * verified over the whole frame in [verifyInbound]. Decryption is wrapped so a failure NEVER throws
     * out of the inbound handler — the router schedules the relay *after* onDeliver returns, so an
     * exception here would silently stop us forwarding the frame to other peers.
     */
    private suspend fun decryptAndDeliver(
        env: RelayEnvelope,
        content: ChatContent,
        me: String,
        conversationId: String,
    ) {
        val enc = content.enc
        if (enc == null) {
            deliverChat(env, content, me, conversationId)
            return
        }
        val plain =
            runCatching { decrypt(env, enc, me) }.getOrElse {
                Log.w(TAG, "drop encrypted chat ${env.id}: ${it.message}")
                null
            } ?: return
        deliverChat(
            env,
            content.copy(
                body = plain.body,
                mentions = plain.mentions,
                attachmentHash = plain.attachmentHash,
                attachmentMime = plain.attachmentMime,
                enc = null,
                replyTo = plain.replyTo,
            ),
            me,
            conversationId,
            plain.attachmentKey,
        )
    }

    /**
     * Unwraps + decrypts an [enc] envelope, gating the crypto-scheme and content-schema versions.
     * Returns null (drop) on an unsupported version or decryption failure. Authentication already
     * happened in [verifyInbound] (the frame signature covers the whole envelope), so this only needs
     * our own hybrid private key.
     */
    private suspend fun decrypt(
        env: RelayEnvelope,
        enc: EncEnvelope,
        me: String,
    ): MessageContent? {
        if (enc.v > EncEnvelope.MAX_SUPPORTED_VERSION) {
            metrics.onDropped(DropReason.UNKNOWN_ENVELOPE_VERSION)
            Log.w(TAG, "drop encrypted chat ${env.id}: unsupported envelope v=${enc.v}")
            return null
        }
        val thread = env.group?.id ?: env.recipientId.orEmpty()
        val header = MessageCrypto.header(env.id, env.senderId, env.sentAt, thread)
        val content = messageCrypto.open(enc, header, me)
        if (content == null) {
            metrics.onDropped(DropReason.DECRYPT_FAILED)
            return null
        }
        if (!content.isSupported()) {
            metrics.onDropped(DropReason.UNKNOWN_CONTENT_VERSION)
            Log.w(TAG, "drop chat ${env.id}: unsupported content v=${content.v}")
            return null
        }
        return content
    }

    /**
     * Whether we should carry a relayed frame for store-and-forward (the [ForwardSync] authenticate hook for
     * ORIGIN_RELAY). The sender must not be blocked and the frame signature must verify byte-exact over the
     * received signed bytes against the key that derives to its senderId (via [verifierBundle], so a profile
     * authenticates on its in-band key and every other type on the sender's pinned key) — a node never stores
     * unauthenticated junk, only frames an identified sender actually authored. A DM/group *chat* frame is
     * carried only in its encrypted form (a carrier holds it without reading); the broadcast room and the
     * cleartext metadata frames (reaction/receipt/group-update/group-leave/profile) carry no enc envelope and
     * are held on their signature alone. Without carrying these, only a frame's author would hold it (via
     * ORIGIN_SELF), so custody / cue-plane anti-entropy would never converge between peers. Our own sends
     * bypass this check.
     */
    private suspend fun canCarry(
        wire: WireEnvelope,
        env: RelayEnvelope,
    ): Boolean {
        if (env.senderId in settings.blockedNodeIds.first()) return false
        if (env.type == FrameType.CHAT) {
            val content = WireCodec.decodePayload<ChatContent>(env.payload) ?: return false
            val isBroadcast = env.recipientId == null && env.group == null
            if (!isBroadcast && content.enc == null) return false // DM/group must be carried encrypted
        }
        val bundle = verifierBundle(env)
        if (bundle == null || NodeId.fromPublicKeyBundle(bundle.encoded) != env.senderId) {
            metrics.onDropped(DropReason.CARRY_REFUSED)
            return false
        }
        if (!MessageCrypto.verify(bundle, wire.sig, wire.signed)) {
            metrics.onDropped(DropReason.CARRY_REFUSED)
            return false
        }
        return true
    }

    /**
     * Custody the image blob a just-carried chat frame references (the [ForwardSync] onCarried hook), so a late
     * joiner can pull it from us long after the sender left. Eager-pulls the content-addressed blob into the
     * encrypted store, where the forward_store reference (see [BlobDao.orphanHashes]) keeps it durable for the
     * frame's carried lifetime — upgrading today's transient relay-cache into real custody. A pull-time byte
     * budget ([CARRIER_BLOB_BUDGET_BYTES]) bounds this altruistic footprint: over budget we skip the pull (the
     * frame stays carried, only the image is absent) and the [resumePendingFetches]/neighbor-join retry backfills
     * as older custody frames expire. The carrier holds ciphertext it can't decrypt or screen (a fresh key never
     * arrives for it, so [screenObtainedAttachment] is a no-op); the addressed recipient screens on decrypt.
     * A no-op for our own sends and delivered messages — [blobStore] already holds the blob.
     */
    private suspend fun onCarriedFrame(env: RelayEnvelope) {
        if (env.type != FrameType.CHAT) return
        val hash = WireCodec.decodePayload<ChatContent>(env.payload)?.attachmentHash ?: return
        if (blobStore.has(hash)) return
        if (blobs.carrierOnlyBlobBytes() >= CARRIER_BLOB_BUDGET_BYTES) return
        blobExchange.want(hash)
    }

    /**
     * Handles a standalone group-metadata update (e.g. a rename): reconciles the stored group, with no
     * message to persist or ack. A member who didn't yet know the group creates it from the roster.
     */
    private suspend fun handleGroupUpdate(env: RelayEnvelope) {
        val group = env.group ?: return
        reconcileGroup(group, env.senderId, env.sentAt, identity.nodeId())
    }

    /**
     * Handles a member's departure. [verifyInbound] has already proven the frame is signed by the key
     * that derives to the [RelayEnvelope.senderId], so the leaver is authenticated and can only remove
     * itself. [GroupRepository.recordDeparture] shrinks the roster (the count drops reactively), records
     * the tombstone, and inserts the status notice — atomically, and as a no-op (returns false) for a
     * blocked sender's frame, a replay, a non-member, or a group we've left. We additionally skip a
     * blocked sender up front so neither the notice nor the roster change is applied for them.
     */
    private suspend fun handleGroupLeave(env: RelayEnvelope) {
        if (env.senderId in settings.blockedNodeIds.first()) return
        val groupId = WireCodec.decodePayload<GroupLeaveContent>(env.payload)?.groupId ?: return
        groups.recordDeparture(groupId, env.senderId, env.sentAt)
    }

    /**
     * Brings the locally-stored group in line with a self-describing [group] roster carried on a frame
     * from [senderId] (stamped [sentAt]). Returns true when the group is active for us (so a chat frame
     * should be delivered), false when the frame must be ignored: blocked sender, a group we've left
     * (never re-upserted, so a frame can't resurrect it), one we're not a member of, or a *new* group
     * whose creator we've blocked (covers the proxy case where a non-blocked member relays the first
     * frame carrying a blocked createdBy). The name is last-writer-wins on [sentAt] so concurrent renames
     * across the mesh converge.
     */
    private suspend fun reconcileGroup(
        group: GroupInfo,
        senderId: String,
        sentAt: Long,
        me: String,
    ): Boolean {
        val existing = groups.find(group.id)
        if (groupFrameRefused(group, senderId, existing, me)) return false

        // The name is shared only when explicitly set; an unnamed (blank/null) frame never clears a name
        // someone else set. Adopt an incoming name only if it's newer (last-writer-wins on sentAt).
        val incomingName = group.name?.takeIf { it.isNotBlank() }?.take(TextLimits.GROUP_NAME)
        val keepName = existing?.name.orEmpty()
        val keepClock = existing?.nameUpdatedAt ?: 0L
        val takeIncoming = incomingName != null && sentAt >= keepClock
        val photo = groupPhotoDecision(existing, group)
        // Preserve our departure tombstone across the wholesale roster overwrite and re-subtract it, so a
        // member who left stays gone even when this frame carries the stale pre-departure roster (a
        // straggler who never saw the GroupLeaveFrame). The set only grows, so this can't re-add a leaver.
        val keepDeparted = existing?.let { GroupMembersStore.decode(it.departed) }.orEmpty()
        val effective = group.members.filter { it !in keepDeparted }
        groups.upsert(
            GroupEntity(
                groupId = group.id,
                name = if (takeIncoming) incomingName else keepName,
                members = GroupMembersStore.encode(effective),
                createdBy = group.createdBy,
                createdAt = existing?.createdAt ?: sentAt,
                nameUpdatedAt = if (takeIncoming) sentAt else keepClock,
                left = false,
                departed = GroupMembersStore.encode(keepDeparted),
                photoHash = photo.hash,
                photoUpdatedAt = photo.clock,
            ),
        )
        // A newer photo whose bytes we don't hold yet: pull it hop-by-hop (after the upsert advanced the
        // clock, so the adopt-on-arrival clock check matches), then adopt on arrival.
        photo.pull?.let { pullGroupPhoto(group.id, it, photo.clock) }
        return true
    }

    /**
     * Whether an inbound group frame must be ignored: a blocked sender, a group we've left (never
     * re-upserted so a frame can't resurrect it), one we're not a member of, or a *new* group whose
     * creator we've blocked (covers the proxy case where a non-blocked member relays the first frame
     * carrying a blocked createdBy).
     */
    private suspend fun groupFrameRefused(
        group: GroupInfo,
        senderId: String,
        existing: GroupEntity?,
        me: String,
    ): Boolean {
        val blocked = settings.blockedNodeIds.first()
        return senderId in blocked ||
            existing?.left == true ||
            !Conversations.isGroupMember(group.members, me) ||
            (existing == null && group.createdBy in blocked)
    }

    /**
     * Resolves a group's photo last-writer-wins on its own clock ([GroupInfo.photoUpdatedAt]), independent
     * of the name's sentAt clock so a stale chat message re-asserting an old photo can't revert a newer one.
     * The clock advances as soon as a newer photo is announced (so a later frame can't re-open the race),
     * but the visible [PhotoDecision.hash] only swaps to the new photo once its bytes are local (a
     * peer-avatar-style invariant — a stored photoHash always renders); otherwise [PhotoDecision.pull] names
     * the hash to fetch and the old photo is kept until it arrives.
     */
    private suspend fun groupPhotoDecision(
        existing: GroupEntity?,
        group: GroupInfo,
    ): PhotoDecision {
        val incomingPhoto = group.photoHash
        val incomingPhotoClock = group.photoUpdatedAt ?: 0L
        val keepPhoto = existing?.photoHash
        val keepPhotoClock = existing?.photoUpdatedAt ?: 0L
        val takePhoto =
            incomingPhoto != null && incomingPhoto != keepPhoto && incomingPhotoClock >= keepPhotoClock
        if (!takePhoto) return PhotoDecision(keepPhoto, keepPhotoClock, pull = null)
        val haveBytes = blobStore.has(incomingPhoto)
        return PhotoDecision(
            hash = if (haveBytes) incomingPhoto else keepPhoto,
            clock = incomingPhotoClock,
            pull = if (haveBytes) null else incomingPhoto,
        )
    }

    /** A reconciled group photo: the hash to store, its clock, and (if its bytes aren't local) the hash to pull. */
    private data class PhotoDecision(
        val hash: String?,
        val clock: Long,
        val pull: String?,
    )

    /**
     * Records a group's advertised-but-not-yet-local photo and pulls its bytes over the same
     * content-addressed [BlobExchange] that carries avatars/attachments. Attributed back to the group in
     * [adoptAdvertisedGroupPhoto] on arrival. Group photos are pull-only (no direct push like avatars), so
     * this runs for direct neighbors too — the holder serves the blob when [BlobExchange.want] reaches it.
     */
    private suspend fun pullGroupPhoto(
        groupId: String,
        hash: String,
        clock: Long,
    ) {
        advertisedGroupPhotos[groupId] = AdvertisedPhoto(hash, clock)
        blobExchange.want(hash)
    }

    /**
     * A pulled blob just landed: if any group advertised it as its photo (see [reconcileGroup]), adopt it
     * onto that group now that the bytes are local — but only if it's still the group's current photo (the
     * clock still matches; a newer photo arriving meanwhile supersedes it) and, with content filtering on,
     * not flagged explicit by the screen in [MeshBlobStore.saveIncoming]. A no-op for blobs no group wants.
     */
    private suspend fun adoptAdvertisedGroupPhoto(hash: String) {
        val targets = advertisedGroupPhotos.entries.filter { it.value.hash == hash }.map { it.key }
        if (targets.isEmpty()) return
        // Mirror the avatar gate: don't adopt an explicit photo when filtering is on (the setting gates
        // receive-side hiding, so off -> adopt anyway); drop the now-unwanted blob.
        if (settings.contentFilteringEnabled.first() && blobs.isImageFlagged(hash)) {
            targets.forEach { advertisedGroupPhotos.remove(it) }
            blobs.deleteIfUnreferenced(hash)
            return
        }
        targets.forEach { groupId ->
            val advertised = advertisedGroupPhotos[groupId] ?: return@forEach
            if (advertised.hash != hash) return@forEach // superseded by a newer photo; leave it pending
            advertisedGroupPhotos.remove(groupId)
            val group = groups.find(groupId) ?: return@forEach
            // The stored clock must still equal what we recorded — else a newer photo won the race.
            if (group.photoUpdatedAt != advertised.clock || group.photoHash == hash) return@forEach
            val oldHash = group.photoHash
            groups.upsert(group.copy(photoHash = hash))
            if (oldHash != null && oldHash != hash) blobs.deleteIfUnreferenced(oldHash)
        }
    }

    /** A group's advertised photo (content hash + its last-writer-wins clock) whose bytes are being pulled. */
    private data class AdvertisedPhoto(
        val hash: String,
        val clock: Long,
    )

    /**
     * Persists an inbound chat into [conversationId], starts pulling any attachment blob we don't hold,
     * fires the appropriate notification, and acks. Shared by the DM/broadcast and group delivery paths.
     */
    private suspend fun deliverChat(
        env: RelayEnvelope,
        content: ChatContent,
        me: String,
        conversationId: String,
        attachmentKey: String? = null,
    ) {
        // A real message from this sender supersedes any "typing" indicator for them in this thread — clear it
        // now (idempotent, and a no-op if they weren't shown as typing). Runs on re-delivery too, harmlessly.
        typingTracker.onMessageFrom(conversationId, env.senderId)
        // First-ever delivery? Store-and-forward can re-serve a DM we already hold (after the 10-min
        // SeenSet window, or after a restart that empties it); notifying again would replay old messages.
        // The save below is an idempotent upsert, so only the notification needs gating.
        val isNew = !messages.exists(env.id)
        val hash = content.attachmentHash
        messages.save(
            MessageEntity(
                id = env.id,
                senderId = env.senderId,
                recipientId = env.recipientId,
                conversationId = conversationId,
                body = content.body,
                sentAt = env.sentAt,
                received = false,
                mentions = MentionStore.encode(content.mentions),
                attachmentHash = hash,
                attachmentMime = content.attachmentMime,
                attachmentKey = attachmentKey,
                moderation =
                    if (
                        isTextFlagged(content.body, "incoming", isRoom = conversationId == Conversations.NEARBY)
                    ) {
                        MessageEntity.MODERATION_TEXT_FLAGGED
                    } else {
                        MessageEntity.MODERATION_NONE
                    },
            ).withReply(content.replyTo),
        )
        // Start pulling the referenced blob unless we already hold it (the UI observes the blobs table
        // and flips the attachment from "loading" to shown once the bytes land). If we already hold it
        // (e.g. cached earlier while relaying), screen its decrypted bytes now that the key is in hand;
        // otherwise it's screened on arrival ([screenObtainedAttachment]) once the key has been stored.
        if (hash != null) {
            if (blobStore.has(hash)) {
                screenEncryptedAttachment(hash, attachmentKey)
            } else {
                blobExchange.want(hash)
                // Arm the fast plane toward the author — the guaranteed holder — so the pull can ride a NAN
                // NDP instead of crawling over BLE (the serve side arms its own half in the composite; only
                // the larger nodeId of a pair ever initiates, so an inert mark here is harmless). Freshness/
                // cooldown gating lives in the transport; a multi-hop author simply isn't fresh and no-ops.
                transport.expectBulkTransfer(env.senderId)
            }
        }
        // A message that @-mentions us notifies on the dedicated Mentions channel only; everything else
        // takes the per-context channel (Nearby / Group messages / Direct messages), keyed off conversationId.
        // Only on first delivery — a re-served carried DM must not re-notify.
        if (isNew) {
            if (env.senderId != me && content.mentions.mention(me)) {
                notifyMention(env, content, conversationId)
            } else {
                notifyIncoming(env, content, conversationId)
            }
        }
        // Ack unconditionally (even on a re-delivery): the receipt floods back to the sender and, for a
        // DM, doubles as the vaccine that purges this message from any carrier that missed the first ack.
        acknowledge(env, me)
    }

    /**
     * Whether [text] is non-blank and the on-device moderator flags it as abusive. Always runs — not
     * gated by the content-filtering setting, which only governs receive-side hiding. Drives both
     * block-on-send (in [sendChat], a send-side "good-citizen"/Nearby check) and the stored flag on
     * inbound messages (in [deliverChat]); a flagged inbound message is still stored and merely collapsed
     * in the UI (collapse itself gated at display time by the setting, see [ChatViewModel]), so a false
     * positive never silently drops content. [isRoom] selects the moderation scope: the Nearby
     * broadcast room gets profanity + toxicity, while DMs and groups get toxicity only (see
     * [ScopedTextModerator]). [direction] (`"outgoing"`/`"incoming"`) only labels the debug log; the
     * verdict score/category/decision is logged under [TEXT_MODERATION_TAG], mirroring the image
     * screen's `ImageModeration` logging — the body itself is never logged (only its length).
     */
    private suspend fun isTextFlagged(
        text: String,
        direction: String,
        isRoom: Boolean,
    ): Boolean {
        if (text.isBlank()) return false
        val verdict = textModeration.classify(text, isRoom)
        Log.d(
            TEXT_MODERATION_TAG,
            "$direction text score=${verdict.score} category=${verdict.category} " +
                "label=${verdict.label} flagged=${verdict.flagged} len=${text.length}",
        )
        return verdict.flagged
    }

    /**
     * Sends a delivery receipt for [frame]. A DM addressed to us floods its receipt via the router
     * ([originateSigned], relay = true) so it reaches the sender across multiple hops and is custodied like any
     * flood frame (the recipient is the only one who acks). Broadcast and group messages have no single
     * recipient, so the tick is best-effort: a **point-to-point** receipt (relay = false) is sent straight back
     * to the author over the coordination plane ([MeshTransport.fastSend]), which needs no data path — so it
     * works whether the message arrived via an NDP flood *or* a coordination-plane fast-fanout. (The old
     * direct-neighbor NDP send silently dropped the fast-fanned case: with no live NDP link, `neighbors` is
     * empty and the ack was never sent.) No-ops if the author isn't currently reachable.
     */
    private suspend fun acknowledge(
        env: RelayEnvelope,
        me: String,
    ) {
        if (env.recipientId == me) {
            // DM: flood so the receipt reaches the sender across hops and is custodied like any flood frame.
            val ack =
                RelayEnvelope(
                    type = FrameType.RECEIPT,
                    id = FrameId.new(),
                    senderId = me,
                    payload = WireCodec.encodePayload(ReceiptContent(env.id)),
                )
            originateSigned(ack)
        } else {
            // Broadcast/group: a unicast, point-to-point (relay = false) tick straight to the author — no NDP
            // required (a fast-fanned message gets its receipt too) and never flooded/custodied. Best-effort, so
            // AckSync remembers it and re-sends until it lands (or ages out): the message itself converges via
            // custody, but this tick otherwise had no delay-tolerance and was lost whenever the author was out of
            // range at delivery time.
            ackSync.owe(env.id, env.senderId)
        }
    }

    /** Fires a "new message" notification for an inbound chat in [conversationId] (skips our own and empty messages). */
    private suspend fun notifyIncoming(
        env: RelayEnvelope,
        content: ChatContent,
        conversationId: String,
    ) {
        val me = identity.nodeId()
        val peer = peers.find(env.senderId)
        val peerAvatar = peer?.avatarHash?.let { blobs.bytes(it) }
        // Image-only messages have a blank body; show a placeholder so they still notify.
        val body = content.body.ifBlank { if (content.attachmentHash != null) "📷 Photo" else content.body }
        val incoming =
            incomingNotification(
                senderId = env.senderId,
                body = body,
                sentAt = env.sentAt,
                selfId = me,
                peerName = peer?.name,
                peerAvatarBytes = peerAvatar,
                conversationId = conversationId,
            ) ?: return
        val conversation = resolveConversation(conversationId, env.senderId, peer?.name, peerAvatar, me)
        val selfAvatar = settings.ownAvatarHash.first()?.let { blobs.bytes(it) }
        notifier.notify(incoming, conversation, me, settings.displayName.first(), selfAvatar)
    }

    /** Fires a "you were mentioned" notification on the Mentions channel for an inbound chat in [conversationId]. */
    private suspend fun notifyMention(
        env: RelayEnvelope,
        content: ChatContent,
        conversationId: String,
    ) {
        val me = identity.nodeId()
        val peer = peers.find(env.senderId)
        val peerAvatar = peer?.avatarHash?.let { blobs.bytes(it) }
        val body = content.body.ifBlank { if (content.attachmentHash != null) "📷 Photo" else content.body }
        val incoming =
            mentionNotification(
                senderId = env.senderId,
                body = body,
                sentAt = env.sentAt,
                selfId = me,
                peerName = peer?.name,
                peerAvatarBytes = peerAvatar,
                conversationId = conversationId,
            ) ?: return
        val conversation = resolveConversation(conversationId, env.senderId, peer?.name, peerAvatar, me)
        val selfAvatar = settings.ownAvatarHash.first()?.let { blobs.bytes(it) }
        notifier.notifyMention(incoming, conversation, me, settings.displayName.first(), selfAvatar)
    }

    /**
     * Resolves the conversation-level title + avatar a Signal-style notification shows (the group photo /
     * DM peer avatar as its large icon, the real thread name as its title). A DM uses the sender's
     * name/avatar; a group looks up its stored name/photo (falling back to member names via [groupTitle]);
     * the Nearby room leaves both null so [notifier] substitutes its own defaults.
     */
    private suspend fun resolveConversation(
        conversationId: String,
        senderId: String,
        dmName: String?,
        dmAvatar: ByteArray?,
        me: String,
    ): NotifConversation =
        when (Conversations.kindFor(conversationId)) {
            ConversationKind.NEARBY -> {
                NotifConversation(conversationId, null, null, ConversationKind.NEARBY)
            }

            ConversationKind.DM -> {
                NotifConversation(conversationId, displayNameFor(dmName, senderId), dmAvatar, ConversationKind.DM)
            }

            ConversationKind.GROUP -> {
                val group = groups.find(conversationId)
                val memberIds = group?.let { GroupMembersStore.decode(it.members) }.orEmpty()
                // Pre-resolve member names off the suspend peer lookups, since groupTitle's nameOf is non-suspend.
                val namesByNode = LinkedHashMap<String, String>()
                for (id in memberIds) namesByNode[id] = displayNameFor(peers.find(id)?.name, id)
                val title =
                    group?.let {
                        groupTitle(it.name, memberIds, me, fallback = "") { id -> namesByNode[id] ?: id }.ifBlank { null }
                    }
                NotifConversation(conversationId, title, group?.photoHash?.let { blobs.bytes(it) }, ConversationKind.GROUP)
            }
        }

    private suspend fun handleProfile(
        env: RelayEnvelope,
        wire: WireEnvelope,
    ) {
        val content = WireCodec.decodePayload<ProfileContent>(env.payload) ?: return
        // Self-certifying identity: a peer's nodeId IS the hash of its public-key bundle, so a profile
        // is only trustworthy if the advertised key actually derives back to the claimed senderId.
        // This makes the key pin race-proof — a peer cannot pin a key for a nodeId it doesn't hold the
        // keypair for (impersonating one would require a hash collision), so there is no first-speaker
        // TOFU window. A null or mismatched key is dropped outright.
        val pubKey = content.pubKey
        if (pubKey == null || NodeId.fromPublicKeyBundle(pubKey) != env.senderId) {
            Log.w(TAG, "drop profile from ${env.senderId}: key does not derive to its nodeId")
            return
        }
        val existing = peers.find(env.senderId)
        // Last-writer-wins: ignore a profile older than the one we already hold. The key is immutable per
        // nodeId (a different key would be a hash collision, excluded above), so an out-of-order or
        // re-served copy can never change the pinned key — it could only revert name/status. A first
        // profile (existing == null) is always accepted, so this never blocks recovering a missing key.
        if (existing != null && env.sentAt < existing.updatedAt) return
        val advertised = content.avatarHash
        // The stored avatarHash means "bytes are present locally": adopt the advertised hash only once
        // we hold its blob, otherwise keep the current avatar (if any) until the new one is fetched.
        val haveAvatar = advertised != null && blobStore.has(advertised)
        // The key is bound to the nodeId, so a verified peer's key cannot legitimately change; keep the
        // pinned-verified state as-is. (A different key for the same nodeId would be a hash collision,
        // already excluded above.)
        peers.upsert(
            (existing ?: PeerEntity(env.senderId)).copy(
                // Clamp inbound too: our own cap only bounds what we originate, not what a peer sends.
                name = content.name.take(TextLimits.DISPLAY_NAME),
                status = content.status.take(TextLimits.STATUS),
                pubKey = pubKey,
                verified = existing?.verified ?: false,
                deviceTag = content.deviceTag ?: existing?.deviceTag,
                avatarHash = resolveAvatarHash(advertised, haveAvatar, existing?.avatarHash),
                protoVersion = content.protoVersion ?: existing?.protoVersion,
                capabilities = content.capabilities ?: existing?.capabilities,
                updatedAt = env.sentAt,
            ),
        )
        reclaimRemovedAvatarIfCleared(env.senderId, advertised, existing?.avatarHash)
        applyDeviceTagBlockContinuity(env.senderId, content.deviceTag)
        pullRelayAvatarIfNeeded(env.senderId, advertised, haveAvatar)
        // The sender's key is now pinned: retransmit any DMs to them that were stuck awaiting it.
        flushPendingFor(env.senderId)
        // Cache this peer's verbatim signed profile so we can re-serve its key to a neighbor that asks, and
        // resolve any key request we (or a node we're relaying for) had outstanding for it.
        keyExchange.onProfilePinned(env.senderId, wire)
        // Replay any frames we parked from this sender while we couldn't verify them. Must run last: the
        // key is now pinned (so the replayed verifyInbound passes instead of re-parking) and any deviceTag
        // block has been applied (so a blocked sender is dropped on replay, not delivered). Replay bypasses
        // the router — no second flood, no SeenSet hit — and onDeliver's isNew/idempotent-save gates make a
        // later store-and-forward re-serve of the same frame a no-op.
        pendingInbound.release(env.senderId).forEach {
            metrics.onFrameReplayed()
            onDeliver(it.wire, it.env, it.fromNodeId)
        }
    }

    /**
     * Block-list continuity: a nodeId is the hash of a keypair, so a blocked peer that regenerates its
     * key returns under a new nodeId. If this peer's (key-independent) [deviceTag] is already blocked,
     * block this new [senderId] too — every other block check stays plain nodeId-based.
     */
    private suspend fun applyDeviceTagBlockContinuity(
        senderId: String,
        deviceTag: String?,
    ) {
        if (deviceTag != null &&
            senderId !in settings.blockedNodeIds.first() &&
            deviceTag in settings.blockedDeviceTags.first()
        ) {
            settings.block(senderId, deviceTag)
        }
    }

    /**
     * A direct neighbor pushes its avatar to us (sendAvatarIfNeeded); a peer we only reach through a
     * relay won't, so pull its [advertised] avatar hop-by-hop over the same content-addressed exchange
     * that carries attachments. Attributed back to this peer in [adoptAdvertisedAvatar] on arrival.
     */
    private suspend fun pullRelayAvatarIfNeeded(
        senderId: String,
        advertised: String?,
        haveAvatar: Boolean,
    ) {
        if (advertised != null && !haveAvatar &&
            transport.neighbors.value.none { it.nodeId == senderId }
        ) {
            advertisedAvatars[senderId] = advertised
            blobExchange.want(advertised)
        }
    }

    /**
     * The avatar hash to store for a peer from an inbound profile. [advertised] is what the profile
     * carries (null = the peer has no avatar):
     *  - adopt [advertised] once its blob is local ([haveAvatar]);
     *  - null when the peer advertises no avatar — an explicit removal, since a set avatar always rides as
     *    a non-null hash and [handleProfile]'s last-writer-wins gate guarantees this profile is the newest
     *    state, so the clear propagates instead of clinging to the old photo;
     *  - otherwise keep [current] until the advertised (but not-yet-fetched) blob arrives.
     */
    private fun resolveAvatarHash(
        advertised: String?,
        haveAvatar: Boolean,
        current: String?,
    ): String? =
        when {
            haveAvatar -> advertised
            advertised == null -> null
            else -> current
        }

    /**
     * When an inbound profile cleared a peer's avatar (null [advertised] over a non-null [previous] hash),
     * cancel any in-flight pull and reclaim the now-orphaned blob. Must run *after* the peer row is upserted
     * to null, so [BlobRepository.deleteIfUnreferenced] no longer sees the peer pointing at it.
     */
    private suspend fun reclaimRemovedAvatarIfCleared(
        senderId: String,
        advertised: String?,
        previous: String?,
    ) {
        if (advertised != null || previous == null) return
        advertisedAvatars.remove(senderId) // cancel any pending pull of the now-removed avatar
        blobs.deleteIfUnreferenced(previous)
    }

    /**
     * Retransmits DMs to [recipientId] that were composed before their key was known (saved pendingKey
     * by [sendChat]). Now that [handleProfile] has pinned the key, each is re-sealed and flooded (and
     * captured for carry via [originate]); a still-unresolvable or now-blocked recipient is left pending.
     */
    private suspend fun flushPendingFor(recipientId: String) {
        if (recipientId in settings.blockedNodeIds.first()) return
        val me = identity.nodeId()
        messages.pendingForRecipient(recipientId).forEach { row ->
            val content =
                MessageContent(
                    body = row.body,
                    mentions = MentionStore.decode(row.mentions),
                    attachmentHash = row.attachmentHash,
                    attachmentMime = row.attachmentMime,
                    attachmentKey = row.attachmentKey,
                    replyTo = row.replyRef(),
                )
            val header = MessageCrypto.header(row.id, me, row.sentAt, recipientId)
            val envelope =
                messageCrypto.seal(content.encode(), header, recipientBundles(recipientId, null, me))
                    ?: return@forEach
            originateSigned(
                chatEnvelope(
                    row.id,
                    me,
                    row.sentAt,
                    recipientId,
                    group = null,
                    // Same cleartext-hash exposure as sendChat, so a re-sealed pending DM's image is custodied too.
                    ChatContent(
                        enc = envelope,
                        attachmentHash = row.attachmentHash,
                        attachmentMime = row.attachmentMime,
                    ),
                ),
            )
            messages.clearPending(row.id)
        }
    }

    /**
     * A pulled blob just landed: if any non-direct peer advertised it as their avatar (see
     * [handleProfile]), point those peers at it now that the bytes are local and drop the previous one.
     * A no-op for attachment blobs, which no peer advertises.
     */
    private suspend fun adoptAdvertisedAvatar(hash: String) {
        val owners = advertisedAvatars.entries.filter { it.value == hash }.map { it.key }
        if (owners.isEmpty()) return
        // A pulled avatar was screened in MeshBlobStore.saveIncoming; with content filtering on, don't
        // adopt it if flagged explicit (the setting gates receive-side hiding, so off → adopt anyway).
        if (settings.contentFilteringEnabled.first() && blobs.isImageFlagged(hash)) {
            owners.forEach { advertisedAvatars.remove(it) }
            blobs.deleteIfUnreferenced(hash)
            return
        }
        owners.forEach { nodeId ->
            advertisedAvatars.remove(nodeId)
            val peer = peers.find(nodeId) ?: return@forEach
            if (peer.avatarHash == hash) return@forEach
            val oldHash = peer.avatarHash
            peers.upsert(peer.copy(avatarHash = hash))
            if (oldHash != hash) blobs.deleteIfUnreferenced(oldHash)
        }
    }

    /**
     * Ingests a direct neighbor's pushed avatar into the encrypted blob store, points the peer row at
     * it by [hash], deletes the decrypted staging copy, and garbage-collects the peer's previous blob.
     */
    private suspend fun onAvatarReceived(
        nodeId: String,
        hash: String,
        mime: String,
        srcPath: String,
    ) {
        val bytes = runCatching { File(srcPath).readBytes() }.getOrNull() ?: return
        // [hash] is the peer's claimed content address. Verify the bytes hash to it before storing, so a
        // neighbor can't push arbitrary bytes under another avatar's address (content-address spoofing).
        if (!isValidBlobHash(hash) || sha256Hex(bytes) != hash) {
            Log.w(TAG, "Dropping avatar from $nodeId: bytes do not match claimed hash")
            File(srcPath).delete()
            return
        }
        blobs.insert(hash, mime, bytes)
        File(srcPath).delete()
        advertisedAvatars.remove(nodeId) // pushed directly; no need to also pull it
        blobs.screenImage(hash, bytes)
        // With content filtering on, don't adopt an explicit avatar: leave the peer on its monogram
        // fallback and drop the blob (the setting gates receive-side hiding, so off → adopt anyway).
        if (settings.contentFilteringEnabled.first() && blobs.isImageFlagged(hash)) {
            blobs.deleteIfUnreferenced(hash)
            return
        }
        val existing = peers.find(nodeId)
        val oldHash = existing?.avatarHash
        peers.upsert((existing ?: PeerEntity(nodeId)).copy(avatarHash = hash))
        if (oldHash != hash) blobs.deleteIfUnreferenced(oldHash)
    }

    /**
     * A blob just landed via [BlobExchange]. If it's an E2E attachment we hold the key for (stored when
     * the message was delivered, which is what triggered the pull), screen its *decrypted* bytes. The
     * screen in [MeshBlobStore.saveIncoming] only ever sees the stored ciphertext for an encrypted
     * attachment — it can't decode it — so this is where receive-side image moderation actually runs for
     * DM/group attachments. A no-op for avatars and for relayed blobs we have no key for.
     */
    private suspend fun screenObtainedAttachment(hash: String) {
        screenEncryptedAttachment(hash, messages.attachmentKeyForHash(hash))
    }

    /**
     * Decrypts the stored ciphertext blob for [hash] with its base64 [key] and screens the plaintext
     * image, caching the verdict under the ciphertext [hash] — the same key the chat UI's flagged set
     * uses, so a flagged attachment blurs behind a tap-to-reveal. A no-op when there's no [key] (a
     * plaintext/broadcast attachment, already screened on arrival in [MeshBlobStore.saveIncoming]), the
     * blob isn't stored yet, or decryption fails. [BlobRepository.screenImage] is idempotent per hash, so
     * a repeat call (or a prior ciphertext screen) is harmless; it always caches a verdict (the
     * content-filtering setting gates only the receive-side blur at display time, not the scan).
     */
    private suspend fun screenEncryptedAttachment(
        hash: String?,
        key: String?,
    ) {
        if (hash == null || key == null) return
        val cipher = blobs.bytes(hash) ?: return
        val plain = AttachmentCrypto.open(cipher, b64d(key)) ?: return
        blobs.screenImage(hash, plain)
    }

    /** Periodically logs a transmission snapshot so flood-suppression and byte savings are visible. */
    private fun logMetricsPeriodically(session: CoroutineScope) {
        session.launch {
            while (true) {
                delay(METRICS_LOG_INTERVAL_MS)
                val s = metrics.snapshot()
                Log.d(
                    TAG,
                    "metrics: originated=${s.framesOriginated} delivered=${s.framesDelivered} " +
                        "relayed=${s.framesRelayed} suppressed=${s.framesSuppressed} " +
                        "deduped=${s.framesDeduped} bytesSent=${s.bytesSent} " +
                        "dropped=${s.framesDropped} drops=${s.dropsByReason} " +
                        "keyReq=${s.keyRequestsSent} keyServed=${s.keysServed} keyRecovered=${s.keysRecovered} " +
                        "framesHeld=${s.framesHeld} framesReplayed=${s.framesReplayed} " +
                        "receiptsResent=${s.receiptsResent} " +
                        "filesNan=${s.filesSentNan} filesBt=${s.filesSentBt} bulkTimeouts=${s.nanBulkGraceTimeouts}",
                )
            }
        }
    }

    private companion object {
        const val TAG = "MeshManager"
        const val TEXT_MODERATION_TAG = "TextModeration"
        const val METRICS_LOG_INTERVAL_MS = 60_000L
        const val FORWARD_SWEEP_INTERVAL_MS = 10 * 60_000L

        // Re-run the neighbor re-offer hooks (custody digest + blob/key re-asks) for currently-linked neighbors
        // this often, so a persistent link (Bluetooth) gets the anti-entropy that Wi-Fi Aware's flapping
        // ephemeral links get for free. Short enough to converge a missed message within ~a minute, long enough
        // to stay cheap on battery/bandwidth.
        const val NEIGHBOR_REOFFER_INTERVAL_MS = 60_000L

        // Min spacing between first-contact profile floods (watchReachable): a burst of newcomers costs one
        // origination; custody + the per-link pushProfileTo cover anyone the coalesced flood skipped.
        const val PROFILE_REFLOOD_MIN_MS = 30_000L

        /** Payload for a frame whose content lives entirely in the routing envelope (e.g. a group update). */
        val EMPTY_PAYLOAD = ByteArray(0)
    }
}
