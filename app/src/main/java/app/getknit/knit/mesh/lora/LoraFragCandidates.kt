package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.link.FastFrameCodec
import java.security.SecureRandom

/**
 * The reassembler of a plane whose channel packets name no sender, on a channel key anyone holds (MeshCore: `KnitChannel.PSK`
 * is public and `CHANNEL_DATA_RECV` names no sender; ADR 2026-11.gsqu's Stage 5c-1 amendment, the security design pass's
 * B2, lora-reviewer finding 5, the maintainer's decisions 7 and "bind both ways" of 2026-11-14). It reads the bound
 * two-part form ([FastFrameCodec.TAG_BOUND_FRAG]) alone: each part carries the first 8 bytes of SHA-256 over the other's
 * slice, so a head and a tail pair only when each names the other. A forger who heard one honest part cannot make a part
 * the honest other one names (a 64-bit second preimage), so a pairing is the honest pair or a forger's own two parts, and
 * one verification runs a matched pair.
 *
 * **Arrival order is not trusted.** Behind a MeshCore repeater a forger's short part routinely lands before the honest
 * one: the repeater holds a packet a random `0..5·t` (`t` = its airtime × `tx_delay_factor`, up to 2.0) before re-airing it
 * (`simple_repeater/MyMesh.cpp:547`, `:893`). So a fake never takes a check or a pairing, only memory:
 * - an entry is keyed on `(fragId, count)`; the sender draws each id fresh;
 * - each side (heads and tails waiting for their other half) keeps up to [perPartFor] candidates: as many as this
 *   receiver can decode within the whole [timeoutMs] at the shortest bound part's airtime. The board decodes one packet at
 *   a time, so within the window no radio input, from one forger or many, fills a side, however long the firmware held the
 *   honest part — its score delay (`calcRxDelay`, up to 32 s at a high `rx_delay_base`), CAD retries under a busy channel
 *   or the 50 % tx budget, and every further hop's `0..5·tx_delay_factor` airtimes are why a repeat-hold window was the
 *   wrong measure. Past the cap (a store the window has not yet aged) a random waiting one is replaced ([Drop.REPLACED],
 *   the source [pick] injectable), so a flood of fakes cannot predict which slot it frees;
 * - a pairing is answered at once as a [Combination] and leaves the entry, so a pair that fails its verification (a
 *   stranger's, a forger's own two parts) never lingers, and two authors under one colliding id each pair their own;
 * - every candidate ages out on its own arrival ([Drop.TIMEOUT]), never on its entry's first;
 * - the store holds at most [capacityFor] entries (one radio cannot open more in the window) and [MAX_BYTES] of slices,
 *   evicting the oldest entry past either ([Drop.OVERFLOW]).
 *
 * Pure, no Android; `@Synchronized` at every entry point, and nothing called out while the monitor is held.
 */
