package app.getknit.knit.mesh.link

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.link.CborItems.MAJOR_BSTR
import app.getknit.knit.mesh.link.CborItems.MAJOR_MAP
import app.getknit.knit.mesh.link.CborItems.MAJOR_SIMPLE
import app.getknit.knit.mesh.link.CborItems.MAJOR_TSTR
import app.getknit.knit.mesh.link.CborItems.MAJOR_UINT
import app.getknit.knit.mesh.protocol.CanonicalText
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import java.io.ByteArrayOutputStream

/**
 * A second pass over [FrameTranscoder]'s schema-1 body that leaves out the envelope ids a receiver can put back
 * itself, for a radio whose every packet counts (ADR 2026-10.jend's MeshCore board kind, 164 B a packet). Like the
 * transcoder it is transport-local: `signed` still arrives byte-for-byte and the originator's signature verifies over
 * it, the ids just do not travel. **Not wired**: no sender emits it yet ([FastFrameCodec.TAG_ELIDED]).
 *
 * Three substitutions, each in place of the id's own value, so the map keeps its entries and their order:
 *  - a `profile`'s **id** rides as CBOR `null`: it is `profile-<senderId>-<sentAt>` ([FrameId.profileId]), which every
 *    door already demands ([FrameId.admits]) — 50 bytes of text rebuilt from two fields the frame carries anyway;
 *  - a `profile`'s **senderId** rides as `null`: a node id is the hash of its key bundle ([NodeId.fromPublicKeyBundle]),
 *    and the profile carries the bundle;
 *  - any other frame's **senderId**, and a DM's **recipientId**, may ride as a [PREFIX_BYTES]-byte prefix that the
 *    receiver expands against the node ids it knows. The receiver must hold the sender's profile to verify the frame
 *    at all, and the recipient is usually itself. A prefix matching more than one id yields one candidate per match
 *    and the signature picks; one matching none yields nothing, and the frame is lost. That is why the caller decides
 *    per frame ([elide]'s flags): a recipient prefix only goes to the phone it names, never toward a carrier that may
 *    not know it.
 *
 * **Self-verifying**, like the transcoder: [elide] returns null unless [rebuild], given exactly the frame's own ids,
 * reproduces the canonical bytes as its one candidate.
 */
internal object IdElision {
    /** Bytes of a node id that ride in place of the whole id. */
    const val PREFIX_BYTES = 4

    /** Most canonical candidates [rebuild] produces; a frame whose prefixes match more is refused. */
    const val MAX_CANDIDATES = 4

    // Schema 1's RelayEnvelope and ProfileContent labels (FrameTranscoder, frozen; IdElisionTest pins them).
    private const val LABEL_TYPE = 1L
    private const val LABEL_ID = 2L
    private const val LABEL_SENDER = 3L
    private const val LABEL_SENT_AT = 4L
    private const val LABEL_RECIPIENT = 5L
    private const val LABEL_PAYLOAD = 7L
    private const val LABEL_PUBKEY = 4L

    /** CBOR `null` (major 7, simple value 22): the marker for an id the receiver derives. */
    private const val SIMPLE_NULL = 22L
    private const val MILLIS_BYTES = 6

    /**
     * The elided body for a canonical [signed] blob, or null when the transcoder cannot reproduce it or this pass
     * cannot rebuild what it left out. A `profile` always loses its derived id and sender; any other frame sends its
     * sender as a prefix when [prefixSender] and its recipient when [prefixRecipient].
     */
    fun elide(
        signed: ByteArray,
        prefixSender: Boolean,
        prefixRecipient: Boolean,
    ): ByteArray? {
        val compact = FrameTranscoder.transcode(signed) ?: return null
        val root = runCatching { Root.parse(compact) }.getOrNull() ?: return null
        val sender = root.bytes(LABEL_SENDER, NodeId.BYTES) ?: return null
        val recipient = root.bytes(LABEL_RECIPIENT, NodeId.BYTES)
        val swaps = mutableMapOf<Long, ByteArray>()
        if (root.isProfile()) {
            val sentAt = root.sentAt() ?: return null
            if (root.text(LABEL_ID) == FrameId.profileId(NodeId.fromBytes(sender), sentAt)) swaps[LABEL_ID] = NULL
            if (root.bundleSender()?.contentEquals(sender) == true) swaps[LABEL_SENDER] = NULL
        } else {
            if (prefixSender) swaps[LABEL_SENDER] = bstr(sender.copyOf(PREFIX_BYTES))
            if (prefixRecipient && recipient != null) swaps[LABEL_RECIPIENT] = bstr(recipient.copyOf(PREFIX_BYTES))
        }
        val out = root.write(swaps)
        val own = listOfNotNull(sender, recipient)
        val back = rebuild(out) { prefix -> own.filter { it.startsWith(prefix) } }
        return out.takeIf { back.size == 1 && back.single().contentEquals(signed) }
    }

