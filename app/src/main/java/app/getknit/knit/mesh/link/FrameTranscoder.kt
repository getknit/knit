package app.getknit.knit.mesh.link

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.link.CborItems.MAJOR_ARRAY
import app.getknit.knit.mesh.link.CborItems.MAJOR_BSTR
import app.getknit.knit.mesh.link.CborItems.MAJOR_MAP
import app.getknit.knit.mesh.link.CborItems.MAJOR_TSTR
import app.getknit.knit.mesh.link.CborItems.MAJOR_UINT
import app.getknit.knit.mesh.protocol.CanonicalText
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import java.io.ByteArrayOutputStream

/**
 * The schema-aware re-encoding behind the `0x05` fast-frame tag (ADR 060). [transcode] rewrites the canonical
 * `WireEnvelope.signed` CBOR — text keys, base64url/base32 ids, hex hashes, a 9-byte millisecond clock, the
 * payload as an opaque byte string — into a form a size-capped radio can afford: one-byte integer labels, raw
 * 16-byte ids, raw hashes, a 6-byte clock, the frame type as a small int, the payload inlined as a nested map.
 * [rebuild] is its exact inverse, so the receiver recovers the **byte-identical** canonical bytes and verifies
 * the originator's Ed25519 signature over them. `signed` still arrives byte-for-byte; it just does not travel
 * that way. Transport-local, not a wire change: nothing here is signed, stored, or relayed.
 *
 * **Generic, path-scoped, passthrough.** The walk is a CBOR rewrite driven by a table of `(scope, key) →
 * (label, kind)`, not a typed mirror: a key the table does not know rides as its text key plus the raw value
 * (so a newer build's additive field costs its name, never a fallback — `fastFanout` re-fans frames other
 * builds originated), and every value transform is **self-describing by CBOR major type with a passthrough
 * fallback** (a non-canonical id — the profile frame's `"profile-…"` id, an uppercase hash — stays a text
 * string; an unknown frame type keeps its byte-string payload). The only elision is `EncEnvelope`'s always-
 * present `nonce`/`keys` when empty (v3's form), and only in a scope with no unknown key — the rebuild walks
 * such a scope in declaration order and fills the two canonical defaults back in.
 *
 * **Self-verifying.** [transcode] returns null unless `rebuild(out)` reproduces its input exactly, so a caller
 * cannot skip the round-trip and a frame this cannot reproduce keeps the `0x03` framing. A receiver that gets
 * a compact body its rebuild disagrees with verifies nothing: the signature fails over the wrong bytes.
 *
 * **Schema 1 is frozen** (pinned by [app.getknit.knit.mesh.link.FrameTranscoderTest]'s golden vectors and label
 * map): labels are 1..n per scope in declaration order, 0 reserved — the `MessageContentV2` convention. A
 * richer schema is a new tag, never an edit: a receiver rebuilds with the table it shipped with.
 *
 * Pure (no Android): the item scanner is [CborItems]; the id/hash/bundle conversions are `FrameId`, `NodeId` and
 * [CanonicalText], every one a round-trip check.
 */
internal object FrameTranscoder {
    /** Input cap for [transcode] and output cap for [rebuild] — a hostile compact body cannot balloon. */
    const val MAX_BYTES = 8 * 1024

    /** The compact form of a canonical [signed] blob, or null when this transcoder cannot reproduce it exactly. */
    fun transcode(signed: ByteArray): ByteArray? {
        if (signed.isEmpty() || signed.size > MAX_BYTES) return null
        val out = runCatching { Transcode(signed).root() }.getOrNull() ?: return null
        val back = rebuild(out) ?: return null
        return out.takeIf { back.contentEquals(signed) }
    }

    /** The canonical bytes behind a [compact] body, or null when it is malformed, oversized, or not this schema. */
    fun rebuild(compact: ByteArray): ByteArray? {
        if (compact.isEmpty() || compact.size > MAX_BYTES) return null
        return runCatching { Rebuild(compact).root() }.getOrNull()?.takeIf { it.size <= MAX_BYTES }
    }

