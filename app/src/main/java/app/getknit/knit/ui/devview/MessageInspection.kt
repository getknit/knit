package app.getknit.knit.ui.devview

import app.getknit.knit.data.blob.AttachmentHolderEntity
import app.getknit.knit.data.blob.BlobVerdictEntity
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.hiddenByFilter
import app.getknit.knit.data.receipt.MessageReceiptEntity
import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.ui.devview.InspectFormat.ABSENT
import app.getknit.knit.ui.devview.InspectFormat.bytes
import app.getknit.knit.ui.devview.InspectFormat.delta
import app.getknit.knit.ui.devview.InspectFormat.span
import app.getknit.knit.ui.devview.InspectFormat.stamp
import app.getknit.knit.ui.devview.InspectFormat.yesNo
import java.util.Locale

/**
 * What the message inspector reads about one message, gathered by [MessageInspectViewModel]. Everything but the
 * [message] row is optional, and an absent value means this phone does not hold it.
 */
data class InspectFacts(
    val message: MessageEntity,
    // This phone's node id, to tell our sends from what reached us; null until the identity is read.
    val selfNodeId: String? = null,
    val receipts: List<MessageReceiptEntity> = emptyList(),
    // The frame's live custody row, or null when custody does not hold it now.
    val custody: ForwardEntity? = null,
    // The attachment's bytes held here, or null when they are not.
    val blobSize: Long? = null,
    // The attachment streaming in on a live link now, if it is.
    val arriving: ArrivingFile? = null,
    val verdict: BlobVerdictEntity? = null,
    val holders: List<AttachmentHolderEntity> = emptyList(),
    // How many carried frames pin the attachment's hash against the GC.
    val custodyPins: Int = 0,
    // The reader's Content filtering switch, which [MessageEntity.hiddenByFilter] reads.
    val filterOn: Boolean = true,
    val now: Long,
)

/**
 * The message inspector's rows (ADR 2026-10.s7wg): what this phone holds about one message, raw, in five groups. Pure,
 * so every row is pinned by `MessageInspectionTest`.
 *
 * It shows a fact as stored and an absent one as [ABSENT], never inferred. It decodes a custodied frame's envelope
 * header and touches no key. It never shows the attachment key (only whether there is one), a ratchet header's `ek`,
 * a nonce or ciphertext bytes, and it never repeats the body, which the screen shows above it.
 */
object MessageInspection {
    fun groups(facts: InspectFacts): List<InspectGroup> =
        listOfNotNull(identity(facts), delivery(facts), custody(facts), attachment(facts), checks(facts))

    private fun identity(facts: InspectFacts): InspectGroup {
        val m = facts.message
        return InspectGroup(
            "identity",
            buildList {
                add(InspectRow("messages.id", m.id))
                add(InspectRow("conversationId", "${m.conversationId} (${Conversations.kindFor(m.conversationId)})"))
                add(InspectRow("senderId", m.senderId))
                add(InspectRow("recipientId", m.recipientId ?: ABSENT))
                add(InspectRow("kind", CodeNames.kindName(m.kind)))
                add(InspectRow("sentAt (author's clock)", stamp(m.sentAt)))
                add(InspectRow("arrivedAt (our clock)", stamp(m.arrivedAt)))
                m.arrivedAt?.let { add(InspectRow("arrivedAt - sentAt", "${delta(it - m.sentAt)} (clock difference + transit)")) }
            },
        )
    }

    private fun delivery(facts: InspectFacts): InspectGroup {
        val m = facts.message
        val mine = facts.selfNodeId != null && m.senderId == facts.selfNodeId
        // For our own send the plane is the first receipt's, and means nothing until one came back; for a message that
        // reached us it is the radio of the hop that handed it over, never the path.
        val via =
            when {
                mine && !m.received -> ABSENT
                mine -> "${plane(m.receivedVia)} · first receipt"
                else -> "${plane(m.receivedVia)} · last hop"
            }
        return InspectGroup(
            "delivery",
            buildList {
                add(InspectRow("received", yesNo(m.received)))
                add(InspectRow("receivedVia", via))
                add(InspectRow("pendingKey", yesNo(m.pendingKey)))
                if (facts.receipts.isEmpty()) {
                    add(InspectRow("message_receipts", "none"))
                } else {
                    facts.receipts.forEach { add(InspectRow("receipt ${it.ackerNodeId}", "${stamp(it.notedAt)} · ${plane(it.via)}")) }
                }
            },
        )
    }

