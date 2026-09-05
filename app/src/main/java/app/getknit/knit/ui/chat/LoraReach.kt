package app.getknit.knit.ui.chat

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.relay.RelayReach
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.lora.LoraSizeHint
import app.getknit.knit.mesh.lora.PublicPostPolicy

/**
 * What to tell the user about a thread's LoRa reach — the board's counterpart of [RelayReach]. Only the
 * "LoRa alone can hear them" cases render anything: a peer the phone radios also reach needs no ornament,
 * and a board that is down says nothing here (the header glyph already does).
 */
enum class LoraReach {
    /** Nothing to say: a group, a peer another plane reaches, a room with no far listener, or no board. */
    Silent,

    /** The board is the only plane that has heard this peer lately: slow, short, no photos. */
    LoraOnly,

    /** As [LoraOnly], but private messages over LoRa are switched off — so nothing reaches them at all. */
    LoraOnlyDmsOff,

    /** As [LoraOnly], but the board has spent its airtime window (ADR 054): messages wait for air, minutes not seconds. */
    LoraOnlySaturated,

    /**
     * A group thread's one and only LoRa state: the plane refuses group-form frames outright
     * (`LoraFramePolicy`), so a member the board alone can hear will not see these messages over it —
     * they wait in custody for that member to come back within phone-radio range, or for a relay.
     * Unlike every other state here this is about a **capability**, not congestion: no amount of
     * airtime would carry it.
     */
    GroupUnsupported,

    /**
     * The room's one and only LoRa state: the window is spent and somebody here is behind the board, so
     * posts reach them minutes late while everyone in phone-radio range still gets them at once. The room
     * has no [LoraOnly] counterpart on purpose (ADR 2026-09.ursc) — its audience is always a mix, so a
     * standing "some people are far away" strip would be permanent chrome saying nothing the user can act on.
     */
    RoomSaturated,
}

/**
 * Whether a draft in this thread would ride the LoRa plane, and in which form — what sizes the composer's
 * length hint ([LoraSizeHint]). [None] when the board is down, in a group (the plane carries no group
 * conversation), or in a DM with private messages over LoRa off.
 */
enum class LoraCarry { None, Room, Dm }

/**
 * Whether [kinds] means the board alone has heard them — the population every notice here is about, and
 * an exact-set test rather than a `contains`: a peer the phone radios also reach is carried by those.
 * Null (reachable over nothing) is not it either; the ordinary offline behaviour speaks for that peer.
 */
fun isLoraOnly(kinds: Set<TransportKind>?): Boolean = kinds == setOf(TransportKind.LoRa)

/**
 * The [LoraReach] for [conversationId], given the plane's [facts], the radios the peer is currently
 * reachable over ([kinds], from `MeshController.peerTransports` — null when it is reachable over none) and
 * the thread's Internet reach. A relay-covered DM has a better carrier than the board and stays quiet. This
 * is the **DM** rule: the room is addressed to nobody, so it has its own ([loraRoomReachFor]) and falls out
 * here on the first line; a group id never appears in the peer map, so groups fall out on their own. LoRa's
 * reachable set lingers 45 min, so the copy says "last heard".
 */
fun loraReachFor(
    conversationId: String,
    facts: LoraFacts,
    kinds: Set<TransportKind>?,
    relayReach: RelayReach,
): LoraReach =
    when {
        conversationId == Conversations.NEARBY -> LoraReach.Silent
        facts.plane != LoraPlane.Live -> LoraReach.Silent
        !isLoraOnly(kinds) -> LoraReach.Silent
        relayReach == RelayReach.Covered -> LoraReach.Silent
        !facts.dms -> LoraReach.LoraOnlyDmsOff
        facts.airtimeSpent -> LoraReach.LoraOnlySaturated
        else -> LoraReach.LoraOnly
    }

/**
 * The [LoraReach] for the Nearby room, whose airtime is the only LoRa story it has (ADR 2026-09.ursc). [loraOnlyPeer] is true
 * while at least one peer anywhere is reachable over the board alone — the room is addressed to nobody, so
 * the question is not "can we reach *them*" but "is there anyone out there a spent window would delay". If
 * the phone radios reach everyone we have heard, the queue holds nothing anybody is waiting for and this
 * stays [LoraReach.Silent].
 *
 * No [RelayReach] gate, unlike [loraReachFor]: the room is never scope-eligible on the Internet plane
 * ([RelayReach.Room] is permanent by design, `SPOOL_PROTOCOL` §4.4), so no better carrier can exist to
 * silence this. And no dismissal: the state clears itself as the rolling window ages air back, so a
 * "never show again" would hide it on the one occasion it matters.
 */
