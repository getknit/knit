package app.getknit.knit.fieldlog

import app.getknit.knit.call.CallEndReason
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CaptureProfile
import app.getknit.knit.call.CaptureWatch
import app.getknit.knit.call.DirectLegEnd
import app.getknit.knit.call.IceNetwork
import app.getknit.knit.call.IceProtocol
import app.getknit.knit.call.IceType
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.ConnectFailReason
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.bluetooth.CodedPhyMode
import app.getknit.knit.mesh.bluetooth.LinkPhy
import app.getknit.knit.mesh.lora.LoraGatewayPolicy
import app.getknit.knit.mesh.lora.LoraLinkEnd
import app.getknit.knit.mesh.lora.LoraRegion
import app.getknit.knit.mesh.lora.ModemPreset
import app.getknit.knit.mesh.power.LowPowerReason
import app.getknit.knit.mesh.spool.SpoolTicketWord
import app.getknit.knit.mesh.spool.SpoolUploadsWord
import app.getknit.knit.mesh.spool.SpoolVerdict
import app.getknit.knit.mesh.wifiaware.NanDormancyPolicy
import app.getknit.knit.mesh.wifiaware.NanIcmPolicy
import app.getknit.knit.net.InternetGate
import app.getknit.knit.ui.BackgroundBattery
import app.getknit.knit.ui.call.TalkPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The field log's line format is a schema other people's analysis reads (`docs/FIELD_LOG.md`,
 * `scripts/fieldlog.py`): every event kind has its golden line here, so a renamed field or kind fails a test
 * rather than a contributor's merge. A new kind adds its line.
 */
class FieldEventSchemaTest {
    private val names = Pseudonyms(ByteArray(Pseudonyms.SALT_BYTES))

    private fun line(event: FieldEvent) = FieldLine.render(42, event, names)

    @Test
    fun everyKindKeepsItsLine() {
        assertEquals("""{"t":42,"e":"mesh_on","vc":31}""", line(FieldEvent.MeshOn(31)))
        assertEquals("""{"t":42,"e":"mesh_off"}""", line(FieldEvent.MeshOff))
        assertEquals(
            """{"t":42,"e":"power","screen":true,"charging":false,"low":true}""",
            line(FieldEvent.Power(screenOn = true, charging = false, batteryLow = true)),
        )
        assertEquals(
            """{"t":42,"e":"battery","usage":"Restricted","exempt":true,"bucket":"RESTRICTED"}""",
            line(FieldEvent.Battery(BackgroundBattery.Restricted, exempt = true, bucket = StandbyBucket.RESTRICTED)),
        )
        assertEquals(
            """{"t":42,"e":"radio","radio":"Bluetooth","health":"Healthy","linked":2,"nearby":3,"contended":false,"held":false}""",
            line(FieldEvent.Radio(TransportKind.Bluetooth, TransportHealth.Healthy, 2, 3, contended = false, initiatorHeld = false)),
        )
        assertEquals(
            """{"t":42,"e":"counters","relayed":4,"btConnectFails":1}""",
            line(FieldEvent.Counters(linkedMapOf("relayed" to 4L, "btConnectFails" to 1L))),
        )
        assertEquals(
            """{"t":42,"e":"latency","dir":"ACK","plane":"Nearby","n":7,"p50":900,"p90":4000,"max":61000}""",
            line(FieldEvent.Latency(LatencyDirection.ACK, DeliveryPlane.Nearby, 7, 900, 4_000, 61_000)),
        )
    }

    @Test
    fun theBluetoothLinkKindsKeepTheirLines() {
        val peer = "abcdefghijklmnopqrstuvwxyz"
        val p = names.of(peer)
        assertEquals(
            """{"t":42,"e":"bt_up","peer":"$p","dialed":true,"dial":850,"sighted":true,"links":2,"relink":61000}""",
            line(FieldEvent.BtUp(peer, dialMs = 850, sighted = true, links = 2, relinkMs = 61_000)),
        )
        assertEquals(
            """{"t":42,"e":"bt_up","peer":"$p","dialed":false,"sighted":false,"links":1}""",
            line(FieldEvent.BtUp(peer, dialMs = null, sighted = false, links = 1)),
        )
        assertEquals(
            """{"t":42,"e":"bt_down","peer":"$p","reason":"EOF","life":300000,"links":0,"phy":"CODED","rssi":-88}""",
            line(FieldEvent.BtDown(peer, BtDownReason.EOF, lifetimeMs = 300_000, links = 0, phy = LinkPhy.CODED, rssi = -88)),
        )
        // A link closed for its silence (#203): the teardown's "silent" reason, by name.
        assertEquals(
            """{"t":42,"e":"bt_down","peer":"$p","reason":"SILENT","life":2160000,"links":1}""",
            line(FieldEvent.BtDown(peer, BtDownReason.of("silent"), lifetimeMs = 2_160_000, links = 1)),
        )
        assertEquals(
            """{"t":42,"e":"bt_fail","peer":"$p","reason":"TIMEOUT","dur":12000,"streak":3}""",
            line(FieldEvent.BtFail(peer, ConnectFailReason.TIMEOUT, durationMs = 12_000, streak = 3)),
        )
    }

