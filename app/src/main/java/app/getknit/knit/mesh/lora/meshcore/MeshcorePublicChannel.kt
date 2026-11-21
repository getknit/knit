package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.MeshcoreReactions
import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.lora.BoardState
import app.getknit.knit.mesh.lora.Destination
import app.getknit.knit.mesh.lora.FragmentOutcome
import app.getknit.knit.mesh.lora.LoraAirtime
import app.getknit.knit.mesh.lora.LoraFramePolicy
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.OrderedRoomSink
import app.getknit.knit.mesh.lora.OutboundFrame
import app.getknit.knit.mesh.lora.PlaneCore
import app.getknit.knit.mesh.lora.PublicChannelFace
import app.getknit.knit.mesh.lora.PublicPostEnded
import app.getknit.knit.mesh.lora.PublicPostKey
import app.getknit.knit.mesh.lora.PublicPostResult
import app.getknit.knit.mesh.lora.SendPurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * A MeshCore board's Public channel (slot 0), mirrored both ways (ADR 2026-11.gsqu's Stage 6 amendment): the half of
 * [MeshcoreHooks] that reads a heard text into the room ([onText]), puts the user's post on the air ([post], [send]) and
 * renders slot 0 on the plane's face ([face]). Made by the hooks, over the same session, core and counters.
 *
 * A heard text is judged ([MeshcorePublicJudge]) and handed to the room as a stranger's, never a hearing. The user's own
 * post leaves only from a board set up for Knit (consented), under the name the user's consent decision saw: the firmware
 * prefixes the board's own name, which is its owner's handle and may be their real name (ADR 2026-11.f4d3, amending lora B1
 * / security B2), so a post is refused when the name moved since, or breaks the postable rules. Under the flood ceiling
 * beside Knit's room posts, one per 30 s, its stamp and the name fixed at enqueue. Nothing about a post is ever logged:
 * counters, sizes and reasons only (security N8).
 *
 * State: the floor's stamp, an atomic (a post comes from `MeshManager`'s coroutine, the texts from the packet collector);
 * the keys of the posts still queued, under their own monitor (the enqueue on `MeshManager`'s coroutine, the write on the
 * pacer).
 */
