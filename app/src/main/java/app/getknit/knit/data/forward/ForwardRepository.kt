package app.getknit.knit.data.forward

import android.util.Log
import androidx.room3.withWriteTransaction
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.CarriedAttachment
import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.CarryReader
import app.getknit.knit.mesh.CarrySnapshot
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.RoomAttachments
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ForwardStore] backed by the encrypted `forward_store` table. Persists carried DM, group, and
 * broadcast-room chat frames as canonical CBOR (signed blob + signature) and enforces the store's bounds
 * so epidemic carry can't grow without limit:
 *
 *  - a per-message **TTL** ([ttlMs] for DMs/groups, the shorter [broadcastTtlMs] for the ambient
 *    broadcast room) after which the sweep reclaims a frame even if never acked (the *only* purge path
 *    for group/broadcast messages — no reliable ack — and the backstop for DMs whose receipt never arrives);
 *  - a **per-sender quota** ([maxPerSender]) so one identity (Sybil is cheap on this mesh) can't fill
 *    the buffer with junk we'd re-offer to everyone;
 *  - a **per-group quota** ([maxPerGroup]) so one busy group can't monopolize the buffer either;
 *  - a **broadcast quota** ([maxBroadcast]) so ambient broadcast chatter can't crowd out addressed messages;
 *  - a **global cap** ([maxRows]) that evicts from the **largest sender bucket** first, one row at a time, so a
 *    flood of free identities displaces its own frames before anyone else's (ADR 2026-10.7mqm); which of that
 *    bucket's rows goes reads [carry], how many peers hold each (the custody-carry-policy contract's §5);
 *  - a **byte cap** ([maxSignedBytes]) on each frame's `signed` bytes, so the row cap bounds what custody holds on disk
 *    and re-serves too (#227): without it a thousand rows of any size were a few hundred megabytes on every carrier;
 *  - a **profile cap** ([maxProfileSignedBytes]) on a `profile` frame's `signed` bytes, under the byte cap: the inbound
 *    door refuses a longer one before it is decoded, and custody refuses it on every origin, so nothing re-serves a
 *    profile no peer would read (#234).
 *
 * The sender, group and room quotas keep their newest-N by the frame-global `(sentAt, id)`. Origin plays no part in
 * any of them: our own sends are bounded exactly like relayed traffic (ADR 006). Every refusal, the TTL, those trims
 * and the digest fold read the frame and the held set alone, so two stores under [maxRows] holding the same frames keep
 * the same ones. Only the global cap's choice of row reads per-node state, who is believed to hold what, so stores at
 * the cap may differ for good; whatever eviction reads, a peer can read back from our digest. A frame naming a group is
 * carried only when its roster derives to the group's id and names its sender (ADR 2026-10.7mqm), so nobody outside a
 * group can fill its quota.
 *
 * Every bound is evaluated over **live** rows only, and the digest folds only live ids — an expired-but-unswept
 * row is per-node state (sweep ticks phase independently), so letting it into any rule would de-converge the
 * carried sets; the sweep is pure storage GC (work item #8).
 */
class ForwardRepository(
    private val dao: ForwardDao,
    // Content digest of the carried set; kept in lockstep with the table so the transport can cue it and skip
    // no-op syncs. Maintained here (the one place that sees every mutation) rather than in the pure ForwardSync.
    private val digest: StoreDigest,
    private val db: KnitDatabase,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val broadcastTtlMs: Long = DEFAULT_BROADCAST_TTL_MS,
    private val maxRows: Int = DEFAULT_MAX_ROWS,
    private val maxSignedBytes: Int = DEFAULT_MAX_SIGNED_BYTES,
    // The profile cap (#234): read by the door in `InboundPipeline.verifyInbound` too, so a rig that lowers one lowers both.
    override val maxProfileSignedBytes: Int = DEFAULT_MAX_PROFILE_SIGNED_BYTES,
    private val maxPerSender: Int = DEFAULT_MAX_PER_SENDER,
    private val maxPerGroup: Int = DEFAULT_MAX_PER_GROUP,
    private val maxBroadcast: Int = DEFAULT_MAX_BROADCAST,
    // Counts the frames [store] refuses for their size or their group's roster; null in the rigs that do not read it.
    private val metrics: MeshMetrics? = null,
    // The carry ledger's read side: the global cap's key 2 (spread), and the events that keep its held set in step with
    // the table, each called under [mutex]. EMPTY orders the cap by the frame alone.
    private val carry: CarryReader = CarryReader.EMPTY,
) : ForwardStore {
    // Serializes store/remove/sweepExpired so each in-memory [digest] update commits atomically with its
    // forward_store row write: a Room transaction can't enroll the in-memory digest, and a concurrent sweep's
    // wholesale rebuild would otherwise clobber an incremental add (leaving a live row absent from the digest
    // and breaking StoreDigest's `current() == liveFingerprint` invariant). These run off the inbound collector
    // too — own sends on viewModelScope / the notification scope, the sweep on the 10-min prune loop — so it is
    // real contention, not belt-and-suspenders. Held OUTER to db.withWriteTransaction; the reverse deadlocks on
    // SQLCipher's single connection (a coroutine holding the connection while waiting on the mutex, vs. one
    // holding the mutex while waiting to BEGIN).
    private val mutex = Mutex()

    override suspend fun store(
        frame: CarriedFrame,
        origin: Int,
        now: Long,
        source: String?,
    ): Boolean {
        val env = frame.envelope
        // The byte cap (#227) comes first, before anything reads the payload. A frame whose `signed` is longer than
        // [maxSignedBytes] is refused on every origin, our own included: no honest build authors one, and a size is a
        // function of the frame alone, so every node refuses the same frames and the digest is unmoved. Every refusal
        // here is such a function, so the order only decides which reason is counted: an oversize frame naming a
        // foreign roster counts as oversize. `sig` is not counted. Delivery and relay are unchanged.
        if (frame.signed.size > maxSignedBytes) {
            metrics?.onCustodyOversizeRefused()
            Log.w(
                TAG,
                "custody refused ${env.id.take(ID_CHARS)} (${env.type}): ${frame.signed.size} signed bytes over the cap of $maxSignedBytes",
            )
            return false
        }
        // The profile cap (#234), after the byte cap so a frame over both counts once, as custody oversize. A function of
        // the frame's type and length alone, refused on every origin like the byte cap: no write, no eviction, and the
        // digest unmoved on every node. The door in `InboundPipeline.verifyInbound` refuses the same frames first.
        if (env.type == FrameType.PROFILE && frame.signed.size > maxProfileSignedBytes) {
            metrics?.onProfileOversizeRefused()
            Log.w(
                TAG,
                "custody refused profile ${env.id.take(
                    ID_CHARS,
                )}: ${frame.signed.size} signed bytes over the cap of $maxProfileSignedBytes",
            )
            return false
        }
        val groupId = env.group?.id
        // Only broadcast-room *chat* is the ambient, higher-volume, shorter-lived class. Reactions, receipts,
        // and profiles share its null recipient/group shape but are higher-value metadata, so they must be
        // bucketed apart (by type) — else they'd inherit the broadcast quota/TTL and be starved or expire early.
        val isBroadcastChat = env.type == FrameType.CHAT && env.recipientId == null && groupId == null
        // Broadcast-room chat is ambient, higher-volume, no-ack chatter, so it gets a shorter TTL than an
        // addressed DM/group message or a carried metadata frame (reaction/receipt/profile keep the full
        // TTL, so they never expire before the message/peer they describe). Expiry is keyed off the
        // frame-global sentAt (the originator's signed, node-identical clock), NOT local receivedAt, so every
        // node — originator and every carrier — expires the same frame at the same absolute instant. Keying it
        // off local arrival let a late joiner hold a frame long after the originator swept it; a still-live
        // peer then re-served it via the id-diff and it re-stored with a fresh full TTL, so broadcast/group
        // frames (bounded only by TTL — no ack) never died mesh-wide and the digests churned. Same
        // convergence rule as the sentAt-ordered quota eviction below.
        val expiresAt = env.sentAt + if (isBroadcastChat) broadcastTtlMs else ttlMs
        // Dead on arrival: a frame past its frame-global expiry is one every node has already dropped (or never
        // held). Persisting it would plant an expired row that the live-only content digest refuses to fold, so
        // it would be pure invisible residue — and the refusal is also what stops a skewed-clock peer's re-serve
        // from resurrecting a swept frame. Refuse so the caller skips the follow-on work (blob custody).
        if (expiresAt < now) return false
        // Future-dated cap — the upper complement of the dead-on-arrival guard above. `sentAt` is the sender's
        // self-attested, unverifiable clock AND the frame-global eviction key, so a frame future-dated past a
        // generous skew window would be un-sweepable (its expiry is far off) and the newest-by-`sentAt` in its
        // buckets. Refuse it. Every node compares against its own `now` exactly like the DOA guard, so an honest
        // frame (sentAt ≈ now) passes on every node. The window stays at five minutes (ADR 2026-10.7mqm): since the
        // global cap evicts from the largest sender first, a stamp inside it only orders a sender's own frames, and
        // tightening it would refuse a fast-clocked honest frame the router has already marked seen, so the peers'
        // digests would differ for a seen window per frame.
        if (env.sentAt > now + Protocol.MAX_FUTURE_SKEW_MS) return false
        // A group frame is carried only for its own group's members (ADR 2026-10.7mqm). The id is the hash of the
        // founding roster, so the frame's own members and departed must derive to it, and its sender must be one of the
        // members. Without this any pinned identity that knew a group's id filled that group's quota on every carrier.
        // A function of the frame alone, so every node refuses alike and the digest is unmoved; here rather than in the
        // carry gate (`InboundPipeline.canCarry`), which is also the spool plane's delivery gate and which our own
        // frames skip. Delivery and relay are unchanged: `vetRoster` still decides what a member reads.
        val group = env.group
        if (group != null && !carriesGroup(group, env.senderId)) {
            metrics?.onGroupCustodyRefused()
            Log.w(TAG, "custody refused ${env.id.take(ID_CHARS)} from ${env.senderId.take(ID_CHARS)}: roster of ${group.id} is not its own")
            return false
        }
        // Denormalize the image content hash so the carrier can pull+hold the blob and pin it against GC. This
        // decode is best-effort metadata ONLY — it must never gate the insert: a null (non-chat, no attachment,
        // or an unreadable payload) still stores the frame, so `forward_store` ids stay byte-identical mesh-wide
        // and the content digest converges (the digest folds only `id`, never this column). A room post's voice note
        // or file is left out (RoomAttachments): no row draws it, so the database re-arm must never pull it.
        val attachmentHash =
            if (env.type == FrameType.CHAT) {
                WireCodec.decodePayload<ChatContent>(env.payload)?.takeUnless { RoomAttachments.refuses(env, it) }?.attachmentHash
            } else {
                null
            }
        val row =
            ForwardEntity(
                id = env.id,
                recipientId = env.recipientId,
                groupId = groupId,
                senderId = env.senderId,
                type = env.type,
                origin = origin,
                signed = frame.signed,
                sig = frame.sig,
                sentAt = env.sentAt,
                receivedAt = now,
                attachmentHash = attachmentHash,
                expiresAt = expiresAt,
            )
        // A frame its own write trimmed is one we do not hold: the caller must not pull its attachment for it.
        return commit(row, isBroadcastChat, now, source)
    }

    /**
     * Writes [row] and trims, then brings the digest and [carry] up to date; returns whether [row] is held afterwards.
     * The row insert + quota eviction (atomic under withWriteTransaction) and the in-memory digest update are one
     * critical section (mutex). The digest update runs AFTER commit but still under the lock, so a rolled-back
     * transaction never leaves the digest ahead of the table and no concurrent store/sweep interleaves between the
     * commit and the update.
     */
    private suspend fun commit(
        row: ForwardEntity,
        isBroadcastChat: Boolean,
        now: Long,
        source: String?,
    ): Boolean =
        mutex.withLock {
            val trimmed = db.withWriteTransaction { insertAndTrim(row, isBroadcastChat, now, source) }
            // Eviction removed ids we don't track individually here, so rebuild the fingerprint wholesale;
            // otherwise fold the single new id in incrementally (the hot path when no bucket is over quota).
            if (trimmed.evicted) rebuildDigest(now) else digest.add(row.id, row.expiresAt, now)
            if (trimmed.kept) carry.onKept(row.id, source)
            trimmed.kept
        }

    /** What [insertAndTrim] did: whether any row went, and whether the row it inserted is still held. */
    private class Trimmed(
        val evicted: Boolean,
        val kept: Boolean,
    )

    /**
     * Inserts [row] and trims each over-quota bucket in the fixed order (sender, group, room, global); returns whether any
     * eviction ran and whether [row] survived it. Runs inside the caller's [db] transaction.
     *
     * A bucket over its quota loses rows rather than refusing the new one, and the quotas apply to our own sends
     * (ORIGIN_SELF) too (ADR 006). The old scheme (refuse-when-full, and never cap our own outbox) let an originator
     * that authored more than a quota's worth of custodial frames hold rows a capped carrier could never accept, so
     * the cue-plane digests never matched and the mesh churned NDPs forever — observed with a 117-frame sender against
     * the 100 per-sender quota.
     *
     * The sender, group and room trims keep their newest-N by the frame-global `(sentAt, id)` and never read [carry]. The
     * global cap evicts one row at a time from the sender holding the most, recounting after each ([evictAtCap], ADR
     * 2026-10.7mqm): five free identities stamping a thousand frames inside the future window used to evict every honest
     * frame on every node, and every honest frame for the next five minutes in the write that stored it. The room and
     * group trims stay newest-N on purpose: every shipped build and knit-ios trim that way, and a busy room sitting at its
     * quota would otherwise split an old node from a new one for good.
     *
     * A just-stored frame the rules put outside what is kept is evicted here too. Below the cap, with one quota binding,
     * that makes the kept set the same whatever the arrival order. It is not a law beyond that: with two bounds binding
     * at once the order of the trims already let arrival order split two stores (#349), and at the cap the choice now
     * reads this phone's carry ledger ([source] counts as a holder of [row] there). Counts and evictions are
     * live-filtered (`expiresAt >= now`): buckets mix TTL classes, so an expired-unswept row can be newer by sentAt than a
     * live one — counting it would evict a live frame on the unswept node only, and which rows are evicted would then
     * depend on the local sweep phase (work item #8).
     */
    private suspend fun insertAndTrim(
        row: ForwardEntity,
        isBroadcastChat: Boolean,
        now: Long,
        source: String?,
    ): Trimmed {
        dao.insert(row)
        val senderId = row.senderId
        val groupId = row.groupId
        var evicted = trim(dao.countBySender(senderId, now) - maxPerSender) { dao.evictOldestBySender(senderId, it, now) }
        if (groupId != null) {
            evicted = trim(dao.countByGroup(groupId, now) - maxPerGroup) { dao.evictOldestByGroup(groupId, it, now) } || evicted
        }
        if (isBroadcastChat) {
            evicted = trim(dao.countBroadcast(now) - maxBroadcast) { dao.evictOldestBroadcast(it, now) } || evicted
        }
        evicted = trim(dao.count(now) - maxRows) { over -> evictAtCap(over, row.id, source, now) } || evicted
        return Trimmed(evicted, kept = !evicted || dao.exists(row.id))
    }

    /**
     * Evicts [over] rows for the global cap, one at a time, recounting the buckets after each: among the rows of the
     * sender buckets tied for the most live rows ([ForwardDao.largestBucketRows], key 1), the first by spread descending,
     * `sentAt` ascending and id bytewise ([CapEviction], keys 2 to 4; the custody-carry-policy contract's §5). [carry] is
     * read once for the whole write, and only here, so a write under the cap never reads it; its snapshot already counts
     * [source] as a holder of [newcomer]. The newcomer is a candidate like any row, so a frame can still be evicted by its
     * own write.
     */
    private suspend fun evictAtCap(
        over: Int,
        newcomer: String,
        source: String?,
        now: Long,
    ) {
        var snapshot: CarrySnapshot? = null
        repeat(over) {
            val seen = snapshot ?: carry.snapshot(newcomer, source).also { taken -> snapshot = taken }
            val id = CapEviction.choose(dao.largestBucketRows(now), seen) ?: return
            dao.delete(id)
        }
    }

    /** Evicts [over] rows via [evict] when a bucket exceeds its quota; returns whether anything was removed. */
    private suspend fun trim(
        over: Int,
        evict: suspend (Int) -> Unit,
    ): Boolean {
        if (over <= 0) return false
        evict(over)
        return true
    }

    override suspend fun liveFrames(now: Long): List<CarriedFrame> =
        dao.liveRows(now).mapNotNull { row ->
            WireCodec.decodeEnvelope(row.signed)?.let { CarriedFrame(it, row.sig, row.signed) }
        }

    override suspend fun liveIds(now: Long): List<String> = dao.liveIds(now)

    override suspend fun liveFramesByIds(
        ids: Collection<String>,
        now: Long,
    ): List<CarriedFrame> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            dao.liveRowsByIds(ids.toList(), now).mapNotNull { row ->
                WireCodec.decodeEnvelope(row.signed)?.let { CarriedFrame(it, row.sig, row.signed) }
            }
        }

    override suspend fun liveGroupChatFrames(
        excludingSender: String,
        now: Long,
    ): List<CarriedFrame> =
        dao.liveGroupChatRows(excludingSender, now).mapNotNull { row ->
            WireCodec.decodeEnvelope(row.signed)?.let { CarriedFrame(it, row.sig, row.signed) }
        }

    override suspend fun attachmentsNeedingFetch(now: Long): Map<String, String> =
        dao.attachmentsNeedingFetch(now).associate {
            it.hash to
                it.senderId
        }

    /**
     * A group frame's roster and a room post's declared size are read off the frame's own signed bytes: what says a
     * group frame is ours before any group row does (ADR 2026-10.js2p), and what says a room post is large before its
     * row exists (ADR 2026-10.hmfe). Only a cleartext post's size is read: an encrypted frame's rides sealed.
     */
    override suspend fun carriedAttachments(now: Long): List<CarriedAttachment> = dao.carriedAttachments(now).map(::carried)

    override suspend fun carriedAttachmentsNaming(
        hash: String,
        now: Long,
    ): List<CarriedAttachment> = dao.carriedAttachmentsNaming(hash, now).map(::carried)

    /** One of [ForwardDao.carriedAttachments]' rows as the re-arm reads it: its roster and declared size off its bytes. */
    private fun carried(row: CarriedAttachmentRow): CarriedAttachment {
        // Only a group frame's and a room post's bytes are loaded (the query's CASE): a DM names neither.
        val env = row.signed?.let(WireCodec::decodeEnvelope)
        val roster = env?.group?.members?.toSet()
        val departed = env?.group?.departed?.toSet()
        val declaredSize =
            env
                ?.takeIf { e -> e.type == FrameType.CHAT && e.recipientId == null && e.group == null }
                ?.let { e -> WireCodec.decodeChecked<ChatContent>(e.payload) }
                ?.takeIf { c -> c.enc == null }
                ?.attachmentSize
                ?.takeIf { size -> size > 0 }
        return CarriedAttachment(
            row.hash,
            row.recipientId,
            row.receivedAt,
            row.senderId,
            row.groupId,
            roster = roster,
            declaredSize = declaredSize,
            sentAt = row.sentAt,
            rowCovered = row.rowCovered,
            departed = departed,
        )
    }

    override suspend fun recipientOf(id: String): String? = dao.recipientOf(id)

    /**
     * How many live chat frames this phone holds for other people (see [ForwardDao.observeCarriedForOthers]).
     * On the concrete class, not the [ForwardStore] seam: it is a UI read, and the seam's fakes model custody
     * semantics, not this projection.
     */
    fun observeCarriedForOthers(
        me: String,
        now: Long,
    ): Flow<Int> = dao.observeCarriedForOthers(me, now)

    override suspend fun has(id: String): Boolean = dao.exists(id)

    override suspend fun frame(
        id: String,
        now: Long,
    ): CarriedFrame? =
        dao.liveRow(id, now)?.let { row ->
            WireCodec.decodeEnvelope(row.signed)?.let { CarriedFrame(it, row.sig, row.signed) }
        }

    override suspend fun remove(id: String) {
        // Single delete needs no transaction, but the mutex keeps the digest.remove atomic with it and serial
        // against a concurrent store/sweep (see [mutex]).
        mutex.withLock {
            dao.delete(id)
            digest.remove(id)
            carry.onLeft(id)
        }
    }

    override suspend fun sweepExpired(now: Long): Int =
        mutex.withLock {
            val expired = dao.deleteExpired(now)
            // Runs at startup, periodically, and on heartbeat — also our reliable point to (re)sync the digest to
            // the table (initialises it after a restart, and reconciles any drift from eviction). The expiry delete is
            // digest-neutral by construction: every row it reclaims was already outside the live fold, so it is pure
            // storage GC and its per-node tick phase can no longer open a divergence window (work item #8).
            //
            // The oversize delete (#227) is the one exception to that: it removes LIVE rows, the ones a build without the
            // byte cap stored, so it moves the live set. It is a function of the row alone, so every upgraded node deletes
            // the same rows, and once the pre-upgrade rows are gone it finds nothing; the rebuild below folds the result.
            // Until a node's first sweep after the upgrade an old over-cap row still counts toward the quotas, so a store
            // in that window can evict one frame an already-swept peer keeps; that peer re-serves it, so nothing is
            // stranded. The live, quota, digest and replay reads stay unfiltered by length on purpose.
            val oversize = dao.deleteLongerThan(maxSignedBytes)
            if (oversize > 0) Log.w(TAG, "custody swept $oversize rows over the cap of $maxSignedBytes signed bytes")
            // The profile cap's rows (#234) go the same way, for the same reasons: live, a function of the row alone, and
            // found only until a node's first sweep after the upgrade.
            val profiles = dao.deleteTypeLongerThan(FrameType.PROFILE, maxProfileSignedBytes)
            if (profiles > 0) Log.w(TAG, "custody swept $profiles profiles over the cap of $maxProfileSignedBytes signed bytes")
            rebuildDigest(now)
            expired + oversize + profiles
        }

    /**
     * Re-syncs the digest to the table's live `(id, expiresAt)` set — the only rebuild source — and tells [carry] the
     * same set, which is how a row the trims or the sweep removed leaves the ledger without the trims' SQL naming it.
     */
    private suspend fun rebuildDigest(now: Long) {
        val live = dao.liveIdExpiries(now)
        digest.setMessages(live.map { it.id to it.expiresAt }, now)
        carry.retainOnly(live.mapTo(HashSet(live.size)) { it.id })
    }

    // Internal rather than private: the spool plane's attachment deferral is bounded by how long a frame
    // stays in custody to drive a later push (`AttachmentDeferPolicy`), so that number has to be derived
    // from this one instead of restated next to it.
    internal companion object {
        /** Carry a DM/group message for at most 24h before the TTL sweep reclaims it. */
        const val DEFAULT_TTL_MS = 24 * 60 * 60_000L

        /** Carry a broadcast-room message for a shorter window (ambient, higher-volume, no ack to purge it). */
        const val DEFAULT_BROADCAST_TTL_MS = 6 * 60 * 60_000L

        /** Cap total carried frames; past it the largest sender bucket loses a frame ([evictAtCap], ADR 2026-10.7mqm). */
        const val DEFAULT_MAX_ROWS = 1_000

        /**
         * Cap one carried frame's `signed` bytes; custody refuses a longer one (#227). The largest honest frame
         * measures 15,335 bytes of `signed` (an eight-member group frame at every clamp), and the one outlier, a room
         * post binding as many mentions as 2,000 units hold, 56,144 (`CustodyHeadroomLabTest`, ADR 2026-10.mqzv). It
         * sits below the spool's ceiling (`maxBlob` 64 KiB, and a sealed frame blob is `signed + 93`,
         * so a spool carries `signed` up to 65,443: `ScopeCrypto`, `ScopeRegistry`), so custody admits nothing the
         * Internet plane couldn't push. Worst case [DEFAULT_MAX_ROWS] × 60 KiB ≈ 60 MiB, down from ~500 MB. Identical
         * on every node and in knit-ios (`vectors/custody-v1.json`).
         */
        const val DEFAULT_MAX_SIGNED_BYTES = 61_440

        /**
         * Cap a `profile` frame's `signed` bytes (#234): the door refuses a longer one before it is decoded, and custody
         * refuses it after the byte cap. The largest honest profile measures 1,017 bytes of `signed` (949 on knit-ios,
         * `CustodyHeadroomLabTest`), so 8 KiB is about 8× headroom; it equals `FastFrameCodec.MAX_INFLATED_BYTES` and
         * `FrameTranscoder.MAX_BYTES`, so a profile over it could not cross the fast or LoRa planes anyway. Identical on
         * every node and in knit-ios (`vectors/custody-v1.json`, `limits.maxProfileSignedBytes`).
         */
        const val DEFAULT_MAX_PROFILE_SIGNED_BYTES = 8_192

        /** Cap carried frames per sender, so one identity can't monopolize the buffer. */
        const val DEFAULT_MAX_PER_SENDER = 200

        /** Cap carried frames per group, so one busy group can't monopolize the buffer. */
        const val DEFAULT_MAX_PER_GROUP = 200

        /** Cap carried broadcast-room frames, so ambient chatter can't crowd out addressed messages. */
        const val DEFAULT_MAX_BROADCAST = 200

        private const val TAG = "ForwardRepository"

        // How much of a frame or node id a log line names: enough to find it in a dump, no more.
        private const val ID_CHARS = 8

        /**
         * Whether custody carries a frame naming [group] from [senderId] (ADR 2026-10.7mqm): its members and departed
         * together derive to the group's id ([Conversations.groupIdFor], the founding roster), and the sender is one of
         * the members. Every group frame a current build or knit-ios sends passes: each builds the roster from a row
         * that only ever came from a roster that derived. A pre-2.3.0 frame after a departure does not, since it lists
         * no `departed`; it is still delivered and relayed, and only custody lets it go.
         */
        fun carriesGroup(
            group: GroupInfo,
            senderId: String,
        ): Boolean = senderId in group.members && Conversations.groupIdFor(group.members + group.departed.orEmpty()) == group.id
    }
}
