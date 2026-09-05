package app.getknit.knit.mesh

import android.util.Log
import androidx.room3.withWriteTransaction
import app.getknit.knit.TextLimits
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.StatusNotices
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.data.message.withReply
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.identity.NodeId
import app.getknit.knit.identity.PeerLabelIndex
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.isValidReactionEmoji
import app.getknit.knit.mesh.crypto.AesGcm
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageContentV2
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import app.getknit.knit.mesh.crypto.readsCryptoV3
import app.getknit.knit.mesh.crypto.sealBytes
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.GroupKeyPayload
import app.getknit.knit.mesh.protocol.GroupLeaveContent
import app.getknit.knit.mesh.protocol.KeyReqContent
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.ProfilePayload
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.ReactionPayload
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.TypingContent
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.protocol.isStorable
import app.getknit.knit.mesh.protocol.mention
import app.getknit.knit.mesh.spool.ScopeSync
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.notifications.NotifConversation
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.notifications.incomingNotification
import app.getknit.knit.notifications.mentionNotification
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The inbound half of the mesh, extracted from [MeshManager]: it owns delivery of every frame the router
 * hands up — authenticate ([verifyInbound]), custody ([onDeliver]'s pre-relay [ForwardSync.onSeen]),
 * dispatch by type, decrypt, persist, notify, ack — plus the store-and-forward carry gate ([canCarry]) and
 * the avatar/group-photo/attachment screening. Pulled out so the security gate and its three load-bearing
 * ordering contracts are a focused, JVM-testable unit ([onDeliver] is directly drivable — see
 * `InboundPipelineTest`):
 *
 *  - **custody-before-relay** — [onDeliver] carries a floodable frame ([ForwardSync.onSeen], `ORIGIN_RELAY`)
 *    before dispatch returns and before the router schedules the relay, so the copy is durable pre-flood.
 *  - **replay-runs-last** — [handleProfile] pins the sender's key, then replays any frames parked in
 *    [PendingInbound] by re-entering [onDeliver] as its **last** step, so the key + any deviceTag block are
 *    applied first.
 *  - **no-throw-out-of-onDeliver** — [verifyInbound]/[decrypt] are `runCatching`-wrapped so a failure drops
 *    the frame locally but never throws, letting the router still relay it (it runs after onDeliver returns).
 *
 * [MeshManager] still **owns** the six DTN services and the outbound origination choke; this pipeline
 * receives the services by reference and reaches origination through the [originate]/[flushPending] lambdas
 * (and moderation through [classifyText]) — the same lambda-mediation the DTN services already use. It holds
 * no coroutine/session state, so it needs no start/stop hooks.
 */
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
class InboundPipeline(
    private val transport: MeshTransport,
    private val messages: MessageRepository,
    private val receipts: MessageReceiptRepository,
    private val groups: GroupRepository,
    private val reactions: ReactionRepository,
    private val peers: PeerRepository,
    private val blobs: BlobRepository,
    private val imageScreening: ImageScreeningService,
    private val blobStore: MeshBlobStore,
    private val db: KnitDatabase,
    private val identity: IdentitySource,
    private val settings: InboundSettings,
    private val messageCrypto: MessageCrypto,
    private val notifier: Notifier,
    private val metrics: MeshMetrics,
    private val forwardSync: ForwardSync,
    private val blobExchange: BlobExchange,
    private val keyExchange: KeyExchange,
    private val ackSync: AckSync,
    private val pendingInbound: PendingInbound,
    private val typingTracker: TypingTracker,
    private val ratchet: RatchetSessions,
    private val groupRatchet: GroupRatchetSessions,
    // Injectable wall clock for the receipt we originate (its sentAt is the frame-global custody expiry
    // anchor). Defaults to the real clock; mirrors the house convention — MeshManager, ForwardSync, AckSync.
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val originate: suspend (RelayEnvelope) -> Unit,
    // The same choke for a frame that is our own delivery receipt, so the long-range plane can rank it as
    // feedback rather than content (`FanoutHint.TICK`, ADR 054). Defaults to [originate] for the test rigs.
    private val originateTick: suspend (RelayEnvelope) -> Unit = originate,
    // Holds the ✓✓ for a DM that arrived over the LoRa board so a burst becomes one tick and a reply can
    // carry the acks (ADR 054) — MeshManager's [DmAckCoalescer]; null (the rigs' default) acks instantly.
    private val dmAcks: DmAckCoalescer? = null,
    private val flushPending: suspend (String) -> Unit,
    private val classifyText: suspend (String, String, Boolean) -> Boolean,
    // Re-seals our recent unacked DMs to a peer whose ratchet session was just replaced (the recovery
    // half of an inbound reset) — MeshManager.resealRecentDmsTo, lambda-mediated like originate.
    private val resealUnacked: suspend (String) -> Unit = {},
    // Answers a member's CTL_GROUP_KEY_REQ by re-sealing our current group seeds to them —
    // MeshManager.redistributeGroupKey (lands with the hardening phase), lambda-mediated like originate.
    private val redistributeGroupKey: suspend (String, String) -> Unit = { _, _ -> },
    // Re-sends unacked group seeds to a member whose profile/prekey just (re)arrived — the group
    // analogue of flushPending (MeshManager.flushPendingGroupKeysFor), lambda-mediated like originate.
    // force = true bypasses the acked-epoch guard — reset-path only (the peer's pre-wipe ack is stale).
    private val flushGroupKeys: suspend (memberId: String, force: Boolean) -> Unit = { _, _ -> },
    // Replays our own custody's undelivered group frames for a (group, sender) whose seed just
    // arrived (MeshManager.replayCustodiedGroupFrames) — the local half of the re-serve heal; a frame
    // WE custodied before its seed is never re-served by a peer (no digest divergence to cue it).
    private val replayGroupCustody: suspend (String?, String?) -> Unit = { _, _ -> },
    // Adopts a gossiped shared group root (MeshManager.adoptGroupRoot, docs/SPOOL_PROTOCOL.md §3.2).
    // Called INSIDE the ctl commit so the root lands atomically with the DM chain advance that carried
    // it; returns whether anything was adopted. Lambda-mediated like redistributeGroupKey, so this
    // pipeline stays free of the spool plane's store.
    private val adoptGroupRoot: suspend (senderId: String, gk: GroupKeyPayload, now: Long) -> Boolean = { _, _, _ -> false },
    // Post-commit half of the root exchange (MeshManager.onGroupRootCtl): pass an adopted root onward,
    // or correct a sender whose gossip proves it is behind ours. Fired for every CTL_GROUP_KEY, not only
    // adoptions — a stale gossip is the ONLY evidence that an earlier one to that member was lost.
    private val onGroupRootCtl: suspend (senderId: String, gk: GroupKeyPayload, adopted: Boolean) -> Unit = { _, _, _ -> },
    // A cleartext profile just pinned (or refreshed) this sender's key/prekey — the intro driver's cue to
    // seal a pending contact-card intro (MeshManager → IntroSync.onProfilePinned), lambda-mediated like
    // the rest so the pipeline stays free of the driver. Fires for every plane the profile arrives on.
    private val onProfilePinned: suspend (senderId: String) -> Unit = {},
    // A v2 DM from this sender opened and committed; `carriesInit` says its ratchet header still carried the
    // X3DH init, i.e. the sender has not yet seen a frame of ours (IntroSync.onPeerFrameOpened). Runs
    // post-commit, outside the ratchet lock, since the answer it may trigger seals a frame of its own.
    private val onPeerFrameOpened: suspend (senderId: String, carriesInit: Boolean) -> Unit = { _, _ -> },
) {
    // nodeId -> avatar hash a non-direct peer advertised but whose bytes we're still pulling, so a blob
    // arriving via the multi-hop BlobExchange can be attributed back to the peer that advertised it.
    private val advertisedAvatars = ConcurrentHashMap<String, String>()

    // groupId -> the group photo (hash + its last-writer-wins clock) a group frame advertised but whose
    // bytes we're still pulling, so a blob arriving via the multi-hop BlobExchange can be adopted onto the
    // right group (and only if still current — the clock guards against a superseded photo, see
    // [adoptAdvertisedGroupPhoto]). The group analogue of [advertisedAvatars].
    private val advertisedGroupPhotos = ConcurrentHashMap<String, AdvertisedPhoto>()

    /**
     * A pulled blob just landed (the [BlobExchange] `onObtained` hook): attribute it to whoever advertised
     * it — a peer's avatar, a group's photo — screen its decrypted bytes if it's an E2E attachment we now
     * hold the key for, and describe it if it's a voice note. The four are order-independent and each is a
     * no-op when the hash isn't theirs. This is the wrapper [MeshManager] wires as `blobExchange`'s
     * onObtained callback.
     */
    suspend fun onObtained(hash: String) {
        adoptAdvertisedAvatar(hash)
        adoptAdvertisedGroupPhoto(hash)
        screenObtainedAttachment(hash)
        deriveObtainedVoiceMeta(hash)
    }

    /**
     * Delivers one first-seen frame. [kind] is the radio it arrived over (stamped by the composite transport;
     * [TransportKind.Other] for a replay with no radio, e.g. `MeshManager.replayCustodiedGroupFrames`), read
     * only to record the delivery plane — see [planeOf].
     */
    suspend fun onDeliver(
        wire: WireEnvelope,
        env: RelayEnvelope,
        fromNodeId: String,
        kind: TransportKind = TransportKind.Other,
    ) {
        // Strict authentication gate: a flooded frame that isn't signed by the key its senderId binds
        // to is dropped (not delivered locally). We still return normally so MeshRouter relays it
        // onward — other peers verify independently, and we don't become a propagation black hole.
        if (!verifyInbound(env, wire, fromNodeId, kind)) return
        // Carry every floodable frame we see — store-and-forward, so we can re-offer it to a neighbor
        // that joins later. That includes a DM addressed to US: with sealed receipts nobody vaccine-
        // purges (a carrier can't read them), so the delivered DM stays live in every carrier's digest
        // for its full TTL — if the recipient didn't hold it too, every digest exchange would re-cue a
        // re-serve of a frame we already delivered (the exists-gate would re-ack it, ~every SeenSet
        // lapse, forever). Holding our own copy is what makes digests converge; a cleartext ack still
        // purges it right back out (see acknowledge's self-vaccinate). Group messages are carried for
        // members who may be offline whether or not we're a member; broadcast + cleartext metadata
        // frames back-fill ambient state. Runs before handleChat returns early and before the router
        // schedules the relay, so the copy is durable pre-flood. Only flood frames (relay = true) are
        // custodied — a point-to-point frame (relay = false, e.g. a broadcast/group delivery tick) is
        // delivered to its addressee and stops.
        if (env.isStorable() && wire.relay) {
            forwardSync.onSeen(wire, env, ForwardStore.ORIGIN_RELAY)
        }
        // Multi-hop coordination-plane fan-out: re-fan a small flood frame to our own neighbors so it spreads
        // across the mesh at message-plane speed (no data path), not just one hop from the originator. onDeliver
        // runs once per first-seen frame (MeshRouter gates on its SeenSet), so each node re-fans it exactly once
        // and the echo dies out — no storm; fastFanout size-gates, so anything too big just no-ops. Only flood
        // frames re-fan; a point-to-point frame (relay = false, e.g. a broadcast receipt) reaches its addressee
        // and goes no further.
        if (wire.relay && shouldFastFanout(env)) transport.fastFanout(wire)
        // The long-range sibling: a sealed DM-form frame re-fans over a plane with no data path (LoRa) for the
        // same once-per-node reason; that plane's own sig-keyed dedup keeps a frame heard over it from bouncing
        // straight back onto it.
        if (wire.relay && shouldLongRangeFanout(env)) transport.longRangeFanout(wire)
        // Completes the no-throw contract: a per-type handler must NEVER throw out of onDeliver. The router
        // schedules the relay only *after* this returns, so an escape would silently stop this node forwarding
        // the frame (a propagation black hole). verifyInbound/decrypt are already runCatching-guarded
        // individually; this backstops every remaining handler — notably deliverChat's on-device moderation
        // classify(), the runtime sibling of the model-load gap fixed in e18b1f4 (a corrupt/failed classify
        // would otherwise crash both delivery and the shared block-on-send path). A swallowed error is logged
        // (it's a should-never-happen bug path); the custody capture above already ran, so the frame still
        // re-serves later.
        runCatching { dispatchByType(env, wire, fromNodeId, kind) }
            .onFailure { Log.w(TAG, "handler error on ${env.type} ${env.id} from ${env.senderId}: ${it.message}") }
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
        kind: TransportKind,
    ) {
        // The one thing the source's *plane* is read for: a delivery receipt records which plane it reached
        // us on, so the ✓✓ can say how the message got there (MessageEntity.receivedVia). It rides the
        // receipt paths only — carry, relay and convergence stay plane-blind, as ADR 019 requires.
        val plane = planeOf(fromNodeId, kind)
        when (env.type) {
            FrameType.CHAT -> {
                handleChat(env, plane, signed = wire.sig.isNotEmpty())
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
                handleReceipt(env, plane)
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
     * The plane an inbound frame arrived on — the single place that maps a delivery source onto a
     * [DeliveryPlane], and the only reader of [InboundFrame.kind] anywhere.
     *
     * A spool-tagged source names the Internet plane (it is never a neighbour, so the tag lives in the
     * source id); a frame off the LoRa board names [DeliveryPlane.LoRa] (ADR 040 — kilometre-range, slow,
     * photo-less: worth saying); every other radio collapses to [DeliveryPlane.Nearby] on purpose. The kind
     * is known now, so returning [DeliveryPlane.Bluetooth]/[DeliveryPlane.WifiAware] here is a one-line
     * change if the UI ever has something different to say about them. Keep this map in the pipeline —
     * `data/` must not learn `mesh/`'s [TransportKind].
     */
    private fun planeOf(
        fromNodeId: String,
        kind: TransportKind,
    ): DeliveryPlane =
        when {
            ScopeSync.isSpoolSource(fromNodeId) -> DeliveryPlane.Internet
            kind == TransportKind.LoRa -> DeliveryPlane.LoRa
            else -> DeliveryPlane.Nearby
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
     * message we don't hold makes [MessageRepository.markReceived] a harmless no-op. [plane] is the plane
     * this receipt arrived on, recorded with the tick it flips, alongside the acker [ackerFor] attributes
     * it to (null = tick only, no per-recipient row).
     */
    private suspend fun handleReceipt(
        env: RelayEnvelope,
        plane: DeliveryPlane,
    ) {
        val ackId = WireCodec.decodePayload<ReceiptContent>(env.payload)?.ackId ?: return
        val recipientOfAcked = messages.recipientOf(ackId)
        if (recipientOfAcked == null || recipientOfAcked == env.senderId) {
            receipts.record(ackId, ackerFor(env, ackId, recipientOfAcked), plane, clock())
        }
        forwardSync.onAck(ackId, env.senderId)
    }

    /**
     * The node a receipt for [ackId] is attributable to, or null when it must not be stored as a
     * per-recipient row. Shared by the cleartext and sealed apply paths.
     *
     * This deliberately does **not** decide whether the tick flips — that rule is unchanged and lives at
     * each call site. The tick is a wire semantic ("≥1 recipient received it") whose group form rests on
     * the forged-ack guard's null arm accepting *any* signed sender; storing the sender turns that same
     * null arm into a roster-spoofing surface, so the row — and only the row — takes a membership gate:
     *
     * - **DM** — the addressed recipient, which the caller's guard has already established.
     * - **Group** — an acker in the group's *effective* roster (a departed member's late tick still ticks,
     *   but names nobody the roster screen would show; a non-member's names nobody at all).
     * - **Broadcast room** — any signed peer: the room is public and has no roster to check against, so
     *   "received by" is an open list by construction.
     *
     * Null for a message we don't hold, so a receipt can never plant an orphan row (unlike a reaction, a
     * receipt for a message that hasn't arrived is meaningless — we only ever ack what we authored).
     */
    private suspend fun ackerFor(
        env: RelayEnvelope,
        ackId: String,
        recipientOfAcked: String?,
    ): String? {
        val conversationId = messages.conversationOf(ackId) ?: return null
        return when {
            recipientOfAcked != null -> {
                env.senderId.takeIf { it == recipientOfAcked }
            }

            conversationId == Conversations.NEARBY -> {
                env.senderId
            }

            else -> {
                val roster = groups.find(conversationId)?.let { GroupMembersStore.decode(it.members) }.orEmpty()
                env.senderId.takeIf { it in roster }
            }
        }
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
            // (a DB wipe) we can then never re-carry our own sends, so the content digest never
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
     * The one frame shape that may arrive unsigned (ADR 059): `relay = false` DM-form chat addressed to us
     * by someone else — the live-link delivery tick. Everything a flooded frame's signature protects is
     * either fixed by this shape (`type`, `group`, `relay`) or bound into the tick's AEAD (id, sender,
     * sentAt, recipient, the ratchet header), so the shape plus a successful open is the whole check.
     */
    private suspend fun isUnsignedTickShape(
        env: RelayEnvelope,
        wire: WireEnvelope,
    ): Boolean {
        val me = identity.nodeId()
        return env.type == FrameType.CHAT && !wire.relay && env.group == null && env.recipientId == me && env.senderId != me
    }

    /**
     * Authenticates a flooded frame: the frame [WireEnvelope.sig] must verify (byte-exact, over the
     * received [WireEnvelope.signed]) against a public-key bundle that derives back to the
     * [RelayEnvelope.senderId]. A profile carries that bundle in-band (first contact arrives before any
     * pin); every other type uses the sender's pinned key, so a frame from a peer whose profile we
     * haven't received yet is dropped. The point-to-point blob request is unsigned by design, and so is the v3 live-link delivery
     * tick (ADR 059) — for that one only its *shape* is judged here ([isUnsignedTickShape]); its ratchet AEAD
     * is the authenticator, enforced in [decryptAndDeliver]. An unknown
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
        kind: TransportKind,
    ): Boolean =
        runCatching {
            if (env.type == FrameType.BLOB_REQ) return true
            // A self frame — one of OUR own frames looping back, after a neighbor carried it and (once our SeenSet
            // window lapsed) re-served it — is authenticated like any other via [verifierBundle], which resolves
            // our identity's own bundle for it. It USED to be dropped here as a silent no-op ("we already have
            // it"), but that assumption breaks after a DB wipe empties custody: peers still hold
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
                    if (FrameType.isReplayable(env.type)) pendingInbound.hold(wire, env, fromNodeId, kind)
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
            if (wire.sig.isEmpty()) {
                // The unsigned door: exactly one shape — a point-to-point v3 sealed tick addressed to us — and
                // only its shape is judged here. The pinned bundle above is still required: the session it
                // names is what will open the frame, and nothing downstream may act on it before that opens.
                if (isUnsignedTickShape(env, wire)) return true
                metrics.onDropped(DropReason.UNSIGNED_REFUSED)
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
     * An emoji the wire refuses ([isValidReactionEmoji]) applies nothing and counts
     * [DropReason.REACTION_REFUSED]; never truncated (that splits a sequence into tofu) and never read as
     * a retraction (garbage would erase a valid reaction). Custody and relay already happened upstream.
     */
    private suspend fun handleReaction(env: RelayEnvelope) {
        // Blocked sender: drop the reaction (the router still relays it onward to other peers).
        if (env.senderId in settings.blockedNodeIds.first()) return
        val content = WireCodec.decodePayload<ReactionContent>(env.payload) ?: return
        if (content.emoji != null && !isValidReactionEmoji(content.emoji)) {
            metrics.onDropped(DropReason.REACTION_REFUSED)
            return
        }
        reactions.apply(
            ReactionEntity(
                messageId = content.messageId,
                reactorNodeId = env.senderId,
                emoji = content.emoji,
                updatedAt = env.sentAt,
            ),
        )
    }

    // [plane] is carried purely for the sealed delivery receipt a v2 ctl DM may turn out to be
    // ([applySealedReceipt]) — nothing on the ordinary message path reads it.
    private suspend fun handleChat(
        env: RelayEnvelope,
        plane: DeliveryPlane,
        // False for the one frame shape verifyInbound admits without a signature (ADR 059); the decrypt
        // path is what authenticates it, so it must know.
        signed: Boolean = true,
    ) {
        val me = identity.nodeId()
        // Blocked sender: never persist, notify, or reconcile their group/roster state — we surface nothing
        // from them. But a broadcast- or group-room message still gets its best-effort delivery tick
        // ([ackBlockedRoomChat]): blocking is a purely local presentation choice and must stay invisible to
        // the blocked party. Concretely, that broadcast/group tick is a fragile unicast `relay = false`
        // receipt (unlike a DM's flooded, custodied one), so when we're the sender's *only* reachable acker,
        // dropping it strands their Nearby/group message with no ✓✓ forever — the reported bug. A DM is
        // deliberately left un-acked: its receipt floods and is custodied (real delay-tolerance, no single-hop
        // trap), and acking one would also vaccine-purge it from mesh-wide custody — a heavier, separate
        // change. The router still relays the frame onward regardless, so a blocked user stays a working peer.
        if (env.senderId in settings.blockedNodeIds.first()) {
            ackBlockedRoomChat(env)
            return
        }
        val content = WireCodec.decodePayload<ChatContent>(env.payload) ?: return
        // Group messages take the membership-gated path; they carry recipientId null, so they must be
        // handled before the DM check below (which would otherwise treat them as broadcast).
        val group = env.group
        if (group != null) {
            if (reconcileGroup(group, env.senderId, env.sentAt, me)) decryptAndDeliver(env, content, me, group.id, plane, signed)
            return
        }
        // A DM addressed to someone else: we're only relaying it (the router floods it onward). It
        // isn't ours, so don't persist, notify, or ack it.
        if (!Conversations.isForMe(env.recipientId, me)) return
        decryptAndDeliver(env, content, me, Conversations.idFor(env.senderId, env.recipientId, me), plane, signed)
    }

    /**
     * One post the bound board heard on its primary (slot 0) channel — [MeshPostSink.onPublicPostHeard] —
     * delivered into [Conversations.MESHTASTIC] here and nowhere else. There is no frame: nothing about a
     * heard post is signed, originated, custodied or fanned out, so the [RelayEnvelope] built below is a local
     * carrier for [deliverChat]'s four envelope reads (id, sender, recipient, sentAt) and must never reach
     * [originate]. Its id is derived from the packet ([FrameId.forMeshPost]), so the board replaying a queued
     * packet on reconnect lands on the row it already wrote and notifies nobody twice.
     *
     * Three things this deliberately does, each for a reason that is easy to lose:
     *
     * - **No receipt.** [deliverChat] runs with `ack = false`. There is nobody to tick: the speaker has no
     *   Knit identity, and the row's `senderId` is this phone by convention — a heard post is "ours" in the
     *   sender column and somebody else's in the origin, and `originNode` is what tells the two apart
     *   everywhere a row is read.
     * - **A contact, resolved once.** The speaker's node number is looked up against the profiles that claim
     *   it ([PeerRepository.findByLoraNode], newest wins) at ingest, and the answer is frozen on the row as
     *   [MeshPostOrigin.peerId]. Boards change hands; resolving at render time would re-attribute history to
     *   whoever holds the board now. Still an attribution, not an identity: a node number is self-asserted
     *   and unsigned, so the UI keeps the unverified styling. No peer row is ever created here — nothing
     *   touches `peers`, presence, `reachable`, open-to-chat or the contacts picker.
     * - **The blocklist is read on the resolved contact only.** Blocking is keyed on Knit node ids; a
     *   Meshtastic node number is not one and is trivially spoofable, so a per-speaker block would be a promise
     *   the radio cannot keep — but a post from a blocked contact's own board is dropped like their chat is.
     */
    internal suspend fun deliverMeshPost(post: MeshPost) {
        val me = identity.nodeId()
        val contact = peers.findByLoraNode(post.node)
        if (contact != null) {
            // A DataStore read, hoisted ahead of the write like every other blocklist check here.
            if (contact.nodeId in settings.blockedNodeIds.first()) {
                metrics.onMeshPostRefused(MESH_POST_BLOCKED_CONTACT)
                return
            }
            metrics.onMeshPostMatched()
        }
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = FrameId.forMeshPost(post.node, post.packetId),
                senderId = me,
                sentAt = clock(),
                payload = ByteArray(0),
            )
        deliverChat(
            env = env,
            // The room's own content shape is a plain body: a post here carries no attachment, no mention,
            // no reply and no encryption, so the chat shell it is delivered through is exactly that much.
            content = ChatContent(body = post.body),
            me = me,
            conversationId = Conversations.MESHTASTIC,
            plane = DeliveryPlane.LoRa,
            origin =
                MeshPostOrigin(
                    node = post.node,
                    name = post.name,
                    channel = post.channel,
                    hops = post.hops,
                    snrDeci = post.snrDeci,
                    viaMqtt = post.viaMqtt,
                    peerId = contact?.nodeId,
                ),
            ack = false,
        )
    }

    /**
     * The one thing [handleChat] still does for a *blocked* sender: send the best-effort broadcast/group
     * delivery tick, so their ✓✓ isn't stranded when we're their only reachable acker (see the block-branch
     * comment for why that tick is fragile and why blocking must stay invisible). A group message ticks only
     * when we're actually a member locally (not merely relaying it, and not a group we've left) — the same
     * recipient gate [reconcileGroup] applies on the delivery path, minus the state mutation. A DM is never
     * acked here. [AckSync.owe] sends now and retries until it lands or ages out, and no-ops for our own
     * frame looping back — so this is convergence-neutral (a new receipt origination, never a custody/relay
     * decision) and matches the "block is read only on the local delivery path" rule.
     */
    private suspend fun ackBlockedRoomChat(env: RelayEnvelope) {
        val group = env.group
        if (group != null) {
            // Group message: tick only when we're actually a member locally (not merely relaying it, and not
            // a group we've left) — the recipient gate reconcileGroup applies on the deliver path, minus the mutation.
            val local = groups.find(group.id)
            if (local != null && !local.left) ackSync.owe(env.id, env.senderId, escalatable = true)
        } else if (env.recipientId == null) {
            // Broadcast room: no addressee, so everyone is a recipient — always tick.
            ackSync.owe(env.id, env.senderId)
        }
        // A DM (recipientId set) is deliberately not acked for a blocked sender — see handleChat's block comment.
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
        plane: DeliveryPlane,
        signed: Boolean = true,
    ) {
        val enc = content.enc
        // The unsigned door (ADR 059) admits one envelope shape — v3, DM form — and it is judged before any
        // other branch can act on the frame: its AEAD is the authenticator, so nothing may run ahead of a
        // successful open. Not the plaintext branch, and not the exists-gate below, which would otherwise
        // let anyone who overheard a DM id on the air make us seal and flood a receipt for it.
        if (!signed && !admitsUnsigned(enc)) {
            metrics.onDropped(DropReason.UNSIGNED_REFUSED)
            return
        }
        if (enc == null) {
            deliverChat(env, content, me, conversationId, plane)
            return
        }
        // The already-have-it gate: custody re-serves the same ciphertext routinely (the 60s re-offer
        // loop; the SeenSet is in-memory, 10 min, per mesh session), and decrypt used to run on every
        // copy. Skipping it once the row exists is what lets the v2 ratchet actually DELETE used
        // message keys (forward secrecy), and spares v1 an HPKE unwrap per re-serve. The ack still
        // runs unconditionally, in whatever form acknowledge picks — rare now that we custody our own
        // inbound DMs (digests match, so a re-serve means genuine divergence, e.g. our copy was
        // quota-evicted), and re-custody above already restored the row for the next exchange.
        if (signed && messages.exists(env.id)) {
            acknowledge(env, me, plane)
            return
        }
        when {
            // v2's two forms share the version and split on addressing (see EncEnvelope's kdoc):
            // group-addressed carries the sender-key header `g`, a DM the epoch-ratchet header `r`. The
            // group form is v2 only; v3 (ADR 059) is the DM form's compact sibling and takes the same arm.
            enc.v == EncEnvelope.VERSION_RATCHET && env.group != null -> {
                runCatching { decryptAndDeliverGroup(env, content, enc, me, conversationId, plane) }.getOrElse {
                    Log.w(TAG, "drop group ratchet chat ${env.id}: ${it.message}")
                    metrics.onDropped(DropReason.DECRYPT_FAILED)
                }
            }

            EncEnvelope.isDmRatchetVersion(enc.v) -> {
                runCatching { decryptAndDeliverV2(env, content, enc, me, conversationId, plane, signed) }.getOrElse {
                    Log.w(TAG, "drop v2 chat ${env.id}: ${it.message}")
                    metrics.onDropped(DropReason.DECRYPT_FAILED)
                }
            }

            else -> {
                val plain =
                    runCatching { decrypt(env, enc, me) }.getOrElse {
                        Log.w(TAG, "drop encrypted chat ${env.id}: ${it.message}")
                        null
                    } ?: return
                deliverChat(env, plaintextContent(content, plain), me, conversationId, plane, plain)
            }
        }
    }

    /** The decrypted [MessageContent] substituted into the cleartext [ChatContent] shell for delivery. */
    private fun plaintextContent(
        content: ChatContent,
        plain: MessageContent,
    ): ChatContent =
        content.copy(
            body = plain.body,
            mentions = plain.mentions,
            attachmentHash = plain.attachmentHash,
            attachmentMime = plain.attachmentMime,
            enc = null,
            replyTo = plain.replyTo,
        )

    /**
     * The v2 (epoch-ratchet) decrypt-and-deliver. Two-phase against [RatchetSessions]' concurrency
     * contract: a lock-free peek yields the plaintext for moderation/row-building, then the persist
     * hook handed to [deliverChat] re-opens on fresh state and commits the ratchet delta atomically
     * with the message row (`db.withWriteTransaction` outer, session lock inner). Typed engine failures map
     * to drop reasons — all delivery-local; the frame already custodied and will still relay.
     */
    @Suppress("ReturnCount") // a drop-reason gate ladder; early returns ARE the readable form (cf. decrypt)
    private suspend fun decryptAndDeliverV2(
        env: RelayEnvelope,
        content: ChatContent,
        enc: EncEnvelope,
        me: String,
        conversationId: String,
        plane: DeliveryPlane,
        signed: Boolean = true,
    ) {
        val wireHeader = enc.r
        // v2/v3 are DM-only; a group-addressed or header-less envelope is malformed by construction.
        if (wireHeader == null || env.group != null) {
            metrics.onDropped(DropReason.RATCHET_BAD_HEADER)
            return
        }
        val v3 = enc.v == EncEnvelope.VERSION_DM_V3
        if (!nonceShapeValid(enc)) {
            metrics.onDropped(DropReason.RATCHET_BAD_HEADER)
            return
        }
        val nonce = enc.nonce.takeIf { !v3 }
        val peerIkPub = pinnedDhKey(env.senderId)
        if (peerIkPub == null) {
            // Unreachable in practice: verifyInbound already required the pinned bundle.
            metrics.onDropped(DropReason.NO_SENDER_KEY)
            return
        }
        val thread = env.recipientId.orEmpty()
        val aad = MessageCrypto.header(env.id, env.senderId, env.sentAt, thread)
        val now = System.currentTimeMillis()
        val peek = ratchet.peekOpen(me, env.senderId, peerIkPub, wireHeader, nonce, enc.ct, aad, now)
        if (peek !is RatchetEngine.OpenOutcome.Opened) {
            onRatchetFailure(env, peek, me, now, authenticated = signed)
            return
        }
        val plain = decodePlaintext(enc, peek.plaintext)
        if (plain == null) {
            metrics.onDropped(DropReason.DECRYPT_FAILED)
            return
        }
        if (!plain.isSupported()) {
            metrics.onDropped(DropReason.UNKNOWN_CONTENT_VERSION)
            Log.w(TAG, "drop chat ${env.id}: unsupported content v=${plain.v}")
            return
        }
        if (unsignedButNotATick(signed, plain)) return
        val commit: suspend (suspend () -> Unit) -> Boolean = { onOpened ->
            val committed =
                db.withWriteTransaction {
                    ratchet.commitOpen(me, env.senderId, peerIkPub, wireHeader, nonce, enc.ct, aad, now, onOpened)
                }
            // After the transaction and outside the session lock: the hook may seal an answer of its own.
            if (committed) onPeerFrameOpened(env.senderId, wireHeader.init != null)
            committed
        }
        if (plain.ctl != null) {
            handleCtlDm(env, plain, me, now, plane, commit)
            return
        }
        deliverChat(
            env,
            plaintextContent(content, plain),
            me,
            conversationId,
            plane,
            plain,
            persist = persistWithInlineAcks(env, plain, plane, commit),
        )
    }

    /**
     * The v2 persist hook: the message row and the receipts the reply carries inline (ADR 054's piggyback)
     * land in one commit — the same per-id guard as a sealed tick, txn outer and session lock inner.
     */
    private fun persistWithInlineAcks(
        env: RelayEnvelope,
        plain: MessageContent,
        plane: DeliveryPlane,
        commit: suspend (suspend () -> Unit) -> Boolean,
    ): suspend (MessageEntity) -> Unit =
        { row ->
            commit {
                messages.save(row)
                applyInlineAcks(env, plain, plane)
            }
        }

    /**
     * Applies the acks a **plain** sealed DM carries inline (`MessageContent.acks` outside a ctl frame — the
     * `CAP_INLINE_ACK` form, ADR 054), bounded and guarded exactly like a `CTL_RECEIPT` batch. Runs once per
     * frame: a re-delivery never reaches the decrypt (the exists-gate), so nothing re-applies.
     */
    private suspend fun applyInlineAcks(
        env: RelayEnvelope,
        plain: MessageContent,
        plane: DeliveryPlane,
    ) {
        plain.acks
            .orEmpty()
            .distinct()
            .take(MAX_RECEIPT_ACKS)
            .forEach { applySealedReceipt(env, it, plane) }
    }

    /**
     * Dispatches a decrypted v2 control DM. A control frame is machinery, not conversation:
     * advance/commit the session state but never persist, notify, or ack it as a message. Group-key
     * payloads commit their group state inside the SAME transaction/lock as the DM advance
     * (adoptSeeds/onKeyAck are deliberately lock-free — the shared ratchet mutex is already held
     * there); sealed receipts/reactions (CTL_RECEIPT/CTL_REACTION) commit their row updates there
     * too, so a crash never splits the chain advance from the tick/reaction it carried. Post-commit
     * actions (reset recovery, the adoption ack, a key-request answer) originate new frames and run
     * strictly after, outside the transaction.
     */
    private suspend fun handleCtlDm(
        env: RelayEnvelope,
        plain: MessageContent,
        me: String,
        now: Long,
        plane: DeliveryPlane,
        commit: suspend (suspend () -> Unit) -> Boolean,
    ) {
        var outcome = CtlOutcome()
        val committed = commit { outcome = applyCtlInTxn(env, plain, now, plane) }
        if (!committed) return
        when (plain.ctl) {
            MessageContent.CTL_SESSION_RESET -> {
                // The recovery half: re-seal our recent unacked DMs under the fresh session, and re-send
                // our group epoch seeds — ctl frames are never persisted, so the DM re-seal alone would
                // leave the wiped peer without our seeds forever (the only wipe-side seed plane).
                resealUnacked(env.senderId)
                // Forced: their outbox row may say they acked our current epoch, but the reset means
                // that ack predates the wipe — without the bypass the flush silently no-ops and the
                // peer black-holes group frames until our next natural mint (hours to days).
                flushGroupKeys(env.senderId, true)
            }

            MessageContent.CTL_GROUP_KEY -> {
                outcome.adoptedEpoch?.let {
                    ackGroupSeed(env.senderId, plain.gk?.groupId, it, me, now)
                    // The seed (or its idempotent re-distribution) may unlock frames we already carry.
                    plain.gk?.let { gk -> replayGroupCustody(gk.groupId, env.senderId) }
                }
                // Independent of the seeds: a root-only distribution carries none, and a distribution can
                // carry a stale epoch alongside a newer root (or the reverse). Fired whether or not
                // anything was adopted — the not-adopted case is where we correct a lagging sender.
                plain.gk?.let { onGroupRootCtl(env.senderId, it, outcome.adoptedRoot) }
            }

            MessageContent.CTL_GROUP_KEY_REQ -> {
                plain.gk?.let { redistributeGroupKey(it.groupId, env.senderId) }
            }

            MessageContent.CTL_PROFILE -> {
                // Post-commit: originating a pull is outbound work, and the peer row must already be
                // written so the bytes have somewhere to be adopted into when they land.
                outcome.avatarToPull?.let { pullRelayAvatarIfNeeded(env.senderId, it, haveAvatar = false) }
            }

            else -> {}
        }
    }

    /** What a ctl DM's in-transaction half changed, for the post-commit actions that answer it. */
    private class CtlOutcome(
        /** The highest seed epoch worth acknowledging (`CTL_GROUP_KEY` only), else null. */
        val adoptedEpoch: Int? = null,
        /** Whether a strictly-newer shared group root landed — gossip it onward, wake the spool plane. */
        val adoptedRoot: Boolean = false,
        /** An avatar a sealed profile advertised whose bytes we still lack (`CTL_PROFILE` only). */
        val avatarToPull: String? = null,
    )

    /**
     * The in-transaction half of a ctl DM — runs inside the ratchet commit (shared lock held, outer
     * Room transaction open) so state the ctl carries lands atomically with the session advance. An
     * unknown code does nothing here — the commit still advances the chain, which is the additive-ctl
     * contract.
     */
    private suspend fun applyCtlInTxn(
        env: RelayEnvelope,
        plain: MessageContent,
        now: Long,
        plane: DeliveryPlane,
    ): CtlOutcome {
        when (plain.ctl) {
            MessageContent.CTL_GROUP_KEY -> {
                // Seeds and root are adopted independently: `gk.keys` is empty on a root-only gossip, and
                // adoptGroupSeeds short-circuits on that, so nesting the root inside it would drop exactly
                // the distributions a member sends before it has ever sealed a group frame.
                val epoch = adoptGroupSeeds(env, plain, now)
                val root = plain.gk?.let { adoptGroupRoot(env.senderId, it, now) } == true
                return CtlOutcome(adoptedEpoch = epoch, adoptedRoot = root)
            }

            MessageContent.CTL_GROUP_KEY_ACK -> {
                plain.gk?.let { gk ->
                    gk.ackEpoch?.let { groupRatchet.onKeyAck(gk.groupId, env.senderId, it, now) }
                }
            }

            MessageContent.CTL_RECEIPT -> {
                // Single-ack tick and/or the custody-escalated batch (`acks`): the forged-ack guard runs
                // per id inside applySealedReceipt. Bounded against a hostile frame — 2× the send-side
                // batch cap (AckSync.MAX_BATCH_ACKS), and dedup'd so a malformed sender can't loop us.
                (listOfNotNull(plain.ack) + plain.acks.orEmpty())
                    .distinct()
                    .take(MAX_RECEIPT_ACKS)
                    .forEach { applySealedReceipt(env, it, plane) }
            }

            MessageContent.CTL_REACTION -> {
                applySealedReaction(env, plain.rp)
            }

            MessageContent.CTL_PROFILE -> {
                return CtlOutcome(avatarToPull = applySealedProfile(env, plain.pr))
            }

            else -> {}
        }
        return CtlOutcome()
    }

    /**
     * The presentation half's follow-ups to a stored profile: the status notices it earns, the orphan
     * avatar blob it may free, and the fetch of an avatar it advertised but whose bytes we lack.
     *
     * All three are gated on [stalePresentation] together, and that is the point of grouping them. A
     * profile frame is admitted for two independent reasons on two independent watermarks — its
     * presentation and its ratchet prekey — so a frame that lost the presentation race still reaches here
     * for its prekey, carrying a name and an avatar hash *older* than the ones already on screen. Acting
     * on those would announce a change that never happened, reclaim a live avatar, or re-fetch a
     * superseded one. Only the prekey columns may move on such a frame.
     *
     * Grouping these moved the avatar pull ahead of [applyDeviceTagBlockContinuity], which it used to
     * follow. That is immaterial rather than merely untested: [pullRelayAvatarIfNeeded] never consults
     * the blocked set, so a peer blocked by device-tag continuity had its avatar pulled under the old
     * order too. If that ever becomes undesirable, the fix is a block check inside the pull, not a
     * re-ordering here — an ordering that only accidentally suppressed it was never the guard.
     */
    private suspend fun applyPresentationFollowUps(
        peerId: String,
        previous: PeerEntity?,
        newName: String,
        advertisedAvatar: String?,
        haveAvatar: Boolean,
        version: Long,
        stalePresentation: Boolean,
    ) {
        if (stalePresentation) return
        peerPresentationNotices(peerId, previous, newName, advertisedAvatar, version)
        reclaimRemovedAvatarIfCleared(peerId, advertisedAvatar, previous?.avatarHash)
        pullRelayAvatarIfNeeded(peerId, advertisedAvatar, haveAvatar)
    }

    /**
     * Writes the status notices a profile update earns: a rename (carrying both the previous name and the
     * new one, so the line stays a record of that step after a later rename) and an avatar change. Shared by both
     * profile writers — the cleartext frame and the sealed `CTL_PROFILE` — because two writers with two
     * conventions is exactly how these would start disagreeing about what counts as a change.
     *
     * [previous] is the row as it stood **before** the upsert, or null on first contact. Neither a first
     * sighting nor a first name (an empty stored one) is a rename, and both get no line: there was no old
     * name to have changed from, and pinning a stranger's key must not conjure a thread out of nothing.
     *
     * The avatar comparison is against the *advertised* hash rather than the adopted one, and both
     * notices key their row id on [version], so the repeated "advertised != stored" that persists while
     * an avatar's blob is still in flight collapses to a single line per profile version. The
     * blob-arrival writers ([adoptAdvertisedAvatar], [onAvatarReceived]) deliberately post nothing —
     * by then this has already said it.
     */
    private suspend fun peerPresentationNotices(
        peerId: String,
        previous: PeerEntity?,
        newName: String,
        advertisedAvatar: String?,
        version: Long,
    ) {
        if (previous == null) return
        if (previous.name.isNotEmpty() && previous.name != newName) {
            savePeerNotice(peerId, StatusNotices.peerRenamed(peerId, previous.name, newName, version))
        }
        // A cleared avatar is a change too, but there is no "removed their photo" line to draw and the
        // reclaim path already handles the bytes, so only an actual new avatar is announced.
        if (advertisedAvatar != null && advertisedAvatar != previous.avatarHash) {
            savePeerNotice(peerId, StatusNotices.peerAvatarChanged(peerId, version))
        }
    }

    /**
     * Writes a peer status notice into the DM thread with [peerId] — but only if that thread already
     * holds an ordinary message.
     *
     * The gate is the whole reason this is a helper rather than an inline save. A `profile` frame is
     * flooded to the entire mesh and re-published every 12 h, so every device eventually holds a row for
     * every peer it has ever heard; without the gate a stranger's rename would conjure a thread into the
     * chat list and the list would slowly become a directory feed. Requiring an ordinary message means
     * the notice appears exactly where that peer's name is already on screen. Status rows deliberately
     * don't satisfy the gate, or one notice would license the next.
     *
     * Idempotent by construction: every [StatusNotices] row has a deterministic id, so a re-served or
     * republished profile upserts the same row rather than stacking a second identical line.
     */
    private suspend fun savePeerNotice(
        peerId: String,
        notice: MessageEntity,
    ) {
        if (!messages.hasMessagesIn(peerId)) return
        messages.save(notice)
    }

    /**
     * Applies a sealed `CTL_PROFILE` — the presentation half of a profile update from a contact whose
     * key is already pinned, since a v2 session is the precondition for this frame existing at all.
     * Returns the avatar hash still needing a fetch, for the post-commit pull.
     *
     * Deliberately far narrower than [handleProfile]: it never touches the pinned key, the prekey, the
     * device tag, or the advertised capabilities. Identity moves only on the authenticated cleartext
     * frame, which is self-certifying (the nodeId IS the key bundle's hash) in a way a sealed payload
     * cannot be — the session it arrives under proves *who sent it*, not what their key is.
     *
     * Two rules are shared verbatim with the cleartext path rather than re-derived, because having two
     * profile writers with two conventions is exactly how a name silently reverts: last-writer-wins on
     * the sender's profile **version** (the same number a cleartext profile frame carries as its
     * `sentAt`, which is why the two paths order against each other and a custody re-serve of a stale
     * ctl cannot undo a newer update), and [resolveAvatarHash]'s rule that a stored avatar hash means
     * "the bytes are present locally", so a hash is never adopted before its blob lands.
     */
    private suspend fun applySealedProfile(
        env: RelayEnvelope,
        pr: ProfilePayload?,
    ): String? {
        val payload = pr ?: return null
        // No pinned peer row means we have never seen their cleartext profile, which cannot happen for a
        // frame we just decrypted under their session — but a missing row is a no-op, never an insert:
        // this path must not be able to mint a peer that skipped the key pin.
        val existing = peers.find(env.senderId) ?: return null
        // A payload predating this build's version field reads 0 and is ignored rather than treated as
        // ancient-but-valid — an unversioned update cannot be ordered, so it must not be applied.
        if (payload.version <= 0L || payload.version < existing.updatedAt) return null
        val advertised = payload.avatarHash
        val haveAvatar = advertised != null && blobStore.has(advertised)
        val name = payload.name.take(TextLimits.DISPLAY_NAME)
        peers.upsert(
            existing.copy(
                // Clamp inbound, as the cleartext path does: our own cap bounds only what we originate.
                name = name,
                status = payload.status.take(TextLimits.STATUS),
                avatarHash = resolveAvatarHash(advertised, haveAvatar, existing.avatarHash),
                updatedAt = payload.version,
                // The whole presentation set moves together (see ProfilePayload): a field this path did not
                // copy would be reverted by every sealed update after the cleartext frame that set it.
                openToChat = payload.openToChat,
                loraNode = payload.loraNode,
            ),
        )
        // Status notices for what actually moved. Compared against the pre-write row, and written here
        // rather than in a writer of their own because this is the point where both the old and the new
        // presentation exist at once — a moment nothing else in the profile paths preserves. Free of the
        // wire: the change is a pure function of two values both ends already hold.
        peerPresentationNotices(env.senderId, existing, name, advertised, payload.version)
        reclaimRemovedAvatarIfCleared(env.senderId, advertised, existing.avatarHash)
        return advertised?.takeIf { !haveAvatar }
    }

    /**
     * Applies a sealed `CTL_RECEIPT`: flip the tick with the cleartext path's forged-ack guard (null
     * recipient = a group/broadcast message keeps the best-effort tick — that IS the sealed group
     * tick's shape) and store the acker [ackerFor] attributes it to, which is where a group's
     * per-recipient list comes from. Deliberately NO [ForwardSync.onAck]: a carrier can't read a sealed
     * receipt, so nobody vaccine-purges — the delivered message ages out of custody on the frame-global
     * TTL uniformly on every node (docs/ENCRYPTED_RECEIPTS_REACTIONS.md). Runs inside the ctl commit.
     *
     * [plane] is the plane the sealed receipt arrived on, stored with the tick: [DeliveryPlane.Internet]
     * means the peer answered us across a spool rather than from radio range. This is the DM path, so it is
     * the one that usually paints the globe — a DM's receipt is sealed, and only the cleartext
     * broadcast/group tick takes [handleReceipt].
     */
    private suspend fun applySealedReceipt(
        env: RelayEnvelope,
        ackId: String?,
        plane: DeliveryPlane,
    ) {
        ackId ?: return
        val recipientOfAcked = messages.recipientOf(ackId)
        if (recipientOfAcked == null || recipientOfAcked == env.senderId) {
            receipts.record(ackId, ackerFor(env, ackId, recipientOfAcked), plane, clock())
        }
    }

    /**
     * Applies a sealed `CTL_REACTION` (DM or group form): same LWW convergence as the cleartext frame
     * (reactor = authenticated sender, clock = the frame's signed sentAt), committed atomically with
     * the ratchet/chain advance. Orphan-permissive like the cleartext path — the target may not have
     * arrived yet; the 24 h orphan reaper bounds junk.
     */
    private suspend fun applySealedReaction(
        env: RelayEnvelope,
        rp: ReactionPayload?,
    ) {
        rp ?: return
        // Same refusal as the cleartext path; the chain/ratchet advance that carried it is unaffected.
        if (rp.emoji != null && !isValidReactionEmoji(rp.emoji)) {
            metrics.onDropped(DropReason.REACTION_REFUSED)
            return
        }
        reactions.apply(ReactionEntity(rp.messageId, env.senderId, rp.emoji, env.sentAt))
    }

    /**
     * Adopts a `CTL_GROUP_KEY` distribution's seeds, gated on actually holding the group, not having
     * left it, and the sender being in the effective roster (a departed member's stale distribution or
     * a non-member's noise adopts nothing). Runs inside the v2 ctl commit — same transaction, shared
     * lock already held. Returns the highest epoch worth acknowledging, or null.
     */
    private suspend fun adoptGroupSeeds(
        env: RelayEnvelope,
        plain: MessageContent,
        now: Long,
    ): Int? {
        val gk = plain.gk ?: return null
        if (gk.keys.isEmpty()) return null
        val group = groups.find(gk.groupId) ?: return null
        if (group.left || env.senderId !in GroupMembersStore.decode(group.members)) return null
        val result = groupRatchet.adoptSeeds(gk.groupId, env.senderId, gk.keys, now)
        repeat(result.freshChains) { metrics.onGroupSeedAdopted() }
        return result.ackEpoch
    }

    /**
     * Acknowledges an adopted seed distribution back to its sender (`CTL_GROUP_KEY_ACK` as an ordinary
     * v2 ctl DM) so their outbox stops re-sending. Post-commit and best-effort: a lost ack just means a
     * redundant, idempotent re-distribution later. The session that carried the distribution exists by
     * construction, so `sealDm` needs no prekey here.
     */
    private suspend fun ackGroupSeed(
        senderId: String,
        groupId: String?,
        epoch: Int,
        me: String,
        now: Long,
    ) {
        groupId ?: return
        val bundle =
            peers
                .find(senderId)
                ?.pubKey
                ?.let { PublicKeyBundle.decode(it) } ?: return
        val id = FrameId.new()
        val aad = MessageCrypto.header(id, me, now, senderId)
        val plaintext =
            MessageContent(
                body = "",
                ctl = MessageContent.CTL_GROUP_KEY_ACK,
                gk = GroupKeyPayload(groupId = groupId, ackEpoch = epoch),
            ).encode()
        val sealed = ratchet.sealDm(senderId, bundle.dhPublicKey(), peerSpk = null, plaintext = plaintext, aad = aad, now = now) ?: return
        originate(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = me,
                sentAt = now,
                recipientId = senderId,
                payload = WireCodec.encodePayload(ChatContent(enc = sealed)),
            ),
        )
    }

    /**
     * The group-form (sender-key) decrypt-and-deliver — [decryptAndDeliverV2]'s shape re-applied: a
     * lock-free peek yields the plaintext for moderation/row-building, then the persist hook re-opens
     * on fresh state and commits the chain delta atomically with the message row (`db.withWriteTransaction`
     * outer, the shared ratchet lock inner). Typed engine failures map to drop reasons — all
     * delivery-local; the frame already custodied and will still relay. [reconcileGroup] already
     * vetted the roster and membership before this runs.
     */
    @Suppress("ReturnCount") // a drop-reason gate ladder; early returns ARE the readable form (cf. decrypt)
    private suspend fun decryptAndDeliverGroup(
        env: RelayEnvelope,
        content: ChatContent,
        enc: EncEnvelope,
        me: String,
        conversationId: String,
        plane: DeliveryPlane,
    ) {
        val wireHeader = enc.g
        val groupId = env.group?.id
        // The group form is group-only and header-required; anything else is malformed by construction.
        if (wireHeader == null || groupId == null) {
            metrics.onDropped(DropReason.GROUP_RATCHET_BAD_HEADER)
            return
        }
        val aad = MessageCrypto.header(env.id, env.senderId, env.sentAt, groupId)
        val now = System.currentTimeMillis()
        val peek = groupRatchet.peekOpen(groupId, env.senderId, wireHeader, enc.nonce, enc.ct, aad, now)
        if (peek !is GroupRatchetEngine.OpenOutcome.Opened) {
            onGroupRatchetFailure(env, peek, groupId, me, now)
            return
        }
        val plain = MessageContent.decode(peek.plaintext)
        if (plain == null) {
            metrics.onDropped(DropReason.DECRYPT_FAILED)
            return
        }
        if (!plain.isSupported()) {
            metrics.onDropped(DropReason.UNKNOWN_CONTENT_VERSION)
            Log.w(TAG, "drop chat ${env.id}: unsupported content v=${plain.v}")
            return
        }
        val commit: suspend (suspend () -> Unit) -> Boolean = { onOpened ->
            db.withWriteTransaction {
                groupRatchet.commitOpen(groupId, env.senderId, wireHeader, enc.nonce, enc.ct, aad, now, onOpened)
            }
        }
        if (plain.ctl != null) {
            // A ctl marker keeps its contract regardless of the envelope it arrived in: advance the
            // chain, persist nothing as a message, notify nothing, ack nothing. CTL_REACTION is the one
            // ctl that legitimately rides the group form (a sealed group reaction, applied atomically
            // with the chain delta); everything else here — group-key machinery on pairwise DMs, an
            // unknown future value — commits the advance and does nothing.
            commit {
                if (plain.ctl == MessageContent.CTL_REACTION) applySealedReaction(env, plain.rp)
            }
            return
        }
        deliverChat(
            env,
            plaintextContent(content, plain),
            me,
            conversationId,
            plane,
            plain,
            persist = { row -> commit { messages.save(row) } },
        )
    }

    /**
     * Maps a typed group-form open failure to its drop reason, and — for the two shapes that mean "the seed
     * this frame needs isn't here" (never arrived / lost, or a stale-era chain after the sender wiped)
     * — feeds the key-request heuristic, sending a rate-limited `CTL_GROUP_KEY_REQ` when it fires.
     */
    private suspend fun onGroupRatchetFailure(
        env: RelayEnvelope,
        outcome: GroupRatchetEngine.OpenOutcome,
        groupId: String,
        me: String,
        now: Long,
    ) {
        val reason =
            when (outcome) {
                GroupRatchetEngine.OpenOutcome.Failed.NO_KEY -> DropReason.GROUP_RATCHET_NO_KEY
                GroupRatchetEngine.OpenOutcome.Failed.DUPLICATE -> DropReason.GROUP_RATCHET_DUPLICATE
                GroupRatchetEngine.OpenOutcome.Failed.BAD_HEADER -> DropReason.GROUP_RATCHET_BAD_HEADER
                GroupRatchetEngine.OpenOutcome.Failed.AEAD_FAIL -> DropReason.GROUP_RATCHET_AEAD_FAIL
                else -> DropReason.DECRYPT_FAILED
            }
        metrics.onDropped(reason)
        Log.w(TAG, "drop group ratchet chat ${env.id}: $outcome")
        if (reason == DropReason.GROUP_RATCHET_NO_KEY || reason == DropReason.GROUP_RATCHET_AEAD_FAIL) {
            maybeRequestGroupKey(env, groupId, me, now)
        }
    }

    /**
     * Sends a key request to [env]'s sender when the heuristic fires (≥3 distinct undecryptable
     * frames within the age window, ≥1 h since the last request to them for this group). An ordinary
     * v2 ctl DM (`CTL_GROUP_KEY_REQ`) — custodied by every relay, reaches an offline sender — answered
     * by the responder's re-seal of their current seeds. Never triggers an epoch advance.
     */
    private suspend fun maybeRequestGroupKey(
        env: RelayEnvelope,
        groupId: String,
        me: String,
        now: Long,
    ) {
        // Age-gate (the custody dead-on-arrival guard, applied to the heuristic): a replayed ancient
        // frame — whose epoch is legitimately swept everywhere — must not burn the request budget.
        if (now - env.sentAt > GroupRatchetSessions.REQUEST_MAX_FRAME_AGE_MS) return
        if (!groupRatchet.noteUndecryptable(groupId, env.senderId, env.id, now)) return
        val peer = peers.find(env.senderId) ?: return
        if ((peer.capabilities ?: 0L) and Protocol.CAP_RATCHET == 0L) return
        val bundle = peer.pubKey?.let { PublicKeyBundle.decode(it) } ?: return
        val prekey =
            peer.prekeyId?.let { pid ->
                peer.prekeyPub
                    ?.let { runCatching { b64d(it) }.getOrNull() }
                    ?.let { RatchetEngine.PeerPrekey(id = pid, pub = it) }
            }
        val id = FrameId.new()
        val aad = MessageCrypto.header(id, me, now, env.senderId)
        val plaintext = MessageContent(body = "", ctl = MessageContent.CTL_GROUP_KEY_REQ, gk = GroupKeyPayload(groupId)).encode()
        val sealed = ratchet.sealDm(env.senderId, bundle.dhPublicKey(), prekey, plaintext, aad, now) ?: return
        Log.w(TAG, "requesting group key for $groupId from ${env.senderId}")
        groupRatchet.markKeyRequested(groupId, env.senderId, now)
        originate(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = me,
                sentAt = now,
                recipientId = env.senderId,
                payload = WireCodec.encodePayload(ChatContent(enc = sealed)),
            ),
        )
    }

    /** The pinned peer's raw X25519 identity key — the ratchet's DH base — or null when it is not pinned. */
    private suspend fun pinnedDhKey(nodeId: String): ByteArray? =
        peers
            .find(nodeId)
            ?.pubKey
            ?.let { PublicKeyBundle.decode(it) }
            ?.dhPublicKey()

    /** The one envelope shape the unsigned door admits (ADR 059): v3, the DM form, and nothing group-shaped. */
    private fun admitsUnsigned(enc: EncEnvelope?): Boolean =
        enc != null && enc.v == EncEnvelope.VERSION_DM_V3 && enc.r != null && enc.g == null

    /**
     * v2 carries its 12-byte nonce; v3 carries the field empty and derives it (ADR 059). Anything else is
     * structurally malformed — its own drop, and like every BAD_HEADER never a reset trigger.
     */
    private fun nonceShapeValid(enc: EncEnvelope): Boolean =
        if (enc.v == EncEnvelope.VERSION_DM_V3) enc.nonce.isEmpty() else enc.nonce.size == AesGcm.IV_BYTES

    /** The plaintext schema follows the envelope version: v3 carries the labeled compact layout, v2 the named one. */
    private fun decodePlaintext(
        enc: EncEnvelope,
        plaintext: ByteArray,
    ): MessageContent? = if (enc.v == EncEnvelope.VERSION_DM_V3) MessageContentV2.decode(plaintext) else MessageContent.decode(plaintext)

    /**
     * An unsigned frame is a tick or nothing. It opened, so it is the session peer's — but a signature
     * stripped off a captured plain DM and re-injected point-to-point would open just as well, and it must
     * not deliver through this door (it would dodge custody and dedup the flooded copy away). Refused
     * before commit, so the chain index is untouched and the signed copy still opens later.
     */
    private fun unsignedButNotATick(
        signed: Boolean,
        plain: MessageContent,
    ): Boolean {
        if (signed || plain.ctl == MessageContent.CTL_RECEIPT) return false
        metrics.onDropped(DropReason.UNSIGNED_REFUSED)
        return true
    }

    /**
     * Maps a typed v2 open failure to its drop reason, and — for the two shapes that mean "the peer
     * assumes session state we don't have" (our DB wiped, or their epochs based on privs we retired) —
     * feeds the reset heuristic, sending a rate-limited session reset request when it fires.
     */
    private suspend fun onRatchetFailure(
        env: RelayEnvelope,
        outcome: RatchetEngine.OpenOutcome,
        me: String,
        now: Long,
        // False for a frame the unsigned door admitted (ADR 059): counted, never a reset trigger.
        authenticated: Boolean = true,
    ) {
        val reason =
            when (outcome) {
                RatchetEngine.OpenOutcome.Failed.NO_SESSION -> DropReason.RATCHET_NO_SESSION
                RatchetEngine.OpenOutcome.Failed.EPOCH_GONE -> DropReason.RATCHET_EPOCH_GONE
                RatchetEngine.OpenOutcome.Failed.DUPLICATE -> DropReason.RATCHET_DUPLICATE
                RatchetEngine.OpenOutcome.Failed.BAD_HEADER -> DropReason.RATCHET_BAD_HEADER
                RatchetEngine.OpenOutcome.Failed.AEAD_FAIL -> DropReason.RATCHET_AEAD_FAIL
                else -> DropReason.DECRYPT_FAILED
            }
        metrics.onDropped(reason)
        // The header fields are the whole diagnosis for these: EPOCH_GONE says only "we could not find a
        // base key", and which one — our local epoch `pe`, or the signed prekey when `pe` is 0 — is what
        // separates two peers sitting in different eras from a swept retention window. Cheap, and drops are
        // already rate-limited by being drops.
        val r = WireCodec.decodePayload<ChatContent>(env.payload)?.enc?.r
        Log.w(TAG, "drop v2 chat ${env.id}: $outcome se=${r?.se} pe=${r?.pe} n=${r?.n} init=${r?.init != null}")
        // AEAD_FAIL joins the two missing-state cases as a reset trigger. It is the *split-brain* failure —
        // both sides hold a session and the roots disagree — and it is the only one that cannot resolve
        // itself: NO_SESSION and EPOCH_GONE each say "we lack something", and the peer's own traffic
        // eventually supplies it, whereas two disagreeing roots re-serve undecryptable custody at each
        // other indefinitely. Safe to act on because the frame is already authenticated: `verifyInbound`
        // checks the Ed25519 signature against the pinned bundle BEFORE any decrypt, so a signature-valid
        // frame that fails the AEAD is a real peer whose era diverged, not a tampered one. Corruption in
        // transit cannot reach here either — it would fail the signature first. The heuristic's own bounds
        // (≥3 distinct frame ids, a 6 h per-peer floor, a pinned ratchet-capable peer) still apply. The
        // group path above already recovers from its own AEAD_FAIL (GROUP_RATCHET_AEAD_FAIL →
        // maybeRequestGroupKey); the DM path was the outlier, not this the novelty.
        // DUPLICATE used to count too, on the theory that several DISTINCT frames landing on consumed chain
        // indices meant the sender had restarted its chain while we kept ours. ADR 024 removed it: the
        // distinct-id rule does not separate that from the benign case, because a re-served *backlog* is
        // many distinct ids, not one id repeating. And a consumed index is proof we already decrypted that
        // frame, so the re-serve is our own delivered history — the one shape that can never mean divergence.
        // The desync it was proxying for is fixed at the source now, at all three sites that change a root.
        // The unsigned tick (ADR 059) is the one frame that reaches here WITHOUT that signature check, and
        // it is exactly why it may never feed the heuristic: three forged frames with distinct ids would
        // otherwise buy an attacker a session reset per pair — a purge, a re-root, a day of re-seals.
        if (authenticated && reason in RESET_TRIGGERING_DROPS) {
            maybeRequestReset(env, me, now)
        }
    }

    /**
     * Sends a session reset request to [env]'s sender when the heuristic fires (≥3 distinct
     * undecryptable frames, ≥6 h since the last request). The request is an ordinary v2 DM — fresh
     * X3DH init in the header, `ctl = CTL_SESSION_RESET` inside the ciphertext — deliberately NOT a
     * new frame type, so pre-ratchet relays custody it like any chat and it reaches an offline peer.
     */
    private suspend fun maybeRequestReset(
        env: RelayEnvelope,
        me: String,
        now: Long,
    ) {
        val session = ratchet.sessionFor(env.senderId)
        if (session != null && !isLiveEvidence(env, session, now)) {
            // Never silent: a pair wedged in the field otherwise presents exactly like one whose
            // heuristic simply has not reached three distinct failures yet. Rate-limited by sitting on
            // the drop path — this only runs for frames already counted as an undecryptable drop.
            Log.d(
                TAG,
                "reset gate: ${env.id} from ${env.senderId} pre-era " +
                    "sentAt=${env.sentAt} establishedAt=${session.establishedAt} " +
                    "weAreInitiator=${session.weAreInitiator}",
            )
            return
        }
        if (!ratchet.noteUndecryptable(env.senderId, env.id, now)) return
        sendSessionReset(env.senderId, me, now)
    }

    /**
     * Whether an undecryptable frame is evidence that the session we hold *now* is broken, rather than
     * ciphertext that was already doomed before it arrived. Gates the heuristic ahead of
     * [RatchetSessions.noteUndecryptable] so doomed frames never enter the distinct-id LRU either — eight
     * slots is small enough that a re-served backlog would otherwise evict the real failures.
     *
     * Re-rooting is exactly what discards the keys the previous era was sealed under, so every frame
     * authored before [RatchetEngine.SessionState.establishedAt] is permanently unreadable by
     * construction — arriving late says nothing about the session we hold now. Without that gate the
     * heuristic is self-sustaining, which is what ADR 024 was opened for: each reset strands a custody
     * TTL's worth of ciphertext, the re-serves of it trip the peer's heuristic, its reset strands ours,
     * and the pair re-roots past each other indefinitely — a six-hour blackout per cycle in the field.
     *
     * **The comparison is only single-clock in one direction** (ADR 026; ADR 024's claim that it "reads
     * identically on both ends" was wrong). [RelayEnvelope.sentAt] is always the *sender's* clock, while
     * `establishedAt` follows [RatchetEngine.SessionState.weAreInitiator] exactly:
     *
     * - `weAreInitiator == false` — we adopted the peer's `InitPayload.at` (establish, replacement, or
     *   race-loser), so `establishedAt` is that same peer's clock. Exact; compared as-is.
     * - `weAreInitiator == true` — `RatchetEngine.initiate` wrote OUR clock. Comparing it against the
     *   peer's `sentAt` spans two devices, and a peer whose clock lags ours has every frame classified
     *   pre-era until the skew is worked off — the heuristic silently disabled in that direction, on a
     *   population that often runs for weeks without network time.
     *
     * For that half: [Protocol.MAX_FUTURE_SKEW_MS] absorbs ordinary disagreement, and
     * [RatchetSessions.STRANDED_TAIL_MS] bounds the rest. The second is the part skew cannot defeat — it
     * compares our own clock against our own stamp, and once the era has outlived every retention window
     * there is no stranded tail left anywhere to protect, so continued undecryptable traffic is real
     * divergence whatever the peer thinks the time is.
     *
     * No session means no era to compare against, and nothing to protect: a reset is the correct and only
     * response to a peer we cannot read at all, so those frames pass (the caller's null check).
     */
    private fun isLiveEvidence(
        env: RelayEnvelope,
        session: RatchetEngine.SessionState,
        now: Long,
    ): Boolean {
        if (!session.weAreInitiator) return env.sentAt >= session.establishedAt
        if (now - session.establishedAt >= RatchetSessions.STRANDED_TAIL_MS) return true
        return env.sentAt >= session.establishedAt - Protocol.MAX_FUTURE_SKEW_MS
    }

    /**
     * Seals and floods a `CTL_SESSION_RESET` to [peerId], with no heuristic in front of it. Split out of
     * [maybeRequestReset] so the debug bridge can drive it directly: every gate below returns silently, so
     * a wedged pair in the field is otherwise indistinguishable from one whose heuristic simply has not
     * fired yet. Returns why it declined, or null on success.
     *
     * The gates are the peer material an X3DH initiation needs, and any of them can be the real reason a
     * stuck session never recovers — a peer we hold no prekey for can never be re-established from this
     * side at all, however many undecryptable frames it sends us.
     */
    suspend fun sendSessionReset(
        peerId: String,
        me: String,
        now: Long,
    ): String? {
        val peer = peers.find(peerId) ?: return "no peer row"
        if ((peer.capabilities ?: 0L) and Protocol.CAP_RATCHET == 0L) return "peer is not ratchet-capable"
        val bundle = peer.pubKey?.let { PublicKeyBundle.decode(it) } ?: return "no pinned pubKey"
        val prekeyId = peer.prekeyId ?: return "no pinned prekey id"
        val prekeyPub = peer.prekeyPub?.let { runCatching { b64d(it) }.getOrNull() } ?: return "no pinned prekey pub"
        val id = FrameId.new()
        val aad = MessageCrypto.header(id, me, now, peerId)
        val plaintext = MessageContent(body = "", ctl = MessageContent.CTL_SESSION_RESET).encode()
        val sealed =
            ratchet.sealResetDm(
                peerId = peerId,
                peerIkPub = bundle.dhPublicKey(),
                peerSpk = RatchetEngine.PeerPrekey(id = prekeyId, pub = prekeyPub),
                plaintext = plaintext,
                aad = aad,
                now = now,
            ) ?: return "sealResetDm refused (unusable prekey)"
        Log.w(TAG, "requesting ratchet session reset with $peerId")
        originate(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = me,
                sentAt = now,
                recipientId = peerId,
                payload = WireCodec.encodePayload(ChatContent(enc = sealed)),
            ),
        )
        return null
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
    suspend fun canCarry(
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
    suspend fun onCarriedFrame(env: RelayEnvelope) {
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
     * (never re-upserted, so a frame can't resurrect it), a roster [vetRoster] refuses, or a *new* group
     * whose creator we've blocked (covers the proxy case where a non-blocked member relays the first
     * frame carrying a blocked createdBy). The name is last-writer-wins on [sentAt] so concurrent renames
     * across the mesh converge.
     *
     * The roster itself is **pinned, never overwritten**: a frame can only establish a group whose id
     * verifiably derives from its founding roster ([vetRoster]'s Adopt), and thereafter membership only
     * shrinks, via signed `groupleave` frames ([GroupRepository.recordDeparture]) — group key state
     * (docs/GROUP_FORWARD_SECRECY.md) distributes epoch seeds to exactly this roster, so its integrity
     * is a security input, not presentation.
     */
    private suspend fun reconcileGroup(
        group: GroupInfo,
        senderId: String,
        sentAt: Long,
        me: String,
    ): Boolean {
        // DataStore read hoisted out of the transaction: it can't enroll in a Room transaction, and holding the
        // exclusive DB lock across a DataStore suspend would stall every other writer.
        val blocked = settings.blockedNodeIds.first()
        // find → refuse-check → upsert run in one transaction, re-reading `existing` inside, so the left-tombstone
        // check and the upsert can't tear apart from a concurrent transactional GroupRepository.leave()/
        // delete(): otherwise a find that read left=false just before leave() commits would blind-upsert left=false
        // and resurrect the group. Returns the PhotoDecision on adoption (its bytes may still need pulling), or null
        // when the frame is refused.
        val photo =
            // Explicit type argument: withWriteTransaction's block is a TransactionScope<R> receiver, and R is
            // invariant, so inference latches onto the first `return@` (null) instead of unifying with the
            // PhotoDecision returned at the end.
            db.withWriteTransaction<PhotoDecision?> {
                val existing = groups.find(group.id)
                if (groupFrameRefused(group, senderId, existing, blocked)) return@withWriteTransaction null
                val roster =
                    when (val verdict = vetRoster(group, senderId, existing, me)) {
                        is RosterVerdict.Accept -> {
                            verdict.members
                        }

                        RosterVerdict.NotOurs -> {
                            return@withWriteTransaction null
                        }

                        RosterVerdict.Refused -> {
                            metrics.onDropped(DropReason.GROUP_ROSTER_REFUSED)
                            return@withWriteTransaction null
                        }
                    }

                // The name is shared only when explicitly set; an unnamed (blank/null) frame never clears a name
                // someone else set. Adopt an incoming name only if it's newer (last-writer-wins on sentAt).
                val incomingName = group.name?.takeIf { it.isNotBlank() }?.take(TextLimits.GROUP_NAME)
                val keepName = existing?.name.orEmpty()
                val keepClock = existing?.nameUpdatedAt ?: 0L
                val takeIncoming = incomingName != null && sentAt >= keepClock
                val decision = groupPhotoDecision(existing, group)
                val createdAt = existing?.createdAt ?: sentAt
                groups.upsert(
                    GroupEntity(
                        groupId = group.id,
                        name = if (takeIncoming) incomingName else keepName,
                        members = GroupMembersStore.encode(roster),
                        createdBy = group.createdBy,
                        createdAt = createdAt,
                        nameUpdatedAt = if (takeIncoming) sentAt else keepClock,
                        left = false,
                        departed = existing?.departed ?: GroupMembersStore.encode(emptyList()),
                        photoHash = decision.hash,
                        photoUpdatedAt = decision.clock,
                    ),
                )
                groupNotices(group, senderId, sentAt, existing, incomingName, keepName, takeIncoming, decision, createdAt)
                decision
            } ?: return false
        // A newer photo whose bytes we don't hold yet: pull it hop-by-hop (after the upsert advanced the clock,
        // so the adopt-on-arrival clock check matches), then adopt on arrival. Outside the transaction — it's a
        // network-bound blob fetch, not a DB write.
        photo.pull?.let { pullGroupPhoto(group.id, it, photo.clock) }
        return true
    }

    /**
     * Writes the status notices a reconciled group frame earns, inside [reconcileGroup]'s transaction so
     * a notice can never survive a roster change that didn't commit (and vice versa) — the rule
     * [GroupRepository.recordDeparture] already follows for departures.
     *
     * The distinction that matters throughout is **adopted** versus **changed**. `GroupInfo` is
     * self-describing and rides on every chat frame, so the name and photo we already hold are
     * re-asserted constantly; [takeIncoming] and [PhotoDecision] answer "does this frame win the
     * last-writer-wins race", which is true for a frame that merely repeats what we have. Only a value
     * that actually differs is an event a reader should see.
     *
     * First sight ([existing] null) is the other half of that: a group arriving with a name and a photo
     * has not been renamed or re-photographed, it has been *created*, and it gets exactly one line
     * saying so. That is also the only join-shaped notice there is — a group's id is the hash of its
     * founding roster and membership only ever shrinks, so nobody ever joins one.
     */
    private suspend fun groupNotices(
        group: GroupInfo,
        senderId: String,
        sentAt: Long,
        existing: GroupEntity?,
        incomingName: String?,
        keepName: String,
        takeIncoming: Boolean,
        photo: PhotoDecision,
        createdAt: Long,
    ) {
        if (existing == null) {
            messages.save(StatusNotices.groupCreated(group.id, group.createdBy, createdAt))
            return
        }
        if (takeIncoming && incomingName != null && incomingName != keepName) {
            messages.save(StatusNotices.groupRenamed(group.id, senderId, incomingName, sentAt))
        }
        photo.changedTo?.let { messages.save(StatusNotices.groupPhotoChanged(group.id, senderId, it, sentAt)) }
    }

    /**
     * Whether an inbound group frame must be ignored on grounds other than its roster (that's
     * [vetRoster]): a blocked sender, a group we've left (never re-upserted so a frame can't resurrect
     * it), or a *new* group whose creator we've blocked (covers the proxy case where a non-blocked
     * member relays the first frame carrying a blocked createdBy).
     */
    private fun groupFrameRefused(
        group: GroupInfo,
        senderId: String,
        existing: GroupEntity?,
        blocked: Set<String>,
    ): Boolean =
        senderId in blocked ||
            existing?.left == true ||
            (existing == null && group.createdBy in blocked)

    /** [vetRoster]'s verdict: the roster to store, a **silent** not-ours refusal (we are merely a
     *  carrier for someone else's group — the overwhelmingly common case on a relay path), or a
     *  counted integrity refusal (a frame claiming us that fails the pin/derivation/sender rules). */
    private sealed interface RosterVerdict {
        data class Accept(
            val members: List<String>,
        ) : RosterVerdict

        object NotOurs : RosterVerdict

        object Refused : RosterVerdict
    }

    /**
     * Vets an inbound self-describing roster against the pinned row and returns the **effective member
     * list to store** — or a refusing [RosterVerdict]. The invariant: the stored founding set
     * (members ∪ departed) only ever comes from a roster whose [GroupInfo.id] *is* the hash of that set
     * ([Conversations.groupIdFor]), so no member can smuggle an extra id in (they cannot forge a set
     * that derives to the pinned id) and none can hide one (a shrunk roster is accepted as a frame but
     * never mutates the pin — departures happen only via signed `groupleave`).
     *
     * Three accepted shapes:
     *  1. First sight (`existing == null`): the frame's founding roster (members ∪ [GroupInfo.departed])
     *     must derive to the id, contain us and the sender, and respect [GroupInfo.MAX_MEMBERS]. A
     *     first sight through a pre-`departed`-field build's frame *after* a departure is refused as
     *     unverifiable — the narrow trade for making id forgery impossible; any current build's frame
     *     (or any pre-departure frame) pins it.
     *  2. Within the pin: the frame's founding roster ⊆ stored founding set. Stored members unchanged.
     *  3. Verified superset: a self-verifying founding roster that *extends* the stored set repairs a
     *     pin first made from a truncated frame (case 1's refusal healing itself once a full roster
     *     arrives). Adopts the verified set minus our recorded departures.
     *
     * The incoming [GroupInfo.departed] list is arithmetic only (it completes the founding set for
     * derivation); it is never merged into the stored tombstones — trusting it would let a member
     * "kick" another by asserting they left. Cost: a departure we never saw the signed leave for keeps
     * the leaver in our effective roster (leave-rekey is *eventual*, bounded by leave-frame
     * convergence — docs/GROUP_FORWARD_SECRECY.md §security claim).
     */
    private fun vetRoster(
        group: GroupInfo,
        senderId: String,
        existing: GroupEntity?,
        me: String,
    ): RosterVerdict {
        val incomingFounding = (group.members + group.departed.orEmpty()).distinct()
        val storedMembers = existing?.let { GroupMembersStore.decode(it.members) }.orEmpty()
        val storedDeparted = existing?.let { GroupMembersStore.decode(it.departed) }.orEmpty()
        val storedFounding = (storedMembers + storedDeparted).toSet()
        // Not our group: nothing to vet and nothing to count — we relay and custody it regardless
        // (delivery-side gating only). Checked against the pin when we hold one, else the frame.
        val claimedFounding = if (existing != null) storedFounding else incomingFounding.toSet()
        if (me !in claimedFounding) return RosterVerdict.NotOurs
        val withinPin = existing != null && storedFounding.containsAll(incomingFounding)
        val founding: Collection<String>
        val members: List<String>
        if (withinPin) {
            founding = storedFounding
            members = storedMembers
        } else {
            // First sight, or a strict superset of our pin: acceptable only when the full founding set
            // self-verifies — the id IS its hash, so only the true founding roster can pass.
            val verifiedSuperset =
                incomingFounding.containsAll(storedFounding.toList()) &&
                    incomingFounding.size <= GroupInfo.MAX_MEMBERS &&
                    Conversations.groupIdFor(incomingFounding) == group.id
            if (!verifiedSuperset) return RosterVerdict.Refused
            founding = incomingFounding
            members = incomingFounding.filter { it !in storedDeparted }
        }
        // Us and the sender must both be founding members. Checked against the founding set, not the
        // frame's effective roster: a frame omitting us can't starve us out of our own group, and a
        // departed member's in-flight (pre-leave) frames still deliver.
        if (me !in founding || senderId !in founding) return RosterVerdict.Refused
        return RosterVerdict.Accept(members)
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
        if (!takePhoto) return PhotoDecision(keepPhoto, keepPhotoClock, pull = null, changedTo = null)
        val haveBytes = blobStore.has(incomingPhoto)
        return PhotoDecision(
            hash = if (haveBytes) incomingPhoto else keepPhoto,
            clock = incomingPhotoClock,
            pull = if (haveBytes) null else incomingPhoto,
            changedTo = incomingPhoto,
        )
    }

    /** A reconciled group photo: the hash to store, its clock, and (if its bytes aren't local) the hash to pull. */
    private data class PhotoDecision(
        val hash: String?,
        val clock: Long,
        val pull: String?,
        /**
         * The newly-adopted photo when this frame actually changed it, else null — which is **not** the
         * same question as [hash] or [pull]. [hash] holds the old photo while new bytes are still in
         * flight and [pull] empties as soon as they land, so neither can tell "the photo changed" from
         * "the photo is unchanged", and only this distinguishes a real change from the same photo being
         * re-asserted by every chat frame that carries the group.
         */
        val changedTo: String?,
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
     * not flagged explicit by the screen in [MeshBlobStore.saveIncoming] — which a group photo, naming no
     * message row, can never skip. A no-op for blobs no group wants.
     */
    private suspend fun adoptAdvertisedGroupPhoto(hash: String) {
        val targets = advertisedGroupPhotos.entries.filter { it.value.hash == hash }.map { it.key }
        if (targets.isEmpty()) return
        // Mirror the avatar gate: don't adopt an explicit photo when filtering is on (the setting gates
        // receive-side hiding, so off -> adopt anyway); drop the now-unwanted blob.
        if (settings.contentFilteringEnabled.first() && imageScreening.isImageFlagged(hash)) {
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
     * Whether [conversationId] should surface a notification / be treated as a known chat rather than a
     * stranger's **message request**. Delegates to the shared, pure [Conversations.isAccepted] so the notify
     * gate here, the retention sweep in [MeshManager], and the Message Requests UI all apply one identical
     * rule. Convergence-safe: read only on the local delivery path (never in [canCarry]/relay); a stranger's
     * first DM/group is silent until accepted — see the Message Requests design.
     */
    private suspend fun isAccepted(
        conversationId: String,
        me: String,
    ): Boolean =
        Conversations.isAccepted(
            conversationId,
            settings.acceptedConversations.first(),
            peers.verifiedNodeIds().toSet(),
            messages.conversationsIAuthoredIn(me).toSet(),
            groupSendersOf(conversationId),
        )

    /**
     * The senders who have posted in [conversationId] when it's a group (empty otherwise), for the group
     * known-sender branch of [Conversations.isAccepted]. Read on the delivery path *after* the inbound
     * message is saved, so it includes the current sender — a group first spoken in by a known peer is
     * accepted on that very message.
     */
    private suspend fun groupSendersOf(conversationId: String): Set<String> =
        if (Conversations.kindFor(conversationId) == ConversationKind.GROUP) {
            messages.sendersIn(conversationId).toSet()
        } else {
            emptySet()
        }

    /**
     * Refreshes the single coalesced "message request received" heads-up with the current count of
     * pending (unaccepted) request threads. Reuses the shared [Conversations.isAccepted] predicate so the
     * count matches the per-message gate; excludes Nearby and blocked senders. Local / notify only, so mesh
     * convergence is untouched (custody + relay run outside this dispatch path).
     */
    private suspend fun notifyPendingRequests(me: String) {
        val accepted = settings.acceptedConversations.first()
        val verified = peers.verifiedNodeIds().toSet()
        val authored = messages.conversationsIAuthoredIn(me).toSet()
        val blocked = settings.blockedNodeIds.first()
        val conversations = messages.distinctConversations()
        // Per-group senders, so a group a known peer has posted in counts as a chat, not a request
        // (matches the per-message gate above and the Message Requests inbox).
        val sendersByGroup =
            conversations
                .filter { Conversations.kindFor(it) == ConversationKind.GROUP }
                .associateWith { messages.sendersIn(it).toSet() }
        val count =
            conversations.count { id ->
                id != Conversations.NEARBY &&
                    id !in blocked &&
                    !Conversations.isAccepted(id, accepted, verified, authored, sendersByGroup[id].orEmpty())
            }
        notifier.notifyMessageRequests(count)
    }

    /**
     * Persists an inbound chat into [conversationId], starts pulling any attachment blob we don't hold,
     * fires the appropriate notification, and acks. Shared by the DM/broadcast and group delivery paths.
     */
    private suspend fun deliverChat(
        env: RelayEnvelope,
        content: ChatContent,
        me: String,
        conversationId: String,
        // The plane this frame reached us on, stored on the row: an inbound message needs no receipt to
        // know how it travelled — it IS the evidence. (Our own sends learn their plane later, from the
        // receipt that flips their tick.) The broadcast path reaches here on every re-serve rather than
        // early-returning on the exists-gate, and a re-serve can cross a different plane (a room post heard
        // over LoRa, then re-served over Bluetooth custody) — which is why the default persist below is
        // first-write-wins: the row keeps the plane it first arrived on, like markReceived keeps the first
        // receipt's.
        plane: DeliveryPlane,
        // The decrypted content this delivery came from, or null for a cleartext room post. Three facts on a
        // sealed attachment live only in here and have no cleartext counterpart: its decryption key, and — for
        // an arbitrary file — its name and byte count (ADR 2026-09.qq2r). Passed whole rather than unpacked
        // into a row of loose nullable parameters, and read only for those three: [content] stays the shape
        // the row is built from, so the plaintext and cleartext paths keep one body.
        sealed: MessageContent? = null,
        // How the built row is persisted. The default inserts only if the row is absent: a re-served frame is
        // the same signed bytes, so it can never carry anything new, while a blind upsert would rewrite the
        // arrival plane, wipe the voice-note metadata setVoiceMeta added after the insert, and — for one of
        // OUR room posts looping back after the SeenSet lapsed — reset its ✓✓ to ✓. The v2 ratchet path
        // substitutes a hook that commits the ratchet delta + the row in one transaction (exists-gated first).
        persist: suspend (MessageEntity) -> Unit = { messages.saveIfAbsent(it) },
        // Who said it on the radio channel, when this is a heard Meshtastic post — carried straight onto the
        // row. Null on every ordinary delivery, and the discriminator the whole heard shape hangs off.
        origin: MeshPostOrigin? = null,
        // Whether a delivery receipt is owed. False for exactly one caller: a heard post has no author to
        // send one to — its Meshtastic speaker has no Knit identity, and the row's senderId is this phone by
        // convention. Never widen this; a Knit message always earns its ✓✓.
        ack: Boolean = true,
    ) {
        // A real message from this sender supersedes any "typing" indicator for them in this thread — clear it
        // now (idempotent, and a no-op if they weren't shown as typing). Runs on re-delivery too, harmlessly.
        typingTracker.onMessageFrom(conversationId, env.senderId)
        // First-ever delivery? Store-and-forward can re-serve a DM we already hold (after the 10-min
        // SeenSet window, or after a restart that empties it); notifying again would replay old messages.
        // The save below leaves an existing row untouched, so only the notification needs gating.
        val isNew = !messages.exists(env.id)
        val hash = content.attachmentHash
        persist(
            MessageEntity(
                id = env.id,
                senderId = env.senderId,
                recipientId = env.recipientId,
                conversationId = conversationId,
                // Clamp the inbound body: our sender-side TextLimits.MESSAGE only bounds what we originate, not
                // what a peer floods (otherwise bounded only by the 512 KiB record cap → unbounded local growth).
                // Mirrors the profile name/status clamp in handleProfile.
                body = content.body.take(TextLimits.MESSAGE),
                // Clamp a future-dated sentAt for local ordering so a bogus far-future frame can't pin itself to
                // the top of the conversation forever (the local-display complement of ForwardRepository's custody
                // guard). Honest clock skew within the window is kept as-is.
                sentAt = minOf(env.sentAt, System.currentTimeMillis() + Protocol.MAX_FUTURE_SKEW_MS),
                // Our own clock, unlike the sender's sentAt just above: the one honest answer to "when did
                // this get here", and the gap between the two is the store-and-forward latency. Null when the
                // frame is one of OUR room posts looping back after the SeenSet lapsed — we did not receive
                // that, we sent it. A heard Meshtastic post sits in our sender column by convention yet did
                // arrive, off the board, so the origin overrides the sender test. Stamped once: the default
                // persist below is exists-gated, so a re-serve keeps the first crossing, exactly like
                // receivedVia's plane.
                arrivedAt = if (env.senderId != me || origin != null) clock() else null,
                received = false,
                receivedVia = plane.code,
                mentions = MentionStore.encode(content.mentions),
                attachmentHash = hash,
                attachmentMime = content.attachmentMime,
                attachmentKey = sealed?.attachmentKey,
                attachmentName = sealed?.attachmentName,
                attachmentSize = sealed?.attachmentSize,
                moderation =
                    if (
                        // Both public rooms take the room moderator (the lexical profanity pass on top of the
                        // ML one), and for one reason: a room is read by strangers. The bridged room needs it
                        // at least as much — its authors are strangers by definition, and nothing screens
                        // them before they reach the air.
                        classifyText(content.body, "incoming", Conversations.isPublicRoom(conversationId))
                    ) {
                        MessageEntity.MODERATION_TEXT_FLAGGED
                    } else {
                        MessageEntity.MODERATION_NONE
                    },
                originNode = origin?.node,
                originName = origin?.name,
                originChannel = origin?.channel,
                originHops = origin?.hops,
                originSnrDeci = origin?.snrDeci,
                originViaMqtt = origin?.viaMqtt == true,
                originPeerId = origin?.peerId,
            ).withReply(content.replyTo),
        )
        // Start pulling the referenced blob unless we already hold it (the UI observes the blobs table
        // and flips the attachment from "loading" to shown once the bytes land). If we already hold it
        // (e.g. cached earlier while relaying), screen its decrypted bytes now that the key is in hand;
        // otherwise it's screened on arrival ([screenObtainedAttachment]) once the key has been stored.
        if (hash != null) {
            if (blobStore.has(hash)) {
                screenHeldAttachment(hash, sealed?.attachmentKey, content.attachmentMime)
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
        // Suppress notifications for a stranger's request (an unaccepted DM/group): the message still persists
        // above and still acks below — only the interruption is withheld (the Message Requests gate). Custody
        // and relay run outside this dispatch path (before it / after it returns), so mesh convergence is
        // untouched; this is a purely local presentation decision.
        if (isNew) {
            if (isAccepted(conversationId, me)) {
                if (env.senderId != me && content.mentions.mention(me)) {
                    notifyMention(env, content, conversationId, sealed?.attachmentName)
                } else {
                    notifyIncoming(env, content, conversationId, sealed?.attachmentName, origin)
                }
            } else if (env.senderId != me) {
                // A stranger's first DM/group: no per-message alert — just refresh the coalesced
                // "message request received" heads-up. A purely local presentation decision, like the accepted path.
                notifyPendingRequests(me)
            }
        }
        // Ack unconditionally (even on a re-delivery): the receipt floods back to the sender and, for a
        // DM, doubles as the vaccine that purges this message from any carrier that missed the first ack.
        if (ack) acknowledge(env, me, plane)
    }

    /**
     * Sends a delivery receipt for [env]. A DM addressed to us answers with a **sealed** receipt when the
     * author can read one (pinned bundle + CAP_RATCHET; an ordinary v2 ctl chat frame — flooded, custodied,
     * indistinguishable from conversation on the wire), else the legacy cleartext receipt frame. The two
     * forms deliberately diverge on custody (docs/ENCRYPTED_RECEIPTS_REACTIONS.md): a **cleartext** receipt
     * vaccine-purges the delivered DM at every carrier that parses it — including us, via the self-vaccinate
     * below, since we now custody our own inbound DMs; a **sealed** receipt purges nowhere (carriers can't
     * read it), so the DM ages out of custody on the frame-global TTL uniformly on every node. Broadcast and
     * group messages have no single recipient, so the tick is best-effort: a **point-to-point** receipt
     * (relay = false) straight back to the author, remembered by AckSync and re-sent until it lands (or ages
     * out) — the message itself converges via custody, but the tick otherwise had no delay-tolerance.
     */
    private suspend fun acknowledge(
        env: RelayEnvelope,
        me: String,
        plane: DeliveryPlane,
    ) {
        if (env.recipientId == me) {
            // A DM off the board holds its ✓✓ (ADR 054): a burst from one author becomes one sealed tick and a
            // reply we send meanwhile carries the acks for free — over LoRa the tick costs as much air as the
            // message. Every other plane keeps the instant receipt, and an author who could not read a sealed
            // one keeps the cleartext form (there is nothing to coalesce there).
            val coalescer = dmAcks
            if (plane == DeliveryPlane.LoRa && coalescer != null && canSealDmReceipt(env.senderId)) {
                coalescer.hold(env.senderId, env.id)
                metrics.onLoraTickDeferred()
                return
            }
            if (sealDmReceipt(env, me)) return
            ackCleartext(env.id, me)
        } else {
            // Broadcast/group: a unicast, point-to-point (relay = false) tick straight to the author when
            // it has a path — no NDP required (a fast-fanned message gets its receipt too). A GROUP tick
            // toward an absent sealed-capable author additionally escalates (escalatable): AckSync batches
            // the acks and originates ONE sealed ctl frame into custody/flood/spool, so the tick converges
            // exactly like the message it acks. Broadcast-room ticks stay best-effort-only by design.
            ackSync.owe(env.id, env.senderId, escalatable = env.group != null)
        }
    }

    /**
     * The legacy cleartext receipt for [ackId] — what an author who cannot read a sealed one gets, and the
     * coalescer's per-id fallback when a held batch fails to seal (`MeshManager.flushDmAcks`). sentAt is
     * load-bearing for its custody: every store derives the frame-global expiry from it (sentAt + TTL, ADR
     * 006), so an unset 0 computes a 1970 expiry and is refused dead-on-arrival at every node — the receipt
     * would flood live but never be carried (work item #16).
     */
    internal suspend fun ackCleartext(
        ackId: String,
        me: String,
    ) {
        val ack =
            RelayEnvelope(
                type = FrameType.RECEIPT,
                id = FrameId.new(),
                senderId = me,
                sentAt = clock(),
                payload = WireCodec.encodePayload(ReceiptContent(ackId)),
            )
        originate(ack)
        // Self-vaccinate: this cleartext receipt purges the delivered DM from every carrier that sees
        // it, and our own custody copy must follow the identical rule (ADR 006 — a liveness rule that
        // differs per node churns digests forever). onAck's recipient guard passes by construction
        // (our row's recipientId is us) and tombstones the id against re-plants.
        forwardSync.onAck(ackId, me)
    }

    /** Whether [authorId] could read a sealed receipt: a pinned bundle carrying `CAP_RATCHET` (the seal itself may still fail). */
    private suspend fun canSealDmReceipt(authorId: String): Boolean {
        val peer = peers.find(authorId) ?: return false
        if ((peer.capabilities ?: 0L) and Protocol.CAP_RATCHET == 0L) return false
        return peer.pubKey?.let { PublicKeyBundle.decode(it) } != null
    }

    /**
     * Seals a `CTL_RECEIPT` ctl DM for [env]'s author and floods it, or returns false when the author
     * can't read one (no pinned bundle / no CAP_RATCHET — the caller falls back to the cleartext frame)
     * or the seal itself fails (no session and no usable prekey). Never routes through the v1 wrap: a
     * pre-ratchet build would decrypt a v1 ctl, strip the unknown field, and persist an empty bubble —
     * ctl payloads are v2-only by construction (the sealDm-direct precedent of every other ctl sender).
     * The session usually exists (we just opened the author's v2 DM); the prekey path covers a capable
     * author whose DM arrived v1 (e.g. sent before our profile landed on their side).
     */
    private suspend fun sealDmReceipt(
        env: RelayEnvelope,
        me: String,
    ): Boolean {
        val peer = peers.find(env.senderId) ?: return false
        if ((peer.capabilities ?: 0L) and Protocol.CAP_RATCHET == 0L) return false
        val bundle = peer.pubKey?.let { PublicKeyBundle.decode(it) } ?: return false
        val prekey =
            peer.prekeyId?.let { pkid ->
                peer.prekeyPub
                    ?.let { runCatching { b64d(it) }.getOrNull() }
                    ?.let { RatchetEngine.PeerPrekey(id = pkid, pub = it) }
            }
        val now = clock()
        val id = FrameId.new()
        val aad = MessageCrypto.header(id, me, now, env.senderId)
        val (plaintext, scheme) = MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, ack = env.id).sealBytes(peer.readsCryptoV3())
        val sealed =
            ratchet.sealDm(
                env.senderId,
                bundle.dhPublicKey(),
                peerSpk = prekey,
                plaintext = plaintext,
                aad = aad,
                now = now,
                scheme = scheme,
            )
                ?: run {
                    metrics.onReceiptSealedFallback()
                    return false
                }
        if (scheme == EncEnvelope.VERSION_DM_V3) metrics.onDmSealedV3()
        originateTick(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = me,
                sentAt = now,
                recipientId = env.senderId,
                payload = WireCodec.encodePayload(ChatContent(enc = sealed)),
            ),
        )
        metrics.onReceiptSealed()
        return true
    }

    /** Fires a "new message" notification for an inbound chat in [conversationId] (skips our own and empty messages). */
    private suspend fun notifyIncoming(
        env: RelayEnvelope,
        content: ChatContent,
        conversationId: String,
        fileName: String?,
        origin: MeshPostOrigin? = null,
    ) {
        val me = identity.nodeId()
        val peer = peers.find(env.senderId)
        // Collision-aware (ADR 058): "Alice (JoyfulFerret)" when another known peer is also an Alice.
        val labels = peers.labelIndex()
        val senderLabel = labels.labelFor(env.senderId, peer?.name)
        val peerAvatar = peer?.avatarHash?.let { blobs.bytes(it) }
        // Attachment-only messages have a blank body; show a placeholder so they still notify.
        val body = content.body.ifBlank { attachmentPreview(content, fileName) }
        // A heard Meshtastic post is authored by its speaker, not by this phone, whose id sits in the row's
        // sender column by convention. Two things follow, and both are wrong without this. The notification
        // must name the speaker — the contact their board resolved to, else the board's NodeDB name, else the
        // `!hex` id — and the sender id it is keyed on must not be ours, or the one device that hears the
        // channel is the one device that never notifies, because `incomingNotification` suppresses our own
        // messages. A resolved contact keys and wears exactly what their DM notification would, so Android
        // groups the two; a stranger wears no face at all.
        val speaker = origin?.let { heardSpeaker(it) { id, name -> labels.labelFor(id, name).text } }
        val incoming =
            incomingNotification(
                senderId = speaker?.id ?: env.senderId,
                body = body,
                sentAt = env.sentAt,
                selfId = me,
                peerName = speaker?.name ?: senderLabel.text,
                peerAvatarBytes = if (speaker != null) speaker.avatar else peerAvatar,
                conversationId = conversationId,
            ) ?: return
        val conversation = resolveConversation(conversationId, env.senderId, senderLabel.text, peerAvatar, me, labels)
        val selfAvatar = settings.ownAvatarHash.first()?.let { blobs.bytes(it) }
        notifier.notify(incoming, conversation, me, settings.displayName.first(), selfAvatar)
    }

    /** Who a heard Meshtastic post's notification is keyed on, named after and wears — see [notifyIncoming]. */
    private class HeardSpeaker(
        val id: String,
        val name: String,
        val avatar: ByteArray?,
        /** The contact's stored display name, for the prefix their board put on the line; null for a stranger. */
        val plainName: String?,
    )

    /**
     * The speaker of a heard post as a notification shows them: the contact their board resolved to (keyed,
     * named and pictured exactly as that contact's DM notification would be, so Android groups the two), else
     * the board's NodeDB name over the `!hex` id, with no face at all — a stranger wears none.
     */
    private suspend fun heardSpeaker(
        origin: MeshPostOrigin,
        label: (id: String, name: String?) -> String,
    ): HeardSpeaker {
        val contact = origin.peerId?.let { peers.find(it) }
        val id = origin.peerId ?: meshNodeLabel(origin.node)
        val name =
            origin.peerId?.let { label(it, contact?.name) }
                ?: origin.name?.takeIf(String::isNotBlank)
                ?: meshNodeLabel(origin.node)
        return HeardSpeaker(id, name, contact?.avatarHash?.let { blobs.bytes(it) }, contact?.name)
    }

    /**
     * Notification stand-in for a message whose only content is an attachment, so it still says something
     * useful on the lock screen. Literal strings rather than resources because this layer holds no
     * `Context` (it is deliberately Android-light, `rules/mesh.md`); they mirror `chat_list_preview_photo`,
     * `chat_list_preview_voice` and `chat_list_preview_file` and should be changed together with them.
     *
     * A [fileName] wins over the mime because it is the more specific fact and the only one a *file* has:
     * an arbitrary file's mime is whatever its sender's provider called it, and "📎 application/zip" would be
     * a worse lock-screen line than the name the sender actually chose. Already normalized on decode
     * ([app.getknit.knit.mesh.protocol.AttachmentName]), so it is safe to draw.
     */
    private fun attachmentPreview(
        content: ChatContent,
        fileName: String?,
    ): String =
        when {
            content.attachmentHash == null -> content.body
            fileName != null -> "📎 $fileName"
            VoiceAudio.isVoice(content.attachmentMime) -> "🎤 Voice message"
            content.attachmentMime == LinkPreviewBlob.MIME -> "🔗 Link"
            else -> "📷 Photo"
        }

    /** Fires a "you were mentioned" notification on the Mentions channel for an inbound chat in [conversationId]. */
    private suspend fun notifyMention(
        env: RelayEnvelope,
        content: ChatContent,
        conversationId: String,
        fileName: String?,
    ) {
        val me = identity.nodeId()
        val peer = peers.find(env.senderId)
        // Collision-aware (ADR 058): "Alice (JoyfulFerret)" when another known peer is also an Alice.
        val labels = peers.labelIndex()
        val senderLabel = labels.labelFor(env.senderId, peer?.name)
        val peerAvatar = peer?.avatarHash?.let { blobs.bytes(it) }
        val body = content.body.ifBlank { attachmentPreview(content, fileName) }
        val incoming =
            mentionNotification(
                senderId = env.senderId,
                body = body,
                sentAt = env.sentAt,
                selfId = me,
                peerName = senderLabel.text,
                peerAvatarBytes = peerAvatar,
                conversationId = conversationId,
            ) ?: return
        val conversation = resolveConversation(conversationId, env.senderId, senderLabel.text, peerAvatar, me, labels)
        val selfAvatar = settings.ownAvatarHash.first()?.let { blobs.bytes(it) }
        notifier.notifyMention(incoming, conversation, me, settings.displayName.first(), selfAvatar)
    }

    /**
     * Resolves the conversation-level title + avatar a Signal-style notification shows (the group photo /
     * DM peer avatar as its large icon, the real thread name as its title). A DM uses the sender's
     * name/avatar; a group looks up its stored name/photo (falling back to member names via [groupTitle],
     * resolved through [labels] so two same-named members read apart); the Nearby room leaves both null so
     * [notifier] substitutes its own defaults.
     */
    private suspend fun resolveConversation(
        conversationId: String,
        senderId: String,
        dmName: String?,
        dmAvatar: ByteArray?,
        me: String,
        labels: PeerLabelIndex,
    ): NotifConversation =
        when (Conversations.kindFor(conversationId)) {
            ConversationKind.NEARBY -> {
                NotifConversation(conversationId, null, null, ConversationKind.NEARBY)
            }

            // Like the Nearby room: both null, so the notifier substitutes the room's own title and glyph.
            // Deliberately NOT the speaker's name and avatar — a bridged author has neither an avatar nor an
            // authenticated name, and putting an unverified one in a notification title is the one place it
            // would read as a person Knit vouches for.
            ConversationKind.MESHTASTIC -> {
                NotifConversation(conversationId, null, null, ConversationKind.MESHTASTIC)
            }

            ConversationKind.DM -> {
                NotifConversation(conversationId, displayNameFor(dmName, senderId), dmAvatar, ConversationKind.DM)
            }

            ConversationKind.GROUP -> {
                val group = groups.find(conversationId)
                val memberIds = group?.let { GroupMembersStore.decode(it.members) }.orEmpty()
                // Pre-resolve member names off the index (one query, not one per member), since
                // groupTitle's nameOf is non-suspend.
                val namesByNode = LinkedHashMap<String, String>()
                for (id in memberIds) namesByNode[id] = labels.labelFor(id).text
                val title =
                    group?.let {
                        groupTitle(it.name, memberIds, me, fallback = "") { id -> namesByNode[id] ?: id }.ifBlank { null }
                    }
                NotifConversation(conversationId, title, group?.photoHash?.let { blobs.bytes(it) }, ConversationKind.GROUP)
            }
        }

    /**
     * Pins an inbound peer's profile (self-cert check → last-writer-wins → immutable-pin guard → upsert),
     * then flushes/replays anything that was waiting on the sender's key.
     *
     * `@Suppress("CyclomaticComplexMethod")`: the #14 immutable-pin guard adds the one branch that tips this
     * past detekt's threshold of 15. The body is a straight-line sequence of null-coalesced field resolutions
     * and guards — not genuinely complex — and the guard is load-bearing, so suppress rather than reshuffle it.
     */
    @Suppress("CyclomaticComplexMethod")
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
        // The LWW key is the sender's profile *version*, not the frame's `sentAt` — `sentAt` is now a publish
        // stamp the sender refreshes on a cadence to keep the frame inside custody's `sentAt + ttl` window
        // (ADR 022), so it moves without the profile having changed. A peer predating the field sends no
        // `version`, and for those `sentAt` is exactly what it used to mean, which makes the fallback exact.
        val version = content.version ?: env.sentAt
        // Last-writer-wins, split across two watermarks. The key is immutable per nodeId (a different key
        // would be a hash collision, excluded above), so an out-of-order or re-served copy can never change
        // the pinned key — it could only revert name/status. A first profile (existing == null) is always
        // accepted, so this never blocks recovering a missing key.
        //
        // Presentation and prekey are gated SEPARATELY because a sealed CTL_PROFILE advances `updatedAt`
        // while deliberately carrying no prekey (ADR 020). Gating both on that one watermark let a sealed
        // presentation update suppress the cleartext frame that carries the prekey — and since a live spool
        // EVENT can outrun a heal-round pull, that race lands exactly when the prekey matters most.
        val stalePresentation = existing != null && version < existing.updatedAt
        val stalePrekey = existing != null && version < (existing.prekeyProfileAt ?: 0L)
        if (stalePresentation && stalePrekey) return
        // Immutable pin: a peer's key is bound to its nodeId, so once pinned it can only "change" via a
        // hash collision — an impersonation attempt. Refuse it: keep the first-pinned key and its verified
        // badge rather than let a swapped-in key inherit a verified contact (finding #14). A first profile
        // (existing?.pubKey == null) still pins normally; a same-key re-profile still updates name/status.
        val pinned = existing?.pubKey
        if (pinned != null && pinned != pubKey) {
            Log.w(TAG, "drop profile from ${env.senderId}: pinned key change refused (collision/impersonation?)")
            metrics.onDropped(DropReason.PIN_CHANGE_REFUSED)
            // Surfaced in the thread as well as counted: this is the one profile event a user could act
            // on, and a metric only a maintainer reads is the wrong place for it. Effectively unreachable
            // by design — a key that doesn't derive back to the sender's nodeId was already dropped
            // above, so arriving here needs a 128-bit collision or a corrupted pin — which is exactly why
            // it should be visible if it ever does happen rather than silent.
            savePeerNotice(env.senderId, StatusNotices.keyPinRefused(env.senderId, env.sentAt))
            return
        }
        val advertised = content.avatarHash
        // The stored avatarHash means "bytes are present locally": adopt the advertised hash only once
        // we hold its blob, otherwise keep the current avatar (if any) until the new one is fetched.
        val haveAvatar = advertised != null && blobStore.has(advertised)
        val prekey = verifiedPrekey(content, pubKey, env.senderId)
        // The pinned key is guaranteed unchanged here (a differing key was refused above), so carrying
        // the prior [verified] state through the upsert is safe.
        val base = existing ?: PeerEntity(env.senderId)
        val name = content.name.take(TextLimits.DISPLAY_NAME)
        peers.upsert(
            base.copy(
                // Clamp inbound too: our own cap only bounds what we originate, not what a peer sends.
                name = if (stalePresentation) base.name else name,
                status = if (stalePresentation) base.status else content.status.take(TextLimits.STATUS),
                pubKey = pubKey,
                verified = existing?.verified ?: false,
                deviceTag = content.deviceTag ?: existing?.deviceTag,
                avatarHash =
                    if (stalePresentation) {
                        base.avatarHash
                    } else {
                        resolveAvatarHash(advertised, haveAvatar, existing?.avatarHash)
                    },
                protoVersion = content.protoVersion ?: existing?.protoVersion,
                capabilities = content.capabilities ?: existing?.capabilities,
                // Never regress: a stale-presentation frame still reaches here for its prekey, and must not
                // drag the presentation watermark backwards on its way through.
                updatedAt = maxOf(base.updatedAt, version),
                // The ratchet prekey rides its OWN watermark: adopt a verified one, and CLEAR the pin when
                // this (prekey-newer) profile carries none — the peer downgraded, and keeping a stale prekey
                // would black-hole v2 sends they can no longer open.
                prekeyId = if (stalePrekey) base.prekeyId else prekey?.id,
                prekeyPub = if (stalePrekey) base.prekeyPub else prekey?.let { b64(it.pub) },
                prekeySig = if (stalePrekey) base.prekeySig else prekey?.let { b64(it.sig) },
                // Advances even when this profile carried NO prekey — the downgrade case this column was
                // always documented to cover. Leaving it null there would reopen the ordering hole the split
                // exposed: a re-served older profile would look prekey-newer than the clear and re-pin a key
                // the peer has stopped serving, black-holing v2 sends exactly as a stale pin does.
                prekeyProfileAt = if (stalePrekey) base.prekeyProfileAt else version,
                // A presentation field: gated with name/status, never with the prekey. Absent on the wire
                // reads false, which is how a flip back to off arrives.
                openToChat = if (stalePresentation) base.openToChat else content.openToChat,
                // The same, for the bound LoRa board: absent means unbound (or handed on), and clears.
                loraNode = if (stalePresentation) base.loraNode else content.loraNode,
            ),
        )
        applyPresentationFollowUps(env.senderId, existing, name, advertised, haveAvatar, version, stalePresentation)
        applyDeviceTagBlockContinuity(env.senderId, content.deviceTag)
        // The sender's key is now pinned: retransmit any DMs to them that were stuck awaiting it, and
        // re-send any group epoch seeds their outbox still shows unacked (their prekey may be new).
        flushPending(env.senderId)
        flushGroupKeys(env.senderId, false)
        // Cache this peer's verbatim signed profile so we can re-serve its key to a neighbor that asks, and
        // resolve any key request we (or a node we're relaying for) had outstanding for it.
        keyExchange.onProfilePinned(env.senderId, wire)
        // A pending contact-card intro to this sender can be sealed now that its prekey is pinned.
        onProfilePinned(env.senderId)
        // Replay any frames we parked from this sender while we couldn't verify them. Must run last: the
        // key is now pinned (so the replayed verifyInbound passes instead of re-parking) and any deviceTag
        // block has been applied (so a blocked sender is dropped on replay, not delivered). Replay bypasses
        // the router — no second flood, no SeenSet hit — and onDeliver's isNew/idempotent-save gates make a
        // later store-and-forward re-serve of the same frame a no-op.
        pendingInbound.release(env.senderId).forEach {
            metrics.onFrameReplayed()
            onDeliver(it.wire, it.env, it.fromNodeId, it.kind)
        }
    }

    /**
     * Verifies a profile's ratchet [app.getknit.knit.mesh.protocol.PrekeyInfo] against the sender's
     * (already self-certified) bundle: the detached Ed25519 signature must cover
     * [RatchetCrypto.spkSigningBytes]. Returns null — treat as "no prekey" — on absence or any
     * verification failure; a bad signature must not block the rest of the profile.
     */
    private fun verifiedPrekey(
        content: ProfileContent,
        pubKey: String,
        senderId: String,
    ): app.getknit.knit.mesh.protocol.PrekeyInfo? {
        val prekey = content.prekey ?: return null
        val bundle = PublicKeyBundle.decode(pubKey) ?: return null
        val valid =
            MessageCrypto.verify(bundle, prekey.sig, RatchetCrypto.spkSigningBytes(prekey.id, prekey.pub)) &&
                prekey.pub.size == RatchetCrypto.KEY_BYTES
        if (!valid) {
            Log.w(TAG, "ignore prekey from $senderId: bad signature or shape")
            return null
        }
        return prekey
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
     * A pulled blob just landed: if any non-direct peer advertised it as their avatar (see
     * [handleProfile]), point those peers at it now that the bytes are local and drop the previous one.
     * A no-op for attachment blobs, which no peer advertises.
     */
    private suspend fun adoptAdvertisedAvatar(hash: String) {
        val owners = advertisedAvatars.entries.filter { it.value == hash }.map { it.key }
        if (owners.isEmpty()) return
        // A pulled avatar is screened in MeshBlobStore.saveIncoming — it holds no message row, so nothing
        // local can claim it is audio and the screen always runs (knit/knit-next#30 closed the header-mime
        // skip that let a serving peer suppress it). With content filtering on, don't adopt it if flagged
        // explicit (the setting gates receive-side hiding, so off → adopt anyway).
        if (settings.contentFilteringEnabled.first() && imageScreening.isImageFlagged(hash)) {
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
    suspend fun onAvatarReceived(
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
        imageScreening.screenImage(hash, bytes)
        // With content filtering on, don't adopt an explicit avatar: leave the peer on its monogram
        // fallback and drop the blob (the setting gates receive-side hiding, so off → adopt anyway).
        if (settings.contentFilteringEnabled.first() && imageScreening.isImageFlagged(hash)) {
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
     *
     * The key is also what tells [MeshBlobStore.saveIncoming] a voice note is genuinely sealed and so worth
     * skipping; a key-less blob is plaintext and is screened there whatever mime claims it is.
     */
    private suspend fun screenObtainedAttachment(hash: String) {
        screenHeldAttachment(hash, messages.attachmentKeyForHash(hash), messages.attachmentMimeForHash(hash))
    }

    /**
     * Screens a held attachment whose row we now have, caching the verdict under the stored [hash] — the same
     * key the chat UI's flagged set uses, so a flagged attachment hides behind a tap-to-reveal. With a base64
     * [key] the stored blob is ciphertext: it is decrypted here and the plaintext screened by [mime] (a picture
     * as an image; a link-preview card as a card, picture and text). Without a key only one shape needs this
     * path: a **room card** whose blob was relayed before its row arrived, which [MeshBlobStore.saveIncoming]
     * could only screen as an image (a no-op on a container) — a plaintext image was screened there on arrival
     * and is left alone. A no-op when the blob isn't stored yet or decryption fails; the screening service is
     * idempotent per hash, so a repeat call is harmless, and it always caches a verdict (the content-filtering
     * setting gates only the receive-side hiding at display time, not the scan).
     */
    private suspend fun screenHeldAttachment(
        hash: String?,
        key: String?,
        mime: String?,
    ) {
        if (hash == null) return
        val stored = blobs.bytes(hash) ?: return
        val plain =
            when {
                key != null -> AttachmentCrypto.open(stored, b64d(key)) ?: return
                mime == LinkPreviewBlob.MIME -> stored
                else -> return
            }
        imageScreening.screenAttachment(hash, plain, mime, isRoom = key == null)
    }

    /**
     * A blob just landed: if a stored message names it as a **voice note**, derive its playing time and
     * waveform from the audio and record them on every row that references it. This is the receiving half of
     * a deliberate symmetry — the sender derives the same two values from the same bytes at ingest, using the
     * same [VoiceAudio] implementation, so the two ends agree by construction and neither value ever has to
     * ride the wire (see `docs/WIRE_COMPAT.md`).
     *
     * Decrypts exactly as [screenHeldAttachment] does when the attachment is E2E; a plaintext blob is
     * described as-is. A no-op for anything that isn't audio, and for a blob no message row claims (a relayed
     * attachment, or an avatar). Never throws — a waveform we couldn't compute is a flat bubble, not a
     * dropped delivery, and `rules/mesh.md` requires inbound handlers to stay silent on failure.
     */
    private suspend fun deriveObtainedVoiceMeta(hash: String) {
        runCatching {
            if (!VoiceAudio.isVoice(messages.attachmentMimeForHash(hash))) return
            val stored = blobs.bytes(hash) ?: return
            val key = messages.attachmentKeyForHash(hash)
            val audio = if (key == null) stored else AttachmentCrypto.open(stored, b64d(key)) ?: return
            val described = VoiceAudio.describe(audio)
            if (described.isEmpty) return
            messages.setVoiceMeta(hash, described.durationMs, described.peaks)
        }.onFailure { Log.w(TAG, "voice metadata derivation failed: ${it.javaClass.simpleName}") }
    }

    private companion object {
        // Same tag as MeshManager on purpose: these inbound verify/drop log lines are grepped in field
        // diagnostics, so the extraction must not change them.
        const val TAG = "MeshManager"

        /** Most acked ids applied from one sealed CTL_RECEIPT — 2× AckSync's send-side batch cap. */
        const val MAX_RECEIPT_ACKS = 2 * AckSync.MAX_BATCH_ACKS

        /**
         * The v2 DM failures that feed the session-reset heuristic (ADR 023) — the ratchet outcomes that
         * can mean "this pair's state has diverged".
         *
         * BAD_HEADER is out because a malformed frame says nothing about our session. DUPLICATE is out as
         * of ADR 024: a consumed chain index is proof we *did* decrypt that index, so the frame is a
         * re-serve of our own delivered history, and a whole re-served backlog carries distinct ids that
         * walk straight past the distinct-frame rule. It was added as a proxy for the peer-restarted-its-
         * chain desync, which the three purge sites now fix at the source.
         */
        val RESET_TRIGGERING_DROPS =
            setOf(
                DropReason.RATCHET_NO_SESSION,
                DropReason.RATCHET_EPOCH_GONE,
                DropReason.RATCHET_AEAD_FAIL,
            )
    }
}

/**
 * `meshPostRefusedByReason` key for a heard radio post whose resolved contact is blocked — the pipeline's own
 * reason, beside the transport's `PublicChannelPolicy.Refusal` names.
 */
internal const val MESH_POST_BLOCKED_CONTACT = "BLOCKED_CONTACT"
