package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.isValidBlobHash
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.CommonsPost
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.sha256Hex

/**
 * The member-side attachment rules of `docs/SPOOL_PROTOCOL.md` §4.5/§9.5: which attachments a scope
 * carries, how one is cut into deterministically-positioned chunks and put back together, and the
 * presence bitmap a spool answers `ahave` with. Pure like [ScopeFrames] — no Android, no IO, no state
 * beyond the caller-owned [Assembly] — so the whole rule set is unit-testable against fixtures.
 *
 * Two structural properties worth stating up front, because both are load-bearing:
 *
 * - **Attachments are deliberately not part of the scope digest.** Anything a digest folds over must be
 *   bounded by a rule identical on every node, and a per-scope *byte* quota is exactly the sort of
 *   operator-tunable knob that cannot be. Two spools with different attachment budgets would never
 *   converge. This is the same reason `ForwardEntity.attachmentHash` stays out of the mesh's own
 *   `StoreDigest`, and it is why presence is discovered by asking (`ahave`) rather than by anti-entropy.
 * - **Every allocation sized by a peer-supplied `total` is bounded by [MAX_CHUNKS].** A chunk header is
 *   inside the AEAD, so only a scope member can set it — but a member is exactly who a scope must
 *   survive, so the bound is enforced here rather than assumed.
 */
object ScopeAttachments {
    /**
     * The largest attachment the app produces (`AttachmentStore.MAX_BYTES`), and therefore the largest a
     * member will reassemble. Anything bigger is not a Knit attachment.
     */
    const val MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024

    /** Chunks in the largest permitted attachment — 171 at the spec's 48 KiB chunk. */
    val MAX_CHUNKS = chunkCount(MAX_ATTACHMENT_BYTES)

    /**
     * An attachment a scope carries: the frame's cleartext content address (the *ciphertext* hash, the
     * DB v19 precedent), a mime *hint* for storing the bytes locally, and the newest referencing frame's
     * `sentAt` — which is what the §9.2 dead-on-arrival guard is applied to on the push side.
     *
     * [mime] is a hint and is usually null: since ADR 035 a sealed frame carries only the hash, so this is
     * populated only by an older peer's frame (and never by a `groupupdate`). The fetcher resolves the real
     * type from its own decrypted message row when it stores the bytes — see `MeshManager.scopeBlobs` — and
     * falls back to `ScopeSync.FALLBACK_MIME`. Nothing on this plane needs the type to *route* bytes.
     */
    class Ref(
        val aHash: String,
        val mime: String?,
        val sentAt: Long,
    )

    /** Chunks needed to carry [size] bytes. */
    fun chunkCount(size: Int): Int = (size + ScopeCrypto.ATTACH_CHUNK_BYTES - 1) / ScopeCrypto.ATTACH_CHUNK_BYTES

    /**
     * A content address in its byte form. The mesh carries an attachment hash as 64 lowercase hex
     * characters ([isValidBlobHash]); [ScopeCrypto.attachmentId] needs the 32 raw bytes. Null for
     * anything that is not a well-formed address, so a malformed peer-supplied field is a skip rather
     * than a throw on the inbound path.
     */
    fun hashBytes(aHash: String): ByteArray? {
        if (!isValidBlobHash(aHash)) return null
        return ByteArray(aHash.length / 2) { aHash.substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }
    }

    /** The [index]th slice of [bytes]; short only for the final chunk. */
    fun sliceAt(
        bytes: ByteArray,
        index: Int,
    ): ByteArray {
        val from = index * ScopeCrypto.ATTACH_CHUNK_BYTES
        require(index >= 0 && from < bytes.size) { "chunk $index outside a ${bytes.size}-byte attachment" }
        return bytes.copyOfRange(from, minOf(from + ScopeCrypto.ATTACH_CHUNK_BYTES, bytes.size))
    }