internal class LoraFragCandidates(
    private val now: () -> Long,
    private val timeoutMs: Long,
    /** A uniform index below its argument: which waiting candidate a full side replaces. `SecureRandom` by default. */
    private val pick: (Int) -> Int = { RANDOM.nextInt(it) },
    private val onDrop: (Drop) -> Unit = {},
) {
    /** Why a candidate or an entry was let go: each is its own `FastPathDrop`. */
    enum class Drop {
        /** A candidate not paired inside [timeoutMs] of its own arrival. */
        TIMEOUT,

        /** The oldest entry, evicted for a new one past the capacity or the byte budget. */
        OVERFLOW,

        /** A waiting candidate replaced at random by a newcomer on a side already at [perPartFor]. */
        REPLACED,
    }

    /** One pairing an arrival completed: the joined [frame]. */
    class Combination internal constructor(
        val frame: ByteArray,
    )

    /** A part waiting for its other half: its own [hash], the [names] hash of the other, its [slice]. */
    private class Waiting(
        val at: Long,
        val hash: ByteArray,
        val names: ByteArray,
        val slice: ByteArray,
    )

    private class Entry {
        val heads = mutableListOf<Waiting>()
        val tails = mutableListOf<Waiting>()

        fun isEmpty(): Boolean = heads.isEmpty() && tails.isEmpty()

        fun bytes(): Int = heads.sumOf { it.slice.size } + tails.sumOf { it.slice.size }
    }

    // Insertion-ordered, so the oldest entry is the first key.
    private val inFlight = LinkedHashMap<Pair<Int, Int>, Entry>()
    private var bytes = 0

    /**
     * Absorbs one bound [frag] and answers the pairing it completes, if any. [capacity] ([capacityFor]) and [perPart]
     * ([perPartFor]) are the caller's at the board's preset now.
     */
    @Synchronized
    fun accept(
        frag: FastFrameCodec.Fragment,
        capacity: Int,
        perPart: Int,
    ): List<Combination> {
        sweep()
        val names = frag.commitment ?: return emptyList()
        val key = frag.fragId to frag.count
        val hash = FastFrameCodec.commitmentOf(frag.payload)
        val head = frag.part == 0
        val held = inFlight[key]
        if (held != null) {
            pair(key, held, head, hash, names, frag.payload)?.let { return listOf(it) }
            val mine = if (head) held.heads else held.tails
            if (mine.any { it.hash.contentEquals(hash) && it.names.contentEquals(names) }) return emptyList() // heard again
        }
        val entry = held ?: open(key, capacity)
        val side = if (head) entry.heads else entry.tails
        if (side.size >= perPart.coerceAtLeast(1)) {
            val out = side.removeAt(pick(side.size))
            bytes -= out.slice.size
            onDrop(Drop.REPLACED)
        }
        side += Waiting(now(), hash, names, frag.payload)
        bytes += frag.payload.size
        while (bytes > MAX_BYTES && inFlight.size > 1) evictOldest(sparing = key)
        return emptyList()
    }

    /** The one waiting part on the other side of [held] that names this one and is named back, taken out and joined. */
    @Suppress("LongParameterList") // one arrival's facts
    private fun pair(
        key: Pair<Int, Int>,
        held: Entry,
        head: Boolean,
        hash: ByteArray,
        names: ByteArray,
        slice: ByteArray,
    ): Combination? {
        val others = if (head) held.tails else held.heads
        val partner = others.firstOrNull { it.hash.contentEquals(names) && it.names.contentEquals(hash) } ?: return null
        others -= partner
        bytes -= partner.slice.size
        if (held.isEmpty()) inFlight.remove(key)
        return Combination(if (head) slice + partner.slice else partner.slice + slice)
    }

    private fun open(
        key: Pair<Int, Int>,
        capacity: Int,
    ): Entry {
        if (inFlight.size >= capacity.coerceAtLeast(1)) evictOldest()
        return Entry().also { inFlight[key] = it }
    }

    private fun evictOldest(sparing: Pair<Int, Int>? = null) {
        val oldest = inFlight.keys.first { it != sparing }
        bytes -= checkNotNull(inFlight.remove(oldest)).bytes()
        onDrop(Drop.OVERFLOW)
    }

    /** Forgets everything (the board lost, the plane stopped). */
    @Synchronized
    fun clear() {
        inFlight.clear()
        bytes = 0
    }

    /** Entries in flight, for the JVM tests. */
    @Synchronized
    internal fun size(): Int = inFlight.size

    /** Ages every candidate out on its own arrival; an entry left with none goes. */
    private fun sweep() {
        val cutoff = now() - timeoutMs
        val it = inFlight.values.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            for (side in listOf(entry.heads, entry.tails)) {
                val old = side.filter { w -> w.at < cutoff }
                side.removeAll(old)
                old.forEach { w ->
                    bytes -= w.slice.size
                    onDrop(Drop.TIMEOUT)
                }
            }
            if (entry.isEmpty()) it.remove()
        }
    }

    companion object {
        private val RANDOM = SecureRandom()

        /** The fewest entries kept, whatever the preset: the design pass's figure for MeshCore's narrow presets. */
        const val MIN_CAPACITY = 64

        /** The most, on the fastest preset. */
        const val MAX_CAPACITY = 2_048

        /**
         * The slices the whole store holds at most: 256 KiB. One radio airs at most about 141 B a second of slice at the
         * companion default (a full 164-B packet in about 1.16 s), so about 8.5 KB in the 60-s window — this budget is some
         * thirty radios' worth, and bounds what `MAX_CAPACITY` entries × two sides × [perPartFor] × 152 B would allow.
         */
        const val MAX_BYTES = 256 * 1024

        /**
         * The entries one radio cannot open faster than [timeoutMs] lets them age out: the timeout over the shortest bound
         * packet's airtime ([shortestAirMs]), one more for the honest entry, between [MIN_CAPACITY] and [MAX_CAPACITY].
         */
        fun capacityFor(
            timeoutMs: Long,
            shortestAirMs: Long,
        ): Int {
            val opened = (timeoutMs + shortestAirMs.coerceAtLeast(1) - 1) / shortestAirMs.coerceAtLeast(1)
            return (opened + 1).coerceIn(MIN_CAPACITY.toLong(), MAX_CAPACITY.toLong()).toInt()
        }

        /**
         * The candidates one side of an entry keeps: what one receiver can decode within [timeoutMs] at the shortest bound
         * part's airtime ([shortestAirMs]), plus one for the honest part — [capacityFor]'s figure, 177 at the companion
         * default (SF8 / 62.5 kHz: 60 s ÷ 341 ms, + 1). Within the window no radio input can then trigger a replacement;
         * memory stays bounded by [MAX_BYTES] and the receive rate (the 5c-1 round-3 reviews).
         */
        fun perPartFor(
            timeoutMs: Long,
            shortestAirMs: Long,
        ): Int = capacityFor(timeoutMs, shortestAirMs)

        /** The shortest bound part a receiver reads ([FastFrameCodec.parseBoundFragment]): header, commitment, one byte. */
        const val SHORTEST_FRAGMENT_BYTES = FastFrameCodec.FRAG_HEADER_BYTES + FastFrameCodec.COMMITMENT_BYTES + 1
    }
}
