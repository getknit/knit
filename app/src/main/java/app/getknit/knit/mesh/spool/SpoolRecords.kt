package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * The client↔spool record layer (docs/SPOOL_PROTOCOL.md §7): one CBOR record per WebSocket binary
 * message, multiplexed over a single WSS connection per spool. This is deliberately *not* mesh wire —
 * no `WireEnvelope`, no frame signature; everything a spool stores or serves is an opaque sealed blob
 * (`ScopeCrypto`), and these records only move blobs, digests, and bounds around. Evolution follows
 * the house wire discipline restated in the spec: additive-only fields (nullable/defaulted), a plain
 * string discriminator [SpoolRecordHead.t] (unknown `t` is skipped, unknown fields are ignored), and
 * an append-only error-code registry. API-only until the reference spool (M2) and `ScopeSync` (M3).
 *
 * Ids ride raw: [SpoolCodec] sets `alwaysUseByteString`, so every `ByteArray` — including list
 * elements, which the per-property `@ByteString` annotation cannot reach — encodes as a CBOR byte
 * string. Scope ids and blob ids are 32 bytes, digests 8 bytes big-endian (`ScopeCrypto.digestBytes`).
 */
object SpoolRecordType {
    const val HELLO = "hello"
    const val SUB = "sub"
    const val DIGEST = "digest"
    const val LIST = "list"
    const val PULL = "pull"
    const val BLOB = "blob"
    const val PUSH = "push"
    const val EVENT = "event"
    const val OK = "ok"
    const val ERR = "err"

    // The attachment family (§7.3). A spool that does not advertise the attachment limits never sees
    // these, because it would skip them silently and leave the client's `q` hanging until timeout.
    const val AHAVE = "ahave"
    const val AHAS = "ahas"
    const val AGET = "aget"
    const val ACHUNK = "achunk"
    const val APUT = "aput"

    // TURN provisioning (experimental; docs/VOICE_CALLS.md §2, proposed spec §7.6). Sent only to a spool whose
    // HELLO carried `turn` (C-7.6-3), for the attachment family's reason.
    const val TURNREQ = "turnreq"
    const val TURNCRED = "turncred"

    // Entitlement tickets (experimental; docs/VOICE_CALLS.md §2.6). Sent only to a spool whose HELLO carried `ent`,
    // and only a ticket whose `aud` is that spool's host.
    const val ENT = "ent"

    // Casts (experimental; docs/VOICE_CALLS.md §2.7, proposed spec §7.7): one name for both directions. Sent only to
    // a spool whose HELLO carried `cast`, and never answered.
    const val CAST = "cast"
}

/** Error codes a spool may return; the registry is append-only (unknown codes are terminal-generic). */
object SpoolErrCode {
    const val VERSION = "version"
    const val POW = "pow"
    const val TOMBSTONED = "tombstoned"
    const val QUOTA = "quota"
    const val TOO_LARGE = "too_large"
    const val BAD_ID = "bad_id"
    const val RATE = "rate"
    const val NOT_SUBSCRIBED = "not_subscribed"
    const val MALFORMED = "malformed"
    const val INTERNAL = "internal"

    /** An APUT whose `cid` differs from the chunk already stored at that position (§7.3, first write wins). */
    const val CONFLICT = "conflict"

    /**
     * An `ent` the spool will not accept, or a gated verb on a connection without the live ticket its gate needs
     * (experimental, docs/VOICE_CALLS.md §2.6): a `turnreq` under bit 0 of `entGates`, an `aput` under bit 1.
     * `msg` carries one word: `malformed`, `key`, `signature`, `audience`, `early` or `expired` for an `ent`;
     * `missing`, `expired` or `flags` for a `turnreq`; those three, `size` (the attachment's `total` is over the ticket's
     * `am`, S-7.6-31) or `budget` (the ticket's pseudonym has spent its day's uploads here) for an `aput`, which carries
     * the record's `q` and `scope`. Never a strike, and never the answer to a SUB.
     */
    const val TICKET = "ticket"
}

/** WebSocket close codes for failures that happen before or outside the record layer. */
object SpoolCloseCode {
    /** The ordinary WebSocket close — the only non-protocol code OkHttp lets a client send. */
    const val NORMAL = 1000
    const val MALFORMED = 4000
    const val AUTH = 4001
    const val VERSION = 4002
    const val ABUSE = 4003
}

/** Record-layer protocol version negotiated in HELLO. */
const val SPOOL_RECORD_VERSION = 1