    /**
     * Every canonical `signed` an elided [body] can stand for — one per combination of the ids [resolve] offers for
     * its prefixes, at most [MAX_CANDIDATES] — or empty when it is malformed or a prefix matches nothing. [resolve]
     * gets a [PREFIX_BYTES]-byte prefix and answers the full node ids it knows that start with it. The caller keeps
     * the candidate whose signature verifies; an elided body with no prefix yields at most one.
     */
    fun rebuild(
        body: ByteArray,
        resolve: (ByteArray) -> List<ByteArray>,
    ): List<ByteArray> {
        val root = runCatching { Root.parse(body) }.getOrNull() ?: return emptyList()
        return runCatching { candidates(root, resolve) }.getOrNull().orEmpty()
    }

    private fun candidates(
        root: Root,
        resolve: (ByteArray) -> List<ByteArray>,
    ): List<ByteArray> {
        val profile = root.isProfile()
        val senders =
            when {
                root.isNull(LABEL_SENDER) -> if (profile) listOfNotNull(root.bundleSender()) else emptyList()
                else -> ids(root, LABEL_SENDER, resolve) ?: return emptyList()
            }
        val recipients = if (root.has(LABEL_RECIPIENT)) ids(root, LABEL_RECIPIENT, resolve) ?: return emptyList() else listOf(null)
        if (senders.size * recipients.size > MAX_CANDIDATES) return emptyList()
        val idNull = root.isNull(LABEL_ID)
        if (idNull && !profile) return emptyList()
        val sentAt = if (idNull) root.sentAt() ?: return emptyList() else 0L
        return senders.flatMap { sender ->
            recipients.mapNotNull { recipient ->
                val swaps = mutableMapOf(LABEL_SENDER to bstr(sender))
                recipient?.let { swaps[LABEL_RECIPIENT] = bstr(it) }
                if (idNull) swaps[LABEL_ID] = tstr(FrameId.profileId(NodeId.fromBytes(sender), sentAt))
                FrameTranscoder.rebuild(root.write(swaps))
            }
        }
    }