    /** Schema 1 as `Scope.key → label`, for the freeze test. */
    fun schema(): Map<String, Int> = SCOPES.flatMap { s -> s.fields.map { "${s.name}.${it.key}" to it.label } }.toMap()

    /** The small int a known frame type rides as, or null for a type that stays text. */
    fun typeCode(type: String): Long? = TYPE_CODES[type]

    /** What a field's value becomes: copied verbatim, or one of the text ↔ bytes transforms, or a nested scope. */
    private enum class Kind { PASS, FRAME_ID, NODE_ID, HASH, GROUP_ID, HEX8, PUBKEY, FRAME_TYPE, MILLIS, NESTED, PAYLOAD }

    private class Field(
        val key: String,
        val label: Int,
        val kind: Kind,
        val nested: Scope? = null,
        /** A definite-length array of [kind] values (nodeId lists, wrapped keys, mentions). */
        val list: Boolean = false,
        /** Omitted when empty in a scope with no unknown key; the rebuild restores the canonical empty value. */
        val elideEmpty: Boolean = false,
    )

    private class Scope(
        val name: String,
        val fields: List<Field>,
    ) {
        val byKey = fields.associateBy { it.key }
        val byLabel = fields.associateBy { it.label }
        val elidable = fields.filter { it.elideEmpty }

        fun indexOf(field: Field): Int = fields.indexOf(field)
    }

    private fun pass(
        key: String,
        label: Int,
    ) = Field(key, label, Kind.PASS)

    private fun nested(
        key: String,
        label: Int,
        scope: Scope,
        list: Boolean = false,
        elideEmpty: Boolean = false,
    ) = Field(key, label, Kind.NESTED, nested = scope, list = list, elideEmpty = elideEmpty)

    private fun nodeIds(
        key: String,
        label: Int,
    ) = Field(key, label, Kind.NODE_ID, list = true)

