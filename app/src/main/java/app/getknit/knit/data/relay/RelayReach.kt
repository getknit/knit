package app.getknit.knit.data.relay

import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.spool.ScopeAttachments
import app.getknit.knit.mesh.spool.SpoolStatus

/**
 * What the Internet-relay plane can currently do, flattened out of `SpoolStatus` into the few facts the
 * UI actually reasons about. Pure data so the rules below are unit-testable without a socket, a spool,
 * or Android.
 *
 * [coveredLabels] holds the labels of live (non-retiring) scopes on **connected** spools, minus the scopes of our
 * own unanswered reset ([coveredLabelsOf]). A scope's
 * label is `peerId ?: groupId` (`ScopeFrames.Scope.label`), which is exactly what
 * [Conversations.idFor] produces for the same conversation — so a thread is covered precisely when its
 * `conversationId` is in this set, with no extra key to keep in sync.
 *
 * [configured] counts every relay in the user's list; [active] counts the ones that may actually carry
 * — the plane on, and the relay itself not parked. The rules below turn on [active], because a device
 * whose relays are all parked reaches nothing, however long its list is; [configured] survives only so
 * the Profile subtitle can tell an empty list apart from a fully parked one.
 *
 * [maxAttachBytes] is the largest per-scope attachment budget advertised by any connected spool, or
 * null when none of them carries attachments at all. Taking the **max** rather than the min is correct
 * because a member converges through the union of whatever every spool holds (spec §9.1): one relay
 * willing to store the bytes is enough. It says what a member can *fetch*, so it is what the reader's
 * placeholder ([attachmentWait]) reads.
 *
 * [uploadRooms] are the connected spools that take **this phone's** uploads (`SpoolStatus.uploads`, C-7.6-24), one
 * [UploadRoom] each: a spool that gates `aput` behind a files ticket this phone does not hold still serves every
 * attachment it stores, but takes none of ours. Empty when no connected spool would take an upload of any size; what
 * the sender's marker ([attachmentReach]) reads. Kept per relay rather than folded into one max, because a ticket's
 * per-attachment limit (`am`, C-7.6-32) and a spool's budget are two bounds that must hold on the same relay.
 */
data class RelayFacts(
    val enabled: Boolean = false,
    val configured: Int = 0,
    val active: Int = 0,
    val connected: Int = 0,
    val coveredLabels: Set<String> = emptySet(),
    val maxAttachBytes: Int? = null,
    val uploadRooms: List<UploadRoom> = listOfNotNull(maxAttachBytes?.let { UploadRoom(it) }),
    // The commons live on a connected relay that takes no attachments for them, by conversation id: its HELLO's
    // commons said no `attach` (S-7.4-5), or it carries frames only. Covered, so [coveredLabels] has them, but no
    // attachment record may name them there, and that relay is the only one a commons is on.
    val textOnlyRooms: Set<String> = emptySet(),
    // The joined commons whose relay does not run them, by conversation id (`SpoolStatus.staleCommons`): the
    // operator rotated the invite, or the relay runs no room at all. Read from every relay, connected or not — what
    // a relay said about the invite we hold stays true of it.
    val staleRooms: Set<String> = emptySet(),
)

/**
 * One connected relay that takes this phone's uploads: its per-scope attachment budget ([maxAttachBytes], spec §6.5),
 * and the most chunks one attachment may have under this phone's ticket there ([maxChunks], from the ticket's `am`,
 * C-7.6-32), null where the ticket sets no such limit.
 */
data class UploadRoom(
    val maxAttachBytes: Int,
    val maxChunks: Int? = null,
) {
    /**
     * Whether an attachment whose `A` (the end-to-end sealed bytes the blob store holds) is [sizeBytes] goes up here:
     * within the budget once chunk-sealed, and its `total = ⌈|A| / aChunkBytes⌉` within [maxChunks].
     */
    fun takes(sizeBytes: Int): Boolean =
        sealedAttachmentBytes(sizeBytes) <= maxAttachBytes && (maxChunks == null || ScopeAttachments.chunkCount(sizeBytes) <= maxChunks)
}

/**
 * The [RelayFacts.coveredLabels] of the **connected** spools in [live]: every live scope's label, but for two kinds
 * a new message would not reach the peer through.
 *
 * - A **retiring** scope is drained but never refilled (spec §3.1/§3.3).
 * - A scope of **our own unanswered reset** (`ScopeStatus.pendingReset`, ADR 2026-10.j67x): we push into the new
 *   root's scope, but the peer derives it only once it adopts the reset, which reaches it through the retiring
 *   scope it is still on. Until it answers the thread reads [RelayReach.Pending] — "Not covered by relays yet" —
 *   and the attachment rules, which all read [reachFor], promise no relay delivery either. The answer confirms the
 *   session and clears the flag, and the thread reads [RelayReach.Covered]. This closes ADR 032's open coverage
 *   note for this one case: there the table says "we can push", not "the pair shares a scope".
 */
