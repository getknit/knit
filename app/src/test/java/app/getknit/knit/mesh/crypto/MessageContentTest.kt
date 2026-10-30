package app.getknit.knit.mesh.crypto

import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.StyleStore
import app.getknit.knit.data.message.withReply
import app.getknit.knit.data.message.withVideo
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.DeclaredLangs
import app.getknit.knit.mesh.protocol.DirectInvite
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.GroupKeyPayload
import app.getknit.knit.mesh.protocol.GroupRootPayload
import app.getknit.knit.mesh.protocol.GroupSeed
import app.getknit.knit.mesh.protocol.LanguageRegistry
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ProfilePayload
import app.getknit.knit.mesh.protocol.ReactionPayload
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.ScopeConfigPayload
import app.getknit.knit.mesh.protocol.StyleKey
import app.getknit.knit.mesh.protocol.StyleSpan
import app.getknit.knit.mesh.protocol.StyleSpans
import app.getknit.knit.mesh.protocol.TransferPayload
import app.getknit.knit.mesh.protocol.VideoInfo
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.CborLabel
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plaintext content schema: CBOR round-trip and the delivery-time version gate (unknown → dropped). */
class MessageContentTest {
    @Test
    fun encodeThenDecodeRoundTripsEveryField() {
        val original =
            MessageContent(
                body = "hi",
                mentions = listOf(Mention("bob00000", "Bob")),
                attachmentHash = "hash",
                attachmentMime = "image/jpeg",
                attachmentKey = "key",
            )
        val decoded = MessageContent.decode(original.encode())!!
        assertEquals("hi", decoded.body)
        assertEquals(listOf(Mention("bob00000", "Bob")), decoded.mentions)
        assertEquals("hash", decoded.attachmentHash)
        assertEquals("image/jpeg", decoded.attachmentMime)
        assertEquals("key", decoded.attachmentKey)
    }

    /**
     * #131's drift guard. [sealedContent] is the one builder sendChat and the re-seal share; a conversational field
     * added to [MessageContent] and left out of it seals on neither path, and one added by hand to only one of two
     * builders is how the re-seal lost a file's name. Every field of the class either comes off a fully populated
     * row or is named in [MACHINERY].
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun theSealedContentOfARowMapsEveryConversationalField() {
        val row =
            MessageEntity(
                id = "m1",
                senderId = "alice000",
                recipientId = "bob00000",
                body = "the report",
                sentAt = 1_000L,
                mentions = MentionStore.encode(listOf(Mention("bob00000", "Bob"))),
                attachmentHash = "cipher-hash",
                attachmentMime = "application/pdf",
                attachmentKey = "key",
                attachmentName = "quarterly-report.pdf",
                attachmentSize = 1_400_000L,
                styles = StyleStore.encode(listOf(StyleSpan(4, 10, BodyStyle.BOLD))),
                bodyLang = "es",
                authorReads = "en",
            ).withReply(ReplyRef(messageId = "m0", authorId = "bob00000", author = "Bob", snippet = "send it?"))
                .withVideo(VideoInfo(durationMs = 30_000L, width = 480, height = 854, poster = byteArrayOf(1, 2, 3)))

        val content = row.sealedContent()
        val emitted =
            JSON
                .encodeToJsonElement(MessageContent.serializer(), content)
                .jsonObject.keys
        val fields =
            MessageContent
                .serializer()
                .descriptor.elementNames
                .toSet()

        assertEquals(
            "a new conversational field must be mapped in MessageEntity.sealedContent, or listed in MACHINERY",
            fields - MACHINERY,
            emitted,
        )
        assertEquals("quarterly-report.pdf", content.attachmentName)
        assertEquals(1_400_000L, content.attachmentSize)
        assertEquals(listOf(Mention("bob00000", "Bob")), content.mentions)
        assertEquals("m0", content.replyTo?.messageId)
        assertEquals(VideoInfo(30_000L, 480, 854, byteArrayOf(1, 2, 3)), content.video)
        assertEquals(listOf(StyleSpan(4, 10, BodyStyle.BOLD)), content.styles)
        assertEquals(LanguageRegistry.indexOf("es"), content.lang)
        assertEquals(LanguageRegistry.indexOf("en"), content.reads)
    }

    // --- Declared languages (ADR 2026-10.t2h6's 2026-10-29 amendment): "l" / "r", labels 18 / 19 ---

    /**
     * The amendment's hard requirement: a message in English by an English reader is the bytes it was, in both sealed
     * layouts, whatever the row holds — `sealedContent` re-normalises (`DeclaredLangs.of`) rather than trusting a row
     * written as `en`/`en`. Hand-written hex, so neither half can drift with the codec.
     */
    @Test
    fun anEnglishMessageByAnEnglishReaderSealsToItsOldBytesInBothLayouts() {
        // {"body": "hi"} and {1: "hi"}: what every build before the declaration sealed.
        val named = "a1" + "64626f6479" + "626869"
        val compact = "a1" + "01" + "626869"
        val row = MessageEntity(id = "m1", senderId = "alice000", recipientId = "bob00000", body = "hi", sentAt = 1L)
        for (declared in listOf(
            row,
            row.copy(bodyLang = "en", authorReads = "en"),
            row.copy(bodyLang = "en"),
            row.copy(authorReads = "en"),
        )) {
            val content = declared.sealedContent()
            assertEquals("${declared.bodyLang}/${declared.authorReads}", named, hex(content.encode()))
            assertEquals("${declared.bodyLang}/${declared.authorReads}", compact, hex(checkNotNull(MessageContentV2.encodeOrNull(content))))
        }
        // A row written un-normalised still seals only what the wire does not imply: Spanish by a Spanish reader is "l" alone.
        assertNull(row.copy(bodyLang = "es", authorReads = "es").sealedContent().reads)
        // A code the registry does not name is never declared.
        assertNull(row.copy(bodyLang = "tlh").sealedContent().lang)
    }

