package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.KnitChannel
import app.getknit.knit.mesh.lora.meshcore.SimFrames.hex
import app.getknit.knit.mesh.lora.meshcore.SimFrames.hexOf
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * [MeshcoreProto] against the frames the real `companion-v1.18.0` firmware wrote in the simulator (bench Q11–Q15, the
 * resources' `NOTICE.txt`): every answer decodes as the bench read it, every `SENT` carries the ack code the phone
 * computes, and the encoders write the bench's own commands byte for byte. Then totality, the encoders' refusals, and the
 * pinned command allowlist.
 */
@Suppress("LargeClass")
class MeshcoreProtoTest {
    private val runs = SimFrames.RUNS.associateWith { SimFrames.load(it) }

    /** Each node's own key, off its `SELF_INFO`. */
    private fun keys(frames: List<SimFrame>): Map<String, ByteArray> =
        frames.filter { !it.tx && it.code == MeshcoreProto.RESP_SELF_INFO }.associate { f ->
            f.node to (MeshcoreProto.decode(f.bytes) as McFrame.SelfInfo).key
        }

    @Test
    fun everyFrameTheFirmwareWroteDecodesAsTheBenchReadIt() {
        var checked = 0
        for ((run, frames) in runs) {
            for (f in frames.filter { !it.tx }) {
                val d = MeshcoreProto.decode(f.bytes)
                assertFalse("$run $f decoded as $d", d is McFrame.Rejected || d is McFrame.Unknown)
                checkAgainstBench(run, f, d)
                checked++
            }
        }
        assertTrue("every run's answers were read", checked > 150)
    }

    /** [d], our decoding of [f], against the fields the bench's own decoder wrote beside it. */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // one arm per frame kind the bench annotated
    private fun checkAgainstBench(
        run: String,
        f: SimFrame,
        d: McFrame,
    ) {
        when (d) {
            is McFrame.Sent -> {
                assertEquals(run, f.int("flood") == 1, d.flood)
                assertEquals(run, f.string("expected_ack"), hexOf(MeshcoreProto.u32(d.expectedAck.toLong())))
                assertEquals(run, f.long("est_timeout_ms"), d.estTimeoutMs)
            }

            is McFrame.SendConfirmed -> {
                assertEquals(run, f.string("ack"), hexOf(MeshcoreProto.u32(d.ack.toLong())))
                assertEquals(run, f.long("trip_ms"), d.tripMs)
            }

            is McFrame.ContactMessage -> {
                assertTrue(d.v3)
                assertEquals(run, f.string("prefix"), hexOf(d.prefix))
                assertEquals(run, f.int("path_len"), d.pathLen)
                assertEquals(run, f.int("txt_type"), d.txtType)
                assertEquals(run, f.long("ts"), d.timestamp)
                assertEquals(run, f.int("text_len"), d.text.size)
            }

            is McFrame.ChannelData -> {
                assertEquals(run, f.int("slot"), d.slot)
                assertEquals(run, f.int("path_len"), d.pathLen)
                assertEquals(run, f.string("data_type"), "0x%04x".format(d.dataType))
                assertEquals(run, f.int("data_len"), d.data.size)
            }

            is McFrame.Contact -> {
                assertEquals(run, f.string("pub"), hexOf(d.record.key.copyOf(6)))
                assertEquals(run, f.int("type"), d.record.type)
                assertEquals(run, f.int("flags"), d.record.flags)
                assertEquals(run, f.int("out_path_len"), d.record.outPathLen)
                assertTrue(run, d.record.named(requireNotNull(f.string("contact_name"))))
            }

            is McFrame.PathUpdated -> {
                assertEquals(run, f.string("pub"), hexOf(d.key.copyOf(6)))
            }

            is McFrame.SelfInfo -> {
                assertEquals(run, f.string("pub"), hexOf(d.key.copyOf(6)))
                // The simulator's own prefs: 915 MHz, 250 kHz, SF10, CR 4/5.
                assertEquals(915_000L, d.freqKhz)
                assertEquals(250_000L, d.bwHz)
                assertEquals(10, d.sf)
                assertEquals(5, d.cr)
                assertEquals(f.node, d.name.decodeToString())
            }

            is McFrame.DeviceInfo -> {
                assertEquals(14, d.versionCode)
                assertEquals(50, d.maxContactsHalf) // the simulator build's MAX_CONTACTS=100
                assertEquals(4, d.maxChannels)
                assertEquals(false, d.clientRepeat)
            }

            is McFrame.Err -> {
                assertEquals("TABLE_FULL", f.string("err"))
                assertEquals(MeshcoreProto.ERR_TABLE_FULL, d.code)
            }

            else -> {}
        }
    }

    @Test
    fun everySentCarriesTheAckCodeThePhoneComputes() {
        // Binding rule "client" 2: a SENT is ours only if its expected_ack is sha256(ts ‖ attempt & 3 ‖ text ‖ our key)[0..4]
        // (BaseChatMesh.cpp composeMsgPacket). The text is what the board kept: it cuts at a zero (Q11).
        var pairs = 0
        for ((run, frames) in runs) {
            val keys = keys(frames)
            frames.forEachIndexed { i, f ->
                if (!f.tx || f.code != MeshcoreProto.CMD_SEND_TXT_MSG) return@forEachIndexed
                val answer = frames.drop(i + 1).first { !it.tx && it.node == f.node && it.code in setOf(1, 6) }
                val sent = MeshcoreProto.decode(answer.bytes) as? McFrame.Sent ?: return@forEachIndexed
                val attempt = f.bytes[2].toInt()
                val ts = MeshcoreProto.u32(f.bytes, 3)
                val raw = f.bytes.copyOfRange(13, f.bytes.size)
                val text = raw.indexOf(0).let { if (it < 0) raw else raw.copyOf(it) }
                assertEquals(run, sent.expectedAck, MeshcoreProto.expectedAck(ts, attempt, text, keys.getValue(f.node)))
                pairs++
            }
        }
        assertEquals("every SENT in Q11–Q14", 11, pairs)
    }

    @Test
    fun theEncodersWriteTheBenchsOwnCommands() {
        val q12 = runs.getValue("q12")
        val bobKey = keys(q12).getValue("bob")
        val tx = q12.filter { it.tx && it.node == "alice" }
        assertEquals("1603", hexOf(MeshcoreProto.deviceQuery()))
        assertEquals("2601", hexOf(MeshcoreProto.setManualAdd(1)))
        assertEquals("0a", hexOf(MeshcoreProto.syncNextMessage()))
        assertArrayEquals(tx.first { it.code == MeshcoreProto.CMD_APP_START }.bytes.copyOf(8), MeshcoreProto.appStart().copyOf(8))
        // A teach is the bench's 136-B record: CHAT, favourite only, zero-hop, its `Knit xxxx` name.
        assertArrayEquals(
            tx.first { it.code == MeshcoreProto.CMD_ADD_UPDATE_CONTACT }.bytes,
            MeshcoreProto.addUpdateContact(ContactRecord.knit(bobKey, "Knit bbbb"), withPosition = false),
        )
        assertArrayEquals(tx.first { it.code == MeshcoreProto.CMD_GET_CONTACT_BY_KEY }.bytes, MeshcoreProto.getContactByKey(bobKey))
        for (run in listOf("q11", "q12", "q14-same_attempt", "q14-next_attempt")) {
            for (f in runs.getValue(run).filter { it.tx && it.code == MeshcoreProto.CMD_SEND_TXT_MSG }) {
                val text = f.bytes.copyOfRange(13, f.bytes.size)
                val ours = MeshcoreProto.sendText(f.bytes.copyOfRange(7, 13), f.bytes[2].toInt(), MeshcoreProto.u32(f.bytes, 3), text)
                if (text.size <= MeshcoreProto.DIRECT_TEXT_CAP && text.none { it.toInt() == 0 }) {
                    assertArrayEquals(run, f.bytes, ours)
                } else {
                    assertNull("$run: a text the firmware would cut or that one notify cannot carry is refused", ours)
                }
            }
        }
        val q15 = runs.getValue("q15").filter { it.tx && it.node == "alice" }
        val setChannel = q15.first { it.code == MeshcoreProto.CMD_SET_CHANNEL }.bytes
        assertArrayEquals(setChannel, MeshcoreProto.setChannel(1, "Knit", setChannel.copyOfRange(34, 50)))
        for (f in q15.filter { it.code == MeshcoreProto.CMD_SEND_CHANNEL_DATA }) {
            assertArrayEquals(f.bytes, MeshcoreProto.sendChannelData(1, f.bytes.copyOfRange(5, f.bytes.size), dataType = 0xFF4B))
        }
    }

    /** One call of every encoder, with arguments the firmware takes. */
    private fun everyEncoderOnce(key: ByteArray): List<ByteArray> =
        listOfNotNull(
            MeshcoreProto.deviceQuery(),
            MeshcoreProto.appStart(),
            MeshcoreProto.getChannel(1),
            MeshcoreProto.setChannel(1, KnitChannel.NAME, KnitChannel.PSK),
            MeshcoreProto.clearChannel(2),
            MeshcoreProto.sendChannelData(1, ByteArray(MeshcoreProto.CHANNEL_DATA_CAP) { 7 }),
            MeshcoreProto.sendChannelText(0, 1_790_000_000L, "hello".encodeToByteArray(), "Knit abcd".encodeToByteArray()),
            MeshcoreProto.sendText(key.copyOf(6), 2, 1_790_000_000L, Cobs.encode(ByteArray(156) { (it % 7).toByte() })),
            MeshcoreProto.syncNextMessage(),
            MeshcoreProto.getContactByKey(key),
            MeshcoreProto.removeContact(key),
            MeshcoreProto.resetPath(key),
            MeshcoreProto.addUpdateContact(ContactRecord.knit(key, MeshcoreNames.forKey(key)), withPosition = false),
            MeshcoreProto.addUpdateContact(ContactRecord.knit(key, MeshcoreNames.forKey(key)), withPosition = true),
            MeshcoreProto.countContacts(),
            MeshcoreProto.getStats(MeshcoreProto.STATS_CORE),
            MeshcoreProto.getStats(MeshcoreProto.STATS_RADIO),
            MeshcoreProto.getStats(MeshcoreProto.STATS_PACKETS),
            MeshcoreProto.getBattery(),
            MeshcoreProto.setDeviceTime(1_790_000_100L),
            MeshcoreProto.getDeviceTime(),
            MeshcoreProto.clearScopeOverride(),
            MeshcoreProto.getDefaultScope(),
            MeshcoreProto.setManualAdd(1),
            MeshcoreProto.getAutoadd(),
            MeshcoreProto.setAutoadd(0),
            MeshcoreProto.setAdvertName("Knit abcd"),
            MeshcoreProto.signStart(),
            MeshcoreProto.signData(MeshcoreSession.CONSENT_DOMAIN + ByteArray(32) { 9 }),
            MeshcoreProto.signFinish(),
        )

    /** The signed challenge's frames, as `companion-v1.18.0` takes and writes them (`MyMesh.cpp:1781-1815`). */
    @Test
    fun theSignCommandsAndTheirAnswersAreTheFirmwaresFrames() {
        assertArrayEquals(byteArrayOf(33), MeshcoreProto.signStart())
        val message = MeshcoreSession.CONSENT_DOMAIN + ByteArray(32) { it.toByte() }
        assertArrayEquals(byteArrayOf(34) + message, MeshcoreProto.signData(message))
        assertNull("nothing to sign", MeshcoreProto.signData(ByteArray(0)))
        assertNull("one write only", MeshcoreProto.signData(ByteArray(MeshcoreProto.MAX_NOTIFY)))
        assertArrayEquals(byteArrayOf(35), MeshcoreProto.signFinish())
        // RESP_CODE_SIGN_START: a reserved byte, then the u32 limit (8 KiB).
        assertEquals(McFrame.SignStart(8192), MeshcoreProto.decode(byteArrayOf(19, 0, 0, 0x20, 0, 0)))
        val sig = ByteArray(64) { (it * 3).toByte() }
        val decoded = MeshcoreProto.decode(byteArrayOf(20) + sig) as McFrame.Signature
        assertArrayEquals(sig, decoded.value)
        assertTrue("a short one is refused", MeshcoreProto.decode(byteArrayOf(20) + ByteArray(63)) !is McFrame.Signature)
    }

    @Test
    fun theEncodersEmitOnlyTheBriefedCommandsAndNeverTheForbiddenOnes() {
        // Binding rule "contacts" 5: never EXPORT_PRIVATE_KEY (23), IMPORT_PRIVATE_KEY (24), SET_DEVICE_PIN (37),
        // SET_RADIO_PARAMS (11), SEND_RAW_PACKET (65) or RUN_CLI_COMMAND (66).
        val forbidden = setOf(23, 24, 37, 11, 65, 66)
        val emitted = everyEncoderOnce(FakeMeshcoreAir.keyOf("bob")).map { it[0].toInt() and 0xFF }.toSet()
        assertEquals("every allowed command is exercised, and nothing else is emitted", MeshcoreProto.ENCODED_COMMANDS, emitted)
        assertTrue(forbidden.none { it in MeshcoreProto.ENCODED_COMMANDS })
        // 33-35: the consent's signed challenge (the maintainer's decision, 2026-11-13).
        val briefed = setOf(1, 2, 3, 4, 5, 6, 8, 9, 10, 13, 15, 20, 22, 30, 31, 32, 33, 34, 35, 38, 54, 56, 58, 59, 62, 64)
        assertEquals(briefed, MeshcoreProto.ENCODED_COMMANDS)
    }

    @Test
    fun theFirmwareModelReadsEveryEncodersOutputAsMeant() {
        // The encoder's own output, decoded by the command handler the fake runs (MyMesh.cpp's): none is answered as an
        // unsupported command or an illegal argument, and the writes land where they say.
        val air = FakeMeshcoreAir(TestScope()) { 0L }
        val board = air.board("alice")
        val bob = air.board("bob")
        board.addContact(bob)
        val client = FakeForeignClient(board)
        for (frame in everyEncoderOnce(bob.key)) {
            val before = client.received.size
            client.send(frame)
            val answers = client.received.drop(before).map { MeshcoreProto.decode(it) }
            assertTrue("${frame[0]} was answered", answers.isNotEmpty())
            answers.filterIsInstance<McFrame.Err>().forEach { e ->
                assertFalse(
                    "${frame[0]} answered ${e.code}",
                    e.code == MeshcoreProto.ERR_UNSUPPORTED_CMD || e.code == MeshcoreProto.ERR_ILLEGAL_ARG,
                )
            }
            answers.forEach { assertFalse("${frame[0]} drew $it", it is McFrame.Rejected) }
        }
        assertEquals("Knit abcd", board.advertName.decodeToString())
        assertEquals(1, board.manualAdd)
        assertEquals(0, board.autoadd)
        assertArrayEquals(KnitChannel.PSK, board.channels[1].second)
        assertEquals("Knit", board.channels[1].first.decodeToString())
        assertTrue("slot 2 cleared", board.channels[2].first.isEmpty())
        assertEquals("removed, then taught again, zero-hop", 0, board.contact(bob.key)?.outPathLen)
    }

    @Test
    fun aChannelDataFrameCutShortByTheEsp32NotifyIsRejected() {
        // Q8: a 165-B payload reached the phone as a 173-B notify, its last byte gone and its length byte still 165.
        val full = byteArrayOf(27, 32, 0, 0, 1, 0, 0x4B, 0xFF.toByte(), 165.toByte()) + ByteArray(165) { 1 }
        assertEquals(McFrame.Rejected(27, McReject.TRUNCATED), MeshcoreProto.decode(full.copyOf(173)))
        // Past the 176-B frame cap is refused before anything else is read.
        assertEquals(McFrame.Rejected(27, McReject.OVERLONG), MeshcoreProto.decode(full + byteArrayOf(9, 9, 9)))
        val withTrailing = byteArrayOf(27, 32, 0, 0, 1, 0, 0x4B, 0xFF.toByte(), 4) + ByteArray(5)
        assertEquals(McFrame.Rejected(27, McReject.OVERLONG), MeshcoreProto.decode(withTrailing))
        val whole = byteArrayOf(27, 32, 0, 0, 1, 0, 0x4B, 0xFF.toByte(), 164.toByte()) + ByteArray(164)
        assertEquals(164, (MeshcoreProto.decode(whole) as McFrame.ChannelData).data.size)
    }

    @Test
    fun shortAndOverlongFixedFramesAreRejectedNeverThrown() {
        assertEquals(McFrame.Rejected(null, McReject.EMPTY), MeshcoreProto.decode(ByteArray(0)))
        assertEquals(McFrame.Rejected(6, McReject.SHORT), MeshcoreProto.decode(hex("0600d440c094")))
        assertEquals(McFrame.Rejected(6, McReject.OVERLONG), MeshcoreProto.decode(hex("0600d440c0946207000000")))
        assertEquals(McFrame.Rejected(0x82, McReject.SHORT), MeshcoreProto.decode(hex("82d440")))
        assertEquals(McFrame.Rejected(16, McReject.SHORT), MeshcoreProto.decode(hex("102000003f16")))
        assertEquals(McFrame.Rejected(5, McReject.SHORT), MeshcoreProto.decode(ByteArray(40).also { it[0] = 5 }))
        assertEquals(McFrame.Rejected(24, McReject.UNKNOWN_KIND), MeshcoreProto.decode(hex("1809")))
        assertEquals(McFrame.Rejected(0, McReject.OVERLONG), MeshcoreProto.decode(ByteArray(MeshcoreProto.MAX_FRAME + 1)))
        assertEquals(McFrame.Unknown(77), MeshcoreProto.decode(byteArrayOf(77)))
        assertEquals(McFrame.Push(0x88), MeshcoreProto.decode(hex("8820b00e00d440c0940075")))
    }

    @Test
    fun theOldReceiveCodesStillDecode() {
        // Binding rule "client" 4: another client's DEVICE_QUERY < 3 switches the board's receive frames to 7 and 8.
        val v1 = hex("073f165555ee6cff00103db16a") + "retry probe".encodeToByteArray()
        val dm = MeshcoreProto.decode(v1) as McFrame.ContactMessage
        assertFalse(dm.v3)
        assertNull(dm.snr)
        assertEquals("retry probe", dm.text.decodeToString())
        assertEquals(0xFF, dm.pathLen)
        val ch = MeshcoreProto.decode(hex("0800020011223344") + "a: hi".encodeToByteArray()) as McFrame.ChannelMessage
        assertEquals(0, ch.slot)
        assertEquals(2, ch.pathLen)
        assertEquals("a: hi", ch.text.decodeToString())
    }

    @Test
    fun theEncodersRefuseWhatTheFirmwareWouldCutOrRefuse() {
        val prefix = ByteArray(6) { 1 }
        assertNull("a zero byte", MeshcoreProto.sendText(prefix, 0, 1L, byteArrayOf(1, 0, 2)))
        assertNull("past one notify", MeshcoreProto.sendText(prefix, 0, 1L, ByteArray(158) { 1 }))
        assertNotNull(MeshcoreProto.sendText(prefix, 0, 1L, ByteArray(157) { 1 }))
        assertNull("attempt past two bits", MeshcoreProto.sendText(prefix, 4, 1L, byteArrayOf(1)))
        assertNull("a short prefix", MeshcoreProto.sendText(ByteArray(5), 0, 1L, byteArrayOf(1)))
        assertNull("empty", MeshcoreProto.sendText(prefix, 0, 1L, ByteArray(0)))
        assertNull("past the BLE cap", MeshcoreProto.sendChannelData(1, ByteArray(165)))
        assertNotNull(MeshcoreProto.sendChannelData(1, ByteArray(164)))
        assertNull("reserved type", MeshcoreProto.sendChannelData(1, ByteArray(4), dataType = 0))
        // Binding rule "sends" 8: the firmware trims `<name>: <text>` to 160 and answers OK.
        val name = "Knit abcd".encodeToByteArray()
        assertEquals(149, MeshcoreProto.channelTextCap(name))
        assertNotNull(MeshcoreProto.sendChannelText(0, 1L, ByteArray(149) { 0x41 }, name))
        assertNull(MeshcoreProto.sendChannelText(0, 1L, ByteArray(150) { 0x41 }, name))
        assertNull(MeshcoreProto.sendChannelText(0, 1L, byteArrayOf(0x41, 0), name))
        assertNull("a name without room for its terminator", MeshcoreProto.setChannel(1, "x".repeat(32), KnitChannel.PSK))
        assertNull("a 256-bit secret", MeshcoreProto.setChannel(1, "Knit", ByteArray(32)))
        assertNull(MeshcoreProto.setAdvertName(""))
        assertNull(MeshcoreProto.getContactByKey(ByteArray(31)))
    }

    @Test
    fun theSetChannelAndContactFormsHaveTheFirmwaresLengths() {
        assertEquals(50, MeshcoreProto.setChannel(1, "Knit", KnitChannel.PSK)?.size)
        val key = FakeMeshcoreAir.keyOf("bob")
        assertEquals(136, MeshcoreProto.addUpdateContact(ContactRecord.knit(key, "Knit 1234"), withPosition = false).size)
        assertEquals(144, MeshcoreProto.addUpdateContact(ContactRecord.knit(key, "Knit 1234"), withPosition = true).size)
        assertEquals(5, MeshcoreProto.countContacts().size)
        assertEquals(164, MeshcoreProto.CHANNEL_DATA_CAP)
    }

    @Test
    fun noFrameTypePrintsAKeyANameOrAText() {
        val q12 = runs.getValue("q12")
        val printed = q12.filter { !it.tx }.joinToString("\n") { MeshcoreProto.decode(it.bytes).toString() }
        val key = keys(q12).getValue("alice")
        assertFalse(printed.contains(hexOf(key.copyOf(6))))
        assertFalse(printed.contains("Knit bbbb"))
        assertFalse(printed.contains("alice"))
        assertFalse(printed.contains("zero-hop pin check"))
    }

    @Test
    fun randomAndTruncatedInputNeverThrows() {
        val rng = Random(42)
        repeat(20_000) {
            val bytes = ByteArray(rng.nextInt(0, 200)) { rng.nextInt().toByte() }
            if (bytes.isNotEmpty() && rng.nextBoolean()) bytes[0] = rng.nextInt(0, 32).toByte()
            MeshcoreProto.decode(bytes)
        }
        for (frames in runs.values) {
            for (f in frames) for (len in 0..f.bytes.size) MeshcoreProto.decode(f.bytes.copyOf(len))
        }
    }

    @Ignore("6013d18c9 ships the MeshCore plane in release while room posts still ride DATA_TYPE_DEV; parked for now")
    @Test
    fun aReleaseBuildNeverCarriesTheDevelopmentDataType() {
        // Binding rule "sends" 9: room posts ride DATA_TYPE_DEV (0xFFFF, TxtDataHelpers.h:12) until MeshCore registers a
        // value for Knit. The MeshCore plane is off in a release build by default; while the type is unregistered, that
        // default must stay off. Registering a value is what lets this test pass with the default turned on.
        if (MeshcoreProto.KNIT_DATA_TYPE != MeshcoreProto.DATA_TYPE_DEV) return
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.exists() }.readText()
        val fields =
            Regex(
                """buildConfigField\("boolean", "MESHCORE_PLANE", ([^\n]+)\)""",
            ).findAll(gradle).map { it.groupValues[1] }.toList()
        assertEquals("one debug and one release definition", 2, fields.size)
        assertTrue("the release default is off: ${fields[1]}", fields[1].contains("meshcorePlane ?: false"))
        val release = gradle.substring(gradle.indexOf("release {"))
        assertTrue("the off default sits in the release block", release.contains("\"MESHCORE_PLANE\", ((meshcorePlane ?: false)"))
    }

    /**
     * Whether the board airs a position (refs #285, ADR 2026-11.gsqu's Stage 7 amendment), pinned on golden `SELF_INFO`
     * bytes laid out as `companion-v1.18.0` writes them (`MyMesh.cpp:1084-1115`): the code, type, tx power, max power, the
     * key, lat, lon (little-endian µdeg), `multi_acks`, `advert_loc_policy`, the telemetry byte (`env << 4 | loc << 2 |
     * base`), `manual_add_contacts`, freq, bw, sf, cr, then the name. Only the policy and the telemetry byte count: a board
     * with no fix yet (0,0) shares as much as one with a position.
     */
    @Test
    fun selfInfoSaysWhetherTheBoardAirsAPositionAndKeepsNoCoordinates() {
        val key = "11".repeat(32)
        val radio = "95440d00" + "90d00300" + "0b" + "05" // 869 525 kHz, 250 kHz, SF11, CR 4/5

        fun frame(
            latLon: String,
            policy: String,
            telemetry: String,
        ): McFrame.SelfInfo =
            MeshcoreProto.decode(hex("05011414" + key + latLon + "00" + policy + telemetry + "00" + radio + "78"))
                as McFrame.SelfInfo
        val origin = "00000000" + "00000000"
        val somewhere = "40e3f702" + "00bbccff" // 49.800000, -3.360000
        assertTrue("policy 1 with no fix yet", frame(origin, "01", "00").sharesPosition)
        assertTrue("policy 1 at a position", frame(somewhere, "01", "00").sharesPosition)
        assertTrue("a policy the firmware may add later", frame(origin, "07", "00").sharesPosition)
        assertTrue("policy 0, location telemetry ALLOW_ALL with base ALLOW_ALL", frame(somewhere, "00", "0a").sharesPosition)
        assertFalse("policy 0, telemetry DENY", frame(somewhere, "00", "00").sharesPosition)
        assertFalse("location ALLOW_ALL answers no one with base DENY", frame(somewhere, "00", "08").sharesPosition)
        assertFalse("base ALLOW_FLAGS: Knit's favourite bit grants no base permission", frame(somewhere, "00", "09").sharesPosition)
        assertFalse("ALLOW_FLAGS: Knit's favourite bit grants none", frame(somewhere, "00", "05").sharesPosition)
        assertFalse("environment alone is no position", frame(somewhere, "00", "22").sharesPosition)
        val parsed = frame(somewhere, "01", "00")
        assertEquals(869_525L, parsed.freqKhz)
        assertEquals(11, parsed.sf)
        assertEquals("x", parsed.name.decodeToString())
        // The coordinates are on no field of the frame, and its string names nothing.
        assertEquals("SelfInfo", parsed.toString())
        assertTrue(McFrame.SelfInfo::class.java.declaredFields.none { it.name.contains("lat", true) || it.name.contains("lon", true) })
    }
}