    /** The ids an id slot stands for: itself when whole, [resolve]'s matches when a prefix, null when neither. */
    private fun ids(
        root: Root,
        label: Long,
        resolve: (ByteArray) -> List<ByteArray>,
    ): List<ByteArray>? {
        root.bytes(label, NodeId.BYTES)?.let { return listOf(it) }
        val prefix = root.bytes(label, PREFIX_BYTES) ?: return null
        val matches = resolve(prefix).filter { it.size == NodeId.BYTES && it.startsWith(prefix) }
        return matches.distinctBy { it.toList() }.takeIf { it.isNotEmpty() }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && copyOf(prefix.size).contentEquals(prefix)

    private val NULL = ByteArrayOutputStream().also { CborItems.writeHeader(it, MAJOR_SIMPLE, SIMPLE_NULL) }.toByteArray()

    private fun bstr(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .also {
                CborItems.writeHeader(it, MAJOR_BSTR, bytes.size.toLong())
                it.write(bytes)
            }.toByteArray()

    private fun tstr(s: String): ByteArray =
        ByteArrayOutputStream()
            .also {
                val utf8 = s.encodeToByteArray()
                CborItems.writeHeader(it, MAJOR_TSTR, utf8.size.toLong())
                it.write(utf8)
            }.toByteArray()

    /** The schema-1 root map, split into entries; a labelled entry is addressable, an unknown text key rides along. */
    private class Root(
        private val src: ByteArray,
        private val entries: List<Entry>,
    ) {
        class Entry(
            val label: Long?,
            val keyStart: Int,
            val valueStart: Int,
            val valueEnd: Int,
        )

        private fun entry(label: Long): Entry? = entries.firstOrNull { it.label == label }

        private fun value(label: Long): CborItems.Header? = entry(label)?.let { CborItems.header(src, it.valueStart) }

        fun has(label: Long): Boolean = entry(label) != null

        fun isNull(label: Long): Boolean = value(label)?.let { it.major == MAJOR_SIMPLE && it.arg == SIMPLE_NULL } == true

        fun isProfile(): Boolean = value(LABEL_TYPE)?.let { it.major == MAJOR_UINT && it.arg == PROFILE_CODE } == true

        /** The byte-string value under [label] when it is exactly [size] bytes. */
        fun bytes(
            label: Long,
            size: Int,
        ): ByteArray? {
            val h = value(label) ?: return null
            if (h.major != MAJOR_BSTR || h.arg != size.toLong()) return null
            return src.copyOfRange(h.contentStart, h.contentStart + size)
        }

        fun text(label: Long): String? {
            val e = entry(label) ?: return null
            val h = CborItems.header(src, e.valueStart) ?: return null
            if (h.major != MAJOR_TSTR) return null
            return String(src, h.contentStart, e.valueEnd - h.contentStart, Charsets.UTF_8)
        }

        /** `sentAt` in either schema-1 form: the 6-byte compact clock or the canonical integer it left alone. */
        fun sentAt(): Long? {
            val h = value(LABEL_SENT_AT) ?: return null
            return when (h.major) {
                MAJOR_BSTR if h.arg == MILLIS_BYTES.toLong() -> CborItems.readBigEndian(src, h.contentStart, MILLIS_BYTES)
                MAJOR_UINT -> h.arg
                else -> null
            }
        }

        /** The node id a profile's inlined key bundle derives to, or null when the payload carries no raw bundle. */
        fun bundleSender(): ByteArray? {
            val payload = entry(LABEL_PAYLOAD) ?: return null
            val h = CborItems.header(src, payload.valueStart) ?: return null
            if (h.major != MAJOR_MAP) return null
            var p = h.contentStart
            repeat(h.arg.toInt()) {
                val key = CborItems.header(src, p) ?: return null
                val keyEnd = CborItems.itemEnd(src, p) ?: return null
                val valueEnd = CborItems.itemEnd(src, keyEnd) ?: return null
                val v = CborItems.header(src, keyEnd) ?: return null
                val raw = key.major == MAJOR_UINT && key.arg == LABEL_PUBKEY && v.major == MAJOR_BSTR
                if (raw && v.arg == CanonicalText.BUNDLE_RAW_BYTES.toLong()) {
                    val bundle = CanonicalText.bundleText(src.copyOfRange(v.contentStart, valueEnd))
                    return NodeId.toBytesOrNull(NodeId.fromPublicKeyBundle(bundle))
                }
                p = valueEnd
            }
            return null
        }

        /** The map with each labelled value in [swaps] replaced by its encoded item, every other entry verbatim. */
        fun write(swaps: Map<Long, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream(src.size)
            CborItems.writeHeader(out, MAJOR_MAP, entries.size.toLong())
            entries.forEach { e ->
                val swap = e.label?.let { swaps[it] }
                if (swap == null) {
                    out.write(src, e.keyStart, e.valueEnd - e.keyStart)
                } else {
                    out.write(src, e.keyStart, e.valueStart - e.keyStart)
                    out.write(swap)
                }
            }
            return out.toByteArray()
        }

        companion object {
            private val PROFILE_CODE = checkNotNull(FrameTranscoder.typeCode(FrameType.PROFILE))

            fun parse(src: ByteArray): Root {
                val h = checkNotNull(CborItems.header(src, 0))
                check(h.major == MAJOR_MAP && h.arg <= src.size)
                var p = h.contentStart
                val entries =
                    List(h.arg.toInt()) {
                        val key = checkNotNull(CborItems.header(src, p))
                        val keyEnd = checkNotNull(CborItems.itemEnd(src, p))
                        val valueEnd = checkNotNull(CborItems.itemEnd(src, keyEnd))
                        Entry(if (key.major == MAJOR_UINT) key.arg else null, p, keyEnd, valueEnd).also { p = valueEnd }
                    }
                check(p == src.size)
                return Root(src, entries)
            }
        }
    }
}