    /** Both fields round-trip both layouts, cost what [DeclaredLangs.wireCost] says, and read back as codes. */
    @Test
    fun aDeclarationRoundTripsBothLayoutsAndCostsWhatItSays() {
        val plain = MessageContent(body = "hola")
        for ((lang, reads) in listOf(1 to null, null to 1, 1 to 0, 27 to 60)) {
            val declared = plain.copy(lang = lang, reads = reads)
            val codes = DeclaredLangs.fromWire(lang, reads)
            val namedBytes = declared.encode()
            val compactBytes = checkNotNull(MessageContentV2.encodeOrNull(declared))
            assertEquals(declared, MessageContent.decode(namedBytes))
            assertEquals(declared, MessageContentV2.decode(compactBytes))
            assertEquals(codes.wireCost(named = true), namedBytes.size - plain.encode().size)
            assertEquals(codes.wireCost(named = false), compactBytes.size - checkNotNull(MessageContentV2.encodeOrNull(plain)).size)
        }
        // {"body": "hola", "l": 1, "r": 24} by hand: one-byte "l", two-byte "r".
        assertEquals(
            "a3" + "64626f6479" + "64686f6c61" + "616c" + "01" + "6172" + "1818",
            hex(plain.copy(lang = 1, reads = 24).encode()),
        )
        // {1: "hola", 18: 1, 19: 24}
        assertEquals(
            "a3" + "01" + "64686f6c61" + "12" + "01" + "13" + "1818",
            hex(checkNotNull(MessageContentV2.encodeOrNull(plain.copy(lang = 1, reads = 24)))),
        )
    }

    /** An index this phone does not know decodes (the frame is the sender's, not malformed) and reads as nothing. */
    @Test
    fun anUnknownIndexDecodesAndReadsAsNothing() {
        val content = checkNotNull(MessageContent.decode(MessageContent(body = "x", lang = 1, reads = 200).encode()))
        assertEquals(200, content.reads)
        assertEquals(DeclaredLangs(lang = "es"), DeclaredLangs.fromWire(content.lang, content.reads))
        assertEquals(DeclaredLangs.NONE, DeclaredLangs.fromWire(-1, LanguageRegistry.CODES.size))
    }