    /**
     * The attachments [frames] carry for [scope]: the §4.4 frame-set rule first (a scope is not a
     * general-purpose upload channel, and that applies to bytes as much as to frames), then the
     * cleartext `ChatContent.attachmentHash` that E2E frames have carried since DB v19 so a carrier
     * blind to the sealed content can still see the blob.
     *
     * Deduped by hash — the same image re-sent is a different ciphertext and so a different hash, but a
     * re-served frame is not — keeping the newest `sentAt` and the first mime seen.
     */
    fun references(
        frames: List<CarriedFrame>,
        scope: Scope,
        selfId: String,
    ): List<Ref> {
        val byHash = LinkedHashMap<String, Ref>()
        for (frame in frames) {
            val found = refFor(frame, scope, selfId) ?: continue
            val existing = byHash[found.aHash]
            byHash[found.aHash] =
                Ref(
                    aHash = found.aHash,
                    mime = existing?.mime ?: found.mime,
                    sentAt = maxOf(found.sentAt, existing?.sentAt ?: Long.MIN_VALUE),
                )
        }
        return byHash.values.toList()
    }

    /**
     * The image one frame references for [scope], or null if it names none this scope may carry. Two
     * shapes qualify, and where the hash lives differs per type exactly as the frame-set rule's group id
     * does:
     *
     * - **`chat`** — `ChatContent.attachmentHash`. That covers message attachments and, since it is also
     *   set on a sealed `CTL_PROFILE` frame, peer **avatars**: the cleartext hint of the DB v19
     *   precedent means this path needs no special case for them. Its `attachmentMime` is read only as a
     *   hint for an older peer's frame — since ADR 035 a sealed frame sets the hash and nothing else.
     * - **`groupupdate`** — `GroupInfo.photoHash`, the group's own picture. A groupupdate is already
     *   scope-eligible (§4.4), so its photo is legitimately scope content; only the bytes were missing.
     * - **`commons`** — the post's `ChatContent.attachmentHash` and its type, cleartext inside the room's seal
     *   (§7.4). The frames a commons round reads are our own outbox, so this is only ever an attachment to push.
     *
     * `groupleave` names no image, and everything else fails the frame-set rule first.
     */
    private fun refFor(
        frame: CarriedFrame,
        scope: Scope,
        selfId: String,
    ): Ref? {
        val env = frame.envelope
        if (!ScopeFrames.eligibleFor(env, selfId, scope)) return null
        val named =
            when (env.type) {
                FrameType.CHAT -> WireCodec.decodePayload<ChatContent>(env.payload)?.let { it.attachmentHash to it.attachmentMime }

                // GroupInfo carries no mime — and since ADR 035 neither does a sealed chat frame. A group
                // photo is JPEG like an avatar, and the fetcher's local lookup plus fallback cover it either
                // way.
                FrameType.GROUP_UPDATE -> env.group?.photoHash to null

                FrameType.COMMONS -> WireCodec.decodePayload<CommonsPost>(env.payload)?.chat?.let { it.attachmentHash to it.attachmentMime }

                else -> null
            } ?: return null
        val hash = named.first?.takeIf { isValidBlobHash(it) } ?: return null
        return Ref(aHash = hash, mime = named.second, sentAt = env.sentAt)
    }

