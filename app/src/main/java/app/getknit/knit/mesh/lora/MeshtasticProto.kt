package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.crypto.XeddsaVerify
import app.getknit.knit.mesh.crypto.b64

/**
 * A hand-written codec for the sliver of the Meshtastic protobuf schema Knit's LoRa bridge needs — the
 * `ToRadio` frames we write to the board and the `FromRadio` frames it streams back. Deliberately not a
 * generated protobuf: the whole surface is a dozen fields, and vendoring a protobuf runtime or a codegen
 * Gradle plugin would fight the bleeding-edge toolchain (`.agents/context/toolchain.md`) for no benefit.
 *
 * Pure (no Android), so it is a plain-JVM unit-test target ([app.getknit.knit.mesh.lora.MeshtasticProtoTest]).
 * Field numbers are pinned against `meshtastic/protobufs` and covered by golden byte vectors in that test;
 * every decode is total — malformed input yields `null`, never a throw (via [ProtoException]).
 */
@Suppress("TooManyFunctions", "LargeClass") // a flat codec: one helper per Meshtastic message we speak, every field number beside it
internal object MeshtasticProto {
    /**
     * The protobuf's own `Constants.DATA_PAYLOAD_LEN`. Kept for reference only: it assumes a one-byte portnum,
     * and Knit's `PRIVATE_APP` (256) takes two, so the cap that actually clears the radio is [MAX_PAYLOAD].
     */
    const val DATA_PAYLOAD_LEN = 233

    /**
     * The largest encoded `Data` the firmware's TX path allows: `MAX_LORA_PAYLOAD_LEN` (255) less
     * `MESHTASTIC_HEADER_LENGTH` (16), both from `RadioInterface.h`. This is the budget the *firmware*
     * measures against, and it counts [DATA_BITFIELD_BYTES] that Knit never writes — see [LORA_DATA_MAX].
     */
    const val DATA_ENCODED_MAX = 239

    /**
     * `Data.bitfield` (field 9), which the firmware fills in on every packet it originates on our behalf
     * (`Router::perhapsEncode`) — an optional varint whose value is 0 for us, so tag + value = 2 bytes. Knit
     * never encodes it, but it lands on the air inside our budget, which is why it is subtracted here rather
     * than left as slack.
     */
    private const val DATA_BITFIELD_BYTES = 2

    /**
     * The largest encoded `Data` **Knit** may hand the board. [DATA_ENCODED_MAX] less the bitfield the
     * firmware adds after us. This is exactly what the lab measured on a Heltec V4 on 2.7.26 (2026-08-29) —
     * a 231-byte `PRIVATE_APP` payload (237-byte `Data`) is queued, 232 and 233 come back
     * `Routing.error_reason = TOO_LARGE` — so the two byte gap that used to be unexplained slack is the
     * bitfield. Every packet that ever NAKed `TOO_LARGE` on the lab boards was one chunked at
     * [DATA_PAYLOAD_LEN].
     */
    const val LORA_DATA_MAX = DATA_ENCODED_MAX - DATA_BITFIELD_BYTES

    /** What `Data` adds around a maximum payload: the two-byte private portnum and the payload's tag + length. */
    val DATA_FRAMING: Int = dataFraming(PORT_PRIVATE_APP)

    /**
     * What `Data` adds around a maximum payload on [portnum], measured by encoding one: the portnum varint
     * (one byte up to 127, two for Knit's `PRIVATE_APP`) and the payload's tag + two-byte length. The
     * difference is why the signature cliff sits one byte higher for ordinary chat than for a Knit frame.
     */
    fun dataFraming(portnum: Int): Int =
        ProtoWriter()
            .varint(DATA_PORTNUM, portnum)
            .bytes(DATA_PAYLOAD, ByteArray(LORA_DATA_MAX))
            .toByteArray()
            .size -
            LORA_DATA_MAX

    /** `Data.payload`'s hard cap on the air for a Knit packet: [LORA_DATA_MAX] less [DATA_FRAMING] — 231. */
    val MAX_PAYLOAD: Int = LORA_DATA_MAX - DATA_FRAMING

    /**
     * What `Data.xeddsa_signature` (field 10) costs on the air: the firmware's `XEDDSA_SIGNATURE_FIELD_BYTES`
     * — 64 signature bytes plus their one-byte tag and one-byte length.
     *
     * Firmware 2.8 signs every broadcast it originates, ours included (`Router::perhapsEncode`: not
     * PKI-encrypted, `isBroadcast(to)`, and it fits). Knit never asks for this and cannot decline it — the
     * board owns signing for packets it sends — so for the plane it is simply 66 bytes of airtime that
     * [app.getknit.knit.mesh.lora.LoraAirtime] must charge for. See [MAX_SIGNED_PAYLOAD].
     */
    const val XEDDSA_SIGNATURE_FIELD = 66

    /**
     * What a PKI-encrypted unicast adds on the air past its `Data`: the firmware's `MESHTASTIC_PKC_OVERHEAD` (an 8-byte tag
     * and a 4-byte extra nonce, `RadioInterface.h:23`, added in `Router::perhapsEncode`, `Router.cpp:1347-1350` at
     * v2.8.1). Firmware 2.8 PKI-encrypts **every** unicast its phone originates on a port it does not exclude, whatever
     * the channel (`wouldEncryptWithPKC`, `Router.cpp:1189-1212`, never reads the index): the DM auto-reply (ADR
     * 2026-10.ug4a) and every compact DM, the Knit secondary included. It is also what the firmware's size check adds:
     * an encoded `Data` past 227 B draws `TOO_LARGE` from our own board, so a PKI unicast carries at most
     * [MAX_PAYLOAD] less this ([LoraDirectPolicy.payloadCap]). Priced at admission and at booking (ADR 2026-10.x9fa's
     * PKI amendment).
     */
    const val PKC_OVERHEAD_BYTES = 12

    /**
     * What a channel with `ChannelSettings.use_aead` adds on the air to every packet encrypted under its key: the
     * firmware's `MESHTASTIC_AEAD_OVERHEAD` (`RadioInterface.h:23`, AES-CCM's tag and nonce), added in
     * `Router::perhapsEncode`, which refuses `numbytes + 16 + 12 > 255` as `TOO_LARGE` (`Router.cpp:1377-1379` at
     * v2.8.1). A PKI unicast takes the other branch and pays [PKC_OVERHEAD_BYTES] instead, never both.
     */
    const val AEAD_OVERHEAD_BYTES = 12

    /** A `Data.xeddsa_signature` is exactly this long (`XEDDSA_SIGNATURE_SIZE`); the firmware emits 0 or 64 bytes, never else. */
    const val XEDDSA_SIGNATURE_BYTES = 64

    /** A node's `User.public_key` — its Curve25519 key, 32 raw bytes; anything else is not a key. */
    const val NODE_PUBLIC_KEY_BYTES = 32

    /**
     * The largest `Data.payload` the firmware will still fit a signature onto, mirroring its own
     * `signedDataFits` gate (`Router.cpp`): the encoded `Data` **with** the signature must clear
     * [DATA_ENCODED_MAX]. Above this the packet simply goes unsigned rather than failing — which is why a
     * full-size Knit fragment costs nothing extra and a small one costs [XEDDSA_SIGNATURE_FIELD].
     *
     * The cliff lands awkwardly for the frames ADR 060 worked hardest to shrink: a transcoded room tick
     * (157 B) is under it and pays the surcharge, while a signed DM ✓✓ (221 B) is over it and does not.
     * This is the Knit-frame figure (a `PRIVATE_APP` portnum); [maxSignedPayload] gives the cliff for any port.
     */
    val MAX_SIGNED_PAYLOAD: Int = maxSignedPayload(PORT_PRIVATE_APP)

    /**
     * The signature cliff for a packet on [portnum] — [MAX_SIGNED_PAYLOAD]'s formula with that port's own
     * [dataFraming]: 165 for a Knit frame, **166** for ordinary `TEXT_MESSAGE_APP` chat, whose one-byte
     * portnum leaves one more byte for the words. The Meshtastic room's composer caps a post at this so every
     * post a 2.8 board sends for us leaves signed (ADR 2026-09.ggq4).
     */
    fun maxSignedPayload(portnum: Int): Int = DATA_ENCODED_MAX - dataFraming(portnum) - DATA_BITFIELD_BYTES - XEDDSA_SIGNATURE_FIELD

    /**
     * The bytes a `ToRadio { packet }` wraps around a maximum `Data.payload`, measured by encoding one
     * rather than summed by hand (which is how the previous constant grew 6 B of "slack" nobody could
     * account for): the ToRadio tag + length, `to` and `id` as fixed32, the worst-case channel index, the
     * `decoded` submessage framing, the private-range portnum, and the payload's own tag + length. This is
     * what a BLE write of a full packet costs above the payload; the ATT header is the transport's.
     */
    val PACKET_OVERHEAD: Int =
        encodePacket(OutboundPacket(channelIndex = MAX_CHANNEL_INDEX, id = UInt.MAX_VALUE, payload = ByteArray(MAX_PAYLOAD))).size -
            MAX_PAYLOAD

    /** The highest channel index the firmware allows (`MAX_NUM_CHANNELS - 1`); the worst case for the varint. */
    private const val MAX_CHANNEL_INDEX = 7

    /** `NodeNum` broadcast address (`0xFFFFFFFF`). */
    const val BROADCAST: UInt = 0xFFFFFFFFu

