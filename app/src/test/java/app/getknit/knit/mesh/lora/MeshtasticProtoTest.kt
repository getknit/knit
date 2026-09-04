package app.getknit.knit.mesh.lora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Golden-vector + property tests for [MeshtasticProto]. The vectors are hand-derived against
 * `meshtastic/protobufs` (tag = field << 3 | wireType; 0 varint, 2 length-delimited, 5 fixed32) and are
 * the executable pin on the field numbers the board interop depends on. The property loops assert the
 * decoder is total: no input, however hostile, throws.
 */
class MeshtasticProtoTest {
    private fun hex(s: String): ByteArray =
        s
            .split(" ")
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    private fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it) }

    // --- encode vectors ---

    @Test
    fun encodeWantConfig() {
        assertEquals("18 F8 AC D1 91 01", MeshtasticProto.encodeWantConfig(0x12345678u).hex())
    }

    @Test
    fun encodeHeartbeatIsAnEmptyPresentMessage() {
        assertEquals("3A 00", MeshtasticProto.encodeHeartbeat().hex())
    }

    @Test
    fun encodeDisconnect() {
        assertEquals("20 01", MeshtasticProto.encodeDisconnect().hex())
    }

    @Test
    fun encodeBroadcastPacket() {
        val bytes =
            MeshtasticProto.encodePacket(
                OutboundPacket(channelIndex = 1, id = 0xDEADBEEFu, payload = byteArrayOf(1, 2, 3)),
            )
        assertEquals(
            "0A 16 15 FF FF FF FF 18 01 22 08 08 80 02 12 03 01 02 03 35 EF BE AD DE",
            bytes.hex(),
        )
    }

    @Test
    fun encodePacketOmitsDefaultChannelAndEmitsHopLimit() {
        val bytes =
            MeshtasticProto.encodePacket(
                OutboundPacket(channelIndex = 0, id = 0xDEADBEEFu, payload = byteArrayOf(1, 2, 3), hopLimit = 3),
            )
        // channel 0 is the proto3 default → omitted; hop_limit (field 9, tag 0x48) is appended.
        assertEquals(
            "0A 16 15 FF FF FF FF 22 08 08 80 02 12 03 01 02 03 35 EF BE AD DE 48 03",
            bytes.hex(),
        )
    }

    @Test
    fun aFullPayloadPacketStaysUnderOneWrite() {
        // 233 B payload → the whole ToRadio worst case must fit one MTU-512 write (the MTU >= 263 gate).
        val bytes =
            MeshtasticProto.encodePacket(
                OutboundPacket(channelIndex = 7, id = 0xFFFFFFFEu, payload = ByteArray(MeshtasticProto.MAX_PAYLOAD)),
            )
        assertTrue("worst-case ToRadio is one ATT write under MTU 512: ${bytes.size}", bytes.size <= 259)
    }

    @Test
    fun encodePacketEmitsWantResponse() {
        // want_response (Data field 3) rides an admin request; here on a self-addressed ADMIN packet.
        val bytes =
            MeshtasticProto.encodePacket(
                OutboundPacket(
                    to = 0x11223344u,
                    channelIndex = 0,
                    id = 0xAABBCCDDu,
                    portnum = MeshtasticProto.PORT_ADMIN,
                    payload = byteArrayOf(0x08, 0x01),
                    wantResponse = true,
                ),
            )
        // packet{ to=11223344, decoded{ portnum=6, payload=08 01, want_response=true }, id=AABBCCDD }
        assertEquals(
            "0A 14 15 44 33 22 11 22 08 08 06 12 02 08 01 18 01 35 DD CC BB AA",
            bytes.hex(),
        )
    }

    // --- admin encode vectors ---

    @Test
    fun encodeAdminGetChannelIsIndexPlusOne() {
        assertEquals("08 01", MeshtasticProto.encodeAdminGetChannel(0).hex())
        assertEquals("08 08", MeshtasticProto.encodeAdminGetChannel(7).hex())
    }

    @Test
    fun encodeAdminBeginAndCommitEdit() {
        assertEquals("80 04 01", MeshtasticProto.encodeAdminBeginEdit(null).hex())
        assertEquals("88 04 01", MeshtasticProto.encodeAdminCommitEdit(null).hex())
    }

    @Test
    fun encodeAdminCommitEchoesSessionPasskey() {
        // commit_edit_settings=true (field 65) then session_passkey=AA BB CC (field 101).
        assertEquals(
            "88 04 01 AA 06 03 AA BB CC",
            MeshtasticProto.encodeAdminCommitEdit(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())).hex(),
        )
    }

    @Test
    fun encodeAdminSetChannel() {
        val bytes =
            MeshtasticProto.encodeAdminSetChannel(
                ChannelWrite(index = 1, name = "A", psk = byteArrayOf(1, 2), role = MeshtasticProto.ROLE_SECONDARY),
                passkey = null,
            )
        // set_channel(33){ index=1, settings{ psk=01 02, name="A" }, role=2 }
        assertEquals("8A 02 0D 08 01 12 07 12 02 01 02 1A 01 41 18 02", bytes.hex())
    }

    @Test
    fun encodeAdminSetChannelCarriesPositionPrecision() {
        val bytes =
            MeshtasticProto.encodeAdminSetChannel(
                ChannelWrite(
                    index = 1,
                    name = "A",
                    psk = byteArrayOf(1, 2),
                    role = MeshtasticProto.ROLE_SECONDARY,
                    positionPrecision = MeshtasticProto.POSITION_PRECISION_NONE,
                ),
                passkey = null,
            )
        // ...settings{ psk, name, module_settings{} }: the submessage is *present but empty* (3A 00), which is
        // how precision 0 reaches the firmware — omitting it would read as "unset" and default to full.
        assertEquals("8A 02 0F 08 01 12 09 12 02 01 02 1A 01 41 3A 00 18 02", bytes.hex())
    }

    @Test
    fun encodeAdminGetOwner() {
        // AdminMessage.get_owner_request = 3 → tag 0x18, a bool.
        assertEquals("18 01", MeshtasticProto.encodeAdminGetOwner().hex())
    }

    @Test
    fun encodeAdminSetOwnerWrapsTheRawUser() {
        // AdminMessage.set_owner = 32 → tag 0x82 0x02; the payload is the board's own `User`, spliced.
        val user = hex("12 04 4B 6E 69 74") // User { long_name = "Knit" }
        assertEquals("82 02 06 12 04 4B 6E 69 74", MeshtasticProto.encodeAdminSetOwner(user, null).hex())
        assertEquals(
            "82 02 06 12 04 4B 6E 69 74 AA 06 03 AA BB CC",
            MeshtasticProto.encodeAdminSetOwner(user, hex("AA BB CC")).hex(),
        )
    }

    @Test
    fun encodeAdminSetOwnerKeepsAnEmptyUserPresent() {
        // Presence is the oneof selector: an omitted `set_owner` would read as no request at all.
        assertEquals("82 02 00", MeshtasticProto.encodeAdminSetOwner(ByteArray(0), null).hex())
    }

    @Test
    fun encodeAdminGetConfigIsAOneofMemberEvenAtZero() {
        // get_config_request(5) = DEVICE_CONFIG(0) — a oneof member must appear on the wire at its default.
        assertEquals("28 00", MeshtasticProto.encodeAdminGetConfig(BoardConfig.DEVICE).hex())
        assertEquals("28 01", MeshtasticProto.encodeAdminGetConfig(BoardConfig.POSITION).hex())
        // The module half is its own request field (7) with its own enum (TELEMETRY_CONFIG = 5).
        assertEquals("38 05", MeshtasticProto.encodeAdminGetConfig(BoardConfig.TELEMETRY).hex())
    }

    @Test
    fun encodeAdminSetConfigWrapsTheRawSubConfig() {
        val raw = hex("38 84 54")
        // set_config(34){ Config{ device(1) = raw } }
        assertEquals("92 02 05 0A 03 38 84 54", MeshtasticProto.encodeAdminSetConfig(BoardConfig.DEVICE, raw, null).hex())
        // set_module_config(35){ ModuleConfig{ telemetry(6) = raw } } + session_passkey(101)
        assertEquals(
            "9A 02 05 32 03 38 84 54 AA 06 03 AA BB CC",
            MeshtasticProto.encodeAdminSetConfig(BoardConfig.TELEMETRY, raw, hex("AA BB CC")).hex(),
        )
    }

    @Test
    fun encodeAdminSetConfigKeepsAnAllDefaultSubConfigPresent() {
        // An empty sub-config still has to select the oneof, or the board reads "no config in this message".
        assertEquals("92 02 02 0A 00", MeshtasticProto.encodeAdminSetConfig(BoardConfig.DEVICE, ByteArray(0), null).hex())
    }

    // --- admin decode vectors ---

    @Test
    fun decodeAdminReadsChannelAndPasskey() {
        // get_channel_response(2){ index=2, settings{name="knit"}, role=2 } + session_passkey(101)=AA BB CC
        val reply = MeshtasticProto.decodeAdmin(hex("12 0C 08 02 12 06 1A 04 6B 6E 69 74 18 02 AA 06 03 AA BB CC"))!!
        assertEquals(ChannelInfo(index = 2, name = "knit", role = 2), reply.channel)
        assertEquals("AA BB CC", reply.passkey!!.hex())
    }

    @Test
    fun decodeNodeInfoReadsTheBoardsOwnName() {
        // FromRadio { node_info = NodeInfo { num = 0xABCD, user = User { long_name, short_name } } }
        val fr = MeshtasticProto.decodeFromRadio(hex("22 17 08 CD D7 02 12 11 12 09 4B 6E 69 74 20 61 62 63 64 1A 04 4B 6E 69 74"))
        val info = fr as FromRadio.NodeInfo
        assertEquals(0xABCDu, info.num)
        assertEquals(BoardOwner("Knit abcd", "Knit"), info.owner)
    }

    @Test
    fun decodeNodeInfoWithoutAUserLeavesTheNameNull() {
        assertNull((MeshtasticProto.decodeFromRadio(hex("22 04 08 CD D7 02")) as FromRadio.NodeInfo).owner)
    }

    @Test
    fun decodeAdminReadsTheOwnerRawAndItsTwoNames() {
        // AdminMessage { get_owner_response = User { long_name = "Knit abcd", short_name = "Knit" } }
        val reply = MeshtasticProto.decodeAdmin(hex("22 11 12 09 4B 6E 69 74 20 61 62 63 64 1A 04 4B 6E 69 74"))!!
        assertEquals(BoardOwner("Knit abcd", "Knit"), reply.owner!!.owner)
        assertEquals("the raw User is kept for splicing", 17, reply.owner!!.raw.size)
    }

    @Test
    fun decodeAdminReadsAnEmptyOwnerAsEmptyNames() {
        // A board that has never been named: the fields are proto3 defaults, so they are simply absent.
        assertEquals(BoardOwner("", ""), MeshtasticProto.decodeAdmin(hex("22 00"))!!.owner!!.owner)
    }

    @Test
    fun decodeAdminReadsTheUnmonitoredMarkOffTheOwner() {
        // User { long_name = "Knit abcd", short_name = "Knit", is_unmessagable = true } — field 9, tag 0x48.
        val marked = MeshtasticProto.decodeAdmin(hex("22 13 12 09 4B 6E 69 74 20 61 62 63 64 1A 04 4B 6E 69 74 48 01"))!!
        assertEquals(BoardOwner("Knit abcd", "Knit", unmessagable = true), marked.owner!!.owner)
        // `optional`, so absent and an explicit false are two encodings — both of which read as messagable.
        assertFalse(
            MeshtasticProto
                .decodeAdmin(hex("22 02 48 00"))!!
                .owner!!
                .owner.unmessagable,
        )
        assertFalse(
            MeshtasticProto
                .decodeAdmin(hex("22 00"))!!
                .owner!!
                .owner.unmessagable,
        )
    }

    @Test
    fun decodeAdminReadsASubConfigAsRawBytes() {
        // get_config_response(6){ Config{ position(2) = 08 84 07 } } + session_passkey(101)
        val reply = MeshtasticProto.decodeAdmin(hex("32 05 12 03 08 84 07 AA 06 03 AA BB CC"))!!
        assertEquals(BoardConfig.POSITION, reply.config!!.config)
        assertEquals("08 84 07", reply.config!!.raw.hex())
        assertEquals("AA BB CC", reply.passkey!!.hex())
    }

    @Test
    fun decodeAdminReadsAModuleSubConfig() {
        // get_module_config_response(8){ ModuleConfig{ telemetry(6) = 08 84 07 } }
        val reply = MeshtasticProto.decodeAdmin(hex("42 05 32 03 08 84 07"))!!
        assertEquals(BoardConfig.TELEMETRY, reply.config!!.config)
        assertEquals("08 84 07", reply.config!!.raw.hex())
    }

    @Test
    fun decodeAdminReadsTheLoraSubConfigAsItsOwnTarget() {
        // Config{ lora(6) = … }. It shares both numbers with TELEMETRY and is told apart by `module` alone,
        // so this pins that the non-module member 6 resolves to LORA — the read-modify-write base the
        // dedicated-slot setup splices `channel_num` into (ADR 067).
        val config = MeshtasticProto.decodeAdmin(hex("32 05 32 03 08 84 07"))!!.config
        assertEquals(BoardConfig.LORA, config?.config)
    }

    @Test
    fun decodeAdminWithoutPasskeyOrChannelIsEmptyReply() {
        val reply = MeshtasticProto.decodeAdmin(ByteArray(0))!!
        assertNull(reply.passkey)
        assertNull(reply.channel)
    }

    @Test
    fun decodeAdminIsTotalOnGarbage() {
        val rng = Random(7)
        repeat(2_000) {
            val bytes = ByteArray(rng.nextInt(0, 24)) { rng.nextInt().toByte() }
            MeshtasticProto.decodeAdmin(bytes) // must never throw
        }
    }

    // --- decode vectors ---

    @Test
    fun decodeEmptyIsDrained() {
        assertEquals(FromRadio.Empty, MeshtasticProto.decodeFromRadio(ByteArray(0)))
    }

    @Test
    fun decodeMyInfo() {
        val fr = MeshtasticProto.decodeFromRadio(hex("1A 11 08 F8 AC D1 91 01 6A 09 68 65 6C 74 65 63 2D 76 34"))
        assertEquals(FromRadio.MyInfo(0x12345678u, "heltec-v4"), fr)
    }

    @Test
    fun decodeConfigComplete() {
        assertEquals(FromRadio.ConfigComplete(0x12345678u), MeshtasticProto.decodeFromRadio(hex("38 F8 AC D1 91 01")))
    }

    @Test
    fun decodeLoraRadioConfig() {
        // FromRadio{config=5}{lora=6}{use_preset=1 t, modem_preset=2 MEDIUM_FAST, region=7 EU_868,
        // hop_limit=8 -> 3, override_duty_cycle=12 f}
        val fr = MeshtasticProto.decodeFromRadio(hex("2A 0C 32 0A 08 01 10 04 38 03 40 03 60 00"))
        assertEquals(
            FromRadio.Config(
                LoraRadioConfig(
                    usePreset = true,
                    modemPreset = ModemPreset.MEDIUM_FAST,
                    region = LoraRegion.EU_868,
                    hopLimit = 3,
                    overrideDutyCycle = false,
                ),
            ),
            fr,
        )
    }

    @Test
    fun decodeLoraRadioConfigHonoursTheDutyCycleOverride() {
        val fr = MeshtasticProto.decodeFromRadio(hex("2A 06 32 04 38 03 60 01")) as FromRadio.Config
        assertEquals(true, fr.lora?.overrideDutyCycle)
        assertEquals(LoraRegion.EU_868, fr.lora?.region)
    }

    @Test
    fun anotherConfigVariantDecodesToANullRadioRatherThanBreakingTheHandshake() {
        // FromRadio{config=5}{device=1}{role=1} — a variant we don't read.
        assertEquals(FromRadio.Config(null), MeshtasticProto.decodeFromRadio(hex("2A 04 0A 02 08 01")))
    }

    @Test
    fun anUnknownPresetOrRegionFallsBackRatherThanThrowing() {
        // An over-estimating preset and a 100 %-duty region are the safe fallbacks for codes we don't know.
        assertEquals(ModemPreset.LONG_FAST, ModemPreset.fromCode(99))
        assertEquals(LoraRegion.OTHER, LoraRegion.fromCode(99))
        assertEquals(LoraRegion.UNSET, LoraRegion.fromCode(0))
        // US and ANZ are named (ADR 067 needs their band to place a dedicated slot in); every other
        // 100 %-duty region still collapses into OTHER, which carries no band and so gets no slot.
        assertEquals(LoraRegion.US, LoraRegion.fromCode(1))
        assertEquals(LoraRegion.ANZ, LoraRegion.fromCode(6))
        assertEquals(LoraRegion.OTHER, LoraRegion.fromCode(5)) // JP: 100 % duty, band unmodelled
    }

    @Test
    fun the28PresetsAreNamedRatherThanFallingBackToLongFast() {
        // Falling back is right for a preset that does not exist yet and wrong for one that does: LONG_FAST's
        // parameters would have priced LongTurbo's airtime and counted its RF slots at twice the real number.
        assertEquals(ModemPreset.LONG_TURBO, ModemPreset.fromCode(9))
        assertEquals(ModemPreset.LITE_FAST, ModemPreset.fromCode(10))
        assertEquals(ModemPreset.LITE_SLOW, ModemPreset.fromCode(11))
        assertEquals(ModemPreset.NARROW_FAST, ModemPreset.fromCode(12))
        assertEquals(ModemPreset.NARROW_SLOW, ModemPreset.fromCode(13))
        assertEquals(ModemPreset.TINY_FAST, ModemPreset.fromCode(14))
        assertEquals(ModemPreset.TINY_SLOW, ModemPreset.fromCode(15))
        assertEquals(ModemPreset.MEDIUM_TURBO, ModemPreset.fromCode(16))
        // The one that matters: 500 kHz at CR 4/8, which is why a US board that took 2.8's new default is
        // deaf to every LongFast board and costs a different amount of air.
        assertEquals(500_000, ModemPreset.LONG_TURBO.bandwidthHz)
        assertEquals(8, ModemPreset.LONG_TURBO.codingRate)
        assertEquals("LongTurbo", ModemPreset.LONG_TURBO.defaultChannelName)
    }

    @Test
    fun the28DutyLimitedRegionsAreNamedRatherThanCollapsingIntoOther() {
        // OTHER means "100 % duty", so a duty-limited region that lands there is granted an allowance the law
        // does not: forty times the legal one for EU_866.
        assertEquals(LoraRegion.EU_866, LoraRegion.fromCode(29))
        assertEquals(LoraRegion.EU_N_868, LoraRegion.fromCode(32))
        assertEquals(LoraRegion.TH, LoraRegion.fromCode(12))
        assertEquals(2.5, LoraRegion.EU_866.dutyCyclePercent, 0.0)
        assertEquals(10.0, LoraRegion.EU_N_868.dutyCyclePercent, 0.0)
        assertEquals(10.0, LoraRegion.TH.dutyCyclePercent, 0.0)
    }

    @Test
    fun aRegionsStockPresetIsStatedOnlyWhereKnitKnowsItExactly() {
        // The convergence notice is built on this, so a guess here is a notice shown to somebody whose board
        // is fine. OTHER buckets the ham regions (TinyFast/NarrowSlow) in with the LongFast ones. It is a
        // default, never a preset Knit wants a board moved to — that one is whatever the local mesh runs.
        assertEquals(ModemPreset.LONG_FAST, LoraRegion.US.defaultPreset)
        assertEquals(ModemPreset.LONG_FAST, LoraRegion.EU_868.defaultPreset)
        assertEquals(ModemPreset.LITE_FAST, LoraRegion.EU_866.defaultPreset)
        assertEquals(ModemPreset.NARROW_SLOW, LoraRegion.EU_N_868.defaultPreset)
        assertNull(LoraRegion.OTHER.defaultPreset)
    }

    @Test
    fun decodeRebooted() {
        assertEquals(FromRadio.Rebooted, MeshtasticProto.decodeFromRadio(hex("40 01")))
    }

    @Test
    fun decodeQueueStatus() {
        val fr = MeshtasticProto.decodeFromRadio(hex("5A 0A 10 0F 18 10 20 EF FD B6 F5 0D"))
        assertEquals(FromRadio.QueueStatus(res = 0, free = 15, maxlen = 16, meshPacketId = 0xDEADBEEFu), fr)
    }

    @Test
    fun decodeQueueStatusWithNegativeResReadsTheTenByteVarint() {
        val fr = MeshtasticProto.decodeFromRadio(hex("5A 0F 08 FF FF FF FF FF FF FF FF FF 01 10 0F 18 10"))
        assertEquals(FromRadio.QueueStatus(res = -1, free = 15, maxlen = 16, meshPacketId = 0u), fr)
    }

    @Test
    fun decodeChannelByName() {
        // Channel { index=1, settings { name="knit" (field 3) }, role=2 } — name is settings field 3 (tag 0x1A).
        val fr = MeshtasticProto.decodeFromRadio(hex("52 0C 08 01 12 06 1A 04 6B 6E 69 74 18 02"))
        assertEquals(FromRadio.Channel(ChannelInfo(index = 1, name = "knit", role = 2)), fr)
    }

    @Test
    fun decodeMetadataFirmware() {
        // DeviceMetadata { firmware_version="2.5.0" (field 1) }.
        val fr = MeshtasticProto.decodeFromRadio(hex("6A 07 0A 05 32 2E 35 2E 30"))
        assertEquals(FromRadio.Metadata("2.5.0"), fr)
    }

    @Test
    fun decodeUnknownVariantKeepsItsFieldNumber() {
        assertEquals(FromRadio.Other(19), MeshtasticProto.decodeFromRadio(hex("9A 01 02 08 01")))
    }

    @Test
    fun decodePacketWithInterleavedUnknownFieldsAndSignalQuality() {
        val bytes =
            hex(
                "12 32 " + // FromRadio.packet, len 50
                    "0D 78 56 34 12 " + // from = 0x12345678
                    "15 FF FF FF FF " + // to = broadcast
                    "18 01 " + // channel = 1
                    "22 08 08 80 02 12 03 AA BB CC " + // decoded { portnum=256, payload=AABBCC }
                    "98 06 01 " + // unknown field 99 (varint) — must be skipped
                    "35 01 01 00 00 " + // id = 0x101
                    "45 00 00 D0 40 " + // rx_snr = 6.5f
                    "48 02 " + // hop_limit = 2
                    "60 AB FF FF FF FF FF FF FF FF 01 " + // rx_rssi = -85 (10-byte sign-extended)
                    "78 03", // hop_start = 3
            )
        val fr = MeshtasticProto.decodeFromRadio(bytes) as FromRadio.Packet
        val p = fr.packet
        assertEquals(0x12345678u, p.from)
        assertEquals(MeshtasticProto.BROADCAST, p.to)
        assertEquals(1, p.channel)
        assertEquals(0x101u, p.id)
        assertFalse(p.encrypted)
        val data = p.decoded!!
        assertEquals(MeshtasticProto.PORT_PRIVATE_APP, data.portnum)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), data.payload)
        assertEquals(6.5f, p.rxSnr!!, 0.0001f)
        assertEquals(-85, p.rxRssi)
        assertEquals(1, p.hopsAway) // hop_start 3 - hop_limit 2
    }

    @Test
    fun decodeEncryptedPacketHasNoDecodedData() {
        val fr =
            MeshtasticProto.decodeFromRadio(
                hex("12 10 0D 78 56 34 12 15 FF FF FF FF 2A 04 DE AD BE EF"),
            ) as FromRadio.Packet
        assertTrue(fr.packet.encrypted)
        assertNull(fr.packet.decoded)
    }

    @Test
    fun decodeRoutingNakCorrelatesByRequestId() {
        val bytes =
            hex(
                "12 1C " +
                    "0D 78 56 34 12 " + // from
                    "15 78 56 34 12 " + // to (us)
                    "22 0B 08 05 12 02 18 01 35 EF BE AD DE " + // decoded { portnum=5, payload={error_reason=1}, request_id=0xDEADBEEF }
                    "35 02 02 00 00", // packet id
            )
        val p = (MeshtasticProto.decodeFromRadio(bytes) as FromRadio.Packet).packet
        val data = p.decoded!!
        assertEquals(MeshtasticProto.PORT_ROUTING, data.portnum)
        assertEquals(0xDEADBEEFu, data.requestId)
        assertEquals(RoutingError.NO_ROUTE, MeshtasticProto.decodeRouting(data.payload))
    }

    @Test
    fun decodeRoutingEmptyIsNone() {
        assertEquals(RoutingError.NONE, MeshtasticProto.decodeRouting(ByteArray(0)))
    }

    @Test
    fun decodeRoutingUnknownCodeSurfacesAsUnknown() {
        // error_reason = 99, a code this build doesn't enumerate.
        assertEquals(RoutingError.UNKNOWN, MeshtasticProto.decodeRouting(hex("18 63")))
    }

    // --- totality / robustness ---

    @Test
    fun decodeNodeInfoWithDeviceMetrics() {
        // node_info (field 4) { num = 42, device_metrics (field 6) { battery_level = 78, voltage = 3.92f } }
        val fr = MeshtasticProto.decodeFromRadio(hex("22 0B 08 2A 32 07 08 4E 15 48 E1 7A 40"))
        assertEquals(FromRadio.NodeInfo(42u, DeviceMetrics(batteryLevel = 78, voltage = 3.92f)), fr)
    }

    @Test
    fun decodeNodeInfoWithoutMetricsHasNone() {
        assertEquals(FromRadio.NodeInfo(42u, null), MeshtasticProto.decodeFromRadio(hex("22 02 08 2A")))
    }

    @Test
    fun decodeTelemetryReadsDeviceMetricsAndSkipsTheRest() {
        // Telemetry { time = 0x66A1B2C3, device_metrics { battery_level = 101, voltage = 3.92f, uptime_seconds = 1000 } }
        val metrics = MeshtasticProto.decodeTelemetry(hex("0D C3 B2 A1 66 12 0A 08 65 15 48 E1 7A 40 28 E8 07"))
        assertEquals(DeviceMetrics(batteryLevel = 101, voltage = 3.92f), metrics)
    }

    @Test
    fun decodeTelemetryReadsTheBoardsOwnDutyCycle() {
        // Telemetry { time, device_metrics { battery_level = 101, voltage = 3.92, channel_utilization =
        // 3.7733335, air_util_tx = 0.60725 } }, encoded by the real protobuf. air_util_tx is the board's own
        // measurement of what LoraAirtime estimates, so the field numbers under it are worth pinning: read
        // channel_utilization as air_util_tx and a quiet plane reports six times the duty it is using.
        val metrics = MeshtasticProto.decodeTelemetry(hex("0D 15 DD B9 66 12 11 08 65 15 48 E1 7A 40 1D 4C 7E 71 40 25 BC 74 1B 3F"))
        assertEquals(101, metrics?.batteryLevel)
        assertEquals(3.92f, metrics?.voltage!!, 0.0001f)
        assertEquals(3.7733335f, metrics.channelUtilPercent!!, 0.0001f)
        assertEquals(0.60725f, metrics.airUtilTxPercent!!, 0.0001f)
    }

    @Test
    fun aBoardThatReportsNoDutyCycleLeavesThePairNull() {
        // The existing vector: battery and voltage only. Both must stay null rather than read as zero — a
        // NodeInfo carries battery without the pair, and a reported 0 % is a real answer that means idle.
        val metrics = MeshtasticProto.decodeTelemetry(hex("0D C3 B2 A1 66 12 0A 08 65 15 48 E1 7A 40 28 E8 07"))
        assertNull(metrics?.channelUtilPercent)
        assertNull(metrics?.airUtilTxPercent)
    }

    @Test
    fun decodeTelemetryOfAnotherVariantIsNull() {
        // Telemetry { environment_metrics (field 3) { temperature = 20.0f } } — says nothing about the battery.
        assertNull(MeshtasticProto.decodeTelemetry(hex("1A 05 0D 00 00 A0 41")))
    }

    @Test
    fun everyPrefixTruncationDecodesToNullNeverThrows() {
        val vectors =
            listOf(
                "1A 11 08 F8 AC D1 91 01 6A 09 68 65 6C 74 65 63 2D 76 34",
                "5A 0A 10 0F 18 10 20 EF FD B6 F5 0D",
                "52 0C 08 01 12 06 1A 04 6B 6E 69 74 18 02",
                "12 10 0D 78 56 34 12 15 FF FF FF FF 2A 04 DE AD BE EF",
                "22 0B 08 2A 32 07 08 4E 15 48 E1 7A 40",
            )
        for (v in vectors) {
            val full = hex(v)
            for (len in 1 until full.size) {
                // A truncated frame either decodes to something (it happened to end on a field boundary)
                // or to null — but it must never throw.
                MeshtasticProto.decodeFromRadio(full.copyOf(len))
            }
        }
    }

    @Test
    fun randomBytesNeverThrow() {
        val rng = Random(42)
        repeat(10_000) {
            val bytes = ByteArray(rng.nextInt(0, 64)) { rng.nextInt().toByte() }
            MeshtasticProto.decodeFromRadio(bytes)
            MeshtasticProto.decodeRouting(bytes)
            MeshtasticProto.decodeTelemetry(bytes)
        }
    }

    @Test
    fun aGroupWireTypeIsRefused() {
        // Field 2 with wire type 3 (START_GROUP) — a construct nothing we speak uses.
        assertNull(MeshtasticProto.decodeFromRadio(hex("13 08 01 14")))
    }

    @Test
    fun aLengthPastTheEndIsRefused() {
        // FromRadio.packet claiming length 40 with only a few bytes present.
        assertNull(MeshtasticProto.decodeFromRadio(hex("12 28 0D 78 56")))
    }

    @Test
    fun anElevenByteVarintIsRefused() {
        assertNull(MeshtasticProto.decodeFromRadio(hex("38 FF FF FF FF FF FF FF FF FF FF 01")))
    }

    @Test
    fun theFullPacketOverheadIsMeasuredNotGuessed() {
        // ToRadio tag+len(3) + to fixed32(5) + channel varint(2) + decoded tag+len(3) + portnum varint(3)
        // + payload tag+len(3) + id fixed32(5).
        assertEquals(24, MeshtasticProto.PACKET_OVERHEAD)
        val full = OutboundPacket(channelIndex = 7, id = UInt.MAX_VALUE, payload = ByteArray(MeshtasticProto.MAX_PAYLOAD))
        assertEquals(MeshtasticProto.MAX_PAYLOAD + MeshtasticProto.PACKET_OVERHEAD, MeshtasticProto.encodePacket(full).size)
        // A smaller channel index or id never costs more than the measured worst case.
        val small = OutboundPacket(channelIndex = 0, id = 1u, payload = ByteArray(MeshtasticProto.MAX_PAYLOAD))
        assertTrue(MeshtasticProto.encodePacket(small).size <= MeshtasticProto.MAX_PAYLOAD + MeshtasticProto.PACKET_OVERHEAD)
    }

    @Test
    fun theOnAirPayloadCapIsTheFirmwareLimitLessThePrivatePortnumFraming() {
        // Measured 2026-08-29 on a Heltec V4 (2.7.26): 231 bytes queue, 232 and 233 NAK TOO_LARGE — the proto's
        // DATA_PAYLOAD_LEN (233) assumes a one-byte portnum, and PRIVATE_APP takes two.
        assertEquals(6, MeshtasticProto.DATA_FRAMING)
        assertEquals(231, MeshtasticProto.MAX_PAYLOAD)
        assertEquals(237, MeshtasticProto.LORA_DATA_MAX)
        assertEquals(MeshtasticProto.LORA_DATA_MAX, MeshtasticProto.MAX_PAYLOAD + MeshtasticProto.DATA_FRAMING)
        assertTrue(MeshtasticProto.MAX_PAYLOAD < MeshtasticProto.DATA_PAYLOAD_LEN)
        // The firmware's own budget is 239 (MAX_LORA_PAYLOAD_LEN 255 less the 16-byte header); the two bytes
        // between that and what the lab measured are the bitfield it fills in after us.
        assertEquals(239, MeshtasticProto.DATA_ENCODED_MAX)
    }

    @Test
    fun theSignatureCliffIsWhereTheFirmwareStopsBeingAbleToFitOne() {
        // Mirrors Router.cpp's signedDataFits: 2.8 signs what still fits signed and sends the rest unsigned,
        // so this is a cliff, not a ramp — and it falls between ADR 060's one-packet tick and its one-packet
        // DM tick, which is exactly why the airtime budget has to know about it.
        assertEquals(66, MeshtasticProto.XEDDSA_SIGNATURE_FIELD)
        assertEquals(165, MeshtasticProto.MAX_SIGNED_PAYLOAD)
        assertTrue(MeshtasticProto.MAX_SIGNED_PAYLOAD < MeshtasticProto.MAX_PAYLOAD)
        // A payload at the cliff plus its signature is exactly what the firmware's TX path allows.
        assertEquals(
            MeshtasticProto.DATA_ENCODED_MAX,
            MeshtasticProto.MAX_SIGNED_PAYLOAD + MeshtasticProto.DATA_FRAMING + 2 + MeshtasticProto.XEDDSA_SIGNATURE_FIELD,
        )
    }
}
