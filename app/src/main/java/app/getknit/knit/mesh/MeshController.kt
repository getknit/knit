package app.getknit.knit.mesh

import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.spool.SpoolStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The app-facing surface of the mesh: the reads (nearby presence, radio health, typing) plus the
 * send/lifecycle actions the UI ViewModels, the foreground [MeshService], and the notification/debug
 * entry points use. Extracted as an interface over [MeshManager] (ARCHITECTURE_REVIEW #15) so those
 * callers bind a narrow seam a test can fake, instead of the concrete orchestrator (which drags in the
 * whole DI graph — transport, repos, crypto, DTN services). [MeshManager] is the only production impl.
 *
 * The `sendChat` defaults live here (an override may not restate them), so every caller must reach the
 * mesh through this interface to get them — which is why all consumers inject `MeshController`, not the
 * concrete class.
 */
@Suppress("TooManyFunctions") // the app's whole mesh surface behind one seam; splitting it would only scatter it
interface MeshController {
    /** Number of nearby peers for the UI status header — the smoothed **short-range** reachable set. */
    val neighborCount: StateFlow<Int>

    /**
     * Nearby peers — everything in the app that says *nearby*, *online* or *connected*. The smoothed
     * reachable set restricted to the **short-range** planes (BLE/NAN), because only those sight the peer's
     * *own* radio. A long-range plane's sighting is not proximity and not even necessarily the peer: LoRa
     * keys presence on the frame **author**, and a gateway puts other people's frames on air, so a peer
     * with no board at all can be "reachable over LoRa" (ADR 044). Those live in [reachable] instead.
     */
    val neighbors: StateFlow<Set<Peer>>

    /**
     * Every peer we can currently reach over *any* plane — [neighbors] plus the long-range ones. Strictly a
     * superset. Only the Diagnostics screen reads it, to separate a direct connection from relay reach;
     * anything asking "is this peer here" wants [neighbors]. Defaulted for the fakes.
     */
    val reachable: StateFlow<Set<Peer>> get() = neighbors

    /**
     * The [TransportKind]s that count as short-range, so the UI can tell a proximity tag from a relay one
     * without restating [MeshTransport.shortRange]. Defaulted for the fakes.
     */
    val shortRangeKinds: Set<TransportKind> get() = TransportKind.entries.toSet()

    /** Radio health for the Diagnostics screen (Healthy vs Degraded). */
    val transportHealth: StateFlow<TransportHealth>

    /** Per-radio status for the Diagnostics screen (Bluetooth vs Wi-Fi Aware: health + link/nearby counts). */
    val transportStatuses: StateFlow<List<TransportStatus>>

    /** nodeId → the radios each node is reachable over, so Diagnostics can tag a node BLE / NAN. */
    val peerTransports: StateFlow<Map<String, Set<TransportKind>>>

    /** conversationId → the set of peers currently shown as "typing" there, for the chat UI. */
    val typing: StateFlow<Map<String, Set<String>>>

    /** Starts the mesh engine (called by the foreground [MeshService]). */
    fun start()

    /** Stops the mesh engine and tears down the session. */
    fun stop()

    /** Triggers an immediate rescan/reconnect (heartbeat/motion) and sweeps stale carry. */
    fun heal()

    /**
     * Runs the spool group-root mint pass now (`docs/SPOOL_PROTOCOL.md` §3.2) instead of waiting for the
     * next [heal]: C-3.2-2 says the preferred minter mints *immediately*, and the creator of a group is
     * that minter — but nothing in the creation path used to ask, so a brand-new group sat off the
     * Internet plane ("Not covered by relays yet") until a heartbeat, a motion trigger or a foreground
     * resume happened along.
     *
     * Deliberately narrower than [heal], which is the whole background-survival basket: a group creation
     * is a UI action, and forcing a radio rescan/reconnect out of one would disturb the very links about
     * to carry that group's first message. Idempotent — the pass re-derives what is due.
     */
    fun mintGroupRoots()

    /** Tears down and re-establishes the transport (e.g. after Bluetooth toggles back on). */
    fun restart()

    /**
     * The Internet (spool) plane's live state — one entry per configured spool, each carrying its scopes'
     * convergence. Empty when the plane is off, unconfigured, or absent from the build. Read-only
     * observability for Diagnostics and the debug bridge; the digests here are the only way to see a
     * scope diverging at a spool, since nothing else in the UI reflects the plane.
     */
    fun spoolStatus(): List<SpoolStatus> = emptyList()

    /**
     * Per-peer DM ratchet state, for diagnosing a session that will not recover. Debug-only observability:
     * every gate in the reset path returns silently, so from outside there is no way to tell a peer whose
     * heuristic has not fired from one we hold no prekey for — states that look identical and need opposite
     * remedies. Empty when the mesh is not running.
     */
    suspend fun ratchetState(): List<RatchetPeerState> = emptyList()

    /**
     * A contact card for [peerId] was imported and its key pinned: register the intro handshake
     * (`IntroSync.want`) and ask the radio mesh for the peer's profile (`KeyExchange.want`), so the sealed
     * intro goes out the moment the peer's prekey is known — from a flood, a LoRa beacon, or the spool pair
     * scope. Idempotent; a no-op for a peer whose session is already confirmed.
     */
    suspend fun importContact(peerId: String) {}

    /** Where the contact-card intro with [peerId] stands, or null when none is pending or recently confirmed. */
    fun introState(peerId: String): Flow<IntroState?> = flowOf(null)

    /**
     * Seals and floods a session reset to [peerId] with **no** heuristic in front of it — no distinct-frame
     * threshold, no per-peer time floor. The escape hatch for a pair already wedged before a fix shipped:
     * the recovery path only runs when the heuristic fires, and a pair that cannot produce countable
     * failures never gets there. Returns null on success, or why it declined.
     */
    suspend fun forceRatchetReset(peerId: String): String? = "mesh not running"

    /**
     * Composes a chat message (optionally with an ingested image [attachment]), stores it locally, and
     * floods it. [recipientId] null + null [group] is the broadcast room; a node id is a 1:1 DM; a non-null
     * [group] is a group message. Returns false without sending if on-device filtering flags [text].
     */
    suspend fun sendChat(
        text: String,
        attachment: AttachmentStore.Ingested? = null,
        mentions: List<Mention> = emptyList(),
        recipientId: String? = null,
        group: GroupInfo? = null,
        replyTo: ReplyRef? = null,
    ): Boolean

    /**
     * Puts a post typed in the **Meshtastic room** ([app.getknit.knit.data.message.Conversations.MESHTASTIC])
     * on this phone's own board's primary channel, and stores it locally once the board has queued it. Nothing
     * crosses Knit's mesh: the post is not flooded, not custodied, and never handed to another phone.
     *
     * Separate from [sendChat] rather than a conversation id passed to it: `sendChat` reads the destination
     * off `recipientId`/`group`, so a room id handed to it would be taken for a peer node id and minted as a
     * DM addressed to nobody. The default refuses with [PublicPostRefusal.NO_BOARD], which is what a fake and
     * a build without the LoRa plane both want.
     */
    suspend fun sendPublicPost(text: String): PublicPostOutcome = PublicPostOutcome.Refused(PublicPostRefusal.NO_BOARD)

    /** Floods a group metadata update (e.g. a rename) immediately, independent of any chat message. */
    suspend fun sendGroupUpdate(group: GroupInfo)

    /** Floods a signed `groupleave` frame announcing that we've left [groupId]. */
    suspend fun sendGroupLeave(groupId: String)

    /**
     * Toggles this device's emoji reaction on [messageId] and floods the change — sealed when the
     * target conversation can carry it ([recipientId] for a DM thread, [group] for a group thread;
     * both null = broadcast room, always cleartext). The caller passes the thread context it already
     * holds, like [sendChat]; the manager never re-derives it from the message row. Ignored (logged) when
     * [emoji] is blank or exceeds `TextLimits.REACTION`.
     */
    suspend fun sendReaction(
        messageId: String,
        emoji: String,
        recipientId: String? = null,
        group: GroupInfo? = null,
    )

    /** Broadcasts a best-effort "now typing" cue for [conversationId] to nearby peers. */
    suspend fun sendTyping(conversationId: String)
}

/**
 * One peer's DM ratchet state, as the reset path sees it — each field is a gate that can silently stop a
 * wedged session from recovering (see [MeshController.ratchetState]).
 *
 * [capRatchet], [peerPrekeyId] and [peerPrekeyPinned] are the peer material an X3DH initiation needs: with
 * any of them missing we can never re-establish from this side, no matter what the peer sends us.
 * [lastResetSentAt] is the per-peer floor's anchor, [confirmed] says whether the session is one the scope
 * table will even export, and [sendEpoch] moving while the peer still cannot read us is the signature of a
 * split brain rather than a missing session.
 */
class RatchetPeerState(
    val peerId: String,
    val name: String,
    val capRatchet: Boolean,
    val peerPrekeyId: Int?,
    val peerPrekeyPinned: Boolean,
    val hasSession: Boolean,
    val confirmed: Boolean,
    val sendEpoch: Int,
    val lastResetSentAt: Long,
    /** The era stamp both peers converge on — two devices disagreeing here are in different eras. */
    val establishedAt: Long = 0L,
    val weAreInitiator: Boolean = false,
    val highestPeAcked: Int = 0,
    /** First 8 hex of SHA-256(root): comparable across devices without exposing the root itself. */
    val rootHash: String? = null,
    val prevRootExpiresAt: Long = 0L,
    /** Whether the resolved-init idempotence anchor is set ([RatchetEngine.SessionState.peerInitEphPub]). */
    val hasPeerInitAnchor: Boolean = false,
    /** Our surviving local epoch privs for this peer, most recently minted first, as (epoch, createdAt). */
    val localEpochs: List<Pair<Int, Long>> = emptyList(),
)