    // Declaration order per scope matches the wire class's (that is what kotlinx emits); labels are its 1-based index.
    private val RATCHET_INIT = Scope("RatchetInit", listOf(pass("eph", 1), pass("pkid", 2), Field("at", 3, Kind.MILLIS)))
    private val RATCHET_HEADER =
        Scope(
            "RatchetHeader",
            listOf(pass("se", 1), pass("ek", 2), pass("pe", 3), pass("n", 4), nested("init", 5, RATCHET_INIT), pass("flags", 6)),
        )
    private val GROUP_RATCHET_HEADER = Scope("GroupRatchetHeader", listOf(pass("se", 1), pass("n", 2)))
    private val WRAPPED_KEY = Scope("WrappedKey", listOf(Field("to", 1, Kind.NODE_ID), pass("wk", 2)))
    private val ENC_ENVELOPE =
        Scope(
            "EncEnvelope",
            listOf(
                pass("v", 1),
                Field("nonce", 2, Kind.PASS, elideEmpty = true),
                pass("ct", 3),
                nested("keys", 4, WRAPPED_KEY, list = true, elideEmpty = true),
                nested("r", 5, RATCHET_HEADER),
                nested("g", 6, GROUP_RATCHET_HEADER),
            ),
        )
    private val MENTION = Scope("Mention", listOf(Field("nodeId", 1, Kind.NODE_ID), pass("name", 2)))
    private val REPLY_REF =
        Scope(
            "ReplyRef",
            listOf(
                Field("messageId", 1, Kind.FRAME_ID),
                Field("authorId", 2, Kind.NODE_ID),
                pass("author", 3),
                pass("snippet", 4),
                pass("hasAttachment", 5),
            ),
        )
    private val CHAT =
        Scope(
            "ChatContent",
            listOf(
                pass("body", 1),
                nested("mentions", 2, MENTION, list = true),
                Field("attachmentHash", 3, Kind.HASH),
                pass("attachmentMime", 4),
                nested("enc", 5, ENC_ENVELOPE),
                nested("replyTo", 6, REPLY_REF),
            ),
        )
    private val PREKEY = Scope("PrekeyInfo", listOf(pass("id", 1), pass("pub", 2), pass("sig", 3)))
    private val PROFILE =
        Scope(
            "ProfileContent",
            listOf(
                pass("name", 1),
                pass("status", 2),
                Field("avatarHash", 3, Kind.HASH),
                Field("pubKey", 4, Kind.PUBKEY),
                Field("deviceTag", 5, Kind.HEX8),
                pass("protoVersion", 6),
                pass("capabilities", 7),
                nested("prekey", 8, PREKEY),
                Field("version", 9, Kind.MILLIS),
            ),
        )
    private val RECEIPT = Scope("ReceiptContent", listOf(Field("ackId", 1, Kind.FRAME_ID)))
    private val REACTION = Scope("ReactionContent", listOf(Field("messageId", 1, Kind.FRAME_ID), pass("emoji", 2)))
    private val GROUP_LEAVE = Scope("GroupLeaveContent", listOf(Field("groupId", 1, Kind.GROUP_ID)))
    private val BLOB_REQ = Scope("BlobReqContent", listOf(Field("hash", 1, Kind.HASH)))
    private val KEY_REQ = Scope("KeyReqContent", listOf(nodeIds("nodeIds", 1)))
    private val TYPING = Scope("TypingContent", listOf(Field("groupId", 1, Kind.GROUP_ID)))
    private val GROUP_INFO =
        Scope(
            "GroupInfo",
            listOf(
                Field("id", 1, Kind.GROUP_ID),
                pass("name", 2),
                nodeIds("members", 3),
                Field("createdBy", 4, Kind.NODE_ID),
                Field("photoHash", 5, Kind.HASH),
                Field("photoUpdatedAt", 6, Kind.MILLIS),
                nodeIds("departed", 7),
            ),
        )
    private val RELAY =
        Scope(
            "RelayEnvelope",
            listOf(
                Field("type", 1, Kind.FRAME_TYPE),
                Field("id", 2, Kind.FRAME_ID),
                Field("senderId", 3, Kind.NODE_ID),
                Field("sentAt", 4, Kind.MILLIS),
                Field("recipientId", 5, Kind.NODE_ID),
                nested("group", 6, GROUP_INFO),
                Field("payload", 7, Kind.PAYLOAD),
            ),
        )
    private val SCOPES =
        listOf(
            RELAY,
            GROUP_INFO,
            CHAT,
            MENTION,
            REPLY_REF,
            ENC_ENVELOPE,
            WRAPPED_KEY,
            RATCHET_HEADER,
            RATCHET_INIT,
            GROUP_RATCHET_HEADER,
            PROFILE,
            PREKEY,
            RECEIPT,
            REACTION,
            GROUP_LEAVE,
            BLOB_REQ,
            KEY_REQ,
            TYPING,
        )

    /** The payload scope a known frame type inlines (`groupupdate` carries an empty payload — nothing to inline). */
    private val CONTENT_SCOPES =
        mapOf(
            FrameType.CHAT to CHAT,
            FrameType.PROFILE to PROFILE,
            FrameType.RECEIPT to RECEIPT,
            FrameType.REACTION to REACTION,
            FrameType.GROUP_LEAVE to GROUP_LEAVE,
            FrameType.BLOB_REQ to BLOB_REQ,
            FrameType.KEY_REQ to KEY_REQ,
            FrameType.TYPING to TYPING,
        )

    /** Frame-type codes 1..9 (append-only, like the strings themselves); an unknown type stays text. */
    private val TYPE_CODES: Map<String, Long> =
        listOf(
            FrameType.CHAT,
            FrameType.GROUP_UPDATE,
            FrameType.GROUP_LEAVE,
            FrameType.PROFILE,
            FrameType.RECEIPT,
            FrameType.REACTION,
            FrameType.BLOB_REQ,
            FrameType.KEY_REQ,
            FrameType.TYPING,
        ).withIndex().associate { (i, type) -> type to (i + 1).toLong() }
    private val TYPE_NAMES: Map<Long, String> = TYPE_CODES.entries.associate { (name, code) -> code to name }