    /** GitHub #44: Wi-Fi Aware's dormancy transitions and the person's switch. */
    @Test
    fun theWifiAwareDormancyAndSwitchKeepTheirLines() {
        assertEquals(
            """{"t":42,"e":"nan_dormant","on":true,"why":"IDLE"}""",
            line(FieldEvent.NanDormant(on = true, why = NanDormancyPolicy.Why.IDLE)),
        )
        assertEquals(
            """{"t":42,"e":"nan_dormant","on":false,"why":"SIGHTING"}""",
            line(FieldEvent.NanDormant(on = false, why = NanDormancyPolicy.Why.SIGHTING)),
        )
        assertEquals("""{"t":42,"e":"aware_switch","on":false}""", line(FieldEvent.AwareSwitch(on = false)))
    }

    @Test
    fun theSampleAndOtherRadioKindsKeepTheirLines() {
        val peer = "abcdefghijklmnopqrstuvwxyz"
        val p = names.of(peer)
        assertEquals(
            """{"t":42,"e":"bt_phy","peer":"$p","coded":true,"rssi":-84}""",
            line(FieldEvent.BtPhy(peer, toCoded = true, rssi = -84)),
        )
        assertEquals(
            """{"t":42,"e":"bt_link","peer":"$p","age":60000,"tx":true,"rx":false,"phy":"TWO_M","rssi":-61,""" +
                """"interval":15000,"mtu":517,"earned":true}""",
            line(FieldEvent.BtLink(peer, 60_000, feeding = true, receiving = false, LinkPhy.TWO_M, -61, 15_000, 517, earned = true)),
        )
        assertEquals(
            """{"t":42,"e":"bt_link","peer":"$p","age":5,"tx":false,"rx":false}""",
            line(FieldEvent.BtLink(peer, 5, feeding = false, receiving = false)),
        )
        assertEquals(
            """{"t":42,"e":"bt_peer","peer":"$p","linked":false,"rssi":-79,"rssi1m":-91,"rssiCoded":-91,"codedLag":12000}""",
            line(FieldEvent.BtPeer(peer, linked = false, rssi = -79, rssi1m = -91, rssiCoded = -91, codedLagMs = 12_000)),
        )
        assertEquals(
            """{"t":42,"e":"bt_scan","links":2,"le":3,"sighted":5,"hunting":false,"coded":"OFF"}""",
            line(FieldEvent.BtScan(links = 2, leLinks = 3, sighted = 5, hunting = false, codedMode = CodedPhyMode.OFF)),
        )
        assertEquals(
            """{"t":42,"e":"bt_orphan","peer":"$p","linked":true,"armed":true,"tries":2,"cleared":false,"age":181000}""",
            line(FieldEvent.BtOrphan(peer, linked = true, armed = true, tries = 2, cleared = false, ageMs = 181_000)),
        )
        assertEquals(
            """{"t":42,"e":"nan_up","peer":"$p","initiator":true,"links":1}""",
            line(FieldEvent.NanUp(peer, initiator = true, links = 1)),
        )
        assertEquals(
            """{"t":42,"e":"nan_down","peer":"$p","initiator":false,"life":4200,"clean":true}""",
            line(FieldEvent.NanDown(peer, initiator = false, lifetimeMs = 4_200, clean = true)),
        )
        assertEquals("""{"t":42,"e":"nan_cycle"}""", line(FieldEvent.NanCycle))
        assertEquals(
            """{"t":42,"e":"nan_icm","on":true,"band":"B5","why":"STA_CHANNEL","sta":5745}""",
            line(FieldEvent.NanIcm(on = true, band = NanIcmPolicy.Band.B5, why = NanIcmPolicy.Reason.STA_CHANNEL, staMhz = 5745)),
        )
        // Off writes no band, even if one is handed in; an unknown STA writes no frequency.
        assertEquals(
            """{"t":42,"e":"nan_icm","on":false,"why":"AUDIO"}""",
            line(FieldEvent.NanIcm(on = false, band = NanIcmPolicy.Band.B24, why = NanIcmPolicy.Reason.AUDIO, staMhz = null)),
        )
        assertEquals(
            """{"t":42,"e":"nan_icm_lit","lit":95000,"up":300000,"configs":7}""",
            line(FieldEvent.NanIcmLit(litMs = 95_000, upMs = 300_000, configs = 7)),
        )
        assertEquals(
            """{"t":42,"e":"low_power","on":true,"why":"BATTERY_SAVER"}""",
            line(FieldEvent.LowPower(LowPowerReason.BATTERY_SAVER)),
        )
        assertEquals("""{"t":42,"e":"low_power","on":false}""", line(FieldEvent.LowPower(null)))
        assertEquals(
            """{"t":42,"e":"lora","role":"ACTIVE","snr10":-75,"rssi":-118,"queued":2,"heard":9,"boards":3,""" +
                """"airUsed":4100,"airBudget":36000}""",
            line(FieldEvent.Lora(LoraGatewayPolicy.Role.ACTIVE, -75, -118, 2, 9, 3, 4_100, 36_000)),
        )
    }