    /** `PortNum.PRIVATE_APP` — the 256..511 private range Knit's frames ride so they never collide with app data. */
    const val PORT_PRIVATE_APP = 256

    /**
     * `PortNum.TEXT_MESSAGE_APP` — ordinary Meshtastic chat. Knit **reads** it on the board's primary only,
     * and only broadcast (the Meshtastic room); a unicast one addressed to the board stays unread, which is
     * what `User.is_unmessagable` says out loud (ADR 2026-09.emd7).
     */
    const val PORT_TEXT_MESSAGE = 1

    /** `PortNum.NODEINFO_APP` — a node broadcasting its own `User`; the name directory behind a bridged post. */
    const val PORT_NODEINFO = 4

    /** `PortNum.ROUTING_APP` — how the mesh delivers a NAK (an `error_reason`) back to the sender. */
    const val PORT_ROUTING = 5

    /** `PortNum.ADMIN_APP` — carries an `AdminMessage`; Knit uses it only to self-configure the local board. */
    const val PORT_ADMIN = 6

    /** `PortNum.TELEMETRY_APP` — the board's own `DeviceMetrics` (its battery) arrive here, addressed from itself. */
    const val PORT_TELEMETRY = 67

    /**
     * `Channel.Role.SECONDARY` — the only role Knit ever writes. The firmware derives its RF slot from
     * `hash(primary channel name)`, so leaving the primary alone is what keeps a Knit board on the public
     * frequency, where stock nodes repeat its packets for free (ADR 045).
     */
    const val ROLE_SECONDARY = 2

    /** `ModuleSettings.position_precision = 0` — "share no position on this channel". */
    const val POSITION_PRECISION_NONE = 0

    // The three housekeeping intervals the board setup stretches (ADR 045). Public because [BoardQuiet]
    // owns the *values*; the field numbers stay here with the rest of the pinned wire, beside their vectors.

    /** `Config.DeviceConfig.node_info_broadcast_secs`. */
    const val DEVICE_NODE_INFO_BROADCAST_SECS = 7

    /** `Config.DeviceConfig.rebroadcast_mode`. */
    const val DEVICE_REBROADCAST_MODE = 6

    /**
     * `Config.LoRaConfig.channel_num` — the 1-based RF slot override. 0 (the firmware default, and what a
     * restore puts back) means "derive the slot from the primary channel's name", which is the shared public
     * frequency every stock board lands on; a non-zero value pins the radio to that slot instead. Public
     * because the dedicated-slot setup splices it (ADR 067); the other `LORA_*` numbers stay private to the
     * decoder.
     */
    const val LORA_CHANNEL_NUM = 11

    /** `Config.PositionConfig.position_broadcast_secs`. */
    const val POSITION_BROADCAST_SECS = 1

    /** `Config.PositionConfig.position_broadcast_smart_enabled`. */
    const val POSITION_BROADCAST_SMART = 2

    /** `ModuleConfig.TelemetryConfig.device_update_interval` — the *mesh* broadcast, not the phone-only feed. */
    const val TELEMETRY_DEVICE_UPDATE_INTERVAL = 1

    // The `User` fields the setup writes (ADR 049, ADR 2026-09.emd7). Public for the same reason as the
    // intervals above: [BoardName] owns the *identity*, the field numbers stay here with the rest of the
    // pinned wire.

    /** `User.id` — `!` and the node number as eight lowercase hex digits; the firmware re-derives it from the number. */
    const val USER_ID = 1

    /** `User.long_name` — up to 39 characters; what a node list and the Meshtastic app show. */
    const val USER_LONG_NAME = 2

    /** `User.short_name` — up to **4** characters (`char[5]`); what the small on-board screens have room for. */
    const val USER_SHORT_NAME = 3

    /**
     * `User.is_unmessagable` — "this node is not read by anybody", what the Meshtastic app offers as
     * *Unmonitored or infrastructure*. A Knit board is exactly that: its inbound path keeps only
     * [PORT_PRIVATE_APP], so a stranger's `TEXT_MESSAGE_APP` direct message is ACKed by the firmware and
     * then dropped, unseen. Clients that honour the flag grey the node out instead of offering it as a
     * message target.
     *
     * Unlike `is_licensed` this one is declared `optional`, so it has presence on the wire: absent is
     * "this board never said", which every client and [BoardOwnerRaw] read as messagable.
     */
    const val USER_IS_UNMESSAGABLE = 9

    /**
     * `User.is_licensed` — ham mode: a licensed board never PKI-encrypts (`wouldEncryptWithPKC` asks `!owner.is_licensed`)
     * and signs its unicasts instead, so a compact DM from one is priced signed and never draws a `39`. Read, never
     * written: no `User` Knit builds for `add_contact` carries it.
     */
    const val USER_IS_LICENSED = 6

    /**
     * `User.public_key` — the node's Curve25519 public key, 32 raw bytes, the key firmware 2.8 signs its
     * broadcasts under (converted to Ed25519 per XEdDSA; `mesh/crypto/XeddsaVerify`). On 2.8 the node number
     * is the CRC32 of it. Read off every `NodeInfo` the board streams, our own included: that one is what the
     * profile advertises beside the node number so a contact's phone can verify our posts.
     */
    const val USER_PUBLIC_KEY = 8

    // --- ToRadio (phone → board) ---

    /** `ToRadio { want_config_id = nonce }` — opens the config handshake; the board replies until `config_complete_id`. */
    fun encodeWantConfig(nonce: UInt): ByteArray = ProtoWriter().uint32(TO_WANT_CONFIG_ID, nonce).toByteArray()

    /** `ToRadio { heartbeat { nonce } }` — keeps the phone API alive; an empty message whose presence is the signal. */
    fun encodeHeartbeat(nonce: UInt = 0u): ByteArray =
        ProtoWriter().message(TO_HEARTBEAT, emitEmpty = true) { uint32(HEARTBEAT_NONCE, nonce) }.toByteArray()

    /** `ToRadio { disconnect = true }` — a clean goodbye so the board frees the phone slot at once. */
    fun encodeDisconnect(): ByteArray = ProtoWriter().bool(TO_DISCONNECT, true).toByteArray()

    /**
     * `ToRadio { packet = MeshPacket { … Data { portnum, payload } } }`. [OutboundPacket.hopLimit] omitted
     * arrives as zero on 2.8, not the node's default (`LoraMeshTransport.HOP_LIMIT`). `want_ack` (field 10) is set
     * only on a direct unicast ([OutboundPacket.wantAck], ADR 2026-10.x9fa): the firmware then retries it until the
     * destination's board answers and reports the outcome as a `Routing` packet. A broadcast never asks: its ack is
     * meaningless and only costs airtime. Written last, after `hop_limit`, so every packet without it is byte-for-byte
     * what it was.
     */
    fun encodePacket(packet: OutboundPacket): ByteArray =
        ProtoWriter()
            .message(TO_PACKET) {
                fixed32(MP_TO, packet.to)
                varint(MP_CHANNEL, packet.channelIndex)
                message(MP_DECODED) {
                    varint(DATA_PORTNUM, packet.portnum)
                    bytes(DATA_PAYLOAD, packet.payload)
                    if (packet.wantResponse) bool(DATA_WANT_RESPONSE, true)
                }
                fixed32(MP_ID, packet.id)
                if (packet.hopLimit != null) varint(MP_HOP_LIMIT, packet.hopLimit)
                if (packet.wantAck) bool(MP_WANT_ACK, true)
            }.toByteArray()

    /**
     * What `want_ack` adds to a `ToRadio`: its tag and a one-byte bool. Not in [PACKET_OVERHEAD], which every
     * broadcast's payload cap is measured against; a direct unicast takes it off its own cap instead
     * (`LoraMeshTransport.directPayloadCap`), so its BLE write is never longer than a full broadcast's.
     */
    val WANT_ACK_FIELD_BYTES: Int =
        encodePacket(OutboundPacket(channelIndex = 0, id = 1u, payload = ByteArray(0), wantAck = true)).size -
            encodePacket(OutboundPacket(channelIndex = 0, id = 1u, payload = ByteArray(0))).size

    // --- Admin (phone → local board): an AdminMessage rides a Data{portnum = ADMIN} packet addressed to self ---

    /**
     * `AdminMessage { get_channel_request = index + 1 }` — a read whose response carries the channel AND a
     * fresh `session_passkey` the board then demands on the matching set. (The `+ 1` is the wire convention:
     * the field doubles as a presence flag, so index 0 is requested as 1.)
     */
    fun encodeAdminGetChannel(index: Int): ByteArray = ProtoWriter().varint(ADMIN_GET_CHANNEL_REQUEST, index + 1).toByteArray()

    /**
     * `AdminMessage { get_owner_request = true }` — the **read** half of the rename. Its response carries the
     * board's whole `User`, which is the base [encodeAdminSetOwner] splices into, and a fresh passkey.
     */
    fun encodeAdminGetOwner(): ByteArray = ProtoWriter().bool(ADMIN_GET_OWNER_REQUEST, true).toByteArray()

