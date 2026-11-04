package app.getknit.knit.mesh.bluetooth

/**
 * The debug bridge's handle on the blinded advert (ADR 2026-11.xp6d, step 6: `…debug.ADVERT` and `…debug.ADVERT_ROTATE`),
 * bound by [BluetoothMeshTransport] while it runs, and only on a debug build: the same shape as [CodedPhyDiag] and
 * [LinkTuneDiag], so the bridge needs no `android.bluetooth` import or transport reference, and nothing is added to the
 * `MeshTransport` seam for a debug lever. Every lambda runs its reads on the transport's confined worker. Null while the
 * transport is not running: the bridge then answers with a reason, never a wait.
 *
 * **What no reply carries.** The receiver mirrors every reply to logcat (`KnitBridge:I`) with a time, and a bug report
 * keeps it, so a reply is held to the writer's logging rule (ADR 2026-11.xp6d's "Counts, never dated causes"): never a
 * rotation's cause, a tag's source (`TagChoice.Source`), `resolvable` / `stale`, or a key generation (a dated fresh key or
 * generation step dates a removal or a block, a dated unresolvable tag dates a clock move), and never a tag, a prefix of
 * one past `zzzz`, or a handle beside a node id. [AdvertStatus] takes none of them as input. A harness that wants to know
 * what a forced rotation aired diffs STATE's counts (`bleTagsUnresolvable`, `bleRotationsByCause`) around the call.
 */
internal object AdvertDiag {
    /** The advert's gates, form, turn, peers and links now ([AdvertStatus]). */
    @Volatile var status: (suspend () -> AdvertStatus)? = null

    /** One unannounced rotation now through the ordinary path, unless [ForcedRotation.refusal] refuses it. */
    @Volatile var rotate: (suspend () -> ForcedRotation.Result)? = null

    /**
     * After the runtime switch was stored as `want`: waits, bounded, until the transport has seen it and the form on the
     * air matches what the writer should air ([AdvertSwitch.verdict]).
     */
    @Volatile var awaitSwitch: (suspend (Boolean) -> AdvertSwitch.Result)? = null
}

/**
 * The blinded advert as `…debug.ADVERT` reports it (step 6). Built by [of] from the writer's one snapshot, the slot turn's
 * [AdvertRotation.View], my current form, the presence snapshots and the live links: inputs that carry no cause, no tag
 * source and no generation, and a tag only inside forms this class reads for an order or a kind and never copies out.
 *
 *  - The gates, each its own field so a dark writer says why: [extendedAdvertising] (null until read with the adapter on,
 *    sticky once true) and [keyHeld] (an own presence key is held). The build flag and the switch are the bridge's to add.
 *  - [blinding]: what the writer should air now; [form]: what the snapshot on the air carries, `clear` or `tag` (null
 *    before the first install); [radioUp] and [pending] (a rotation's tag is being chosen): why the two may differ.
 *  - [epoch] and [rotatedAtMs]: how many snapshots this process installed (every rotation, bring-up and flip adds one), and
 *    the wall clock of the last. The advertiser's own start and stop lines already date every restart (B1).
 *  - The turn ([AdvertRotation.View]): [nextEdgeAtMs], [announced], [holding].
 *  - [psm]: the advertised PSM while clear, null while blinding (as `bt state`'s `psm=rotating`: a PSM sequence would tie
 *    one tag to the next).
 *  - [peers]: one per presence snapshot ([Peer]); [links]: one per live link ([Link]).
 */
