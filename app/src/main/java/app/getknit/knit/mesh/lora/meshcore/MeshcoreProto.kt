package app.getknit.knit.mesh.lora.meshcore

import java.security.MessageDigest

/**
 * The MeshCore companion protocol as Knit speaks it to a board (ADR 2026-11.gsqu, Stage 3a): a hand-rolled, total codec
 * over the frames `examples/companion_radio/MyMesh.cpp` writes at tag `companion-v1.18.0`, one frame per NUS write and per
 * notify, every integer little-endian. Line numbers below are that file's at that tag unless another file is named.
 *
 * **Encoders** build only the commands the plane uses — [ENCODED_COMMANDS], pinned by `MeshcoreProtoTest` against the
 * forbidden ones (a private-key export or import, a PIN, the radio, a raw packet, the CLI) — and refuse (null) what the
 * firmware would cut or reject silently: a zero byte in a text, a text past its cap, a payload past the BLE notify cap.
 *
 * **[decode]** never throws: a short, over-long or inconsistent frame is a [McFrame.Rejected], and an unknown code is
 * [McFrame.Unknown]. A frame whose length byte claims more than arrived is rejected — an ESP32 board truncates a notify
 * past its 173 B silently and keeps the length byte (bench Q8).
 *
 * Nothing here keeps a position, a channel secret's provenance or a name past the frame it was decoded from, and no
 * frame type's `toString` prints one (`.agents/rules` security: ADR 2026-11.gsqu binding rules, "what leaves the session").
 */
@Suppress(
    "TooManyFunctions", // one encoder per command the plane sends, one reader per frame layout the board writes
    "MagicNumber", // the numbers ARE the firmware's byte offsets within each frame, named in each reader's KDoc
)
internal object MeshcoreProto {
    // --- command codes (MyMesh.cpp:9-66) ---
    const val CMD_APP_START = 1
    const val CMD_SEND_TXT_MSG = 2
    const val CMD_SEND_CHANNEL_TXT_MSG = 3
    const val CMD_GET_CONTACTS = 4
    const val CMD_GET_DEVICE_TIME = 5
    const val CMD_SET_DEVICE_TIME = 6
    const val CMD_SET_ADVERT_NAME = 8
    const val CMD_ADD_UPDATE_CONTACT = 9
    const val CMD_SYNC_NEXT_MESSAGE = 10
    const val CMD_RESET_PATH = 13
    const val CMD_REMOVE_CONTACT = 15
    const val CMD_GET_BATT_AND_STORAGE = 20
    const val CMD_DEVICE_QUERY = 22
    const val CMD_GET_CONTACT_BY_KEY = 30
    const val CMD_GET_CHANNEL = 31
    const val CMD_SET_CHANNEL = 32
    const val CMD_SET_OTHER_PARAMS = 38
    const val CMD_SET_FLOOD_SCOPE_KEY = 54
    const val CMD_GET_STATS = 56
    const val CMD_SET_AUTOADD_CONFIG = 58
    const val CMD_GET_AUTOADD_CONFIG = 59
    const val CMD_SEND_CHANNEL_DATA = 62
    const val CMD_GET_DEFAULT_FLOOD_SCOPE = 64

    /** The board's own signer (`MyMesh.cpp:1781-1815`, since 2025-03): start, data (up to 8 KiB in all), finish. */
    const val CMD_SIGN_START = 33
    const val CMD_SIGN_DATA = 34
    const val CMD_SIGN_FINISH = 35

    /**
     * Every command code an encoder here can emit — the pinned allowlist. `CMD_GET_CONTACTS` (4) is a count read (its
     * `since` past every `lastmod`, so the board lists none: teaching a key needs a free slot confirmed first) or, from
     * Stage 5b-2, the one listing a Ready's reconcile, a setup's erase and a restore read ([listContacts]). The three sign
     * commands have two uses, each a fixed message behind its own Knit domain tag ([signData]): the consent's signed
     * challenge (the maintainer's decision, 2026-11-13: a fresh nonce) and, since Stage 5d, the bind of our node id to the
     * board's key (`MeshcoreBind.message`), the sealed profile's proof of possession. Never anything else.
     */
    val ENCODED_COMMANDS: Set<Int> =
        setOf(
            CMD_APP_START,
            CMD_SEND_TXT_MSG,
            CMD_SEND_CHANNEL_TXT_MSG,
            CMD_GET_CONTACTS,
            CMD_GET_DEVICE_TIME,
            CMD_SET_DEVICE_TIME,
            CMD_SET_ADVERT_NAME,
            CMD_ADD_UPDATE_CONTACT,
            CMD_SYNC_NEXT_MESSAGE,
            CMD_RESET_PATH,
            CMD_REMOVE_CONTACT,
            CMD_GET_BATT_AND_STORAGE,
            CMD_DEVICE_QUERY,
            CMD_GET_CONTACT_BY_KEY,
            CMD_GET_CHANNEL,
            CMD_SET_CHANNEL,
            CMD_SET_OTHER_PARAMS,
            CMD_SET_FLOOD_SCOPE_KEY,
            CMD_GET_STATS,
            CMD_SET_AUTOADD_CONFIG,
            CMD_GET_AUTOADD_CONFIG,
            CMD_SEND_CHANNEL_DATA,
            CMD_GET_DEFAULT_FLOOD_SCOPE,
            CMD_SIGN_START,
            CMD_SIGN_DATA,
            CMD_SIGN_FINISH,
        )

    // --- response codes (MyMesh.cpp:73-101) ---
    const val RESP_OK = 0
    const val RESP_ERR = 1
    const val RESP_CONTACTS_START = 2
    const val RESP_CONTACT = 3
    const val RESP_END_OF_CONTACTS = 4
    const val RESP_SELF_INFO = 5
    const val RESP_SENT = 6
    const val RESP_CONTACT_MSG_RECV = 7
    const val RESP_CHANNEL_MSG_RECV = 8
    const val RESP_CURR_TIME = 9
    const val RESP_NO_MORE_MESSAGES = 10
    const val RESP_BATT_AND_STORAGE = 12
    const val RESP_DEVICE_INFO = 13
    const val RESP_DISABLED = 15
    const val RESP_CONTACT_MSG_RECV_V3 = 16
    const val RESP_CHANNEL_MSG_RECV_V3 = 17
    const val RESP_CHANNEL_INFO = 18
    const val RESP_STATS = 24
    const val RESP_AUTOADD_CONFIG = 25
    const val RESP_CHANNEL_DATA_RECV = 27
    const val RESP_DEFAULT_FLOOD_SCOPE = 28
    const val RESP_SIGN_START = 19
    const val RESP_SIGNATURE = 20