    private fun custody(facts: InspectFacts): InspectGroup {
        val row = facts.custody ?: return InspectGroup("custody", listOf(InspectRow("forward_store", "not held (live)")))
        return InspectGroup(
            "custody",
            buildList {
                add(InspectRow("origin", if (row.origin == ForwardStore.ORIGIN_SELF) "self" else "relay"))
                add(InspectRow("type", row.type))
                add(InspectRow("receivedAt", stamp(row.receivedAt)))
                add(InspectRow("expiresAt", "${stamp(row.expiresAt)} · ${span(row.expiresAt - facts.now)} left"))
                add(InspectRow("signed", bytes(row.signed.size)))
                add(InspectRow("sig", if (row.sig.isEmpty()) "none (unsigned)" else bytes(row.sig.size)))
                addAll(envelopeRows(row.signed))
            },
        )
    }

    /** The header a custodied frame carries, decoded from its signed bytes. A decode only: no key, no session. */
    private fun envelopeRows(signed: ByteArray): List<InspectRow> {
        val env = WireCodec.decodeEnvelope(signed) ?: return listOf(InspectRow("envelope", "undecodable"))
        val rows = mutableListOf(InspectRow("envelope.payload", bytes(env.payload.size)))
        if (env.type != FrameType.CHAT) return rows
        val chat = WireCodec.decodePayload<ChatContent>(env.payload) ?: return rows + InspectRow("chat", "undecodable")
        val enc = chat.enc ?: return rows + InspectRow("enc", "none (plaintext)")
        rows += InspectRow("enc.v", "${enc.v} (${formOf(env, enc)})")
        rows += InspectRow("enc.ct", bytes(enc.ct.size))
        enc.r?.let { rows += InspectRow("enc.r", ratchetHeader(it)) }
        enc.g?.let { rows += InspectRow("enc.g", "se=${it.se} n=${it.n}") }
        if (enc.keys.isNotEmpty()) rows += InspectRow("enc.keys", "${enc.keys.size} wrapped")
        return rows
    }

    private fun formOf(
        env: RelayEnvelope,
        enc: EncEnvelope,
    ): String =
        when {
            env.group != null && enc.g != null -> "group sender key"
            enc.v == EncEnvelope.VERSION_DM_V3 -> "compact DM ratchet"
            enc.r != null -> "DM ratchet"
            else -> "wrapped keys"
        }

    private fun ratchetHeader(r: RatchetHeader): String =
        buildString {
            append("se=${r.se} pe=${r.pe} n=${r.n}")
            if (r.init != null) append(" init")
            if (r.flags and RatchetHeader.FLAG_RESET != 0) append(" reset")
        }

    private fun attachment(facts: InspectFacts): InspectGroup? {
        val hash = facts.message.attachmentHash ?: return null
        return InspectGroup("attachment", declared(facts.message, hash) + held(facts))
    }

    /** What the message says about its attachment. */
    private fun declared(
        m: MessageEntity,
        hash: String,
    ): List<InspectRow> =
        buildList {
            add(InspectRow("attachmentHash", hash))
            add(InspectRow("attachmentPlainHash", m.attachmentPlainHash ?: ABSENT))
            add(InspectRow("attachmentSourceHash", m.attachmentSourceHash ?: ABSENT))
            // Whether it is sealed, and never the key itself.
            add(InspectRow("keyed", yesNo(m.attachmentKey != null)))
            add(InspectRow("attachmentMime", m.attachmentMime ?: ABSENT))
            add(InspectRow("attachmentName", m.attachmentName ?: ABSENT))
            add(InspectRow("attachmentSize (declared)", m.attachmentSize?.let(::bytes) ?: ABSENT))
            add(InspectRow("animated", yesNo(m.attachmentAnimated)))
            m.videoDurationMs?.let { add(InspectRow("video", "${span(it)} · ${m.videoWidth ?: "?"}×${m.videoHeight ?: "?"}")) }
            m.videoPoster?.let { add(InspectRow("videoPoster", "held · flagged ${yesNo(m.videoPosterFlagged)}")) }
            m.voiceDurationMs?.let { add(InspectRow("voiceDurationMs", span(it.toLong()))) }
            add(InspectRow("fetchApproved", yesNo(m.attachmentFetchApproved)))
        }

    /** What this phone holds of the attachment, and who it knows holds it. */
    private fun held(facts: InspectFacts): List<InspectRow> =
        buildList {
            add(InspectRow("blobs (held here)", facts.blobSize?.let(::bytes) ?: "not held"))
            add(InspectRow("arriving", facts.arriving?.let { InspectFormat.progress(it.bytes, it.total) } ?: ABSENT))
            add(InspectRow("blob_verdicts", facts.verdict?.let(::verdict) ?: "not screened"))
            if (facts.holders.isEmpty()) {
                add(InspectRow("attachment_holders", "none"))
            } else {
                facts.holders.forEach { add(InspectRow("holder ${it.holderId}", "${plane(it.plane)} · ${stamp(it.provedAt)}")) }
            }
            add(InspectRow("forward_store pins", facts.custodyPins.toString()))
        }