    /**
     * `AdminMessage { set_owner = User { … } }`. [raw] is the board's own `User` as `get_owner_request`
     * returned it, with only [USER_LONG_NAME] / [USER_SHORT_NAME] ([spliceStringFields]) and
     * [USER_IS_UNMESSAGABLE] ([spliceVarintFields]) spliced.
     *
     * The firmware merges rather than assigns here — `handleSetOwner` copies each *non-empty* string — but
     * **both** bools it merges are cleared by omission, so a `User` built from scratch would silently reset
     * them. `is_licensed` has no presence at all, so an absent one reads as `false` and takes
     * `config.lora.override_duty_cycle` with it; `is_unmessagable` does have presence, yet the firmware's
     * merge assigns `owner.is_unmessagable = o.is_unmessagable` whenever the two presence bits differ, so an
     * absent one still clears a board that had it set. Splicing the board's own bytes keeps both, the public
     * key, and everything else this codec does not model.
     */
    fun encodeAdminSetOwner(
        raw: ByteArray,
        passkey: ByteArray?,
    ): ByteArray = admin(passkey) { bytes(ADMIN_SET_OWNER, raw, emitEmpty = true) }

    /**
     * `AdminMessage { begin_edit_settings = true }` — opens a transaction so the board defers its implicit
     * save-and-reboot until [encodeAdminCommitEdit], collapsing a multi-write channel edit into one reboot.
     */
    fun encodeAdminBeginEdit(passkey: ByteArray?): ByteArray = admin(passkey) { bool(ADMIN_BEGIN_EDIT, true) }

    /**
     * `AdminMessage { set_channel = Channel { index, settings, role } }` with [settings] — a `ChannelSettings` the board
     * itself reported ([ChannelInfo.settingsRaw]), spliced — written verbatim (#243). `set_channel` replaces the whole
     * `ChannelSettings`, so this is how a slot is rewritten without losing a field Knit does not model:
     * `module_settings` (position precision, mute), uplink, downlink, `id`.
     */
    fun encodeAdminSetChannelSettings(
        index: Int,
        role: Int,
        settings: ByteArray,
        passkey: ByteArray?,
    ): ByteArray =
        admin(passkey) {
            message(ADMIN_SET_CHANNEL) {
                varint(CHANNEL_INDEX, index)
                bytes(CHANNEL_SETTINGS, settings, emitEmpty = true)
                varint(CHANNEL_ROLE, role)
            }
        }

    /** `AdminMessage { set_channel = Channel { index, settings { psk, name }, role } }`. */
    fun encodeAdminSetChannel(
        spec: ChannelWrite,
        passkey: ByteArray?,
    ): ByteArray =
        admin(passkey) {
            message(ADMIN_SET_CHANNEL) {
                varint(CHANNEL_INDEX, spec.index)
                message(CHANNEL_SETTINGS) {
                    bytes(CHANNEL_SETTINGS_PSK, spec.psk)
                    string(CHANNEL_SETTINGS_NAME, spec.name)
                    // Emitted even when empty: an absent `module_settings` reads as "unset" and the firmware
                    // falls back to full position precision, which is the opposite of precision 0.
                    if (spec.positionPrecision != null) {
                        message(CHANNEL_SETTINGS_MODULE_SETTINGS, emitEmpty = true) {
                            varint(MODULE_SETTINGS_POSITION_PRECISION, spec.positionPrecision)
                        }
                    }
                }
                varint(CHANNEL_ROLE, spec.role)
            }
        }

    /**
     * `AdminMessage { get_config_request = <ConfigType> }` (or the module-config variant) — the **read** half
     * of every config write this app does. The value rides [ProtoWriter.oneofVarint] because `ConfigType`'s
     * first member is 0 and a `oneof` member must appear on the wire even at its default.
     */
    fun encodeAdminGetConfig(config: BoardConfig): ByteArray =
        ProtoWriter()
            .oneofVarint(
                if (config.module) ADMIN_GET_MODULE_CONFIG_REQUEST else ADMIN_GET_CONFIG_REQUEST,
                config.configType,
            ).toByteArray()

    /**
     * `AdminMessage { set_config = Config { <member> = <raw> } }` (or the module-config variant). [raw] is
     * the board's own sub-config as it came back from [encodeAdminGetConfig], with only the intended fields
     * spliced ([spliceVarintFields]) — the firmware assigns the whole sub-message, so anything missing here
     * is anything reset on the board.
     */
    fun encodeAdminSetConfig(
        config: BoardConfig,
        raw: ByteArray,
        passkey: ByteArray?,
    ): ByteArray =
        admin(passkey) {
            message(if (config.module) ADMIN_SET_MODULE_CONFIG else ADMIN_SET_CONFIG) {
                bytes(config.member, raw, emitEmpty = true)
            }
        }

    /** `AdminMessage { commit_edit_settings = true }` — applies the open transaction (and may reboot the board). */
    fun encodeAdminCommitEdit(passkey: ByteArray?): ByteArray = admin(passkey) { bool(ADMIN_COMMIT_EDIT, true) }

    /**
     * `AdminMessage { add_contact = SharedContact { node_num, user } }` (field 66; `SharedContact` is `node_num 1, user 2,
     * should_ignore 3, manually_verified 4`, `admin.proto`) — teaches the local board a node and its key
     * (`NodeDB::addFromContact`, `NodeDB.cpp:3717-3790` at v2.8.1). [user] is the whole `User` the board will store for
     * [nodeNum] (`CopyUserToNodeInfoLite` copies every field, names and key alike), built by [LoraContactPolicy].
     * `should_ignore` and `manually_verified` are never set: the first blocks the node, the second is a user-facing
     * trust mark in every Meshtastic app and would lock the key against the contact's own reflash. A local admin message
     * (`from == 0`) carries no session passkey (`AdminModule.cpp:161-182`), so none is appended (ADR 2026-10.aajj).
     */
    fun encodeAdminAddContact(
        nodeNum: UInt,
        user: ByteArray,
    ): ByteArray =
        admin(passkey = null) {
            message(ADMIN_ADD_CONTACT) {
                uint32(SHARED_CONTACT_NODE_NUM, nodeNum)
                bytes(SHARED_CONTACT_USER, user, emitEmpty = true)
            }
        }

    /** Wraps one AdminMessage `oneof` member ([block]) and appends the `session_passkey` the board issued, if any. */
    private inline fun admin(
        passkey: ByteArray?,
        block: ProtoWriter.() -> Unit,
    ): ByteArray =
        ProtoWriter()
            .apply {
                block()
                if (passkey != null && passkey.isNotEmpty()) bytes(ADMIN_SESSION_PASSKEY, passkey)
            }.toByteArray()

    /** Decodes an inbound `AdminMessage` down to the fields provisioning reads: the passkey, a channel, a
     * sub-config and the board's `User`. */
    fun decodeAdmin(payload: ByteArray): AdminReply? =
        runCatching {
            val reader = ProtoReader(payload)
            var passkey: ByteArray? = null
            var channel: ChannelInfo? = null
            var config: BoardConfigRaw? = null
            var owner: BoardOwnerRaw? = null
            while (reader.hasMore) {
                val tag = reader.readTag()
                when (tag ushr WireType.FIELD_SHIFT) {
                    ADMIN_GET_CHANNEL_RESPONSE -> channel = decodeChannelInfo(reader.sub())
                    ADMIN_GET_OWNER_RESPONSE -> owner = BoardOwnerRaw(reader.readBytes())
                    ADMIN_GET_CONFIG_RESPONSE -> config = decodeConfigRaw(reader.readBytes(), module = false)
                    ADMIN_GET_MODULE_CONFIG_RESPONSE -> config = decodeConfigRaw(reader.readBytes(), module = true)
                    ADMIN_SESSION_PASSKEY -> passkey = reader.readBytes()
                    else -> reader.skip(tag and WireType.MASK)
                }
            }
            AdminReply(passkey, channel, config, owner)
        }.getOrNull()

    // --- FromRadio (board → phone) ---

    /** One decoded `FromRadio` variant, or null when [bytes] is malformed. Empty input is [FromRadio.Empty]. */
    fun decodeFromRadio(bytes: ByteArray): FromRadio? =
        runCatching {
            if (bytes.isEmpty()) return FromRadio.Empty
            val reader = ProtoReader(bytes)
            var result: FromRadio = FromRadio.Empty
            while (reader.hasMore) {
                result = decodeVariant(reader, reader.readTag())
            }
            result
        }.getOrNull()

    /**
     * One `FromRadio` field by number. A known field with an unexpected wire type reads as garbage and
     * [decodeFromRadio]'s `runCatching` turns that into null — no per-field wire guard needed.
     */
    private fun decodeVariant(
        reader: ProtoReader,
        tag: Int,
    ): FromRadio =
        when (val field = tag ushr WireType.FIELD_SHIFT) {
            FR_PACKET -> FromRadio.Packet(decodeMeshPacket(reader.sub()))
            FR_MY_INFO -> decodeMyInfo(reader.sub())
            FR_NODE_INFO -> decodeNodeInfo(reader.sub())
            FR_CONFIG_COMPLETE -> FromRadio.ConfigComplete(reader.readVarint32().toUInt())
            FR_REBOOTED -> FromRadio.Rebooted.also { reader.readVarint64() }
            FR_QUEUE_STATUS -> decodeQueueStatus(reader.sub())
            FR_CONFIG -> decodeConfig(reader.sub())
            FR_CHANNEL -> decodeChannel(reader.sub())
            FR_METADATA -> decodeMetadata(reader.sub())
            else -> FromRadio.Other(field).also { reader.skip(tag and WireType.MASK) }
        }