    /** An Ed25519 signature (`SIGNATURE_SIZE`). */
    const val SIGNATURE_BYTES = 64

    // --- pushes, written to every connected interface at any time (MyMesh.cpp:113-129) ---
    const val PUSH_ADVERT = 0x80
    const val PUSH_PATH_UPDATED = 0x81
    const val PUSH_SEND_CONFIRMED = 0x82
    const val PUSH_MSG_WAITING = 0x83
    const val PUSH_LOG_RX_DATA = 0x88
    const val PUSH_NEW_ADVERT = 0x8A
    const val PUSH_CONTACT_DELETED = 0x8F
    const val PUSH_CONTACTS_FULL = 0x90

    /** Codes from here up are pushes; below are answers to a command. */
    const val PUSH_FLOOR = 0x80

    // --- ERR codes (MyMesh.cpp:131-136) ---
    const val ERR_UNSUPPORTED_CMD = 1
    const val ERR_NOT_FOUND = 2
    const val ERR_TABLE_FULL = 3
    const val ERR_BAD_STATE = 4
    const val ERR_FILE_IO = 5
    const val ERR_ILLEGAL_ARG = 6

    // --- the values the plane writes ---

    /** The protocol version Knit asks for in `DEVICE_QUERY`: 3 makes the board answer with the V3 receive frames. */
    const val APP_TARGET_VERSION = 3

    /**
     * The oldest `FIRMWARE_VER_CODE` a session reaches Ready on: 11, companion v1.15.0, the first with
     * `SET_FLOOD_SCOPE_KEY` (54) as a key, `SEND_CHANNEL_DATA` (62) and `GET_DEFAULT_FLOOD_SCOPE` (64) — every one
     * handshake and room post need (the v1.12.0–v1.14.1 tags define 54 under another name and neither of the others).
     */
    const val MIN_FIRMWARE_VERSION_CODE = 11

    /** `TXT_TYPE_PLAIN` (`TxtDataHelpers.h:6`): the one contact text type the receiving board acks. */
    const val TXT_TYPE_PLAIN = 0

    /** `TXT_TYPE_SIGNED_PLAIN` (`TxtDataHelpers.h:8`): acked too, under a 4-B code where a plain text's is 6 B. */
    const val TXT_TYPE_SIGNED_PLAIN = 2

    /** `ADV_TYPE_CHAT`: a companion, what Knit teaches every contact as. */
    const val ADV_TYPE_CHAT = 1

    /** A contact's favourite bit (`BaseChatMesh.cpp:93`): the overwrite-oldest rule never evicts one. */
    const val CONTACT_FLAG_FAVOURITE = 0x01

    /** `OUT_PATH_UNKNOWN`: on a contact, route by flood; in a receive frame, the packet arrived direct (Q1). */
    const val PATH_UNKNOWN = 0xFF

    /** `CMD_SEND_CHANNEL_DATA`'s `path_len` asking for a flood. */
    const val PATH_FLOOD = 0xFF

    /** The hop count in a `path_len` (`Packet.h:79-80`: bits 6–7 are the hash size). */
    const val PATH_HOPS_MASK = 0x3F

    /**
     * `DATA_TYPE_DEV` (`TxtDataHelpers.h:12`), the firmware's own development namespace. The binding rule of the
     * 2026-11-13 design passes: Knit's channel data rides this until MeshCore's `number_allocations.md` registers a
     * value for Knit, and `MeshcoreProtoTest` keeps a release build from shipping on it.
     */
    const val DATA_TYPE_DEV = 0xFFFF

    /** `DATA_TYPE_RESERVED`: the board refuses channel data under it (`MyMesh.cpp:1228`). */
    const val DATA_TYPE_RESERVED = 0x0000

    /** What Knit's room posts are tagged with ([DATA_TYPE_DEV] until registered): the one constant to move. */
    const val KNIT_DATA_TYPE = DATA_TYPE_DEV

    /** `AUTO_ADD_OVERWRITE_OLDEST` (`MyMesh.cpp:146`): a full table evicts the oldest non-favourite contact. */
    const val AUTO_ADD_OVERWRITE_OLDEST = 0x01

    /** `AUTO_ADD_CHAT` (`MyMesh.cpp:147`): adverts from companions are added under `manual_add_contacts = 1`. */
    const val AUTO_ADD_CHAT = 0x02

    /** `MAX_FRAME_SIZE` (`BaseSerialInterface.h:5`): the largest frame either way; the board caps the ATT MTU at it. */
    const val MAX_FRAME = 176

    /** `CHANNEL_DATA_RECV`'s header before the data (`MyMesh.cpp:609-617`). */
    private const val CHANNEL_DATA_HEADER = 9

    /** `CONTACT_MSG_RECV_V3`'s header before the text (`MyMesh.cpp:443-459`). */
    private const val CONTACT_MSG_V3_HEADER = 16

    /** What one ESP32 notify carries: the ATT MTU of [MAX_FRAME] less its 3-B header (bench Q8). */
    const val MAX_NOTIFY = MAX_FRAME - 3

    /** The largest channel-data payload a board's notify delivers whole: 173 B less the 9-B receive header (Q8). */
    const val CHANNEL_DATA_CAP = MAX_NOTIFY - CHANNEL_DATA_HEADER

    /** The longest contact text the firmware composes (`MAX_TEXT_LEN`, `BaseChatMesh.h:8`). */
    const val MAX_TEXT_LEN = 160

    /** The longest DM text one receiver's notify carries whole: 173 B less the 16-B V3 header (ADR 2026-11.gsqu §2). */
    const val DIRECT_TEXT_CAP = MAX_NOTIFY - CONTACT_MSG_V3_HEADER

    /** The largest sealed compact DM whose COBS form fits [DIRECT_TEXT_CAP]. */
    const val DIRECT_SEAL_CAP = DIRECT_TEXT_CAP - 1

    /** `PUB_KEY_SIZE`. */
    const val KEY_BYTES = 32

    /** A contact is addressed in a send and a receive frame by this much of its key. */
    const val PREFIX_BYTES = 6

    /** A channel name field (`MyMesh.cpp:1758`) and a contact name field (`MyMesh.cpp:181`). */
    const val NAME_FIELD = 32

    /** A channel secret: 128-bit only (`MyMesh.cpp:1762`). */
    const val SECRET_BYTES = 16

    /** `MAX_PATH_SIZE`. */
    const val PATH_FIELD = 64