    private fun checks(facts: InspectFacts): InspectGroup {
        val m = facts.message
        return InspectGroup(
            "checks",
            buildList {
                add(InspectRow("moderation", CodeNames.moderationName(m.moderation)))
                add(InspectRow("hiddenByFilter", yesNo(m.hiddenByFilter(facts.filterOn))))
                m.replyToId?.let { reply ->
                    add(InspectRow("replyToId", reply))
                    add(InspectRow("replyCheck", CodeNames.replyCheckName(m.replyCheck)))
                    add(InspectRow("replyToFlagged", yesNo(m.replyToFlagged)))
                }
                add(InspectRow("styles", m.styles ?: ABSENT))
                add(InspectRow("mentions", m.mentions.takeUnless { it == EMPTY_JSON_LIST } ?: ABSENT))
                m.originNode?.let { node ->
                    add(InspectRow("originNode", String.format(Locale.ROOT, "!%08x", node)))
                    add(InspectRow("originHops", m.originHops?.toString() ?: ABSENT))
                    add(InspectRow("originSnr", m.originSnrDeci?.let { String.format(Locale.ROOT, "%.1f dB", it / DECI) } ?: ABSENT))
                    add(InspectRow("originViaMqtt", yesNo(m.originViaMqtt)))
                    add(InspectRow("originSigned", CodeNames.originSignedName(m.originSigned)))
                    add(InspectRow("originChannel", m.originChannel ?: ABSENT))
                    add(InspectRow("originPeerId", m.originPeerId ?: ABSENT))
                }
            },
        )
    }

    /** A stored plane code by name. A row from before ADR 2026-10.e42d says it never recorded the radio. */
    fun plane(code: Int): String =
        when (val plane = DeliveryPlane.fromCode(code)) {
            DeliveryPlane.Nearby -> "Nearby (radio not recorded)"
            DeliveryPlane.Unknown -> "Unknown ($code)"
            else -> plane.name
        }

    private fun verdict(v: BlobVerdictEntity): String =
        String.format(Locale.ROOT, "%s %.3f", if (v.flagged) "flagged" else "clean", v.score)

    private const val EMPTY_JSON_LIST = "[]"
    private const val DECI = 10.0
}

/** The stored codes [MessageInspection] shows, by the name the code gives them. */
private object CodeNames {
    fun kindName(kind: Int): String =
        when (kind) {
            MessageEntity.KIND_NORMAL -> "NORMAL"
            MessageEntity.KIND_MEMBER_LEFT -> "MEMBER_LEFT"
            MessageEntity.KIND_PEER_RENAMED -> "PEER_RENAMED"
            MessageEntity.KIND_PEER_AVATAR -> "PEER_AVATAR"
            MessageEntity.KIND_GROUP_RENAMED -> "GROUP_RENAMED"
            MessageEntity.KIND_GROUP_PHOTO -> "GROUP_PHOTO"
            MessageEntity.KIND_GROUP_CREATED -> "GROUP_CREATED"
            MessageEntity.KIND_KEY_PIN_REFUSED -> "KEY_PIN_REFUSED"
            MessageEntity.KIND_FILE_TRANSFER -> "FILE_TRANSFER"
            MessageEntity.KIND_MEMBER_REJOINED -> "MEMBER_REJOINED"
            MessageEntity.KIND_CALL -> "CALL"
            else -> "unknown ($kind)"
        }

    fun moderationName(code: Int): String =
        when (code) {
            MessageEntity.MODERATION_NONE -> "none"
            MessageEntity.MODERATION_TEXT_FLAGGED -> "text flagged"
            else -> "unknown ($code)"
        }

    fun replyCheckName(code: Int): String =
        when (code) {
            MessageEntity.REPLY_UNCHECKED -> "unchecked"
            MessageEntity.REPLY_VERIFIED -> "verified"
            MessageEntity.REPLY_MISMATCH -> "mismatch"
            else -> "unknown ($code)"
        }

    fun originSignedName(code: Int): String =
        when (code) {
            MessageEntity.ORIGIN_UNSIGNED -> "unsigned"
            MessageEntity.ORIGIN_SIGNED_BY_BOARD -> "signed by board"
            MessageEntity.ORIGIN_SIGNED_BY_CONTACT -> "signed by contact"
            MessageEntity.ORIGIN_SIGNATURE_MISMATCH -> "signature mismatch"
            else -> "unknown ($code)"
        }
}
