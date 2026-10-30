package app.getknit.knit.mesh

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room3.withWriteTransaction
import app.getknit.knit.TextLimits
import app.getknit.knit.badge.Badges
import app.getknit.knit.clampToUtf16
import app.getknit.knit.data.AnimatedImage
import app.getknit.knit.data.AttachmentIdentities
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.QuoteVerifier
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.AttachmentFetchRow
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.REPLY_SNIPPET_MAX
import app.getknit.knit.data.message.StatusNotices
import app.getknit.knit.data.message.StyleStore
import app.getknit.knit.data.message.hiddenByFilter
import app.getknit.knit.data.message.reactionSnippet
import app.getknit.knit.data.message.videoInfo
import app.getknit.knit.data.message.withReply
import app.getknit.knit.data.message.withVideo
import app.getknit.knit.data.message.writtenBy
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.scopeconfig.ScopeConfigRepository
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.data.video.VideoAttachment
import app.getknit.knit.data.video.VideoFrames
import app.getknit.knit.data.video.isPlausible
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.identity.NodeId
import app.getknit.knit.isValidReactionEmoji
import app.getknit.knit.location.GeoUri
import app.getknit.knit.mesh.crypto.AesGcm
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageContentV2
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.XeddsaVerify
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import app.getknit.knit.mesh.crypto.readsCryptoV3
import app.getknit.knit.mesh.crypto.sealBytes
import app.getknit.knit.mesh.lora.MeshtasticProto
import app.getknit.knit.mesh.protocol.BadgeEntry
import app.getknit.knit.mesh.protocol.BlobCancelContent
import app.getknit.knit.mesh.protocol.BlobHasContent
import app.getknit.knit.mesh.protocol.BlobHaveContent
import app.getknit.knit.mesh.protocol.BlobProgressContent
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.CommonsPost
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
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.ReactionPayload
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.ScopeConfigPayload
import app.getknit.knit.mesh.protocol.StyleSpans
import app.getknit.knit.mesh.protocol.TalkSeal
import app.getknit.knit.mesh.protocol.TransferPayload
import app.getknit.knit.mesh.protocol.TypingContent
import app.getknit.knit.mesh.protocol.VideoInfo
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.protocol.isStorable
import app.getknit.knit.mesh.protocol.mention
import app.getknit.knit.mesh.spool.ScopeConfigPolicy
import app.getknit.knit.mesh.spool.ScopeSync
import app.getknit.knit.mesh.spool.SpoolUrl
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.normalizeSingleLine
import app.getknit.knit.notifications.ConversationFaces
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.notifications.incomingNotification
import app.getknit.knit.notifications.mentionNotification
import app.getknit.knit.notifications.reactionNotificationKey
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
 *    applied first. The group analogue: [reconcileGroup] commits the roster, then replays any seed ctl DM
 *    parked in [PendingGroupKeys] because it arrived for a group we did not hold yet.
 *  - **no-throw-out-of-onDeliver** — [verifyInbound]/[decrypt] are [runCatchingUnlessCancelled]-wrapped so a
 *    failure drops the frame locally but never throws, letting the router still relay it (it runs after onDeliver
 *    returns). The one throw they let out is the caller's own cancellation, never counted as a drop. No caller
 *    today sees it: the router runs an admitted delivery under `NonCancellable`, and so do `MeshManager`'s custody
 *    replays for each frame. It is the rule for a caller added later (ADR 2026-10.f82h).
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
    // Learns the plaintext name of each sealed attachment as its bytes arrive, and shows a room post of one this
    // phone holds sealed from its own copy (ADR 2026-10.mptm). Stateless over [messages] and [blobs]; the default
    // is what every test wants.
    private val identities: AttachmentIdentities = AttachmentIdentities(messages, blobs),
    // Judges an inbound reply's quote against the original it names (ADR 2026-10.gpa3). The default labels a
    // rebuilt quote in English; the app hands MeshManager one that reads the reader's own strings.
    private val quotes: QuoteVerifier = QuoteVerifier(messages),
    // What a notification says in place of a body it cannot show, read per notification so a language change
    // lands at once. The default is English, which every test wants; the app reads the reader's own strings.
    private val notificationLabels: () -> NotificationLabels = { NotificationLabels.ENGLISH },
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
    // Seeds that outran their group's first frame, parked before the ratchet commit and replayed by
    // [reconcileGroup] once the roster lands. Defaults for the rigs that never see a group-key ctl.
    private val pendingGroupKeys: PendingGroupKeys = PendingGroupKeys(metrics = metrics),
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
    // The sealed receipts we minted for DMs to us, so a re-delivery in the same era mints no other (#176, ADR
    // 2026-10.8hn6); [holdsLive] answers whether one is still live in our own custody (`ForwardStore.frame`, never `has`).
    // The rigs' default holds nothing, so every re-delivery re-acks there as it always did.
    private val dmReAcks: DmReAckLedger = DmReAckLedger(),
    private val holdsLive: suspend (frameId: String, now: Long) -> Boolean = { _, _ -> false },
    private val flushPending: suspend (String) -> Unit,
    private val classifyText: suspend (String, String, Boolean) -> Boolean,
    // Re-seals our recent unacked DMs to a peer whose ratchet session was just replaced (the recovery
    // half of an inbound reset) — MeshManager.resealRecentDmsTo, lambda-mediated like originate.
    private val resealUnacked: suspend (String) -> Unit = {},
    // Lets the sender's re-seal of an unopenable DM (same id, fresh seal) past the router's dedup — see
    // `MeshRouter.reopen`. Default no-op for tests that build the pipeline without a router.
    private val reopenFrame: (String) -> Unit = {},
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
    // The DM half of the same heal (MeshManager.replayCustodiedSeedDms): on first sight of a group, re-enters
    // our own custody's undelivered chat DMs from its roster, so a seed that was parked in memory and lost
    // with the process is adopted from the copy custody kept. The second argument is the frame id to skip —
    // the seed whose own roster is creating the row (pinRosterFromSeed), already custodied and still mid-flight.
    private val replaySeedCustody: suspend (groupId: String, except: String?) -> Unit = { _, _ -> },
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
    // A verified `profile` under OUR OWN node id arrived, with its publish stamp (`sentAt`) — the clone
    // watch's one input (mesh/CloneWatch, ADR 2026-09.ypcc): a stamp this phone never minted proves the
    // identity is running elsewhere. Lambda-mediated like the rest; the pipeline itself keeps dropping the frame.
    private val onSelfProfile: suspend (sentAt: Long) -> Unit = {},
    // A v2 DM from this sender opened and committed; `initEph` is the X3DH init its ratchet header still
    // carried, i.e. the sender has not yet seen a frame of ours (IntroSync.onPeerFrameOpened), or null, and
    // `resetFlagged` whether that header carried FLAG_RESET. Runs post-commit, outside the ratchet lock, since
    // the answer it may trigger seals a frame of its own.
    private val onPeerFrameOpened: suspend (senderId: String, initEph: ByteArray?, resetFlagged: Boolean) -> Unit = { _, _, _ -> },
    // A sealed CTL_TRANSFER landed (transfer/TransferManager.onSignal): direct-transfer signaling, handed on
    // post-commit like the rest. True when it admitted a live incoming OFFER, which is what earns a notification.
    private val onTransferCtl: suspend (senderId: String, payload: TransferPayload, sentAt: Long) -> Boolean = { _, _, _ -> false },
    // A sealed CTL_CALL landed (call/CallManager.onSignal; experimental, docs/VOICE_CALLS.md): voice-call signaling,
    // handed on post-commit from an accepted thread only. The call layer owns ringing and every notification, and the
    // hook must return without waiting — it runs on this frame's inbound path.
    private val onCallCtl: suspend (senderId: String, payload: CallPayload, sentAt: Long) -> Unit = { _, _, _ -> },
    // A walkie chunk's plaintext ([handleTalk], docs/VOICE_CALLS.md §3.11), from an accepted contact once its seal
    // opened. The call layer decides whether it belongs to a session; the hook returns without waiting, like the above.
    // The hop is the radio link the chunk came over, null for a spool's cast; kind is that link's transport.
    private val onCallTalk: suspend (
        senderId: String,
        chunk: ByteArray,
        sentAt: Long,
        frameBytes: Int,
        hop: String?,
        kind: TransportKind,
    ) -> Unit =
        { _, _, _, _, _, _ -> },
    // A commons' advertised name for its notification title (`CommonsStore.find`), null when the operator
    // set none — the notifier then uses the generic room title.
    commonsTitle: suspend (conversationId: String) -> String? = { null },
    // Every frame that passed verifyInbound, with the hop that handed it to us and its plane — the radio mesh's
    // indirect-reach tracker (MeshManager → RelayedPresence.note, ADR 2026-10.fw8g), which applies its own rule.
    // Presentation only: it sees the frame after the gate and decides nothing about it.
    private val noteVerified: suspend (WireEnvelope, RelayEnvelope, fromNodeId: String, TransportKind) -> Unit =
        { _, _, _, _ -> },
    // The scope configs a sealed CTL_SCOPE_CONFIG writes (docs/SPOOL_PROTOCOL.md §5), inside the ratchet commit that
    // carried it. Over [db] by default, which is what MeshManager and every rig want.
    private val scopeConfigs: ScopeConfigRepository = ScopeConfigRepository(db),
) {
    // The title and avatar a notification wears, resolved from the conversation alone — the resolver the
    // conversation shortcuts share, so a refreshed shortcut and the next notification agree (ADR 2026-10.jbsa).
    private val conversationFaces = ConversationFaces(groups, peers, blobs, commonsTitle)

    // nodeId -> avatar hash a non-direct peer advertised but whose bytes we're still pulling, so a blob
    // arriving via the multi-hop BlobExchange can be attributed back to the peer that advertised it.
    private val advertisedAvatars = ConcurrentHashMap<String, String>()

    /**
     * A pulled blob just landed (the [BlobExchange] `onObtained` hook): attribute it to whoever advertised
     * it — a peer's avatar, a group's photo — and, if a stored message names it, run [describeHeldAttachment]
     * with the key and MIME that row gives it. The three are order-independent and each is a no-op when the hash
     * isn't theirs. This is the wrapper [MeshManager] wires as `blobExchange`'s onObtained callback.
     */
    suspend fun onObtained(hash: String) {
        adoptAdvertisedAvatar(hash)
        settleArrivedGroupPhoto(hash)
        describeHeldAttachment(hash, messages.attachmentKeyForHash(hash), messages.attachmentMimeForHash(hash))
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
        noteVerified(wire, env, fromNodeId, kind)
        // Carry every floodable frame we see — store-and-forward, so we can re-offer it to a neighbor
        // that joins later. That includes a DM addressed to US: with sealed receipts nobody vaccine-
        // purges (a carrier can't read them), so the delivered DM stays live in every carrier's digest
        // for its full TTL — if the recipient didn't hold it too, every digest exchange would re-cue a
        // re-serve of a frame we already delivered, and each copy past a SeenSet lapse would reach the
        // exists-gate. Holding our own copy is what makes digests converge; a cleartext ack still
        // purges it right back out (see acknowledge's self-vaccinate). Group messages are carried for
        // members who may be offline whether or not we're a member; broadcast + cleartext metadata
        // frames back-fill ambient state. Runs before handleChat returns early and before the router
        // schedules the relay, so the copy is durable pre-flood. A point-to-point frame (relay = false) is
        // otherwise delivered to its addressee and stops — it is *addressed*, so nobody else is meant to hold
        // it (a broadcast/group delivery tick, a sealed ctl DM). A `profile` is the one custodial type that
        // arrives in that wrapper without being addressed to anyone: it names no recipient and no group, it
        // is ambient state every node is meant to hold, and it self-certifies on its own in-band key — so it
        // is custodied wherever it is delivered from. Today that means `KeyExchange.serve`, which answers a
        // keyreq point-to-point by design; without this the requester pinned the key but stored nothing, its
        // router had marked the id seen, and the holder's custody re-serve was deduped for the rest of the
        // SeenSet window — the three digests disagreed for up to eleven minutes after every key request, and
        // the cue plane raised a reconcile with nothing to transfer (ADR 2026-09.7bu7, issue #49).
        if (env.isStorable() && (wire.relay || env.type == FrameType.PROFILE)) {
            forwardSync.onSeen(wire, env, ForwardStore.ORIGIN_RELAY, from = fromNodeId)
        }
        // Multi-hop coordination-plane fan-out: re-fan a small flood frame to our own neighbors so it spreads
        // across the mesh at message-plane speed (no data path), not just one hop from the originator. onDeliver
        // runs once per first-seen frame (MeshRouter gates on its SeenSet), so each node re-fans it exactly once
        // and the echo dies out — no storm; fastFanout size-gates, so anything too big just no-ops. Only flood
        // frames re-fan; a point-to-point frame (relay = false, e.g. a broadcast receipt) reaches its addressee
        // and goes no further.
        if (wire.relay && shouldFastFanout(env)) transport.fastFanout(wire)
        // The targeted sibling ([shouldFastSend]): a bridge node hands a sealed DM-form frame straight to its
        // addressee over the coordination plane, so a pair joined only through us converges at message-plane
        // speed instead of waiting on our own NDP. Same once-per-first-seen discipline as the fan-out above,
        // so the echo dies out; the `!= fromNodeId` guard is the split horizon — never bounce a frame back at
        // the hop that just handed it to us.
        if (wire.relay) {
            env.recipientId?.let { if (shouldFastSend(env) && it != fromNodeId) transport.fastSend(wire, Peer(it)) }
        }
        // The long-range sibling: a sealed DM-form frame re-fans over a plane with no data path (LoRa) for the
        // same once-per-node reason; that plane's own sig-keyed dedup keeps a frame heard over it from bouncing
        // straight back onto it.
        if (wire.relay && shouldLongRangeFanout(env)) transport.longRangeFanout(wire)
        // Completes the no-throw contract: a per-type handler must NEVER throw out of onDeliver. The router
        // schedules the relay only *after* this returns, so an escape would silently stop this node forwarding
        // the frame (a propagation black hole). verifyInbound/decrypt are already guarded individually; this
        // backstops every remaining handler — notably deliverChat's on-device moderation classify(), the runtime
        // sibling of the model-load gap fixed in e18b1f4 (a corrupt/failed classify would otherwise crash both
        // delivery and the shared block-on-send path). A swallowed error is logged (it's a should-never-happen bug
        // path). It is not healed by a re-serve: the custody capture above already ran, so our digest folds the
        // frame and no peer offers it again. A caller's cancellation is rethrown rather than logged here, and the
        // router's own deliveries never see one (ADR 2026-10.f82h).
        runCatchingUnlessCancelled { dispatchByType(env, wire, fromNodeId, kind) }
            .onFailure { Log.w(TAG, "handler error on ${env.type} ${env.id} from ${env.senderId}: ${it.message}") }
    }

    /**
     * The unsigned blob traffic, all point-to-point on the link it came in on: a neighbour's ask, and the holder query
     * and its answer (ADR 2026-10.6e5t). For the query and the answer the link's peer is the party, so one naming somebody
     * else as its sender is a stray, and nobody acts on it.
     */
    private suspend fun handleBlobFrame(
        env: RelayEnvelope,
        fromNodeId: String,
        plane: DeliveryPlane,
    ) {
        when {
            env.type == FrameType.BLOB_REQ -> {
                decodeCounted<BlobReqContent>(env.payload)?.let { req ->
                    blobExchange.onRequest(
                        req.hash,
                        fromNodeId,
                        req.offset,
                        fastOnly = req.fastOnly == true,
                        pull = req.pull,
                        until = req.until,
                    )
                }
                metrics.onBlobAskHandled()
            }

            env.senderId != fromNodeId -> {}

            env.type == FrameType.BLOB_HAVE -> {
                decodeCounted<BlobHaveContent>(env.payload)?.let { blobExchange.onHaveQuery(it.hashes, fromNodeId, env.id) }
            }

            env.type == FrameType.BLOB_PROGRESS -> {
                decodeCounted<BlobProgressContent>(env.payload)?.let { blobExchange.onProgressReport(it, fromNodeId) }
            }

            env.type == FrameType.BLOB_CANCEL -> {
                decodeCounted<BlobCancelContent>(env.payload)?.let { blobExchange.onCancel(it.hash, fromNodeId) }
            }

            else -> {
                // Only an answer that came in on a phone-to-phone link proves a hold (ADR 2026-10.vy27).
                decodeCounted<BlobHasContent>(env.payload)?.let { blobExchange.onHaveAnswer(it, fromNodeId, plane.shortRange) }
            }
        }
    }

    /**
     * [WireCodec.decodeChecked], counting a payload that won't decode, or holds text that isn't UTF-8, as
     * [DropReason.PAYLOAD_DECODE_FAILED] — once per frame, where its handler reads it after [verifyInbound] (and a
     * profile's in-band key, which the verify itself needs). A re-read of a payload already counted, and the carry gate,
     * which runs before any signature check, use the uncounted [WireCodec.decodeChecked], so carried traffic and
     * unsigned junk don't swamp the count a port's regressed encoder should show in Diagnostics (AND-12).
     */
    private inline fun <reified T> decodeCounted(payload: ByteArray): T? =
        WireCodec.decodeChecked<T>(payload).also { if (it == null) metrics.onDropped(DropReason.PAYLOAD_DECODE_FAILED) }

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
        // What the source's *plane* is read for: a delivery receipt records which plane it reached us on, so the
        // ✓✓ can say how the message got there (MessageEntity.receivedVia), and a holder's answer to a proof query
        // proves a hold only over a short-range link (ADR 2026-10.vy27). Carry, relay and convergence stay
        // plane-blind, as ADR 019 requires.
        val plane = planeOf(fromNodeId, kind)
        when (env.type) {
            FrameType.CHAT -> {
                handleChat(env, plane, signed = wire.sig.isNotEmpty(), source = InboundFrame(wire, env, fromNodeId, kind))
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

            FrameType.BLOB_REQ, FrameType.BLOB_HAVE, FrameType.BLOB_HAS, FrameType.BLOB_PROGRESS, FrameType.BLOB_CANCEL -> {
                handleBlobFrame(env, fromNodeId, plane)
            }

            FrameType.KEY_REQ -> {
                decodeCounted<KeyReqContent>(env.payload)?.let { keyExchange.onRequest(it.nodeIds, fromNodeId) }
            }

            FrameType.TYPING -> {
                handleTyping(env)
            }

            // A commons post reaches this device through the spool plane's own door ([deliverCommonsPost]),
            // never a radio: a copy met on a link is a stray (a member re-flooding by hand) and is relayed
            // like any unknown type by the router and delivered by nobody. Named so the `else` below stays
            // for types nobody has minted yet.
            FrameType.COMMONS -> {}

            FrameType.TALK -> {
                handleTalk(env, frameBytes = wire.signed.size, hop = fromNodeId.takeIf { plane.shortRange }, kind = kind)
            }

            else -> {}
        }
    }

    /**
     * One walkie chunk (docs/VOICE_CALLS.md §3.11): a [FrameType.TALK] frame addressed to us, its payload a [TalkSeal]
     * (or a [ChatContent], for a header that form cannot carry). Unsigned over the radios by design, so the ratchet's
     * AEAD is what authenticates it: nothing acts on it before it opens. The copy a spool casts (§2.7) is signed, and the
     * signed door has verified it by now, but a failed open is never a reset trigger either way: a chunk is live or lost,
     * a raced or duplicated one fails to open as a matter of course, and that must not cost the pair its session. Opened
     * and committed like any DM, from an accepted contact only, and its plaintext handed to the call layer. Never
     * custodied ([onDeliver] carries custodial types alone), never acked, and never a message.
     */
    @Suppress("ReturnCount") // a ladder of refusals ahead of the one hand-off
    private suspend fun handleTalk(
        env: RelayEnvelope,
        frameBytes: Int,
        hop: String?,
        kind: TransportKind,
    ) {
        val me = identity.nodeId()
        if (env.group != null || env.recipientId != me || env.senderId == me) return
        if (env.senderId in settings.blockedNodeIds.first() || !isAccepted(env.senderId, me)) return
        val sealed = talkSealOf(env.payload)
        if (sealed == null) {
            metrics.onDropped(DropReason.RATCHET_BAD_HEADER)
            return
        }
        val peerIkPub = pinnedDhKey(env.senderId) ?: return
        val aad = MessageCrypto.header(env.id, env.senderId, env.sentAt, me)
        val now = System.currentTimeMillis()
        val peek = ratchet.peekOpen(me, env.senderId, peerIkPub, sealed.header, sealed.nonce, sealed.ct, aad, now)
        if (peek !is RatchetEngine.OpenOutcome.Opened) return onRatchetFailure(env, peek, me, now, authenticated = false)
        val committed =
            db.withWriteTransaction { ratchet.commitOpen(me, env.senderId, peerIkPub, sealed.header, sealed.nonce, sealed.ct, aad, now) {} }
        if (!committed) return
        onPeerFrameOpened(env.senderId, sealed.header.init?.eph, sealed.header.flags and RatchetHeader.FLAG_RESET != 0)
        onCallTalk(env.senderId, peek.plaintext, env.sentAt, frameBytes, hop, kind)
    }

    /** A talk frame's seal: the ratchet header, the nonce (v2 only; v3 derives it) and the ciphertext. */
    private class TalkSealed(
        val header: RatchetHeader,
        val nonce: ByteArray?,
        val ct: ByteArray,
    )

    private fun talkSealOf(payload: ByteArray): TalkSealed? {
        TalkSeal.decode(payload)?.let { (r, ct) -> return TalkSealed(r, null, ct) }
        val enc =
            WireCodec
                .decodePayload<ChatContent>(payload)
                ?.enc
                ?.takeIf { EncEnvelope.isDmRatchetVersion(it.v) && it.g == null && nonceShapeValid(it) }
        val r = enc?.r ?: return null
        return TalkSealed(r, enc.nonce.takeIf { enc.v != EncEnvelope.VERSION_DM_V3 }, enc.ct)
    }

    /**
     * The commons door's dispatch (spec §7.4): a [CommonsPost] carries exactly one content (`ScopeFrames` refused
     * any other shape), a chat for the room's thread or a member's reaction to one of its posts.
     */
    internal suspend fun deliverCommons(
        env: RelayEnvelope,
        post: CommonsPost,
        conversationId: String,
    ) {
        post.chat?.let { return deliverCommonsPost(env, it, conversationId) }
        post.reaction?.let { applyCommonsReaction(env, it, conversationId) }
    }

    /**
     * A member's reaction pulled from a commons (C-7.4-13): applied latest-stamp-wins exactly as every other
     * reaction ([ReactionRepository.apply]), so the room's unordered listing and a restart's re-pull converge. It
     * reaches this room's posts only: a target held in another thread is someone naming an id from elsewhere, and
     * is dropped. A target not held yet is kept, as on every reaction path — the post may be later in the listing.
     */
    internal suspend fun applyCommonsReaction(
        env: RelayEnvelope,
        reaction: ReactionContent,
        conversationId: String,
    ) {
        // The block gate, as on the post's own door: the carry gate never reads the block list (ADR 010).
        if (env.senderId in settings.blockedNodeIds.first()) return
        if (reaction.emoji != null && !isValidReactionEmoji(reaction.emoji)) {
            metrics.onDropped(DropReason.REACTION_REFUSED)
            return
        }
        val target = messages.find(reaction.messageId)
        if (target != null && target.conversationId != conversationId) return
        val row = ReactionEntity(reaction.messageId, env.senderId, reaction.emoji, clampFuture(env.sentAt))
        if (reactions.apply(row)) noticeReaction(env, row)
    }

    /**
     * The commons door (spec §7.4): a post the spool plane pulled from a room this device joined, already
     * opened under the room's key and authenticated by [canCarry] against the author's pinned key. It is
     * delivered as the room post it is — the ordinary chat shell, the conversation the room's id names,
     * the Internet as its plane, room moderation — and **never** custodied or relayed: the spool is the
     * room's store. It *is* acknowledged, the group way ([acknowledge]): a delay-tolerant tick that
     * batches and escalates into one sealed receipt DM to the author, riding that pair's own scope —
     * never a receipt frame in the room, where N of them per post would evict the posts.
     */
    internal suspend fun deliverCommonsPost(
        env: RelayEnvelope,
        chat: ChatContent,
        conversationId: String,
    ) {
        // The one block gate on this door: the carry gate never reads the block list (ADR 010 — blocking is a
        // delivery-path decision, so the same post is accounted for the scope like any member's), and a
        // blocked member's post is dropped here, exactly as [handleChat] drops their radio-borne chat.
        if (env.senderId in settings.blockedNodeIds.first()) return
        deliverChat(
            env = env,
            content = chat,
            me = identity.nodeId(),
            conversationId = conversationId,
            plane = DeliveryPlane.Internet,
        )
    }

    /**
     * The plane an inbound frame arrived on — the single place that maps a delivery source onto a
     * [DeliveryPlane], and the only reader of [InboundFrame.kind] that decides anything (the indirect-reach
     * tracker reads it too, for presentation only — ADR 2026-10.fw8g).
     *
     * A spool-tagged source names the Internet plane (it is never a neighbour, so the tag lives in the
     * source id); a frame off the LoRa board names [DeliveryPlane.LoRa] (ADR 040 — kilometre-range, slow,
     * photo-less: worth saying); each phone radio names itself, [DeliveryPlane.Bluetooth] or
     * [DeliveryPlane.WifiAware] (ADR 2026-10.e42d: the developer view and the field log's latency lines tell
     * them apart, the chat reads both as nearby). A kind that names no radio ([TransportKind.Other]: fakes,
     * the demo transport, the lab) stays [DeliveryPlane.Nearby]. A reader that means "a short-range radio"
     * asks [DeliveryPlane.shortRange], never `== Nearby`. Keep this map in the pipeline — `data/` must not
     * learn `mesh/`'s [TransportKind].
     */
    private fun planeOf(
        fromNodeId: String,
        kind: TransportKind,
    ): DeliveryPlane =
        when {
            ScopeSync.isSpoolSource(fromNodeId) -> DeliveryPlane.Internet
            kind == TransportKind.LoRa -> DeliveryPlane.LoRa
            kind == TransportKind.Bluetooth -> DeliveryPlane.Bluetooth
            kind == TransportKind.WifiAware -> DeliveryPlane.WifiAware
            else -> DeliveryPlane.Nearby
        }

    /**
     * Records a best-effort "now typing" cue in [typingTracker] so the chat UI can show it. The conversation is
     * derived the same way [deliverChat] scopes a message: a group by the compact [TypingContent.groupId] (a group
     * we don't know locally is ignored — nothing to show it in), a DM addressed to us keyed by the other party, and
     * the broadcast room otherwise. A DM not addressed to us and our own cue looping back are dropped. Auto-expires
     * in the tracker; a real message from the same sender clears it early ([deliverChat] → [TypingTracker.onMessageFrom]).
     *
     * One rule for every plane, a radio link or a relay's signal lane (docs/SPOOL_PROTOCOL.md §7.8): nothing is shown
     * while the person has typing indicators off, from a blocked sender, or in a conversation that is still a message
     * request. A cue is never presence, so none of this touches the peer's reach.
     */
    private suspend fun handleTyping(env: RelayEnvelope) {
        val me = identity.nodeId()
        if (env.senderId == me) return
        if (!settings.typingIndicatorsEnabled.first() || env.senderId in settings.blockedNodeIds.first()) return
        // Every port encodes a DM or room cue as `{}`, so a payload that won't decode is malformed, never a DM or
        // room cue: counted and dropped rather than read as one (a group cue whose groupId broke would light the room).
        val groupId = (decodeCounted<TypingContent>(env.payload) ?: return).groupId
        val conversationId =
            when {
                groupId != null -> if (groups.find(groupId) == null) return else groupId
                env.recipientId == null -> Conversations.NEARBY
                !Conversations.isForMe(env.recipientId, me) -> return
                else -> env.senderId
            }
        if (!isAccepted(conversationId, me)) return
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
        val ackId = decodeCounted<ReceiptContent>(env.payload)?.ackId ?: return
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
     * there is no key to verify with (an unpinned non-profile sender, or a malformed in-band key). [countDecode] is
     * true only from [verifyInbound], so a profile whose payload won't decode counts once, not again at the carry gate.
     */
    private suspend fun verifierBundle(
        env: RelayEnvelope,
        countDecode: Boolean,
    ): PublicKeyBundle? =
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
                val profile =
                    if (countDecode) {
                        decodeCounted<ProfileContent>(env.payload)
                    } else {
                        WireCodec.decodeChecked<ProfileContent>(env.payload)
                    }
                profile?.pubKey?.let { PublicKeyBundle.decode(it) }
            }

            else -> {
                peers.find(env.senderId)?.pubKey?.let { PublicKeyBundle.decode(it) }
            }
        }

    /**
     * The other shape that may arrive unsigned: a walkie chunk addressed to us by someone else (§3.11), flooded or not.
     * The [isUnsignedTickShape] argument again: its AEAD binds the id, sender, sentAt and recipient, so [handleTalk]'s
     * open is the whole check, and nothing acts on it before that.
     */
    private suspend fun isTalkShape(env: RelayEnvelope): Boolean {
        val me = identity.nodeId()
        return env.type == FrameType.TALK && env.group == null && env.recipientId == me && env.senderId != me
    }

    /**
     * The one chat shape that may arrive unsigned (ADR 059): `relay = false` DM-form chat addressed to us
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
     * haven't received yet is dropped. The blob traffic is unsigned by design and taken point-to-point only
     * ([FrameType.isUnsignedPointToPoint]), and so is the v3 live-link delivery tick (ADR 059) — for that one
     * only its *shape* is judged here ([isUnsignedTickShape]); its ratchet AEAD is the authenticator, enforced
     * in [decryptAndDeliver]. An unknown
     * future type falls through to the pinned-key path: if it verifies we still don't deliver it (the
     * [onDeliver] dispatch has no handler) but the router relays it onward.
     *
     * Wrapped in [runCatchingUnlessCancelled] so it NEVER throws out of [onDeliver]: any failure returns false =
     * "drop locally", and the router still schedules the relay (it runs after onDeliver returns). Only the
     * caller's own cancellation is rethrown (ADR 2026-10.f82h).
     */
    private suspend fun verifyInbound(
        env: RelayEnvelope,
        wire: WireEnvelope,
        fromNodeId: String,
        kind: TransportKind,
    ): Boolean =
        runCatchingUnlessCancelled {
            // The blob traffic is unsigned and point-to-point only: the ask, the holder query and its answer (ADR
            // 2026-10.6e5t), the receiver's progress report and the asker's cancel (ADR 2026-10.nsr7). No build marks one
            // for relay, so a flooded copy is nobody's: an ask honoured at every hop it reached would have each holder
            // serve the blob to the neighbour that handed it on, and each other node pull it through. Ahead of the key
            // path, so none ever spends a key request; the router never floods one either (MeshRouter.scheduleRelay).
            if (FrameType.isUnsignedPointToPoint(env.type)) {
                if (!wire.relay) return true
                metrics.onDropped(DropReason.POINT_TO_POINT_FLOODED)
                return false
            }
            // A self frame — one of OUR own frames looping back, after a neighbor carried it and (once our SeenSet
            // window lapsed) re-served it — is authenticated like any other via [verifierBundle], which resolves
            // our identity's own bundle for it. It USED to be dropped here as a silent no-op ("we already have
            // it"), but that assumption breaks after a DB wipe empties custody: peers still hold
            // our sends and re-serve them, yet the drop stopped [onDeliver]'s carry from re-custodying them, so
            // the content digest never reconverged. Letting it through re-carries it; delivery is idempotent
            // ([deliverChat]'s isNew gate, own-message notifications already skipped), so a duplicate is harmless.
            val bundle = verifierBundle(env, countDecode = true)
            if (bundle == null) {
                metrics.onDropped(DropReason.NO_SENDER_KEY)
                // Try to recover the sender's key so future frames from it verify (the inbound key-request
                // path). Excludes a key request itself (don't request keys for key-requesters — no recursion),
                // a profile (its key rides in-band, so a null bundle there means a malformed key, not an absent
                // pin that a request could fill), and a typing cue (ephemeral best-effort presence — with no
                // pinned key we can't render the peer's avatar anyway, so drop it silently rather than spend a
                // key request / park slot on a frame that's worthless a moment later). Safe inside
                // verifyInbound's guard — never throws.
                // … and a commons post: it never arrives here on purpose (the spool door verifies and
                // delivers it), so one on a radio is a stray copy that no key request should be spent on.
                if (env.type !in NO_KEY_REQUEST_TYPES) {
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
                if (isUnsignedTickShape(env, wire) || isTalkShape(env)) return true
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
     * Bounds a sender-supplied clock before it becomes a local last-writer-wins watermark. Every such
     * clock — a profile version, a reaction's stamp, a group's name/photo clock, a member's leave/rejoin —
     * is the sender's self-attested, unverifiable number, and stored raw it is a permanent wedge: one
     * `Long.MAX_VALUE` outranks every honest update after it, including the sender's own. Pulled back to
     * [Protocol.MAX_FUTURE_SKEW_MS] past our own clock, the worst a far-future stamp can do is win for
     * that window; honest skew inside it is kept as-is, so honest peers never see the clamp. The
     * local-ordering complement of [app.getknit.knit.data.forward.ForwardRepository.store]'s refusing
     * half — delivery runs regardless of custody, so that guard alone protects nothing here. Past the
     * window this turns sender order into arrival order for that one sender (a re-served older frame from
     * a peer whose clock runs far ahead can briefly win until their next update) — bounded and
     * self-healing, which the wedge is not. Never applied to the envelope itself: the era gate, ack ids and
     * signatures need the raw value.
     */
    private fun clampFuture(stamp: Long): Long = minOf(stamp, clock() + Protocol.MAX_FUTURE_SKEW_MS)

    /**
     * Applies an inbound reaction. [ReactionRepository.apply] is last-writer-wins, so duplicates and
     * out-of-order add/retract/replace frames are idempotent. The target message may not exist yet
     * (reactions can outrun the message over the mesh) — the row persists regardless and the UI joins.
     * An emoji the wire refuses ([isValidReactionEmoji]) applies nothing and counts
     * [DropReason.REACTION_REFUSED]; never truncated (that splits a sequence into tofu) and never read as
     * a retraction (garbage would erase a valid reaction). Custody and relay already happened upstream. A write
     * that changed what the reactor shows notifies, after it, when the message is ours ([noticeReaction]).
     */
    private suspend fun handleReaction(env: RelayEnvelope) {
        // Blocked sender: drop the reaction (the router still relays it onward to other peers).
        if (env.senderId in settings.blockedNodeIds.first()) return
        val content = decodeCounted<ReactionContent>(env.payload) ?: return
        if (content.emoji != null && !isValidReactionEmoji(content.emoji)) {
            metrics.onDropped(DropReason.REACTION_REFUSED)
            return
        }
        val row =
            ReactionEntity(
                messageId = content.messageId,
                reactorNodeId = env.senderId,
                emoji = content.emoji,
                updatedAt = clampFuture(env.sentAt),
            )
        if (reactions.apply(row)) noticeReaction(env, row)
    }

    // [plane] is carried purely for the sealed delivery receipt a v2 ctl DM may turn out to be
    // ([applySealedReceipt]) — nothing on the ordinary message path reads it.
    private suspend fun handleChat(
        env: RelayEnvelope,
        plane: DeliveryPlane,
        // False for the one frame shape verifyInbound admits without a signature (ADR 059); the decrypt
        // path is what authenticates it, so it must know.
        signed: Boolean = true,
        // The verified inbound unit this frame arrived as, so a seed ctl DM for a group we do not hold yet
        // can be parked verbatim ([PendingGroupKeys]) and replayed later. Null for the carriers that have no
        // wire frame to park (a board post); those never carry a group key.
        source: InboundFrame? = null,
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
        val content = decodeCounted<ChatContent>(env.payload) ?: return
        // Group messages take the membership-gated path; they carry recipientId null, so they must be
        // handled before the DM check below (which would otherwise treat them as broadcast).
        val group = env.group
        if (group != null) {
            if (reconcileGroup(group, env.senderId, clampFuture(env.sentAt), me)) {
                // The source too: it names the hop the picture's first blind ask ranks next (ADR 2026-10.f7gg).
                decryptAndDeliver(env, content, me, group.id, plane, signed, source)
            }
            return
        }
        // A DM addressed to someone else: we're only relaying it (the router floods it onward). It
        // isn't ours, so don't persist, notify, or ack it.
        if (!Conversations.isForMe(env.recipientId, me)) return
        // A DM from us to us is nothing this app ever sends on purpose: no thread has that shape, and every
        // sealed ctl DM is addressed to some *other* member or contact. The ones in the field are the residue
        // of the self-pin `handleProfile` now refuses — still in custody for a day, re-served by every peer —
        // and opening one fails (there is no receive side of a session with ourselves), which is exactly what
        // feeds the reset heuristic and re-seals more of them. Dropped ahead of the decrypt, so the residue
        // ages out quietly. Our own broadcast posts and group frames are untouched: they carry no recipient.
        if (env.senderId == me && env.recipientId == me) return
        decryptAndDeliver(env, content, me, Conversations.idFor(env.senderId, env.recipientId, me), plane, signed, source)
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
        val claimant = peers.findByLoraNode(post.node)
        val signed = judgeMeshPostSignature(post, claimant)
        // A mismatch is some other radio on the contact's number: their name must not go on the words, and
        // their blocklist entry has nothing to say about a stranger either.
        val contact = claimant.takeIf { signed != MessageEntity.ORIGIN_SIGNATURE_MISMATCH }
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
                    signed = signed,
                ),
            ack = false,
        )
    }

    /**
     * What a heard post's XEdDSA signature proves, decided here and nowhere else (`MessageEntity.ORIGIN_*`).
     *
     * The strong answer needs two things a contact's own signed profile supplied: the claim that they hold
     * this node number, and the Curve25519 key that board signs under (`peers.loraKey`). With both, the
     * signature is checked on this phone over the bytes as heard and the exact input the firmware signs
     * (`from ‖ id ‖ portnum ‖ payload`) — a pass binds the words to *their* radio, and a failure means some
     * other radio is using their number, which is worse than a stranger and is recorded as such. Without a
     * key to check against, the most a post can be is what our own board vouched for
     * (`MeshPacket.xeddsa_signed`: the radio that has been using the number signed it), and an unsigned post
     * proves nothing either way — pre-2.8 radios never sign, and a post past the signature cliff cannot.
     */
    private fun judgeMeshPostSignature(
        post: MeshPost,
        contact: PeerEntity?,
    ): Int {
        val key = contact?.loraKey?.let { runCatching { b64d(it) }.getOrNull() }
        val signature = post.signature
        return when {
            key != null && signature != null -> {
                val ok =
                    XeddsaVerify.verify(
                        curvePub = key,
                        from = post.node.toUInt(),
                        id = post.packetId.toUInt(),
                        portnum = MeshtasticProto.PORT_TEXT_MESSAGE,
                        payload = post.payload,
                        signature = signature,
                    )
                if (ok) {
                    metrics.onMeshPostVerified()
                    MessageEntity.ORIGIN_SIGNED_BY_CONTACT
                } else {
                    metrics.onMeshPostSignatureMismatch()
                    Log.w(TAG, "mesh post from ${meshNodeLabel(post.node)}: signature does not match ${contact.nodeId}'s board key")
                    MessageEntity.ORIGIN_SIGNATURE_MISMATCH
                }
            }

            post.boardVerified -> {
                metrics.onMeshPostBoardVerified()
                MessageEntity.ORIGIN_SIGNED_BY_BOARD
            }

            else -> {
                MessageEntity.ORIGIN_UNSIGNED
            }
        }
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
        source: InboundFrame? = null,
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
            // The room's composer offers no mic and no paperclip, but that is the sender's choice: a voice note or a
            // file named in a room post would land unscreened, so the receiver drops it (RoomAttachments). A post that
            // was only that attachment keeps no row, as a blocked sender's does not, and is acked the same way.
            if (RoomAttachments.dropsWhole(env, content)) {
                acknowledge(env, me, plane)
                return
            }
            deliverChat(env, RoomAttachments.admitted(env, content), me, conversationId, plane, via = source?.fromNodeId)
            return
        }
        // The already-have-it gate: custody re-serves the same ciphertext routinely (the 60s re-offer
        // loop; the SeenSet is in-memory, 10 min, per mesh session), and decrypt used to run on every
        // copy. Skipping it once the row exists is what lets the v2 ratchet actually DELETE used
        // message keys (forward secrecy), and spares v1 an HPKE unwrap per re-serve. A re-delivery is
        // rare now that we custody our own inbound DMs, but not gone: a carrier whose quota kept a DM ours
        // evicted re-serves it every exchange, and a back-fill fed by two holders relays one holder's copy
        // to us past our seen window (#176). Each gets acknowledge's answer, which for a DM to us off the
        // board is bounded: one sealed receipt per session root, re-sealed only once ours has left our
        // custody or its re-arm floor has passed (DmReAckLedger, ADR 2026-10.8hn6). Over LoRa, and for an
        // author who reads only the cleartext form, every copy is still answered.
        if (signed && messages.exists(env.id)) {
            acknowledge(env, me, plane, reDelivery = true)
            return
        }
        when {
            // v2's two forms share the version and split on addressing (see EncEnvelope's kdoc):
            // group-addressed carries the sender-key header `g`, a DM the epoch-ratchet header `r`. The
            // group form is v2 only; v3 (ADR 059) is the DM form's compact sibling and takes the same arm.
            enc.v == EncEnvelope.VERSION_RATCHET && env.group != null -> {
                runCatchingUnlessCancelled {
                    decryptAndDeliverGroup(
                        env,
                        content,
                        enc,
                        me,
                        conversationId,
                        plane,
                        source?.fromNodeId,
                    )
                }.getOrElse {
                    Log.w(TAG, "drop group ratchet chat ${env.id}: ${it.message}")
                    metrics.onDropped(DropReason.DECRYPT_FAILED)
                }
            }

            EncEnvelope.isDmRatchetVersion(enc.v) -> {
                runCatchingUnlessCancelled { decryptAndDeliverV2(env, content, enc, me, conversationId, plane, signed, source) }.getOrElse {
                    Log.w(TAG, "drop v2 chat ${env.id}: ${it.message}")
                    metrics.onDropped(DropReason.DECRYPT_FAILED)
                }
            }

            else -> {
                val plain =
                    runCatchingUnlessCancelled { decrypt(env, enc, me) }.getOrElse {
                        Log.w(TAG, "drop encrypted chat ${env.id}: ${it.message}")
                        null
                    } ?: return
                deliverChat(env, plaintextContent(content, plain), me, conversationId, plane, plain, via = source?.fromNodeId)
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
            // The room's own fields, never read off an encrypted frame's shell: a DM's size and video ride sealed.
            attachmentSize = null,
            video = null,
            // Only the plaintext's styles: a shell's cleartext "s" is the carrier-visible half and never formats a
            // sealed message (ADR 2026-10.p95v).
            styles = plain.styles,
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
        source: InboundFrame? = null,
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
        val peek = ratchet.peekOpen(me, env.senderId, peerIkPub, wireHeader, nonce, enc.ct, aad, now, env.sentAt)
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
        // Decided on the peek, before anything commits: a seed for a group we do not hold yet pins the
        // roster it carries (so the commit below adopts it), or — carrying none — is parked whole with the
        // chain left where it was, so the replay after the roster lands opens it again.
        if (source != null && holdGroupKeyForUnknownGroup(source, plain, me)) return
        val commit: suspend (suspend () -> Unit) -> Boolean = { onOpened ->
            val committed =
                db.withWriteTransaction {
                    ratchet.commitOpen(me, env.senderId, peerIkPub, wireHeader, nonce, enc.ct, aad, now, env.sentAt, onOpened)
                }
            // After the transaction and outside the session lock: the hook may seal an answer of its own.
            if (committed) onPeerFrameOpened(env.senderId, wireHeader.init?.eph, wireHeader.flags and RatchetHeader.FLAG_RESET != 0)
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
            via = source?.fromNodeId,
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
     * advance/commit the session state but never persist it as a message and never ack it; the one
     * notification a ctl earns is a reaction to a message we wrote, posted post-commit like every other
     * follow-up here (ADR 2026-10.59fu). Group-key
     * payloads commit their group state inside the SAME transaction/lock as the DM advance
     * (adoptSeeds/onKeyAck are deliberately lock-free — the shared ratchet mutex is already held
     * there); sealed receipts/reactions (CTL_RECEIPT/CTL_REACTION) commit their row updates there
     * too, so a crash never splits the chain advance from the tick/reaction it carried. Post-commit
     * actions (reset recovery, the adoption ack, a key-request answer, a reaction's notification) run
     * strictly after, outside the transaction — a commit that refused the frame notifies nothing.
     */
    @Suppress("CyclomaticComplexMethod") // one arm per ctl value; splitting the dispatch would scatter the registry
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

            MessageContent.CTL_REACTION -> {
                // Post-commit: the row is written and the chain advanced, so a crash between the two can only
                // lose a notification, never post one for a reaction that did not land.
                outcome.reaction?.let { noticeReaction(env, it) }
            }

            MessageContent.CTL_TRANSFER -> {
                plain.xf?.let { onTransferSignal(env, it, me) }
            }

            MessageContent.CTL_CALL -> {
                plain.cl?.let { onCallSignal(env, it, me) }
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
        /** A sealed reaction whose write changed what its reactor shows (`CTL_REACTION` only), else null. */
        val reaction: ReactionEntity? = null,
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
                return CtlOutcome(reaction = applySealedReaction(env, plain.rp))
            }

            MessageContent.CTL_PROFILE -> {
                return CtlOutcome(avatarToPull = applySealedProfile(env, plain.pr))
            }

            MessageContent.CTL_SCOPE_CONFIG -> {
                // A DM config is the pair's: keyed by the peer, carrying no group (C-5-3).
                applyScopeConfig(env, plain.sc, conversationId = env.senderId, groupId = null, now = now)
            }

            else -> {}
        }
        return CtlOutcome()
    }

    /**
     * Applies a sealed `CTL_SCOPE_CONFIG` (docs/SPOOL_PROTOCOL.md §5) inside the ratchet commit that opened it — DM form
     * from [applyCtlInTxn], group form from [decryptAndDeliverGroup]'s commit — so the row lands atomically with the
     * chain advance, through Room alone (no DataStore read, no lock of its own: the transaction is already outer and
     * the ratchet mutex inner). The issuer is the frame's authenticated sender, never a payload field (C-5-2).
     *
     * The sender is recorded as declared whatever becomes of its config: a frame that opened proves its build speaks
     * the ctl. The config itself goes through [ScopeConfigPolicy] — [groupId] is the group the frame was sealed to and
     * must be the payload's own, a group issuer must be a current member who sealed it after their last leave, and the
     * store holds every version, reading it once this phone's clock reaches it (C-5-4) — a version is never a reason to
     * refuse, since a refusal is final and clocks differ between phones. A refusal counts
     * [DropReason.SCOPE_CONFIG_REFUSED] and changes nothing else: the chain still advances, so a re-served copy is the
     * ordinary `DUPLICATE` and never reaches here again.
     */
    private suspend fun applyScopeConfig(
        env: RelayEnvelope,
        sc: ScopeConfigPayload?,
        conversationId: String,
        groupId: String?,
        now: Long,
    ) {
        sc ?: return
        scopeConfigs.noteDeclared(env.senderId, now)
        val config =
            when (val result = ScopeConfigPolicy.validate(sc, conversationId, groupId, env.senderId)) {
                is ScopeConfigPolicy.Result.Refused -> return refuseScopeConfig(env, result.why)
                is ScopeConfigPolicy.Result.Valid -> result.config
            }
        if (groupId != null && !groupIssuerAdmitted(groupId, env)) return refuseScopeConfig(env, ScopeConfigPolicy.Refusal.NOT_A_MEMBER)
        if (scopeConfigs.offer(config, now) == ScopeConfigPolicy.Decision.STORE) {
            Log.i(TAG, "scope config v${config.version} for $conversationId from ${env.senderId}: ${config.spools.map(SpoolUrl::redact)}")
        }
    }

    /** Whether [env]'s sender may configure [groupId]'s scope: [ScopeConfigPolicy.groupIssuerAdmitted] on the held row. */
    private suspend fun groupIssuerAdmitted(
        groupId: String,
        env: RelayEnvelope,
    ): Boolean {
        val group = groups.find(groupId)?.takeIf { !it.left } ?: return false
        return ScopeConfigPolicy.groupIssuerAdmitted(
            issuer = env.senderId,
            members = GroupMembersStore.decode(group.members),
            sentAt = clampFuture(env.sentAt),
            leftAt = groups.leftAt(groupId, env.senderId),
        )
    }

    private fun refuseScopeConfig(
        env: RelayEnvelope,
        why: ScopeConfigPolicy.Refusal,
    ) {
        metrics.onDropped(DropReason.SCOPE_CONFIG_REFUSED)
        Log.w(TAG, "scope config ${env.id} from ${env.senderId} refused: $why")
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
     * The avatar comparison is against the *advertised* hash rather than the adopted one, so it stays
     * true for every profile that lands while the blob is still in flight; the notice absorbs that by
     * keying its row on the hash and writing it once (see [StatusNotices.peerAvatarChanged]), which is
     * what collapses the repeat to one line per photo rather than one per profile version. The
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
        // A row stored before names were cleaned on receipt is compared cleaned, so the upgrade is not a rename.
        if (previous.name.isNotEmpty() && TextLimits.cleanReceived(previous.name, TextLimits.DISPLAY_NAME) != newName) {
            savePeerNotice(peerId, StatusNotices.peerRenamed(peerId, previous.name, newName, version))
        }
        // A cleared avatar is a change too, but there is no "removed their photo" line to draw and the
        // reclaim path already handles the bytes, so only an actual new avatar is announced.
        if (advertisedAvatar != null && advertisedAvatar != previous.avatarHash) {
            savePeerNotice(peerId, StatusNotices.peerAvatarChanged(peerId, newName, advertisedAvatar, version), once = true)
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
     * republished profile upserts the same row rather than stacking a second identical line. [once]
     * keeps the *first* write instead — for a row whose id is keyed on the change rather than on the
     * profile version, so that a later version re-asserting the same change neither duplicates the line
     * nor drags its `sentAt` (and with it its place in the thread) forward to the re-assertion.
     */
    private suspend fun savePeerNotice(
        peerId: String,
        notice: MessageEntity,
        once: Boolean = false,
    ) {
        if (!messages.hasMessagesIn(peerId)) return
        if (once) messages.saveIfAbsent(notice) else messages.save(notice)
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
     * "the bytes are present locally and screening let them through" ([avatarAdoptable]), so a hash is never
     * adopted before its blob lands, nor ever while content filtering refuses it.
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
        // The version is the peer's own number and becomes our watermark, so it is bounded first — the
        // same clamp the cleartext writer applies, or a far-future one would freeze this peer's row.
        val version = clampFuture(payload.version)
        // A payload predating this build's version field reads 0 and is ignored rather than treated as
        // ancient-but-valid — an unversioned update cannot be ordered, so it must not be applied.
        if (version <= 0L || version < existing.updatedAt) return null
        val advertised = payload.avatarHash
        // Held decides the pull (a held blob is never asked for again); adoptable decides the row.
        val haveAvatar = advertised != null && blobStore.has(advertised)
        val name = TextLimits.cleanReceived(payload.name, TextLimits.DISPLAY_NAME)
        peers.upsert(
            existing.copy(
                // Clean inbound, as the cleartext path does: our own cap and line rules bound only what we originate.
                name = name,
                status = TextLimits.cleanReceived(payload.status, TextLimits.STATUS),
                avatarHash = resolveAvatarHash(advertised, avatarAdoptable(advertised), existing.avatarHash),
                updatedAt = version,
                // The whole presentation set moves together (see ProfilePayload): a field this path did not
                // copy would be reverted by every sealed update after the cleartext frame that set it.
                openToChat = payload.openToChat,
                loraNode = payload.loraNode,
                loraKey = payload.loraKey,
            ),
        )
        // The supporter badges are this path's alone (docs/SUPPORTER_BADGE.md C-6-2, ADR 2026-10.r99s): stored apart,
        // replaced whole by a sealed profile that passed the gate above (absent clears), never touched by the
        // cleartext writer, and verified only when drawn. Stored unverified on purpose: a badge whose key this build
        // does not pin yet, or whose window has not opened, shows once it can.
        // Bounded at intake by the verifier's own limits, so a peer cannot park junk the display check would refuse anyway.
        peers.replaceBadges(
            env.senderId,
            payload.badges
                .orEmpty()
                .filter(Badges::entryFits)
                .take(BadgeEntry.MAX_PER_PROFILE),
        )
        // Status notices for what actually moved. Compared against the pre-write row, and written here
        // rather than in a writer of their own because this is the point where both the old and the new
        // presentation exist at once — a moment nothing else in the profile paths preserves. Free of the
        // wire: the change is a pure function of two values both ends already hold.
        peerPresentationNotices(env.senderId, existing, name, advertised, version)
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
     * (reactor = authenticated sender, clock = the frame's signed sentAt, bounded by [clampFuture]),
     * committed atomically with the ratchet/chain advance. Orphan-permissive like the cleartext path — the
     * target may not have arrived yet; the 24 h orphan reaper bounds junk. Returns the row when its write
     * changed what the reactor shows — the caller's post-commit [noticeReaction] — else null (refused, absent,
     * stale, or the same emoji again), so a re-served frame never notifies twice.
     */
    private suspend fun applySealedReaction(
        env: RelayEnvelope,
        rp: ReactionPayload?,
    ): ReactionEntity? {
        rp ?: return null
        // Same refusal as the cleartext path; the chain/ratchet advance that carried it is unaffected.
        if (rp.emoji != null && !isValidReactionEmoji(rp.emoji)) {
            metrics.onDropped(DropReason.REACTION_REFUSED)
            return null
        }
        val row = ReactionEntity(rp.messageId, env.senderId, rp.emoji, clampFuture(env.sentAt))
        return row.takeIf { reactions.apply(it) }
    }

    /**
     * The seed-before-roster race ([PendingGroupKeys]): a `CTL_GROUP_KEY` naming a group we hold no row
     * for — the creator distributes the seed *before* the first group frame carries the roster, and custody
     * serves the two in either order. A seed that carries its founding roster (`GroupKeyPayload.group`) pins
     * the group from the seed itself first ([pinRosterFromSeed]), so the ordinary commit that follows adopts
     * it on this pass; one that carries none — an older build's — or whose roster is refused is parked
     * verbatim and reported held (true), so the caller skips the ratchet commit that would otherwise consume
     * the frame with its seeds unadopted. The same race in its second shape: a sender we hold as *departed*
     * ([rejoinBy]), whose seed for the re-created group floods ahead of the frame that rejoins them — a
     * roster-carrying seed is that member's own signed frame listing them and rejoins them itself; a
     * roster-less one is released by the rejoin frame alone, never by another member's
     * ([replayHeldGroupKeys]). [adoptGroupSeeds]'s other gates (left, a non-member) are not pre-judged here —
     * the frame takes the ordinary path. A group with a full hold (the per-group cap) also takes the ordinary
     * path — the pre-existing behaviour — rather than being refused outright.
     */
    private suspend fun holdGroupKeyForUnknownGroup(
        source: InboundFrame,
        plain: MessageContent,
        me: String,
    ): Boolean {
        if (plain.ctl != MessageContent.CTL_GROUP_KEY) return false
        val gk = plain.gk ?: return false
        val groupId = gk.groupId
        val senderId = source.envelope.senderId
        val departed = { row: GroupEntity -> senderId in GroupMembersStore.decode(row.departed) }
        val before = groups.find(groupId)
        if (before == null || departed(before)) pinRosterFromSeed(source, gk, me)
        val group = groups.find(groupId)
        val why =
            when {
                group == null -> "not held yet"
                departed(group) -> "sender departed"
                else -> return false
            }
        if (!pendingGroupKeys.hold(groupId, source)) return false
        Log.i(TAG, "holding group key ${source.envelope.id} from $senderId: group $groupId $why")
        return true
    }

    /**
     * Pins the group a seed carries the founding roster of (`GroupKeyPayload.group`, work item #47), through
     * the one door every roster goes through — [reconcileGroup], so [vetRoster]'s derivation check (the id
     * *is* the hash of the founding set, we and the sender are in it, at most [GroupInfo.MAX_MEMBERS]) and
     * [rejoinBy]'s `sentAt > leftAt` guard apply exactly as they do to a group frame. Called only for the two
     * shapes that would otherwise park: no row, or the sender held as departed. For a departed sender whose
     * seed is a re-served *pre-leave* one the row is still reconciled (within the pin, no rejoin — what that
     * member's pre-leave chat frame already does through [handleChat]) and the caller then parks the seed as
     * before. A roster naming another group, or an over-cap one, is malformed and ignored (the seed parks).
     *
     * The seed is already in custody when this runs (`onDeliver` custodies before it dispatches), so the
     * first-sight custody replay inside [reconcileGroup] is told to skip it: the adopt, the ack and the root
     * gossip must come from this pass's own commit, not from a nested re-entry under the pin.
     */
    private suspend fun pinRosterFromSeed(
        source: InboundFrame,
        gk: GroupKeyPayload,
        me: String,
    ) {
        val roster = gk.group ?: return
        if (roster.id != gk.groupId || roster.members.size > GroupInfo.MAX_MEMBERS) return
        val env = source.envelope
        if (reconcileGroup(roster, env.senderId, clampFuture(env.sentAt), me, replayExcept = env.id)) {
            Log.i(TAG, "pinned group ${gk.groupId} from the seed ${env.id} of ${env.senderId}")
        }
    }

    /**
     * The release half: every seed parked for [groupId] whose sender the reconciled row can now adopt
     * re-enters [onDeliver], so the same open that was skipped commits and adopts — and the post-commit
     * `replayGroupCustody` then decrypts the group frame that just carried the roster (already custodied by
     * the time this runs). Runs **after** [reconcileGroup]'s transaction has committed: the adoption reads
     * the row. Like the custody replay, the frame re-enters with `relay = false` — no second flood, no
     * second fan-out; verify, custody and the exists-gate are all idempotent.
     *
     * [departed] is the row's tombstones as that transaction left them. A seed parked because its sender
     * is departed waits on that sender's own rejoin — the frame that lifts their tombstone, in the same
     * transaction that produced this set — so it stays parked, clock untouched, through every other
     * member's frame; replaying it would only park it again, stamped afresh, and the hold would never
     * age out.
     */
    private suspend fun replayHeldGroupKeys(
        groupId: String,
        departed: Set<String>,
    ) {
        pendingGroupKeys.release(groupId) { it.env.senderId !in departed }.forEach {
            metrics.onGroupSeedReplayed()
            onDeliver(WireEnvelope(relay = false, sig = it.wire.sig, signed = it.wire.signed), it.env, it.fromNodeId, it.kind)
        }
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
        via: String? = null,
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
            commitGroupCtl(env, plain, groupId, now, commit)
            return
        }
        deliverChat(
            env,
            plaintextContent(content, plain),
            me,
            conversationId,
            plane,
            plain,
            via = via,
            persist = { row -> commit { messages.save(row) } },
        )
    }

    /**
     * A group-form ctl keeps its contract regardless of the envelope it arrived in: advance the chain, persist nothing
     * as a message, ack nothing. Two ctls legitimately ride the group form, each applied atomically with the chain
     * delta inside [commit]: `CTL_REACTION` (a sealed group reaction, and the one that may notify — a reaction to a
     * message we wrote, only once the commit has landed, ADR 2026-10.59fu) and `CTL_SCOPE_CONFIG` (the group's relays,
     * docs/SPOOL_PROTOCOL.md §5, through [applyScopeConfig]). Everything else here — group-key machinery on pairwise
     * DMs, an unknown future value — commits the advance and does nothing.
     */
    private suspend fun commitGroupCtl(
        env: RelayEnvelope,
        plain: MessageContent,
        groupId: String,
        now: Long,
        commit: suspend (suspend () -> Unit) -> Boolean,
    ) {
        var reaction: ReactionEntity? = null
        val committed =
            commit {
                when (plain.ctl) {
                    MessageContent.CTL_REACTION -> {
                        reaction = applySealedReaction(env, plain.rp)
                    }

                    MessageContent.CTL_SCOPE_CONFIG -> {
                        applyScopeConfig(env, plain.sc, conversationId = groupId, groupId = groupId, now = now)
                    }
                }
            }
        if (committed) reaction?.let { noticeReaction(env, it) }
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
        metrics.onGroupKeyRequested()
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
        val r = WireCodec.decodeChecked<ChatContent>(env.payload)?.enc?.r
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
            // The frame is addressed to us and we could not open it. If a reset is on its way (ours, sent
            // already or about to be), the sender answers with a fresh seal of this very frame under the
            // same id — which the router would otherwise dedup for ten minutes, since the copy custody just
            // served us is what put the id in its set. Let that one copy through. This opens our gate only:
            // a relay between us that saw the id in its window drops that seal, which is why the sender seals
            // it once more after the window (MeshManager.resealRecentDmsTo, ADR 2026-10.dmgw).
            reopenFrame(env.id)
            maybeRequestReset(env, me, now, epochGoneBase = r?.pe?.takeIf { reason == DropReason.RATCHET_EPOCH_GONE })
        }
    }

    /**
     * Sends a session reset request to [env]'s sender when the heuristic fires (≥3 distinct
     * undecryptable frames, ≥6 h since the last request). The request is an ordinary v2 DM — fresh
     * X3DH init in the header, `ctl = CTL_SESSION_RESET` inside the ciphertext — deliberately NOT a
     * new frame type, so pre-ratchet relays custody it like any chat and it reaches an offline peer.
     *
     * [epochGoneBase] is the header's `pe` when the frame failed as `EPOCH_GONE`, else null. A frame sealed against
     * one of our epochs that retention already retired, behind an epoch the peer has since sealed against, is
     * doomed the way a pre-era frame is ([RatchetSessions.isRetiredBase], ADR 2026-10.9wvr) and is gated in the same
     * place, ahead of the distinct-id LRU. The field case (2026-10-16): after a restart a neighbour re-served its
     * custody, and three peers' in-era frames sealed against epochs the 16-row cap had long retired passed the 48 h
     * escape of [isLiveEvidence] and re-rooted three healthy sessions in nine seconds — the one whose peer was out of
     * range stayed unconfirmed, and with it off the relay's scope table.
     */
    private suspend fun maybeRequestReset(
        env: RelayEnvelope,
        me: String,
        now: Long,
        epochGoneBase: Int? = null,
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
        val retired = epochGoneBase?.let { ratchet.retiredBase(env.senderId, it) }
        if (retired != null) {
            Log.d(
                TAG,
                "reset gate: ${env.id} from ${env.senderId} retired epoch " +
                    "pe=${retired.pe} lowestRetained=${retired.lowestRetained} highestPeAcked=${retired.highestPeAcked}",
            )
            return
        }
        if (!ratchet.noteUndecryptable(env.senderId, env.id, now)) return
        sendSessionReset(env.senderId, me, now, RatchetSessions.ResetCause.UNREADABLE)?.let {
            Log.d(TAG, "no session reset to ${env.senderId}: $it")
        }
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
     * side at all, however many undecryptable frames it sends us. Past them the ratchet may still decline,
     * or seal the reset under the session we hold rather than a new root: it never sends a reset the peer
     * would refuse ([RatchetSessions.sealResetDm], ADR 2026-09.qerd), which is why it is told the [cause].
     */
    suspend fun sendSessionReset(
        peerId: String,
        me: String,
        now: Long,
        cause: RatchetSessions.ResetCause = RatchetSessions.ResetCause.ON_DEMAND,
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
            when (
                val seal =
                    ratchet.sealResetDm(
                        peerId = peerId,
                        peerIkPub = bundle.dhPublicKey(),
                        peerSpk = RatchetEngine.PeerPrekey(id = prekeyId, pub = prekeyPub),
                        plaintext = plaintext,
                        aad = aad,
                        now = now,
                        cause = cause,
                    )
            ) {
                is RatchetSessions.ResetSeal.Sealed -> seal
                is RatchetSessions.ResetSeal.Declined -> return seal.reason
                RatchetSessions.ResetSeal.NoPrekey -> return "sealResetDm refused (unusable prekey)"
            }
        if (sealed.reRooted) {
            Log.w(TAG, "requesting ratchet session reset with $peerId")
        } else {
            Log.w(TAG, "requesting ratchet session reset with $peerId under the session we hold")
        }
        originate(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = me,
                sentAt = now,
                recipientId = peerId,
                payload = WireCodec.encodePayload(ChatContent(enc = sealed.envelope)),
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
     * ORIGIN_RELAY). The frame signature must verify byte-exact over the received signed bytes against the
     * key that derives to its senderId (via [verifierBundle], so a profile authenticates on its in-band key
     * and every other type on the sender's pinned key) — a node never stores unauthenticated junk, only
     * frames an identified sender actually authored. A DM/group *chat* frame is carried only in its encrypted
     * form (a carrier holds it without reading); the broadcast room and the cleartext metadata frames
     * (reaction/receipt/group-update/group-leave/profile) carry no enc envelope and are held on their
     * signature alone. Without carrying these, only a frame's author would hold it (via ORIGIN_SELF), so
     * custody / cue-plane anti-entropy would never converge between peers. Our own sends bypass this check.
     *
     * The block list is deliberately **not** consulted here (ADR 010, ADR 2026-09.bts9, work item #45): the
     * content digest is folded over what this gate admits, so anything it refuses on a per-node input diverges
     * this node's live set from every peer's for as long as that input holds — a blocker would be re-served
     * the blocked sender's frames every exchange and refuse them every time. A blocked sender's frames are
     * carried and relayed like anyone else's and dropped on the *delivery* path ([handleChat]), which is the
     * only place blocking is read; the per-sender custody quota bounds what they can cost.
     */
    suspend fun canCarry(
        wire: WireEnvelope,
        env: RelayEnvelope,
    ): Boolean {
        if (env.type == FrameType.CHAT) {
            // Uncounted: the carry gate runs before the signature is checked, and the delivery path counts the frame.
            val content = WireCodec.decodeChecked<ChatContent>(env.payload) ?: return false
            val isBroadcast = env.recipientId == null && env.group == null
            if (!isBroadcast && content.enc == null) return false // DM/group must be carried encrypted
        }
        val bundle = verifierBundle(env, countDecode = false)
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
     * arrives for it, so [describeHeldAttachment] is a no-op); the addressed recipient screens on decrypt.
     * A no-op for our own sends and delivered messages — [blobStore] already holds the blob. A room post (or a
     * key-less forward) of a picture we hold sealed from a conversation with its author is copied from our own store
     * instead, exactly as [deliverChat] would (ADR 2026-10.mptm) — a carried room frame can be custodied before it
     * is delivered here. A DM whose recipient is linked to us waits ([CarrierPullPolicy], ADR 2026-10.g24x), and so
     * does a group's picture whose author is: the recipient or the members are fetching it from the author now, and
     * our copy would only split the author's feed; the database re-arm pulls it later.
     *
     * A frame addressed to us — our DM, a group we are in — is custodied before it is opened, so its attachment's
     * declared size is still sealed: while the user has a Bluetooth download limit it is left to [deliverChat], right
     * behind, which wants it by the real verdict ([carriedFastOnly], ADR 2026-10.js2p). A room post is every node's and
     * declares its size in the clear (ADR 2026-10.hmfe), so it is judged here as its row will be: this runs ahead of
     * [deliverChat], and a plain want here would ask a Bluetooth holder for a large room video before the delivery's
     * fast-only want could hold it (an outstanding ask is left to run). A carrier's pull of somebody else's DM or group
     * frame is not the user's download, and is never held to the limit.
     */
    suspend fun onCarriedFrame(
        env: RelayEnvelope,
        // The neighbour the frame came from: the first ask ranks it after the author and the fast plane (ADR 2026-10.f7gg).
        via: String? = null,
    ) {
        if (env.type != FrameType.CHAT) return
        val content = WireCodec.decodeChecked<ChatContent>(env.payload) ?: return
        val hash = content.attachmentHash ?: return
        // A room post's attachment the room does not take is dropped from its row (RoomAttachments), so no row will
        // ever want it; carrying it would spend the carrier budget on bytes no Knit draws.
        if (RoomAttachments.refuses(env, content)) return
        if (blobStore.has(hash)) return
        if (copyFromOwnStore(hash, env.senderId)) return
        if (blobs.carrierOnlyBlobBytes() >= CARRIER_BLOB_BUDGET_BYTES) return
        val linked = transport.neighbors.value.mapTo(HashSet()) { it.nodeId }
        val member = env.group?.members?.contains(identity.nodeId()) == true
        val carried = CarriedAttachment(hash, env.recipientId, clock(), env.senderId, env.group?.id, member)
        if (CarrierPullPolicy.defers(listOf(carried), linked, clock())) {
            metrics.onCarrierPullDeferred()
            return
        }
        val fastOnly = carriedFastOnly(env, content, hash, member) ?: return
        // A DM's carrier pulls it through for its custody window and no longer: frame-global, the same on every node (#195).
        val until = env.recipientId?.let { clampFuture(env.sentAt) + ForwardRepository.DEFAULT_TTL_MS }
        blobExchange.want(hash, author = env.senderId, via = via, fastOnly = fastOnly, until = until)
    }

    /**
     * How [deliverChat] fetches its attachment [hash] (ADR 2026-10.js2p): whether the new row comes in tapped, and whether
     * the want is fast-only. A row that is not gated itself — no declared size, under the limit, no limit, our own —
     * fetches the attachment for every row naming it, so it is plain with no read at all: the room post and the ordinary
     * photo pay nothing. Only a gated row reads the rows naming the hash, once (by index): it comes in tapped when the user
     * already tapped the hash — it would show the card again otherwise — and it is fast-only when every row, this one
     * included, is gated ([BluetoothFetchGate.fastOnly]); one free row fetches it for all.
     *
     * A DM's row reads the rows too, for the deadline its want pulls it through by (`PullThroughGate.until`, #195): a DM
     * delivered past its custody window must not ask anyone to pull it, from its first step. A group's or a room's row has
     * none, so it still pays nothing.
     */
    private suspend fun deliveredFetch(
        hash: String?,
        declaredSize: Long?,
        isNew: Boolean,
        env: RelayEnvelope,
        me: String,
    ): DeliveredFetch {
        if (hash == null) return DeliveredFetch.PLAIN
        val limit = settings.bluetoothDownloadLimitBytes.first()
        val senderId = env.senderId
        val own = senderId == me
        val gated = BluetoothFetchGate.fastOnly(declaredSize, limit, approved = false, own = own)
        if (!gated && env.recipientId == null) return DeliveredFetch.PLAIN
        val stored = messages.attachmentFetchRows(hash, me)
        val tapped = stored.any { it.approved }
        val rows = if (isNew) stored + newRow(hash, env, declaredSize, tapped, own) else stored
        return DeliveredFetch(
            inheritsTap = gated && isNew && tapped,
            fastOnly = gated && BluetoothFetchGate.fastOnly(rows, limit),
            until = PullThroughGate.until(rows),
        )
    }

    /** The fetch row [env] will store for its attachment [hash], before it is stored: what the gates fold it as. */
    private fun newRow(
        hash: String,
        env: RelayEnvelope,
        declaredSize: Long?,
        approved: Boolean,
        own: Boolean,
    ) = AttachmentFetchRow(hash, env.senderId, declaredSize, approved, own, sentAt = clampFuture(env.sentAt), dm = env.recipientId != null)

    /**
     * Starts pulling a delivered row's [hash] unless we already hold it (the UI observes the blobs table and flips the
     * attachment from "loading" to shown once the bytes land, and onObtained describes it). If we already hold it
     * (carried while its frame was parked for a key, fetched off a spool first, or a forward of a file we have), no
     * onObtained is coming, so the row is described here, now that it and the key are in hand — the same hooks through
     * the same helper, so neither order can miss one (#132). Off the [radio] (a commons attachment, which the room's
     * relay alone brings, ADR 2026-10.xhfr) nothing is asked of a neighbour.
     */
    @Suppress("LongParameterList") // the delivery's attachment facts, threaded once from deliverChat
    private suspend fun pullOrDescribe(
        hash: String,
        key: String?,
        mime: String?,
        env: RelayEnvelope,
        via: String?,
        fetch: DeliveredFetch,
        radio: Boolean,
    ) {
        if (blobStore.has(hash)) {
            describeHeldAttachment(hash, key, mime)
        } else if ((key != null || !copyFromOwnStore(hash, env.senderId)) && radio) {
            // The delivery read every row naming the hash, or its own is no DM's: its deadline, none included, is known.
            blobExchange.want(hash, author = env.senderId, via = via, fastOnly = fetch.fastOnly, until = fetch.until, untilKnown = true)
            // Arm the fast plane toward the author — the guaranteed holder — so the pull can ride a NAN
            // NDP instead of crawling over BLE (the serve side arms its own half in the composite; only
            // the larger nodeId of a pair ever initiates, so an inert mark here is harmless). Freshness/
            // cooldown gating lives in the transport; a multi-hop author simply isn't fresh and no-ops.
            transport.expectBulkTransfer(env.senderId)
        }
    }

    /** [deliveredFetch]'s verdict: whether the new row comes in tapped, and whether its want is fast-only. */
    private class DeliveredFetch(
        val inheritsTap: Boolean,
        val fastOnly: Boolean,
        // The deadline the want pulls it through by: a DM's custody window, null for anything else (#195).
        val until: Long? = null,
    ) {
        companion object {
            val PLAIN = DeliveredFetch(inheritsTap = false, fastOnly = false)
        }
    }

    /**
     * How [onCarriedFrame] wants [env]'s attachment [hash] (ADR 2026-10.js2p): plain unless the frame is addressed to us
     * ([member] of its group, or its DM's recipient) or is a room post, and a limit is set. A room post's own declared
     * size ([content], cleartext, ADR 2026-10.hmfe) is folded with the rows naming the hash, exactly as [deliveredFetch]
     * will fold its row: no read when the post is not gated itself. For a DM or a group frame the rows naming it judge
     * it, as the database re-arm does — a re-served DM we already stored, which no delivery follows — and with none it is
     * not wanted here at all (null): its size is sealed inside, and [deliverChat], right behind, wants it by its verdict.
     * One that never opens is the tick's (`MeshManager.rewantMissingBlobs`, fast-only); a want here would only query the
     * Wi-Fi Aware neighbours once more per incoming picture.
     */
    private suspend fun carriedFastOnly(
        env: RelayEnvelope,
        content: ChatContent,
        hash: String,
        member: Boolean,
    ): Boolean? {
        val me = identity.nodeId()
        val room = env.recipientId == null && env.group == null && content.enc == null
        if (!room && env.recipientId != me && !member) return false
        val limit = settings.bluetoothDownloadLimitBytes.first() ?: return false
        if (room) {
            val declared = content.attachmentSize?.takeIf { it > 0 }
            val own = env.senderId == me
            if (!BluetoothFetchGate.fastOnly(declared, limit, approved = false, own = own)) return false
            val stored = messages.attachmentFetchRows(hash, me)
            val post = newRow(hash, env, declared, approved = stored.any { it.approved }, own = own)
            return BluetoothFetchGate.fastOnly(stored + post, limit)
        }
        val rows = messages.attachmentFetchRows(hash, me).takeIf { it.isNotEmpty() } ?: return null
        return BluetoothFetchGate.fastOnly(rows, limit)
    }

    /**
     * Handles a standalone group-metadata update (e.g. a rename): reconciles the stored group, with no
     * message to persist or ack. A member who didn't yet know the group creates it from the roster.
     */
    private suspend fun handleGroupUpdate(env: RelayEnvelope) {
        val group = env.group ?: return
        reconcileGroup(group, env.senderId, clampFuture(env.sentAt), identity.nodeId())
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
        val groupId = decodeCounted<GroupLeaveContent>(env.payload)?.groupId ?: return
        // The leave clock is what the member's own rejoin must beat ([rejoinBy]); bounded, or a far-future
        // leave locks them out of their own group for good.
        groups.recordDeparture(groupId, env.senderId, clampFuture(env.sentAt))
    }

    /**
     * Brings the locally-stored group in line with a self-describing [group] roster carried on a frame
     * from [senderId] (stamped [sentAt] — the frame's clock already bounded by [clampFuture] at both call
     * sites, since it becomes the name, creation, rename-notice and rejoin clocks below and none of those
     * may hold a sender's far-future number). Returns true when the group is active for us (so a chat frame
     * should be delivered), false when the frame must be ignored: blocked sender, a group we've left
     * (never re-upserted, so a frame can't resurrect it), a roster [vetRoster] refuses, or a *new* group
     * whose creator we've blocked (covers the proxy case where a non-blocked member relays the first
     * frame carrying a blocked createdBy). The name is last-writer-wins on [sentAt] so concurrent renames
     * across the mesh converge.
     *
     * The roster itself is **pinned, never overwritten**: a frame can only establish a group whose id
     * verifiably derives from its founding roster ([vetRoster]'s Adopt), and thereafter membership only
     * shrinks, via signed `groupleave` frames ([GroupRepository.recordDeparture]) — with one mirror-image
     * exception, a founding member who left re-adding *themselves* by their own signed frame
     * ([rejoinBy]) — group key state (docs/GROUP_FORWARD_SECRECY.md) distributes epoch seeds to exactly
     * this roster, so its integrity is a security input, not presentation.
     */
    private suspend fun reconcileGroup(
        group: GroupInfo,
        senderId: String,
        sentAt: Long,
        me: String,
        replayExcept: String? = null,
    ): Boolean {
        // DataStore read hoisted out of the transaction: it can't enroll in a Room transaction, and holding the
        // exclusive DB lock across a DataStore suspend would stall every other writer.
        val blocked = settings.blockedNodeIds.first()
        val filtering = settings.contentFilteringEnabled.first()
        // find → refuse-check → upsert run in one transaction, re-reading `existing` inside, so the left-tombstone
        // check and the upsert can't tear apart from a concurrent transactional GroupRepository.leave()/
        // delete(): otherwise a find that read left=false just before leave() commits would blind-upsert left=false
        // and resurrect the group. Returns the PhotoDecision on adoption (its bytes may still need pulling) with
        // the tombstones the row now holds, or null when the frame is refused.
        val (photo, departed, firstSight) =
            // Explicit type argument: withWriteTransaction's block is a TransactionScope<R> receiver, and R is
            // invariant, so inference latches onto the first `return@` (null) instead of unifying with the
            // Reconciled returned at the end.
            db.withWriteTransaction<Reconciled?> {
                val existing = groups.find(group.id)
                if (groupFrameRefused(group, senderId, existing, blocked)) return@withWriteTransaction null
                val rejoining = existing != null && rejoinBy(group, senderId, existing, sentAt)
                val roster = acceptedRoster(group, senderId, existing, me, rejoining) ?: return@withWriteTransaction null

                // The name is shared only when explicitly set; an unnamed (blank/null) frame never clears a name
                // someone else set. Adopt an incoming name only if it's newer (last-writer-wins on sentAt).
                val incomingName = group.name?.takeIf { it.isNotBlank() }?.clampToUtf16(TextLimits.GROUP_NAME)
                val keepName = existing?.name.orEmpty()
                val keepClock = existing?.nameUpdatedAt ?: 0L
                val takeIncoming = incomingName != null && sentAt >= keepClock
                val decision = groupPhotoDecision(existing, group, filtering)
                val createdAt = existing?.createdAt ?: sentAt
                val (_, departed) = storedRoster(existing, senderId, rejoining)
                groups.upsert(
                    GroupEntity(
                        groupId = group.id,
                        name = if (takeIncoming) incomingName else keepName,
                        members = GroupMembersStore.encode(roster),
                        createdBy = group.createdBy,
                        createdAt = createdAt,
                        nameUpdatedAt = if (takeIncoming) sentAt else keepClock,
                        left = false,
                        // The tombstones are ours alone (never the frame's `departed`, see vetRoster); the one
                        // way out of them is the member's own signed rejoin, already lifted in `departed`.
                        departed = GroupMembersStore.encode(departed),
                        photoHash = decision.hash,
                        photoUpdatedAt = decision.clock,
                        photoShownHash = decision.shown,
                    ),
                )
                groupNotices(group, senderId, me, sentAt, existing, incomingName, keepName, takeIncoming, decision, createdAt)
                if (rejoining) groups.recordRejoin(group.id, senderId, sentAt, rekey = rejoinRekeyDue(group.id, senderId))
                Reconciled(decision, departed.toSet(), firstSight = existing == null)
            } ?: return false
        // A decided photo whose bytes we don't hold yet: pull it hop-by-hop (after the upsert stored it, so
        // settleArrivedGroupPhoto finds the row waiting on it). Outside the transaction — a network-bound blob
        // fetch, and the reclaims below are their own transactions.
        settleGroupPhotoBlobs(photo, senderId)
        // The row exists now: any seed that arrived ahead of it can be adopted. Cheap when nothing is parked
        // (the usual case), and it must run before a chat frame's own decrypt so the first message of a new
        // group opens on its first pass rather than on the custody replay.
        replayHeldGroupKeys(group.id, departed)
        // The park is in memory: a seed that arrived, was parked, and was lost to a process death before this
        // row existed is still in our custody store, and nothing else will hand it to us again (see
        // MeshManager.replayCustodiedSeedDms). First sight only — the park can only have held a seed for a
        // group we did not hold — so a group's ordinary frames never pay for it. [replayExcept] is the seed
        // whose own roster this sight came from (pinRosterFromSeed): custodied already, still mid-flight.
        if (firstSight) replaySeedCustody(group.id, replayExcept)
        return true
    }

    /**
     * What [reconcileGroup]'s transaction hands its post-commit half: the photo verdict, the row's tombstones,
     * and whether the row was created just now.
     */
    private data class Reconciled(
        val photo: PhotoDecision,
        val departed: Set<String>,
        val firstSight: Boolean,
    )

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
     *
     * That line's subject is the one notice subject a frame gets to *claim*: [GroupInfo.createdBy] is the
     * sender's word, and our node id is on every advert. A notice keeps its subject as its sender, and
     * [MessageRepository.conversationsIAuthoredIn] counts notices, so a line naming us makes the thread one
     * we wrote in and lets a stranger's group past the Message Requests gate (#107). Only our own frame may
     * name us; any other names its verified sender. The cost: a group we created, met again after a
     * reinstall with no backup, names another member as its creator. The row's `createdBy` keeps the
     * frame's word — every member reads the preferred root minter from it, so it must match theirs.
     */
    private suspend fun groupNotices(
        group: GroupInfo,
        senderId: String,
        me: String,
        sentAt: Long,
        existing: GroupEntity?,
        incomingName: String?,
        keepName: String,
        takeIncoming: Boolean,
        photo: PhotoDecision,
        createdAt: Long,
    ) {
        if (existing == null) {
            val creator = group.createdBy.takeIf { it != me || senderId == me } ?: senderId
            messages.save(StatusNotices.groupCreated(group.id, creator, createdAt))
            return
        }
        if (takeIncoming && incomingName != null && incomingName != keepName) {
            messages.save(StatusNotices.groupRenamed(group.id, senderId, incomingName, sentAt))
        }
        photo.changedTo?.let { messages.save(StatusNotices.groupPhotoChanged(group.id, senderId, it, sentAt)) }
    }

    /** [vetRoster]'s verdict as the roster to store, or null for either refusal (the counted one counted). */
    private fun acceptedRoster(
        group: GroupInfo,
        senderId: String,
        existing: GroupEntity?,
        me: String,
        rejoining: Boolean,
    ): List<String>? =
        when (val verdict = vetRoster(group, senderId, existing, me, rejoining)) {
            is RosterVerdict.Accept -> {
                verdict.members
            }

            RosterVerdict.NotOurs -> {
                null
            }

            RosterVerdict.Refused -> {
                metrics.onDropped(DropReason.GROUP_ROSTER_REFUSED)
                null
            }
        }

    /**
     * The pinned roster as `(members, departed)`, with [senderId]'s tombstone already lifted when
     * [rejoining] — so [vetRoster] and the upsert read one consistent view. Empty pair for a first sight.
     */
    private fun storedRoster(
        existing: GroupEntity?,
        senderId: String,
        rejoining: Boolean,
    ): Pair<List<String>, List<String>> {
        if (existing == null) return emptyList<String>() to emptyList()
        val members = GroupMembersStore.decode(existing.members)
        val departed = GroupMembersStore.decode(existing.departed)
        return if (rejoining) (members + senderId) to (departed - senderId) else members to departed
    }

    /**
     * Whether this frame re-adds its sender to a group they had left: the sender is in our tombstones,
     * lists *itself* in the frame's roster, and the frame is newer than the recorded leave. The mirror of
     * the signed `groupleave` — [verifyInbound] has proven the frame is the sender's own, so this is the
     * one roster growth that needs no coordination: nobody can re-add anyone else (the tombstone is keyed
     * on the signer), and nobody can be re-added against their will. The `sentAt` guard is what keeps a
     * custody re-serve of a *pre-leave* frame (in which they were, of course, a member) from undoing the
     * leave; its clock is the member's leave clock on the group ([GroupRepository.leftAt]), written atomically
     * with the tombstone by [GroupRepository.recordDeparture], and a missing one reads as "left at 0" — lenient,
     * never a lock-out. Why it exists at all: a group's id is the hash of its member set, so "create a group with
     * the same people" after leaving resolves to the *same* group everywhere, and without this the
     * creator's `createGroup` "re-creating rejoins it" was only ever true on their own phone.
     */
    private suspend fun rejoinBy(
        group: GroupInfo,
        senderId: String,
        existing: GroupEntity,
        sentAt: Long,
    ): Boolean {
        if (senderId !in GroupMembersStore.decode(existing.departed)) return false
        if (senderId !in group.members) return false
        return sentAt > groups.leftAt(group.id, senderId)
    }

    // (groupId, memberId) -> when we last rekeyed for their rejoin. A rejoin always restores membership;
    // this only bounds the *rekey* it triggers, so a member flipping leave/rejoin cannot make every other
    // member re-mint and fan seeds out on each turn (the rekey-fan-out amplifier §6.1 guards against).
    private val lastRejoinRekeyAt = ConcurrentHashMap<Pair<String, String>, Long>()

    /** Checks-and-stamps the per-(group, member) rejoin-rekey floor. */
    private fun rejoinRekeyDue(
        groupId: String,
        memberId: String,
    ): Boolean {
        val key = groupId to memberId
        val now = clock()
        val last = lastRejoinRekeyAt[key]
        if (last != null && now - last < REJOIN_REKEY_FLOOR_MS) return false
        lastRejoinRekeyAt[key] = now
        return true
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
     *
     * [rejoining] ([rejoinBy], decided by the caller) is the one growth: the sender's own tombstone is
     * lifted and they return to the members ([storedRoster]) — in both accepted shapes, since the founding
     * set is unchanged.
     */
    private fun vetRoster(
        group: GroupInfo,
        senderId: String,
        existing: GroupEntity?,
        me: String,
        rejoining: Boolean = false,
    ): RosterVerdict {
        val incomingFounding = (group.members + group.departed.orEmpty()).distinct()
        val (storedMembers, storedDeparted) = storedRoster(existing, senderId, rejoining)
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
     * of the name's sentAt clock so a stale chat message re-asserting an old photo can't revert a newer one
     * (ADR 2026-09.nxcq). Two answers, because the row holds two photos:
     *
     * - **Decided** ([PhotoDecision.hash], the row's `photoHash`): a newer photo is taken the moment it is
     *   heard, bytes or no bytes, because every frame this device sends re-asserts it — keeping the old hash
     *   at the new clock until the bytes landed made a member still pulling them switch everyone who already
     *   showed the new photo back (#108). A tie keeps the photo held: an equal clock with another hash is that
     *   same stale pair from an older build, or two members setting a photo in one millisecond, and only the
     *   first of those ever happens. With no photo held a tie still takes, so a hash with no clock is adopted.
     * - **Shown** ([PhotoDecision.shown], `photoShownHash`): follows the decided photo once its blob is local
     *   and, with content filtering on, not flagged explicit; until then the previous photo keeps showing and
     *   [PhotoDecision.pull] names the hash to fetch. A frame re-asserting the decided photo re-arms that pull,
     *   so a want lost to a restart or the fetch TTL comes back with the group's next frame.
     *
     * A photo screening refused (filtering on, verdict flagged) is still the decided one — the group goes on
     * advertising it — but is never shown and never fetched again: its kept verdict says so
     * ([BlobRepository.dropRefusedGroupPhoto]).
     */
    private suspend fun groupPhotoDecision(
        existing: GroupEntity?,
        group: GroupInfo,
        filtering: Boolean,
    ): PhotoDecision {
        val incomingPhoto = group.photoHash
        // A payload field every member re-asserts in every frame, so a far-future one would circulate as
        // the group's photo clock forever; bounded here.
        val incomingPhotoClock = clampFuture(group.photoUpdatedAt ?: 0L)
        val keepPhoto = existing?.photoHash
        val keepPhotoClock = existing?.photoUpdatedAt ?: 0L
        val shownPhoto = existing?.photoShownHash
        val takePhoto = photoWins(incomingPhoto, incomingPhotoClock, keepPhoto, keepPhotoClock)
        val decided = if (takePhoto) incomingPhoto else keepPhoto
        val clock = if (takePhoto) incomingPhotoClock else keepPhotoClock
        val pending = decided?.takeIf { it != shownPhoto }
        // Held first, then the verdict: the store screens before it inserts, so bytes seen held always have
        // their verdict by now — the opposite order could read "no verdict" just before both land (#109).
        val held = pending != null && blobStore.has(pending)
        val refused = pending != null && filtering && imageScreening.isImageFlagged(pending)
        val haveBytes = held && !refused
        val shown = if (haveBytes) pending else shownPhoto
        return PhotoDecision(
            hash = decided,
            clock = clock,
            shown = shown,
            pull = pending.takeIf { !refused && !haveBytes },
            refused = pending.takeIf { refused },
            replaced = listOfNotNull(keepPhoto.takeIf { it != decided }, shownPhoto.takeIf { it != shown }).distinct(),
            changedTo = incomingPhoto.takeIf { takePhoto },
        )
    }

    /**
     * Whether [incoming] at [incomingClock] replaces the decided photo [held] at [heldClock]: a different photo
     * on a later clock, or on an equal clock only while nothing is held. A tie never moves a held photo — the
     * one rule under which a stale pair (the photo a member still shows, at the new clock) cannot win.
     */
    private fun photoWins(
        incoming: String?,
        incomingClock: Long,
        held: String?,
        heldClock: Long,
    ): Boolean =
        incoming != null &&
            incoming != held &&
            (incomingClock > heldClock || (held == null && incomingClock >= heldClock))

    /** A reconciled group photo: what the row stores, and the blob work its commit leaves behind. */
    private data class PhotoDecision(
        /** The decided photo, advertised on every frame we send. */
        val hash: String?,
        val clock: Long,
        /** The photo that renders: [hash] once its bytes are local and screened, else the previous one. */
        val shown: String?,
        /** The decided photo's hash to fetch, when its bytes aren't local and screening hasn't refused it. */
        val pull: String?,
        /** The decided photo screening refused, whose bytes (if any) go and whose verdict stays. */
        val refused: String?,
        /** Hashes the row stopped naming — reclaimed once nothing else references them. */
        val replaced: List<String>,
        /**
         * The newly-decided photo when this frame actually changed it, else null — which is **not** the
         * same question as [hash], [shown] or [pull]: those also answer for a frame that merely re-asserts
         * what we hold, and only this distinguishes a real change from the same photo being re-asserted by
         * every chat frame that carries the group.
         */
        val changedTo: String?,
    )

    /**
     * The blob half of a committed [PhotoDecision]: pulls the decided photo over the same content-addressed
     * [BlobExchange] that carries avatars and attachments (group photos are pull-only — no direct push like
     * avatars — so this runs for direct neighbors too, and the holder serves the blob when the want reaches
     * it; [settleArrivedGroupPhoto] shows it on arrival), drops a refused photo's bytes, and reclaims the
     * hashes the row stopped naming.
     */
    private suspend fun settleGroupPhotoBlobs(
        photo: PhotoDecision,
        // The member whose frame named the photo: the likeliest holder, so the round asks it first (ADR 2026-10.f7gg).
        senderId: String,
    ) {
        photo.pull?.let { blobExchange.want(it, author = senderId) }
        photo.refused?.let { blobs.dropRefusedGroupPhoto(it) }
        photo.replaced.forEach { blobs.deleteIfUnreferenced(it) }
    }

    /**
     * A pulled blob just landed: every group that decided on it as its photo and does not show it yet shows
     * it now — the rows say which ([GroupRepository.awaitingPhoto]), so a pull re-armed after a restart lands
     * as well as one this process asked for — unless, with content filtering on, the screen in
     * [MeshBlobStore.ingest] (which a group photo, naming no message row, can never skip, whether a radio pull or
     * a spool fetch brought it) flagged it explicit: then its bytes go and its verdict stays, and the previous
     * photo keeps showing. Each row is re-read in its own transaction and shown only while it still decides on
     * [hash], so a newer photo decided meanwhile wins. A no-op for blobs no group wants.
     */
    private suspend fun settleArrivedGroupPhoto(hash: String) {
        val waiting = groups.awaitingPhoto(hash)
        if (waiting.isEmpty()) return
        // Mirror the avatar gate: the setting gates receive-side hiding, so off -> show it anyway.
        if (refusedImage(hash)) {
            blobs.dropRefusedGroupPhoto(hash)
            return
        }
        waiting.forEach { row ->
            val replaced =
                db.withWriteTransaction {
                    val group = groups.find(row.groupId) ?: return@withWriteTransaction null
                    if (group.left || group.photoHash != hash || group.photoShownHash == hash) return@withWriteTransaction null
                    groups.upsert(group.copy(photoShownHash = hash))
                    group.photoShownHash
                }
            blobs.deleteIfUnreferenced(replaced)
        }
    }

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
     * The moderation column for an inbound chat row — classified only on a first delivery. The verdict lands
     * on the row, and `deliverChat`'s exists-gated persist discards the row for a re-served frame, so a
     * duplicate (the second radio, a custody re-serve, a restart that emptied the SeenSet) used to buy a full
     * ML inference for nothing, serialized behind every real one on the moderator's mutex.
     */
    private suspend fun inboundModeration(
        isNew: Boolean,
        body: String,
        conversationId: String,
    ): Int {
        if (!isNew) return MessageEntity.MODERATION_NONE
        // Both public rooms take the room moderator (the lexical profanity pass on top of the ML one), and
        // for one reason: a room is read by strangers. The bridged room needs it at least as much — its
        // authors are strangers by definition, and nothing screens them before they reach the air.
        val flagged = classifyText(body, "incoming", Conversations.isPublicRoom(conversationId))
        return if (flagged) MessageEntity.MODERATION_TEXT_FLAGGED else MessageEntity.MODERATION_NONE
    }

    /**
     * The quote an inbound frame carries, bounded the way its body is, or null when it cannot name an original —
     * a blank or overlong id. The author string is kept only as a record (the reader's own label for the author's
     * id is what renders), and the snippet is cut to what a sender's composer ever writes.
     */
    private fun admitReply(ref: ReplyRef?): ReplyRef? {
        if (ref == null) return null
        if (ref.messageId.isBlank() || ref.messageId.length > MAX_QUOTED_ID) return null
        if (ref.authorId.isBlank() || ref.authorId.length > MAX_QUOTED_ID) return null
        return ref.copy(
            author = normalizeSingleLine(ref.author).clampToUtf16(TextLimits.DISPLAY_NAME),
            snippet = normalizeSingleLine(ref.snippet).clampToUtf16(REPLY_SNIPPET_MAX),
        )
    }

    /**
     * Persists [built] with its quote judged, and returns the row as it now reads (ADR 2026-10.gpa3). Somebody else's
     * quote is a claim until it is checked against the original it names: judged before the insert when the original
     * is held, else its words are screened, since they show in the bubble. After the insert, whichever of a reply and
     * its original commits second settles the quote — this row's own, if its original raced in since the check, and
     * those of the replies that were waiting on it. Read back, so the caller's notification hides a reply whose
     * quoted words are flagged on this phone.
     */
    private suspend fun persistQuoted(
        built: MessageEntity,
        isNew: Boolean,
        me: String,
        conversationId: String,
        persist: suspend (MessageEntity) -> Unit,
    ): MessageEntity {
        val row = if (isNew && built.senderId != me && built.replyToId != null) quoteOnArrival(built, conversationId) else built
        persist(row)
        return (if (isNew) quotes.settleAfterInsert(row.id, me) else null) ?: row
    }

    /**
     * [row]'s quote on a first delivery (ADR 2026-10.gpa3): judged against its original when this phone holds it;
     * otherwise the sender's snippet is all the bubble can show, so it is screened like a body — by the same
     * moderator, in the same scope — and a flagged one hides the reply. Runs before the persist and outside any
     * transaction, like [inboundModeration].
     */
    private suspend fun quoteOnArrival(
        row: MessageEntity,
        conversationId: String,
    ): MessageEntity {
        quotes.judge(row)?.let { return it }
        val snippet = row.replyToSnippet.orEmpty()
        val flagged =
            snippet.isNotBlank() && classifyText(snippet, "incoming quote", Conversations.isPublicRoom(conversationId))
        return row.copy(replyToFlagged = flagged)
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
        // The neighbour this frame reached us from, when it came off a link: the attachment's first ask ranks it
        // after the author and the fast plane (ADR 2026-10.f7gg). Null for a replay with no hop worth naming.
        via: String? = null,
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
        // A commons post's attachment lives on the room's relay alone (spec §7.4): its key and name ride cleartext
        // inside the room's seal, and the room's own pass fetches its bytes — never the radios.
        val commons = Conversations.kindFor(conversationId) == ConversationKind.COMMONS
        val attachmentKey = sealed?.attachmentKey ?: content.attachmentKey?.takeIf { commons }
        val moderation = inboundModeration(isNew, content.body, conversationId)
        val (declaredSize, video) = deliveredDescription(content, sealed)
        // No radio fetch for a commons attachment, so no fetch rule to judge it by.
        val fetch = if (commons) DeliveredFetch.PLAIN else deliveredFetch(hash, declaredSize, isNew, env, me)
        // Clamp the inbound body: our sender-side TextLimits.MESSAGE only bounds what we originate, not what a peer
        // floods (otherwise bounded only by the 512 KiB record cap → unbounded local growth). Mirrors the profile
        // name/status clamp in handleProfile.
        val body = content.body.clampToUtf16(TextLimits.MESSAGE)
        val built =
            MessageEntity(
                id = env.id,
                senderId = env.senderId,
                recipientId = env.recipientId,
                conversationId = conversationId,
                body = body,
                // Clamp a future-dated sentAt for local ordering so a bogus far-future frame can't pin itself to
                // the top of the conversation forever (the local-display complement of ForwardRepository's custody
                // guard). Honest clock skew within the window is kept as-is.
                sentAt = clampFuture(env.sentAt),
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
                attachmentKey = attachmentKey,
                attachmentName = sealed?.attachmentName ?: content.attachmentName?.takeIf { commons },
                attachmentSize = declaredSize,
                attachmentFetchApproved = fetch.inheritsTap,
                moderation = moderation,
                originNode = origin?.node,
                originName = origin?.name,
                originChannel = origin?.channel,
                originHops = origin?.hops,
                originSnrDeci = origin?.snrDeci,
                originViaMqtt = origin?.viaMqtt == true,
                originPeerId = origin?.peerId,
                originSigned = origin?.signed ?: MessageEntity.ORIGIN_UNSIGNED,
                // The one normalisation of every door's styles (room, commons, each sealed form), against the body as
                // stored, so the clamp clips them and the sender's ranges are never trusted as sent (ADR 2026-10.p95v).
                // A heard Meshtastic post builds a content with none.
                styles = StyleStore.encode(StyleSpans.normalize(body, content.styles)),
            ).withReply(admitReply(content.replyTo))
        val stored = persistQuoted(built.withVideo(video), isNew, me, conversationId, persist)
        screenPoster(stored, fresh = isNew, own = env.senderId == me)
        val hidden = stored.hiddenByFilter(settings.contentFilteringEnabled.first())
        if (hash != null) pullOrDescribe(hash, attachmentKey, content.attachmentMime, env, via, fetch, radio = !commons)
        // A message that @-mentions us notifies on the dedicated Mentions channel only; everything else
        // takes the per-context channel (Nearby / Group messages / Direct messages), keyed off conversationId.
        // Only on first delivery — a re-served carried DM must not re-notify.
        // Suppress notifications for a stranger's request (an unaccepted DM/group): the message still persists
        // above and still acks below — only the interruption is withheld (the Message Requests gate). Custody
        // and relay run outside this dispatch path (before it / after it returns), so mesh convergence is
        // untouched; this is a purely local presentation decision.
        if (isNew) {
            val (shown, fileName) = notificationSubject(content, sealed, video)
            if (isAccepted(conversationId, me)) {
                if (env.senderId != me && content.mentions.mention(me)) {
                    notifyMention(env, shown, conversationId, fileName, hidden)
                } else {
                    notifyIncoming(env, shown, conversationId, fileName, hidden, origin)
                }
            } else if (env.senderId != me) {
                // A stranger's first DM/group: no per-message alert — just refresh the coalesced
                // "message request received" heads-up. A purely local presentation decision, like the accepted path.
                notifyPendingRequests(me)
            }
        }
        // Ack every delivery this far, a concurrent copy that lost the commit race included (the pre-decrypt
        // exists-gate answers the later re-deliveries, bounded per era). A cleartext receipt doubles as the
        // vaccine that purges this DM from any carrier that missed the first ack; a sealed one purges nothing
        // (ADR 018), and sealDmReceipt files it in the re-ack ledger for the exists-gate to read.
        if (ack) acknowledge(env, me, plane)
    }

    /**
     * The declared size and video description a delivered message carries (ADR 2026-10.hmfe): a sealed frame's from
     * inside it, a room post's from the post. Labels, both: a size that is not positive declares nothing, and a video
     * description is kept only on a video and only when it is one a bubble can draw ([isPlausible]).
     */
    private fun deliveredDescription(
        content: ChatContent,
        sealed: MessageContent?,
    ): Pair<Long?, VideoInfo?> {
        val size = (sealed?.attachmentSize ?: content.attachmentSize)?.takeIf { it > 0 }
        val video = (sealed?.video ?: content.video)?.takeIf { VideoAttachment.isVideo(content.attachmentMime) && it.isPlausible() }
        return size to video
    }

    /**
     * A video's poster is a picture this phone shows before the video lands, so it is screened at once (9rh6): on a
     * [fresh] delivery of someone else's message, never our own ([own]) and never a re-serve.
     */
    private suspend fun screenPoster(
        row: MessageEntity,
        fresh: Boolean,
        own: Boolean,
    ) {
        if (!fresh || own) return
        val poster = row.videoInfo()?.poster?.takeIf { it.isNotEmpty() } ?: return
        // After the row's commit and before its receipt: a failure here must never cost the ✓✓ or count a drop.
        runCatching { if (imageScreening.isPosterExplicit(poster)) messages.markPosterFlagged(row.id) }
            .onFailure { Log.w(TAG, "poster screen failed: ${it.javaClass.simpleName}") }
    }

    /**
     * What a notification describes: a video is named only for an older build's sake, so its line says "Video",
     * not "video.mp4" (ADR 2026-10.hmfe) — the content carries its description and no file name.
     */
    private fun notificationSubject(
        content: ChatContent,
        sealed: MessageContent?,
        video: VideoInfo?,
    ): Pair<ChatContent, String?> = if (video != null) content.copy(video = video) to null else content to sealed?.attachmentName

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
     *
     * [reDelivery] is the pre-decrypt exists-gate's call, for a DM whose row we already hold: see [answeredInEra].
     */
    private suspend fun acknowledge(
        env: RelayEnvelope,
        me: String,
        plane: DeliveryPlane,
        reDelivery: Boolean = false,
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
            if (reDelivery && answeredInEra(env, plane)) {
                metrics.onReAckSkipped()
                return
            }
            if (sealDmReceipt(env, me)) return
            ackCleartext(env.id, me)
        } else {
            // Broadcast/group: a unicast, point-to-point (relay = false) tick straight to the author when
            // it has a path — no NDP required (a fast-fanned message gets its receipt too). A GROUP tick
            // toward an absent sealed-capable author additionally escalates (escalatable): AckSync batches
            // the acks and originates ONE sealed ctl frame into custody/flood/spool, so the tick converges
            // exactly like the message it acks. Broadcast-room ticks stay best-effort-only by design. A
            // COMMONS post escalates like a group's: its author is reachable through the relay far more
            // often than through a radio, and the sealed tick rides the pair's DM scope, not the room's.
            ackSync.owe(env.id, env.senderId, escalatable = env.group != null || env.type == FrameType.COMMONS)
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

    /**
     * Whether a re-delivered DM to us is already answered by a sealed receipt of ours in the session's current era
     * (#176, ADR 2026-10.8hn6): one the ledger holds under the root we hold now, sealed less than its re-arm floor ago,
     * and still live in our own custody, so every carrier that meets the author re-serves it. Any one of those failing
     * re-seals: a new root after a reset (the old receipt was sealed into an era the author may have dropped), a
     * receipt our quota or the TTL evicted (nobody re-serves it), or the floor passed (the author may hold our receipt
     * in custody without ever having applied it). Never keyed on the copy's ratchet header or its signature: a
     * verbatim re-serve after a reset carries the old era's header. Never over LoRa, whose re-offer heals a lost tick
     * by its re-ack (ADR 039 §3, §7), and never for an author who reads only the cleartext receipt, which purges the DM
     * and ends the loop itself.
     */
    private suspend fun answeredInEra(
        env: RelayEnvelope,
        plane: DeliveryPlane,
    ): Boolean {
        if (plane == DeliveryPlane.LoRa || !canSealDmReceipt(env.senderId)) return false
        val now = clock()
        val receiptId = dmReAcks.heldReceipt(env.id, ratchet.rootTagFor(env.senderId), now) ?: return false
        return holdsLive(receiptId, now)
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
     *
     * A sealed receipt is filed in [dmReAcks] under [env]'s id alone (the room ids riding with it are AckSync's), with
     * the root tag the seal itself committed under ([RatchetSessions.sealDmTagged]) — never a session read after it,
     * since a reset adopted on the other delivery plane can commit in between and file the receipt in an era it was
     * not sealed in.
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
        // A frame already sealed to this author is a ride for the room ticks waiting on one (ADR 2026-09.y5f3
        // — the field case aa27's two carriers missed: the DM came off the spool, not the board). Capped
        // for the 3-packet LoRa hop, `ack` included; given back — never to the DM coalescer — if the seal
        // falls through, since that hold originates what it still holds and a room tick must never cost a row.
        val riding = ackSync.takeRiding(env.senderId, DmAckCoalescer.MAX_LORA_TICK_ACKS - 1)
        val content = MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, ack = env.id, acks = riding.takeIf { it.isNotEmpty() })
        val (plaintext, scheme) = content.sealBytes(peer.readsCryptoV3())
        val tagged =
            ratchet.sealDmTagged(
                env.senderId,
                bundle.dhPublicKey(),
                peerSpk = prekey,
                plaintext = plaintext,
                aad = aad,
                now = now,
                scheme = scheme,
            )
                ?: run {
                    ackSync.giveBackRiding(env.senderId, riding)
                    metrics.onReceiptSealedFallback()
                    return false
                }
        val sealed = tagged.envelope
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
        dmReAcks.record(env.id, tagged.rootTag, id, now)
        ackSync.rode(riding)
        metrics.onReceiptRidden(riding.size)
        return true
    }

    /**
     * Voice-call signaling (experimental), handed to the call layer post-commit — and, like a transfer offer, only
     * from an accepted DM thread: a stranger's call is dropped as silently as their file. The call layer rings.
     */
    private suspend fun onCallSignal(
        env: RelayEnvelope,
        payload: CallPayload,
        me: String,
    ) {
        if (isAccepted(env.senderId, me)) onCallCtl(env.senderId, payload, env.sentAt)
    }

    /**
     * Direct-transfer signaling, handed to the transfer manager post-commit — and only from an accepted DM
     * thread, so a stranger's offer is dropped as silently as a stranger's message lands in Requests. An
     * admitted OFFER is the one phase worth a notification, on the DM channel exactly as a message would be.
     */
    private suspend fun onTransferSignal(
        env: RelayEnvelope,
        payload: TransferPayload,
        me: String,
    ) {
        if (!isAccepted(env.senderId, me)) return
        val admitted = onTransferCtl(env.senderId, payload, env.sentAt)
        if (admitted && payload.phase == TransferPayload.PHASE_OFFER) {
            // Its own notification rather than a line in the thread's, so it can wear the transfer mark
            // instead of an emoji standing in for one — see Notifier.notifyTransferOffer.
            val peer = peers.find(env.senderId)
            notifier.notifyTransferOffer(
                peerId = env.senderId,
                peerName = peers.labelIndex().labelFor(env.senderId, peer?.name).text,
                peerAvatarBytes = peer?.avatarHash?.let { blobs.bytes(it) },
                fileName = payload.name.orEmpty(),
                sizeBytes = payload.size,
            )
        }
    }

    /** Fires a "new message" notification for an inbound chat in [conversationId] (skips our own and empty messages). */
    private suspend fun notifyIncoming(
        env: RelayEnvelope,
        content: ChatContent,
        conversationId: String,
        fileName: String?,
        hidden: Boolean,
        origin: MeshPostOrigin? = null,
    ) {
        notifyWithBody(env, conversationId, notificationBody(content, fileName, hidden), origin)
    }

    /**
     * What a notification says of [content]. A message content filtering hides ([hidden] — its body flagged, or the
     * words it quotes, `MessageEntity.hiddenByFilter`) says only that it is hidden — the thread collapses it behind
     * a tap, and a notification has no tap to reveal. Since only the public rooms screen on the way out (ADR
     * 2026-10.9rh6), this is where a DM's or a group's flagged text would otherwise land in the shade.
     * Attachment-only messages have a blank body, so a placeholder names the attachment and they still notify.
     */
    private suspend fun notificationBody(
        content: ChatContent,
        fileName: String?,
        hidden: Boolean,
    ): String {
        val labels = notificationLabels()
        if (hidden) return labels.hidden
        return GeoUri.describe(content.body, labels.location).ifBlank { attachmentPreview(content, fileName, labels) }
    }

    /** Posts [body] as a message from [env]'s sender in [conversationId] — the shared tail of every inbound notification. */
    private suspend fun notifyWithBody(
        env: RelayEnvelope,
        conversationId: String,
        body: String,
        origin: MeshPostOrigin? = null,
        // A reaction's line key ([reactionNotificationKey]): a later change replaces the line in place.
        key: String? = null,
    ) {
        val me = identity.nodeId()
        val peer = peers.find(env.senderId)
        // Collision-aware (ADR 058): "Alice (JoyfulFerret)" when another known peer is also an Alice.
        val labels = peers.labelIndex()
        val senderLabel = labels.labelFor(env.senderId, peer?.name)
        val peerAvatar = peer?.avatarHash?.let { blobs.bytes(it) }
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
                key = key,
            ) ?: return
        val conversation = conversationFaces.resolve(conversationId, me, labels)
        val selfAvatar = settings.ownAvatarHash.first()?.let { blobs.bytes(it) }
        notifier.notify(incoming, conversation, me, settings.displayName.first(), selfAvatar)
    }

    /** Who a heard Meshtastic post's notification is keyed on, named after and wears — see [notifyIncoming]. */
    private class HeardSpeaker(
        val id: String,
        val name: String,
        val avatar: ByteArray?,
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
        return HeardSpeaker(id, name, contact?.avatarHash?.let { blobs.bytes(it) })
    }

    /**
     * Notification stand-in for a message whose only content is an attachment, so it still says something
     * useful on the lock screen. This layer holds no `Context` (it is deliberately Android-light,
     * `rules/mesh.md`), so the words come in as [NotificationLabels], read from the same strings the chat list's
     * preview uses.
     *
     * A shared position in the body is named the same way, by [GeoUri.describe] at both call sites — a
     * lock-screen line should say "📍 Location", not print coordinates — mirroring `messagePreview` in
     * `ui/chat/AttachmentLabels.kt`, the `Context`-holding twin this is changed together with.
     *
     * A [fileName] wins over the mime because it is the more specific fact and the only one a *file* has:
     * an arbitrary file's mime is whatever its sender's provider called it, and "📎 application/zip" would be
     * a worse lock-screen line than the name the sender actually chose. Already normalized on decode
     * ([app.getknit.knit.mesh.protocol.AttachmentName]), so it is safe to draw.
     *
     * Only the MIME can speak for a GIF here: the notification fires before the blob is pulled, so the frames
     * that make an animated WebP a GIF ([deriveAnimation]) are not in hand yet, and it reads as a photo.
     */
    private fun attachmentPreview(
        content: ChatContent,
        fileName: String?,
        labels: NotificationLabels,
    ): String =
        attachmentLabel(content.attachmentHash, content.attachmentMime, fileName, video = content.video != null, labels)
            ?: content.body

    /**
     * [attachmentPreview]'s rule over the bare fields, so an inbound frame and a stored row ([noticeReaction]'s
     * target) name an attachment the same way. Null when there is no attachment ([hash] null).
     */
    private fun attachmentLabel(
        hash: String?,
        mime: String?,
        fileName: String?,
        video: Boolean,
        labels: NotificationLabels,
    ): String? =
        when {
            hash == null -> null
            video -> labels.video
            fileName != null -> labels.file.format(fileName)
            VoiceAudio.isVoice(mime) -> labels.voice
            mime == LinkPreviewBlob.MIME -> labels.link
            mime == "image/gif" -> labels.gif
            else -> labels.photo
        }

    /**
     * Notifies that someone reacted to a message we wrote (ADR 2026-10.59fu): "Reacted 👍 to “see you at 6”",
     * one line per reactor per message ([reactionNotificationKey]) that a changed emoji replaces and a
     * retraction withdraws. Called only after [reaction]'s write landed and changed what the reactor shows
     * ([ReactionRepository.apply]'s answer) — on the cleartext path after the write, on both sealed paths after
     * the ratchet commit — so a custody re-serve, a spool copy or a replay never notifies twice.
     *
     * Read on the local delivery path only, never in custody or relay. The target must be ours and stored (an
     * orphan cannot be: our own sends are saved before they leave), and the reactor must be someone who reads
     * that thread — the DM's peer, a member on the group's roster, anyone in a room — because a message id is
     * visible to every carrier, and a carrier's reaction to it is not a conversation we are in. A blocked reactor
     * never notifies (the cleartext path already dropped them; the sealed ones did not ask). The thread must
     * pass the same [isAccepted] gate a message does.
     */
    private suspend fun noticeReaction(
        env: RelayEnvelope,
        reaction: ReactionEntity,
    ) {
        val me = identity.nodeId()
        val reactor = reaction.reactorNodeId
        if (reactor == me || reactor in settings.blockedNodeIds.first()) return
        val target = messages.find(reaction.messageId) ?: return
        if (!target.writtenBy(me) || !readsThread(reactor, target.conversationId)) return
        if (!isAccepted(target.conversationId, me)) return
        val key = reactionNotificationKey(reaction.messageId, reactor)
        val emoji = reaction.emoji
        if (emoji == null) {
            notifier.retractReaction(target.conversationId, key)
            return
        }
        val labels = notificationLabels()
        val snippet =
            if (target.hiddenByFilter(settings.contentFilteringEnabled.first())) {
                labels.hidden
            } else {
                reactionSnippet(
                    GeoUri.describe(target.body, labels.location),
                    attachmentLabel =
                        attachmentLabel(
                            target.attachmentHash,
                            target.attachmentMime,
                            target.attachmentName,
                            video = target.videoDurationMs != null,
                            labels,
                        ),
                )
            }
        notifyWithBody(env, target.conversationId, labels.reaction.format(emoji, snippet), key = key)
    }

    /** Whether [nodeId] reads [conversationId]: the DM's peer, a member on the group's roster, anyone in a room. */
    private suspend fun readsThread(
        nodeId: String,
        conversationId: String,
    ): Boolean =
        when (Conversations.kindFor(conversationId)) {
            ConversationKind.DM -> nodeId == conversationId
            ConversationKind.GROUP -> groups.find(conversationId)?.let { nodeId in GroupMembersStore.decode(it.members) } == true
            else -> true
        }

    /** Fires a "you were mentioned" notification on the Mentions channel for an inbound chat in [conversationId]. */
    private suspend fun notifyMention(
        env: RelayEnvelope,
        content: ChatContent,
        conversationId: String,
        fileName: String?,
        hidden: Boolean,
    ) {
        val me = identity.nodeId()
        val peer = peers.find(env.senderId)
        // Collision-aware (ADR 058): "Alice (JoyfulFerret)" when another known peer is also an Alice.
        val labels = peers.labelIndex()
        val senderLabel = labels.labelFor(env.senderId, peer?.name)
        val peerAvatar = peer?.avatarHash?.let { blobs.bytes(it) }
        val body = notificationBody(content, fileName, hidden)
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
        val conversation = conversationFaces.resolve(conversationId, me, labels)
        val selfAvatar = settings.ownAvatarHash.first()?.let { blobs.bytes(it) }
        notifier.notifyMention(incoming, conversation, me, settings.displayName.first(), selfAvatar)
    }

    /**
     * Pins an inbound peer's profile (self-cert check → last-writer-wins → immutable-pin guard → upsert),
     * then flushes/replays anything that was waiting on the sender's key.
     */
    private suspend fun handleProfile(
        env: RelayEnvelope,
        wire: WireEnvelope,
    ) {
        // Our own profile looping back — a peer re-served it, or the LoRa plane echoed it — is not a peer to
        // pin. `verifyInbound` admits self frames so a wiped custody re-carries them (and custody has already
        // taken this one, upstream of the type dispatch); everything below is for OTHER people's profiles.
        // Without this guard the row `peers[me]` appears, with our own key, capabilities and prekey, and
        // every path that treats a pinned row as a sealable peer takes us for one: `flushGroupKeys(me)` seals
        // every group's seed to ourselves, the ratchet opens a session with ourselves, the echo cannot be
        // opened and trips the reset heuristic, the reset re-flushes the seeds — a self-sustaining loop of
        // sealed self-addressed frames, one every hour or two on every phone, each flooded, custodied and
        // carried over LoRa airtime (found via the Your mesh screen's custody count, 2026-09-13).
        // Still dropped, but first noted: the stamp is the one thing a re-served self profile can tell us —
        // whether this phone ever minted it (mesh/CloneWatch, ADR 2026-09.ypcc).
        if (env.senderId == identity.nodeId()) {
            onSelfProfile(env.sentAt)
            return
        }
        val content = decodeCounted<ProfileContent>(env.payload) ?: return
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
        // The LWW key is the sender's profile *version*, not the frame's `sentAt` — `sentAt` is now a publish
        // stamp the sender refreshes on a cadence to keep the frame inside custody's `sentAt + ttl` window
        // (ADR 022), so it moves without the profile having changed. A peer predating the field sends no
        // `version`, and for those `sentAt` is exactly what it used to mean, which makes the fallback exact.
        // Bounded before it becomes either watermark below: the peer picks this number, and stored raw a
        // far-future one would outrank every later profile of theirs, on both paths, for good.
        val version = clampFuture(content.version ?: env.sentAt)
        val prekey = verifiedPrekey(content, pubKey, env.senderId)
        // The read, the blob check and the write share one write transaction (#98): the avatar writers run on
        // another collector and copy the same row, so without it either write can put back the row the other
        // one read — reverting this profile, or the pushed avatar that landed while we checked for its blob.
        val stored =
            db.withWriteTransaction {
                storeProfileRow(env.senderId, content, pubKey, version, prekey, seenAt = heardAt(env, clock()))
            }
        val applied =
            when (stored) {
                StoredProfile.Stale -> {
                    return
                }

                StoredProfile.PinRefused -> {
                    Log.w(TAG, "drop profile from ${env.senderId}: pinned key change refused (collision/impersonation?)")
                    metrics.onDropped(DropReason.PIN_CHANGE_REFUSED)
                    // Surfaced in the thread as well as counted: this is the one profile event a user could act
                    // on, and a metric only a maintainer reads is the wrong place for it. Effectively unreachable
                    // by design — a key that doesn't derive back to the sender's nodeId was already dropped
                    // above, so arriving here needs a 128-bit collision or a corrupted pin — which is exactly why
                    // it should be visible if it ever does happen rather than silent.
                    savePeerNotice(env.senderId, StatusNotices.keyPinRefused(env.senderId, clampFuture(env.sentAt)))
                    return
                }

                is StoredProfile.Applied -> {
                    stored
                }
            }
        val name = TextLimits.cleanReceived(content.name, TextLimits.DISPLAY_NAME)
        applyPresentationFollowUps(
            env.senderId,
            applied.previous,
            name,
            content.avatarHash,
            applied.haveAvatar,
            version,
            applied.stalePresentation,
        )
        applyDeviceTagBlockContinuity(env.senderId, content.deviceTag)
        // The sender's key is now pinned: retransmit any DMs to them that were stuck awaiting it, and
        // re-send any group epoch seeds their outbox still shows unacked (their prekey may be new).
        flushPending(env.senderId)
        flushGroupKeys(env.senderId, false)
        // Keep this peer's verbatim signed profile beside the pin, newest publish stamp winning (clamped: the peer
        // picks it), so this phone can prove their key to a newcomer for as long as it carries their frames —
        // after a restart, and after custody has lost the profile to the quota or the TTL (ADR 2026-09.g64k).
        peers.recordProfileFrame(env.senderId, wire, clampFuture(env.sentAt))
        // Cache it for the same re-serve, and resolve any key request we (or a node we're relaying for) had
        // outstanding for it.
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

    /** What [storeProfileRow] did with a cleartext profile, for the follow-ups [handleProfile] runs after commit. */
    private sealed interface StoredProfile {
        /** Older than the row on both watermarks: nothing moved. */
        data object Stale : StoredProfile

        /** A different key for a pinned node id: refused, nothing moved. */
        data object PinRefused : StoredProfile

        /** The row was written; [previous] is how it stood before (null on first contact). */
        class Applied(
            val previous: PeerEntity?,
            val haveAvatar: Boolean,
            val stalePresentation: Boolean,
        ) : StoredProfile
    }

    /**
     * The peer-row half of [handleProfile]: read, gate and write the row. Runs inside the caller's write
     * transaction, so it must stay free of DataStore reads, the ratchet lock and anything that sends.
     *
     * `@Suppress("CyclomaticComplexMethod")`: the body is a straight-line sequence of null-coalesced field
     * resolutions and guards — not genuinely complex — and every guard is load-bearing (the #14 immutable-pin
     * guard among them), so suppress rather than reshuffle it.
     */
    @Suppress("CyclomaticComplexMethod")
    private suspend fun storeProfileRow(
        senderId: String,
        content: ProfileContent,
        pubKey: String,
        version: Long,
        prekey: app.getknit.knit.mesh.protocol.PrekeyInfo?,
        seenAt: Long,
    ): StoredProfile {
        val existing = peers.find(senderId)
        // Last-writer-wins, split across two watermarks. The key is immutable per nodeId (a different key
        // would be a hash collision, excluded by the caller's derivation check), so an out-of-order or
        // re-served copy can never change the pinned key — it could only revert name/status. A first profile
        // (existing == null) is always accepted, so this never blocks recovering a missing key.
        //
        // Presentation and prekey are gated SEPARATELY because a sealed CTL_PROFILE advances `updatedAt`
        // while deliberately carrying no prekey (ADR 020). Gating both on that one watermark let a sealed
        // presentation update suppress the cleartext frame that carries the prekey — and since a live spool
        // EVENT can outrun a heal-round pull, that race lands exactly when the prekey matters most.
        val stalePresentation = existing != null && version < existing.updatedAt
        val stalePrekey = existing != null && version < (existing.prekeyProfileAt ?: 0L)
        if (stalePresentation && stalePrekey) return StoredProfile.Stale
        // Immutable pin: a peer's key is bound to its nodeId, so once pinned it can only "change" via a
        // hash collision — an impersonation attempt. Refuse it: keep the first-pinned key and its verified
        // badge rather than let a swapped-in key inherit a verified contact (finding #14). A first profile
        // (existing?.pubKey == null) still pins normally; a same-key re-profile still updates name/status.
        val pinned = existing?.pubKey
        if (pinned != null && pinned != pubKey) return StoredProfile.PinRefused
        val advertised = content.avatarHash
        // The stored avatarHash means "bytes are present locally" (and screened through — [avatarAdoptable]):
        // adopt the advertised hash only then, otherwise keep the current avatar (if any) until the new one is
        // fetched. [haveAvatar] — held at all — is what decides the pull.
        val haveAvatar = advertised != null && blobStore.has(advertised)
        // The pinned key is guaranteed unchanged here (a differing key was refused above), so carrying
        // the prior [verified] state through the upsert is safe.
        val base = existing ?: PeerEntity(senderId)
        val name = TextLimits.cleanReceived(content.name, TextLimits.DISPLAY_NAME)
        peers.upsert(
            base.copy(
                // Clean inbound too: our own cap and line rules only bound what we originate, not what a peer sends
                // (controls, bidi overrides and line breaks out, ADR 2026-10.2kfr). The signed frame stays verbatim.
                name = if (stalePresentation) base.name else name,
                status = if (stalePresentation) base.status else TextLimits.cleanReceived(content.status, TextLimits.STATUS),
                pubKey = pubKey,
                verified = existing?.verified ?: false,
                deviceTag = content.deviceTag ?: existing?.deviceTag,
                avatarHash =
                    if (stalePresentation) {
                        base.avatarHash
                    } else {
                        resolveAvatarHash(advertised, avatarAdoptable(advertised), existing?.avatarHash)
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
                loraKey = if (stalePresentation) base.loraKey else content.loraKey,
                // Heard from now, stale presentation or not: a row this profile pins is never born quiet, which
                // would let the name-clash fade judge it before the metrics tick banks its stamp (ADR 2026-10.k8ge).
                lastSeenAt = maxOf(base.lastSeenAt, seenAt),
            ),
        )
        return StoredProfile.Applied(existing, haveAvatar, stalePresentation)
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
        // A signature proves the peer chose it, not that it is a key: a low-order point verifies, and every seal
        // against it throws (#172). Such a peer reads as having no prekey and gets v1.
        if (!RatchetCrypto.acceptsPublicKey(prekey.pub)) {
            Log.w(TAG, "ignore prekey from $senderId: X25519 refuses it")
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
            blobExchange.want(advertised, author = senderId)
        }
    }

    /**
     * The avatar hash to store for a peer from an inbound profile. [advertised] is what the profile
     * carries (null = the peer has no avatar):
     *  - adopt [advertised] once its blob is local and screening let it through ([adoptable], from
     *    [avatarAdoptable]);
     *  - null when the peer advertises no avatar — an explicit removal, since a set avatar always rides as
     *    a non-null hash and [handleProfile]'s last-writer-wins gate guarantees this profile is the newest
     *    state, so the clear propagates instead of clinging to the old photo;
     *  - otherwise keep [current] until the advertised (but not-yet-fetched) blob arrives, or for good while
     *    content filtering refuses it.
     */
    private fun resolveAvatarHash(
        advertised: String?,
        adoptable: Boolean,
        current: String?,
    ): String? =
        when {
            adoptable -> advertised
            advertised == null -> null
            else -> current
        }

    /**
     * Whether a profile may point its peer at the advertised avatar [hash] now: the bytes are held and, with
     * content filtering on, screening has not refused them — the avatar half of [groupPhotoDecision]'s rule.
     * Held alone is not enough (#109). A refused avatar's bytes can stay, pinned in custody by the sealed
     * profile whose cleartext names them, so [adoptAdvertisedAvatar]'s refusal does not delete them; and the
     * spool fetches from custody, so the bytes can land, screened, before any profile is applied and with no
     * owner for [adoptAdvertisedAvatar] to refuse. Either way the next profile carrying the hash finds it held.
     */
    private suspend fun avatarAdoptable(hash: String?): Boolean = hash != null && blobStore.has(hash) && !refusedImage(hash)

    /**
     * Whether screening refused [hash] while content filtering is on. The setting gates receive-side hiding
     * only — the scan runs and caches its verdict regardless — so with it off a flagged image is shown.
     */
    private suspend fun refusedImage(hash: String): Boolean =
        settings.contentFilteringEnabled.first() && imageScreening.isImageFlagged(hash)

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
        // A pulled avatar is screened in MeshBlobStore.ingest, on either plane — it holds no message row, so
        // nothing local can claim it is audio and the screen always runs (knit/knit-next#30 closed the
        // header-mime skip that let a serving peer suppress it; #109 the spool's bare insert). With content
        // filtering on, don't adopt it if flagged explicit (the setting gates receive-side hiding, so off →
        // adopt anyway). The bytes may stay — the sealed profile that named them pins them in custody — which
        // is why the profile writers ask [avatarAdoptable], not only whether the blob is held.
        if (refusedImage(hash)) {
            owners.forEach { advertisedAvatars.remove(it) }
            blobs.deleteIfUnreferenced(hash)
            return
        }
        owners.forEach { nodeId ->
            advertisedAvatars.remove(nodeId)
            pointPeerAtAvatar(nodeId, hash, createRow = false)?.let { blobs.deleteIfUnreferenced(it) }
        }
    }

    /**
     * Points [nodeId]'s row at the now-local avatar [hash] and returns the hash it replaced, for the caller to
     * reclaim after the commit (null when nothing was replaced). A missing row is created only when
     * [createRow] (a pushed avatar can outrun the profile that pins its sender).
     *
     * The read and the write share one write transaction (#98). The profile writers run on the router's
     * collector while these run on the files path, and a photo change sends both at once: a row read outside
     * the lock and written back whole reverts a profile that landed in between — name, version, prekey pin.
     */
    private suspend fun pointPeerAtAvatar(
        nodeId: String,
        hash: String,
        createRow: Boolean,
    ): String? =
        db.withWriteTransaction {
            val peer = peers.find(nodeId) ?: if (createRow) PeerEntity(nodeId) else return@withWriteTransaction null
            if (peer.avatarHash == hash) return@withWriteTransaction null
            peers.upsert(peer.copy(avatarHash = hash))
            peer.avatarHash
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
        // Screened before it is stored, as MeshBlobStore.ingest does: a profile writer that finds the bytes
        // held must find their verdict too, or it adopts an avatar this push is about to refuse.
        imageScreening.screenImage(hash, bytes)
        blobs.insert(hash, mime, bytes)
        File(srcPath).delete()
        advertisedAvatars.remove(nodeId) // pushed directly; no need to also pull it
        // With content filtering on, don't adopt an explicit avatar: leave the peer on its monogram
        // fallback and drop the blob (the setting gates receive-side hiding, so off → adopt anyway).
        if (refusedImage(hash)) {
            blobs.deleteIfUnreferenced(hash)
            return
        }
        pointPeerAtAvatar(nodeId, hash, createRow = true)?.let { blobs.deleteIfUnreferenced(it) }
    }

    /**
     * A held attachment and a message row naming it have just met, in whichever order they arrived: the bytes
     * after the row ([onObtained]) or the row after the bytes (`deliverChat`'s already-held branch). Everything
     * this phone derives from an attachment's bytes for its row runs here, so a hook added for one order cannot
     * be missed by the other (#132 was the voice note's, missing from the second). [key] and [mime] are the
     * row's — the frame's own on delivery, any stored row's on arrival.
     *
     * Screening is here because [MeshBlobStore.ingest] only ever sees the stored ciphertext of an encrypted
     * attachment, so this is where receive-side image moderation actually runs for DM/group attachments. The key
     * is also what tells [MeshBlobStore.ingest] a voice note is genuinely sealed and so worth skipping; a key-less
     * blob is plaintext and is screened there whatever mime claims it is. Each hook is a no-op for a hash that is
     * not its kind, and for a blob no row claims (a relayed attachment, an avatar): [mime] is then null.
     */
    private suspend fun describeHeldAttachment(
        hash: String,
        key: String?,
        mime: String?,
    ) {
        screenHeldAttachment(hash, key, mime)
        deriveVoiceMeta(hash, key, mime)
        deriveVideoMeta(hash, key, mime)
        deriveAnimation(hash, key, mime)
        identities.notePlain(hash)
    }

    /**
     * Shows a key-less attachment — a room post, or a forward of one — from a sealed copy this phone already holds,
     * when the frame's [author] was party to the conversation it came through (ADR 2026-10.mptm, and
     * [AttachmentIdentities.localCopy] for why only then). The copy enters through [MeshBlobStore.ingest] like any
     * arrival, so it is screened before it is held (#109), and the obtained hook runs as it would after a pull.
     * False — pull it — when there is no such copy.
     */
    private suspend fun copyFromOwnStore(
        hash: String,
        author: String,
    ): Boolean {
        if (blobStore.storing(hash)) return true // the other door (delivery or custody) is copying it right now
        val (bytes, mime) = identities.localCopy(hash, author) ?: return false
        blobStore.ingest(hash, mime, bytes)
        onObtained(hash)
        return true
    }

    /**
     * Screens a held attachment whose row we now have, caching the verdict under the stored [hash] — the same
     * key the chat UI's flagged set uses, so a flagged attachment hides behind a tap-to-reveal. With a base64
     * [key] the stored blob is ciphertext: it is decrypted here and the plaintext screened by [mime] (a picture
     * as an image; a link-preview card as a card, picture and text). Without a key only one shape needs this
     * path: a **room card** whose blob was relayed before its row arrived, which [MeshBlobStore.ingest]
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

                // A room video whose bytes landed before any row named it was tried as a picture there and got no
                // verdict; now the row says what it is (ADR 2026-10.hmfe). Idempotent per hash, like the others.
                VideoAttachment.isVideo(mime) -> stored

                else -> return
            }
        imageScreening.screenAttachment(hash, plain, mime, isRoom = key == null)
    }

    /**
     * If the row names a held blob as a **voice note** ([mime]), derive its playing time and waveform from the
     * audio and record them on every row that references it. This is the receiving half of a deliberate
     * symmetry — the sender derives the same two values from the same bytes at ingest, using the same
     * [VoiceAudio] implementation, so the two ends agree by construction and neither value ever has to ride the
     * wire (see `docs/WIRE_COMPAT.md`).
     *
     * Decrypts with [key] exactly as [screenHeldAttachment] does when the attachment is E2E; a plaintext blob is
     * described as-is. A no-op for anything that isn't audio, and once every row naming it has its duration —
     * a re-served frame brings its held blob back here each time, and the description is a full decode. Never
     * throws — a waveform we couldn't compute is a flat bubble, not a dropped delivery, and `rules/mesh.md`
     * requires inbound handlers to stay silent on failure.
     */
    private suspend fun deriveVoiceMeta(
        hash: String,
        key: String?,
        mime: String?,
    ) {
        runCatching {
            if (!VoiceAudio.isVoice(mime) || !messages.voiceMetaWanted(hash)) return
            val stored = blobs.bytes(hash) ?: return
            val audio = if (key == null) stored else AttachmentCrypto.open(stored, b64d(key)) ?: return
            val described = VoiceAudio.describe(audio)
            if (described.isEmpty) return
            messages.setVoiceMeta(hash, described.durationMs, described.peaks)
        }.onFailure { Log.w(TAG, "voice metadata derivation failed: ${it.javaClass.simpleName}") }
    }

    /**
     * If the row names a held blob as a video ([mime]), read its duration and upright size off the bytes and record
     * them over the sender's claim on every row naming it (ADR 2026-10.hmfe) — "derive, don't carry" once the bytes
     * are here, as for a voice note. Decrypts with [key] as [deriveVoiceMeta] does. Never throws.
     */
    private suspend fun deriveVideoMeta(
        hash: String,
        key: String?,
        mime: String?,
    ) {
        runCatching {
            if (!VideoAttachment.isVideo(mime)) return
            val stored = blobs.bytes(hash) ?: return
            val video = if (key == null) stored else AttachmentCrypto.open(stored, b64d(key)) ?: return
            val meta = VideoFrames.meta(video) ?: return
            messages.setVideoMeta(hash, meta.durationMs, meta.width, meta.height)
        }.onFailure { Log.w(TAG, "video metadata derivation failed: ${it.javaClass.simpleName}") }
    }

    /**
     * If the row names a held blob as an image whose container can animate ([mime]), count its frames and mark
     * every row that references it — the recipient's half of the "GIF" label, read off the same bytes the sender
     * read at ingest ([AnimatedImage]), so it costs no wire field. Decrypts with [key] as [deriveVoiceMeta] does;
     * a no-op for a still image, anything else, and a blob no row claims. Never throws: a GIF we could not read
     * is labelled a photo, not a dropped delivery.
     */
    private suspend fun deriveAnimation(
        hash: String,
        key: String?,
        mime: String?,
    ) {
        runCatching {
            if (mime !in AnimatedImage.ANIMATABLE_MIMES) return
            val stored = blobs.bytes(hash) ?: return
            val image = if (key == null) stored else AttachmentCrypto.open(stored, b64d(key)) ?: return
            if (AnimatedImage.isAnimated(image)) messages.markAttachmentAnimated(hash)
        }.onFailure { Log.w(TAG, "animation check failed: ${it.javaClass.simpleName}") }
    }

    internal companion object {
        /** Types a missing sender key never spends a key request on — see the comment in `verifyInbound`. */
        private val NO_KEY_REQUEST_TYPES =
            setOf(
                FrameType.KEY_REQ,
                FrameType.PROFILE,
                FrameType.TYPING,
                FrameType.COMMONS,
                FrameType.TALK,
                FrameType.BLOB_HAVE,
                FrameType.BLOB_HAS,
                FrameType.BLOB_PROGRESS,
                FrameType.BLOB_CANCEL,
            )

        // Same tag as MeshManager on purpose: these inbound verify/drop log lines are grepped in field
        // diagnostics, so the extraction must not change them.
        private const val TAG = "MeshManager"

        /** Longest message or node id a quote may name; every id this protocol mints is far shorter. */
        private const val MAX_QUOTED_ID = 64

        /** Most acked ids applied from one sealed CTL_RECEIPT — 2× AckSync's send-side batch cap. */
        private const val MAX_RECEIPT_ACKS = 2 * AckSync.MAX_BATCH_ACKS

        /**
         * How often one member's rejoin may force our rekey ([rejoinRekeyDue]). Membership itself is never
         * floored — the rejoiner is back the moment their frame lands — only the epoch re-mint it triggers,
         * so the worst a leave/rejoin loop costs the roster is one mint and one seed fan-out an hour.
         */
        private const val REJOIN_REKEY_FLOOR_MS = 60 * 60_000L

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
        @VisibleForTesting
        internal val RESET_TRIGGERING_DROPS =
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
