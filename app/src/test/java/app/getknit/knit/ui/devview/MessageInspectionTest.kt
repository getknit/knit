package app.getknit.knit.ui.devview

import app.getknit.knit.data.blob.AttachmentHolderEntity
import app.getknit.knit.data.blob.BlobVerdictEntity
import app.getknit.knit.data.forward.ForwardEntity
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.receipt.MessageReceiptEntity
import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.GroupRatchetHeader
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RatchetInit
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The message inspector's rows (ADR 2026-10.s7wg): each group as stored, an absent fact as an absent mark and never a
 * guess, a custodied frame's header decoded from its bytes with no key material shown, and the attachment key never
 * shown at all.
 */
class MessageInspectionTest {
    private fun inspect(facts: InspectFacts): Map<String, Map<String, String>> =
        MessageInspection.groups(facts).associate { group -> group.title to group.rows.associate { it.key to it.value } }

    private fun inbound(
        receivedVia: Int = DeliveryPlane.Bluetooth.code,
        arrivedAt: Long? = NOW - 60_000L,
    ) = MessageEntity(
        id = ID,
        senderId = PEER,
        recipientId = ME,
        conversationId = PEER,
        body = "hello",
        sentAt = NOW - 300_000L,
        receivedVia = receivedVia,
        arrivedAt = arrivedAt,
    )

    @Test
    fun anInboundRowReadsAsStoredAndTheGapIsLabelledAsClockDifferencePlusTransit() {
        val identity = inspect(InspectFacts(inbound(), selfNodeId = ME, now = NOW)).getValue("identity")

        assertEquals(ID, identity["messages.id"])
        assertEquals("$PEER (DM)", identity["conversationId"])
        assertEquals(PEER, identity["senderId"])
        assertEquals(ME, identity["recipientId"])
        assertEquals("NORMAL", identity["kind"])
        assertEquals(InspectFormat.stamp(NOW - 300_000L), identity["sentAt (author's clock)"])
        assertEquals(InspectFormat.stamp(NOW - 60_000L), identity["arrivedAt (our clock)"])
        assertEquals("+4m 00s (clock difference + transit)", identity["arrivedAt - sentAt"])
    }

    @Test
    fun anAbsentFactShowsAsTheAbsentMarkAndIsNeverInferred() {
        val row = inbound(arrivedAt = null).copy(recipientId = null)

        val groups = inspect(InspectFacts(row, selfNodeId = ME, now = NOW))

        assertEquals(InspectFormat.ABSENT, groups.getValue("identity")["recipientId"])
        assertEquals(InspectFormat.ABSENT, groups.getValue("identity")["arrivedAt (our clock)"])
        assertNull("no gap without an arrival", groups.getValue("identity")["arrivedAt - sentAt"])
        assertEquals(mapOf("forward_store" to "not held (live)"), groups.getValue("custody"))
        assertNull("no attachment, no group", groups["attachment"])
        assertEquals("none", groups.getValue("delivery")["message_receipts"])
    }

    @Test
    fun theArrivalRadioIsNamedAndARowFromBeforeSaysItWasNeverRecorded() {
        fun via(row: MessageEntity) = inspect(InspectFacts(row, selfNodeId = ME, now = NOW)).getValue("delivery")["receivedVia"]

        assertEquals("Bluetooth · last hop", via(inbound(DeliveryPlane.Bluetooth.code)))
        assertEquals("WifiAware · last hop", via(inbound(DeliveryPlane.WifiAware.code)))
        assertEquals("Nearby (radio not recorded) · last hop", via(inbound(DeliveryPlane.Nearby.code)))
        assertEquals("Unknown (9) · last hop", via(inbound(9)))

        // Our own send: no plane until a receipt came back, then the plane that receipt crossed.
        val mine = inbound(DeliveryPlane.Unknown.code).copy(senderId = ME, recipientId = PEER)
        assertEquals(InspectFormat.ABSENT, via(mine))
        assertEquals("WifiAware · first receipt", via(mine.copy(received = true, receivedVia = DeliveryPlane.WifiAware.code)))
    }

    @Test
    fun everyReceiptIsListedAsStoredWithItsPlane() {
        val receipts =
            listOf(
                MessageReceiptEntity(ID, PEER, NOW - 2_000L, DeliveryPlane.Bluetooth.code),
                MessageReceiptEntity(ID, OTHER, NOW - 1_000L, DeliveryPlane.Internet.code),
            )

        val delivery = inspect(InspectFacts(inbound(), selfNodeId = ME, receipts = receipts, now = NOW)).getValue("delivery")

        assertEquals("${InspectFormat.stamp(NOW - 2_000L)} · Bluetooth", delivery["receipt $PEER"])
        assertEquals("${InspectFormat.stamp(NOW - 1_000L)} · Internet", delivery["receipt $OTHER"])
        assertNull(delivery["message_receipts"])
    }