fun coveredLabelsOf(live: List<SpoolStatus>): Set<String> =
    live
        .flatMap { spool -> spool.scopes.filterNot { it.retiring || it.pendingReset }.map { it.label } }
        .toSet()

/**
 * The [RelayFacts.textOnlyRooms] of the **connected** spools in [live]: a commons whose relay takes no attachment for
 * it — that room's own `attach` (S-7.4-5), or no attachment support at all.
 */
fun textOnlyRoomsOf(live: List<SpoolStatus>): Set<String> =
    live
        .flatMap { spool ->
            spool.scopes
                .map { it.label }
                .filter { Conversations.kindFor(it) == ConversationKind.COMMONS }
                .filter { spool.rooms[it]?.attach != true || spool.maxAttachBytes == null }
        }.toSet()

/**
 * The Internet plane's state as the connection header reports it: one answer for the whole device, where
 * [RelayReach] is the per-conversation one. Coarse on purpose — the header lives in a `TopAppBar` subtitle
 * with room for a dot, a short line and a glyph, so *which* relays and *how many* stay in the relay
 * settings screen and Diagnostics.
 */
enum class RelayPlane {
    /** Off, or on with an empty relay list — the header says nothing about the Internet at all. */
    Off,

    /** Armed and configured, but nothing connected right now: no message crosses the plane. */
    Down,

    /** At least one relay connected, so a scoped conversation reaches past radio range. */
    Live,
}

/**
 * What to tell the user about one conversation's Internet reach.
 *
 * The states are deliberately asymmetric: only [Room], [Pending] and [NoForwardSecrecy] render anything.
 * Coverage is the happy path and needs no ornament, and [Silent] is the "we have nothing true to say" case — including
 * a relay outage, which heals by itself and must not paint a notice across every open thread.
 */
enum class RelayReach {
    /** Plane off, no relay configured, none connected, or the Meshtastic room — say nothing. */
    Silent,

    /** A live scope for this thread exists on at least one connected relay. */
    Covered,

    /** The broadcast room, which is never scope-eligible (spec §4.4). Permanent and by design. */
    Room,

    /**
     * Relays are live but this thread has no scope yet — a peer or group still becoming eligible, or a DM whose
     * only live scope is our own reset the peer has not answered ([coveredLabelsOf]).
     */
    Pending,

    /**
     * Relays are live, but this DM's peer runs a Knit without forward secrecy, so no scope is coming until
     * it updates. See [lacksForwardSecrecy].
     */
    NoForwardSecrecy,
}

/** What to tell the user about one attachment's Internet reach. */
enum class AttachmentRelay {
    /** Nothing to say — no relays, or the conversation-level notice already says it. */
    Silent,

    /** Fits, and a connected relay will carry it. */
    Relayable,

    /**
     * Larger than every connected relay that takes this phone's uploads will hold: over its per-scope budget (refused
     * `quota`, spec §6.5), or over the per-attachment limit this phone's ticket carries there (`am`, C-7.6-32).
     */
    TooLarge,

    /** No connected relay advertises attachment support at all (spec §7.3) — frames only. */
    Unsupported,

    /**
     * Some connected relay carries attachments, but none takes this phone's uploads: each gates sending files behind
     * a ticket this phone does not hold, or its ticket's uploads there are used up for the day (C-7.6-24). The
     * attachment still arrives over the radios, and attachments others send still arrive through the relay.
     */
    Locked,
}

/** Whether [this] draws a sent attachment's "Nearby only" marker: a cause that lasts, never Silent or Relayable. */
fun AttachmentRelay.marksNearbyOnly(): Boolean =
    this == AttachmentRelay.TooLarge || this == AttachmentRelay.Unsupported || this == AttachmentRelay.Locked

/**
 * What to tell the reader of an attachment whose bytes are not here yet: whether the Internet plane is
 * still able to bring them. The placeholder's first line — "appears once a device that has it is
 * reachable" — is always true, since the radios can always carry them; this is the second line.
 */
enum class AttachmentWait {
    /** Nothing to add: no connected relay covers this thread, or it is the room. */
    Nearby,

    /** A connected relay covers the thread and carries attachments, so the bytes can also arrive that way. */
    Relay,

    /** A connected relay covers the thread, but none of the connected relays carries attachments (spec §7.3). */
    RelayFramesOnly,

    /** A commons whose relay is connected and takes its attachments: the bytes come from there, and only there. */
    CommonsRelay,