    /** `CMD_SET_CHANNEL` that the firmware accepts: code, index, name, 16-B secret (`MyMesh.cpp:1769`). */
    const val SET_CHANNEL_SIZE = 2 + NAME_FIELD + SECRET_BYTES

    /** A contact record without `lastmod` or a position: what a teach writes (the board stamps `lastmod` itself). */
    const val CONTACT_BASIC_SIZE = 1 + KEY_BYTES + 3 + PATH_FIELD + NAME_FIELD + 4

    /** A contact record with the position and without `lastmod`: what a pin writes back. */
    const val CONTACT_PIN_SIZE = CONTACT_BASIC_SIZE + 8

    /** A contact record as the board writes it (`writeContactRespFrame`, `MyMesh.cpp:171`). */
    const val CONTACT_FULL_SIZE = CONTACT_PIN_SIZE + 4

    /** `STATS_TYPE_*` (`MyMesh.cpp:69-71`). */
    const val STATS_CORE = 0
    const val STATS_RADIO = 1
    const val STATS_PACKETS = 2

    /** The `since` that lists no contact: past every `lastmod`, so `GET_CONTACTS` reports only the count. */
    private const val SINCE_NONE = 0xFFFFFFFFL

    private const val CONTACT_MSG_V1_HEADER = 13
    private const val CHANNEL_MSG_V3_HEADER = 11
    private const val CHANNEL_MSG_V1_HEADER = 8
    private const val APP_START_RESERVED = 7
    private const val APP_NAME = "Knit"
    private const val SELF_INFO_FIXED = 58
    private const val DEVICE_INFO_MIN = 4
    private const val DEVICE_INFO_VERSION_AT = 60
    private const val DEVICE_INFO_VERSION_BYTES = 20
    private const val DEVICE_INFO_REPEAT_AT = 80
    private const val CHANNEL_INFO_SIZE = 2 + NAME_FIELD + SECRET_BYTES
    private const val SENT_SIZE = 10
    private const val CONFIRMED_SIZE = 9
    private const val KEY_PUSH_SIZE = 1 + KEY_BYTES
    private const val CURR_TIME_SIZE = 5
    private const val SIGN_START_SIZE = 6
    private const val BATT_SIZE = 11
    private const val AUTOADD_SIZE = 3
    private const val COUNT_SIZE = 5
    private const val STATS_CORE_SIZE = 11
    private const val STATS_RADIO_SIZE = 14
    private const val STATS_PACKETS_SIZE = 30
    private const val SCOPE_SET_SIZE = 1 + 31 + SECRET_BYTES
    private const val SNR_SCALE = 4f
    private const val BYTE_MASK = 0xFF
    private const val U32_MASK = 0xFFFFFFFFL
    private const val ACK_BYTES = 4
    private const val ATTEMPT_MASK = 3
    private const val BITS_PER_BYTE = 8

    // --- encoders ---

    /** `DEVICE_QUERY` asking for protocol [APP_TARGET_VERSION] (`MyMesh.cpp:1056`). */
    fun deviceQuery(): ByteArray = byteArrayOf(CMD_DEVICE_QUERY.toByte(), APP_TARGET_VERSION.toByte())

    /** `APP_START`: seven reserved bytes and the app's name, which the board only logs (`MyMesh.cpp:1076`). */
    fun appStart(): ByteArray = byteArrayOf(CMD_APP_START.toByte()) + ByteArray(APP_START_RESERVED) + APP_NAME.encodeToByteArray()

    fun getChannel(index: Int): ByteArray = byteArrayOf(CMD_GET_CHANNEL.toByte(), index.toByte())

    /**
     * `SET_CHANNEL` [index] to [name] and [secret], the form the firmware takes (`MyMesh.cpp:1769`, 128-bit only), or
     * null for a name past 31 bytes (it must keep its terminator), a zero inside it, or a secret not 16 bytes.
     */
    fun setChannel(
        index: Int,
        name: String,
        secret: ByteArray,
    ): ByteArray? {
        val n = name.encodeToByteArray()
        if (!fitsField(n, allowEmpty = true) || secret.size != SECRET_BYTES || index !in 0..BYTE_MASK) return null
        return byteArrayOf(CMD_SET_CHANNEL.toByte(), index.toByte()) + n.copyOf(NAME_FIELD) + secret
    }

    /** A slot emptied: no name, a zero secret, what an unused slot reads back as. */
    fun clearChannel(index: Int): ByteArray = requireNotNull(setChannel(index, "", ByteArray(SECRET_BYTES)))

    /**
     * `SEND_CHANNEL_DATA` on [slot]: flooded (`path_len 0xFF`), tagged [dataType] (`MyMesh.cpp:1198`). Null for a
     * payload past [CHANNEL_DATA_CAP] — the board takes 165 B and a 165-B one reaches the far phone short (Q8) — or the
     * reserved type.
     */
    fun sendChannelData(
        slot: Int,
        payload: ByteArray,
        dataType: Int = KNIT_DATA_TYPE,
    ): ByteArray? {
        if (payload.isEmpty() || payload.size > CHANNEL_DATA_CAP || dataType !in 1..U16_MAX) return null // 0 is DATA_TYPE_RESERVED
        return byteArrayOf(
            CMD_SEND_CHANNEL_DATA.toByte(),
            slot.toByte(),
            PATH_FLOOD.toByte(),
            (dataType and BYTE_MASK).toByte(),
            (dataType shr BITS_PER_BYTE).toByte(),
        ) + payload
    }

    /**
     * `SEND_CHANNEL_TXT_MSG` on [slot]: the firmware sends `<name>: <text>` and silently trims the whole to
     * [MAX_TEXT_LEN] (`BaseChatMesh.cpp:527`), answering OK — so a text past `160 − (name + 2)` bytes, with [advertName]
     * the board's own, is refused here, as is one with a zero byte (`strlen` would cut it) or none at all.
     */
    fun sendChannelText(
        slot: Int,
        timestamp: Long,
        text: ByteArray,
        advertName: ByteArray,
    ): ByteArray? {
        if (text.isEmpty() || text.any { it.toInt() == 0 } || text.size > channelTextCap(advertName)) return null
        return byteArrayOf(CMD_SEND_CHANNEL_TXT_MSG.toByte(), TXT_TYPE_PLAIN.toByte(), slot.toByte()) + u32(timestamp) + text
    }

    /** The longest text a channel post from a board named [advertName] carries untrimmed. */
    fun channelTextCap(advertName: ByteArray): Int = MAX_TEXT_LEN - (advertName.size + 2)

