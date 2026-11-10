package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.OwnPresenceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one writer of the presence advert (ADR 2026-11.xp6d's writer; step 5b's W3, a blocking finding of both the
 * bluetooth and the security design passes). Every advertiser write goes through one snapshot — the presence instance,
 * the Coded instance, the form on the air and the PSM it names ([Aired]) — replaced whole, only by [install] (a
 * rotation, a bring-up, a switch flip) and [replaceCoded] (a Coded power change), under one lock that every encode is
 * also made under. `readvertise` and its callers ([push]) read the snapshot and build from its form, never from the node
 * id while the snapshot is blinded.
 *
 * Why one writer: `readvertise` has seven callers on the transport's scope (the bring-up, the cue debounce, the Aware
 * flag, the side channel's availability, the Coded retry, the Coded power and mode), and a rotation computes its tag on
 * the confined worker (the tag schedule writes its mark there). Without a single snapshot, any of them could, between a
 * rotation's start and its swap, start a fresh set with the clear id or the previous tag and have the new tag follow
 * in place on the same address; or update the new set with the old tag; or restart the stopped old set through its
 * cold path. Each joins two forms on one address for everyone in range, every time.
 *
 * The rules this class keeps, each pinned by `AdvertWriterTest`:
 *  - **No form changes in place.** A snapshot's form is fixed; a new form is a new [Aired] with new instances, and the
 *    instances it replaces are [Sink.retire]d (inert from then on) before the new ones are written.
 *  - **Nothing airs while a tag is pending.** Between [begin] (or a blinding [up]) and [install], [push] starts and
 *    updates nothing; it records that a push is wanted, and [install] pushes.
 *  - **A blinded snapshot never airs the id.** Every payload [push] hands a sink while the snapshot is blinded is checked
 *    last: bytes 1–16 must be the form's tag, never the node id; one that is not is dropped and counted ([clearRefused]).
 *  - **A stale install is dropped.** [down] (adapter off, stop) moves the epoch; an [install] whose [begin] came before
 *    it retires its own new instances instead of starting them.
 *
 * Lock order: this lock, then a sink's own. A sink's callbacks run under the sink's lock on a binder thread, so they may
 * read [isCurrent] (a volatile read, no lock) but never call into this class otherwise.
 */