/**
 * The largest inbound record this client will even materialize, applied at the socket before decode.
 *
 * A spool is untrusted storage, and every *protocol* size cap it offers is one it advertised itself in
 * HELLO — so the outermost bound has to be a constant we own. Derived rather than picked: the largest
 * record a conforming spool can send is a `blob` carrying one sealed frame at the applied `maxBlob`
 * (§6.2 clamps our SUB declaration *down*, never up), and doubling that clears the other two contenders —
 * a sealed chunk (49 221 B, §12.1) and a maximal `list` (400 ids + 1024 tombstones × 32 B ≈ 47 KB, §12.2).
 * Deriving it also means this tracks `DEFAULT_MAX_BLOB` if §5's scope-config ctl ever moves that.
 */
const val MAX_INBOUND_RECORD = 2 * ScopeRegistry.DEFAULT_MAX_BLOB

/**
 * How much of an `err` code we retain. The code is an arbitrary spool-supplied string that outlives the
 * connection in `ScopeSync.lastError` and is rendered raw by the relay and diagnostics screens when it is
 * outside the registry — so an unbounded one is held for the process life and handed to text layout. The
 * registry is append-only and its longest member is `not_subscribed` at 14 characters; 32 is headroom.
 */
const val SPOOL_ERR_CODE_MAX = 32

/** Scope ids, blob ids, roots and keys are all `bstr32` (§12.1) — the one shape check the record layer owns. */
const val SPOOL_ID_BYTES = 32

/**
 * The most ids one listing record can carry under [MAX_INBOUND_RECORD] — the ceiling on any `maxFrames` a
 * spool pins for a commons (HELLO and DIGEST alike), since a room it could never list whole is not one we
 * can heal, and the pinned number sizes the accounted set and the listing threshold derived from it.
 */
const val MAX_PINNED_FRAMES = MAX_INBOUND_RECORD / SPOOL_ID_BYTES

/** Decode-first view of any record: just the discriminator (unknown keys are ignored). */
@Serializable
class SpoolRecordHead(
    val t: String,
)

/**
 * Hard limits a spool advertises in its HELLO.
 *
 * The three attachment limits are the plane's capability signal, and their absence is meaningful:
 * a v1 spool skips an unknown record type **without answering**, so a client that sent `ahave`
 * optimistically would stall on that `q` until the request timeout. [attachments] is therefore a gate,
 * not a hint — see §7.3.
 */
@Serializable
class SpoolLimits(
    val maxBlob: Int,
    val maxRecord: Int,
    val maxScopes: Int,
    val maxPull: Int,
    val maxFramesCap: Int,
    val maxTtlMs: Long,
    val maxAttachBytes: Int? = null,
    val maxAChunk: Int? = null,
    val maxAget: Int? = null,
) {
    /** Whether this spool speaks the attachment family at all. All three limits, or none. */
    val attachments: Boolean get() = maxAttachBytes != null && maxAChunk != null && maxAget != null
}

/**
 * The commons a spool runs, advertised in its HELLO (spec §7.4): a display [name], the room's pinned
 * bounds, and whether it takes attachments. Present iff the spool has a commons — the same
 * present-or-absent capability signal as the attachment triad — and it **never** carries the scope id:
 * the id is the hash of the invite, and a spool that published it would turn a room only invite holders
 * can find into one anybody who connects can flood. Field order matches the daemon's `CommonsInfo`.
 */
@Serializable
class SpoolCommonsInfo(
    val name: String? = null,
    val maxFrames: Int,
    val ttlMs: Long,
    val maxBlob: Int,
    val attach: Boolean = false,
)

/**
 * One entry of a HELLO's `rooms` (spec §7.4, S-7.4-1): a room the spool runs, as [SpoolCommonsInfo] says it, led by
 * its [tag] — the first 8 bytes of a hash of the room's scope id (`ScopeCrypto.commonsTag`), the only form of the id
 * a spool may publish. A member finds its own room by computing the tag; nobody can subscribe with one. Field order
 * matches the spec's `helloSpoolRooms` vector.
 */
@Serializable
class SpoolCommonsRoom(
    val tag: ByteArray,
    val name: String? = null,
    val maxFrames: Int,
    val ttlMs: Long,
    val maxBlob: Int,
    val attach: Boolean = false,
) {
    /** The entry without its tag: what the rest of the client reads a room's advertisement as. */
    fun info(): SpoolCommonsInfo = SpoolCommonsInfo(name, maxFrames, ttlMs, maxBlob, attach)
}