    /**
     * `SEND_TXT_MSG` of [text] to the contact whose key starts with [prefix]: `TXT_TYPE_PLAIN`, [attempt] 0..3, the
     * sender's [timestamp] (`MyMesh.cpp:1132`). Null for an empty text, one past [DIRECT_TEXT_CAP] or with a zero byte
     * — the board cuts there and still acks (Q11) — an attempt outside the two bits, or a prefix not 6 bytes.
     */
    fun sendText(
        prefix: ByteArray,
        attempt: Int,
        timestamp: Long,
        text: ByteArray,
    ): ByteArray? {
        if (text.isEmpty() || text.size > DIRECT_TEXT_CAP || text.any { it.toInt() == 0 }) return null
        if (attempt !in 0..ATTEMPT_MASK || prefix.size != PREFIX_BYTES) return null
        return byteArrayOf(CMD_SEND_TXT_MSG.toByte(), TXT_TYPE_PLAIN.toByte(), attempt.toByte()) + u32(timestamp) + prefix + text
    }

    fun syncNextMessage(): ByteArray = byteArrayOf(CMD_SYNC_NEXT_MESSAGE.toByte())

    fun getContactByKey(key: ByteArray): ByteArray? = keyed(CMD_GET_CONTACT_BY_KEY, key)

    fun removeContact(key: ByteArray): ByteArray? = keyed(CMD_REMOVE_CONTACT, key)

    fun resetPath(key: ByteArray): ByteArray? = keyed(CMD_RESET_PATH, key)

    /**
     * `ADD_UPDATE_CONTACT` of [contact] (`MyMesh.cpp:1328`, `updateContactFromFrame`). [withPosition] writes the record
     * back with the position it was read with (the pin's 144-B form); without it the 136-B form, which an existing
     * contact's position survives. Never `lastmod`: the board stamps its own clock.
     */
    fun addUpdateContact(
        contact: ContactRecord,
        withPosition: Boolean,
    ): ByteArray {
        val out = ByteArray(if (withPosition) CONTACT_PIN_SIZE else CONTACT_BASIC_SIZE)
        out[0] = CMD_ADD_UPDATE_CONTACT.toByte()
        contact.writeTo(out, withPosition)
        return out
    }

    /** `SIGN_START`: the board answers `SIGN_START` with its data limit and opens an empty buffer, shared by every client. */
    fun signStart(): ByteArray = byteArrayOf(CMD_SIGN_START.toByte())

    /** `SIGN_DATA` of [data], appended to the board's buffer; null for nothing, or more than one write carries. */
    fun signData(data: ByteArray): ByteArray? =
        if (data.isEmpty() || data.size > MAX_NOTIFY - 1) null else byteArrayOf(CMD_SIGN_DATA.toByte()) + data

    /** `SIGN_FINISH`: the board signs its buffer with its own key (`ed25519_sign`) and answers `SIGNATURE`. */
    fun signFinish(): ByteArray = byteArrayOf(CMD_SIGN_FINISH.toByte())

    /** `GET_CONTACTS` past every `lastmod`: answers `CONTACTS_START` with the board's count, then `END_OF_CONTACTS`. */
    fun countContacts(): ByteArray = byteArrayOf(CMD_GET_CONTACTS.toByte()) + u32(SINCE_NONE)

    /**
     * `GET_CONTACTS` from `since` 0: `CONTACTS_START` with the board's count, a `CONTACT` for every contact modified since the
     * epoch, then `END_OF_CONTACTS` (`MyMesh.cpp:1238-1257`) — the one listing a Ready's reconcile, a setup's erase and a
     * restore read (Stage 5b-2), never one per key.
     */
    fun listContacts(): ByteArray = byteArrayOf(CMD_GET_CONTACTS.toByte()) + u32(0)

    fun getStats(type: Int): ByteArray = byteArrayOf(CMD_GET_STATS.toByte(), type.toByte())

    fun getBattery(): ByteArray = byteArrayOf(CMD_GET_BATT_AND_STORAGE.toByte())

    /** `SET_DEVICE_TIME` to [epochSecs]: the board refuses a time behind its own with `ERR ILLEGAL_ARG` (`MyMesh.cpp:1287`). */
    fun setDeviceTime(epochSecs: Long): ByteArray = byteArrayOf(CMD_SET_DEVICE_TIME.toByte()) + u32(epochSecs)

    /** `GET_DEVICE_TIME`: answered by `CURR_TIME`, the one reply no other command draws — the barrier. */
    fun getDeviceTime(): ByteArray = byteArrayOf(CMD_GET_DEVICE_TIME.toByte())

    /** `SET_FLOOD_SCOPE_KEY [54, 0]`: clears any client's RAM scope override, so floods take the board's default. */
    fun clearScopeOverride(): ByteArray = byteArrayOf(CMD_SET_FLOOD_SCOPE_KEY.toByte(), 0)

    fun getDefaultScope(): ByteArray = byteArrayOf(CMD_GET_DEFAULT_FLOOD_SCOPE.toByte())

    /** `SET_OTHER_PARAMS` as exactly `[38, manualAdd]`: the length keeps every other field (`MyMesh.cpp:1488`). */
    fun setManualAdd(manualAdd: Int): ByteArray = byteArrayOf(CMD_SET_OTHER_PARAMS.toByte(), manualAdd.toByte())

    fun getAutoadd(): ByteArray = byteArrayOf(CMD_GET_AUTOADD_CONFIG.toByte())

    /** `SET_AUTOADD_CONFIG [58, config]`: two bytes, so `autoadd_max_hops` is kept (`MyMesh.cpp:2023`). */
    fun setAutoadd(config: Int): ByteArray = byteArrayOf(CMD_SET_AUTOADD_CONFIG.toByte(), config.toByte())

    /** `SET_ADVERT_NAME`, or null for an empty name, one past 31 bytes or with a zero (`MyMesh.cpp:1259`). */
    fun setAdvertName(name: String): ByteArray? = setAdvertName(name.encodeToByteArray())

    /**
     * `SET_ADVERT_NAME` of raw [name] bytes, as a restore writes back a name read off the board: never through a
     * `String`, which would mangle one the firmware cut mid-character.
     */
    fun setAdvertName(name: ByteArray): ByteArray? {
        if (!fitsField(name, allowEmpty = false)) return null
        return byteArrayOf(CMD_SET_ADVERT_NAME.toByte()) + name
    }