internal data class AdvertStatus(
    val extendedAdvertising: Boolean?,
    val keyHeld: Boolean,
    val radioUp: Boolean,
    val pending: Boolean,
    val blinding: Boolean,
    val form: String?,
    val epoch: Long,
    val rotatedAtMs: Long?,
    val nextEdgeAtMs: Long?,
    val announced: Boolean,
    val holding: Boolean,
    val psm: Int?,
    val peers: List<Peer>,
    val links: List<Link>,
) {
    /**
     * One sighted peer. [peer] is its resolved node id, or its form's handle (`~zzzz…<n>`, [FormKeys]) while unresolved,
     * never a tag; [resolved] says which. [blinded] is their form's kind. [iDial] is [AdvertOrder.iDial] on my current form
     * against theirs: the pre-link order alone, so a lonely dial, a Coded hunt or an iPhone's doorbell can still make the
     * other side the dialer. [linked] reads the entry's own key only: a handle is never linked, so no reply pairs a handle
     * with a node id in [links].
     */
    data class Peer(
        val peer: String,
        val resolved: Boolean,
        val blinded: Boolean,
        val iDial: Boolean,
        val linked: Boolean,
    )

    /**
     * One live link: [nodeId] as its HELLO named it, [proven] (ADR 2026-11.264w: an unproven link names an id it has not
     * proved), [dialedByMe] (this phone dialed it, recorded at the link's registration; never a successor's role), and
     * [tagAware] (its HELLO carried `CAP_BLIND_ADVERT`).
     */
    data class Link(
        val nodeId: String,
        val proven: Boolean,
        val dialedByMe: Boolean,
        val tagAware: Boolean,
    )

    /** The reply's fields in order, as plain values, maps and lists: the bridge turns them into JSON as they stand. */
    fun fields(): Map<String, Any?> =
        linkedMapOf(
            "extendedAdvertising" to extendedAdvertising,
            "keyHeld" to keyHeld,
            "radioUp" to radioUp,
            "pending" to pending,
            "blinding" to blinding,
            "form" to form,
            "epoch" to epoch,
            "rotatedAtMs" to rotatedAtMs,
            "nextEdgeAtMs" to nextEdgeAtMs,
            "announced" to announced,
            "holding" to holding,
            "psm" to psm,
            "peers" to
                peers.map {
                    linkedMapOf(
                        "peer" to it.peer,
                        "resolved" to it.resolved,
                        "blinded" to it.blinded,
                        "iDial" to it.iDial,
                        "linked" to it.linked,
                    )
                },
            "links" to
                links.map {
                    linkedMapOf(
                        "nodeId" to it.nodeId,
                        "proven" to it.proven,
                        "dialedByMe" to it.dialedByMe,
                        "tagAware" to it.tagAware,
                    )
                },
        )

    companion object {
        const val FORM_CLEAR = "clear"
        const val FORM_TAG = "tag"

        /**
         * The status from the transport's state, read on its worker. [aired] is the writer's one snapshot ([AdvertWriter.aired]),
         * so the form and the PSM come from one reference; [mine] my current form; [linkedIds] the node ids a live link is
         * held under.
         */
        @Suppress("LongParameterList") // the status's sources, each a different reader in the transport
        fun of(
            aired: AdvertWriter.Aired<*>?,
            radioUp: Boolean,
            pending: Boolean,
            blinding: Boolean,
            extendedAdvertising: Boolean?,
            keyHeld: Boolean,
            turn: AdvertRotation.View,
            mine: AdvertOrder.Form,
            sightings: List<BlePresenceTracker.Snapshot>,
            linkedIds: Set<String>,
            links: List<Link>,
        ): AdvertStatus =
            AdvertStatus(
                extendedAdvertising = extendedAdvertising,
                keyHeld = keyHeld,
                radioUp = radioUp,
                pending = pending,
                blinding = blinding,
                form = aired?.let { if (it.form.blinded) FORM_TAG else FORM_CLEAR },
                epoch = aired?.installs ?: 0L,
                rotatedAtMs = aired?.takeIf { it.installs > 0 }?.installedAtMs,
                nextEdgeAtMs = turn.nextEdgeAtMs,
                announced = turn.announced,
                holding = turn.holding,
                psm = aired?.takeIf { !it.form.blinded }?.psm,
                peers = sightings.map { peer(it, mine, linkedIds) },
                links = links,
            )

        private fun peer(
            s: BlePresenceTracker.Snapshot,
            mine: AdvertOrder.Form,
            linkedIds: Set<String>,
        ): Peer {
            // A blinded sighting is keyed by its resolved id or its handle, never its tag (FormKeys). Should one ever be
            // keyed by the tag itself, it is shown as a handle-shaped redaction and counts as unresolved and unlinked.
            val keyedByTag = s.blinded && s.nodeId == s.advertised
            val handle = keyedByTag || FormKeys.isForm(s.nodeId)
            return Peer(
                peer = if (keyedByTag) "${FormKeys.MARK}${FormKeys.shown(s.advertised)}" else s.nodeId,
                resolved = !handle,
                blinded = s.blinded,
                iDial = AdvertOrder.iDial(mine, s.form),
                linked = !handle && s.nodeId in linkedIds,
            )
        }
    }
}

/**
 * Whether the runtime switch, stored as `want`, is on the air (`…debug.ADVERT --ez blind|clear`, step 6). The writer airs
 * what [BluetoothMeshTransport]'s `blinding()` says, never the switch alone: without extended advertising, or with the radio
 * down, it stays clear whatever is stored. So the wait ends when the form on the air matches `blinding()`, and the
 * verdict says whether that is what was asked. Pure.
 */
internal object AdvertSwitch {
    enum class Why(
        val wire: String,
    ) {
        NOT_RUNNING("transport not running"),

        /** `flipIfWanted` returns while the radio is down: the switch applies at the next bring-up. */
        RADIO_DOWN("radio down"),

        /** The controller has no extended advertising (or none read yet): the writer never blinds there. */
        NO_EXTENDED("no extended advertising"),

        /** The writer has no store for its tag marks, so it never blinds. */
        NO_MARK_STORE("no mark store"),

        /** A rotation's tag was still pending at the bound (its `afterInstall` re-runs the flip). */
        PENDING("pending"),

        TIMEOUT("timeout"),
    }

    /** [applied]: the form on the air is what the switch asked for; else [why]. */
    data class Result(
        val applied: Boolean,
        val why: Why? = null,
    )