    /**
     * The `device_metrics` inside a TELEMETRY_APP `Data.payload` (`Telemetry { time, device_metrics }`), or
     * null when the packet carries another telemetry variant (environment, power, …) or is malformed.
     */
    fun decodeTelemetry(payload: ByteArray): DeviceMetrics? =
        runCatching {
            val reader = ProtoReader(payload)
            var metrics: DeviceMetrics? = null
            while (reader.hasMore) {
                val tag = reader.readTag()
                when (tag ushr WireType.FIELD_SHIFT) {
                    TELEMETRY_DEVICE_METRICS -> metrics = decodeDeviceMetrics(reader.sub())
                    else -> reader.skip(tag and WireType.MASK)
                }
            }
            metrics
        }.getOrNull()

    /** The `error_reason` inside a ROUTING_APP `Data.payload` (`Routing { error_reason }`), or null if malformed. */
    fun decodeRouting(payload: ByteArray): RoutingError? =
        runCatching {
            val reader = ProtoReader(payload)
            var reason = RoutingError.NONE
            while (reader.hasMore) {
                val tag = reader.readTag()
                val field = tag ushr WireType.FIELD_SHIFT
                val wire = tag and WireType.MASK
                if (field == ROUTING_ERROR_REASON && wire == WireType.VARINT) {
                    reason = RoutingError.fromCode(reader.readVarint32())
                } else {
                    reader.skip(wire)
                }
            }
            reason
        }.getOrNull()

