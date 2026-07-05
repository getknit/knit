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
) : MeshController {
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
            onObtained = { hash, _ -> pipeline.onObtained(hash) },
        )

    // Store-and-forward DM custody: carries DMs we originate/relay and re-offers them to neighbors that
    // join later, so a message reaches a recipient that wasn't connected when it was first flooded.
    private val forwardSync =
        ForwardSync(
            transport = transport,
            store = forwardStore,
            authenticate = { wire, env -> pipeline.canCarry(wire, env) },
            // Fired once when a chat frame is actually carried: eager-pull its image blob so a custodied image
            // survives to a late joiner (the carrier holds ciphertext it can't read, like the frame itself).
            onCarried = { pipeline.onCarriedFrame(it) },
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

    // The inbound half of the mesh: verify → custody → dispatch → deliver/ack, plus the store-and-forward
    // carry gate and the avatar/group-photo/attachment screening. MeshManager still OWNS the DTN services
    // above and the outbound origination choke; the pipeline receives the services by reference and reaches
    // origination through the originate/flushPending lambdas (moderation through classifyText). Declared
    // after the services it consumes; the services' authenticate/onCarried/onObtained callbacks read
    // `pipeline` lazily (they never fire during construction — first only in start()'s launches), so the
    // mutual reference is safe.
    // Explicit type: the DTN services above take `pipeline.*` lambdas while `pipeline` takes the services,
    // so the type must be stated to break the inference cycle (the values still resolve — pipeline is a
    // stable field the deferred lambdas read at call time).
    private val pipeline: InboundPipeline =
        InboundPipeline(
            transport = transport,
            messages = messages,
            groups = groups,
            reactions = reactions,
            peers = peers,
            blobs = blobs,
            blobStore = blobStore,
            identity = identity,
            settings = settings,
            messageCrypto = messageCrypto,
            notifier = notifier,
            metrics = metrics,
            forwardSync = forwardSync,
            blobExchange = blobExchange,
            keyExchange = keyExchange,
            ackSync = ackSync,
            pendingInbound = pendingInbound,
            typingTracker = typingTracker,
            originate = ::originateSigned,
            flushPending = ::flushPendingFor,
            classifyText = ::isTextFlagged,
        )

    // Reconstructed per session so its inbound collector + relay jobs live on the session scope and are
    // cancelled by stop() (rather than leaking on the never-cancelled app scope). Declared after `pipeline`
    // so onDeliver targets it.
    private var router = MeshRouter(transport, scope, metrics = metrics, onDeliver = pipeline::onDeliver)

    @Volatile
    private var started = false

    // nodeId -> avatar hash we last sent that neighbor, so we don't re-push an unchanged avatar on
    // every profile edit or reconnect. Cleared per-peer when they disconnect (see watchNeighbors).
    private val sentAvatarHashes = ConcurrentHashMap<String, String>()

    /**
     * Number of nearby peers for the UI status header — the smoothed [MeshTransport.reachable] set (seen
     * over the coordination plane), not the ≤1 live data-path link, so the header doesn't blink as the
     * cue-driven transport rotates through ephemeral syncs.
     */
    override val neighborCount: StateFlow<Int> =
        transport.reachable
            .map { it.size }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    /** Nearby peers for the contact picker (message someone nearby) — the smoothed [MeshTransport.reachable] set. */
    override val neighbors: StateFlow<Set<Peer>> get() = transport.reachable

    /** Radio health for the Diagnostics screen (Healthy vs Degraded — e.g. radios seized by Quick Share). */
    override val transportHealth: StateFlow<TransportHealth> get() = transport.health

    /**
     * Per-radio status for the Diagnostics screen (Bluetooth vs Wi-Fi Aware: health + live-link/nearby counts),
     * so the merged [transportHealth]/[neighbors] can be broken back out by plane. In production the transport is
     * always a [CompositeMeshTransport]; the fallback describes a single non-composite transport (demo/fakes) as
     * one entry.
     */
    override val transportStatuses: StateFlow<List<TransportStatus>> =
        (transport as? CompositeMeshTransport)?.statuses
            ?: combine(transport.neighbors, transport.reachable, transport.health) { linked, nearby, health ->
                listOf(TransportStatus(transport.kind, health, linked.size, nearby.size))
            }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** nodeId → the radios each node is reachable over, so Diagnostics can tag a connected node BLE / NAN. */
    override val peerTransports: StateFlow<Map<String, Set<TransportKind>>> =
        (transport as? CompositeMeshTransport)?.peerTransports
            ?: transport.reachable
                .map { set -> set.associate { it.nodeId to setOf(transport.kind) } }
                .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** conversationId → the set of peers currently shown as "typing" there, for the chat UI. Ephemeral (TTL'd). */
    override val typing: StateFlow<Map<String, Set<String>>> get() = typingTracker.typing

    override fun start() {
        if (started) return
        started = true
        blobStore.clearTransfers() // drop any plaintext transfer temp files left by a previous session
        // Child of the app Job so app-scope cancellation still propagates; SupervisorJob isolates a
        // single collector's failure from the rest of the session. The shared handler logs any uncaught
        // throw in a top-level session collector instead of letting it vanish silently.
        val session =
            CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default + meshExceptionHandler)
        sessionScope = session
        router = MeshRouter(transport, session, metrics = metrics, onDeliver = pipeline::onDeliver)
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

    override fun stop() {
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
    override fun heal() {
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
    override fun restart() {
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
    override suspend fun sendChat(
        text: String,
        attachment: AttachmentStore.Ingested?,
        mentions: List<Mention>,
        recipientId: String?,
        group: GroupInfo?,
        replyTo: ReplyRef?,
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
    override suspend fun sendGroupUpdate(group: GroupInfo) {
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
    override suspend fun sendGroupLeave(groupId: String) {
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
    override suspend fun sendReaction(
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
    override suspend fun sendTyping(conversationId: String) {
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
                        pipeline.onAvatarReceived(file.fromNodeId, file.key, file.mime, file.path)
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