    @Test
    fun theCallStartAndEndKeepTheirLines() {
        val peer = "abcdefghijklmnopqrstuvwxyz"
        val p = names.of(peer)
        assertEquals(
            """{"t":42,"e":"call_start","peer":"$p","out":true,"walkie":false}""",
            line(FieldEvent.CallStart(peer, outgoing = true, walkie = false)),
        )
        assertEquals(
            """{"t":42,"e":"call_end","peer":"$p","out":true,"walkie":false,"reason":"USER","last":"Connected","life":95000,""" +
                """"byPeer":false,"missed":false,"degraded":false,"ring":600,"answer":4000,"setup":5200,"talk":89800,""" +
                """"restarts":1,"drops":1,"relay":false,"direct":true,"directUp":true,"turn":2,"stun":1,"unreached":false,""" +
                """"unproven":false}""",
            line(
                FieldEvent.CallEnd(
                    peer,
                    outgoing = true,
                    walkie = false,
                    reason = CallEndReason.USER,
                    lastPhase = CallPhase.Connected,
                    lifeMs = 95_000,
                    ringMs = 600,
                    answerMs = 4_000,
                    setupMs = 5_200,
                    talkMs = 89_800,
                    restarts = 1,
                    drops = 1,
                    directOffered = true,
                    directTaken = true,
                    turn = 2,
                    stun = 1,
                ),
            ),
        )
        assertEquals(
            """{"t":42,"e":"call_end","peer":"$p","out":false,"walkie":true,"reason":"STALE","life":0,"byPeer":false,""" +
                """"missed":true,"degraded":false,"restarts":0,"drops":0,"relay":false,"direct":false,"directUp":false,""" +
                """"unreached":false,"unproven":false}""",
            line(
                FieldEvent.CallEnd(peer, outgoing = false, walkie = true, CallEndReason.STALE, lastPhase = null, lifeMs = 0, missed = true),
            ),
        )
    }

    @Test
    fun theCallMediaKindsKeepTheirLines() {
        assertEquals(
            """{"t":42,"e":"call_route","local":"SRFLX","remote":"RELAY","proto":"UDP","net":"CELLULAR","direct":false,"seq":2}""",
            line(FieldEvent.CallRoute(IceType.SRFLX, IceType.RELAY, IceProtocol.UDP, IceNetwork.CELLULAR, direct = false, seq = 2)),
        )
        assertEquals(
            """{"t":42,"e":"call_media","lost":false,"rtt":48,"jitter":6,"buffer":60,"lossIn10":12,"lossOut10":3,""" +
                """"lossCall10":8,"send":32,"recv":31,"pkts":1500,"mic":120,"heard":90,"local":"RELAY","remote":"SRFLX",""" +
                """"proto":"UDP","relayProto":"TLS","net":"WIFI","direct":false,"pairs":3,"pending":1,"micPath":"STANDARD",""" +
                """"micHealth":"LIVE"}""",
            line(
                FieldEvent.CallSample(
                    reconnecting = false,
                    rttMs = 48,
                    jitterMs = 6,
                    bufferMs = 60,
                    lossIn10 = 12,
                    lossOut10 = 3,
                    lossCall10 = 8,
                    sendKbps = 32,
                    receiveKbps = 31,
                    packets = 1_500,
                    micPermille = 120,
                    heardPermille = 90,
                    local = IceType.RELAY,
                    remote = IceType.SRFLX,
                    protocol = IceProtocol.UDP,
                    relayProtocol = IceProtocol.TLS,
                    network = IceNetwork.WIFI,
                    pairs = 3,
                    pending = 1,
                    micPath = CaptureProfile.STANDARD,
                    micHealth = CaptureWatch.Health.LIVE,
                ),
            ),
        )
        assertEquals(
            """{"t":42,"e":"call_media","lost":true,"direct":false,"pairs":0,"pending":0}""",
            line(FieldEvent.CallSample(reconnecting = true)),
        )
    }