internal class AdvertWriter<S : AdvertWriter.Sink>(
    // Only for [Aired.installedAtMs], the debug bridge's `rotatedAtMs`. First, so a trailing lambda is still [localId].
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val localId: () -> String,
) {
    /** An advertising set the writer drives ([BleAdvertiser]). */
    interface Sink {
        /** Starts the set with [serviceData], or swaps the live set's data in place. */
        fun update(serviceData: ByteArray)

        /** Takes the set down; a later [update] may start it again (a bring-up, a Coded set that comes back). */
        fun stop()

        /** Takes the set down for good: from now on [update] and every other call is a no-op. */
        fun retire()
    }

    /**
     * What is on the air: the presence and Coded instances, the form both carry, and the L2CAP PSM they name. Immutable
     * and replaced whole, so one read of [aired] gives a form and a PSM from the same snapshot. [installs] and
     * [installedAtMs] are the writer's stamp at the bring-up or install that put it up (zero on one built by a caller and
     * not yet installed): how many snapshots this writer has installed by then, counting it, and when (wall clock). The
     * debug bridge's `epoch` and `rotatedAtMs` (step 6); a Coded power change keeps the stamp.
     */
    class Aired<S>(
        val presence: S,
        val coded: S?,
        val form: AdvertOrder.Form,
        val psm: Int,
        val installs: Long = 0,
        val installedAtMs: Long = 0,
    ) {
        internal fun stamped(
            installs: Long,
            atMs: Long,
        ): Aired<S> = Aired(presence, coded, form, psm, installs, atMs)

        /** Redacted: the form's own `toString` (four characters), never the tag. */
        override fun toString(): String = "Aired($form, installs=$installs)"
    }

    private val lock = Any()

    @Volatile private var aired: Aired<S>? = null

    // The snapshot's instances were stopped by [down] and no [up] or [install] has replaced it since: nothing pushes it
    // again (a bring-up whose rotation failed must not restart the last session's form).
    private var stale = false
    private var up = false
    private var pending = false
    private var pushWanted = false
    private var epoch = 0
    private var installs = 0L

    // [blindedOnAir]'s one source, written only under [lock] beside the snapshot it describes.
    private val _blindedOnAir = MutableStateFlow(false)

    /**
     * Whether the form this writer has on the air is blinded, while the radio is up: false at [up] (a clear form, or a
     * pending tag that has aired nothing yet), the new form's [AdvertOrder.Form.blinded] at each [install], false at
     * [down]. What is aired, never what the build or the switch asks for (`AdvertStatus.blinding` stays true through an
     * adapter off, a stop, a pending bring-up and a failed install; [aired] outlives [down]). A rotation's [begin] and a
     * Coded power change move nothing: the sets keep airing the same form until an [install]. Diagnostic-only: the
     * Diagnostics screen's "rotating ID" tag (`MeshTransport.advertBlinded`, ADR 2026-11.xp6d); nothing acts on it.
     */
    val blindedOnAir: StateFlow<Boolean> = _blindedOnAir.asStateFlow()

    /** Payloads dropped because a blinded snapshot's bytes 1–16 were not its tag (the id, or another form). */
    @Volatile var clearRefused = 0L
        private set

    /** The presence instance on the air, or the last one while the radio is down; null before the first. */
    fun presence(): S? = aired?.presence

    /** The Coded instance, as [presence]. */
    fun coded(): S? = aired?.coded

    /**
     * The snapshot on the air, or the last one while the radio is down; null before the first. One volatile read: a
     * reader that wants the form and the PSM together reads them from this one reference, never from [form] and [psm].
     */
    fun aired(): Aired<S>? = aired

    /** The form on the air (null before the first install). Read without the lock: a snapshot, replaced whole. */
    fun form(): AdvertOrder.Form? = aired?.form

    /** The PSM the snapshot names, or 0. */
    fun psm(): Int = aired?.psm ?: 0

    /** Whether [sink] is the presence or Coded instance of the snapshot on the air. A volatile read, safe from a callback. */
    fun isCurrent(sink: S): Boolean = aired.let { it != null && (it.presence === sink || it.coded === sink) }

    /** Whether a rotation's tag is being chosen ([begin] or a blinding [up] not yet [install]ed). */
    fun pending(): Boolean = synchronized(lock) { pending }

    /** Whether the radio is up ([up] since the last [down]). */
    fun isUp(): Boolean = synchronized(lock) { up }

    /**
     * The radio came up. Blinding ([pendingTag]), a rotation will [install] the tag: nothing airs until then. Otherwise
     * [clear] (the clear snapshot, its instances reused) goes on the air now through [write], and [onStamped] is told,
     * under the lock, the install count it was stamped with ([Aired.installs]). Returns the epoch token a rotation's
     * [install] must carry.
     */
    fun up(
        pendingTag: Boolean,
        clear: Aired<S>?,
        encode: (AdvertOrder.Form, Int) -> ByteArray,
        write: (Aired<S>, ByteArray) -> Unit,
        onStamped: (Long) -> Unit = {},
    ): Int =
        synchronized(lock) {
            up = true
            pending = pendingTag
            pushWanted = false
            _blindedOnAir.value = false
            if (!pendingTag && clear != null) {
                if (clear.form.blinded) error("a clear bring-up names a clear form")
                aired?.let { old -> retireReplaced(old, clear) }
                aired = clear.stamped(++installs, wallMs())
                onStamped(installs)
                stale = false
                pushLocked(encode, write)
            }
            epoch
        }

    /** A rotation begins: nothing airs until its [install]. Returns the epoch token it must carry. */
    fun begin(): Int =
        synchronized(lock) {
            pending = true
            epoch
        }

    /**
     * The rotation that [begin] started will not [install]: pushes resume on the snapshot on the air, unless the radio
     * went down since (a bring-up's snapshot is the last session's, stopped: it stays down until an [install]).
     */
    fun abandon(token: Int) {
        synchronized(lock) {
            if (token != epoch) return
            pending = false
        }
    }

    /**
     * The radio went down (adapter off, transport stop): every instance stops (not retired: a clear bring-up reuses
     * them), pushes stop, and any rotation begun before now is stale.
     */
    fun down() {
        synchronized(lock) {
            epoch++
            up = false
            stale = true
            pending = false
            pushWanted = false
            _blindedOnAir.value = false
            aired?.let {
                it.presence.stop()
                it.coded?.stop()
            }
        }
    }

    /**
     * Swaps the snapshot [prepare] builds onto the air: the instances it replaces are retired first (stopped before the
     * new ones start, never overlapping, so a controller near its set budget is never asked for both), then the new
     * ones are written. [prepare] runs under the lock and only while [token]'s epoch is current, so nothing it opens (a
     * server, a set) outlives a [down] that came first: a [down] that comes after waits for the swap and then stops what
     * it installed. False when the epoch is over, or [prepare] had nothing to install (no server); a rotation's pending
     * state then stays until the next [install], so nothing airs that the rotation did not choose.
     */
    fun install(
        token: Int,
        prepare: () -> Aired<S>?,
        encode: (AdvertOrder.Form, Int) -> ByteArray,
        write: (Aired<S>, ByteArray) -> Unit,
        // Told, under the lock, the install count the new snapshot was stamped with ([Aired.installs]).
        onStamped: (Long) -> Unit = {},
    ): Boolean =
        synchronized(lock) {
            if (token != epoch || !up) return false
            val next = prepare() ?: return false
            aired?.let { old -> retireReplaced(old, next) }
            aired = next.stamped(++installs, wallMs())
            onStamped(installs)
            _blindedOnAir.value = next.form.blinded
            stale = false
            pending = false
            pushWanted = false
            pushLocked(encode, write)
            true
        }

    /** A new Coded instance (a Coded TX power change) under the same form: the old one retires, the snapshot is pushed. */
    fun replaceCoded(
        coded: S,
        encode: (AdvertOrder.Form, Int) -> ByteArray,
        write: (Aired<S>, ByteArray) -> Unit,
    ) {
        synchronized(lock) {
            val old = aired
            if (old == null) {
                coded.retire()
                return
            }
            old.coded?.retire()
            aired = Aired(old.presence, coded, old.form, old.psm, old.installs, old.installedAtMs)
            if (up && !pending && !stale) pushLocked(encode, write)
        }
    }

    /**
     * `readvertise`: the snapshot's form and PSM, encoded now, written through [write] to the snapshot's own instances.
     * Nothing while the radio is down; while a tag is pending, only a note that a push is wanted ([install] pushes).
     * Returns whether it wrote.
     */
    fun push(
        encode: (AdvertOrder.Form, Int) -> ByteArray,
        write: (Aired<S>, ByteArray) -> Unit,
    ): Boolean =
        synchronized(lock) {
            if (!up || stale) return false
            if (pending) {
                pushWanted = true
                return false
            }
            pushLocked(encode, write)
        }

    /** Whether a push was asked for while a tag was pending (diagnostics; [install] serves it). */
    fun pushWanted(): Boolean = synchronized(lock) { pushWanted }

    /** Holds [lock]. */
    private fun pushLocked(
        encode: (AdvertOrder.Form, Int) -> ByteArray,
        write: (Aired<S>, ByteArray) -> Unit,
    ): Boolean {
        val a = aired ?: return false
        val bytes = encode(a.form, a.psm)
        if (!carriesOnly(a.form, bytes)) {
            clearRefused++
            return false
        }
        write(a, bytes)
        return true
    }

    /**
     * The last check before bytes reach a set: a blinded form's payload carries the tag in bytes 1–16 and never the node
     * id; a clear one carries the id. Anything else is dropped.
     */
    private fun carriesOnly(
        form: AdvertOrder.Form,
        bytes: ByteArray,
    ): Boolean {
        if (bytes.size < 1 + NodeId.BYTES) return false
        val named = NodeId.fromBytes(bytes.copyOfRange(1, 1 + NodeId.BYTES))
        if (!form.blinded) return named == localId()
        return named == form.advertised && named != localId()
    }

    /** Holds [lock]: retires what [old] holds that [next] does not reuse. */
    private fun retireReplaced(
        old: Aired<S>,
        next: Aired<S>,
    ) {
        if (old.presence !== next.presence) old.presence.retire()
        val coded = old.coded
        if (coded != null && coded !== next.coded) coded.retire()
    }
}