    /** A commons whose relay is not connected: nothing brings the bytes until it is. */
    CommonsOffline,

    /** A commons whose relay takes no attachments for it (S-7.4-5): nothing will bring the bytes. */
    CommonsTextOnly,

    /** A commons its relay no longer runs (a rotated invite): nothing will bring the bytes. */
    CommonsGone,
}

/**
 * The [RelayPlane] for [facts].
 *
 * Configured-but-none-connected is [RelayPlane.Down] rather than folded into [RelayPlane.Off] because the
 * two earn different pixels: Off is a setting the user chose and needs no indicator at all, while Down is
 * a plane they expect to be working. That split is the opposite call from [reachFor], which *does* fold an
 * outage into [RelayReach.Silent] — a per-thread notice would be repeated across every open chat, whereas
 * the header shows one glyph the user can already see the shape of.
 */
fun planeFor(facts: RelayFacts): RelayPlane =
    when {
        !facts.enabled || facts.active == 0 -> RelayPlane.Off
        facts.connected == 0 -> RelayPlane.Down
        else -> RelayPlane.Live
    }

/**
 * Whether [conversationId] currently rides the Internet plane.
 *
 * The broadcast room is checked before coverage rather than after, because it is a *structural*
 * exclusion — `ScopeFrames.eligibleForDm` requires a recipient and a v2 ratchet header, neither of which
 * a room frame has — and a permanent fact deserves different copy from a temporary one.
 *
 * The Meshtastic room ([Conversations.MESHTASTIC]) is a structural exclusion too, but it earns no copy at
 * all: nothing in that thread ever enters Knit's mesh — it is this phone's mirror of the board's own
 * channel — so a relay could not carry it under any future configuration. [RelayReach.Pending] would have
 * promised a coverage that is never coming, and even the room's permanent-by-design wording would be
 * describing a plane that thread was never on.
 *
 * A DM whose peer [lacksForwardSecrecy] is the same trap with a way out, so it earns its own copy rather
 * than silence: a DM scope keys on the ratchet session's root (`ScopeRegistry`), and `ScopeFrames.eligibleForDm`
 * refuses anything without a v2 header, so [RelayReach.Pending]'s "appears once both sides have exchanged
 * keys" would never come true — but an update on the peer's side would. Checked after coverage, so a pair
 * scope (spec §3.5, which needs no session) still reads as covered.
 */
fun reachFor(
    conversationId: String,
    facts: RelayFacts,
    peerLacksForwardSecrecy: Boolean = false,
): RelayReach =
    when {
        conversationId == Conversations.MESHTASTIC -> RelayReach.Silent

        !facts.enabled || facts.active == 0 || facts.connected == 0 -> RelayReach.Silent

        conversationId == Conversations.NEARBY -> RelayReach.Room

        conversationId in facts.coveredLabels -> RelayReach.Covered

        // A commons lives on one relay and nothing else could ever carry it: while that relay is down there
        // is no "becoming eligible" to promise, only silence.
        Conversations.kindFor(conversationId) == ConversationKind.COMMONS -> RelayReach.Silent

        peerLacksForwardSecrecy -> RelayReach.NoForwardSecrecy

        else -> RelayReach.Pending
    }

/**
 * Whether [peer] — a DM's other side — has a pinned profile that does not claim [Protocol.CAP_RATCHET]:
 * every DM to it seals v1 (`MeshManager.sealEnvelopeFor`), so no ratchet session, and with it no relay
 * scope, can form until its Knit updates. No pinned profile yet is not this case: the keys simply have not
 * arrived, which is what [RelayReach.Pending] already says.
 */
fun lacksForwardSecrecy(peer: PeerEntity?): Boolean = peer?.pubKey != null && (peer.capabilities ?: 0L) and Protocol.CAP_RATCHET == 0L

/**
 * The notice to render for [conversationId]: [reachFor], with the user's standing dismissal folded in.
 *
 * Only [RelayReach.Room] is dismissable, and only because it is the one notice that never retires itself.
 * The room's exclusion is structural and permanent, so without this the line is chrome the user reads
 * once and then carries forever. [RelayReach.Pending] is deliberately left alone: it clears on its own the
 * moment a scope appears — usually within a round or two of key exchange — and hiding it would suppress
 * the one signal that says a thread is still becoming eligible.
 *
 * Folded in here rather than at the call site so that "what the notice says" has exactly one definition,
 * and so a dismissed room reads as [RelayReach.Silent] — the same "nothing true to say" the rest of the
 * chrome already understands.
 */