fun loraRoomReachFor(
    facts: LoraFacts,
    loraOnlyPeer: Boolean,
): LoraReach =
    when {
        facts.plane != LoraPlane.Live -> LoraReach.Silent
        !loraOnlyPeer -> LoraReach.Silent
        !facts.airtimeSpent -> LoraReach.Silent
        else -> LoraReach.RoomSaturated
    }

/**
 * The [LoraReach] for a group thread (ADR 2026-09.6ww7). [loraOnlyMember] is true while at least one member
 * of *this group's roster* is reachable over the board alone — a LoRa-only stranger says nothing about
 * whether a group message lands, so unlike the room this is never "anyone at all".
 *
 * The plane refuses group-form chat by policy, so there is nothing to say about airtime or about
 * `facts.dms` here: no setting and no spare window would carry it. A relay-covered group **is** carried by
 * the Internet plane (a group scope is scope-eligible where the room is not), so that silences it exactly
 * as it silences the DM rule.
 */
fun loraGroupReachFor(
    facts: LoraFacts,
    loraOnlyMember: Boolean,
    relayReach: RelayReach,
): LoraReach =
    when {
        facts.plane != LoraPlane.Live -> LoraReach.Silent
        !loraOnlyMember -> LoraReach.Silent
        relayReach == RelayReach.Covered -> LoraReach.Silent
        else -> LoraReach.GroupUnsupported
    }

/**
 * Whether the Meshtastic room's composer may post from this device, and if not, what stands in its way.
 * [Open] in every other thread. The room posts through this phone's own radio, so the answer follows the
 * radio: none bound, bound but down, or bound and live but with nothing to post on.
 */
enum class PublicPostGate {
    Open,
    NoRadio,
    RadioDown,
    ChannelUnusable,
}

/** The [PublicPostGate] for [conversationId] given the plane's [facts]. */
fun publicPostGateFor(
    conversationId: String,
    facts: LoraFacts,
): PublicPostGate =
    when {
        conversationId != Conversations.MESHTASTIC -> PublicPostGate.Open
        facts.plane == LoraPlane.Off -> PublicPostGate.NoRadio
        facts.plane == LoraPlane.Down -> PublicPostGate.RadioDown
        !facts.canPost -> PublicPostGate.ChannelUnusable
        else -> PublicPostGate.Open
    }

/**
 * The [LoraCarry] for a draft in [conversationId].
 *
 * The **Meshtastic** room is [LoraCarry.None] whatever the plane is doing, because its length rule is not
 * this one: a post there is capped hard in the composer at what a Meshtastic frame carries
 * ([PublicPostPolicy.bodyBudget]). Left as a DM it would take the DM's larger hint and hang a soft "may not
 * reach people over LoRa" under a field that has already refused the 201st byte — and it would follow the
 * private-messages-over-LoRa switch, which governs nothing in that room.
 */
fun loraCarryFor(
    conversationId: String,
    isGroup: Boolean,
    facts: LoraFacts,
): LoraCarry =
    when {
        facts.plane != LoraPlane.Live -> LoraCarry.None
        conversationId == Conversations.MESHTASTIC -> LoraCarry.None
        conversationId == Conversations.NEARBY -> LoraCarry.Room
        isGroup -> LoraCarry.None
        facts.dms -> LoraCarry.Dm
        else -> LoraCarry.None
    }

/** The composer's body budget in bytes for [carry], or null when the draft would not ride LoRa at all. */
fun loraBudgetFor(
    carry: LoraCarry,
    replying: Boolean,
    attached: Boolean,
): Int? =
    when (carry) {
        LoraCarry.None -> null
        LoraCarry.Room -> LoraSizeHint.budget(LoraSizeHint.ROOM_BODY_BYTES, replying, attached)
        LoraCarry.Dm -> LoraSizeHint.budget(LoraSizeHint.DM_BODY_BYTES, replying, attached)
    }
