package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.protocol.WireEnvelope
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Compact re-encoding of a fast-path [WireEnvelope] for a size-capped side channel (the Wi-Fi Aware
 * coordination-plane `sendMessage`, ~255 B/message; the BLE extended-advertising page,
 * `mesh/bluetooth/BleSideChannel`, 236 B/page — knit/knit-next#13). **Transport-local, not a wire change**: only the outer envelope
 * (whose `ttl`/`hops`/`relay` are unsigned, mutable routing metadata every relayer already rewrites) is
 * re-framed; [WireEnvelope.sig] and [WireEnvelope.signed] pass through **byte-for-byte**, so the
 * originator's Ed25519 signature verifies unchanged at the endpoint. Senders emit these tags only
 * toward peers advertising `Protocol.CAP_FAST_COMPACT`; everyone else keeps the legacy tagged-CBOR
 * framing, which can represent any envelope.
 *
 * Compact frame ([TAG_COMPACT]):
 * ```
 * [0]    tag 0x03
 * [1]    flags: bit0 RELAY, bit1 DEFLATED, bits2-3 dictId (1 = DICT_V1), bit4 UNSIGNED (the sig field is
 *        absent — the v3 point-to-point sealed tick, ADR 059, whose AEAD is its authenticator),
 *        bits5-7 reserved (must be 0)
 * [2]    ttl (high nibble) / hops (low nibble), each saturated at 15 — legal values are ≤ DEFAULT_TTL=8,
 *        and saturating a hostile larger value can only tighten propagation, never loosen it
 * [3]    sig, 64 raw bytes (outside the deflate stream: 64 random bytes would only poison the
 *        Huffman histogram the compressible CBOR text needs) — omitted when UNSIGNED, so `signed`
 *        starts at [3]
 * [67]   signed — verbatim (DEFLATED clear) or raw-deflate `nowrap` with the preset dict (DEFLATED set;
 *        kept only when strictly smaller, so a compact frame never out-grows its stored form)
 * ```
 *
 * Fragment ([TAG_FRAG]) — the fit guarantee for frames past one message:
 * ```
 * [0]    tag 0x04
 * [1-2]  fragId, big-endian u16 (per-sender counter; one id per frame across all fan-out targets)
 * [3]    part index (high nibble) / part count (low nibble), count in 2..MAX_PARTS
 * [4]    a consecutive slice of the COMPLETE 0x03 or 0x05 frame (its tag+header included), fixed-size
 *        chunks except the last — so reassembly yields a self-describing tagged unit fed back through
 *        [decodeCompact], and the receiver never needs to know the sender's chunk size
 * ```
 *
 * Transcoded frame ([TAG_TRANSCODED], ADR 060): the same header and sig as 0x03, but the body is
 * [FrameTranscoder]'s schema-aware re-encoding of `signed` (integer labels, raw ids and hashes, a 6-byte clock,
 * the payload inlined), which the receiver rebuilds byte-exact before the signature is verified — `signed`
 * still arrives byte-for-byte, it just does not *travel* that way. DEFLATED on 0x05 means raw deflate with
 * **no** dictionary (dictId 0 — the text tokens [DICT_V1] was built from are gone from the transcoded form).
 * A sender emits 0x05 only toward `Protocol.CAP_FRAME_TRANSCODE` peers and only when it is the smaller of the
 * two forms ([encodeBest]); a frame the transcoder cannot reproduce keeps 0x03.
 *
 * Elided frame ([TAG_ELIDED], ADR 2026-10.jend's follow-up): the 0x05 header and sig around an [IdElision] body,
 * a transcoded body with the ids a receiver can put back left out. It cannot be decoded alone — a prefixed id needs
 * the receiver's own directory — so it is not a [decodeCompact] tag; [decodeElided] answers every candidate envelope
 * and the caller keeps the one whose signature verifies. **No sender emits it yet**: it is for a board kind with no
 * fielded receivers (MeshCore), and stays out of every Wi-Fi Aware, BLE and Meshtastic path.
 *
 * Tag registry (append-only, like capability bits): `0x01` legacy tagged-CBOR frame (forever), `0x02`
 * burned (a since-removed nudge), `0x03` compact, `0x04` fragment, `0x05` transcoded, `0x06` elided; `0x10` burned
 * (the LoRa bridge's retired OFFER, see below). Flag bits are append-only too: a
 * receiver drops any reserved bit it does not know, so a new bit is only ever emitted toward a peer whose
 * capabilities say it reads it (UNSIGNED rides behind `Protocol.CAP_CRYPTO_V3`). Tags stay non-printable
 * (`0x00..0x1F`) so untagged cues — whose first byte is a printable node-id char — remain
 * distinguishable. [DICT_V1] is **frozen** once shipped (pinned by a SHA-256 golden test): a receiver
 * inflating with a different dictionary yields garbage that only dies later at decode/signature, so
 * post-ship tuning mints `DICT_V2` under a fresh dictId, never edits V1.
 *
 * Pure (no Android), so the codec is JVM-unit-testable ([app.getknit.knit.FastFrameCodecTest]).
 */
@Suppress("TooManyFunctions") // one wire format's encode/decode/fragment surface; splitting it would scatter the layout
internal object FastFrameCodec {
    /** A single compact frame (layout above). */
    const val TAG_COMPACT: Byte = 0x03

    /** One fragment of a compact frame (layout above). */
    const val TAG_FRAG: Byte = 0x04

    /** A single transcoded frame (layout above, ADR 060): the 0x03 header around a [FrameTranscoder] body. */
    const val TAG_TRANSCODED: Byte = 0x05

    /** A single elided frame (layout above): the 0x05 header around an [IdElision] body. Not emitted yet. */
    const val TAG_ELIDED: Byte = 0x06

    // Burned elsewhere in this tag space: 0x10 was the LoRa plane's gateway-to-gateway OFFER (ADR 044's
    // `LoraCtl`), retired with the pocket bridge (ADR 2026-10.rp3s). Older builds still air it, and the LoRa plane
    // drops it before decode (`LoraMeshTransport.RETIRED_CTL_TAG`), so it is never minted again — not as a frame
    // tag, not as a control packet.

    /** Most parts one frame may split into — bounds reassembly state and the loss-probability cost. */
    const val MAX_PARTS = 3

    /**
     * Raw Ed25519 signature width; a [WireEnvelope] with any other [WireEnvelope.sig] size is unrepresentable —
     * except the empty one, which is the UNSIGNED form.
     */
    const val SIG_BYTES = 64

    /** Flags bit 4: no sig field — a `relay = false` v3 sealed tick authenticated by its AEAD (ADR 059). */
    const val FLAG_UNSIGNED = 0x10

    /** Compact fixed header: tag + flags + ttl/hops. */
    const val HEADER_BYTES = 3

    /** Fragment fixed header: tag + fragId(2) + part/count. */
    const val FRAG_HEADER_BYTES = 4

    /** The shipped preset dictionary's id (flags bits 2-3). Never emitted on 0x03 without a dictionary. */
    const val DICT_ID_V1 = 1

    /** No dictionary (flags bits 2-3): the only dictId a DEFLATED 0x05 frame carries; unknown on 0x03. */
    const val DICT_ID_NONE = 0

    private const val FLAG_RELAY = 0x01
    private const val FLAG_DEFLATED = 0x02
    private const val DICT_SHIFT = 2
    private const val DICT_MASK = 0x03
    private const val RESERVED_MASK = 0xE0
    private const val NIBBLE_MAX = 15
    private const val NIBBLE_BITS = 4
    private const val BYTE_MASK = 0xFF

    /** Inflate output cap — a hostile tiny deflate stream can't balloon (largest honest frame ≪ this). */
    private const val MAX_INFLATED_BYTES = 8 * 1024

    /**
     * Preset deflate dictionary, version 1 — the CBOR key tokens (1-byte text header + UTF-8, matching
     * the on-wire bytes) of every frame field, ordered least-frequent first / hottest last (zlib scores
     * matches nearer the tail higher). FROZEN: see the class kdoc.
     */
    val DICT_V1: ByteArray = buildDictV1()

    /**
     * The one 0x03 frame for [wire] — deflated when that is strictly smaller, stored otherwise — or
     * null when [wire] is unrepresentable: a [WireEnvelope.sig] that is neither exactly [SIG_BYTES] nor
     * empty. An empty sig is the UNSIGNED form (the sig field is simply absent); the caller decides
     * whether the peer reads it. Note the unsigned blob request has never ridden this path — it goes
     * over links — so the empty case here is, in practice, the v3 tick.
     */
    fun encodeCompact(wire: WireEnvelope): ByteArray? {
        if (!representable(wire)) return null
        return frame(TAG_COMPACT, wire, wire.signed, deflate(wire.signed, DICT_V1), DICT_ID_V1)
    }

    /**
     * The one 0x05 frame for [wire] (ADR 060): [FrameTranscoder]'s re-encoding of `signed`, raw-deflated with
     * no dictionary when that is strictly smaller. Null when the sig is unrepresentable (as [encodeCompact]) or
     * the transcoder cannot reproduce this frame — an encoding it does not model — so the caller keeps 0x03.
     */
    fun encodeTranscoded(wire: WireEnvelope): ByteArray? {
        if (!representable(wire)) return null
        val transcoded = FrameTranscoder.transcode(wire.signed) ?: return null
        return frame(TAG_TRANSCODED, wire, transcoded, deflate(transcoded, null), DICT_ID_NONE)
    }

    /**
     * The smaller of [encodeCompact] and — when [transcode], i.e. toward a `Protocol.CAP_FRAME_TRANSCODE` peer —
     * [encodeTranscoded]: fewer bytes is never more parts. Null when neither exists (an odd-sized sig).
     */
    fun encodeBest(
        wire: WireEnvelope,
        transcode: Boolean,
    ): Best? {
        val compact = encodeCompact(wire)
        if (!transcode) return compact?.let { Best(it, transcodeRefused = false) }
        val transcoded = encodeTranscoded(wire) ?: return compact?.let { Best(it, transcodeRefused = true) }
        val frame = if (compact == null || transcoded.size < compact.size) transcoded else compact
        return Best(frame, transcodeRefused = false)
    }

    /**
     * The one 0x06 frame for [wire]: [IdElision]'s body, raw-deflated with no dictionary when that is strictly
     * smaller. Null when the sig is unrepresentable or the body cannot be reproduced — the caller keeps 0x05.
     * [prefixSender] and [prefixRecipient] are the caller's per-frame call ([IdElision.elide]).
     */
    fun encodeElided(
        wire: WireEnvelope,
        prefixSender: Boolean,
        prefixRecipient: Boolean,
    ): ByteArray? {
        if (!representable(wire)) return null
        val body = IdElision.elide(wire.signed, prefixSender, prefixRecipient) ?: return null
        return frame(TAG_ELIDED, wire, body, deflate(body, null), DICT_ID_NONE)
    }

    /**
     * Every [WireEnvelope] a 0x06 [message] can stand for, given [resolve] for its prefixed ids
     * ([IdElision.rebuild]); empty when it is malformed under the same rules as [decodeCompact].
     */
    fun decodeElided(
        message: ByteArray,
        resolve: (ByteArray) -> List<ByteArray>,
    ): List<WireEnvelope> {
        if (message.size < HEADER_BYTES + 1 || message[0] != TAG_ELIDED) return emptyList()
        val flags = message[1].toInt() and BYTE_MASK
        if (flags and RESERVED_MASK != 0) return emptyList()
        val sigBytes = if (flags and FLAG_UNSIGNED != 0) 0 else SIG_BYTES
        if (message.size < HEADER_BYTES + sigBytes + 1) return emptyList()
        val raw = message.copyOfRange(HEADER_BYTES + sigBytes, message.size)
        val body =
            when {
                flags and FLAG_DEFLATED == 0 -> raw
                (flags shr DICT_SHIFT) and DICT_MASK == DICT_ID_NONE -> inflate(raw, null)
                else -> null
            } ?: return emptyList()
        return IdElision.rebuild(body, resolve).map { signed ->
            WireEnvelope(
                ttl = (message[2].toInt() shr NIBBLE_BITS) and NIBBLE_MAX,
                hops = message[2].toInt() and NIBBLE_MAX,
                relay = flags and FLAG_RELAY != 0,
                sig = message.copyOfRange(HEADER_BYTES, HEADER_BYTES + sigBytes),
                signed = signed,
            )
        }
    }

    /**
     * [encodeBest]'s pick. [transcodeRefused] is the field signal that some build emits an encoding the transcoder
     * cannot reproduce (the frame rode 0x03 for that reason, not because 0x03 was smaller).
     */
    class Best(
        val frame: ByteArray,
        val transcodeRefused: Boolean,
    ) {
        val transcoded: Boolean get() = frame[0] == TAG_TRANSCODED
    }

    /** Whether [tag] opens a complete frame this codec decodes — what a reassembled fragment set must start with. */
    fun isFrameTag(tag: Byte): Boolean = tag == TAG_COMPACT || tag == TAG_TRANSCODED

    /**
     * Whether a complete 0x03/0x05 [frame]'s body is a deflate stream — and so, **whether trailing bytes
     * appended after it are ignored on decode**. That second reading is the one callers actually want, and it
     * is a property of [inflate]: the loop stops the moment the stream says it is finished and never looks at
     * what follows, so every build in the field already tolerates a padded frame. A **stored** body has no
     * such marker — on 0x03 the extra bytes become the tail of `signed` and the frame dies later at CBOR
     * decode or signature verification, on 0x05 [FrameTranscoder.rebuild] rejects it outright.
     *
     * `LoraFrameCodec` pads on exactly this basis (ADR 2026-09.mhs5): a Meshtastic 2.8 board signs any packet small
     * enough to still fit a signature, so growing one past that cliff trades 66 bytes of firmware signature
     * for a few bytes of pad. False for anything that is not a complete frame, so a fragment (0x04) — whose
     * flags live inside the frame it carries a slice of — is never mistaken for a paddable one.
     */
    fun deflated(frame: ByteArray): Boolean = frame.size > 1 && isFrameTag(frame[0]) && (frame[1].toInt() and FLAG_DEFLATED) != 0

    /**
     * The [WireEnvelope] a 0x03 or 0x05 message reconstructs, or null when it is malformed: wrong tag, shorter
     * than header + sig, a reserved flag bit set (an unknown future variant — the flood copy is the
     * backstop), a dictId the tag does not take, a deflate stream that fails to inflate, or (0x05) a body the
     * transcoder cannot rebuild.
     */
    fun decodeCompact(message: ByteArray): WireEnvelope? {
        if (message.size < HEADER_BYTES + 1 || !isFrameTag(message[0])) return null
        val flags = message[1].toInt() and BYTE_MASK
        if (flags and RESERVED_MASK != 0) return null
        val sigBytes = if (flags and FLAG_UNSIGNED != 0) 0 else SIG_BYTES
        if (message.size < HEADER_BYTES + sigBytes + 1) return null
        val signed = signedOf(message[0], flags, message.copyOfRange(HEADER_BYTES + sigBytes, message.size)) ?: return null
        return WireEnvelope(
            ttl = (message[2].toInt() shr NIBBLE_BITS) and NIBBLE_MAX,
            hops = message[2].toInt() and NIBBLE_MAX,
            relay = flags and FLAG_RELAY != 0,
            sig = message.copyOfRange(HEADER_BYTES, HEADER_BYTES + sigBytes),
            signed = signed,
        )
    }

    /**
     * The DEFLATED equivalent of a **stored** 0x03/0x05 [frame] — the same envelope, the same body, re-encoded
     * as a deflate stream so that trailing bytes after it are ignored on decode ([deflated]). Null when [frame]
     * is already deflated, is not a complete frame, or the re-encoding is not decodable.
     *
     * The point is not compression — an incompressible body grows by the deflate framing — but *paddability*:
     * a Meshtastic 2.8 board charges 66 bytes of signature for a packet under its cliff, and a few bytes of
     * deflate framing plus a few of pad buy that back (ADR 2026-09.mhs5). [encodeCompact] deliberately never does this
     * (a compact frame must never out-grow its stored form for the coordination plane); the LoRa sender asks
     * for it explicitly, and only where it has priced the trade.
     */
    fun deflatedForm(frame: ByteArray): ByteArray? {
        if (frame.size < HEADER_BYTES || !isFrameTag(frame[0]) || deflated(frame)) return null
        val flags = frame[1].toInt() and BYTE_MASK
        if (flags and RESERVED_MASK != 0) return null
        val sigBytes = if (flags and FLAG_UNSIGNED != 0) 0 else SIG_BYTES
        if (frame.size < HEADER_BYTES + sigBytes) return null
        val transcoded = frame[0] == TAG_TRANSCODED
        val dictId = if (transcoded) DICT_ID_NONE else DICT_ID_V1
        val body = frame.copyOfRange(HEADER_BYTES + sigBytes, frame.size)
        val stream = deflate(body, if (transcoded) null else DICT_V1)
        // deflate() reports an expansion by handing the input straight back; that is not a stream, so refuse.
        if (stream.contentEquals(body)) return null
        val out = frame.copyOf(HEADER_BYTES + sigBytes + stream.size)
        out[1] = (flags or FLAG_DEFLATED or (dictId shl DICT_SHIFT)).toByte()
        stream.copyInto(out, HEADER_BYTES + sigBytes)
        return out
    }

    private fun representable(wire: WireEnvelope): Boolean = wire.sig.isEmpty() || wire.sig.size == SIG_BYTES

    /** Frames [wire] under [tag] with the smaller of [stored] and its [deflated] form (flagged with [dictId]). */
    private fun frame(
        tag: Byte,
        wire: WireEnvelope,
        stored: ByteArray,
        deflated: ByteArray,
        dictId: Int,
    ): ByteArray {
        val unsigned = wire.sig.isEmpty()
        val useDeflate = deflated.size < stored.size
        val body = if (useDeflate) deflated else stored
        var flags = 0
        if (wire.relay) flags = flags or FLAG_RELAY
        if (useDeflate) flags = flags or FLAG_DEFLATED or (dictId shl DICT_SHIFT)
        if (unsigned) flags = flags or FLAG_UNSIGNED
        val sigBytes = if (unsigned) 0 else SIG_BYTES
        val out = ByteArray(HEADER_BYTES + sigBytes + body.size)
        out[0] = tag
        out[1] = flags.toByte()
        out[2] = packTtlHops(wire.ttl, wire.hops)
        wire.sig.copyInto(out, HEADER_BYTES)
        body.copyInto(out, HEADER_BYTES + sigBytes)
        return out
    }

    /** `signed` behind a frame [body]: inflated when DEFLATED (0x03 takes [DICT_ID_V1], 0x05 no dictionary), rebuilt for 0x05. */
    private fun signedOf(
        tag: Byte,
        flags: Int,
        body: ByteArray,
    ): ByteArray? {
        val transcoded = tag == TAG_TRANSCODED
        val stored =
            if (flags and FLAG_DEFLATED == 0) {
                body
            } else {
                val dictId = (flags shr DICT_SHIFT) and DICT_MASK
                when {
                    transcoded && dictId == DICT_ID_NONE -> inflate(body, null)
                    !transcoded && dictId == DICT_ID_V1 -> inflate(body, DICT_V1)
                    else -> null
                } ?: return null
            }
        return if (transcoded) FrameTranscoder.rebuild(stored) else stored
    }

    /**
     * Splits a complete 0x03 frame into 0x04 messages of at most [maxMessage] bytes, or null when it
     * would take more than [MAX_PARTS]. Never called for a frame that already fits ([fragment] demands
     * ≥ 2 parts); chunks are fixed-size except the last, so the receiver reassembles by index alone.
     */
    fun fragment(
        compact: ByteArray,
        maxMessage: Int,
        fragId: Int,
    ): List<ByteArray>? {
        val chunk = maxMessage - FRAG_HEADER_BYTES
        if (chunk <= 0) return null
        val count = (compact.size + chunk - 1) / chunk
        if (count !in 2..MAX_PARTS) return null
        return List(count) { part ->
            val from = part * chunk
            val to = minOf(from + chunk, compact.size)
            byteArrayOf(
                TAG_FRAG,
                ((fragId shr Byte.SIZE_BITS) and BYTE_MASK).toByte(),
                (fragId and BYTE_MASK).toByte(),
                ((part shl NIBBLE_BITS) or count).toByte(),
            ) + compact.copyOfRange(from, to)
        }
    }

    /**
     * A parsed 0x04 message, or null when malformed: wrong tag, no payload, a count outside
     * 2..[MAX_PARTS], or a part index at/past the count.
     */
    fun parseFragment(message: ByteArray): Fragment? {
        if (message.size <= FRAG_HEADER_BYTES || message[0] != TAG_FRAG) return null
        val part = (message[3].toInt() shr NIBBLE_BITS) and NIBBLE_MAX
        val count = message[3].toInt() and NIBBLE_MAX
        if (count !in 2..MAX_PARTS || part >= count) return null
        val fragId = ((message[1].toInt() and BYTE_MASK) shl Byte.SIZE_BITS) or (message[2].toInt() and BYTE_MASK)
        return Fragment(fragId, part, count, message.copyOfRange(FRAG_HEADER_BYTES, message.size))
    }

    /**
     * One parsed fragment. A plain class (like [WireEnvelope]): a ByteArray field would make a data
     * class' equals/hashCode reference-based, so we don't pretend to value equality.
     */
    class Fragment(
        val fragId: Int,
        val part: Int,
        val count: Int,
        val payload: ByteArray,
    )

    /** [ttl]/[hops] packed to one byte, each nibble saturated at [NIBBLE_MAX] (kdoc: why that is safe). */
    private fun packTtlHops(
        ttl: Int,
        hops: Int,
    ): Byte {
        val t = ttl.coerceIn(0, NIBBLE_MAX)
        val h = hops.coerceIn(0, NIBBLE_MAX)
        return ((t shl NIBBLE_BITS) or h).toByte()
    }

    /** Raw-deflate [bytes] with the preset [dictionary] (none for 0x05) at best compression (frames are ≤ ~1 KB — µs work). */
    private fun deflate(
        bytes: ByteArray,
        dictionary: ByteArray?,
    ): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            if (dictionary != null) deflater.setDictionary(dictionary)
            deflater.setInput(bytes)
            deflater.finish()
            val buf = ByteArray(bytes.size + DEFLATE_SLACK)
            var len = 0
            while (!deflater.finished() && len < buf.size) {
                len += deflater.deflate(buf, len, buf.size - len)
            }
            // Not finished within input-size + slack ⇒ it expanded; report as such so stored wins.
            if (!deflater.finished()) return bytes
            return buf.copyOf(len)
        } finally {
            deflater.end()
        }
    }

    /**
     * Inflates a raw-deflate [bytes] stream with the preset [dictionary] (none for 0x05), or null if
     * malformed/oversized.
     *
     * **Trailing bytes past the end of the stream are ignored, and senders depend on that** — see [deflated].
     * Rejecting them would read as a safe tightening and would silently break every padded LoRa sender
     * against older receivers, which is why `FastFrameCodecTest` pins the tolerance rather than leaving it an
     * accident of the loop below.
     */
    private fun inflate(
        bytes: ByteArray,
        dictionary: ByteArray?,
    ): ByteArray? {
        val inflater = Inflater(true)
        try {
            // Raw (nowrap) streams take the preset dictionary up front; zlib-wrapped ones would signal it.
            if (dictionary != null) inflater.setDictionary(dictionary)
            inflater.setInput(bytes)
            val buf = ByteArray(MAX_INFLATED_BYTES)
            var len = 0
            while (!inflater.finished()) {
                val n = inflater.inflate(buf, len, buf.size - len)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                len += n
                if (len == buf.size && !inflater.finished()) return null // past the honest-frame cap
            }
            return buf.copyOf(len)
        } catch (_: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }

    private const val DEFLATE_SLACK = 64

    /** A CBOR definite-length text token as it appears on the wire: 1-byte header (all keys ≤ 23 chars) + UTF-8. */
    private fun cborText(s: String): ByteArray {
        val utf8 = s.encodeToByteArray()
        check(utf8.size <= CBOR_TINY_TEXT_MAX) { "dict token too long for a 1-byte header: $s" }
        return byteArrayOf((CBOR_TEXT_BASE + utf8.size).toByte()) + utf8
    }

    private const val CBOR_TEXT_BASE = 0x60
    private const val CBOR_TINY_TEXT_MAX = 23

    /** See [DICT_V1]. Grouped rare → hot; the RelayEnvelope spine every frame carries goes last. */
    @Suppress("LongMethod") // a flat data list, one token per line — splitting it would scatter the ordering
    private fun buildDictV1(): ByteArray {
        val tokens =
            listOf(
                // Profile / group / request payload keys (rarer frames).
                "name",
                "status",
                "avatarHash",
                "pubKey",
                "deviceTag",
                "protoVersion",
                "capabilities",
                "prekey",
                "pkid",
                "version",
                "members",
                "createdBy",
                "groupId",
                "nodeIds",
                "hash",
                "photoHash",
                "photoUpdatedAt",
                "departed",
                "ackId",
                "messageId",
                "emoji",
                // Chat / crypto envelope keys.
                "body",
                "mentions",
                "attachmentHash",
                "attachmentMime",
                "replyTo",
                "enc",
                "nonce",
                "ct",
                "keys",
                "init",
                "eph",
                "at",
                "se",
                "ek",
                "pe",
                "n",
                "v",
                "r",
                "g",
                // Frame-type strings.
                "groupupdate",
                "groupleave",
                "blobreq",
                "keyreq",
                "typing",
                "profile",
                "reaction",
                "receipt",
                "chat",
                // The RelayEnvelope spine — on every frame, so hottest, so last.
                "type",
                "id",
                "senderId",
                "sentAt",
                "recipientId",
                "group",
                "payload",
            )
        return tokens.fold(ByteArray(0)) { acc, t -> acc + cborText(t) }
    }
}

/**
 * Reassembles [FastFrameCodec.TAG_FRAG] parts back into complete compact frames, keyed by [K] (the
 * transport's per-sender identity — e.g. the Wi-Fi Aware discovery session + peer handle) plus the
 * fragment id. Single-thread-confined by contract (the Wi-Fi Aware transport touches it only on its
 * one Aware callback thread), so it needs no locking; [now] is injected so the JVM tests drive time.
 *
 * Bounded on both axes: at most [capacity] in-flight entries (inserting past it evicts the oldest —
 * [Drop.OVERFLOW]) and a lazy [timeoutMs] sweep on every insert drops stale ones ([Drop.TIMEOUT]) —
 * no timer, because the whole map is ≤ [DEFAULT_CAPACITY] × ~765 B and an orphan parks harmlessly
 * until the next insert. Duplicate parts are idempotent; a re-announced fragId with a *different*
 * part count restarts its entry (the sender wrapped or restarted); completion removes the entry, so
 * a late duplicate part starts a fresh one that simply ages out. Losses are fine by design: this
 * plane is best-effort and the flood/custody path re-carries every reliable frame.
 *
 * Pure (no Android) — JVM-unit-testable ([app.getknit.knit.FragReassemblerTest]).
 */
internal class FragReassembler<K : Any>(
    private val now: () -> Long,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onDrop: (Drop) -> Unit = {},
) {
    /** Why an incomplete entry was discarded (surfaced as metrics by the transport). */
    enum class Drop { TIMEOUT, OVERFLOW }

    private class Entry(
        val count: Int,
        val firstAt: Long,
        val parts: Array<ByteArray?>,
    ) {
        fun complete(): Boolean = parts.all { it != null }

        fun assembled(): ByteArray = parts.fold(ByteArray(0)) { acc, p -> acc + checkNotNull(p) }
    }

    // Insertion-ordered so "evict the oldest" is the first key; entries are removed on completion.
    private val inFlight = LinkedHashMap<Pair<K, Int>, Entry>()

    /**
     * Absorbs one [frag] from [key]; returns the completed compact frame (and forgets the entry) once
     * every part is present, else null.
     */
    fun accept(
        key: K,
        frag: FastFrameCodec.Fragment,
    ): ByteArray? {
        sweep()
        val mapKey = key to frag.fragId
        var entry = inFlight[mapKey]
        if (entry == null || entry.count != frag.count) {
            entry = Entry(frag.count, now(), arrayOfNulls(frag.count))
            inFlight.remove(mapKey)
            if (inFlight.size >= capacity) {
                inFlight.remove(inFlight.keys.first())
                onDrop(Drop.OVERFLOW)
            }
            inFlight[mapKey] = entry
        }
        if (entry.parts[frag.part] == null) entry.parts[frag.part] = frag.payload
        if (!entry.complete()) return null
        inFlight.remove(mapKey)
        return entry.assembled()
    }

    /** Forgets everything (transport stop). */
    fun clear() = inFlight.clear()

    private fun sweep() {
        val cutoff = now() - timeoutMs
        val it = inFlight.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.firstAt < cutoff) {
                it.remove()
                onDrop(Drop.TIMEOUT)
            }
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 8
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
