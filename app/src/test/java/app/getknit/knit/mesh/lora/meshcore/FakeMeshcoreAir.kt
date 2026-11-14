package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.BondState
import app.getknit.knit.mesh.lora.DialMode
import app.getknit.knit.mesh.lora.GattResult
import app.getknit.knit.mesh.lora.meshcore.MeshcoreProto.u32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

/**
 * A few MeshCore companion boards and the air between them, as `companion-v1.18.0` behaves (ADR 2026-11.gsqu, Stage 3b):
 * the command handler of `MyMesh.cpp` per board, answering **every** connected interface; contacts with routes, the
 * 8-entry circular ack table and a 16-packet pool; the offline queue a `SYNC_NEXT_MESSAGE` pops before it replies; and an
 * air of direct neighbours and repeaters on which a contact's UNKNOWN path floods, a flood is re-aired one hop by a
 * repeater, a flooded DM draws a flooded PATH return that rewrites the sender's route and pushes `0x81` on both boards
 * (bench Q13), a receiver's own UNKNOWN record floods the ack, and a byte-identical packet is dropped by every board that
 * already heard it (Q10, Q14).
 *
 * Each modelled behaviour is named against the simulator's frames and events (`/media/walter/data/meshcore-sim-runs/r1`)
 * in `MeshcoreFakeVsSimTest`. Everything is timed on [scope]'s virtual clock.
 */
internal class FakeMeshcoreAir(
    val scope: CoroutineScope,
    val now: () -> Long,
) {
    val boards = LinkedHashMap<String, FakeMeshcoreBoard>()
    private val links = HashSet<Pair<String, String>>()
    private val repeaters = HashSet<String>()

    /** Every airing, in order: what the simulator's `.events.ndjson` records. */
    val airings = mutableListOf<Airing>()

    /** How long a confirmation takes from the send (Q12: a 469-ms trip). */
    var confirmDelayMs = 470L

    /** How long one packet holds the pool before it has aired. */
    var airMs = 600L

    /** A repeater's re-airing delay (Q15: about 1.45 s). */
    var repeatDelayMs = 1_450L

    /** Drops a DM attempt on the air before anyone hears it ([attempt] 0..3). */
    var loseDm: (attempt: Int) -> Boolean = { false }

    /** Drops the ack of a delivered DM on its way back. */
    var loseAck: (attempt: Int) -> Boolean = { false }

    class Airing(
        val from: String,
        val type: String,
        val flood: Boolean,
        val hops: Int,
    ) {
        override fun toString(): String = "$from $type ${if (flood) "flood" else "direct"} hops=$hops"
    }

    fun board(
        name: String,
        key: ByteArray = keyOf(name),
        maxContacts: Int = 100,
        maxChannels: Int = 4,
    ): FakeMeshcoreBoard = FakeMeshcoreBoard(this, name, key, maxContacts, maxChannels).also { boards[name] = it }

    fun link(
        a: String,
        b: String,
    ) {
        links += a to b
        links += b to a
    }

    /** A repeater node [name], in range of [neighbours]: it re-airs every flood it hears once, one hop on. */
    fun repeater(
        name: String,
        vararg neighbours: String,
    ) {
        repeaters += name
        neighbours.forEach { link(name, it) }
    }

    private fun neighbours(name: String): List<String> = links.filter { it.first == name }.map { it.second }

    /**
     * [from] airs a packet: every board in range hears it at [hops]; a repeater in range re-airs a flood once, a hop on.
     * [deliver] is each board's reception.
     */
    fun transmit(
        from: String,
        type: String,
        flood: Boolean,
        hops: Int = 0,
        seen: MutableSet<String> = HashSet(),
        deliver: (FakeMeshcoreBoard, Int) -> Unit,
    ) {
        airings += Airing(from, type, flood, hops)
        seen += from
        for (n in neighbours(from)) {
            // A node never takes back a packet it has aired: its own hash is in its table (Mesh::sendFlood).
            if (n !in seen) boards[n]?.let { deliver(it, hops) }
            if (flood && n in repeaters && n !in seen) {
                seen += n
                scope.launch {
                    delay(repeatDelayMs)
                    transmit(n, type, flood = true, hops = hops + 1, seen = seen, deliver = deliver)
                }
            }
        }
    }

    companion object {
        private val pairs = java.util.concurrent.ConcurrentHashMap<String, com.google.crypto.tink.subtle.Ed25519Sign.KeyPair>()

        /**
         * A board key: a real Ed25519 public key whose private half is seeded from [name] (so a fake board signs, as a
         * companion does, `MyMesh.cpp:1801`), never starting with the reserved `0x00` / `0xFF`.
         */
        fun keyOf(name: String): ByteArray = pairOf(name).publicKey.copyOf()

        private fun pairOf(name: String): com.google.crypto.tink.subtle.Ed25519Sign.KeyPair =
            pairs.getOrPut(name) {
                generateSequence(0) { it + 1 }
                    .map { n ->
                        val seed = MessageDigest.getInstance("SHA-256").digest(("meshcore-fake:$name:$n").encodeToByteArray())
                        com.google.crypto.tink.subtle.Ed25519Sign.KeyPair
                            .newKeyPairFromSeed(seed)
                    }.first { it.publicKey[0].toInt() != 0 && it.publicKey[0].toInt() != -1 }
            }

        /** The signer for [key] when it is a [keyOf] key; null for any other (a clone that reports a key it does not hold). */
        fun signerOf(key: ByteArray): com.google.crypto.tink.subtle.Ed25519Sign? =
            pairs.values.firstOrNull { it.publicKey.contentEquals(key) }?.let {
                com.google.crypto.tink.subtle
                    .Ed25519Sign(it.privateKey)
            }

        /** `PUBLIC_GROUP_PSK` (`MyMesh.cpp:110`). */
        val PUBLIC_PSK: ByteArray = Base64.getDecoder().decode("izOH6cXN6mrJ5e26oRXNcg==")
    }
}