    /**
     * The code a receiving board acks a contact text with, which `SENT` echoes (`BaseChatMesh.cpp:462`,
     * `composeMsgPacket`): the first four bytes of `SHA-256(timestamp ‖ attempt & 3 ‖ text ‖ sender's public key)`,
     * read little-endian as the firmware's `memcpy` into a `uint32_t` leaves them.
     */
    fun expectedAck(
        timestamp: Long,
        attempt: Int,
        text: ByteArray,
        senderKey: ByteArray,
    ): Int {
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(u32(timestamp))
        sha.update((attempt and ATTEMPT_MASK).toByte())
        sha.update(text)
        sha.update(senderKey)
        return leInt(sha.digest(), 0)
    }

    // --- decoding ---

    /** One frame off the board, never throwing (see the object's KDoc). */
    @Suppress("CyclomaticComplexMethod") // one arm per frame layout the firmware writes
    fun decode(frame: ByteArray): McFrame {
        if (frame.isEmpty()) return McFrame.Rejected(null, McReject.EMPTY)
        if (frame.size > MAX_FRAME) return McFrame.Rejected(frame[0].toInt() and BYTE_MASK, McReject.OVERLONG)
        val code = frame[0].toInt() and BYTE_MASK
        return runCatching {
            when (code) {
                RESP_OK -> exact(frame, 1) { McFrame.Ok }
                RESP_ERR -> exact(frame, 2) { McFrame.Err(u8(frame, 1)) }
                RESP_CONTACTS_START -> exact(frame, COUNT_SIZE) { McFrame.ContactsStart(u32(frame, 1)) }
                RESP_CONTACT -> exact(frame, CONTACT_FULL_SIZE) { McFrame.Contact(ContactRecord.read(frame, 1)) }
                RESP_END_OF_CONTACTS -> exact(frame, COUNT_SIZE) { McFrame.EndOfContacts }
                RESP_SELF_INFO -> selfInfo(frame)
                RESP_SENT -> exact(frame, SENT_SIZE) { McFrame.Sent(u8(frame, 1) != 0, leInt(frame, 2), u32(frame, 6)) }
                RESP_CONTACT_MSG_RECV, RESP_CONTACT_MSG_RECV_V3 -> contactMessage(frame, code == RESP_CONTACT_MSG_RECV_V3)
                RESP_CHANNEL_MSG_RECV, RESP_CHANNEL_MSG_RECV_V3 -> channelMessage(frame, code == RESP_CHANNEL_MSG_RECV_V3)
                RESP_CURR_TIME -> exact(frame, CURR_TIME_SIZE) { McFrame.CurrTime(u32(frame, 1)) }
                RESP_SIGN_START -> exact(frame, SIGN_START_SIZE) { McFrame.SignStart(u32(frame, 2)) }
                RESP_SIGNATURE -> exact(frame, 1 + SIGNATURE_BYTES) { McFrame.Signature(frame.copyOfRange(1, 1 + SIGNATURE_BYTES)) }
                RESP_NO_MORE_MESSAGES -> exact(frame, 1) { McFrame.NoMore }
                RESP_BATT_AND_STORAGE -> exact(frame, BATT_SIZE) { McFrame.Battery(u16(frame, 1)) }
                RESP_DEVICE_INFO -> deviceInfo(frame)
                RESP_DISABLED -> exact(frame, 1) { McFrame.Disabled }
                RESP_CHANNEL_INFO -> exact(frame, CHANNEL_INFO_SIZE) { channelInfo(frame) }
                RESP_STATS -> stats(frame)
                RESP_AUTOADD_CONFIG -> exact(frame, AUTOADD_SIZE) { McFrame.Autoadd(u8(frame, 1), u8(frame, 2)) }
                RESP_CHANNEL_DATA_RECV -> channelData(frame)
                RESP_DEFAULT_FLOOD_SCOPE -> defaultScope(frame)
                else -> if (code >= PUSH_FLOOR) push(frame, code) else McFrame.Unknown(code)
            }
        }.getOrElse { McFrame.Rejected(code, McReject.SHORT) }
    }

    private fun push(
        frame: ByteArray,
        code: Int,
    ): McFrame =
        when (code) {
            PUSH_ADVERT -> exact(frame, KEY_PUSH_SIZE) { McFrame.Advert(isNew = false) }
            PUSH_NEW_ADVERT -> exact(frame, CONTACT_FULL_SIZE) { McFrame.Advert(isNew = true) }
            PUSH_PATH_UPDATED -> exact(frame, KEY_PUSH_SIZE) { McFrame.PathUpdated(frame.copyOfRange(1, KEY_PUSH_SIZE)) }
            PUSH_CONTACT_DELETED -> exact(frame, KEY_PUSH_SIZE) { McFrame.ContactDeleted(frame.copyOfRange(1, KEY_PUSH_SIZE)) }
            PUSH_SEND_CONFIRMED -> exact(frame, CONFIRMED_SIZE) { McFrame.SendConfirmed(leInt(frame, 1), u32(frame, 5)) }
            PUSH_MSG_WAITING -> exact(frame, 1) { McFrame.MsgWaiting }
            PUSH_CONTACTS_FULL -> exact(frame, 1) { McFrame.ContactsFull }
            else -> McFrame.Push(code)
        }

    /** `SELF_INFO` (`MyMesh.cpp:1084-1115`): the fixed 58 bytes, then the advert name to the end. */
    private fun selfInfo(frame: ByteArray): McFrame {
        if (frame.size < SELF_INFO_FIXED) return McFrame.Rejected(RESP_SELF_INFO, McReject.SHORT)
        var i = 4
        val key = frame.copyOfRange(i, i + KEY_BYTES)
        i += KEY_BYTES + 8 // the key, then the position: skipped, never kept
        val multiAcks = u8(frame, i++)
        i++ // advert_loc_policy
        i++ // telemetry modes
        val manualAdd = u8(frame, i++)
        val freqKhz = u32(frame, i)
        val bwHz = u32(frame, i + 4)
        val sf = u8(frame, i + 8)
        val cr = u8(frame, i + 9)
        val name = frame.copyOfRange(SELF_INFO_FIXED, frame.size)
        return McFrame.SelfInfo(key, multiAcks, manualAdd, freqKhz, bwHz, sf, cr, name)
    }

