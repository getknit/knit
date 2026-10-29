package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope

/**
 * Decides which mesh frames are worth an ~2.5-second LoRa airslot, and whose. Since ADR 2026-10.rp3s the board is an
 * **endpoint**: it airs only frames this phone's user wrote ([airs]), never a pocket-mate's, a relayed frame or a
 * custody re-serve of someone else's. Of the mesh's frames one kind rides the fan-out: an own **Nearby-room** post (a
 * broadcast `chat`, and only one that fits one packet — the transport asks that after encoding).
 *
 * Our own DMs no longer ride as mesh frames at all (ADR 2026-10.x9fa): a DM, its ✓✓ and a DM reaction leave as a
 * compact pair-sealed unicast offered beside the signed copy (`LoraDirectSink`), and every other DM-form frame — a
 * session reset, a seed, a key reply, an intro — travels custody, the spool and the links only. So everything else is
 * refused here: DM-form chat, the room's cleartext `reaction`, the `profile`, group-form chat, group metadata,
 * `typing`, every receipt, and the point-to-point `blobreq`/`keyreq`. Pure — decides on the already-decoded
 * [RelayEnvelope], never re-encoding it ([app.getknit.knit.mesh.lora.LoraFramePolicyTest]).
 */
internal object LoraFramePolicy {
    /**
     * Whether this plane may put a frame [authorId] wrote on the air at all: the one admit rule (ADR 2026-10.rp3s).
     * A frame this phone's user wrote, and nothing else — so the board airs no pocket-mate's frame, no relayed DM-form frame and
     * no custody re-serve of somebody else's, and the board's air is spent only on "my message to you". An own frame
     * re-served back to us after a wipe counts as own; that is accepted.
     *
     * One named predicate on purpose: the deferred "Forward over LoRa" (a pocket-mate's room post the user chose to
     * forward, `roadmap.md`) is a second clause here, and nowhere else. [selfId] is null until the plane has read
     * our own id, and then nothing is ours. Keyed on the author rather than the envelope so the send-time re-ask
     * (`RideGate.authorId`) asks the very same question.
     */
    fun airs(
        authorId: String,
        selfId: String?,
    ): Boolean = selfId != null && authorId == selfId

    /**
     * Whether [env] is a kind of frame the fan-out carries: an own room post (broadcast `chat`), and nothing else since
     * our DMs ride compact (ADR 2026-10.x9fa). Whose frame it is is [airs]'s question, and whether a room post fits one
     * packet is the transport's, after it has encoded it.
     */
    fun eligible(env: RelayEnvelope): Boolean = isBroadcastRoomChat(env)

    /**
     * DM-form chat: addressed to one recipient, no group — a DM, or any sealed ctl frame riding as one. Never aired
     * since ADR 2026-10.x9fa; still read on receive, where an older build's board airs them.
     */
    fun isDmForm(env: RelayEnvelope): Boolean = env.type == FrameType.CHAT && env.recipientId != null && env.group == null

    /**
     * Whether [env] is recent enough for a live plane at wall-clock [now]: a `chat` or `reaction` older than
     * [maxAgeMs] is a custody re-serve (the router's SeenSet lapses at 10 min, so a fresh flood never looks this
     * old) and stays custody's business — fanning it would spend a newcomer's whole backfill on the air. Every
     * other type is exempt: none of them rides the fan-out any more ([eligible], ADR 2026-10.rp3s), and a `profile`'s
     * `sentAt` is a publish stamp (up to 12 h old) that this age test would misread. A peer whose clock lags by more than the window has
     * its fresh frames kept off this plane only — they still ride the radios and custody.
     */
    fun isFresh(
        env: RelayEnvelope,
        now: Long,
        maxAgeMs: Long = FRESH_MS,
    ): Boolean = freshnessExempt(env) || now - env.sentAt <= maxAgeMs

    /**
     * Whether [isFresh] exempts [env] on its type alone — every type but `chat` and `reaction`, for the
     * reasons [isFresh] gives. Split out so a caller that needs the *deadline* rather than the verdict
     * ([app.getknit.knit.mesh.lora.RideGate.freshUntil]) reads the rule from here instead of restating it.
     */
    fun freshnessExempt(env: RelayEnvelope): Boolean = env.type != FrameType.CHAT && env.type != FrameType.REACTION

    /**
     * How old a chat/reaction may be and still ride the fan-out path: past the 10-min SeenSet, with skew slack.
     * `mesh/FramePresence.kt`'s [app.getknit.knit.mesh.PRESENCE_FRESH_MS] is the same number for the same
     * reason and stays separate on purpose — that one answers "does this prove its author is there", which
     * [isFresh] gets wrong for every non-chat type by design.
     */
    const val FRESH_MS = 15 * 60_000L

    /** A Nearby-room post: a chat with no DM recipient and no group. Its reactions are not this. */
    fun isBroadcastRoomChat(env: RelayEnvelope): Boolean = env.type == FrameType.CHAT && env.recipientId == null && env.group == null
}