/** A mined `SpoolPow` stamp: counter [n] for UTC day [d]. */
@Serializable
class PowStamp(
    val n: Long,
    val d: Long,
)

/** Per-scope retention bounds — declared by the client at SUB, echoed (as applied) in DIGEST. */
@Serializable
class ScopeBounds(
    val maxFrames: Int,
    val ttlMs: Long,
    val maxBlob: Int,
)

/** One scope subscription: the scope id, the bounds the members converged on, an optional PoW stamp. */
@Serializable
class ScopeSub(
    val scope: ByteArray,
    val bounds: ScopeBounds,
    val pow: PowStamp? = null,
)

/**
 * First record in each direction. Spool→client: [v] is the highest supported version, with [min],
 * [limits], [powBits] (0 = PoW off), [commons] when the spool runs one (§7.4), and [turn] when it issues
 * TURN credentials (experimental, docs/VOICE_CALLS.md §2.1: presence is the signal, `false` is never sent).
 * [ent] says the spool takes entitlement tickets ([SpoolEnt]), and [entGates] which verbs need one, in the bits of a
 * ticket's `fl` ([ticketGates] reads the pair); [stun] lists up to four `stun:`/`stuns:` URIs anyone may gather from
 * with no credential (experimental, docs/VOICE_CALLS.md §2.6). All three are absent when off: `false` and an empty
 * list are never sent. [cast] says the spool fans out [SpoolCast]s
 * within its bounds (experimental, docs/VOICE_CALLS.md §2.7), and is absent when it does not. [rooms] lists every
 * commons room the spool runs, each by its tag, and [commons] then repeats the first (§7.4, S-7.4-1); a spool that
 * predates the list sends [commons] alone. [CommonsAdverts] is the one reader of the pair.
 * Client→spool: [v] is the chosen version, everything else omitted — a client identifies itself no further
 * than that.
 */
@Serializable
class SpoolHello(
    val t: String,
    val v: Int,
    val min: Int? = null,
    val limits: SpoolLimits? = null,
    val powBits: Int? = null,
    val commons: SpoolCommonsInfo? = null,
    val turn: Boolean? = null,
    val ent: Boolean? = null,
    val stun: List<String>? = null,
    val cast: SpoolCastInfo? = null,
    val entGates: Long? = null,
    val rooms: List<SpoolCommonsRoom>? = null,
) {
    /**
     * The `fl` bits this spool requires a ticket for (S-7.6-13), mirroring knit-spool's `Hello.ticketGates`: none
     * without [ent], whatever [entGates] says, and [SpoolTicket.LEGACY_GATES] (TURN and cast) from a spool that sent
     * [ent] but predates [entGates]. Each bit gates one verb (C-7.6-30): a bit not set needs no ticket.
     */
    val ticketGates: Long
        get() = if (ent == true) entGates ?: SpoolTicket.LEGACY_GATES else 0L
}

/**
 * A spool's cast bounds (experimental, docs/VOICE_CALLS.md §2.7): [maxBytes] caps a cast's `data`, and [rate] is casts
 * per second per connection with a burst of four times that. Stated up front because a cast is never answered: a
 * spool drops one past either bound without a word, so a client has to keep to them unprompted.
 */
@Serializable
class SpoolCastInfo(
    val maxBytes: Int,
    val rate: Int,
)

/**
 * A cast (experimental, docs/VOICE_CALLS.md §2.7, proposed spec §7.7): one sealed frame for a scope's other live
 * subscribers, which the spool fans out and never stores. Client→spool it is the one record with no `q`, and it is
 * never answered; spool→client it arrives unchanged, from whichever member cast it. [data] is a cast seal
 * (`ScopeCrypto.sealCast`), never a frame blob.
 */
@Serializable
class SpoolCast(
    val t: String,
    val scope: ByteArray,
    val data: ByteArray,
)

/** Client→spool: subscribe to scopes (responded to with one DIGEST or scoped ERR each). */
@Serializable
class SpoolSub(
    val t: String,
    val q: Long,
    val subs: List<ScopeSub>,
)

/** Spool→client: the anti-entropy cue — current digest, live count, applied bounds, fullness. */
@Serializable
class SpoolDigest(
    val t: String,
    val scope: ByteArray,
    val digest: ByteArray,
    val count: Int,
    val full: Boolean,
    val bounds: ScopeBounds,
)

/**
 * The id exchange behind a digest mismatch. Client→spool: [q] and [scope] only. Spool→client: the
 * live [blobIds] plus current [tombstones], so the client can diff and skip dead pushes.
 */
