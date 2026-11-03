package app.getknit.knit.mesh.bluetooth

/**
 * Who dials before a link exists, once a presence advert can name a rotating tag instead of the node id (ADR
 * 2026-11.xp6d, knit-next #285). It replaces every raw `localId > nodeId` the plane reads to decide who dials or hunts
 * (the reader's wiring moved each of them onto it, with my forms ([Mine]) as an input, so a phone that airs its id reads
 * the classic order everywhere and the writer only has to move [Mine]):
 *  - `DialPlan.ordinaryCandidates`, `promotable` and `scanCandidates`, the boost trigger and the scan floor;
 *  - the lonely pick ([lonelyMayDial]) and `DialPlan.lonelyDialInFlight`, which no longer infers a lonely dial from the
 *    order: a dial records its kind when it starts (`DialPlan.DialKind`), and the `bt coded heard … dials=` line reads
 *    [iDial];
 *  - the Aware→BLE chase (`DialPlan.initiable`, [initiable]);
 *  - three roles that today read `CodedPhyPolicy.drives` but decide who dials back or hunts, not who steps a live
 *    link: `fastAdvertAfterDrop`, `huntAfterDrop` and `coldHunt` (with its `DialPlan.promotes` input). Each is judged by
 *    [iDial] on my current form against the peer's last known advertised form, which is the classic order when that
 *    form was clear (`CodedPhyPolicy.dialsBack`, the classic order on the real ids when no form is known).
 *
 * Pure, free of Android and of a clock.
 *
 * The rule, from one phone's side ([iDial]):
 *  - **Equal forms never dial.** My own advert, or a tag that collides with mine.
 *  - **Against a clear advert:** a blinding phone dials; a clear one keeps the classic order (the larger id dials).
 *  - **Against a blinded advert:** a blinding phone dials when its tag sorts above theirs; a clear phone never does,
 *    leaving the dial to the blinding side, which dials every clear advert it hears.
 *
 * That is antisymmetric for every pair of forms, so exactly one side dials whenever both hear each other's current
 * form. A shipped build dials only a smaller advertised id, and a tag starts `zzzz` ([app.getknit.knit.mesh.crypto.BlindTag]),
 * so it leaves the dial to the blinding phone in all but about 2⁻²⁰ of pairs; in those it dials the tag and fails on
 * `WrongPeer`, and the blinding phone's own dial still links them (an old responder admits a dialer it never
 * sighted, ADR 2026-09.shzv).
 *
 * The ADR words the clear-against-blinded case as "the classic order holds", which is the same answer except for a
 * clear id that itself sorts above the tag (about 2⁻²⁰ of them): there both sides would dial, and each responder's
 * order check ([responderShouldHaveDialed]) would then refuse the other, leaving the pair unlinked for good. Deferring
 * to the blinding side on every clear-against-blinded pair closes that.
 *
 * **Not the PHY steps of a live link.** Which side steps a live link's PHY (`CodedPhyPolicy.drives`'s step role) stays
 * on the real ids the two HELLOs proved, never on advertised forms: across an old↔new link the old side reads only real
 * ids, so judging one end on tags would have both ends step (ADR 2026-11.xp6d, "Who dials").
 */
internal object AdvertOrder {
    /**
     * A peer's advertised form: [advertised] is the 26-char string of advert bytes 1–16 (`NodeId.fromBytes`), a node
     * id when [blinded] is false and a tag when the advert's `FLAG_BLINDED` was set. A shipped build never sets the
     * flag, so its advert is always clear.
     */
    data class Form(
        val advertised: String,
        val blinded: Boolean,
    ) {
        /** Redacted, so no log line pairs a tag with an id: the first four characters only (a tag's are always `zzzz`). */
        override fun toString(): String = "Form(${advertised.take(SHOWN)}…, blinded=$blinded)"

        companion object {
            private const val SHOWN = 4

            /** A clear advert: the node id itself. */
            fun clear(nodeId: String): Form = Form(nodeId, blinded = false)

            /**
             * The dialer's form from its HELLO: its `t` field ([told], already decoded to the 26-char form), or its
             * node id when the HELLO carries none (a build that never blinds). A `t` equal to the HELLO's id is clear;
             * any other is a tag.
             */
            fun fromHello(
                helloId: String,
                told: String?,
            ): Form = if (told == null || told == helloId) clear(helloId) else Form(told, blinded = true)
        }
    }