    /**
     * `DEVICE_INFO` (`MyMesh.cpp:1058-1074`): the version code, then (v3+) `MAX_CONTACTS / 2` and the channel count; the
     * BLE PIN beside them is never read. The version string and the client-repeat flag when the frame reaches them.
     */
    private fun deviceInfo(frame: ByteArray): McFrame {
        if (frame.size < DEVICE_INFO_MIN) return McFrame.Rejected(RESP_DEVICE_INFO, McReject.SHORT)
        val version =
            if (frame.size >= DEVICE_INFO_VERSION_AT + DEVICE_INFO_VERSION_BYTES) {
                cString(frame, DEVICE_INFO_VERSION_AT, DEVICE_INFO_VERSION_BYTES)
            } else {
                null
            }
        val repeat = if (frame.size > DEVICE_INFO_REPEAT_AT) u8(frame, DEVICE_INFO_REPEAT_AT) != 0 else null
        return McFrame.DeviceInfo(u8(frame, 1), u8(frame, 2), u8(frame, 3), version, repeat)
    }

    private fun channelInfo(frame: ByteArray): McFrame =
        McFrame.ChannelInfo(
            index = u8(frame, 1),
            name = cBytes(frame, 2, NAME_FIELD),
            secret = frame.copyOfRange(2 + NAME_FIELD, 2 + NAME_FIELD + SECRET_BYTES),
        )

    private fun stats(frame: ByteArray): McFrame {
        if (frame.size < 2) return McFrame.Rejected(RESP_STATS, McReject.SHORT)
        return when (u8(frame, 1)) {
            STATS_CORE -> {
                exact(frame, STATS_CORE_SIZE) { McFrame.StatsCore(u16(frame, 2), u32(frame, 4), u16(frame, 8), u8(frame, 10)) }
            }

            STATS_RADIO -> {
                exact(frame, STATS_RADIO_SIZE) {
                    McFrame.StatsRadio(frame[4].toInt(), frame[5] / SNR_SCALE, u32(frame, 6), u32(frame, 10))
                }
            }

            STATS_PACKETS -> {
                exact(frame, STATS_PACKETS_SIZE) {
                    McFrame.StatsPackets(
                        recv = u32(frame, 2),
                        sent = u32(frame, 6),
                        sentFlood = u32(frame, 10),
                        sentDirect = u32(frame, 14),
                    )
                }
            }

            else -> {
                McFrame.Rejected(RESP_STATS, McReject.UNKNOWN_KIND)
            }
        }
    }

    /**
     * `CONTACT_MSG_RECV(_V3)` (`queueMessage`, `MyMesh.cpp:440`): the sender's 6-byte prefix, `path_len` (`0xFF` is
     * direct), the text type, the sender's timestamp, four bytes of signer prefix for a signed text, then the text.
     */
    private fun contactMessage(
        frame: ByteArray,
        v3: Boolean,
    ): McFrame {
        val header = if (v3) CONTACT_MSG_V3_HEADER else CONTACT_MSG_V1_HEADER
        if (frame.size < header) return McFrame.Rejected(frame[0].toInt() and BYTE_MASK, McReject.SHORT)
        var i = if (v3) 4 else 1
        val snr = if (v3) frame[1] / SNR_SCALE else null
        val prefix = frame.copyOfRange(i, i + PREFIX_BYTES)
        i += PREFIX_BYTES
        val pathLen = u8(frame, i++)
        val txtType = u8(frame, i++)
        val timestamp = u32(frame, i)
        i += 4
        if (txtType == TXT_TYPE_SIGNED) i += SIGNER_PREFIX
        if (i > frame.size) return McFrame.Rejected(frame[0].toInt() and BYTE_MASK, McReject.SHORT)
        return McFrame.ContactMessage(v3, snr, prefix, pathLen, txtType, timestamp, frame.copyOfRange(i, frame.size))
    }

    /** `CHANNEL_MSG_RECV(_V3)` (`onChannelMessageRecv`, `MyMesh.cpp:558`): the slot, `path_len`, the type, the time, the text. */
    private fun channelMessage(
        frame: ByteArray,
        v3: Boolean,
    ): McFrame {
        val header = if (v3) CHANNEL_MSG_V3_HEADER else CHANNEL_MSG_V1_HEADER
        if (frame.size < header) return McFrame.Rejected(frame[0].toInt() and BYTE_MASK, McReject.SHORT)
        val i = if (v3) 4 else 1
        return McFrame.ChannelMessage(
            v3 = v3,
            snr = if (v3) frame[1] / SNR_SCALE else null,
            slot = u8(frame, i),
            pathLen = u8(frame, i + 1),
            txtType = u8(frame, i + 2),
            timestamp = u32(frame, i + 3),
            text = frame.copyOfRange(header, frame.size),
        )
    }

    /**
     * `CHANNEL_DATA_RECV` (`MyMesh.cpp:601-620`): no sender, RSSI or time; the length byte must say exactly what arrived,
     * so a notify the ESP32 cut short (Q8) is rejected rather than handed on a byte short.
     */
    private fun channelData(frame: ByteArray): McFrame {
        if (frame.size < CHANNEL_DATA_HEADER) return McFrame.Rejected(RESP_CHANNEL_DATA_RECV, McReject.SHORT)
        val len = u8(frame, CHANNEL_DATA_HEADER - 1)
        val got = frame.size - CHANNEL_DATA_HEADER
        if (len > got) return McFrame.Rejected(RESP_CHANNEL_DATA_RECV, McReject.TRUNCATED)
        if (len < got) return McFrame.Rejected(RESP_CHANNEL_DATA_RECV, McReject.OVERLONG)
        return McFrame.ChannelData(
            snr = frame[1] / SNR_SCALE,
            slot = u8(frame, 4),
            pathLen = u8(frame, 5),
            dataType = u16(frame, 6),
            data = frame.copyOfRange(CHANNEL_DATA_HEADER, frame.size),
        )
    }

    /** `DEFAULT_FLOOD_SCOPE`: one byte when none is set, else the name and key — of which only "set" is kept. */
    private fun defaultScope(frame: ByteArray): McFrame =
        when (frame.size) {
            1 -> McFrame.DefaultScope(set = false)
            SCOPE_SET_SIZE -> McFrame.DefaultScope(set = true)
            else -> McFrame.Rejected(RESP_DEFAULT_FLOOD_SCOPE, if (frame.size < SCOPE_SET_SIZE) McReject.SHORT else McReject.OVERLONG)
        }

    private inline fun exact(
        frame: ByteArray,
        size: Int,
        read: () -> McFrame,
    ): McFrame =
        when {
            frame.size < size -> McFrame.Rejected(frame[0].toInt() and BYTE_MASK, McReject.SHORT)
            frame.size > size -> McFrame.Rejected(frame[0].toInt() and BYTE_MASK, McReject.OVERLONG)
            else -> read()
        }