    /**
     * The value is a CBOR uint forever: a text, or one past an Int, fails the whole content's decode in both layouts.
     * Decode is what `InboundPipeline` reads after the AEAD opened and before anything commits, so such a frame is dropped
     * `DECRYPT_FAILED` (the sender's one message, no reset, no chain moved) — `InboundPipelineTest` walks it through.
     */
    @Test
    fun aWrongTypedOrOversizedDeclarationFailsTheDecodeInBothLayouts() {
        // {"body": "hi", "l": "es"} and {"body": "hi", "r": 2^32}
        assertNull(MessageContent.decode(unhex("a2" + "64626f6479" + "626869" + "616c" + "626573")))
        assertNull(MessageContent.decode(unhex("a2" + "64626f6479" + "626869" + "6172" + "1b0000000100000000")))
        // {1: "hi", 18: "es"} and {1: "hi", 19: 2^32}
        assertNull(MessageContentV2.decode(unhex("a2" + "01" + "626869" + "12" + "626573")))
        assertNull(MessageContentV2.decode(unhex("a2" + "01" + "626869" + "13" + "1b0000000100000000")))
        // The same maps with a uint in range decode, so it is the type that failed, not the shape.
        assertEquals(1, MessageContent.decode(unhex("a2" + "64626f6479" + "626869" + "616c" + "01"))?.lang)
        assertEquals(1, MessageContentV2.decode(unhex("a2" + "01" + "626869" + "12" + "01"))?.lang)
        // A negative integer is an int, not a uint: it decodes, and names no language ({"l": -1}, {18: -1}).
        val negative = checkNotNull(MessageContent.decode(unhex("a2" + "64626f6479" + "626869" + "616c" + "20")))
        assertEquals(-1, negative.lang)
        assertEquals(null, DeclaredLangs.fromWire(negative.lang, negative.reads).lang)
        assertEquals(-1, MessageContentV2.decode(unhex("a2" + "01" + "626869" + "12" + "20"))?.lang)
    }

    /**
     * Every fielded build reads a declared frame (WIRE_COMPAT rule 1): its decoders, the named and compact plaintexts and
     * the room's chat payload as they were compiled before the amendment, skip `"l"` / `"r"` and labels 18 / 19.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun aBuildBeforeTheDeclarationReadsADeclaredMessage() {
        val bold = listOf(StyleSpan(0, 2, BodyStyle.BOLD))
        val declared = MessageContent(body = "hola", styles = bold, lang = 1, reads = 60)
        val named = cryptoCbor.decodeFromByteArray(PreLangContent.serializer(), declared.encode())
        assertEquals("hola", named.body)
        assertEquals(checkNotNull(StyleSpans.pack(bold)).toList(), named.styles?.toList())
        val compact = compactCbor.decodeFromByteArray(PreLangWire.serializer(), checkNotNull(MessageContentV2.encodeOrNull(declared)))
        assertEquals("hola", compact.body)
        assertEquals(checkNotNull(StyleSpans.pack(bold)).toList(), compact.st?.toList())
        val room = WireCodec.encodePayload(ChatContent(body = "hola", lang = 1, reads = 60))
        assertEquals("hola", WireCodec.decodePayload<OlderContent>(room)?.body)
    }

    /** A video's description survives the named layout byte for byte, poster included (ADR 2026-10.hmfe). */
    @Test
    fun aVideoDescriptionRoundTripsTheNamedLayout() {
        val video = VideoInfo(durationMs = 61_000L, width = 854, height = 480, poster = ByteArray(5_000) { it.toByte() })
        val content = MessageContent(body = "", attachmentMime = "video/mp4", attachmentSize = 3_000_000L, video = video)
        assertEquals(video, checkNotNull(MessageContent.decode(content.encode())).video)
        assertEquals(null, MessageContent.decode(MessageContent(body = "x").encode())?.video)
    }

    @Test
    fun theCurrentVersionIsSupported() {
        assertTrue(MessageContent(body = "hi").isSupported()) // v defaults to VERSION
    }

    @Test
    fun aFutureContentSchemaVersionIsNotSupported() {
        assertFalse(MessageContent(v = MessageContent.MAX_SUPPORTED + 1, body = "hi").isSupported())
    }