    @Test
    fun theDirectLegKeepsItsLine() {
        assertEquals(
            """{"t":42,"e":"call_direct","host":true,"end":"CALL","group":3100,"lane":6400,"life":120000,"tries":0}""",
            line(
                FieldEvent.CallDirectLeg(
                    hosting = true,
                    DirectLegEnd.CALL,
                    groupMs = 3_100,
                    laneMs = 6_400,
                    lifeMs = 120_000,
                    joinTries = 0,
                ),
            ),
        )
        assertEquals(
            """{"t":42,"e":"call_direct","host":false,"end":"RADIO","life":21000,"tries":2}""",
            line(
                FieldEvent.CallDirectLeg(
                    hosting = false,
                    DirectLegEnd.RADIO,
                    groupMs = null,
                    laneMs = null,
                    lifeMs = 21_000,
                    joinTries = 2,
                ),
            ),
        )
    }

    @Test
    fun theWalkieKindsKeepTheirLines() {
        assertEquals(
            """{"t":42,"e":"talk","dir":"OUT","chunks":6,"ms":2880,"sent":6,"path":"WIFI_AWARE","held":true}""",
            line(FieldEvent.Talk(TalkDirection.OUT, chunks = 6, audioMs = 2_880, sent = 6, path = TalkPath.WIFI_AWARE, held = true)),
        )
        assertEquals(
            """{"t":42,"e":"talk","dir":"IN","chunks":5,"lost":1,"wait":700,"spread":2400}""",
            line(FieldEvent.Talk(TalkDirection.IN, chunks = 5, lost = 1, waitMs = 700, spreadMs = 2_400)),
        )
        assertEquals(
            """{"t":42,"e":"walkie_end","burstsOut":3,"burstsIn":2,"chunksOut":18,"failed":1,"chunksIn":11,"lost":2,""" +
                """"transit":420,"jitter":35,"hand":12,"frameOut":310,"frameIn":305}""",
            line(FieldEvent.WalkieEnd(3, 2, 18, 1, 11, 2, transitMs = 420, jitterMs = 35, handMs = 12, frameOut = 310, frameIn = 305)),
        )
        assertEquals(
            """{"t":42,"e":"walkie_end","burstsOut":0,"burstsIn":0,"chunksOut":0,"failed":0,"chunksIn":0,"lost":0}""",
            line(FieldEvent.WalkieEnd(0, 0, 0, 0, 0, 0)),
        )
    }

    @Test
    fun theLoraKindsKeepTheirLines() {
        assertEquals(
            """{"t":42,"e":"lora","role":"PASSIVE","queued":0,"heard":2,"boards":1,"gateways":1,"pocket":3,"sighted":4,""" +
                """"covered":2,"free":14,"battery":71,"powered":false,"chUtil10":123,"airTx10":8}""",
            line(
                FieldEvent.Lora(
                    LoraGatewayPolicy.Role.PASSIVE,
                    null,
                    null,
                    0,
                    2,
                    1,
                    null,
                    null,
                    gatewaysHeard = 1,
                    pocketLinks = 3,
                    pocketSightings = 4,
                    internetCovered = 2,
                    queueFree = 14,
                    batteryPercent = 71,
                    powered = false,
                    channelUtilTenths = 123,
                    airUtilTxTenths = 8,
                ),
            ),
        )
        assertEquals(
            """{"t":42,"e":"lora_up","dial":4200,"tries":2,"mtu":512,"region":"EU_868","preset":"LONG_FAST","slot":true,""" +
                """"hops":3,"xeddsa":true,"relink":95000}""",
            line(
                FieldEvent.LoraUp(
                    dialMs = 4_200,
                    tries = 2,
                    mtu = 512,
                    region = LoraRegion.EU_868,
                    preset = ModemPreset.LONG_FAST,
                    dedicatedSlot = true,
                    hopLimit = 3,
                    xeddsa = true,
                    relinkMs = 95_000,
                ),
            ),
        )
        assertEquals("""{"t":42,"e":"lora_up","tries":0,"mtu":23}""", line(FieldEvent.LoraUp(dialMs = null, tries = 0, mtu = 23)))
        assertEquals(
            """{"t":42,"e":"lora_down","reason":"GATT_DROP","status":8,"life":600000}""",
            line(FieldEvent.LoraDown(LoraLinkEnd.GATT_DROP, lifeMs = 600_000, status = 8)),
        )
        assertEquals(
            """{"t":42,"e":"lora_fail","reason":"DIAL_TIMEOUT","streak":3,"wait":20000}""",
            line(FieldEvent.LoraFail(LoraLinkEnd.DIAL_TIMEOUT, streak = 3, waitMs = 20_000)),
        )
        assertEquals(
            """{"t":42,"e":"lora_role","role":"ACTIVE","pocket":0,"gateways":2}""",
            line(FieldEvent.LoraRole(LoraGatewayPolicy.Role.ACTIVE, pocketLinks = 0, gatewaysHeard = 2)),
        )
    }

