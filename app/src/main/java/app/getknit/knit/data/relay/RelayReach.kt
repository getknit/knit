package app.getknit.knit.data.relay

import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.spool.ScopeAttachments

/**
 * What the Internet-relay plane can currently do, flattened out of `SpoolStatus` into the few facts the
 * UI actually reasons about. Pure data so the rules below are unit-testable without a socket, a spool,
 * or Android.
 *
 * [coveredLabels] holds the labels of live (non-retiring) scopes on **connected** spools. A scope's
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
 * willing to store the bytes is enough.
 */
data class RelayFacts(
    val enabled: Boolean = false,
    val configured: Int = 0,
    val active: Int = 0,
    val connected: Int = 0,
    val coveredLabels: Set<String> = emptySet(),
    val maxAttachBytes: Int? = null,
)

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

    /** Relays are live but this thread has no scope yet — a peer or group still becoming eligible. */
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

    /** Larger than every connected relay's per-scope budget: refused `quota` (spec §6.5), permanently. */
    TooLarge,

    /** No connected relay advertises attachment support at all (spec §7.3) — frames only. */
    Unsupported,
}

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
 * - It reports only **permanent** causes. A relay that is merely full evicts its oldest attachment and
 *   accepts this one (spec §6.5); a `rate` or `pow` refusal heals on the next round. Neither is visible
 *   here, deliberately — the only two answers are "too big for any relay you use" and "none of your
 *   relays carries photos", both of which stay true until the user changes something.
 */
fun attachmentReach(
    conversationId: String,
    sizeBytes: Int,
    facts: RelayFacts,
): AttachmentRelay {
    if (reachFor(conversationId, facts) != RelayReach.Covered) return AttachmentRelay.Silent
    val budget = facts.maxAttachBytes ?: return AttachmentRelay.Unsupported
    return if (sealedAttachmentBytes(sizeBytes) <= budget) AttachmentRelay.Relayable else AttachmentRelay.TooLarge
}

/**
 * The second line under a missing attachment's spinner for [conversationId].
 *
 * Reads the **connected** relays only, and that is the point (work item 50). A relay that carries photos
 * but is down or switched off is bringing nothing right now, so "your relays carry photos" would be a
 * promise about a relay that is not there — and since `SpoolStatus.connected` requires a completed hello,
 * a route that swallows the socket is not there either. The line names the connected set honestly and
 * flips by itself the moment that relay is back. Anything short of [RelayReach.Covered] says nothing:
 * an outage stays silent for the reason [reachFor] gives, and the room's exclusion has its own notice.
 */
fun attachmentWait(
    conversationId: String,
    facts: RelayFacts,
): AttachmentWait =
    when {
        reachFor(conversationId, facts) != RelayReach.Covered -> AttachmentWait.Nearby
        facts.maxAttachBytes != null -> AttachmentWait.Relay
        else -> AttachmentWait.RelayFramesOnly
    }

/**
 * What [sizeBytes] of attachment ciphertext occupies at a spool once sealed: whole chunks, each grown by
 * the §4.5 envelope. An upper bound — the final chunk seals shorter than the rest — which is the safe
 * direction to round, since over-estimating only declines to relay bytes the mesh still carries.
 */
fun sealedAttachmentBytes(sizeBytes: Int): Int =
    if (sizeBytes <= 0) 0 else ScopeAttachments.chunkCount(sizeBytes) * ScopeCrypto.SEALED_CHUNK_BYTES