@Serializable
class SpoolList(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val blobIds: List<ByteArray>? = null,
    val tombstones: List<ByteArray>? = null,
)

/** Client→spool: fetch blobs by id (bounded by the HELLO `maxPull`); answered by BLOBs then OK. */
@Serializable
class SpoolPull(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val blobIds: List<ByteArray>,
)

/** Spool→client: one pulled blob. */
@Serializable
class SpoolBlob(
    val t: String,
    val scope: ByteArray,
    val blobId: ByteArray,
    val data: ByteArray,
)

/** Client→spool: store a sealed blob (the spool re-verifies `blobId = SHA-256(data)`). */
@Serializable
class SpoolPush(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val blobId: ByteArray,
    val data: ByteArray,
    val pow: PowStamp? = null,
)

/** Spool→subscribers: live delivery of a newly stored blob (uploader excluded; best-effort). */
@Serializable
class SpoolEvent(
    val t: String,
    val scope: ByteArray,
    val blobId: ByteArray,
    val data: ByteArray,
)

/** Spool→client: terminal ack for [q]; a PULL's ids the spool no longer holds land in [missing]. */
@Serializable
class SpoolOk(
    val t: String,
    val q: Long,
    val missing: List<ByteArray>? = null,
)

/** Client→spool: what does this spool hold for one attachment (§7.3)? Answered by [SpoolAhas]. */
@Serializable
class SpoolAhave(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val aid: ByteArray,
)

/**
 * Client→spool: ask for one TURN credential (experimental, docs/VOICE_CALLS.md §2.2). Names no scope on purpose,
 * so the spool never learns which conversation is calling (S-7.6-4). Answered by [SpoolTurnCred] or `err`.
 */
@Serializable
class SpoolTurnReq(
    val t: String,
    val q: Long,
)

/**
 * Spool→client: one coturn REST credential. [user] is `"<exp>:<random>"`, [pass] its HMAC, [exp] Unix
 * **seconds**, [urls] RFC 7065 `turn:`/`turns:` URIs. Terminal, and echoes [q] like [SpoolAhas]. Never logged.
 */
@Serializable
class SpoolTurnCred(
    val t: String,
    val q: Long,
    val urls: List<String>,
    val user: String,
    val pass: String,
    val exp: Long,
)

/**
 * Client→spool: present an entitlement ticket (experimental, docs/VOICE_CALLS.md §2.6). [tk] is the CBOR [SpoolTicket]
 * exactly as the issuer signed it, [sig] the 64-byte Ed25519 signature over `"knit/spool/v1/ticket" ‖ tk`, which only
 * the spool checks. Answered by `ok` or `err ticket`; a later accepted `ent` on the same connection replaces the
 * claims. Never logged: the bytes are a bearer grant.
 */
@Serializable
class SpoolEnt(
    val t: String,
    val q: Long,
    val tk: ByteArray,
    val sig: ByteArray,
)

/**
 * The claims of a ticket's `tk`, as far as a client reads them (docs/SPOOL_PROTOCOL.md §7.6): [aud] is the one spool
 * host it is good for, [nbf] and [exp] are Unix **seconds**, [fl] says which gated verbs it unlocks, [ab], when the
 * issuer set it, is the upload budget in bytes for the holder's day (absent: the spool's own), and [am], when set, is
 * the largest attachment `|A|` in bytes it may `aput`, in whole chunks ([maxChunks], S-7.6-31, C-7.6-32). The
 * wire also carries `kid` and `ps`, the holder's daily pseudonym; neither is declared here, so the codec skips both
 * and the claims never hold the pseudonym in a field they could log or show (`HeldTicket.pseudonym` reads it apart,
 * behind a redacted type). Unknown keys are ignored, so later claims add without a version bump.
 */