    @Suppress("CyclomaticComplexMethod", "LongMethod") // one `when` branch per MeshPacket field the plane reads; a flat wire decoder
    private fun decodeMeshPacket(reader: ProtoReader): MeshPacket {
        var from = 0u
        var to = 0u
        var channel = 0
        var id = 0u
        var decoded: MeshData? = null
        var encrypted = false
        var rxSnr: Float? = null
        var rxRssi: Int? = null
        var hopLimit = 0
        var hopStart = 0
        var viaMqtt = false
        var xeddsaSigned = false
        var rxTime = 0u
        var wantAck = false
        var nextHop = 0
        var pkiEncrypted = false
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (val field = tag ushr WireType.FIELD_SHIFT) {
                MP_FROM -> {
                    from = reader.readFixed32()
                }

                MP_TO -> {
                    to = reader.readFixed32()
                }

                MP_CHANNEL -> {
                    channel = reader.readVarint32()
                }

                MP_DECODED -> {
                    decoded = decodeData(reader.sub())
                }

                MP_ENCRYPTED -> {
                    reader.readBytes()
                    encrypted = true
                }

                MP_ID -> {
                    id = reader.readFixed32()
                }

                MP_RX_TIME -> {
                    rxTime = reader.readFixed32()
                }

                MP_RX_SNR -> {
                    rxSnr = reader.readFloat()
                }

                MP_HOP_LIMIT -> {
                    hopLimit = reader.readVarint32()
                }

                MP_WANT_ACK -> {
                    wantAck = reader.readVarint32() != 0
                }

                MP_RX_RSSI -> {
                    rxRssi = reader.readVarint32()
                }

                MP_VIA_MQTT -> {
                    viaMqtt = reader.readVarint32() != 0
                }

                MP_HOP_START -> {
                    hopStart = reader.readVarint32()
                }

                MP_NEXT_HOP -> {
                    nextHop = reader.readVarint32()
                }

                MP_XEDDSA_SIGNED -> {
                    xeddsaSigned = reader.readVarint32() != 0
                }

                MP_PKI_ENCRYPTED -> {
                    pkiEncrypted = reader.readVarint32() != 0
                }

                else -> {
                    reader.skip(tag and WireType.MASK)
                }
            }
        }
        return MeshPacket(
            from,
            to,
            channel,
            id,
            decoded,
            encrypted,
            rxSnr,
            rxRssi,
            hopLimit,
            hopStart,
            viaMqtt,
            xeddsaSigned,
            rxTime = rxTime.takeIf { it != 0u },
            wantAck = wantAck,
            nextHop = nextHop,
            pkiEncrypted = pkiEncrypted,
        )
    }

    private fun decodeData(reader: ProtoReader): MeshData {
        var portnum = 0
        var payload = ByteArray(0)
        var requestId = 0u
        var signature: ByteArray? = null
        var wantResponse = false
        var replyId = 0u
        var emoji = 0u
        var bitfield: UInt? = null
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                DATA_PORTNUM -> portnum = reader.readVarint32()

                DATA_PAYLOAD -> payload = reader.readBytes()

                DATA_WANT_RESPONSE -> wantResponse = reader.readVarint32() != 0

                DATA_REQUEST_ID -> requestId = reader.readFixed32()

                DATA_REPLY_ID -> replyId = reader.readFixed32()

                DATA_EMOJI -> emoji = reader.readFixed32()

                // `optional`: a present zero is still encoded, and 2.8.1 signs its presence (the flags byte).
                DATA_BITFIELD -> bitfield = reader.readVarint32().toUInt()

                // The firmware writes 0 or 64 bytes and drops anything else before the phone sees it;
                // a wrong length here is not a signature and must not be verified as one.
                DATA_XEDDSA_SIGNATURE -> signature = reader.readBytes().takeIf { it.size == XEDDSA_SIGNATURE_BYTES }

                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return MeshData(
            portnum,
            payload,
            requestId,
            signature,
            signedFields = XeddsaVerify.DataFields(wantResponse, requestId, replyId, emoji, bitfield),
        )
    }

    private fun decodeMyInfo(reader: ProtoReader): FromRadio.MyInfo {
        var myNodeNum = 0u
        var pioEnv: String? = null
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                MYINFO_NODE_NUM -> myNodeNum = reader.readVarint32().toUInt()
                MYINFO_PIO_ENV -> pioEnv = reader.readString()
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return FromRadio.MyInfo(myNodeNum, pioEnv)
    }

    /** `NodeInfo { num, user, device_metrics }` — the board's own entry carries its name and its battery. */
    private fun decodeNodeInfo(reader: ProtoReader): FromRadio.NodeInfo {
        var num = 0u
        var metrics: DeviceMetrics? = null
        var user: BoardOwnerRaw? = null
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                NODEINFO_NUM -> num = reader.readVarint32().toUInt()
                NODEINFO_USER -> user = BoardOwnerRaw(reader.readBytes())
                NODEINFO_DEVICE_METRICS -> metrics = decodeDeviceMetrics(reader.sub())
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return FromRadio.NodeInfo(num, metrics, user?.owner, user)
    }

    /** `DeviceMetrics { … }` — every field proto3-`optional`, so an absent one stays null. */
    private fun decodeDeviceMetrics(reader: ProtoReader): DeviceMetrics {
        var level: Int? = null
        var voltage: Float? = null
        var channelUtil: Float? = null
        var airUtilTx: Float? = null
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                DM_BATTERY_LEVEL -> level = reader.readVarint32()
                DM_VOLTAGE -> voltage = reader.readFloat()
                DM_CHANNEL_UTILIZATION -> channelUtil = reader.readFloat()
                DM_AIR_UTIL_TX -> airUtilTx = reader.readFloat()
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return DeviceMetrics(level, voltage, channelUtil, airUtilTx)
    }

    private fun decodeQueueStatus(reader: ProtoReader): FromRadio.QueueStatus {
        var res = 0
        var free = 0
        var maxlen = 0
        var meshPacketId = 0u
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                QS_RES -> res = reader.readVarint32()
                QS_FREE -> free = reader.readVarint32()
                QS_MAXLEN -> maxlen = reader.readVarint32()
                QS_MESH_PACKET_ID -> meshPacketId = reader.readVarint32().toUInt()
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return FromRadio.QueueStatus(res, free, maxlen, meshPacketId)
    }

    /**
     * A `Config` streamed during the want_config handshake. Two variants are read: `lora` (the radio settings the
     * airtime governor needs) and, of `security`, its `packet_signature_policy` alone (#243: what the firmware signs).
     * Every other variant decodes to a null-payload [FromRadio.Config] and is ignored, so an unknown or reordered
     * variant can never break the handshake.
     *
     * **The security config carries the board's `private_key` and `admin_key`** (`PhoneAPI.cpp:797` at v2.8.1), so it
     * is read field by field inside [decodeSignaturePolicy] and never captured whole: no raw copy of it exists past this
     * decoder, and `BoardConfig` must never gain a `security` member that would splice it.
     */
    private fun decodeConfig(reader: ProtoReader): FromRadio.Config {
        var lora: LoraRadioConfig? = null
        var policy: SignaturePolicy? = null
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                CONFIG_LORA -> lora = decodeLoraConfig(reader.sub())
                CONFIG_SECURITY -> policy = decodeSignaturePolicy(reader.sub())
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return FromRadio.Config(lora, policy)
    }

    /**
     * `SecurityConfig.packet_signature_policy` (9) and nothing else: every other field — the keys above all — is skipped
     * unread. proto3, so a security config without field 9 is [SignaturePolicy.COMPATIBLE], the enum's zero; a value
     * this build does not know is [SignaturePolicy.UNRECOGNISED], which reads as signing (`LoraAirtime.price`).
     */
    private fun decodeSignaturePolicy(reader: ProtoReader): SignaturePolicy {
        var policy = SignaturePolicy.COMPATIBLE
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                SECURITY_PACKET_SIGNATURE_POLICY -> policy = SignaturePolicy.fromCode(reader.readVarint32())
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return policy
    }

    /** The `Config.LoRaConfig` fields that decide time-on-air, the legal duty cycle, and which RF slot we sit on. */
    private fun decodeLoraConfig(reader: ProtoReader): LoraRadioConfig {
        var usePreset = false
        var preset = ModemPreset.LONG_FAST
        var region = LoraRegion.UNSET
        var hopLimit = 0
        var overrideDutyCycle = false
        var channelNum = 0
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                LORA_USE_PRESET -> usePreset = reader.readVarint32() != 0
                LORA_MODEM_PRESET -> preset = ModemPreset.fromCode(reader.readVarint32())
                LORA_REGION -> region = LoraRegion.fromCode(reader.readVarint32())
                LORA_HOP_LIMIT -> hopLimit = reader.readVarint32()
                LORA_OVERRIDE_DUTY_CYCLE -> overrideDutyCycle = reader.readVarint32() != 0
                LORA_CHANNEL_NUM -> channelNum = reader.readVarint32()
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return LoraRadioConfig(usePreset, preset, region, hopLimit, overrideDutyCycle, channelNum)
    }

    private fun decodeChannel(reader: ProtoReader): FromRadio.Channel = FromRadio.Channel(decodeChannelInfo(reader))

    /** Decodes a `Channel { index, settings { psk, name }, role }` — shared by the config handshake and admin reads. */
    private fun decodeChannelInfo(reader: ProtoReader): ChannelInfo {
        var index = 0
        var settings = ChannelSettings()
        var settingsRaw = ByteArray(0)
        var role = 0
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                CHANNEL_INDEX -> {
                    index = reader.readVarint32()
                }

                CHANNEL_SETTINGS -> {
                    settingsRaw = reader.readBytes()
                    settings = decodeChannelSettings(ProtoReader(settingsRaw))
                }

                CHANNEL_ROLE -> {
                    role = reader.readVarint32()
                }

                else -> {
                    reader.skip(tag and WireType.MASK)
                }
            }
        }
        return ChannelInfo(index, settings.name, role, settings.psk, settings.useAead, settingsRaw)
    }

    /**
     * Picks the one populated member out of a `Config` / `ModuleConfig` and hands back its bytes **untouched**,
     * so a read-modify-write can splice them without this codec having to model the sub-config at all. A
     * member Knit never asks about yields null rather than a wrong [BoardConfig].
     */
    private fun decodeConfigRaw(
        bytes: ByteArray,
        module: Boolean,
    ): BoardConfigRaw? {
        val reader = ProtoReader(bytes)
        while (reader.hasMore) {
            val tag = reader.readTag()
            if (tag and WireType.MASK != WireType.LEN) {
                reader.skip(tag and WireType.MASK)
                continue
            }
            val raw = reader.readBytes()
            BoardConfig.of(tag ushr WireType.FIELD_SHIFT, module)?.let { return BoardConfigRaw(it, raw) }
        }
        return null
    }

    private fun decodeChannelSettings(reader: ProtoReader): ChannelSettings {
        var name = ""
        var psk = ByteArray(0)
        var useAead = false
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                CHANNEL_SETTINGS_PSK -> psk = reader.readBytes()
                CHANNEL_SETTINGS_NAME -> name = reader.readString()
                CHANNEL_SETTINGS_USE_AEAD -> useAead = reader.readVarint32() != 0
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return ChannelSettings(name, psk, useAead)
    }

    /** The `ChannelSettings` fields the decoder keeps, carried together so [decodeChannelInfo] reads once. */
    private class ChannelSettings(
        val name: String = "",
        val psk: ByteArray = ByteArray(0),
        val useAead: Boolean = false,
    )

    private fun decodeMetadata(reader: ProtoReader): FromRadio.Metadata {
        var firmware: String? = null
        var hasXeddsa: Boolean? = null
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                METADATA_FIRMWARE_VERSION -> firmware = reader.readString()
                METADATA_HAS_XEDDSA -> hasXeddsa = reader.readVarint32() != 0
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        return FromRadio.Metadata(firmware, hasXeddsa)
    }

    // ToRadio field numbers.
    private const val TO_PACKET = 1
    private const val TO_WANT_CONFIG_ID = 3
    private const val TO_DISCONNECT = 4
    private const val TO_HEARTBEAT = 7
    private const val HEARTBEAT_NONCE = 1

    // FromRadio field numbers.
    private const val FR_PACKET = 2
    private const val FR_MY_INFO = 3
    private const val FR_NODE_INFO = 4
    private const val FR_CONFIG = 5
    private const val FR_CONFIG_COMPLETE = 7
    private const val FR_REBOOTED = 8
    private const val FR_CHANNEL = 10
    private const val FR_QUEUE_STATUS = 11
    private const val FR_METADATA = 13

    // MeshPacket field numbers.
    private const val MP_FROM = 1
    private const val MP_TO = 2
    private const val MP_CHANNEL = 3
    private const val MP_DECODED = 4
    private const val MP_ENCRYPTED = 5
    private const val MP_ID = 6

    /**
     * `MeshPacket.rx_time` (fixed32, epoch seconds): when the receiving board took the packet off the air, by its own
     * clock — zero when it has none. Read so a hearing can tell a live packet from one the board queued for the phone
     * while it was away and replays at reconnect (ADR 2026-10.x9fa).
     */
    private const val MP_RX_TIME = 7
    private const val MP_RX_SNR = 8
    private const val MP_HOP_LIMIT = 9

    /**
     * `MeshPacket.want_ack`: ask the destination's board to acknowledge, and the firmware to retry until it does. Written
     * on a compact DM, and read off one addressed to us: what draws our own board's ACK (ADR 2026-10.x9fa).
     */
    private const val MP_WANT_ACK = 10
    private const val MP_RX_RSSI = 12
    private const val MP_VIA_MQTT = 14
    private const val MP_HOP_START = 15

    /**
     * `MeshPacket.next_hop` (uint32 carrying one byte): the last byte of the node the sender's next-hop routing chose
     * to carry it on, 0 for none. A unicast to us without `want_ack` still draws our board's hop-zero ACK when it names
     * us here with hops left (`ReliableRouter::sniffReceived`), so it is read beside [MP_WANT_ACK].
     */
    private const val MP_NEXT_HOP = 18

    /**
     * `MeshPacket.xeddsa_signed` — the board's own verdict: it verified the packet's signature against the
     * key its NodeDB holds for the sender. Computed on receive and never trusted off the air; absent (false)
     * when the packet was unsigned or the board had no key to check it with.
     */
    private const val MP_XEDDSA_SIGNED = 22

    /** `MeshPacket.pki_encrypted` — the board PKI-decrypted it (field 17). */
    private const val MP_PKI_ENCRYPTED = 17

    // Data field numbers.
    private const val DATA_PORTNUM = 1
    private const val DATA_PAYLOAD = 2
    private const val DATA_WANT_RESPONSE = 3
    private const val DATA_REQUEST_ID = 6

    /** `Data.reply_id` and `Data.emoji` (`fixed32` both): a reply's or a tapback's target, and the tapback flag. */
    private const val DATA_REPLY_ID = 7
    private const val DATA_EMOJI = 8

    /** `Data.bitfield` (`optional uint32`): bit 0 ok-to-MQTT, bit 1 want-response; the sender's board fills it in. */
    private const val DATA_BITFIELD = 9

    /** `Data.xeddsa_signature` — the 64-byte XEdDSA signature 2.8 attaches to a broadcast that fits signed. */
    private const val DATA_XEDDSA_SIGNATURE = 10

    // AdminMessage field numbers (a `oneof`, so at most one of these per message) + its session key.
    private const val ADMIN_GET_CHANNEL_REQUEST = 1
    private const val ADMIN_GET_CHANNEL_RESPONSE = 2
    private const val ADMIN_GET_OWNER_REQUEST = 3
    private const val ADMIN_GET_OWNER_RESPONSE = 4
    private const val ADMIN_GET_CONFIG_REQUEST = 5
    private const val ADMIN_GET_CONFIG_RESPONSE = 6
    private const val ADMIN_GET_MODULE_CONFIG_REQUEST = 7
    private const val ADMIN_GET_MODULE_CONFIG_RESPONSE = 8
    private const val ADMIN_SET_OWNER = 32
    private const val ADMIN_SET_CHANNEL = 33
    private const val ADMIN_SET_CONFIG = 34
    private const val ADMIN_SET_MODULE_CONFIG = 35
    private const val ADMIN_BEGIN_EDIT = 64
    private const val ADMIN_COMMIT_EDIT = 65
    private const val ADMIN_ADD_CONTACT = 66
    private const val ADMIN_SESSION_PASSKEY = 101

    // SharedContact field numbers (`admin.proto`): what `add_contact` carries.
    private const val SHARED_CONTACT_NODE_NUM = 1
    private const val SHARED_CONTACT_USER = 2

    // ChannelSettings field numbers (psk + name; the rest are left at their proto3 defaults).
    private const val CHANNEL_SETTINGS_PSK = 2
    private const val CHANNEL_SETTINGS_MODULE_SETTINGS = 7

    /** `ChannelSettings.use_aead` — read off the handshake, never written: `encodeAdminSetChannel` leaves it at false. */
    const val CHANNEL_SETTINGS_USE_AEAD = 8
    private const val MODULE_SETTINGS_POSITION_PRECISION = 1

    // QueueStatus / MyNodeInfo / Channel / DeviceMetadata / Routing field numbers.
    private const val QS_RES = 1
    private const val QS_FREE = 2
    private const val QS_MAXLEN = 3
    private const val QS_MESH_PACKET_ID = 4
    private const val MYINFO_NODE_NUM = 1
    private const val MYINFO_PIO_ENV = 13
    private const val CHANNEL_INDEX = 1
    private const val CHANNEL_SETTINGS = 2
    private const val CHANNEL_SETTINGS_NAME = 3
    private const val CHANNEL_ROLE = 3
    private const val METADATA_FIRMWARE_VERSION = 1

    /** `DeviceMetadata.has_xeddsa` — this build verifies XEdDSA signatures (and so signs); absent on builds that predate it. */
    private const val METADATA_HAS_XEDDSA = 14
    private const val ROUTING_ERROR_REASON = 3

    // Config / Config.LoRaConfig field numbers (the radio settings the airtime governor reads).
    private const val CONFIG_LORA = 6

    /** `Config.security` — only its [SECURITY_PACKET_SIGNATURE_POLICY] is read; see [decodeConfig]. */
    private const val CONFIG_SECURITY = 8
    private const val SECURITY_PACKET_SIGNATURE_POLICY = 9
    private const val LORA_USE_PRESET = 1
    private const val LORA_MODEM_PRESET = 2
    private const val LORA_REGION = 7
    private const val LORA_HOP_LIMIT = 8
    private const val LORA_OVERRIDE_DUTY_CYCLE = 12

    // NodeInfo / Telemetry / DeviceMetrics field numbers (the battery path).
    private const val NODEINFO_NUM = 1
    private const val NODEINFO_USER = 2
    private const val NODEINFO_DEVICE_METRICS = 6
    private const val TELEMETRY_DEVICE_METRICS = 2
    private const val DM_BATTERY_LEVEL = 1
    private const val DM_VOLTAGE = 2
    private const val DM_CHANNEL_UTILIZATION = 3
    private const val DM_AIR_UTIL_TX = 4
}