    /** The presence bitmap for [present] over [total] chunks: chunk *i* is bit *i % 8* (MSB-first) of byte *i / 8*. */
    fun bitmap(
        present: Set<Int>,
        total: Int,
    ): ByteArray {
        require(total >= 1) { "total must be positive" }
        val out = ByteArray((total + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS)
        for (index in present) {
            if (index in 0 until total) {
                out[index / Byte.SIZE_BITS] = (out[index / Byte.SIZE_BITS].toInt() or mask(index)).toByte()
            }
        }
        return out
    }

    /** Whether [bits] marks chunk [index] present. Out-of-range indices read as absent, never throw. */
    fun bitSet(
        bits: ByteArray,
        index: Int,
    ): Boolean {
        if (index < 0) return false
        val byte = index / Byte.SIZE_BITS
        return byte < bits.size && (bits[byte].toInt() and mask(index)) != 0
    }

    /** The indices in `0 until total` that [bits] does not mark present — what a fetcher still needs. */
    fun missing(
        bits: ByteArray,
        total: Int,
    ): List<Int> = (0 until total).filter { !bitSet(bits, it) }

    /**
     * Groups ascending [indices] into contiguous runs, each at most [maxRun] long — the `(from, n)`
     * windows an `aget` can ask for. Contiguity matters: `aget` addresses a range, so asking across a
     * gap would drag back chunks already in hand.
     */
    fun windows(
        indices: List<Int>,
        maxRun: Int,
    ): List<IntRange> {
        require(maxRun >= 1) { "maxRun must be positive" }
        val out = mutableListOf<IntRange>()
        var start: Int? = null
        var previous = 0
        for (index in indices) {
            val open = start
            if (open == null) {
                start = index
            } else if (index != previous + 1 || index - open >= maxRun) {
                out.add(open..previous)
                start = index
            }
            previous = index
        }
        start?.let { out.add(it..previous) }
        return out
    }

    private fun mask(index: Int): Int = HIGH_BIT ushr (index % Byte.SIZE_BITS)

    private const val HEX_RADIX = 16

    /** Bit 0 of a bitmap byte is its most significant one — the wire order §7.3 pins. */
    private const val HIGH_BIT = 0x80

    /**
     * A partially-received attachment, held in memory by whoever is fetching it. Deliberately **not**
     * persisted: the blob-id set of this plane is derived rather than stored (ADR 019's M3 decision 2)
     * and a partial-chunk table would be the first thing to break that, for a download that a spool's
     * presence bitmap makes cheap to restart. The cost is honest — a process death mid-transfer refetches
     * that attachment — and it is bounded by [MAX_CHUNKS] either way.
     */
    class Assembly(
        val aHash: String,
        val total: Int,
    ) {
        init {
            require(total in 1..MAX_CHUNKS) { "total $total outside 1..$MAX_CHUNKS" }
        }

        private val chunks = arrayOfNulls<ByteArray>(total)

        /** Bytes buffered so far, for the fetcher's in-flight budget. */
        var bytes: Int = 0
            private set

        /** The indices already in hand. */
        val held: Set<Int> get() = chunks.indices.filterTo(mutableSetOf()) { chunks[it] != null }

        fun isComplete(): Boolean = chunks.all { it != null }

        /**
         * Stores [data] at [index]. Returns false — and changes nothing — for an out-of-range or
         * already-held index, an empty or oversize chunk, or a **short interior chunk**: only the final
         * chunk may be short, and admitting a short one anywhere else would silently shift every later
         * offset while still reassembling to a plausible-looking buffer.
         */
        fun put(
            index: Int,
            data: ByteArray,
        ): Boolean {
            if (index !in 0 until total || chunks[index] != null) return false
            if (data.isEmpty() || data.size > ScopeCrypto.ATTACH_CHUNK_BYTES) return false
            if (index < total - 1 && data.size != ScopeCrypto.ATTACH_CHUNK_BYTES) return false
            chunks[index] = data
            bytes += data.size
            return true
        }

        /**
         * The reassembled attachment ciphertext, or null while incomplete or if the bytes do not hash to
         * [aHash]. That final content-address check is the one that matters: the chunk seals prove a
         * member produced each piece, and this proves the pieces are the attachment the frame named.
         */
        fun finish(): ByteArray? {
            if (!isComplete()) return null
            val out = ByteArray(bytes)
            var at = 0
            for (chunk in chunks) {
                val piece = chunk ?: return null
                piece.copyInto(out, at)
                at += piece.size
            }
            return if (sha256Hex(out) == aHash) out else null
        }
    }
}