    /** A name that fits a 32-B NUL-terminated field: under 32 bytes, no zero inside. */
    private fun fitsField(
        n: ByteArray,
        allowEmpty: Boolean,
    ): Boolean = (allowEmpty || n.isNotEmpty()) && n.size < NAME_FIELD && n.none { it.toInt() == 0 }

    private fun keyed(
        cmd: Int,
        key: ByteArray,
    ): ByteArray? = if (key.size == KEY_BYTES) byteArrayOf(cmd.toByte()) + key else null

    internal fun u8(
        b: ByteArray,
        at: Int,
    ): Int = b[at].toInt() and BYTE_MASK

    internal fun u16(
        b: ByteArray,
        at: Int,
    ): Int = u8(b, at) or (u8(b, at + 1) shl BITS_PER_BYTE)

    internal fun leInt(
        b: ByteArray,
        at: Int,
    ): Int {
        var v = 0
        for (k in 0 until ACK_BYTES) v = v or (u8(b, at + k) shl (BITS_PER_BYTE * k))
        return v
    }

    internal fun u32(
        b: ByteArray,
        at: Int,
    ): Long = leInt(b, at).toLong() and U32_MASK

    internal fun u32(v: Long): ByteArray = ByteArray(ACK_BYTES) { k -> (v shr (BITS_PER_BYTE * k)).toByte() }

    internal fun i32(v: Int): ByteArray = u32(v.toLong())

    /** The bytes of a NUL-padded field, up to its first zero. */
    internal fun cBytes(
        b: ByteArray,
        at: Int,
        len: Int,
    ): ByteArray {
        val field = b.copyOfRange(at, at + len)
        val end = field.indexOf(0).let { if (it < 0) len else it }
        return field.copyOf(end)
    }

    private fun cString(
        b: ByteArray,
        at: Int,
        len: Int,
    ): String = cBytes(b, at, len).decodeToString()

    private const val U16_MAX = 0xFFFF

    /** `TXT_TYPE_SIGNED_PLAIN`: its receive frame carries the signer's 4-byte prefix before the text (`MyMesh.cpp:555`). */
    private const val TXT_TYPE_SIGNED = 2
    private const val SIGNER_PREFIX = 4
}

/** Why [MeshcoreProto.decode] refused a frame. */
internal enum class McReject {
    EMPTY,

    /** Fewer bytes than the layout needs. */
    SHORT,

    /** More bytes than the layout has, or past [MeshcoreProto.MAX_FRAME]. */
    OVERLONG,

    /** A length byte claiming more than arrived: an ESP32 notify cut at 173 B (bench Q8). */
    TRUNCATED,

    /** A stats frame of a kind this build does not know. */
    UNKNOWN_KIND,
}

/**
 * One frame off a MeshCore board, decoded. Value types where the session compares them; byte arrays are copies. No
 * `toString` prints a key, a name, a secret or a text: a frame is logged by its type alone.
 */
internal sealed interface McFrame {
    /** Answers that carry nothing to match them by: a bare `OK` / `ERR` may be anybody's (the board answers every interface). */
    data object Ok : McFrame

    data class Err(
        val code: Int,
    ) : McFrame

    data class ContactsStart(
        val count: Long,
    ) : McFrame

    data object EndOfContacts : McFrame

    class Contact(
        val record: ContactRecord,
    ) : McFrame {
        override fun toString(): String = "Contact"
    }

    /**
     * `SELF_INFO`: the board's own [key] (32 B), `multi_acks`, `manual_add_contacts`, its radio as programmed —
     * [freqKhz] (`freq × 1000`), [bwHz] (`bw × 1000`), [sf], [cr] — and its advert [name]. The position is never read.
     */
    class SelfInfo(
        val key: ByteArray,
        val multiAcks: Int,
        val manualAdd: Int,
        val freqKhz: Long,
        val bwHz: Long,
        val sf: Int,
        val cr: Int,
        val name: ByteArray,
    ) : McFrame {
        override fun toString(): String = "SelfInfo"
    }

    /** `RESP_SENT`: [flood] when the board had no path and flooded it; the [expectedAck] it waits on; its own [estTimeoutMs]. */
    data class Sent(
        val flood: Boolean,
        val expectedAck: Int,
        val estTimeoutMs: Long,
    ) : McFrame

    /** A drained contact text: data, dispatched whoever asked for it (binding rule "client" 1). */
    class ContactMessage(
        val v3: Boolean,
        val snr: Float?,
        val prefix: ByteArray,
        val pathLen: Int,
        val txtType: Int,
        val timestamp: Long,
        val text: ByteArray,
    ) : McFrame {
        override fun toString(): String = "ContactMessage(v3=$v3, pathLen=$pathLen, txtType=$txtType, ${text.size} B)"
    }

    /** A drained channel text (`<name>: <text>`): data. */
    class ChannelMessage(
        val v3: Boolean,
        val snr: Float?,
        val slot: Int,
        val pathLen: Int,
        val txtType: Int,
        val timestamp: Long,
        val text: ByteArray,
    ) : McFrame {
        override fun toString(): String = "ChannelMessage(v3=$v3, slot=$slot, pathLen=$pathLen, ${text.size} B)"
    }

    data class CurrTime(
        val epochSecs: Long,
    ) : McFrame

    /** `SIGN_START`: the board's buffer is open, [maxLen] bytes at most. */
    data class SignStart(
        val maxLen: Long,
    ) : McFrame

    /** `SIGNATURE`: the board's Ed25519 signature over its buffer. */
    class Signature(
        signature: ByteArray,
    ) : McFrame {
        private val bytes = signature.copyOf()

        /** The 64 signature bytes. */
        val value: ByteArray get() = bytes.copyOf()

        override fun toString(): String = "Signature"
    }

    data object NoMore : McFrame

    data class Battery(
        val millivolts: Int,
    ) : McFrame

    /** `DEVICE_INFO`: [versionCode] (`FIRMWARE_VER_CODE`), `MAX_CONTACTS / 2`, `MAX_GROUP_CHANNELS`, the version string. */
    data class DeviceInfo(
        val versionCode: Int,
        val maxContactsHalf: Int,
        val maxChannels: Int,
        val version: String?,
        val clientRepeat: Boolean?,
    ) : McFrame

    data object Disabled : McFrame

    /** One channel slot: its [index], its [name] bytes (up to the terminator) and its 16-B [secret]. */
    class ChannelInfo(
        val index: Int,
        val name: ByteArray,
        val secret: ByteArray,
    ) : McFrame {
        override fun toString(): String = "ChannelInfo($index)"
    }