/** One interface a board writes every answer and push to: our NUS channel, or another client. */
internal interface FakeIface {
    val connected: Boolean

    fun deliver(frame: ByteArray)
}

/** One contact on a fake board, as `ContactInfo` keeps it. */
internal class FakeContact(
    val key: ByteArray,
    var type: Int,
    var flags: Int,
    var outPathLen: Int,
    var name: ByteArray,
    var lastmod: Long,
)

/** One companion board running the `MyMesh.cpp` command handler (see [FakeMeshcoreAir]). */
@Suppress("TooManyFunctions", "LargeClass") // one handler per command, as the firmware's handleCmdFrame is one function
internal class FakeMeshcoreBoard(
    private val air: FakeMeshcoreAir,
    val name: String,
    val key: ByteArray,
    var maxContacts: Int,
    val maxChannels: Int,
) {
    var versionCode = 14
    var advertName: ByteArray = name.encodeToByteArray()
    var manualAdd = 0
    var autoadd = 0
    var autoaddMaxHops = 0
    var multiAcks = 0
    var defaultScope = false
    var scopeOverride = false
    var clockSecs = 1_790_000_000L
    var batteryMv = 4_100
    var freqKhz = 869_525L
    var bwHz = 250_000L
    var sf = 11
    var cr = 5
    var appTargetVersion = 0
    var uptimeBaseMs = 0L

    val channels = Array(maxChannels) { ByteArray(0) to ByteArray(MeshcoreProto.SECRET_BYTES) }

    /** The board's sign buffer (`sign_data`), null when none is open. */
    var signBuffer: ByteArray? = null

    /** False: a clone at the address that reports this board's key (it is in the board's adverts) but cannot sign with it. */
    var canSign = true

    /** `SIGN_FINISH`es left unanswered (a lost notify): each one drops its answer. */
    var dropSignFinish = 0

    /** A key `SELF_INFO` reports instead of [key] (another client imported one): signing stays [key]'s. */
    var reportedKey: ByteArray? = null

    /** From which `APP_START` answer (1-based, counted over the board's life) [reportedKey] applies; null: from the first. */
    var reportedKeyFrom: Int? = null
    private var appStarts = 0
    val contacts = mutableListOf<FakeContact>()
    val offline = ArrayDeque<ByteArray>()
    var offlineCap = 256
    private val ackTable = IntArray(MeshcoreAckTable.EXPECTED_ACK_TABLE_SIZE)
    private var nextAck = 0
    var outbound = 0
    var sent = 0L
    var txAirSecs = 0L
    var rxAirSecs = 0L
    private val heard = HashSet<String>()
    val ifaces = mutableListOf<FakeIface>()

    /** Commands this board answered, by code (the firmware has no such count; the tests read it). */
    val commands = mutableListOf<Int>()

    /** DMs its app was handed, as the receive frames' text. */
    val acksAired = mutableListOf<Boolean>()

    init {
        channels[0] = "Public".encodeToByteArray() to FakeMeshcoreAir.PUBLIC_PSK
    }

    /** The air's scope, on which a delayed notify lands. */
    val scope: CoroutineScope get() = air.scope

    val uptimeSecs: Long get() = (air.now() - uptimeBaseMs) / 1_000

    /** The board reboots: its uptime restarts and its RAM state (the ack table, the scope override) is gone. */
    fun reboot() {
        uptimeBaseMs = air.now()
        ackTable.fill(0)
        scopeOverride = false
        outbound = 0
    }

    fun contact(key: ByteArray): FakeContact? = contacts.firstOrNull { it.key.contentEquals(key) }

    /** Adds [other] as a contact with route [outPathLen] (`0` pinned, `0xFF` unknown), favourite like Knit's. */
    fun addContact(
        other: FakeMeshcoreBoard,
        outPathLen: Int = 0,
        name: String = MeshcoreNames.forKey(other.key),
        flags: Int = MeshcoreProto.CONTACT_FLAG_FAVOURITE,
    ): FakeContact =
        FakeContact(other.key, MeshcoreProto.ADV_TYPE_CHAT, flags, outPathLen, name.encodeToByteArray(), clockSecs).also { contacts += it }

    fun setChannel(
        index: Int,
        name: String,
        secret: ByteArray,
    ) {
        channels[index] = name.encodeToByteArray() to secret
    }

    /** Another client (or the firmware) drifts a route to UNKNOWN, pushing nothing (`RESET_PATH`, Q13). */
    fun driftPath(other: FakeMeshcoreBoard) {
        contact(other.key)?.outPathLen = MeshcoreProto.PATH_UNKNOWN
    }

    /** Writes [frame] to every connected interface (`MultiSerialInterface`). */
    fun writeAll(frame: ByteArray) {
        ifaces.filter { it.connected }.forEach { it.deliver(frame.copyOf()) }
    }

    private fun ok() = writeAll(byteArrayOf(0))

    private fun err(code: Int) = writeAll(byteArrayOf(1, code.toByte()))

    /** `handleCmdFrame` (`MyMesh.cpp:1055`). */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun handle(frame: ByteArray) {
        if (frame.isEmpty()) return
        val code = frame[0].toInt() and 0xFF
        commands += code
        when (code) {
            MeshcoreProto.CMD_DEVICE_QUERY -> {
                deviceInfo(frame)
            }

            MeshcoreProto.CMD_APP_START -> {
                selfInfo()
            }

            MeshcoreProto.CMD_GET_CHANNEL -> {
                getChannel(frame[1].toInt() and 0xFF)
            }

            MeshcoreProto.CMD_SET_CHANNEL -> {
                setChannelCmd(frame)
            }

            MeshcoreProto.CMD_SET_DEVICE_TIME -> {
                setTime(MeshcoreProto.u32(frame, 1))
            }

            MeshcoreProto.CMD_GET_DEVICE_TIME -> {
                writeAll(byteArrayOf(MeshcoreProto.RESP_CURR_TIME.toByte()) + u32(clockSecs))
            }

            MeshcoreProto.CMD_SET_FLOOD_SCOPE_KEY -> {
                scopeOverride = frame.size >= 18
                ok()
            }

            MeshcoreProto.CMD_GET_DEFAULT_FLOOD_SCOPE -> {
                writeAll(
                    if (defaultScope) byteArrayOf(MeshcoreProto.RESP_DEFAULT_FLOOD_SCOPE.toByte()) + ByteArray(47) else byteArrayOf(28),
                )
            }

            MeshcoreProto.CMD_GET_STATS -> {
                stats(frame[1].toInt())
            }

            MeshcoreProto.CMD_GET_BATT_AND_STORAGE -> {
                writeAll(byteArrayOf(12, batteryMv.toByte(), (batteryMv shr 8).toByte()) + ByteArray(8))
            }

            MeshcoreProto.CMD_SET_OTHER_PARAMS -> {
                manualAdd = frame[1].toInt() and 0xFF
                ok()
            }

            MeshcoreProto.CMD_GET_AUTOADD_CONFIG -> {
                writeAll(byteArrayOf(25, autoadd.toByte(), autoaddMaxHops.toByte()))
            }

            MeshcoreProto.CMD_SET_AUTOADD_CONFIG -> {
                autoadd = frame[1].toInt() and 0xFF
                if (frame.size >= 3) autoaddMaxHops = frame[2].toInt()
                ok()
            }

            MeshcoreProto.CMD_SET_ADVERT_NAME -> {
                advertName = frame.copyOfRange(1, minOf(frame.size, 32))
                ok()
            }

            MeshcoreProto.CMD_SYNC_NEXT_MESSAGE -> {
                val next = offline.removeFirstOrNull()
                writeAll(next ?: byteArrayOf(MeshcoreProto.RESP_NO_MORE_MESSAGES.toByte()))
            }

            MeshcoreProto.CMD_GET_CONTACT_BY_KEY -> {
                val c = contact(frame.copyOfRange(1, 33))
                if (c == null) err(MeshcoreProto.ERR_NOT_FOUND) else writeAll(contactFrame(MeshcoreProto.RESP_CONTACT, c))
            }

            MeshcoreProto.CMD_ADD_UPDATE_CONTACT -> {
                addUpdate(frame)
            }

            MeshcoreProto.CMD_REMOVE_CONTACT -> {
                val c = contact(frame.copyOfRange(1, 33))
                if (c == null) err(MeshcoreProto.ERR_NOT_FOUND) else ok().also { contacts.remove(c) }
            }

            MeshcoreProto.CMD_RESET_PATH -> {
                val c = contact(frame.copyOfRange(1, 33))
                if (c == null) {
                    err(MeshcoreProto.ERR_NOT_FOUND)
                } else {
                    c.outPathLen = MeshcoreProto.PATH_UNKNOWN
                    ok()
                }
            }

            MeshcoreProto.CMD_GET_CONTACTS -> {
                val since = if (frame.size >= 5) MeshcoreProto.u32(frame, 1) else 0L
                writeAll(byteArrayOf(2) + u32(contacts.size.toLong()))
                contacts.filter { it.lastmod > since }.forEach { writeAll(contactFrame(MeshcoreProto.RESP_CONTACT, it)) }
                writeAll(byteArrayOf(4) + u32(contacts.maxOfOrNull { it.lastmod } ?: 0))
            }

            MeshcoreProto.CMD_SEND_TXT_MSG -> {
                sendText(frame)
            }

            // `MyMesh.cpp:1781-1815`: one buffer for every client; SIGN_FINISH signs it with the board's own key.
            MeshcoreProto.CMD_SIGN_START -> {
                signBuffer = ByteArray(0)
                writeAll(byteArrayOf(MeshcoreProto.RESP_SIGN_START.toByte(), 0) + u32(8 * 1024L))
            }

            MeshcoreProto.CMD_SIGN_DATA -> {
                val buf = signBuffer
                if (buf == null) err(MeshcoreProto.ERR_BAD_STATE) else ok().also { signBuffer = buf + frame.copyOfRange(1, frame.size) }
            }

            MeshcoreProto.CMD_SIGN_FINISH -> {
                if (dropSignFinish > 0) {
                    dropSignFinish--
                    return
                }
                val buf = signBuffer
                val signer = if (canSign) FakeMeshcoreAir.signerOf(key) else null
                signBuffer = null
                when {
                    buf == null -> {
                        err(MeshcoreProto.ERR_BAD_STATE)
                    }

                    // A clone at the address reporting a key it does not hold: whatever it answers, it is no signature by it.
                    signer == null -> {
                        writeAll(
                            byteArrayOf(MeshcoreProto.RESP_SIGNATURE.toByte()) + ByteArray(MeshcoreProto.SIGNATURE_BYTES),
                        )
                    }

                    else -> {
                        writeAll(byteArrayOf(MeshcoreProto.RESP_SIGNATURE.toByte()) + signer.sign(buf))
                    }
                }
            }

            MeshcoreProto.CMD_SEND_CHANNEL_DATA -> {
                sendChannelData(frame)
            }

            MeshcoreProto.CMD_SEND_CHANNEL_TXT_MSG -> {
                sendChannelText(frame)
            }

            else -> {
                err(MeshcoreProto.ERR_UNSUPPORTED_CMD)
            }
        }
    }

    private fun deviceInfo(frame: ByteArray) {
        if (frame.size >= 2) appTargetVersion = frame[1].toInt()
        val out = ByteArray(82)
        out[0] = MeshcoreProto.RESP_DEVICE_INFO.toByte()
        out[1] = versionCode.toByte()
        out[2] = (maxContacts / 2).toByte()
        out[3] = maxChannels.toByte()
        "fake-v1.18".encodeToByteArray().copyInto(out, 60)
        writeAll(out)
    }

    private fun selfInfo() {
        val out = ByteArray(58)
        out[0] = MeshcoreProto.RESP_SELF_INFO.toByte()
        out[1] = 1
        out[2] = 20
        out[3] = 20
        appStarts++
        val rekeyed = reportedKey?.takeIf { reportedKeyFrom?.let { from -> appStarts >= from } ?: true }
        (rekeyed ?: key).copyInto(out, 4)
        out[44] = multiAcks.toByte()
        out[47] = manualAdd.toByte()
        u32(freqKhz).copyInto(out, 48)
        u32(bwHz).copyInto(out, 52)
        out[56] = sf.toByte()
        out[57] = cr.toByte()
        writeAll(out + advertName)
    }

    private fun getChannel(index: Int) {
        if (index >= maxChannels) return err(MeshcoreProto.ERR_NOT_FOUND)
        val (n, secret) = channels[index]
        writeAll(byteArrayOf(MeshcoreProto.RESP_CHANNEL_INFO.toByte(), index.toByte()) + n.copyOf(32) + secret)
    }

    private fun setChannelCmd(frame: ByteArray) {
        if (frame.size >= 2 + 32 + 32) return err(MeshcoreProto.ERR_UNSUPPORTED_CMD)
        val index = frame[1].toInt() and 0xFF
        if (frame.size < 2 + 32 + 16 || index >= maxChannels) return err(MeshcoreProto.ERR_NOT_FOUND)
        channels[index] = MeshcoreProto.cBytes(frame, 2, 32) to frame.copyOfRange(34, 50)
        ok()
    }

    private fun setTime(secs: Long) {
        if (secs >= clockSecs) {
            clockSecs = secs
            ok()
        } else {
            err(MeshcoreProto.ERR_ILLEGAL_ARG)
        }
    }

    private fun stats(type: Int) {
        when (type) {
            MeshcoreProto.STATS_CORE -> {
                writeAll(
                    byteArrayOf(24, 0, batteryMv.toByte(), (batteryMv shr 8).toByte()) + u32(uptimeSecs) +
                        byteArrayOf(0, 0, outbound.toByte()),
                )
            }

            MeshcoreProto.STATS_RADIO -> {
                writeAll(byteArrayOf(24, 1, 0x9C.toByte(), 0xFF.toByte(), (-80).toByte(), 32) + u32(txAirSecs) + u32(rxAirSecs))
            }

            MeshcoreProto.STATS_PACKETS -> {
                writeAll(byteArrayOf(24, 2) + u32(0) + u32(sent) + ByteArray(20))
            }

            else -> {
                err(MeshcoreProto.ERR_ILLEGAL_ARG)
            }
        }
    }

    private fun contactFrame(
        code: Int,
        c: FakeContact,
    ): ByteArray {
        val out = ByteArray(MeshcoreProto.CONTACT_FULL_SIZE)
        out[0] = code.toByte()
        c.key.copyInto(out, 1)
        out[33] = c.type.toByte()
        out[34] = c.flags.toByte()
        out[35] = c.outPathLen.toByte()
        c.name.copyOf(32).copyInto(out, 100)
        u32(c.lastmod).copyInto(out, 144)
        return out
    }

    /** `CMD_ADD_UPDATE_CONTACT` (`MyMesh.cpp:1328`), with `allocateContactSlot`'s overwrite-oldest (`BaseChatMesh.cpp:72`). */
    private fun addUpdate(frame: ByteArray) {
        if (frame.size < 36) return err(MeshcoreProto.ERR_UNSUPPORTED_CMD)
        val k = frame.copyOfRange(1, 33)
        val type = frame[33].toInt() and 0xFF
        val flags = frame[34].toInt() and 0xFF
        val opl = frame[35].toInt() and 0xFF
        val n = if (frame.size >= 132) frame.copyOfRange(100, 132) else ByteArray(32)
        val existing = contact(k)
        if (existing != null) {
            existing.type = type
            existing.flags = flags
            existing.outPathLen = opl
            existing.name = MeshcoreProto.cBytes(n, 0, 32)
            existing.lastmod = clockSecs
            return ok()
        }
        if (contacts.size >= maxContacts) {
            if (autoadd and MeshcoreProto.AUTO_ADD_OVERWRITE_OLDEST == 0) return err(MeshcoreProto.ERR_TABLE_FULL)
            val victim =
                contacts.filter { it.flags and MeshcoreProto.CONTACT_FLAG_FAVOURITE == 0 }.minByOrNull { it.lastmod }
                    ?: return err(MeshcoreProto.ERR_TABLE_FULL)
            contacts.remove(victim)
            writeAll(byteArrayOf(MeshcoreProto.PUSH_CONTACT_DELETED.toByte()) + victim.key)
        }
        contacts += FakeContact(k, type, flags, opl, MeshcoreProto.cBytes(n, 0, 32), clockSecs)
        ok()
    }

    /** Another board's advert reaches this one (`onAdvertRecv`): added or not by the auto-add rules, always pushed. */
    fun hearAdvert(other: FakeMeshcoreBoard) {
        val autoAdds = manualAdd and 1 == 0 || autoadd and MeshcoreProto.AUTO_ADD_CHAT != 0
        if (contact(other.key) == null && autoAdds) {
            if (contacts.size >= maxContacts && autoadd and MeshcoreProto.AUTO_ADD_OVERWRITE_OLDEST != 0) {
                contacts.filter { it.flags and 1 == 0 }.minByOrNull { it.lastmod }?.let { victim ->
                    contacts.remove(victim)
                    writeAll(byteArrayOf(MeshcoreProto.PUSH_CONTACT_DELETED.toByte()) + victim.key)
                }
            }
            if (contacts.size < maxContacts) addContact(other, MeshcoreProto.PATH_UNKNOWN, flags = 0)
        }
        val c = FakeContact(other.key, 1, 0, MeshcoreProto.PATH_UNKNOWN, other.advertName, clockSecs)
        writeAll(contactFrame(MeshcoreProto.PUSH_NEW_ADVERT, c))
    }

    /** One packet into the pool for [airMs]; false when the pool is full (`obtainNewPacket` null). */
    private fun takePacket(): Boolean {
        if (outbound >= MeshcoreHeadroom.POOL) return false
        outbound++
        air.scope.launch {
            delay(air.airMs)
            outbound--
            sent++
            txAirSecs++
        }
        return true
    }

    /** `CMD_SEND_TXT_MSG` (`MyMesh.cpp:1132`, `composeMsgPacket`, `sendMessage`). */
    private fun sendText(frame: ByteArray) {
        if (frame.size < 14) return err(MeshcoreProto.ERR_UNSUPPORTED_CMD)
        val attempt = frame[2].toInt() and 0xFF
        val ts = MeshcoreProto.u32(frame, 3)
        val prefix = frame.copyOfRange(7, 13)
        val recipient = contacts.firstOrNull { it.key.copyOf(6).contentEquals(prefix) } ?: return err(MeshcoreProto.ERR_NOT_FOUND)
        // strlen: a zero byte cuts the text, and the cut text is what is sent and acked (Q11).
        val raw = frame.copyOfRange(13, frame.size)
        val text = raw.indexOf(0).let { if (it < 0) raw else raw.copyOf(it) }
        if (text.size > MeshcoreProto.MAX_TEXT_LEN || !takePacket()) return err(MeshcoreProto.ERR_TABLE_FULL)
        val code = MeshcoreProto.expectedAck(ts, attempt, text, key)
        ackTable[nextAck] = code
        nextAck = (nextAck + 1) % ackTable.size
        val flood = recipient.outPathLen == MeshcoreProto.PATH_UNKNOWN
        val est = if (flood) 2_724L else 1_890L
        writeAll(byteArrayOf(MeshcoreProto.RESP_SENT.toByte(), if (flood) 1 else 0) + u32(code.toLong()) + u32(est))
        if (air.loseDm(attempt)) {
            air.airings += FakeMeshcoreAir.Airing(name, "msg", flood, 0)
            return
        }
        air.transmit(name, "msg", flood) { rx, hops ->
            if (rx.key.contentEquals(recipient.key)) rx.receiveText(this, ts, attempt, text, flood, hops)
        }
    }

    /**
     * A contact text reaches this board (`onPeerDataRecv`): dropped unless the sender is a contact (`Mesh.cpp:147-191`) or
     * a byte-identical copy was already heard; else queued for the phone and acked — direct when this board has a route
     * to the sender, flooded otherwise; a flooded DM draws a PATH return that rewrites the sender's route (Q13).
     */
    fun receiveText(
        from: FakeMeshcoreBoard,
        ts: Long,
        attempt: Int,
        text: ByteArray,
        flood: Boolean,
        hops: Int,
    ) {
        rxAirSecs++
        val sender = contact(from.key) ?: return
        val hash = "msg:${from.name}:$ts:$attempt:${text.toList()}"
        if (!heard.add(hash)) return
        val head =
            if (appTargetVersion >= 3) {
                byteArrayOf(MeshcoreProto.RESP_CONTACT_MSG_RECV_V3.toByte(), 32, 0, 0)
            } else {
                byteArrayOf(MeshcoreProto.RESP_CONTACT_MSG_RECV.toByte())
            }
        val frame = head + from.key.copyOf(6) + byteArrayOf((if (flood) hops else 0xFF).toByte(), 0) + u32(ts) + text
        queue(frame)
        if (air.loseAck(attempt)) return
        val code = MeshcoreProto.expectedAck(ts, attempt, text, from.key)
        if (flood) {
            // The PATH return carries the ack, and both ends learn the route (Q13): 0x81 on both boards.
            sender.outPathLen = hops
            writeAll(byteArrayOf(MeshcoreProto.PUSH_PATH_UPDATED.toByte()) + from.key)
            acksAired += true
            air.transmit(name, "path", flood = true) { rx, _ ->
                if (rx === from) rx.receiveAck(code, this, pathHops = hops)
            }
        } else {
            val ackFloods = sender.outPathLen == MeshcoreProto.PATH_UNKNOWN
            acksAired += ackFloods
            air.transmit(name, "ack", flood = ackFloods) { rx, _ -> if (rx === from) rx.receiveAck(code, this, pathHops = null) }
        }
    }

    /** An ack for one of this board's sends (`processAck`): pushed once per table entry, after the trip. */
    fun receiveAck(
        code: Int,
        from: FakeMeshcoreBoard,
        pathHops: Int?,
    ) {
        // One ack per code: a copy re-aired by a repeater is the same packet, dropped as heard.
        if (!heard.add("ack:$code:${pathHops != null}")) return
        air.scope.launch {
            delay(air.confirmDelayMs)
            if (pathHops != null) {
                contact(from.key)?.outPathLen = pathHops
                writeAll(byteArrayOf(MeshcoreProto.PUSH_PATH_UPDATED.toByte()) + from.key)
                // The sender answers a PATH return with one direct PATH of its own (Q13's fifth airing).
                air.transmit(name, "path", flood = false) { _, _ -> }
            }
            val i = ackTable.indexOfFirst { it == code && it != 0 }
            if (i < 0) return@launch
            ackTable[i] = 0
            writeAll(byteArrayOf(MeshcoreProto.PUSH_SEND_CONFIRMED.toByte()) + u32(code.toLong()) + u32(air.confirmDelayMs))
        }
    }

    /** `CMD_SEND_CHANNEL_DATA` (`MyMesh.cpp:1198`). */
    private fun sendChannelData(frame: ByteArray) {
        if (frame.size < 4) return err(MeshcoreProto.ERR_ILLEGAL_ARG)
        val slot = frame[1].toInt() and 0xFF
        val pathLen = frame[2].toInt() and 0xFF
        val dataType = MeshcoreProto.u16(frame, 3)
        val data = frame.copyOfRange(5, frame.size)
        if (slot >= maxChannels) return err(MeshcoreProto.ERR_NOT_FOUND)
        if (dataType == 0 || data.size > 165) return err(MeshcoreProto.ERR_ILLEGAL_ARG)
        if (!takePacket()) return err(MeshcoreProto.ERR_TABLE_FULL)
        ok()
        val secret = channels[slot].second
        val flood = pathLen == MeshcoreProto.PATH_FLOOD
        air.transmit(name, "grp_data", flood) { rx, hops -> rx.receiveGroup(secret, data, dataType, flood, hops) }
    }

    private fun sendChannelText(frame: ByteArray) {
        val slot = frame[2].toInt() and 0xFF
        if (slot >= maxChannels || !takePacket()) return err(MeshcoreProto.ERR_NOT_FOUND)
        val ts = MeshcoreProto.u32(frame, 3)
        val body = advertName + ": ".encodeToByteArray() + frame.copyOfRange(7, frame.size)
        val text = body.copyOf(minOf(body.size, MeshcoreProto.MAX_TEXT_LEN))
        ok()
        val secret = channels[slot].second
        air.transmit(name, "grp_txt", flood = true) { rx, hops -> rx.receiveGroupText(secret, ts, text, hops) }
    }

    /** Channel data on a secret this board holds (`onChannelDataRecv`); a byte-identical copy is dropped (Q1, Q10). */
    fun receiveGroup(
        secret: ByteArray,
        data: ByteArray,
        dataType: Int,
        flood: Boolean,
        hops: Int,
    ) {
        rxAirSecs++
        val slot = channels.indexOfFirst { it.first.isNotEmpty() && it.second.contentEquals(secret) }
        if (slot < 0 || !heard.add("grp:${secret.toList()}:$dataType:${data.toList()}")) return
        queue(
            byteArrayOf(
                27,
                32,
                0,
                0,
                slot.toByte(),
                (if (flood) hops else 0xFF).toByte(),
                dataType.toByte(),
                (dataType shr 8).toByte(),
                data.size.toByte(),
            ) +
                data,
        )
    }

    fun receiveGroupText(
        secret: ByteArray,
        ts: Long,
        text: ByteArray,
        hops: Int,
    ) {
        val slot = channels.indexOfFirst { it.second.contentEquals(secret) }
        if (slot < 0 || !heard.add("txt:${secret.toList()}:$ts:${text.toList()}")) return
        queue(byteArrayOf(17, 32, 0, 0, slot.toByte(), hops.toByte(), 0) + u32(ts) + text)
    }

    /** `addToOfflineQueue`: full, the oldest channel message goes (`MyMesh.cpp:224`); a tickle to whoever is connected. */
    fun queue(frame: ByteArray) {
        if (offline.size >= offlineCap) {
            val i = offline.indexOfFirst { it[0].toInt() in listOf(8, 17, 27) }
            if (i < 0) return
            offline.removeAt(i)
        }
        offline.addLast(frame)
        writeAll(byteArrayOf(MeshcoreProto.PUSH_MSG_WAITING.toByte()))
    }
}