/**
 * Which tag a blinding rotation airs (step 5b's W2/W3), as one pure decision over the tag schedule: the key the transport
 * holds ([held], the feed's last value), the engine's value read just now ([live], `MeshTransport.onOwnPresenceKeySource`)
 * and whether the turn is airing an announced slot ([announced]).
 *
 *  - No key: a random prefixed tag (never the id, never a clear start followed by an in-place switch).
 *  - The engine's generation is not the one held (a fresh pair landed inside the feed's hop): a random tag, and the
 *    reservation is dropped unaired; the feed's change follows and rotates again.
 *  - Announced: exactly what `TagSchedule.take` hands back; a reservation that missed its slot falls through to `next`.
 *  - Otherwise `TagSchedule.next`. A mark store that throws (a demoted generation's conditional write refused, a storage
 *    fault) gives a random tag: nothing is aired under a mark that is not durable, and never the id.
 */
internal object TagChoice {
    /**
     * Why the tag is what it is: counted, never logged beside a time (release logs and the field log); a debug build's
     * `…debug.ADVERT` reports the last one's with its `resolvable` and `stale` (the maintainer, 2026-11-04).
     */
    enum class Source { ANNOUNCED, SLOTTED, NO_KEY, KEY_MOVED, MARK_FAILED }

    /** [generation] is the key generation the rotation aired under (null with no key, or one the engine moved past). */
    class Choice(
        val form: AdvertOrder.Form,
        val generation: Long?,
        val source: Source,
        val resolvable: Boolean,
        val stale: Boolean,
    ) {
        override fun toString(): String = "Choice(source=$source, resolvable=$resolvable)"
    }

