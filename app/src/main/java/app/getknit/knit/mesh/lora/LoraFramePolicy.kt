package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope

/**
 * Decides which mesh frames are worth an ~2.5-second LoRa airslot. On the fan-out path: the plaintext
 * **Nearby room** — broadcast `chat` and its cleartext `reaction` — the cleartext `profile` frame (the far side
 * must pin the author's key before it can verify anything), and, since ADR 039, **DM-form chat**: a `chat`
 * frame with a recipient and no group. That form is deliberately opaque — a 1:1 DM, its sealed receipt or
 * reaction, a session reset, a group-key seed and an escalated delivery tick are wire-indistinguishable
 * (ADR 016/018), so all of them ride and this policy never tries to tell them apart. On the targeted path:
 * the delivery **receipt** back to a message's author, in either form.
 *
 * Everything else is refused: group-form chat (`group != null` — the plane carries no group conversation),
 * group metadata (useless without it), `typing` (worthless a moment later), and the point-to-point
 * `blobreq`/`keyreq`. Pure — decides on the already-decoded [RelayEnvelope], never re-encoding it
 * ([app.getknit.knit.mesh.lora.LoraFramePolicyTest]).
 */
internal object LoraFramePolicy {
    /**
     * How the frame is being offered: a flood-to-everyone fan-out, a targeted point-to-point send, or the
     * bridge's digest-driven backfill of frames a far gateway is missing (ADR 044).
     */
    enum class Path { FANOUT, TARGETED, BACKFILL }

    /**
     * Whether [env] (with its outer [wire], carrying the flood flag) may ride LoRa on [path]. On the
     * targeted path, [to] is the intended recipient's node id, so a sealed delivery tick — a `relay = false`
     * chat frame addressed to the author — is admitted while a flooded DM (`relay = true`) is not: a DM
     * reaches this plane only through the fan-out path, so no `fastSend` caller can widen what it carries.
     */
    fun eligible(
        env: RelayEnvelope,
        wire: WireEnvelope,
        path: Path,
        to: String? = null,
    ): Boolean =
        when (path) {
            // BACKFILL admits exactly what FANOUT does, and that is deliberate rather than incidental: the
            // bridge re-serves history, so anything it carries must be something the live plane would also
            // have carried. What separates the two is [isFresh], which the fan-out applies and the backfill
            // does not — an old frame is the whole point there. Kept a distinct arm so a future widening of
            // one is a decision about the other rather than a silent inheritance.
            Path.FANOUT, Path.BACKFILL -> isBroadcastRoom(env) || isDmForm(env) || env.type == FrameType.PROFILE

            Path.TARGETED -> env.type == FrameType.RECEIPT || isSealedTickTo(env, wire, to)
        }

    /**
     * How a frame ranks when the bridge can only afford a few (ADR 044, reordered by ADR 2026-09.rre4).
     * Lower is better.
     *
     * The cleartext `profile` still comes first, because nothing the far side receives verifies without the
     * author's key — serving anything to a peer that cannot check its signature is airtime thrown away.
     *
     * The **room then outranks DM-form chat**, which is the reverse of the pacing queue's [FrameClass] order
     * and deliberately so: the two answer different questions. The queue asks who transmits first once both
     * frames are already paid for, and there a DM wins because one named person is waiting. The rank asks
     * which frames are worth the *scarce* slots — four per offer — and there the room wins on both terms of
     * the trade. A bridged room post is readable by every member of the far pocket, while a bridged DM has
     * exactly one addressee (and `coveredByLink` has already dropped the ones a link would carry). It is
     * also cheaper: a cleartext room post is typically one packet, a sealed DM two.
     */
    fun backfillRank(env: RelayEnvelope): Int =
        when {
            env.type == FrameType.PROFILE -> RANK_PROFILE
            isDmForm(env) -> RANK_DM
            else -> RANK_ROOM
        }

    private const val RANK_PROFILE = 0
    private const val RANK_ROOM = 1
    private const val RANK_DM = 2