    /**
     * The verdict now, or null to keep waiting. [markStore]: the writer has a store for its tag marks (without one it never
     * blinds, and the transport runs no switch collector, so [seen] would never come true); [seen]: the transport's copy
     * of the switch is `want` (it collects the key asynchronously); [airedBlinded] the snapshot on the air's kind (null
     * before the first); [blinding] what the writer should air; [extendedAdvertising] the controller's read; [timedOut]
     * the bound passed.
     */
    @Suppress("LongParameterList", "ReturnCount") // the verdict's facts, and one return per reason
    fun verdict(
        markStore: Boolean,
        want: Boolean,
        seen: Boolean,
        radioUp: Boolean,
        pending: Boolean,
        airedBlinded: Boolean?,
        blinding: Boolean,
        extendedAdvertising: Boolean?,
        timedOut: Boolean,
    ): Result? {
        if (!markStore) return Result(false, Why.NO_MARK_STORE)
        if (!seen) return if (timedOut) Result(false, Why.TIMEOUT) else null
        if (!radioUp) return Result(false, Why.RADIO_DOWN)
        if (pending || airedBlinded != blinding) {
            return if (!timedOut) null else Result(false, if (pending) Why.PENDING else Why.TIMEOUT)
        }
        if (blinding == want) return Result(true)
        // Asked on, airing clear because the writer cannot blind here.
        return Result(false, if (extendedAdvertising != true) Why.NO_EXTENDED else Why.NO_MARK_STORE)
    }
}

/**
 * `…debug.ADVERT_ROTATE` (step 6): one unannounced rotation now, through the ordinary path, with
 * [AdvertRotation.Cause.FORCED]. It is refused, with nothing rotated, whenever an ordinary rotation would not run now or
 * would cost the plane something; [refusal] is the rule, checked on the transport's worker in the same task as the
 * rotation. Pure.
 *
 * The slot's restarts ([app.getknit.knit.mesh.crypto.BlindTag.RESTARTS]) are burnt durably by each: past them, every
 * rotation or bring-up in that slot airs a random tag no contact resolves until the edge (linked peers are unharmed). A
 * timed trial must not mix forced rotations with its reps inside one slot.
 */
internal object ForcedRotation {
    enum class Refusal(
        val wire: String,
    ) {
        RADIO_DOWN("radio down"),

        /** A rotation's tag is being chosen. */
        PENDING("pending"),

        /** The writer is not blinding, or the clear form is on the air (a deferred flip to on keeps its settle). */
        CLEAR("clear"),

        /** An ACL edge's 2.5 s settle: fresh starts would land in the stack's pause and resume around a connection (9utz). */
        SETTLING("settling"),

        /**
         * An announcement stands: an unannounced rotation would spend the reservation, and the peers told of it would
         * apply a form never aired at the edge.
         */
        ANNOUNCED("announced"),

        /** A rotation hold applies (a fast Coded window, a hunt, a dial of mine in flight). */
        HOLDING("holding"),

        /**
         * A retired server is still in its grace, or the last install was under [PsmRotation.GRACE_MS] ago: at most one
         * retired server outstanding, so a loop of calls cannot pile up servers and accept loops or drain the LE PSM pool.
         */
        GRACE("grace"),

        /**
         * A live link was sent a form within the receiver's two-minute limit (`TagNextSends.MIN_INTERVAL_MS`): the
         * rotation's post-rotation send to it would be throttled, and that tag-aware peer would keep the old form as the
         * link's form.
         */
        THROTTLED("throttled"),
    }

    /** What the bridge reports: [rotated], or the [refused] reason; the install counts before and after. */
    data class Result(
        val rotated: Boolean,
        val refused: Refusal?,
        val epochBefore: Long,
        val epochAfter: Long,
    )

    /**
     * Why a forced rotation is refused now, or null when it may run. [airedBlinded]: the snapshot on the air carries a tag;
     * [retiredServerOpen]: a replaced server is still accepting; [sinceInstallMs]: elapsed time since the last install,
     * null when none; [formSentRecently]: a live link was sent a form inside the receiver's per-link limit
     * (`TagNextSends.sentWithin`).
     */
    @Suppress("LongParameterList", "ReturnCount") // the refusal's facts, and one return per reason
    fun refusal(
        radioUp: Boolean,
        pending: Boolean,
        blinding: Boolean,
        airedBlinded: Boolean,
        settleLeftMs: Long,
        announced: Boolean,
        holding: Boolean,
        retiredServerOpen: Boolean,
        sinceInstallMs: Long?,
        formSentRecently: Boolean,
    ): Refusal? {
        if (!radioUp) return Refusal.RADIO_DOWN
        if (pending) return Refusal.PENDING
        if (!blinding || !airedBlinded) return Refusal.CLEAR
        if (settleLeftMs > 0) return Refusal.SETTLING
        if (announced) return Refusal.ANNOUNCED
        if (holding) return Refusal.HOLDING
        if (retiredServerOpen || (sinceInstallMs != null && sinceInstallMs < PsmRotation.GRACE_MS)) return Refusal.GRACE
        if (formSentRecently) return Refusal.THROTTLED
        return null
    }
}