    fun choose(
        schedule: TagSchedule,
        held: OwnPresenceKey?,
        live: OwnPresenceKey?,
        announced: Boolean,
        wallMs: Long,
    ): Choice {
        if (held == null) return random(schedule, Source.NO_KEY)
        if (live?.generation != held.generation) {
            schedule.take(held.generation, wallMs) // dropped unaired either way
            return random(schedule, Source.KEY_MOVED)
        }
        if (announced) {
            schedule.take(held.generation, wallMs)?.let { return slotted(it, held.generation, Source.ANNOUNCED) }
        } else {
            schedule.take(held.generation, wallMs) // a reservation this unannounced rotation passes by is spent, unaired
        }
        val next =
            runCatching { schedule.next(held.key, held.generation, wallMs) }.getOrElse {
                return random(schedule, Source.MARK_FAILED, held.generation)
            }
        return slotted(next, held.generation, Source.SLOTTED)
    }

    private fun slotted(
        next: TagSchedule.Next,
        generation: Long,
        source: Source,
    ): Choice =
        Choice(
            AdvertOrder.Form(NodeId.fromBytes(next.tag), blinded = true),
            generation,
            source,
            next.resolvable,
            next.stale,
        )

    private fun random(
        schedule: TagSchedule,
        source: Source,
        generation: Long? = null,
    ): Choice = Choice(AdvertOrder.Form(NodeId.fromBytes(schedule.random().tag), blinded = true), generation, source, false, false)
}
