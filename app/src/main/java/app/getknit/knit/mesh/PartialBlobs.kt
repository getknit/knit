package app.getknit.knit.mesh

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * The prefixes of attachments a link drop cut off, kept so the next ask can resume where the bytes stopped
 * instead of at byte 0 (work item #116, ADR 2026-10.wtyc). A link at the edge of Bluetooth or Coded range can
 * drop every few minutes; when every answer restarted from nothing, a large attachment could fail forever while
 * both phones paid for the airtime.
 *
 * A link's `FileIntake` hands its temp file here when the link is cut mid-stream ([keep]); `BlobExchange` asks
 * with the kept length as the `blobreq` offset ([length]); a holder that understands it streams the tail, and the
 * next intake splices that tail onto a copy of the prefix ([open]). Bytes for a hash are the same at every
 * holder, so any holder can finish what another started, and the whole file is still checked against its hash
 * before it is stored (`MeshBlobStore.saveIncoming`) — a bad splice costs one tail, never a wrong blob.
 *
 * - **Session-scoped.** [purge] runs at every mesh start beside `MeshBlobStore.clearTransfers`: a room photo, an
 *   avatar or a group photo crosses the link as plaintext, and the blob layer keeps no plaintext on disk past
 *   the transfer it belongs to. A resumed transfer is one transfer spread over several links, so a partial
 *   lives no longer than that: until the blob lands, [ttlMs] after its last progress, or the session ends.
 * - **Immutable once kept.** A longer prefix replaces a kept one by rename, and a drop or eviction deletes it,
 *   so a reader holding an [open] stream reads a file nothing writes to: the copy runs outside [lock] without
 *   a link's `close()` ever waiting behind another link's 8 MiB copy.
 * - **Keep-the-longer.** A whole-file stream never discards a kept prefix: a holder that ignores the offset (an
 *   older build, the iOS port before its companion change) streams from 0, and a short cut of that stream must
 *   not cost an 80 % prefix someone else delivered.
 * - **Epochs.** [drop] retires every copy built on the dropped prefix: a resumed copy still streaming when its
 *   sibling failed the hash check would otherwise bring the bad prefix back when it, too, is cut.
 * - **Bounded.** At most [maxCount] partials and [maxBytes] in all, oldest evicted first; a prefix under
 *   [minBytes] is not worth a file. The key is the peer's claim, so only a well-formed content hash ever names a
 *   file here ([isValidBlobHash]), and the name is checked to stay inside [dir].
 *
 * It also carries the two facts a fetch made for a profile photo shares with the links that receive it (#234),
 * because this store is the one object every link's `FileIntake` and `BlobExchange` already hold:
 *
 * - **A per-hash ceiling** ([cap], [capFor]): a want only a profile photo makes is fetched under the photo ceiling
 *   (`FileIntake.MAX_AVATAR_BYTES`), not the attachment one. `BlobExchange` writes it while such a want stands and
 *   clears it when the want ends or an attachment want for the same hash lifts it (the larger ceiling wins); a link
 *   reads it once, at a stream's header. No entry is the attachment ceiling.
 * - **The holders passed over under it** ([markHolder], [markedHolders]): one whose stream passed the per-hash ceiling,
 *   or whose size answer did. Neither claim can be checked against the hash before the whole file is in, so it speaks
 *   for that holder alone: `BlobExchange` asks it for the hash no more this session and goes on to the others, and only
 *   when the holder is the photo's author does it refuse the hash itself ([refuse], [isRefused]) and end the want. One
 *   oversized photo costs at most one copy per holder per session, and one lying neighbour cannot end an honest peer's
 *   photo. Both are purged with the prefixes at each mesh start.
 *
 * Pure of Android, thread-safe, every disk touch under one short-held [lock] except the caller's copy.
 */
class PartialBlobs(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val minBytes: Long = MIN_BYTES,
    private val maxCount: Int = MAX_COUNT,
    private val maxBytes: Long = MAX_BYTES,
    private val ttlMs: Long = TTL_MS,
    private val log: (String) -> Unit = {},
) {
    private class Entry(
        val file: File,
        val length: Long,
        val keptAt: Long,
    )

    /** A kept prefix opened for a resume: its bytes from 0, and the [epoch] a later [keep] must still match. */
    class Prefix(
        val input: InputStream,
        val epoch: Long,
    ) : Closeable by input

    private val lock = Any()

    // hash -> the kept prefix; insertion order is keep order (a replacement is removed and re-put), so the
    // eldest entry is the one evicted first. Guarded by [lock].
    private val entries = LinkedHashMap<String, Entry>()

    // hash -> the epoch of its current prefix lineage. Values come from one counter and are never reused, so a
    // hash whose epoch was evicted from this bounded map reads as no epoch at all — a copy built on it is
    // refused rather than mistaken for current. Guarded by [lock].
    private val epochs =
        object : LinkedHashMap<String, Long>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > MAX_EPOCHS
        }
    private var nextEpoch = 0L

    // hash -> the ceiling a stream of it is kept under, below the attachment one (#234). Written and cleared by
    // `BlobExchange` with the want it belongs to, so it holds at most its wants; capped all the same. Guarded by [lock].
    private val caps =
        object : LinkedHashMap<String, Long>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > MAX_CAPS
        }

    // hash -> the holders passed over under its per-hash ceiling this session ([markHolder]), oldest first. Only a hash a
    // want of ours capped is ever marked, and a holder id is the link's own neighbour, but both are capped all the same:
    // a mark that falls out costs that holder one more copy, never a want. Guarded by [lock].
    private val marked =
        object : LinkedHashMap<String, LinkedHashSet<String>>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashSet<String>>): Boolean = size > MAX_REFUSED
        }

    // Hashes refused as a profile photo this session ([refuse]): their author sent or claimed more than the ceiling, or
    // a held copy was found over it. Capped, oldest first. Guarded by [lock].
    private val refused =
        object : LinkedHashMap<String, Unit>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean = size > MAX_REFUSED
        }

    /**
     * Keeps a stream of [hash] to at most [bytes] (#234), or lifts the per-hash ceiling when null: the attachment
     * ceiling applies. `BlobExchange` alone writes it, with the want it belongs to.
     */
    fun cap(
        hash: String,
        bytes: Long?,
    ) = synchronized(lock) {
        if (bytes == null) caps.remove(hash) else caps[hash] = bytes
    }

    /** The per-hash ceiling on a stream of [hash], or null for the attachment ceiling. A link reads it at the header. */
    fun capFor(hash: String): Long? = synchronized(lock) { caps[hash] }

    /**
     * [holder]'s stream of [hash], or its size answer for it, passed the per-hash ceiling: it is not asked for [hash] under
     * that ceiling again this session. True when the mark is new.
     */
    fun markHolder(
        hash: String,
        holder: String,
    ): Boolean =
        synchronized(lock) {
            val holders = marked.getOrPut(hash) { LinkedHashSet() }
            val added = holders.add(holder)
            if (holders.size > MAX_MARKED_HOLDERS) holders.remove(holders.first())
            if (added) log("partial ${hash.take(LOG_HASH)} holder $holder over its ceiling")
            added
        }

    /** The holders passed over for [hash] under its per-hash ceiling this session ([markHolder]). */
    fun markedHolders(hash: String): Set<String> = synchronized(lock) { marked[hash]?.toSet() ?: emptySet() }

    /** [hash] is refused as a profile photo for the rest of the session: its author passed the ceiling, or a held copy did. */
    fun refuse(hash: String) =
        synchronized(lock) {
            if (refused.put(hash, Unit) == null) log("partial ${hash.take(LOG_HASH)} refused over its ceiling")
        }

    /** Whether [hash] was refused as a profile photo this session ([refuse]). */
    fun isRefused(hash: String): Boolean = synchronized(lock) { refused.containsKey(hash) }

    /** How many bytes of [hash] are kept: the offset to ask a holder for, or 0 when there is nothing to resume. */
    fun length(hash: String): Long = synchronized(lock) { entries[hash]?.length ?: 0L }

    /**
     * The kept prefix of [hash] when it holds at least [offset] bytes, opened at byte 0, or null. The caller copies
     * exactly [offset] bytes out of it (outside this store's lock) and closes it.
     */
    fun open(
        hash: String,
        offset: Long,
    ): Prefix? =
        synchronized(lock) {
            val entry = entries[hash]?.takeIf { offset >= 1 && it.length >= offset } ?: return null
            val input = runCatching { FileInputStream(entry.file) }.getOrNull()
            if (input == null) {
                // The file went from under us (the cache was cleared): forget it, and anything built on it.
                retire(hash)
                return null
            }
            Prefix(input, epochs.getOrPut(hash) { nextEpoch++ })
        }

    /**
     * A link was cut with [file] holding the first bytes of [hash]: keep them if they are the longest prefix held,
     * else delete [file]. [basedOn] is the epoch of the prefix a resumed stream was spliced onto (null for a
     * stream from byte 0): a prefix that was dropped since is never kept again. Takes ownership of [file].
     */
    fun keep(
        hash: String,
        file: File,
        basedOn: Long?,
    ): Boolean {
        val length = file.length()
        val kept =
            synchronized(lock) {
                when {
                    !isValidBlobHash(hash) -> false
                    basedOn != null && epochs[hash] != basedOn -> false.also { log("partial ${hash.take(LOG_HASH)} stale, not kept") }
                    length < minBytes -> false
                    (entries[hash]?.length ?: 0L) >= length -> false
                    else -> install(hash, file, length)
                }
            }
        if (!kept) file.delete()
        return kept
    }

    /**
     * Drops the kept prefix of [hash] and retires its epoch, so no copy still built on it is kept again. True when
     * a prefix was removed. Called when the blob landed (nothing left to resume) and when a spliced file failed
     * its hash (the prefix may be what was wrong).
     */
    fun drop(hash: String): Boolean =
        synchronized(lock) {
            val removed = retire(hash)
            if (removed) log("partial ${hash.take(LOG_HASH)} dropped")
            removed
        }

    /**
     * Drops the prefixes past [ttlMs] and those whose blob [isHeld] now says we hold (it arrived some other way —
     * the spool, a second link). The suspend check runs outside the lock; returns how many were dropped.
     */
    suspend fun sweep(isHeld: suspend (String) -> Boolean): Int {
        val (expired, kept) =
            synchronized(lock) {
                val t = now()
                val stale = entries.filterValues { t - it.keptAt > ttlMs }.keys
                stale.forEach { retire(it) }
                stale.size to entries.keys.toList()
            }
        val held = kept.filter { isHeld(it) }
        synchronized(lock) { held.forEach { retire(it) } }
        return expired + held.size
    }

    /**
     * Deletes every prefix, including a dead session's leftovers on disk, and forgets the ceilings, marks and refusals.
     * Run at each mesh start.
     */
    fun purge() =
        synchronized(lock) {
            entries.clear()
            epochs.clear()
            caps.clear()
            marked.clear()
            refused.clear()
            dir.listFiles()?.forEach { it.delete() }
        }

    // --- Under [lock] ---

    private fun install(
        hash: String,
        file: File,
        length: Long,
    ): Boolean {
        dir.mkdirs()
        val dest = File(dir, "$hash$SUFFIX")
        val inside = dest.canonicalPath.startsWith(dir.canonicalPath + File.separator)
        // rename(2) replaces the held file in one step; a reader with it open keeps reading the old bytes.
        val moved = inside && file.renameTo(dest)
        if (moved) {
            entries.remove(hash)
            entries[hash] = Entry(dest, length, now())
            epochs.getOrPut(hash) { nextEpoch++ }
            log("partial ${hash.take(LOG_HASH)} kept ${length}B")
            evict()
        }
        return moved
    }

    private fun evict() {
        while (entries.size > maxCount || entries.values.sumOf { it.length } > maxBytes) {
            val eldest = entries.entries.first()
            entries.remove(eldest.key)
            eldest.value.file.delete()
            log("partial ${eldest.key.take(LOG_HASH)} evicted")
        }
    }

    /** Removes [hash]'s prefix (if any) and gives its lineage a fresh epoch. True when a prefix was removed. */
    private fun retire(hash: String): Boolean {
        val entry = entries.remove(hash)
        entry?.file?.delete()
        if (entry != null || epochs.containsKey(hash)) epochs[hash] = nextEpoch++
        return entry != null
    }

    companion object {
        // Below this a restart costs little even on Coded's ~1 KiB/s, and the store would churn on near-empty cuts.
        const val MIN_BYTES = 16L * 1024

        // A few attachments cut at once is the realistic worst case; more is a peer filling the store.
        const val MAX_COUNT = 8

        // Four ceiling-sized attachments (the receive ceiling is 8 MiB plus the seal's headroom).
        const val MAX_BYTES = 32L * 1024 * 1024

        // From the last progress. Covers a flapping link and two people walking in and out of range; a carrier
        // back hours later starts over, which is what every transfer did before.
        const val TTL_MS = 60 * 60_000L

        // Epochs only need to outlive the copies in flight on them; a few per held prefix is plenty.
        private const val MAX_EPOCHS = 64

        // Per-hash ceilings: one per want that only a profile photo made, and wants are capped at 256 (BlobExchange).
        private const val MAX_CAPS = 256

        // Refused or marked hashes: each cost a want and a stream or an answer to earn; past this many the oldest may be
        // fetched once more.
        private const val MAX_REFUSED = 256

        // Holders marked for one hash: more than a phone has neighbours over a session's links; past it the oldest may be
        // asked once more.
        private const val MAX_MARKED_HOLDERS = 16
        private const val SUFFIX = ".part"
        private const val LOG_HASH = 12
    }
}