    /**
     * ADR 2026-10.p95v's hard requirement: an unformatted message is the bytes it was before styles existed, in the
     * named sealed layout and in a room post, and a styled one adds only `"s"` and one CBOR byte string (major type 2,
     * never an integer array). Hand-written hex, so neither half can drift with the codec.
     */
    @Test
    fun anUnformattedMessageIsTheOldBytesAndAStyledOneAddsOneByteString() {
        // {"body": "make it bold"}: what every build before styles wrote.
        val plain = "a1" + "64626f6479" + "6c6d616b6520697420626f6c64"
        assertEquals(plain, hex(MessageContent(body = "make it bold").encode()))
        assertEquals(plain, hex(WireCodec.encodePayload(ChatContent(body = "make it bold"))))
        // + "s": h'08 20' — bold from 8, four units: delta 8, (4 << 3) | 0.
        val styled = "a2" + "64626f6479" + "6c6d616b6520697420626f6c64" + "6173" + "420820"
        val bold = listOf(StyleSpan(8, 12, BodyStyle.BOLD))
        assertEquals(styled, hex(MessageContent(body = "make it bold", styles = bold).encode()))
        assertEquals(styled, hex(WireCodec.encodePayload(ChatContent(body = "make it bold", styles = bold))))
        assertEquals(StyleSpans.wireCost(bold, StyleKey.NAMED), (styled.length - plain.length) / 2)
        assertEquals(bold, MessageContent.decode(unhex(styled))?.styles)
        assertEquals(bold, WireCodec.decodePayload<ChatContent>(unhex(styled))?.styles)
        assertEquals(emptyList<StyleSpan>(), MessageContent.decode(unhex(plain))?.styles)
    }

    /** A build that predates styles skips the key and reads the same body; iOS's named decoder must do the same. */
    @Test
    fun aBuildWithoutStylesReadsAStyledMessagesBody() {
        val bytes = MessageContent(body = "make it bold", styles = listOf(StyleSpan(8, 12, BodyStyle.BOLD))).encode()
        assertEquals("make it bold", WireCodec.decodePayload<OlderContent>(bytes)?.body)
    }

    /**
     * The value's type is frozen (ADR 2026-10.p95v): `"s"` holding anything but a byte string fails the typed decode,
     * as a wrong-typed `video` does, while malformed bytes inside a byte string never throw.
     */
    @Test
    fun aWrongTypedStylesValueFailsTheDecodeAndMalformedBytesDoNot() {
        // {"body": "hi", "s": [0, 16]}
        val array = unhex("a2" + "64626f6479" + "626869" + "6173" + "820010")
        assertNull(MessageContent.decode(array))
        assertNull(WireCodec.decodePayload<ChatContent>(array))
        // {"body": "hi", "s": h'00 10 80'}: one span, then a truncated uvarint.
        val truncated = unhex("a2" + "64626f6479" + "626869" + "6173" + "43001080")
        assertEquals(listOf(StyleSpan(0, 2, BodyStyle.BOLD)), MessageContent.decode(truncated)?.styles)
    }

    @Test
    fun decodingGarbageReturnsNullRatherThanThrowing() {
        assertNull(MessageContent.decode(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun theControlMarkerRoundTripsAndDefaultsToNull() {
        // Additive field under the same schema version: a v1-era decoder ignores it, an ordinary
        // message omits it (encodeDefaults = false), and a reset frame carries it inside the ciphertext.
        val reset = MessageContent.decode(MessageContent(body = "", ctl = MessageContent.CTL_SESSION_RESET).encode())!!
        assertEquals(MessageContent.CTL_SESSION_RESET, reset.ctl)
        assertTrue(reset.isSupported())
        assertNull(MessageContent.decode(MessageContent(body = "hi").encode())!!.ctl)
    }

    @Test
    fun theGroupKeyPayloadRoundTripsThroughEveryCtlShape() {
        val seed = GroupSeed(epoch = 3, seed = ByteArray(32) { 5 }, mintedAt = 77L)
        val dist =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload("g-1", keys = listOf(seed))).encode(),
            )!!
        assertEquals(MessageContent.CTL_GROUP_KEY, dist.ctl)
        assertEquals("g-1", dist.gk?.groupId)
        assertEquals(
            3,
            dist.gk
                ?.keys
                ?.single()
                ?.epoch,
        )
        assertEquals(
            77L,
            dist.gk
                ?.keys
                ?.single()
                ?.mintedAt,
        )
        assertTrue(
            ByteArray(32) { 5 }.contentEquals(
                dist.gk!!
                    .keys
                    .single()
                    .seed,
            ),
        )

        val req =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_GROUP_KEY_REQ, gk = GroupKeyPayload("g-1")).encode(),
            )!!
        assertEquals(MessageContent.CTL_GROUP_KEY_REQ, req.ctl)
        assertTrue(req.gk!!.keys.isEmpty())