    /**
     * The bridge's candidate list: the frames [frames] holds that are worth a slot, superseded profiles
     * dropped, in [backfillRank] order and newest-first within a rank. **Both halves of the digest exchange
     * must draw from this one list** — the OFFER names its head (one packet holds ~48 prefixes) and the
     * serve picks the head of what the OFFER did not name — because a frame the offer cannot reach but the
     * serve prefers is re-sent on every round, for ever.
     *
     * That is not hypothetical: the offer used to be the newest 48 frames by `sentAt` while the serve
     * preferred profiles, and a `profile`'s `sentAt` is a **publish stamp** up to 12 h old. In any pocket
     * with 48 frames of recent chat, no profile was ever named, so every profile looked missing to the far
     * gateway for ever — and since profiles rank first, all four slots an offer buys went to profiles the
     * peer already held, round after round, and no room post could ever cross. Field-observed on the lab
     * boards (2026-09-05, ADR 2026-09.zkma): `bridge served=4/4` four times running, every frame a profile,
     * three of them the *recipient's own*, while the Nearby-room post that was waiting never moved until the
     * two phones came back into BLE range.
     *
     * Superseded profiles go for the same reason they are worthless: only the newest publish carries the key
     * and prekey a far pocket needs, and holding three of one author's — the lab really did serve three —
     * spends three of the four slots to say one thing. Dropping them is symmetric, so two nodes applying this
     * rule agree about what is missing; keeping the *newest* is what makes a genuine republish still cross.
     *
     * Custody itself is untouched: this ranks what the ~1 kbps plane offers to carry, not what is stored.
     */
    fun <T> bridgeOrder(
        frames: List<T>,
        envelopeOf: (T) -> RelayEnvelope,
    ): List<T> {
        val newestProfile =
            frames
                .filter { envelopeOf(it).type == FrameType.PROFILE }
                .groupBy { envelopeOf(it).senderId }
                .mapValues { (_, theirs) -> theirs.maxOf { envelopeOf(it).sentAt } }
        return frames
            .filter { frame ->
                val env = envelopeOf(frame)
                env.type != FrameType.PROFILE || env.sentAt == newestProfile[env.senderId]
            }.sortedWith(compareBy({ backfillRank(envelopeOf(it)) }, { -envelopeOf(it).sentAt }))
    }

    /** DM-form chat: addressed to one recipient, no group — a DM, or any sealed ctl frame riding as one. */
    fun isDmForm(env: RelayEnvelope): Boolean = env.type == FrameType.CHAT && env.recipientId != null && env.group == null

    /**
     * Whether [env] is recent enough for a live plane at wall-clock [now]: a `chat` or `reaction` older than
     * [maxAgeMs] is a custody re-serve (the router's SeenSet lapses at 10 min, so a fresh flood never looks this
     * old) and stays custody's business — fanning it would spend a newcomer's whole backfill on the air. Every
     * other type is exempt: a `profile`'s `sentAt` is its publish stamp (up to 12 h old, and the key bootstrap
     * must never be refused), a `receipt` is one packet. A peer whose clock lags by more than the window has
     * its fresh frames kept off this plane only — they still ride the radios and custody.
     */
    fun isFresh(
        env: RelayEnvelope,
        now: Long,
        maxAgeMs: Long = FRESH_MS,
    ): Boolean = (env.type != FrameType.CHAT && env.type != FrameType.REACTION) || now - env.sentAt <= maxAgeMs

    /**
     * How old a chat/reaction may be and still ride the fan-out path: past the 10-min SeenSet, with skew slack.
     * `mesh/FramePresence.kt`'s [app.getknit.knit.mesh.PRESENCE_FRESH_MS] is the same number for the same
     * reason and stays separate on purpose — that one answers "does this prove its author is there", which
     * [isFresh] gets wrong for every non-chat type by design.
     */
    const val FRESH_MS = 15 * 60_000L

    /** The Nearby room: a chat or reaction with no DM recipient and no group. */
    private fun isBroadcastRoom(env: RelayEnvelope): Boolean =
        (env.type == FrameType.CHAT || env.type == FrameType.REACTION) &&
            env.recipientId == null &&
            env.group == null

    /** A `relay = false` chat frame addressed to [to] — AckSync's sealed CTL_RECEIPT tick, never a flooded DM. */
    private fun isSealedTickTo(
        env: RelayEnvelope,
        wire: WireEnvelope,
        to: String?,
    ): Boolean = env.type == FrameType.CHAT && !wire.relay && to != null && env.recipientId == to
}