    private val TEXT_TO_BYTES: Map<Kind, (String) -> ByteArray?> =
        mapOf(
            Kind.FRAME_ID to FrameId::toBytesOrNull,
            Kind.NODE_ID to NodeId::toBytesOrNull,
            Kind.HASH to CanonicalText::hashBytesOrNull,
            Kind.GROUP_ID to CanonicalText::groupIdBytesOrNull,
            Kind.HEX8 to CanonicalText::hex8BytesOrNull,
            Kind.PUBKEY to CanonicalText::bundleRawOrNull,
        )
    private val BYTES_TO_TEXT: Map<Kind, Pair<Int, (ByteArray) -> String>> =
        mapOf(
            Kind.FRAME_ID to (FrameId.ID_BYTES to FrameId::fromBytes),
            Kind.NODE_ID to (NodeId.BYTES to NodeId::fromBytes),
            Kind.HASH to (CanonicalText.HASH_BYTES to CanonicalText::hashText),
            Kind.GROUP_ID to (CanonicalText.GROUP_ID_BYTES to CanonicalText::groupIdText),
            Kind.HEX8 to (CanonicalText.TAG_BYTES to CanonicalText::hex8Text),
            Kind.PUBKEY to (CanonicalText.BUNDLE_RAW_BYTES to CanonicalText::bundleText),
        )

    private const val MILLIS_BYTES = 6

    /** A clock rides as 6 raw bytes only when its canonical form is the 9-byte one (≥ 2^32) and it fits 48 bits. */
    private const val MILLIS_MIN = 1L shl 32
    private const val MILLIS_MAX = 1L shl (MILLIS_BYTES * Byte.SIZE_BITS)

    /** The largest label any scope uses; a compact key past it cannot be this schema. */
    private const val MAX_LABEL = 23

    /** Refuses the frame from inside a walk; caught by [transcode] / [rebuild]. Stackless — it is control flow. */
    private class Refuse : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Entry(
        val keyStart: Int,
        val keyEnd: Int,
        val valueEnd: Int,
    )

    /** One walk over one source buffer: the scanning both directions share. */
    @Suppress("AbstractClassCanBeConcreteClass") // a base for the two walks, never a walk of its own
    private abstract class Walk(
        protected val src: ByteArray,
    ) {
        /** The frame type once the RelayEnvelope's `type` has been walked — what selects the payload scope. */
        protected var type: String? = null

        protected fun header(pos: Int): CborItems.Header = CborItems.header(src, pos) ?: throw Refuse()

        protected fun end(pos: Int): Int = CborItems.itemEnd(src, pos) ?: throw Refuse()

        protected fun raw(
            start: Int,
            end: Int,
            out: ByteArrayOutputStream,
        ) = out.write(src, start, end - start)

        protected fun text(
            h: CborItems.Header,
            end: Int,
        ): String = String(src, h.contentStart, end - h.contentStart, Charsets.UTF_8)

        /**
         * The entries of the map headed by [h], in order; the map ends at the last entry's value end. A count of 2^63 or
         * more reads negative, so it is refused here before `toInt()` can turn it into a huge list.
         */
        protected fun entries(h: CborItems.Header): List<Entry> {
            if (h.major != MAJOR_MAP || h.arg < 0 || h.arg > src.size - h.contentStart) throw Refuse()
            var p = h.contentStart
            return List(h.arg.toInt()) {
                val keyEnd = end(p)
                val valueEnd = end(keyEnd)
                Entry(p, keyEnd, valueEnd).also { p = valueEnd }
            }
        }

        protected fun mapEnd(
            h: CborItems.Header,
            entries: List<Entry>,
        ): Int = entries.lastOrNull()?.valueEnd ?: h.contentStart

        /** Iterates the elements of the definite array headed by [h] (which the caller has bounded by [end]). */
        protected fun elements(
            h: CborItems.Header,
            end: Int,
            each: (start: Int, end: Int) -> Unit,
        ) {
            if (h.arg < 0 || h.arg > src.size - h.contentStart) throw Refuse()
            var p = h.contentStart
            repeat(h.arg.toInt()) {
                val e = end(p)
                each(p, e)
                p = e
            }
            if (p != end) throw Refuse()
        }

        protected fun writeText(
            s: String,
            out: ByteArrayOutputStream,
        ) {
            val utf8 = s.encodeToByteArray()
            CborItems.writeHeader(out, MAJOR_TSTR, utf8.size.toLong())
            out.write(utf8)
        }

        protected fun writeBytes(
            bytes: ByteArray,
            out: ByteArrayOutputStream,
        ) {
            CborItems.writeHeader(out, MAJOR_BSTR, bytes.size.toLong())
            out.write(bytes)
        }
    }