        val ack =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_GROUP_KEY_ACK, gk = GroupKeyPayload("g-1", ackEpoch = 3)).encode(),
            )!!
        assertEquals(3, ack.gk?.ackEpoch)

        // An ordinary message carries no gk (encodeDefaults = false keeps it off the wire entirely).
        assertNull(MessageContent.decode(MessageContent(body = "hi").encode())!!.gk)

        // A seed distribution carries no root unless one is gossiped alongside it, and no roster unless the
        // sender attached its founding one.
        assertNull(dist.gk.gr)
        assertNull(dist.gk.group)
    }

    @Test
    fun theFoundingRosterRidesTheKeyCtlBesideSeedsAndRoot() {
        // Work item #47: the roster half of GroupInfo (no photo) on the same ctl DM as the seeds and the
        // root, so a member holding no row can pin the group from the seed itself.
        val roster =
            GroupInfo(
                id = "g-1",
                name = "Team",
                members = listOf("aaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbbbbbb"),
                createdBy = "aaaaaaaaaaaaaaaaaaaaaaaaaa",
                departed = listOf("cccccccccccccccccccccccccc"),
            )
        val dist =
            MessageContent.decode(
                MessageContent(
                    body = "",
                    ctl = MessageContent.CTL_GROUP_KEY,
                    gk =
                        GroupKeyPayload(
                            "g-1",
                            keys = listOf(GroupSeed(epoch = 4, seed = ByteArray(32) { 1 }, mintedAt = 88L)),
                            gr = GroupRootPayload(root = ByteArray(32) { 9 }, version = 1, minter = "aaaaaaaaaaaaaaaaaaaaaaaaaa"),
                            group = roster,
                        ),
                ).encode(),
            )!!
        assertEquals(roster, dist.gk?.group)
        assertEquals(
            4,
            dist.gk
                ?.keys
                ?.single()
                ?.epoch,
        )
        assertEquals(1, dist.gk?.gr?.version)

        // A root-only gossip carries it too: the shape a member with a root but no send chain emits.
        val rootOnly =
            MessageContent.decode(
                MessageContent(
                    body = "",
                    ctl = MessageContent.CTL_GROUP_KEY,
                    gk =
                        GroupKeyPayload(
                            "g-1",
                            gr = GroupRootPayload(root = ByteArray(32) { 9 }, version = 1, minter = "aaaaaaaaaaaaaaaaaaaaaaaaaa"),
                            group = roster,
                        ),
                ).encode(),
            )!!
        assertTrue(rootOnly.gk!!.keys.isEmpty())
        assertEquals(roster, rootOnly.gk.group)
    }

    @Test
    fun theGroupRootRidesTheKeyCtlWithAndWithoutSeeds() {
        // The root-only distribution (docs/SPOOL_PROTOCOL.md §3.2): `keys` empty is the normal shape for a
        // member that holds a root but has never sealed a group frame, so `gr` must survive it — a receiver
        // that adopts roots only inside its seed branch would drop exactly these.
        val root = GroupRootPayload(root = ByteArray(32) { 9 }, version = 2, minter = "bbbbbbbbbbbbbbbbbbbbbbbbbb")
        val rootOnly =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload("g-1", gr = root)).encode(),
            )!!
        assertTrue(rootOnly.gk!!.keys.isEmpty())
        assertEquals(2, rootOnly.gk.gr?.version)
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbb", rootOnly.gk.gr?.minter)
        assertTrue(ByteArray(32) { 9 }.contentEquals(rootOnly.gk.gr!!.root))

        // ...and the ordinary shape: seeds and root together on one ctl DM.
        val both =
            MessageContent.decode(
                MessageContent(
                    body = "",
                    ctl = MessageContent.CTL_GROUP_KEY,
                    gk =
                        GroupKeyPayload(
                            "g-1",
                            keys = listOf(GroupSeed(epoch = 4, seed = ByteArray(32) { 1 }, mintedAt = 88L)),
                            gr = root,
                        ),
                ).encode(),
            )!!
        assertEquals(
            4,
            both.gk
                ?.keys
                ?.single()
                ?.epoch,
        )
        assertEquals(2, both.gk?.gr?.version)
    }

    @Test
    fun theReceiptCtlRoundTripsWithItsAckId() {
        val receipt =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, ack = "m-42").encode(),
            )!!
        assertEquals(MessageContent.CTL_RECEIPT, receipt.ctl)
        assertEquals("m-42", receipt.ack)
        assertTrue(receipt.isSupported()) // additive fields, same schema version
        assertNull(MessageContent.decode(MessageContent(body = "hi").encode())!!.ack)
    }

    @Test
    fun theReactionCtlRoundTripsIncludingRetraction() {
        val reaction =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_REACTION, rp = ReactionPayload("m-42", "👍")).encode(),
            )!!
        assertEquals(MessageContent.CTL_REACTION, reaction.ctl)
        assertEquals("m-42", reaction.rp?.messageId)
        assertEquals("👍", reaction.rp?.emoji)

        // A retraction is emoji = null INSIDE a present rp — distinguishable from "no reaction payload"
        // because rp itself is non-null (encodeDefaults = false omits the null emoji key, and a missing
        // key decodes back to null; no reaction legitimately has no emoji, so the shape is unambiguous).
        val retraction =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_REACTION, rp = ReactionPayload("m-42")).encode(),
            )!!
        assertEquals("m-42", retraction.rp?.messageId)
        assertNull(retraction.rp?.emoji)
        assertNull(MessageContent.decode(MessageContent(body = "hi").encode())!!.rp)
    }

    @Test
    fun theProfileCtlRoundTripsAndCarriesItsOwnVersion() {
        val update =
            MessageContent.decode(
                MessageContent(
                    body = "",
                    ctl = MessageContent.CTL_PROFILE,
                    pr = ProfilePayload(name = "Ann", status = "hiking", avatarHash = "av1", version = 1_700L),
                ).encode(),
            )!!
        assertEquals(MessageContent.CTL_PROFILE, update.ctl)
        assertEquals("Ann", update.pr?.name)
        assertEquals("hiking", update.pr?.status)
        assertEquals("av1", update.pr?.avatarHash)
        // The version rides in the payload, not the carrying frame's sentAt: it is the one number the
        // sealed and cleartext profile paths both order against.
        assertEquals(1_700L, update.pr?.version)
        assertTrue(update.isSupported()) // additive field, same schema version
        assertNull(MessageContent.decode(MessageContent(body = "hi").encode())!!.pr)

        // Avatar cleared, and the ctl value is 8 — 7 is CTL_SCOPE_CONFIG, and ctl numbers are append-only.
        val cleared =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_PROFILE, pr = ProfilePayload("Ann", "", version = 2L)).encode(),
            )!!
        assertNull(cleared.pr?.avatarHash)
        assertEquals(8, MessageContent.CTL_PROFILE)
        assertEquals(7, MessageContent.CTL_SCOPE_CONFIG)
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun theScopeConfigCtlRoundTripsInBothFormsAndIsAbsentElsewhere() {
        // docs/SPOOL_PROTOCOL.md §5: a DM config names no group, a group's names its own.
        val dm =
            ScopeConfigPayload(
                spools = listOf("wss://a.example/spool/v1"),
                maxFrames = 400,
                ttlMs = 172_800_000L,
                maxBlob = 65_536,
                version = 3,
            )
        val group = dm.copy(groupId = "g-1", spools = listOf("wss://a.example/spool/v1", "wss://b.example/spool/v1?k=t"))
        for (payload in listOf(dm, group)) {
            val decoded = MessageContent.decode(MessageContent(body = "", ctl = MessageContent.CTL_SCOPE_CONFIG, sc = payload).encode())!!
            assertEquals(MessageContent.CTL_SCOPE_CONFIG, decoded.ctl)
            assertEquals(payload, decoded.sc)
            assertTrue(decoded.isSupported()) // additive field, same schema version
        }
        assertNull(MessageContent.decode(MessageContent(body = "hi").encode())!!.sc)
        // Every field but groupId is required (WIRE_COMPAT rule 6): a config missing its version is no content at all,
        // never a config a port fills in with defaults.
        val versionless =
            ContentWithoutAScopeConfigVersion(
                body = "",
                ctl = 7,
                sc = ScopeConfigWithoutVersion(listOf("wss://a.example/spool/v1"), 1, 1L, 1),
            )
        assertNull(MessageContent.decode(cryptoCbor.encodeToByteArray(versionless)))
    }

    /** The named layout of a scope-config ctl with the required `version` left out (rule 6's other half). */
    @Serializable
    private class ContentWithoutAScopeConfigVersion(
        val body: String,
        val ctl: Int,
        val sc: ScopeConfigWithoutVersion,
    )

    @Serializable
    private class ScopeConfigWithoutVersion(
        val spools: List<String>,
        val maxFrames: Int,
        val ttlMs: Long,
        val maxBlob: Int,
    )

    @Test
    fun aPlainMessageEncodingIsByteIdenticalWithTheNewFieldsDefaulted() {
        // WIRE_COMPAT rule 1 proof for the ack/rp/acks additions: an ordinary message's bytes are
        // unchanged by the new nullable fields (encodeDefaults = false), so pre-change golden vectors —
        // and every deployed build's expectations — still hold. Pinned against the hand-computed CBOR of
        // {"body": "hi"}: definite-length map, one text key, one text value.
        val plain = MessageContent(body = "hi").encode()
        assertTrue(
            byteArrayOf(
                0xA1.toByte(),
                0x64,
                'b'.code.toByte(),
                'o'.code.toByte(),
                'd'.code.toByte(),
                'y'.code.toByte(),
                0x62,
                'h'.code.toByte(),
                'i'.code.toByte(),
            ).contentEquals(plain),
        )
    }

    @Test
    fun theReceiptCtlRoundTripsWithBatchedAcks() {
        // The custody-escalated group tick: one sealed frame acking many ids (docs/
        // ENCRYPTED_RECEIPTS_REACTIONS.md §2). Order is preserved, the single-ack field stays null on
        // a batch, and an ordinary message decodes acks null (additive under the same schema version).
        val batch =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, acks = listOf("m1", "m2", "m3")).encode(),
            )!!
        assertEquals(MessageContent.CTL_RECEIPT, batch.ctl)
        assertEquals(listOf("m1", "m2", "m3"), batch.acks)
        assertNull(batch.ack)
        assertTrue(batch.isSupported())
        assertNull(MessageContent.decode(MessageContent(body = "hi").encode())!!.acks)
    }

    @Test
    fun theTransferPayloadRoundTripsEveryFieldAndNormalizesItsName() {
        val ready =
            TransferPayload(
                id = "t1",
                phase = TransferPayload.PHASE_READY,
                name = "evil\u202Efdp.exe",
                size = 4_000_000_000L,
                mime = "application/octet-stream",
                ssid = "DIRECT-ab-cdefghij",
                passphrase = "p".repeat(24),
                port = 45_000,
                key = "k".repeat(44),
                reason = TransferPayload.REASON_BUSY,
            )
        val decoded = MessageContent.decode(MessageContent(body = "", ctl = MessageContent.CTL_TRANSFER, xf = ready).encode())!!
        assertEquals(MessageContent.CTL_TRANSFER, decoded.ctl)
        // Sender-supplied text is brought inside AttachmentName's rules at the decode boundary, like attachmentName.
        assertEquals(ready.copy(name = "evilfdp.exe"), decoded.xf)
        assertNull(MessageContent.decode(MessageContent(body = "hi").encode())!!.xf)
    }

    @Test
    fun theCallPayloadRoundTripsEveryFieldAndStaysOffOrdinaryMessages() {
        val cands =
            CallPayload(
                id = "c1",
                ph = CallPayload.PHASE_CANDS,
                gen = 1,
                sdp = "v=0\r\na=fingerprint:sha-256 AB:CD\r\n",
                cand = listOf("candidate:1 1 udp 2122260223 192.0.2.1 49152 typ host", ""),
                rsn = CallPayload.REASON_NO_ROUTE,
                m = CallPayload.MEDIA_AUDIO,
            )
        val decoded = MessageContent.decode(MessageContent(body = "", ctl = MessageContent.CTL_CALL, cl = cands).encode())!!
        assertEquals(MessageContent.CTL_CALL, decoded.ctl)
        assertEquals(cands, decoded.cl)
        // The defaults cost nothing: an OFFER carries only its id, phase and SDP.
        val offer = CallPayload(id = "c2", ph = CallPayload.PHASE_OFFER, sdp = "v=0")
        assertEquals(offer, MessageContent.decode(MessageContent(body = "", ctl = MessageContent.CTL_CALL, cl = offer).encode())!!.cl)
        assertNull(MessageContent.decode(MessageContent(body = "hi").encode())!!.cl)
    }

    @Test
    fun aCallsWifiDirectInviteAndVerdictRoundTripAndABrokenInviteCostsOnlyItself() {
        // §3.10: the caller's group rides its OFFER, the callee's "joining" its RINGING.
        val invite = DirectInvite(s = "DIRECT-kn-abcdefgh", p = "abcdefghijklmnopqrstuvwx", o = 45_000, k = "AAAA")
        val offer = CallPayload(id = "c3", ph = CallPayload.PHASE_OFFER, sdp = "v=0", dw = invite)
        assertEquals(offer, MessageContent.decode(MessageContent(body = "", ctl = MessageContent.CTL_CALL, cl = offer).encode())!!.cl)
        val ringing = CallPayload(id = "c3", ph = CallPayload.PHASE_RINGING, dx = CallPayload.DIRECT_JOINING)
        assertEquals(ringing, MessageContent.decode(MessageContent(body = "", ctl = MessageContent.CTL_CALL, cl = ringing).encode())!!.cl)
        // Every invite field is optional: one that names only part of a group still decodes, and the reader refuses it.
        val partial = offer.copy(dw = DirectInvite(s = "DIRECT-kn-abcdefgh"))
        assertEquals(partial, MessageContent.decode(MessageContent(body = "", ctl = MessageContent.CTL_CALL, cl = partial).encode())!!.cl)
    }

    /** The named sealed layout as a build before the 2026-10-29 declaration decoded it: `"s"`, no `"l"` / `"r"`. */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private class PreLangContent(
        val v: Int = 1,
        val body: String,
        val ctl: Int? = null,
        @SerialName("s")
        @ByteString
        val styles: ByteArray? = null,
    )

    /** The compact layout as a build before the declaration decoded it: labels to 17. */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private class PreLangWire(
        @CborLabel(0)
        val v: Int = 1,
        @CborLabel(1)
        val body: String = "",
        @CborLabel(7)
        val ctl: Int? = null,
        @CborLabel(17)
        val st: ByteArray? = null,
    )

    /** The named layout as a build before ADR 2026-10.p95v decoded it: no `"s"`. */
    @Serializable
    private class OlderContent(
        val body: String,
    )

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        /**
         * The [MessageContent] fields a stored message row never carries: the schema version (elided while it equals
         * [MessageContent.VERSION]) and the ctl machinery. A new conversational field must be mapped in
         * [sealedContent]; only a new machinery field belongs here.
         */
        val MACHINERY = setOf("v", "ctl", "gk", "ack", "rp", "pr", "acks", "xf", "cl", "sc")

        /** Omits what equals its default, as the CBOR codec does, so the keys left are the fields the row filled. */
        val JSON = Json { encodeDefaults = false }
    }
}