    @Test
    fun aCustodiedV3DmShowsItsHeaderCountersAndSizesButNoKeyBytes() {
        val ek = ByteArray(32) { 0x5A }
        val enc =
            EncEnvelope(
                v = EncEnvelope.VERSION_DM_V3,
                nonce = ByteArray(0),
                ct = ByteArray(212),
                keys = emptyList(),
                r = RatchetHeader(se = 4, ek = ek, pe = 3, n = 17, init = RatchetInit(eph = ByteArray(32) { 0x6B }, pkid = 2, at = 9L)),
            )
        val signed = signedChat(enc, recipientId = ME, group = null)
        val custody = custodyRow(signed, sig = ByteArray(64))

        val rows = inspect(InspectFacts(inbound(), selfNodeId = ME, custody = custody, now = NOW)).getValue("custody")

        assertEquals("relay", rows["origin"])
        assertEquals(FrameType.CHAT, rows["type"])
        assertEquals("${InspectFormat.stamp(NOW + 3_600_000L)} · 1h 00m left", rows["expiresAt"])
        assertEquals(InspectFormat.bytes(signed.size), rows["signed"])
        assertEquals("64 B", rows["sig"])
        assertEquals("3 (compact DM ratchet)", rows["enc.v"])
        assertEquals("212 B", rows["enc.ct"])
        assertEquals("se=4 pe=3 n=17 init", rows["enc.r"])
        // The header's keys are public, and still not shown: nothing but counters and sizes.
        val hexEk = ek.joinToString("") { "%02x".format(it) }
        assertFalse(rows.values.any { hexEk in it || "5a5a5a" in it || "WlpaWl" in it })
    }

    @Test
    fun aCustodiedGroupFrameShowsItsSenderKeyHeader() {
        val enc =
            EncEnvelope(
                v = EncEnvelope.VERSION_RATCHET,
                nonce = ByteArray(12),
                ct = ByteArray(40),
                keys = emptyList(),
                g = GroupRatchetHeader(se = 2, n = 9),
            )
        val group = GroupInfo(id = "g-trail", members = listOf(ME, PEER), createdBy = PEER)
        val custody = custodyRow(signedChat(enc, recipientId = null, group = group), sig = ByteArray(64))

        val rows = inspect(InspectFacts(inbound(), selfNodeId = ME, custody = custody, now = NOW)).getValue("custody")

        assertEquals("2 (group sender key)", rows["enc.v"])
        assertEquals("se=2 n=9", rows["enc.g"])
        assertNull(rows["enc.r"])
    }

    @Test
    fun anUnsignedFrameAndUndecodableBytesShowWhatTheyAreAndNoMore() {
        val custody = custodyRow(byteArrayOf(1, 2, 3), sig = ByteArray(0)).copy(origin = ForwardStore.ORIGIN_SELF)

        val rows = inspect(InspectFacts(inbound(), selfNodeId = ME, custody = custody, now = NOW)).getValue("custody")

        assertEquals("self", rows["origin"])
        assertEquals("3 B", rows["signed"])
        assertEquals("none (unsigned)", rows["sig"])
        assertEquals("undecodable", rows["envelope"])
    }

    @Test
    fun theAttachmentKeyIsNeverShownOnlyWhetherThereIsOne() {
        val key = "c2VjcmV0LWNvbnRlbnQta2V5LWJ5dGVz"
        val row = inbound().copy(attachmentHash = HASH, attachmentKey = key, attachmentMime = "image/jpeg")

        val groups = MessageInspection.groups(InspectFacts(row, selfNodeId = ME, now = NOW))

        assertEquals(
            "yes",
            groups
                .single { it.title == "attachment" }
                .rows
                .single { it.key == "keyed" }
                .value,
        )
        assertFalse("the key leaked into a row", groups.flatMap { it.rows }.any { key in it.value || key in it.key })
    }