    /** Canonical → compact. */
    private class Transcode(
        src: ByteArray,
    ) : Walk(src) {
        fun root(): ByteArray {
            val out = ByteArrayOutputStream(src.size)
            if (map(RELAY, 0, out, depth = 0) != src.size) throw Refuse()
            return out.toByteArray()
        }

        /** Rewrites the map at [pos] under [scope] into [out]; returns the map's end. */
        private fun map(
            scope: Scope,
            pos: Int,
            out: ByteArrayOutputStream,
            depth: Int,
        ): Int {
            if (depth > CborItems.MAX_DEPTH) throw Refuse()
            val h = header(pos)
            val entries = entries(h)
            val fields = entries.map { fieldOf(scope, it) }
            val mayElide = fields.none { it == null } // an unknown key means the scope is rebuilt verbatim, nothing elided
            val body = ByteArrayOutputStream()
            var count = 0
            entries.forEachIndexed { i, e ->
                val f = fields[i]
                when {
                    f == null -> {
                        raw(e.keyStart, e.valueEnd, body)
                        count++
                    }

                    f.elideEmpty && mayElide && isEmpty(e) -> {
                        // Elided: an empty field the whole scope may drop, so nothing goes on the wire.
                    }

                    else -> {
                        CborItems.writeHeader(body, MAJOR_UINT, f.label.toLong())
                        value(f, e.keyEnd, e.valueEnd, body, depth)
                        count++
                    }
                }
            }
            CborItems.writeHeader(out, MAJOR_MAP, count.toLong())
            body.writeTo(out)
            return mapEnd(h, entries)
        }

        private fun fieldOf(
            scope: Scope,
            e: Entry,
        ): Field? {
            val kh = header(e.keyStart)
            return if (kh.major == MAJOR_TSTR) scope.byKey[text(kh, e.keyEnd)] else null
        }

        private fun isEmpty(e: Entry): Boolean {
            val vh = header(e.keyEnd)
            return vh.arg == 0L && (vh.major == MAJOR_BSTR || vh.major == MAJOR_ARRAY)
        }

        private fun value(
            f: Field,
            start: Int,
            end: Int,
            out: ByteArrayOutputStream,
            depth: Int,
        ) {
            val h = header(start)
            if (!f.list || h.major != MAJOR_ARRAY) {
                leaf(f, h, start, end, out, depth)
                return
            }
            CborItems.writeHeader(out, MAJOR_ARRAY, h.arg)
            elements(h, end) { s, e -> leaf(f, header(s), s, e, out, depth + 1) }
        }

        private fun leaf(
            f: Field,
            h: CborItems.Header,
            start: Int,
            end: Int,
            out: ByteArrayOutputStream,
            depth: Int,
        ) {
            when (f.kind) {
                Kind.PASS -> raw(start, end, out)
                Kind.NESTED -> if (h.major == MAJOR_MAP) map(checkNotNull(f.nested), start, out, depth + 1) else raw(start, end, out)
                Kind.PAYLOAD -> payload(h, start, end, out, depth)
                Kind.FRAME_TYPE -> frameType(h, start, end, out)
                Kind.MILLIS -> millis(h, start, end, out)
                else -> textLeaf(f.kind, h, start, end, out)
            }
        }

        private fun textLeaf(
            kind: Kind,
            h: CborItems.Header,
            start: Int,
            end: Int,
            out: ByteArrayOutputStream,
        ) {
            val bytes = if (h.major == MAJOR_TSTR) TEXT_TO_BYTES.getValue(kind)(text(h, end)) else null
            if (bytes == null) raw(start, end, out) else writeBytes(bytes, out)
        }

        private fun frameType(
            h: CborItems.Header,
            start: Int,
            end: Int,
            out: ByteArrayOutputStream,
        ) {
            val name = if (h.major == MAJOR_TSTR) text(h, end) else null
            val code = name?.let { TYPE_CODES[it] }
            if (code == null) {
                raw(start, end, out)
            } else {
                type = name
                CborItems.writeHeader(out, MAJOR_UINT, code)
            }
        }

        private fun millis(
            h: CborItems.Header,
            start: Int,
            end: Int,
            out: ByteArrayOutputStream,
        ) {
            val compactable = h.major == MAJOR_UINT && h.info == CborItems.INFO_EIGHT_BYTES && h.arg in MILLIS_MIN until MILLIS_MAX
            if (compactable) {
                CborItems.writeHeader(out, MAJOR_BSTR, MILLIS_BYTES.toLong())
                CborItems.writeBigEndian(out, h.arg, MILLIS_BYTES)
            } else {
                raw(start, end, out)
            }
        }

        /** Inlines the byte-string payload as its content map when the type is known and the bytes are exactly one map. */
        private fun payload(
            h: CborItems.Header,
            start: Int,
            end: Int,
            out: ByteArrayOutputStream,
            depth: Int,
        ) {
            val scope = type?.let { CONTENT_SCOPES[it] }
            val inner = if (h.major == MAJOR_BSTR && h.arg > 0 && scope != null) CborItems.header(src, h.contentStart) else null
            val inlinable = inner != null && inner.major == MAJOR_MAP && CborItems.itemEnd(src, h.contentStart) == end
            val tmp = ByteArrayOutputStream()
            val inlined =
                inlinable &&
                    try {
                        map(checkNotNull(scope), h.contentStart, tmp, depth + 1)
                        true
                    } catch (_: Refuse) {
                        false // a payload this cannot rewrite rides as its opaque byte string, like an unknown type's
                    }
            if (inlined) tmp.writeTo(out) else raw(start, end, out)
        }
    }