/** A `MeshPacket` we send: one `Data` sub-message to [to] (broadcast by default), a client-assigned [id] for NAK and ACK correlation. */
internal data class OutboundPacket(
    val to: UInt = MeshtasticProto.BROADCAST,
    val channelIndex: Int,
    val id: UInt,
    val portnum: Int = MeshtasticProto.PORT_PRIVATE_APP,
    val payload: ByteArray,
    val hopLimit: Int? = null,
    /** Set on an admin request so the local node returns a response (the passkey / get-channel reply). */
    val wantResponse: Boolean = false,
    /** `MeshPacket.want_ack`: a direct unicast asks its destination's board to acknowledge (ADR 2026-10.x9fa). */
    val wantAck: Boolean = false,
)

/** One channel Knit writes to the board via `set_channel`: a slot with a name + AES PSK, at some [role]. */
internal data class ChannelWrite(
    val index: Int,
    val name: String,
    val psk: ByteArray,
    val role: Int = MeshtasticProto.ROLE_SECONDARY,
    /**
     * `module_settings.position_precision`; null writes no `module_settings` at all. `set_channel` replaces the whole
     * `ChannelSettings`, so on a slot that already exists null does **not** keep the board's value — the firmware falls
     * back to full precision — and is safe only for a slot Knit wrote itself or one being disabled. A rewrite of an
     * existing slot that must keep everything else goes through [MeshtasticProto.encodeAdminSetChannelSettings].
     */
    val positionPrecision: Int? = null,
)

/** What provisioning reads out of an inbound `AdminMessage`: the [passkey] and whatever the read returned. */
internal data class AdminReply(
    val passkey: ByteArray?,
    val channel: ChannelInfo?,
    /** The sub-config a `get_config` / `get_module_config` returned, kept as raw bytes for splicing. */
    val config: BoardConfigRaw? = null,
    /** The `User` a `get_owner_request` returned, likewise kept raw. */
    val owner: BoardOwnerRaw? = null,
)

/**
 * One board sub-config the setup rewrites (ADR 045), naming both numbers the Meshtastic admin API
 * uses for it: [configType] is the `ConfigType` / `ModuleConfigType` enum value a *request* carries, and
 * [member] is the `Config` / `ModuleConfig` oneof field the *payload* rides in. They differ — LoRa is
 * `ConfigType.LORA_CONFIG = 5` but `Config.lora = 6` — so they are never interchangeable.
 */
internal enum class BoardConfig(
    val configType: Int,
    val member: Int,
    val module: Boolean,
) {
    DEVICE(configType = 0, member = 1, module = false),
    POSITION(configType = 1, member = 2, module = false),
    TELEMETRY(configType = 5, member = 6, module = true),

    /**
     * The radio config. Deliberately **not** in [QUIET], so the ordinary setup never reads or writes a
     * legally-scoped sub-config (ADR 045); only the dedicated-slot setup and the restore that
     * undoes it touch it, and then only to splice [MeshtasticProto.LORA_CHANNEL_NUM] (ADR 067). Note it
     * collides with [TELEMETRY] on both numbers and is told apart by [module] alone.
     */
    LORA(configType = 5, member = 6, module = false),
    ;

    companion object {
        /**
         * The sub-configs every setup reads and quiets. [LORA] is excluded on purpose: reading it costs an
         * extra admin round-trip on every board, and writing it — even spliced byte-identically — would break
         * ADR 045's promise that a plain setup never touches the radio.
         */
        val QUIET: List<BoardConfig> = listOf(DEVICE, POSITION, TELEMETRY)

        fun of(
            member: Int,
            module: Boolean,
        ): BoardConfig? = entries.firstOrNull { it.member == member && it.module == module }
    }
}

/** A sub-config exactly as the board sent it — the base every read-modify-write splices into. */
internal class BoardConfigRaw(
    val config: BoardConfig,
    val raw: ByteArray,
)

/**
 * The board's `User` exactly as it sent it — the base the owner write splices into — alongside the identity
 * decoded out of it, which is what a setup records so a restore can put it back.
 */
internal class BoardOwnerRaw(
    val raw: ByteArray,
) {
    val owner: BoardOwner =
        BoardOwner(
            longName = readStringField(raw, MeshtasticProto.USER_LONG_NAME).orEmpty(),
            shortName = readStringField(raw, MeshtasticProto.USER_SHORT_NAME).orEmpty(),
            // `optional`, so absent and explicitly-false are distinguishable on the wire — but not to any
            // reader: both mean the node accepts messages, which is what a stock board is.
            unmessagable = (readVarintField(raw, MeshtasticProto.USER_IS_UNMESSAGABLE) ?: 0L) != 0L,
            // Only a whole key is a key: a truncated or empty field reads as "this node published none".
            publicKey =
                readBytesField(raw, MeshtasticProto.USER_PUBLIC_KEY)
                    ?.takeIf { it.size == MeshtasticProto.NODE_PUBLIC_KEY_BYTES }
                    ?.let(::b64),
        )

    /**
     * `User.is_licensed` — ham mode. Read only: on the bound board's own entry it says the firmware signs a compact DM
     * rather than PKI-encrypt it, and a node reporting it is never taught a key (ADR 2026-10.aajj). Kept off [owner],
     * which a setup records and compares.
     */
    val licensed: Boolean get() = (readVarintField(raw, MeshtasticProto.USER_IS_LICENSED) ?: 0L) != 0L
}

/** A decoded inbound `MeshPacket`. [decoded] is null when the packet arrived [encrypted] (a foreign channel). */
internal class MeshPacket(
    val from: UInt,
    val to: UInt,
    val channel: Int,
    val id: UInt,
    val decoded: MeshData?,
    val encrypted: Boolean,
    val rxSnr: Float?,
    val rxRssi: Int?,
    val hopLimit: Int,
    val hopStart: Int,
    /**
     * `MeshPacket.via_mqtt` — the packet reached this mesh through somebody's MQTT uplink rather than over the
     * air. Load-bearing only for the Meshtastic room, where it is the difference between "somebody near you
     * said this" and "this came off the internet".
     */
    val viaMqtt: Boolean = false,
    /**
     * `MeshPacket.xeddsa_signed` — the board verified `decoded.signature` against the key *its* NodeDB holds
     * for [from]. True is real evidence that the radio which has been using that number sent these bytes;
     * false says nothing (unsigned, or a sender the board holds no key for). A signature that fails against
     * the board's key never reaches the phone at all — the firmware drops it, whatever its policy.
     */
    val xeddsaSigned: Boolean = false,
    /**
     * `MeshPacket.rx_time`, epoch **seconds** on the receiving board's clock, or null when the board reported none
     * (no clock set). A packet the board queued for the phone while it was away keeps the time it was heard, which is
     * what tells that replay from a live hearing.
     */
    val rxTime: UInt? = null,
    /** `MeshPacket.want_ack` — the sender asked the destination's board to acknowledge it. */
    val wantAck: Boolean = false,
    /** `MeshPacket.next_hop` — the last byte of the node chosen to carry it on, 0 for no preference. */
    val nextHop: Int = 0,
    /**
     * `MeshPacket.pki_encrypted` — the board decrypted it with its own private key and the sender's public key, and
     * reports it on index 0 whatever channel the sender's board used. Every compact DM from a 2.8 board arrives so
     * (ADR 2026-10.x9fa's PKI amendment).
     */
    val pkiEncrypted: Boolean = false,
) {
    /** How many hops away the origin is (`hop_start - hop_limit`), or null when the board didn't report a start. */
    val hopsAway: Int? get() = if (hopStart > 0) (hopStart - hopLimit).coerceAtLeast(0) else null
}

/**
 * The two `DeviceMetrics` fields the bridge reads: `battery_level` (0–100; above 100 means external power;
 * absent when the board can't tell) and `voltage` (volts). Folded for display by [BoardBattery.of].
 */