    data class StatsCore(
        val batteryMv: Int,
        val uptimeSecs: Long,
        val errFlags: Int,
        val outboundQueue: Int,
    ) : McFrame

    data class StatsRadio(
        val lastRssi: Int,
        val lastSnr: Float,
        val txAirSecs: Long,
        val rxAirSecs: Long,
    ) : McFrame

    data class StatsPackets(
        val recv: Long,
        val sent: Long,
        val sentFlood: Long,
        val sentDirect: Long,
    ) : McFrame

    data class Autoadd(
        val config: Int,
        val maxHops: Int,
    ) : McFrame

    /** A drained channel datagram: data. No sender, RSSI or receive time (`MyMesh.cpp:610-620`). */
    class ChannelData(
        val snr: Float,
        val slot: Int,
        val pathLen: Int,
        val dataType: Int,
        val data: ByteArray,
    ) : McFrame {
        override fun toString(): String = "ChannelData(slot=$slot, pathLen=$pathLen, type=$dataType, ${data.size} B)"
    }

    /** Whether the board has a default flood scope; its name and key are never kept. */
    data class DefaultScope(
        val set: Boolean,
    ) : McFrame

    /** `ADVERT` / `NEW_ADVERT`: counted and dropped, nothing kept per advertised key (binding rule "client" 4). */
    data class Advert(
        val isNew: Boolean,
    ) : McFrame

    class PathUpdated(
        val key: ByteArray,
    ) : McFrame {
        override fun toString(): String = "PathUpdated"
    }

    class ContactDeleted(
        val key: ByteArray,
    ) : McFrame {
        override fun toString(): String = "ContactDeleted"
    }

    data class SendConfirmed(
        val ack: Int,
        val tripMs: Long,
    ) : McFrame

    data object MsgWaiting : McFrame

    data object ContactsFull : McFrame

    /** A push this build reads no further (`LOG_RX_DATA` and the rest): counted. */
    data class Push(
        val code: Int,
    ) : McFrame

    /** An answer code this build does not know. */
    data class Unknown(
        val code: Int,
    ) : McFrame

    data class Rejected(
        val code: Int?,
        val why: McReject,
    ) : McFrame

    /** Whether this is drained data (binding rule "client" 1: never matched to a request, always dispatched). */
    val isData: Boolean get() = this is ContactMessage || this is ChannelMessage || this is ChannelData
}

/**
 * One contact as the board keeps it (`ContactInfo`): what a pin reads and writes back and a teach writes. Held only
 * between a read and its write: the [nameField] may be a person's name and the position theirs, so `toString` prints
 * neither, nor the key.
 */
internal class ContactRecord(
    val key: ByteArray,
    val type: Int,
    val flags: Int,
    val outPathLen: Int,
    private val outPath: ByteArray,
    private val nameField: ByteArray,
    private val lastAdvert: Long,
    private val position: ByteArray,
) {
    init {
        require(key.size == MeshcoreProto.KEY_BYTES && outPath.size == MeshcoreProto.PATH_FIELD)
        require(nameField.size == MeshcoreProto.NAME_FIELD && position.size == POSITION_BYTES)
    }

    /** This record with its route pinned zero-hop: `out_path_len 0`, the path cleared (ADR 2026-11.gsqu §3). */
    fun pinned(): ContactRecord = ContactRecord(key, type, flags, 0, ByteArray(MeshcoreProto.PATH_FIELD), nameField, lastAdvert, position)

    /** Whether the board's name for this contact is [name] exactly. */
    fun named(name: String): Boolean = MeshcoreProto.cBytes(nameField, 0, MeshcoreProto.NAME_FIELD).contentEquals(name.encodeToByteArray())

    internal fun writeTo(
        out: ByteArray,
        withPosition: Boolean,
    ) {
        var i = 1
        key.copyInto(out, i)
        i += MeshcoreProto.KEY_BYTES
        out[i++] = type.toByte()
        out[i++] = flags.toByte()
        out[i++] = outPathLen.toByte()
        outPath.copyInto(out, i)
        i += MeshcoreProto.PATH_FIELD
        nameField.copyInto(out, i)
        i += MeshcoreProto.NAME_FIELD
        MeshcoreProto.u32(lastAdvert).copyInto(out, i)
        i += U32_BYTES
        if (withPosition) position.copyInto(out, i)
    }

    override fun toString(): String = "ContactRecord(outPathLen=$outPathLen)"

    companion object {
        private const val POSITION_BYTES = 8
        private const val U32_BYTES = 4

        /**
         * A contact Knit teaches (ADR 2026-11.gsqu §3): a companion, favourite and nothing else (no permissions), routed
         * zero-hop, named [name] — `Knit xxxx` from the key, never a display name.
         */
        fun knit(
            key: ByteArray,
            name: String,
        ): ContactRecord =
            ContactRecord(
                key = key.copyOf(),
                type = MeshcoreProto.ADV_TYPE_CHAT,
                flags = MeshcoreProto.CONTACT_FLAG_FAVOURITE,
                outPathLen = 0,
                outPath = ByteArray(MeshcoreProto.PATH_FIELD),
                nameField = name.encodeToByteArray().copyOf(MeshcoreProto.NAME_FIELD),
                lastAdvert = 0L,
                position = ByteArray(POSITION_BYTES),
            )

        /** The record in a `CONTACT` frame from [at] (`writeContactRespFrame`); `lastmod` is not kept. */
        fun read(
            frame: ByteArray,
            at: Int,
        ): ContactRecord {
            var i = at
            val key = frame.copyOfRange(i, i + MeshcoreProto.KEY_BYTES)
            i += MeshcoreProto.KEY_BYTES
            val type = MeshcoreProto.u8(frame, i++)
            val flags = MeshcoreProto.u8(frame, i++)
            val outPathLen = MeshcoreProto.u8(frame, i++)
            val outPath = frame.copyOfRange(i, i + MeshcoreProto.PATH_FIELD)
            i += MeshcoreProto.PATH_FIELD
            val name = frame.copyOfRange(i, i + MeshcoreProto.NAME_FIELD)
            i += MeshcoreProto.NAME_FIELD
            val lastAdvert = MeshcoreProto.u32(frame, i)
            i += U32_BYTES
            val position = frame.copyOfRange(i, i + POSITION_BYTES)
            return ContactRecord(key, type, flags, outPathLen, outPath, name, lastAdvert, position)
        }
    }
}