/**
 * Our NUS channel to a fake board: a write is handled by the board at once, so its answer can be in [frames] before
 * the write returns (binding rule "GATT" 4). [dropFrame] loses a notify to us alone (a reply lost to the board's
 * four-frame BLE queue, Q3); [writeResult] fails a write; [swallow] has the board never see a command.
 */
internal class FakeNusChannel(
    val board: FakeMeshcoreBoard,
) : NusChannel,
    FakeIface {
    private val events = Channel<NusEvent>(Channel.UNLIMITED)
    override val frames: ReceiveChannel<NusEvent> = events
    val writes = mutableListOf<ByteArray>()
    var writeResult: (ByteArray) -> GattResult<Unit> = { GattResult.Ok(Unit) }
    var swallow: (ByteArray) -> Boolean = { false }
    var dropFrame: (ByteArray) -> Boolean = { false }
    var closed = false
        private set

    override val connected: Boolean get() = !closed

    init {
        board.ifaces += this
    }

    /** How long each notify takes to reach us: 0 delivers at once, as the board writes it. */
    var latencyMs = 0L

    /** A further delay for one notify: a slow answer of one kind ([latencyMs] slows every one). */
    var delayFrame: (ByteArray) -> Long = { 0L }

    override fun deliver(frame: ByteArray) {
        if (dropFrame(frame)) return
        val wait = latencyMs + delayFrame(frame)
        if (wait == 0L) {
            events.trySend(NusEvent.Frame(frame))
        } else {
            board.scope.launch {
                delay(wait)
                events.trySend(NusEvent.Frame(frame))
            }
        }
    }

    /** Pushes a raw frame as the board would, to us alone (a foreign answer, a garbled notify). */
    fun inject(frame: ByteArray) {
        events.trySend(NusEvent.Frame(frame))
    }

    override suspend fun write(
        frame: ByteArray,
        timeoutMs: Long,
    ): GattResult<Unit> {
        if (closed) return GattResult.Closed
        val result = writeResult(frame)
        if (result !is GattResult.Ok) return result
        writes += frame.copyOf()
        if (!swallow(frame)) board.handle(frame)
        return result
    }

    fun disconnect(status: Int = 8) {
        events.trySend(NusEvent.Disconnected(status))
    }

    override fun close() {
        closed = true
        board.ifaces -= this
    }

    /** The command codes written, in order. */
    val codes: List<Int> get() = writes.map { it[0].toInt() and 0xFF }
}

