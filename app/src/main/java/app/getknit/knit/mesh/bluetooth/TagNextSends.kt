package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.crypto.BlindTag
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/**
 * Who is sent this phone's advertised form over a link, and when (ADR 2026-11.xp6d's "Rotation is announced"; step 5b's
 * W5). Pure, keyed by link ([L]: the transport's `FramedLink`), on the wall clock the caller passes, single-threaded with
 * its owner (the transport's confined worker).
 *
 * Three sends, one [Kind] each:
 *  - [Kind.ANNOUNCE], the lead announcement of the coming slot's reserved tag (and the same tag to a link that comes up
 *    while it stands). It never moves and is never throttled: the resends are kept out of its way instead.
 *  - [Kind.CURRENT], the form just aired after a rotation nobody was told of (a late wake, a key change, a flip), once
 *    per link, so linked strangers do not dial the new tag.
 *  - [Kind.RESEND], the held-link row's answer to a dialer that missed a rotation (`BleAdmissionPolicy.heldLinkRow`).
 *
 * The receiver keeps one `TAG_NEXT` per link per [minIntervalMs] (`AdvertAliases.Config.tagNextMinIntervalMs`) and drops
 * the rest, so a second send inside it is wasted, or worse, leaves the dialer dialing. So a [Kind.CURRENT] or
 * [Kind.RESEND] goes only when none went to that link inside [minIntervalMs], never inside [minIntervalMs] before a due
 * announcement (which would then be the one dropped), and never while an announcement stands (`announcing`): the receiver
 * holds the announced tag pending, and another form would replace it there and read as the coming edge's. A forged dial
 * times when a resend goes, so it must not be able to push an announcement out: the announcement does not move for it.
 *
 * Every send needs [eligible]: a proven link whose HELLO carried `CAP_BLIND_ADVERT`, whose peer the session's predicate
 * (`MeshTransport.onTagNextRecipients`, read at the send, never at link-up) says may hear it — not blocked, not among
 * those who left the own key's audience — and an own key held (a random tag is never announced: the departures an
 * unsettled settle keeps can miss a fresh leaver).
 */
internal class TagNextSends<L : Any>(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) {
    enum class Kind { ANNOUNCE, CURRENT, RESEND }

    /** What [decide] made of a send; every one but [SENT] is counted by the wiring. */
    enum class Outcome { SENT, NO_KEY, INELIGIBLE, THROTTLED, BEFORE_ANNOUNCE }

    private val lastSent = HashMap<L, Long>()

    /**
     * Whether a [kind] send to [link] goes at [wallMs] ([Outcome.SENT], recorded as sent), given its [eligible]ity, whether
     * an own key is held ([hasKey]), when the next announcement is due ([announceDueAt], wall clock, null when none) and
     * whether one stands now ([announcing]).
     */
    @Suppress("LongParameterList") // the send's facts, each from a different source in the transport
    fun decide(
        link: L,
        kind: Kind,
        eligible: Boolean,
        hasKey: Boolean,
        wallMs: Long,
        announceDueAt: Long?,
        announcing: Boolean = false,
    ): Outcome {
        val last = lastSent[link]
        val outcome =
            when {
                !hasKey -> Outcome.NO_KEY

                !eligible -> Outcome.INELIGIBLE

                kind == Kind.ANNOUNCE -> Outcome.SENT

                // While an announcement stands, the receiver holds the announced tag pending: any other form sent now would
                // replace it there, and read as an announcement of the coming edge.
                announcing -> Outcome.BEFORE_ANNOUNCE

                last != null && wallMs - last in 0 until minIntervalMs -> Outcome.THROTTLED

                announceDueAt != null && announceDueAt - wallMs in 0 until minIntervalMs -> Outcome.BEFORE_ANNOUNCE

                else -> Outcome.SENT
            }
        if (outcome == Outcome.SENT) lastSent[link] = wallMs
        return outcome
    }

    /**
     * Whether any live link was sent a form within [minIntervalMs] before [wallMs]: a post-rotation send to it now would
     * be [Outcome.THROTTLED], leaving that peer on the old form (`…debug.ADVERT_ROTATE` refuses then, step 6). Ended links
     * are [forget]ed, so only links still held count.
     */
    fun sentWithin(wallMs: Long): Boolean = lastSent.values.any { wallMs - it in 0 until minIntervalMs }

    /** The link ended: its record goes with it. */
    fun forget(link: L) {
        lastSent.remove(link)
    }

    /** Every link's record (adapter off, stop). */
    fun clear() {
        lastSent.clear()
    }