internal data class DeviceMetrics(
    val batteryLevel: Int?,
    val voltage: Float?,
    /**
     * How much of the last **minute** the board heard the channel busy, as a percentage: its own transmissions, what it
     * received and the noise together (firmware `airtime.h`: six 10 s periods). The shared-band brake reads it (ADR
     * 2026-10.ug4a).
     */
    val channelUtilPercent: Float? = null,
    /**
     * How much of the last hour the board spent **transmitting**, as a percentage — its own measurement of
     * the thing [app.getknit.knit.mesh.lora.LoraAirtime] estimates. The two are not the same quantity and
     * must not be read as if they were: this counts every packet the radio sent, relays of other people's
     * traffic included, over an hour; the governor counts what Knit handed the board over fifteen minutes.
     * Surfaced so the gap between them is visible rather than inferred.
     */
    val airUtilTxPercent: Float? = null,
)

/** A decoded `Data` sub-message. */
internal class MeshData(
    val portnum: Int,
    val payload: ByteArray,
    val requestId: UInt,
    /**
     * `Data.xeddsa_signature`, exactly 64 bytes or null. The sender's board signed the packet and its whole `Data`
     * envelope ([signedFields], 2.8.1's layout) under its Curve25519 key; `mesh/crypto/XeddsaVerify` checks it on the phone against a key a
     * contact's profile advertises, which is what turns "some radio sent this" into "their radio sent this".
     */
    val signature: ByteArray? = null,
    /**
     * The rest of the `Data` envelope firmware 2.8.1 signs (`want_response`, `request_id`, `reply_id`, `emoji`, the
     * bitfield with its presence), as decoded — what `XeddsaVerify` rebuilds the 2.8.1 signing input from. Null only
     * on a value not made by the decoder.
     */
    val signedFields: XeddsaVerify.DataFields? = null,
)

/** One channel as the board reports it in the config handshake, so the transport can select by [name]. */
internal data class ChannelInfo(
    val index: Int,
    val name: String,
    val role: Int,
    /**
     * `ChannelSettings.psk`, verbatim. Empty means the field was absent, which the firmware reads as the
     * default key. Decoded so the table round-trips whole; nothing in Knit judges a channel by its key any
     * more — the Meshtastic room mirrors slot 0 as the user keyed it.
     */
    val psk: ByteArray = ByteArray(0),
    /**
     * `ChannelSettings.use_aead` (8): the channel encrypts with AES-CCM, which adds
     * [MeshtasticProto.AEAD_OVERHEAD_BYTES] to every packet, stops firmware with upstream `34cb8d582` signing on it, and
     * changes the channel's hash (`Channels.cpp:53`, `h ^= 0xAE`) — so a Knit slot switched to it is no longer the
     * channel every other Knit board is on (`LoraMeshTransport.boundSlotIsKnit`). Read only: Knit never writes it.
     */
    val useAead: Boolean = false,
    /**
     * The whole `ChannelSettings` as the board sent it, so a rewrite can keep every field Knit does not model
     * (`MeshtasticProto.encodeAdminSetChannelSettings`: the setup clearing AEAD, #243). Left out of [equals]: the table is
     * compared by what Knit reads off it, and a value built in a test or by hand has none.
     */
    val settingsRaw: ByteArray = ByteArray(0),
) {
    /** A `ByteArray` member makes the generated `equals`/`hashCode` identity-based; compare the bytes instead. */
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is ChannelInfo &&
                    index == other.index &&
                    name == other.name &&
                    role == other.role &&
                    psk.contentEquals(other.psk) &&
                    useAead == other.useAead
            )

    override fun hashCode(): Int =
        ((((index * PRIME) + name.hashCode()) * PRIME + role) * PRIME + psk.contentHashCode()) * PRIME + useAead.hashCode()

    private companion object {
        const val PRIME = 31
    }
}

/**
 * `Config.SecurityConfig.packet_signature_policy` (#243): what a board with upstream `34cb8d582` (reported `2.8.2`) signs.
 * Tag 2.8.1 ignores it and signs every broadcast. The session reads a board that streamed no security config as null —
 * unknown, priced as signing, never as [COMPATIBLE].
 */
internal enum class SignaturePolicy {
    /** 0, the default: never signs. */
    COMPATIBLE,

    /** 1: signs broadcasts, and a NodeInfo or position even unicast; ham mode signs its non-routing unicasts. */
    BALANCED,

    /** 2: signs as [BALANCED], and drops every unsigned non-PKI, non-routing packet it receives (`Router.cpp:780-784`). */
    STRICT,

    /** A value this build does not know: a future policy, priced as signing. */
    UNRECOGNISED,
    ;

    companion object {
        fun fromCode(code: Int): SignaturePolicy =
            when (code) {
                0 -> COMPATIBLE
                1 -> BALANCED
                2 -> STRICT
                else -> UNRECOGNISED
            }
    }
}

/** The `FromRadio` variants the bridge acts on; everything else decodes to [Other] and is ignored. */
internal sealed interface FromRadio {
    /** A 0-length read: the board's FromRadio queue is drained. */
    data object Empty : FromRadio

    data class MyInfo(
        val myNodeNum: UInt,
        val pioEnv: String?,
    ) : FromRadio

    data class Metadata(
        val firmwareVersion: String?,
        /**
         * `DeviceMetadata.has_xeddsa` — the build can sign and verify XEdDSA. Set on every such build whatever its
         * `packet_signature_policy` (`main.cpp:1390`), so it says what the firmware *can* do, never whether it signs
         * (#243); [LoraAirtime.price] reads it beside the version and the policy. Null on firmware that predates it.
         */
        val hasXeddsa: Boolean? = null,
    ) : FromRadio

    /** One NodeDB entry, streamed in the handshake (and pushed on change); only the board's own [metrics] are read. */
    data class NodeInfo(
        val num: UInt,
        val metrics: DeviceMetrics?,
        /** `NodeInfo.user` — for the board's own entry, what it calls itself on the mesh. */
        val owner: BoardOwner? = null,
        /**
         * The same `User` exactly as the board sent it: the key the board's NodeDB holds for [num], and the base an
         * `add_contact` splices our contact's key into (ADR 2026-10.aajj). Not compared: a class, not a value.
         */
        val user: BoardOwnerRaw? = null,
    ) : FromRadio

    data class Channel(
        val channel: ChannelInfo,
    ) : FromRadio

    /**
     * A `Config` variant from the handshake; [lora] is non-null only for the radio-settings variant, [signaturePolicy]
     * only for the security one (its `packet_signature_policy`, never the keys beside it).
     */
    data class Config(
        val lora: LoraRadioConfig?,
        val signaturePolicy: SignaturePolicy? = null,
    ) : FromRadio

    data class ConfigComplete(
        val id: UInt,
    ) : FromRadio

    class Packet(
        val packet: MeshPacket,
    ) : FromRadio

    data class QueueStatus(
        val res: Int,
        val free: Int,
        val maxlen: Int,
        val meshPacketId: UInt,
    ) : FromRadio

    data object Rebooted : FromRadio

    /** A known-shape-but-unhandled variant, named by its field number for diagnostics. */
    data class Other(
        val fieldNumber: Int,
    ) : FromRadio
}

/**
 * The board's own radio settings, read off the `Config.LoRaConfig` the want_config handshake streams. They
 * are what [LoraAirtime] needs to turn a packet size into milliseconds of air and to know the legal duty
 * cycle — Knit never writes any of them (ADR 038 §10: region and modem preset are a per-board, legally
 * scoped setting the user configures once at flash time).
 *
 * [usePreset] false means the board is on hand-set bandwidth/spread-factor/coding-rate rather than
 * [modemPreset]; we keep the preset's estimate anyway and say so, since a wrong estimate is still far
 * better than none. [overrideDutyCycle] is the firmware's own escape hatch and is honoured: a user who set
 * it has taken the regulatory call, so we stop applying the regional cap and fall back to the polite
 * ceiling.
 *
 * [channelNum] is the one field Knit *can* write, and only through the dedicated setup (ADR 067): 0 means
 * the firmware derives the RF slot from the primary channel's name — the shared public frequency ADR 045
 * deliberately sits on — and non-zero means the board is pinned. Only the slot [LoraSlot] derives counts as
 * Knit's own ([dedicatedSlot]): a slot pinned by hand may be a community's, so [LoraAirtime] keeps the
 * politeness ceiling and the room stays up there, as they did before the setup shipped.
 */
internal data class LoraRadioConfig(
    val usePreset: Boolean,
    val modemPreset: ModemPreset,
    val region: LoraRegion,
    val hopLimit: Int,
    val overrideDutyCycle: Boolean,
    /** `Config.LoRaConfig.channel_num`: 0 = the slot hashed from the primary's name, non-zero = a pinned slot. */
    val channelNum: Int = 0,
) {
    /**
     * Whether the board sits on the slot Knit's dedicated setup pins ([LoraSlot.forRegion]) — never just any
     * non-zero [channelNum], which a community may share. Always false outside the regions [LoraSlot] knows.
     */
    val dedicatedSlot: Boolean get() = channelNum != 0 && channelNum == LoraSlot.forRegion(region, modemPreset)
}

/**
 * `Config.LoRaConfig.ModemPreset`, pinned by number, each carrying the spreading factor / bandwidth /
 * coding rate the Meshtastic firmware programs for it (`RadioInterface.cpp`). Those three are the whole
 * input to the LoRa time-on-air formula, which is why they live on the enum rather than in a side table.
 * An unrecognised code falls back to [LONG_FAST] — the shipping default, and the slowest of the common
 * presets, so an unknown future preset is over-estimated rather than under.
 */