    /** Compact → canonical. */
    private class Rebuild(
        src: ByteArray,
    ) : Walk(src) {
        fun root(): ByteArray {
            val out = ByteArrayOutputStream(src.size * 2)
            if (map(RELAY, 0, out, depth = 0) != src.size) throw Refuse()
            return out.toByteArray()
        }

        /** Rebuilds the compact map at [pos] under [scope] into [out]; returns the map's end. */
        private fun map(
            scope: Scope,
            pos: Int,
            out: ByteArrayOutputStream,
            depth: Int,
        ): Int {
            if (depth > CborItems.MAX_DEPTH) throw Refuse()
            val h = header(pos)
            val entries = entries(h)
            val keys = entries.map { header(it.keyStart) }
            // Elided defaults are only restored in a scope the transcoder could elide in: one with no unknown key.
            val pending = if (keys.any { it.major != MAJOR_UINT }) mutableListOf() else scope.elidable.toMutableList()
            val body = ByteArrayOutputStream()
            var count = 0
            entries.forEachIndexed { i, e ->
                count +=
                    when (keys[i].major) {
                        MAJOR_UINT -> {
                            known(scope, keys[i], e, pending, body, depth)
                        }

                        MAJOR_TSTR -> {
                            raw(e.keyStart, e.valueEnd, body)
                            1
                        }

                        else -> {
                            throw Refuse()
                        }
                    }
            }
            while (pending.isNotEmpty()) {
                default(pending.removeAt(0), body)
                count++
            }
            CborItems.writeHeader(out, MAJOR_MAP, count.toLong())
            body.writeTo(out)
            return mapEnd(h, entries)
        }

        /** Emits one labeled entry, first restoring any elided default declared before it; returns the entries written. */
        private fun known(
            scope: Scope,
            key: CborItems.Header,
            e: Entry,
            pending: MutableList<Field>,
            out: ByteArrayOutputStream,
            depth: Int,
        ): Int {
            if (key.arg > MAX_LABEL) throw Refuse()
            val f = scope.byLabel[key.arg.toInt()] ?: throw Refuse()
            var written = 0
            while (pending.isNotEmpty() && scope.indexOf(pending.first()) < scope.indexOf(f)) {
                default(pending.removeAt(0), out)
                written++
            }
            pending.remove(f)
            writeText(f.key, out)
            value(f, e.keyEnd, e.valueEnd, out, depth)
            return written + 1
        }

        /** The canonical empty value of an elided field: `nonce` is an empty byte string, `keys` an empty array. */
        private fun default(
            f: Field,
            out: ByteArrayOutputStream,
        ) {
            writeText(f.key, out)
            CborItems.writeHeader(out, if (f.list) MAJOR_ARRAY else MAJOR_BSTR, 0)
        }

        private fun value(
            f: Field,
            start: Int,
            end: Int,
            out: ByteArrayOutputStream,
            depth: Int,
        ) {
            val h = header(start)
            if (!f.list || h.major != MAJOR_ARRAY) {
                leaf(f, h, start, end, out, depth)
                return
            }
            CborItems.writeHeader(out, MAJOR_ARRAY, h.arg)
            elements(h, end) { s, e -> leaf(f, header(s), s, e, out, depth + 1) }
        }

        private fun leaf(
            f: Field,
            h: CborItems.Header,
            start: Int,
            end: Int,
            out: ByteArrayOutputStream,
            depth: Int,
        ) {
            when (f.kind) {
                Kind.PASS -> raw(start, end, out)
                Kind.NESTED -> if (h.major == MAJOR_MAP) map(checkNotNull(f.nested), start, out, depth + 1) else raw(start, end, out)
                Kind.PAYLOAD -> payload(h, start, end, out, depth)
                Kind.FRAME_TYPE -> frameType(h, start, end, out)
                Kind.MILLIS -> millis(h, start, end, out)
                else -> textLeaf(f.kind, h, start, end, out)
            }
        }

        private fun textLeaf(
            kind: Kind,
            h: CborItems.Header,
            start: Int,
            end: Int,
            out: ByteArrayOutputStream,
        ) {
            if (h.major != MAJOR_BSTR) {
                raw(start, end, out) // the passthrough form: the transcoder left the text as it was
                return
            }
            val (size, toText) = BYTES_TO_TEXT.getValue(kind)
            if (h.arg != size.toLong()) throw Refuse()
            writeText(toText(src.copyOfRange(h.contentStart, end)), out)
        }

        private fun frameType(
            h: CborItems.Header,
            start: Int,
            end: Int,
            out: ByteArrayOutputStream,
        ) {
            if (h.major != MAJOR_UINT) {
                raw(start, end, out)
                return
            }
            val name = TYPE_NAMES[h.arg] ?: throw Refuse()
            type = name
            writeText(name, out)
        }

        private fun millis(
            h: CborItems.Header,
            start: Int,
            end: Int,
            out: ByteArrayOutputStream,
        ) {
            if (h.major != MAJOR_BSTR) {
                raw(start, end, out)
                return
            }
            if (h.arg != MILLIS_BYTES.toLong()) throw Refuse()
            CborItems.writeHeader(out, MAJOR_UINT, CborItems.readBigEndian(src, h.contentStart, MILLIS_BYTES))
        }

        /** An inlined payload map is rebuilt under the frame type's scope and wrapped back into its byte string. */
        private fun payload(
            h: CborItems.Header,
            start: Int,
            end: Int,
            out: ByteArrayOutputStream,
            depth: Int,
        ) {
            if (h.major != MAJOR_MAP) {
                raw(start, end, out)
                return
            }
            val scope = type?.let { CONTENT_SCOPES[it] } ?: throw Refuse()
            val tmp = ByteArrayOutputStream()
            map(scope, start, tmp, depth + 1)
            CborItems.writeHeader(out, MAJOR_BSTR, tmp.size().toLong())
            tmp.writeTo(out)
        }
    }
}