    @Test
    fun theAttachmentReadsItsHeldBytesProgressVerdictHoldersAndPins() {
        val row = inbound().copy(attachmentHash = HASH, attachmentSize = 2_048L, attachmentFetchApproved = true)
        val holders =
            listOf(
                AttachmentHolderEntity(HASH, ME, DeliveryPlane.WifiAware.code, NOW - 5_000L),
                AttachmentHolderEntity(HASH, PEER, DeliveryPlane.Nearby.code, NOW - 4_000L),
            )

        val arriving =
            inspect(
                InspectFacts(
                    row,
                    selfNodeId = ME,
                    arriving = ArrivingFile(HASH, 512, 2_048),
                    holders = holders,
                    custodyPins = 2,
                    now = NOW,
                ),
            ).getValue("attachment")
        assertEquals("not held", arriving["blobs (held here)"])
        assertEquals("512 / 2048 B (25 %)", arriving["arriving"])
        assertEquals("not screened", arriving["blob_verdicts"])
        assertEquals("WifiAware · ${InspectFormat.stamp(NOW - 5_000L)}", arriving["holder $ME"])
        assertEquals("Nearby (radio not recorded) · ${InspectFormat.stamp(NOW - 4_000L)}", arriving["holder $PEER"])
        assertEquals("yes", arriving["fetchApproved"])
        assertEquals("2", arriving["forward_store pins"])

        val held =
            inspect(InspectFacts(row, selfNodeId = ME, blobSize = 2_048L, verdict = BlobVerdictEntity(HASH, true, 0.9314f), now = NOW))
                .getValue("attachment")
        assertEquals("2048 B (2.0 KiB)", held["blobs (held here)"])
        assertEquals(InspectFormat.ABSENT, held["arriving"])
        assertEquals("flagged 0.931", held["blob_verdicts"])
        assertEquals("none", held["attachment_holders"])
    }

    @Test
    fun theChecksNameTheirCodesAndALoraPostCarriesItsOriginFields() {
        val row =
            inbound().copy(
                moderation = MessageEntity.MODERATION_TEXT_FLAGGED,
                replyToId = "q1",
                replyCheck = MessageEntity.REPLY_MISMATCH,
                styles = "[[0,5,1]]",
                originNode = 0xA1B2C3D4L,
                originHops = 2,
                originSnrDeci = -75,
                originSigned = MessageEntity.ORIGIN_SIGNED_BY_BOARD,
            )

        val shown = inspect(InspectFacts(row, selfNodeId = ME, filterOn = true, now = NOW)).getValue("checks")
        assertEquals("text flagged", shown["moderation"])
        assertEquals("yes", shown["hiddenByFilter"])
        assertEquals("mismatch", shown["replyCheck"])
        assertEquals("[[0,5,1]]", shown["styles"])
        assertEquals(InspectFormat.ABSENT, shown["mentions"])
        assertEquals("!a1b2c3d4", shown["originNode"])
        assertEquals("-7.5 dB", shown["originSnr"])
        assertEquals("signed by board", shown["originSigned"])

        // The reader's own Content filtering decides what is hidden, not the verdict alone.
        assertEquals("no", inspect(InspectFacts(row, selfNodeId = ME, filterOn = false, now = NOW)).getValue("checks")["hiddenByFilter"])
    }

    private fun signedChat(
        enc: EncEnvelope,
        recipientId: String?,
        group: GroupInfo?,
    ): ByteArray =
        WireCodec.encodeEnvelope(
            RelayEnvelope(
                type = FrameType.CHAT,
                id = ID,
                senderId = PEER,
                sentAt = NOW - 300_000L,
                recipientId = recipientId,
                group = group,
                payload = WireCodec.encodePayload(ChatContent(enc = enc)),
            ),
        )

    private fun custodyRow(
        signed: ByteArray,
        sig: ByteArray,
    ) = ForwardEntity(
        id = ID,
        recipientId = ME,
        groupId = null,
        senderId = PEER,
        type = FrameType.CHAT,
        origin = ForwardStore.ORIGIN_RELAY,
        signed = signed,
        sig = sig,
        sentAt = NOW - 300_000L,
        receivedAt = NOW - 60_000L,
        expiresAt = NOW + 3_600_000L,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val ID = "0b4f9c2e7a1d4e6b8c3f5a2d"
        const val ME = "me5fdtq2wvzk4hn7cxgae3bjrm"
        const val PEER = "samr1vz2kq7dw4mhgc5tyxpe3n"
        const val OTHER = "priy4nzk2q7dw4mhgc5tyxpe3n"
        const val HASH = "9f2c1e6a7b3d4c5e8f0a1b2c3d4e5f60718293a4b5c6d7e8f9a0b1c2d3e4f5a6"
    }
}