/** The user's own MeshCore app on the same board: it issues commands and receives every answer too. */
internal class FakeForeignClient(
    private val board: FakeMeshcoreBoard,
) : FakeIface {
    val received = mutableListOf<ByteArray>()
    override val connected: Boolean = true

    init {
        board.ifaces += this
    }

    override fun deliver(frame: ByteArray) {
        received += frame
    }

    fun send(frame: ByteArray) = board.handle(frame)
}

/** Dials a fresh [FakeNusChannel] on [board] each time, at MTU 176. */
internal class FakeMeshcoreDialer(
    var board: FakeMeshcoreBoard,
    var bond: BondState = BondState.BONDED,
) : MeshcoreDialer {
    val adapter = MutableStateFlow(true)
    override val adapterOn: StateFlow<Boolean> = adapter
    val channels = mutableListOf<FakeNusChannel>()
    var dialResult: (() -> NusDialResult)? = null
    val modes = mutableListOf<DialMode>()
    var onOpen: (FakeNusChannel) -> Unit = {}

    val channel: FakeNusChannel get() = channels.last()

    override fun bondState(address: String): BondState = bond

    override suspend fun dial(
        address: String,
        mode: DialMode,
        timeoutMs: Long,
    ): NusDialResult {
        modes += mode
        dialResult?.let { return it() }
        val ch = FakeNusChannel(board).also(onOpen)
        channels += ch
        return NusDialResult.Opened(ch, MeshcoreProto.MAX_FRAME)
    }
}

/** A random key, for a test that needs one nobody else holds. */
internal fun randomKey(rng: Random = Random(1)): ByteArray =
    ByteArray(MeshcoreProto.KEY_BYTES) { rng.nextInt().toByte() }.also {
        it[0] =
            0x42
    }