    @Test
    fun theInternetAndSpoolKindsKeepTheirLines() {
        val relay = "wss://relay.example/v1"
        val r = names.ofRelay(relay)
        assertEquals(
            """{"t":42,"e":"net","route":"CELLULAR","restricted":false}""",
            line(FieldEvent.Net(InternetGate.RouteKind.CELLULAR, restricted = false)),
        )
        assertEquals(
            """{"t":42,"e":"spool_up","relay":"$r","hello":640,"fails":2,"pow":0,"attach":true,"ent":true,"turn":false,"cast":true}""",
            line(FieldEvent.SpoolUp(relay, 640, 2, 0, attachments = true, ent = true, turn = false, cast = true)),
        )
        assertEquals(
            """{"t":42,"e":"spool_down","relay":"$r","reason":"CLOSED","code":4003,"life":3600000,"ours":false}""",
            line(FieldEvent.SpoolDown(relay, SpoolVerdict.CLOSED, 3_600_000, ours = false, code = 4003)),
        )
        assertEquals(
            """{"t":42,"e":"spool_fail","relay":"$r","reason":"HTTP","code":503,"dur":900,"streak":4,"retryAfter":30000}""",
            line(FieldEvent.SpoolFail(relay, SpoolVerdict.HTTP, 900, 4, code = 503, retryAfterMs = 30_000)),
        )
        assertEquals(
            """{"t":42,"e":"spool_err","relay":"$r","code":"QUOTA"}""",
            line(FieldEvent.SpoolErr(relay, SpoolVerdict.QUOTA)),
        )
        assertEquals(
            """{"t":42,"e":"spool","relay":"$r","up":true,"scopes":5,"converged":4,"local":120,"remote":118,"invalid":0,""" +
                """"fails":0,"pow":8,"ticket":"ACCEPTED","uploads":"OPEN"}""",
            line(
                FieldEvent.Spool(
                    relay,
                    true,
                    5,
                    4,
                    120,
                    118,
                    0,
                    0,
                    8,
                    ticket = SpoolTicketWord.ACCEPTED,
                    uploads = SpoolUploadsWord.OPEN,
                ),
            ),
        )
    }

    @Test
    fun aRelayIsWrittenAsItsPseudonymNeverItsUrlAndNeverAsAPeer() {
        val url = "wss://relay.example/v1"

        val rendered = line(FieldEvent.SpoolErr(url, SpoolVerdict.RATE))

        assertTrue(rendered, Regex(""""relay":"r[0-9a-f]{8}"""").containsMatchIn(rendered))
        assertTrue(rendered, !rendered.contains("relay.example"))
        assertTrue("a relay's input is its own domain", names.ofRelay(url).drop(1) != names.of(url).drop(1))
    }

    @Test
    fun aPeerIsWrittenAsItsPseudonymNeverItsNodeId() {
        val nodeId = "abcdefghijklmnopqrstuvwxyz"

        val rendered = line(FieldEvent.BtUp(nodeId, dialMs = null, sighted = true, links = 1))

        assertTrue(rendered, Regex(""""peer":"p[0-9a-f]{8}"""").containsMatchIn(rendered))
        assertTrue(rendered, !rendered.contains(nodeId))
    }

    @Test
    fun aFieldNameCannotBreakOutOfItsQuotes() {
        assertEquals("""{"t":42,"e":"counters","xy":1}""", line(FieldEvent.Counters(mapOf("x\",\"y" to 1L))))
    }
}