@Serializable
class SpoolTicket(
    val v: Int,
    val aud: String,
    val nbf: Long,
    val exp: Long,
    val fl: Long,
    val ab: Long? = null,
    val am: Long? = null,
) {
    /**
     * The most chunks an attachment may have on a files-gated spool under this ticket, `⌊am / aChunkBytes⌋`, or null when
     * the ticket sets no `am` and the spool's own limit is the only one. 0 for an `am` under one chunk: no `aput` at all.
     */
    val maxChunks: Int? get() = am?.let(::chunksOf)

    override fun toString(): String = "SpoolTicket(aud=$aud, nbf=$nbf, exp=$exp, fl=$fl, ab=$ab, am=$am)"

    companion object {
        const val VERSION = 1

        /** [fl] bit 0: the holder may ask for TURN credentials. */
        const val FL_TURN = 1L

        /**
         * [fl] bit 1: the holder may upload attachments (`aput`) on its own connection, within its day's budget
         * (knit-spool ADR 2026-10.h6pt). Only the uploader's ticket counts: another member's never admits an upload.
         */
        const val FL_FILES = 2L

        /**
         * [fl] bit 3: the holder may cast, and its connection unlocks casts on every scope it subscribes, for the
         * scope's other members too (docs/VOICE_CALLS.md §2.7). Bit 2 is reserved for knit-spool's proposed
         * scope-creation claim; nothing here reads or sets it.
         */
        const val FL_CAST = 8L

        /** The gates a HELLO with `ent` and no `entGates` means (S-7.6-13): TURN and cast, what a key gated first. */
        const val LEGACY_GATES = FL_TURN or FL_CAST

        /** `⌊am / aChunkBytes⌋`: the whole chunks an `am` of [am] bytes admits (S-7.6-31). */
        fun chunksOf(am: Long): Int = (am / ScopeCrypto.ATTACH_CHUNK_BYTES).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/**
 * Spool→client: one attachment's presence. [total] is 0 when the spool has never seen it; [dead] marks
 * one it has evicted or expired (the attachment analogue of a tombstone, so a client stops re-uploading
 * what it would only lose again). [bits] is the presence bitmap — chunk *i* is bit *i % 8*, MSB-first,
 * of byte *i / 8*, which is what makes "what am I still missing" a local computation.
 */
@Serializable
class SpoolAhas(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val aid: ByteArray,
    val total: Int,
    val bits: ByteArray,
    val dead: Boolean = false,
)

/**
 * Client→spool: fetch up to [n] chunks starting at [from] (bounded by the HELLO `maxAget`); answered by
 * [SpoolAchunk]s then a bare OK. Indices the spool lacks simply do not arrive — the client already knows
 * which those are from the bitmap, so nothing needs to enumerate them back.
 */
@Serializable
class SpoolAget(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val aid: ByteArray,
    val from: Int,
    val n: Int,
)

/** Spool→client: one sealed attachment chunk. Carries no [q] — attributed like [SpoolBlob] is. */
@Serializable
class SpoolAchunk(
    val t: String,
    val scope: ByteArray,
    val aid: ByteArray,
    val idx: Int,
    val total: Int,
    val cid: ByteArray,
    val data: ByteArray,
)

/**
 * Client→spool: store one sealed chunk. The spool re-verifies `cid = SHA-256(data)` and keeps the first
 * write at a position: an identical re-push is acked idempotently, a differing one is
 * [SpoolErrCode.CONFLICT]. Since the chunk seal is deterministic, honest members never differ.
 */
@Serializable
class SpoolAput(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val aid: ByteArray,
    val idx: Int,
    val total: Int,
    val cid: ByteArray,
    val data: ByteArray,
    val pow: PowStamp? = null,
)

/** Spool→client: terminal error for [q] (or connection-scoped when [q] is absent). */
@Serializable
class SpoolErr(
    val t: String,
    val code: String,
    val q: Long? = null,
    val scope: ByteArray? = null,
    val msg: String? = null,
    val retryMs: Long? = null,
)

/**
 * Codec for the record layer. Same explicit profile as `WireCodec`/`cryptoCbor` — definite-length,
 * unknown-tolerant, defaults omitted — plus `alwaysUseByteString` so `List<ByteArray>` ids encode as
 * byte strings (the annotation route only covers direct properties). Decoders return null on any
 * malformed input; a spool answers that with close code 4000, a client just drops the record.
 */
@OptIn(ExperimentalSerializationApi::class)
object SpoolCodec {
    val cbor: Cbor =
        Cbor {
            ignoreUnknownKeys = true
            encodeDefaults = false
            useDefiniteLengthEncoding = true
            alwaysUseByteString = true
        }

    inline fun <reified T> encode(record: T): ByteArray = cbor.encodeToByteArray(record)

    inline fun <reified T> decode(bytes: ByteArray): T? = runCatching { cbor.decodeFromByteArray<T>(bytes) }.getOrNull()

    /** The discriminator of an arbitrary record, or null when it isn't even a record. */
    fun peekType(bytes: ByteArray): String? = decode<SpoolRecordHead>(bytes)?.t
}