    /**
     * My own advertised forms, read as one snapshot by every role that judges order ([iDial], the responder's check,
     * the held-link row): [current] is what I air now, [previous] what I aired before my last rotation (null when none).
     * The transport holds one in a single reference and replaces it whole, so no reader sees a current from one rotation
     * beside a previous from another. A phone that never blinds holds `Mine(Form.clear(id), null)` for good; the writer
     * (ADR 2026-11.xp6d's staging, `BuildConfig.BLIND_ADVERT`) is what will move it.
     */
    data class Mine(
        val current: Form,
        val previous: Form? = null,
    ) {
        /** Whether I air a tag now. */
        val blinded: Boolean get() = current.blinded

        companion object {
            /** A phone that airs its id: every pre-link role then reads the classic order. */
            fun clear(nodeId: String): Mine = Mine(Form.clear(nodeId))
        }
    }

    /** Whether I, advertising [mine], dial a peer advertising [theirs] before any link. */
    fun iDial(
        mine: Form,
        theirs: Form,
    ): Boolean =
        when {
            mine.advertised == theirs.advertised -> false
            !theirs.blinded -> mine.blinded || mine.advertised > theirs.advertised
            !mine.blinded -> false
            else -> mine.advertised > theirs.advertised
        }

    /**
     * Whether a lonely dial (ADR 2026-09.hj4a: no link for 180 s) may go to [theirs]: only a peer the ordinary order
     * leaves to dial me. For two clear ids it is the shipped rule, a larger id.
     */
    fun lonelyMayDial(
        mine: Form,
        theirs: Form,
    ): Boolean = mine.advertised != theirs.advertised && !iDial(mine, theirs)

    /**
     * The peers another plane (Wi-Fi Aware, `DialPlan.initiable` today) reports that are worth chasing over Bluetooth:
     * while I blind ([blinding]), every foreign id: I dial every clear advert, and against a blinded one the order rests
     * on two tags no real id predicts. Otherwise the classic order, the larger id dials. [peers] are real node ids.
     *
     * A reader with writing off therefore chases smaller ids classically, blinding phones among them that it will never
     * dial (they dial it): a boost spent for nothing, accepted, since the chase cannot tell a blinding peer from its
     * Aware id. Don't tune the chase against that waste.
     */
    fun initiable(
        localId: String,
        blinding: Boolean,
        peers: Set<String>,
    ): Set<String> = peers.filterTo(HashSet()) { it != localId && (blinding || localId > it) }

    /**
     * The form of mine a dialer's HELLO `a` names ([dialed], decoded to the 26-char form), honoured only when it is my
     * [current] or [previous] advertised form; null when absent or neither (stale past one rotation, or not mine).
     */
    fun dialedForm(
        current: Form,
        previous: Form?,
        dialed: String?,
    ): Form? =
        when (dialed) {
            null -> null
            current.advertised -> current
            previous?.advertised -> previous
            else -> null
        }

    /**
     * The responder's order check: whether I should have dialed this [dialer] instead (the dialer's form from its HELLO,
     * [Form.fromHello]), judged on the form of mine it says it dialed ([dialedForm]) so a dial made against my previous
     * tag is judged as the dialer saw it. With no honoured `a` it is judged against my [current] form, which is the
     * shipped rule for a clear phone. True is the "sighted and out of order" refusal of `BleAdmissionPolicy.decide`.
     */
    fun responderShouldHaveDialed(
        current: Form,
        previous: Form?,
        dialed: String?,
        dialer: Form,
    ): Boolean = iDial(dialedForm(current, previous, dialed) ?: current, dialer)
}

/** The advertised form of a presence snapshot ([BlePresenceTracker.Snapshot.advertised]): what every pre-link role reads. */
internal val BlePresenceTracker.Snapshot.form: AdvertOrder.Form get() = AdvertOrder.Form(advertised, blinded)