fun noticeFor(
    conversationId: String,
    facts: RelayFacts,
    roomNoticeDismissed: Boolean,
    peerLacksForwardSecrecy: Boolean = false,
): RelayReach {
    val reach = reachFor(conversationId, facts, peerLacksForwardSecrecy)
    return if (reach == RelayReach.Room && roomNoticeDismissed) RelayReach.Silent else reach
}

/**
 * Whether the notice for [reach] offers a close button. See [noticeFor] for why only the room does.
 */
fun dismissable(reach: RelayReach): Boolean = reach == RelayReach.Room

/**
 * Whether an attachment of [sizeBytes] can cross the plane for [conversationId].
 *
 * Two compositions worth stating, because getting either wrong produces a marker users learn to
 * distrust:
 *
 * - It answers [AttachmentRelay.Silent] for any conversation that is not [RelayReach.Covered]. When the
 *   whole thread is off-plane the conversation-level notice already says so, and repeating it on every
 *   photo would be noise dressed as detail.
 * - It reports only causes that last. A relay that is merely full evicts its oldest attachment and
 *   accepts this one (spec §6.5); a `rate` or `pow` refusal heals on the next round. Neither is visible
 *   here, deliberately — the answers are "none of your relays carries photos", "none takes yours", and
 *   "too big for any relay that takes yours", each true until something changes on the relay or the phone.
 *
 * The precedence is that order: [AttachmentRelay.Unsupported] when no relay carries attachments at all,
 * [AttachmentRelay.Locked] when some do but none takes this phone's ([RelayFacts.uploadRooms] is empty), then
 * [AttachmentRelay.TooLarge] when no relay that takes them takes this one ([UploadRoom.takes]). [sizeBytes] is the
 * attachment's `A`, the bytes the blob store holds for it.
 */
fun attachmentReach(
    conversationId: String,
    sizeBytes: Int,
    facts: RelayFacts,
): AttachmentRelay {
    if (reachFor(conversationId, facts) != RelayReach.Covered) return AttachmentRelay.Silent
    if (facts.maxAttachBytes == null || conversationId in facts.textOnlyRooms) return AttachmentRelay.Unsupported
    if (facts.uploadRooms.isEmpty()) return AttachmentRelay.Locked
    return if (facts.uploadRooms.any { it.takes(sizeBytes) }) AttachmentRelay.Relayable else AttachmentRelay.TooLarge
}

/**
 * The second line under a missing attachment's spinner for [conversationId].
 *
 * Reads the **connected** relays only, and that is the point (work item 50). A relay that carries photos
 * but is down or switched off is bringing nothing right now, so "your relays carry photos" would be a
 * promise about a relay that is not there — and since `SpoolStatus.connected` requires a completed hello,
 * a route that swallows the socket is not there either. The line names the connected set honestly and
 * flips by itself the moment that relay is back. Anything short of [RelayReach.Covered] says nothing:
 * an outage stays silent for the reason [reachFor] gives, and the room's exclusion has its own notice. A commons
 * is the exception: its one relay is the only way the bytes can come, so it always says which state that relay
 * is in ([AttachmentWait.CommonsRelay] and its three siblings).
 */
fun attachmentWait(
    conversationId: String,
    facts: RelayFacts,
): AttachmentWait =
    when {
        // A commons attachment never touches the radios (ADR 2026-10.xhfr): its one relay is the whole answer,
        // and "nearby" or "your relays" would both be untrue.
        Conversations.kindFor(conversationId) == ConversationKind.COMMONS -> commonsWait(conversationId, facts)

        reachFor(conversationId, facts) != RelayReach.Covered -> AttachmentWait.Nearby

        facts.maxAttachBytes != null && conversationId !in facts.textOnlyRooms -> AttachmentWait.Relay

        else -> AttachmentWait.RelayFramesOnly
    }

private fun commonsWait(
    conversationId: String,
    facts: RelayFacts,
): AttachmentWait =
    when {
        conversationId in facts.staleRooms -> AttachmentWait.CommonsGone
        reachFor(conversationId, facts) != RelayReach.Covered -> AttachmentWait.CommonsOffline
        conversationId in facts.textOnlyRooms -> AttachmentWait.CommonsTextOnly
        else -> AttachmentWait.CommonsRelay
    }

/**
 * What [sizeBytes] of attachment ciphertext occupies at a spool once sealed: whole chunks, each grown by
 * the §4.5 envelope. An upper bound — the final chunk seals shorter than the rest — which is the safe
 * direction to round, since over-estimating only declines to relay bytes the mesh still carries.
 */
fun sealedAttachmentBytes(sizeBytes: Int): Int =
    if (sizeBytes <= 0) 0 else ScopeAttachments.chunkCount(sizeBytes) * ScopeCrypto.SEALED_CHUNK_BYTES