@Suppress("MagicNumber") // the numbers ARE the wire codes and the firmware's radio parameters
internal enum class ModemPreset(
    val code: Int,
    val spreadFactor: Int,
    val bandwidthHz: Int,
    /** Coding-rate denominator: 5 means 4/5. */
    val codingRate: Int,
    /**
     * The name the firmware substitutes for an **empty primary channel name** (`Channels::getName`), and so
     * the string it hashes into the RF slot. A board whose primary is unnamed or named exactly this is on the
     * stock frequency for its preset — which is what lets two Knit boards meet without coordinating (ADR 045).
     */
    val defaultChannelName: String,
) {
    LONG_FAST(0, 11, 250_000, 5, "LongFast"),
    LONG_SLOW(1, 12, 125_000, 8, "LongSlow"),

    /** Deprecated in the protobuf and dropped from the firmware's name table, which now calls it "Invalid". */
    VERY_LONG_SLOW(2, 12, 62_500, 8, "VeryLongSlow"),
    MEDIUM_SLOW(3, 10, 250_000, 5, "MediumSlow"),
    MEDIUM_FAST(4, 9, 250_000, 5, "MediumFast"),
    SHORT_SLOW(5, 8, 250_000, 5, "ShortSlow"),
    SHORT_FAST(6, 7, 250_000, 5, "ShortFast"),
    LONG_MODERATE(7, 11, 125_000, 8, "LongMod"),
    SHORT_TURBO(8, 7, 500_000, 5, "ShortTurbo"),

    /**
     * What a **freshly flashed US board picks on 2.8** where it used to pick [LONG_FAST], and the reason the
     * rest of this list stopped being optional: the two cannot hear each other, so a board on this preset
     * meets no Knit board on the other however well it is set up (see [LoraRegion.defaultPreset]).
     */
    LONG_TURBO(9, 11, 500_000, 8, "LongTurbo"),

    // The EU 2.8 additions. Not reachable from a stock EU_868 board — each belongs to a sibling region whose
    // preset list is disjoint (`PROFILE_LITE` / `PROFILE_NARROW`) — but a board in one of those regions
    // reports them, and a preset Knit cannot name is one whose time-on-air it computes wrong.
    LITE_FAST(10, 9, 125_000, 5, "LiteFast"),
    LITE_SLOW(11, 10, 125_000, 5, "LiteSlow"),
    NARROW_FAST(12, 7, 62_500, 6, "NarrowFast"),
    NARROW_SLOW(13, 8, 62_500, 6, "NarrowSlow"),

    /** The ham 20 kHz presets: 15.6 kHz on the air, which the firmware pads out to a 20 kHz slot. */
    TINY_FAST(14, 7, 15_600, 5, "TinyFast"),
    TINY_SLOW(15, 8, 15_600, 6, "TinySlow"),
    MEDIUM_TURBO(16, 9, 500_000, 5, "MediumTurbo"),
    ;

    companion object {
        fun fromCode(code: Int): ModemPreset = entries.firstOrNull { it.code == code } ?: LONG_FAST
    }
}

/**
 * `Config.LoRaConfig.RegionCode`, pinned by number, carrying the transmit **duty cycle** percentage the
 * Meshtastic firmware enforces for each (`RegionInfo` in `RadioInterface.cpp`). Only the duty-limited
 * regions need naming individually; everything else is 100 % and collapses into [OTHER]. [UNSET] means the
 * board has not been given a region and will not transmit at all — treated as the conservative case.
 *
 * [bandStartKhz]/[bandEndKhz] are populated for exactly the regions whose band Knit is prepared to place a
 * **dedicated RF slot** in (ADR 067): picking a slot means computing a transmit frequency
 * ourselves, and a slot past the end of the band is out-of-band transmission, not a bug. So the band is
 * stated only where it is known exactly, [OTHER] carries none — it is a bucket of regions with different
 * bands, and the narrowest of them (RU, ~0.5 MHz) has no room to move — and [LoraSlot] refuses everything it
 * cannot place. Everything else about a region, the duty cycle included, is unaffected by this.
 *
 * [defaultPreset] follows the same "stated only where it is known exactly" rule, for the same reason: a guess
 * there is a notice shown to somebody with nothing wrong. So [OTHER] carries none — it buckets the ham
 * regions, whose defaults are `TinyFast`/`NarrowSlow`, together with the LongFast ones — and the notice
 * simply stays quiet there.
 */
@Suppress("MagicNumber") // the numbers ARE meshtastic's RegionCode wire codes and the band edges in kHz
internal enum class LoraRegion(
    val code: Int,
    val dutyCyclePercent: Double,
    /** Band start in kHz, or 0 where Knit does not know it exactly enough to place a slot in it. */
    val bandStartKhz: Int = 0,
    /** Band end in kHz, or 0 alongside a 0 [bandStartKhz]. */
    val bandEndKhz: Int = 0,
    /**
     * The preset a stock board in this region picks for itself (`RegionInfo::defaultPreset`), or null where
     * Knit will not claim to know. A board on any other preset is deaf to every board on this one, and picks
     * a different RF slot besides — the firmware hashes the preset's own display name whenever the primary
     * channel is unnamed (`Channels::getName`), so the two differ in frequency as well as in modulation.
     *
     * This is **not** the preset Knit wants a board to be on. Knit does not choose one at all: ADR 045 buys
     * its reach by borrowing hops from whatever stock nodes are nearby, and those only relay traffic sharing
     * their own radio parameters, so the right preset is whatever the local mesh actually runs — regularly
     * something other than this. It is a default, not an authority; [app.getknit.knit.ui.lora.PresetMismatch]
     * uses it only to notice that the user now has a setting to keep in step across their own boards.
     */
    val defaultPreset: ModemPreset? = ModemPreset.LONG_FAST,
) {
    UNSET(0, DUTY_LIMITED_PERCENT),
    EU_433(2, DUTY_LIMITED_PERCENT),
    EU_868(3, DUTY_LIMITED_PERCENT),
    UA_433(14, DUTY_LIMITED_PERCENT),

    /** Deprecated in the protobuf and gone from the firmware's region table; still reported by 2.7 boards. */
    UA_868(15, DUTY_LIMITED_PERCENT),

    /** Thailand, 10 % — duty-limited since long before 2.8 and missed here until the 2.8 audit. */
    TH(12, DUTY_LIMITED_PERCENT),

    /**
     * 2.8's EU siblings on the 868 band, both duty-limited and neither defaulting to LongFast. Collapsing
     * these into [OTHER] granted them the 100 % allowance, which for [EU_866] is forty times the legal one.
     */
    EU_866(29, EU_866_DUTY_PERCENT, defaultPreset = ModemPreset.LITE_FAST),
    EU_N_868(32, DUTY_LIMITED_PERCENT, defaultPreset = ModemPreset.NARROW_SLOW),

    /** 902–928 MHz: 104 slots at LongFast's 250 kHz, which is what makes a dedicated slot worth having. */
    US(1, 100.0, bandStartKhz = 902_000, bandEndKhz = 928_000),

    /** 915–928 MHz: 52 slots at 250 kHz. */
    ANZ(6, 100.0, bandStartKhz = 915_000, bandEndKhz = 928_000),

    /** Every remaining region the firmware runs at 100 % duty (JP, IN, RU, the ham carve-outs, …). */
    OTHER(-1, 100.0, defaultPreset = null),
    ;

    /** How wide the band is, in kHz; 0 where Knit does not know it (see [bandStartKhz]). */
    val bandWidthKhz: Int get() = (bandEndKhz - bandStartKhz).coerceAtLeast(0)

    companion object {
        fun fromCode(code: Int): LoraRegion = entries.firstOrNull { it.code == code && it != OTHER } ?: OTHER
    }
}

/** The duty cycle the firmware enforces in the ETSI-style regions above. */
private const val DUTY_LIMITED_PERCENT = 10.0

/** EU_866's own limit (2.5 %), the tightest the firmware enforces anywhere. */
private const val EU_866_DUTY_PERCENT = 2.5

/**
 * `Routing.Error` values (`meshtastic/mesh.proto`), pinned by number. [UNKNOWN] catches a future code so a
 * NAK we don't recognise is still surfaced rather than swallowed.
 */
@Suppress("MagicNumber") // the numbers ARE meshtastic's Routing.Error wire codes; the enum name documents each
internal enum class RoutingError(
    val code: Int,
) {
    NONE(0),
    NO_ROUTE(1),
    GOT_NAK(2),
    TIMEOUT(3),
    NO_INTERFACE(4),
    MAX_RETRANSMIT(5),
    NO_CHANNEL(6),
    TOO_LARGE(7),
    NO_RESPONSE(8),
    DUTY_CYCLE_LIMIT(9),
    BAD_REQUEST(32),
    NOT_AUTHORIZED(33),
    PKI_FAILED(34),
    PKI_UNKNOWN_PUBKEY(35),
    ADMIN_BAD_SESSION_KEY(36),
    ADMIN_PUBLIC_KEY_UNAUTHORIZED(37),
    RATE_LIMIT_EXCEEDED(38),

    /**
     * Our own board refused a unicast before it aired: it PKI-encrypts every unicast its phone originates and holds no
     * key for the destination (`Router.cpp:1351-1355` at v2.8.1). Reported first as the send's `queueStatus.res`, then
     * as a self-NAK on index 0 (ADR 2026-10.aajj).
     */
    PKI_SEND_FAIL_PUBLIC_KEY(39),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromCode(code: Int): RoutingError = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