internal class MeshcorePublicChannel(
    private val session: MeshcoreBoardOps,
    private val core: PlaneCore,
    scope: CoroutineScope,
    private val clock: () -> Long,
    private val wallClock: () -> Long,
    private val log: (String) -> Unit,
    private val counters: MeshcorePlaneCounters,
    /** This phone's consent for the Ready board, as the session's actor decided it (null before the first). */
    private val consent: () -> Boolean?,
    /** The room's inbound sink: each judged post in turn, on [scope], in the order the board handed them over. */
    onPublicPost: suspend (MeshcorePublicPost) -> Unit,
) {
    /**
     * When this phone last queued a Public post, for the per-phone floor ([LoraMeshTransport.PUBLIC_POST_FLOOR_MS], the
     * constant the Meshtastic room shares): monotonic ([clock]), in memory, never persisted — a restart may post once more
     * inside 30 s, as the Meshtastic room's may. [NEVER] so a session's first post is never held. Claimed at enqueue.
     */
    private val lastPostAt = AtomicLong(NEVER)

    /**
     * The key each queued post was answered with, by its frame's (stamp, text): what an end without airing is reported
     * under. Bounded ([PENDING_MAX]; at one post a 30 s that is hours of queue); a post the core shed or drained before its
     * write never reaches [send] and stays here until pushed out — its end is not reported (the core drops it uncounted to
     * this kind).
     */
    private val pending = LinkedHashMap<String, PublicPostKey>()

    /**
     * The room's inbound queue (part 6c): judged posts reach [onPublicPost] one at a time, in the order the board handed
     * them over — an offline-queue drain lands in its own order — without the packet collector ever waiting on the room.
     * Full, a post is counted (`meshcorePublicBacklogDropped`) and logged, never silently lost.
     */
    private val room =
        OrderedRoomSink<MeshcorePublicPost>(
            scope,
            onDropped = {
                counters.publicBacklogDropped.incrementAndGet()
                log("lora public post dropped: room backlog full")
            },
            onFailed = { log("lora public post not delivered: ${it.javaClass.simpleName}") },
            deliver = onPublicPost,
        )

    private val endedFlow = MutableSharedFlow<PublicPostEnded>(extraBufferCapacity = ENDED_BUFFER)

    /** Queued posts that ended without airing: stale at the write, or given up there. Hot, no replay. */
    val ended: SharedFlow<PublicPostEnded> = endedFlow.asSharedFlow()

    /**
     * A text on slot 0, on the packet collector: dropped and counted while the MeshCore room is off (`LoraConfig.room`, which
     * this plane's config folds from `meshcoreRoomEnabled` alone, never the bound kind's facts: security N13) or slot 0 is
     * Knit's own; else judged and the post queued for the room behind every earlier one ([room]), live or replayed from the
     * board's offline queue alike. Returns at once.
     */
    fun onText(mc: MeshcorePacket) {
        counters.publicHeard.incrementAndGet()
        if (core.config?.room != true) {
            counters.publicRoomOff.incrementAndGet()
            return
        }
        val detail = session.ready?.detail as? MeshcoreDetail
        if (detail?.publicSlot?.knit == true) {
            counters.publicRefused.incrementAndGet()
            return
        }
        when (val verdict = MeshcorePublicJudge.judge(mc.text, mc.senderTimestamp, mc.live)) {
            is MeshcorePublicJudge.Verdict.Refused -> {
                counters.publicRefused.incrementAndGet()
            }

            is MeshcorePublicJudge.Verdict.Post -> {
                counters.publicDelivered.incrementAndGet()
                room.offer(verdict.post)
            }
        }
    }

    /**
     * Slot 0 as the room's face shows it: its name and whether its key is Public off the Ready's slot table, and whether a
     * post may leave now — the same structural gate [post] asks (set up for Knit, slot 0 holding a channel that is not
     * Knit's). Null while not Ready. Reads volatiles only (called under the core's status lock).
     */
    fun face(state: BoardState): PublicChannelFace? {
        val detail = (state as? BoardState.Ready)?.detail as? MeshcoreDetail ?: return null
        val slot = detail.publicSlot ?: return null
        return PublicChannelFace(
            name = slot.name,
            keyIsPublic = slot.keyIsPublic,
            postable = setUpForKnit() && detail.namePostable && !slot.empty && !slot.knit,
        )
    }

    /**
     * The board is set up for Knit on this phone, by this connection's consent. Its name is the owner's handle and no part of
     * it since ADR 2026-11.f4d3: [post] asks the name apart (postable, and the one consented).
     */
    private fun setUpForKnit(): Boolean = consent() == true

    /**
     * Puts the user's post on the board's slot 0, or says why not, from `MeshManager`'s coroutine. Each guard is asked here,
     * so each refusal is counted and named, in this order:
     * - [PublicPostRefusal.ROOM_OFF]: this plane's room switch (`meshcoreRoomEnabled`);
     * - [PublicPostRefusal.NOT_READY]: no Ready board;
     * - [PublicPostRefusal.NOT_SET_UP]: this connection holds no consent for the board;
     * - [PublicPostRefusal.NAME_UNPOSTABLE]: the board's name breaks the postable rules ([MeshcoreNames.check]: another app
     *   wrote a `": "`, a control or bidi override, or bytes that are not UTF-8) — rename the board;
     * - [PublicPostRefusal.NAME_CHANGED]: [consented], the name the composer's consent decision saw, is not the board's name
     *   now (another app renamed it, or the user gave no name): the composer asks again under the new prefix;
     * - [PublicPostRefusal.NO_CHANNEL] / [PublicPostRefusal.KNIT_ON_PRIMARY]: slot 0 holds no channel (it would air under the
     *   all-zero key) or Knit's own (lora B2);
     * - [PublicPostRefusal.TOO_SOON]: inside the per-phone floor;
     * - [PublicPostRefusal.TOO_LARGE]: over the cap under the board's name ([MeshcoreTraits.publicTextCap]: 160 B less the
     *   name and `": "`), empty once a NUL is dropped (no receiver reads
     *   past one: `strlen`, `MyMesh.cpp:577`), or more than the flood ceiling of an empty window can ever carry (a
     *   [LoraAirtime.Refusal.NEVER], read once the band's duty is known);
     * - [PublicPostRefusal.NO_AIR]: the window's total or the floods' share would not admit it now, at the price the booking
     *   will charge ([LoraAirtime.publicPrice] at the name's length: the `"<name>: "` prefix included), or the queue would
     *   not take it.
     *
     * Then the floor is claimed and the post queued under [MeshcoreTraits.publicCeiling] with the sender timestamp and the
     * board's name fixed now, which the frame carries to every write of it (its price reads the name; the write refuses it
     * when the board reads another). The answer's key is the post as receivers will read it (the name's own bytes, `": "`
     * and the text — never through a `String`, so a name cut mid-character still matches), what our own row's id is derived
     * from, so an echo of it dedups by id: never by name. Beside it, the post's MeshCore One reaction tag over the text bytes
     * queued and the stamp (ADR 2026-11.gsqu's 2026-11-21 amendment). Admission reads only what
     * the window has booked, not what is queued: a post admitted behind queued room posts can still be skipped under the
     * ceiling and wait, while its row reads sent (the amendment's accepted gap).
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one guard per line, each its own refusal, in the brief's order
    fun post(
        body: String,
        consented: MeshcoreHandle?,
    ): PublicPostResult {
        if (core.config?.room == false) return refuse(PublicPostRefusal.ROOM_OFF)
        val detail = session.ready?.detail as? MeshcoreDetail ?: return refuse(PublicPostRefusal.NOT_READY)
        if (!setUpForKnit()) return refuse(PublicPostRefusal.NOT_SET_UP)
        if (!detail.namePostable) return refuse(PublicPostRefusal.NAME_UNPOSTABLE)
        val name = detail.handle
        if (consented != name) return refuse(PublicPostRefusal.NAME_CHANGED)
        val slot = detail.publicSlot
        if (slot == null || slot.empty) return refuse(PublicPostRefusal.NO_CHANNEL)
        if (slot.knit) return refuse(PublicPostRefusal.KNIT_ON_PRIMARY)
        val now = clock()
        val last = lastPostAt.get()
        if (last != NEVER && now - last < LoraMeshTransport.PUBLIC_POST_FLOOR_MS) return refuse(PublicPostRefusal.TOO_SOON)
        val text = body.replace(NUL, "").encodeToByteArray()
        if (text.isEmpty() || text.size > MeshcoreTraits.publicTextCap(name.size)) return refuse(PublicPostRefusal.TOO_LARGE)
        val airtime = core.airtime
        // At the name's own price, the one the frame's booking reads (OutboundFrame.price over its publicName).
        val air = airtime.refusal(MeshcoreTraits.publicCeiling, listOf(text.size), now, airtime.publicPrice(name.size))
        // Never on this band, whatever the window: too large, not a wait. On the fallback duty (a board that reported no sane
        // radio) it may fit once a real one lands, so that is air.
        if (air == LoraAirtime.Refusal.NEVER && airtime.dutyKnown()) return refuse(PublicPostRefusal.TOO_LARGE)
        if (air != null) return refuse(PublicPostRefusal.NO_AIR)
        // Claimed at enqueue, as the Meshtastic room's: the floor is about how often this phone decides to speak. A post that
        // raced this one past the check claims first, and this one is too soon.
        if (!lastPostAt.compareAndSet(last, now)) return refuse(PublicPostRefusal.TOO_SOON)
        val stamp = wallClock() / MS_PER_SECOND
        if (!core.enqueuePublic(text, LABEL, MeshcoreTraits.publicCeiling, stamp, name)) {
            lastPostAt.compareAndSet(now, last)
            return refuse(PublicPostRefusal.NO_AIR)
        }
        counters.publicPostsQueued.incrementAndGet()
        val key = PublicPostKey(stamp, name.bytes + SEPARATOR.encodeToByteArray() + text)
        synchronized(pending) {
            pending[pendingKey(stamp, text)] = key
            while (pending.size > PENDING_MAX) pending.remove(pending.keys.first())
        }
        // The reaction tag over exactly the bytes the board airs after its "name: " and the stamp the frame carries to every
        // write (a Busy retry reuses both): never key.raw, which leads with the name, nor the String the row saves.
        return PublicPostResult.Queued(key, MeshcoreReactions.tag(text, stamp))
    }

    private fun pendingKey(
        stamp: Long,
        text: ByteArray,
    ): String = "$stamp:" + text.contentHashCode() + ":" + text.size

    /** The key [post] answered for this frame, taken out of [pending]; null for one pushed out of it. */
    private fun takeKey(
        stamp: Long,
        text: ByteArray,
    ): PublicPostKey? = synchronized(pending) { pending.remove(pendingKey(stamp, text)) }

    /** Reports a queued post ended without airing, and counts it and logs the edge (never its key). */
    private fun ended(
        stamp: Long,
        text: ByteArray,
        reason: PublicPostEnded.Reason,
    ) {
        counters.publicPostsEnded.incrementAndGet()
        log(
            when (reason) {
                PublicPostEnded.Reason.STALE -> "lora public post stale at the write"
                PublicPostEnded.Reason.REFUSED_AT_WRITE -> "lora public post refused at the write"
                PublicPostEnded.Reason.ENDED_AT_WRITE -> "lora public post ended at the write"
            },
        )
        takeKey(stamp, text)?.let { endedFlow.tryEmit(PublicPostEnded(it, reason)) }
    }

    private fun refuse(reason: PublicPostRefusal): PublicPostResult {
        counters.publicPostsRefused.incrementAndGet()
        log("lora public post refused: $reason")
        return PublicPostResult.Refused(reason)
    }

    /**
     * Writes a queued Public post through the core's [send] under the timestamp its frame carries (the session re-asks the
     * set-up gate and slot 0 at the write), and counts what became of it. The only kind frame this kind queues: anything
     * else, or a frame with no stamp, is never written. OWED is back on the queue (a busy board, a session gone under it) and
     * keeps its stamp. A post past its `freshUntil` ([LoraFramePolicy.FRESH_MS] after its stamp, set at enqueue) is never
     * written: kind frames skip the core's stale check, and airing it under an old stamp would file it hours back on a stock
     * client and new on a Knit one. Every end without airing is reported on [ended]: a refusal before the board took it
     * ([FragmentOutcome.REFUSED]: the session's gate, the name, the firmware's `ERR`) as never sent, a write whose answer
     * never came as one that may have aired. Never fragmented: one channel text is the whole post. Called on the pacer.
     */
    suspend fun send(
        frame: OutboundFrame,
        send: suspend (ByteArray, SendPurpose) -> FragmentOutcome,
    ) {
        val message = frame.messages.firstOrNull() ?: return
        val stamp = frame.timestampSecs
        if (frame.destination != Destination.Public || stamp == null) return
        val freshUntil = frame.gate.freshUntil
        if (freshUntil != null && wallClock() > freshUntil) return ended(stamp, message, PublicPostEnded.Reason.STALE)
        when (send(message, SendPurpose.PublicPost(stamp, frame.publicName))) {
            FragmentOutcome.OWED -> {
                return
            }

            FragmentOutcome.ENDED -> {
                ended(stamp, message, PublicPostEnded.Reason.ENDED_AT_WRITE)
            }

            FragmentOutcome.REFUSED -> {
                ended(stamp, message, PublicPostEnded.Reason.REFUSED_AT_WRITE)
            }

            FragmentOutcome.SENT -> {
                takeKey(stamp, message)
                counters.publicPostsSent.incrementAndGet()
                log("lora tx public ${message.size}B")
            }
        }
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val NUL = "\u0000"
        const val SEPARATOR = ": "
        const val LABEL = "public"
        const val MS_PER_SECOND = 1_000L
        const val PENDING_MAX = 64
        const val ENDED_BUFFER = 16
    }
}