    companion object {
        /** The receiver's per-link limit (`AdvertAliases.Config.tagNextMinIntervalMs`). */
        const val MIN_INTERVAL_MS = 120_000L

        /** Whether a link may be sent a form at all: proven, tag-aware, and the session's predicate true now. */
        fun eligible(
            proven: Boolean,
            readsTags: Boolean,
            hears: Boolean,
        ): Boolean = proven && readsTags && hears

        /** How far before a slot edge a received `TAG_NEXT` reads as the announcement of the coming slot. */
        const val ANNOUNCE_WINDOW_MS = AdvertRotation.LEAD_MS + 60_000L

        /**
         * When a `TAG_NEXT` received at [wallMs] becomes the link's form (`linkForms`, so both ends read the same pair of
         * forms in `dialsBackAfterDrop`): at the coming slot edge for one received within [ANNOUNCE_WINDOW_MS] before it
         * (an announcement: the peer airs it from its edge), at once for any other (a resend or a post-rotation send names
         * the form on the air now).
         */
        fun appliesAt(wallMs: Long): Long {
            val edge = (BlindTag.slotOf(wallMs) + 1) * BlindTag.SLOT_MS
            return if (edge - wallMs <= ANNOUNCE_WINDOW_MS) edge else wallMs
        }
    }
}

/**
 * The form a peer's accepted `TAG_NEXT` makes its link's (`linkForms`, step 5b's W5), one note per link: a later record
 * replaces the note (and the wiring cancels the wait behind it), so a linked fork sending records at link rate keeps one
 * note and one wait, never one per record. [offer] says when the note applies ([TagNextSends.appliesAt]: at once, or at the
 * coming edge for an announcement); [due] confirms, when the wait ends, that the note is still that link's and that tag.
 * Pure, single-threaded with its owner (the transport's confined worker).
 */
internal class LinkFormNotes<L : Any> {
    private val notes = HashMap<L, Pair<String, Long>>()

    /** How many links hold a note waiting for its edge. */
    val size: Int get() = notes.size

    /**
     * A `TAG_NEXT` naming [tag], received on [link] at [wallMs] and accepted by the resolver. Null when it applies now (any
     * note the link held is dropped); else the wall-clock instant its note applies at.
     */
    fun offer(
        link: L,
        tag: String,
        wallMs: Long,
    ): Long? {
        val at = TagNextSends.appliesAt(wallMs)
        if (at <= wallMs) {
            notes.remove(link)
            return null
        }
        notes[link] = tag to at
        return at
    }

    /** Whether [link]'s note is still [tag] and due at [wallMs]: then it is taken, and applies. */
    fun due(
        link: L,
        tag: String,
        wallMs: Long,
    ): Boolean {
        val (held, at) = notes[link] ?: return false
        if (held != tag || wallMs < at) return false
        notes.remove(link)
        return true
    }

    /** The link ended. */
    fun forget(link: L) {
        notes.remove(link)
    }

    fun clear() = notes.clear()
}

/**
 * The transport's half of [LinkFormNotes] (step 5b's W5; split out in step 6 so the JVM covers the wiring): which
 * resolver answers are noted at all, and one wait per link. Only an announcement the resolver accepted ([notes]: held
 * pending, or what a contact's key already says) is noted; a later accepted record cancels the link's wait and replaces
 * it, so a fork flooding records at link rate holds one coroutine, never one per record. At the wait's end the note must
 * still be that link's and that tag ([LinkFormNotes.due]) and still stand ([offer]'s `stands`, read then) before it
 * applies. Confined, with every coroutine [launch] starts, to one thread (the transport's worker).
 */
internal class LinkFormWaits<L : Any>(
    // Starts a wait on the owner's confined dispatcher.
    private val launch: (suspend () -> Unit) -> Job,
    // An elapsed-aware wait on the wall clock: a peer's edge is a wall-clock instant, and a `delay` stops in suspend.
    private val wait: suspend (Long) -> Unit,
    private val wallMs: () -> Long,
) {
    private val notes = LinkFormNotes<L>()
    private val jobs = HashMap<L, Job>()

    /** How many links hold a wait for their edge. */
    val waiting: Int get() = jobs.size

    /**
     * The resolver answered [outcome] to [tag], announced on [link]. Returns whether it was noted. A noted record applies
     * ([apply]) at once when it names the form on the air now, else at the coming edge if [stands] still holds then.
     */
    fun offer(
        link: L,
        tag: String,
        outcome: AdvertAliases.ClaimOutcome,
        stands: () -> Boolean,
        apply: () -> Unit,
    ): Boolean {
        if (!notes(outcome)) return false
        jobs.remove(link)?.cancel()
        val wall = wallMs()
        val at = notes.offer(link, tag, wall)
        if (at == null) {
            apply()
            return true
        }
        jobs[link] =
            launch {
                wait(at - wall)
                val self = currentCoroutineContext()[Job]
                if (jobs[link] === self) jobs.remove(link)
                if (notes.due(link, tag, wallMs()) && stands()) apply()
            }
        return true
    }

    /** The link ended (or was replaced): its note and its wait go. */
    fun forget(link: L) {
        notes.forget(link)
        jobs.remove(link)?.cancel()
    }

    /** Every link's (adapter off, stop). */
    fun clear() {
        notes.clear()
        jobs.values.forEach(Job::cancel)
        jobs.clear()
    }

    companion object {
        /** Whether a resolver answer to a `TAG_NEXT` is noted: held pending, or already a contact's key's answer. */
        fun notes(outcome: AdvertAliases.ClaimOutcome): Boolean =
            outcome == AdvertAliases.ClaimOutcome.Pending || outcome == AdvertAliases.ClaimOutcome.Redundant
    }
}
