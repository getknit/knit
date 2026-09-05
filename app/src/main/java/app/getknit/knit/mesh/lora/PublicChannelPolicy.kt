package app.getknit.knit.mesh.lora

import app.getknit.knit.TextLimits
import app.getknit.knit.mesh.MeshPost

/**
 * Decides which packets off the board are a **post on its primary (slot 0) channel** — the Meshtastic room's
 * inbound half. Pure, so the whole rule is JVM-testable against fabricated packets
 * ([app.getknit.knit.mesh.lora.PublicChannelPolicyTest]); the sibling of [LoraFramePolicy], which does the
 * same job for Knit's own frames.
 *
 * Receive-only: nothing here transmits, and hearing a post costs no airtime at all, which is why the plane's
 * budget ([LoraAirtime]) is not consulted anywhere in this file.
 *
 * **Slot 0 is mirrored as the user configured it** — whatever preset it runs, whatever it is called, whatever
 * key it carries. The room is this phone's own window onto its own radio, and nothing heard here ever leaves
 * the phone, so a renamed or re-keyed primary is not somebody else's private channel being read into a public
 * room: it is the user's own channel, on the user's own board, shown to the user. (The first design read only
 * the stock public primary, because it flooded what it heard to a whole pocket; that gate went with the
 * flood.) What the filters are about instead is *what is a post*:
 *
 * - **Index 0 only.** The secondary slots hold the user's other channels, Knit's included — and a board
 *   whose slot 0 *is* the Knit channel (the lab shape, [isKnitPrimary]) has no primary to mirror at all.
 * - **Broadcast only.** A unicast text addressed to the board is not the channel's conversation, and
 *   ADR 2026-09.emd7 marks the board unmonitored precisely to say that Knit does not read it.
 * - **`TEXT_MESSAGE_APP` only.** Position, telemetry and routing say where somebody is and how their hardware
 *   is doing; none of that belongs in a chat room. `NODEINFO_APP` feeds the name directory instead, one layer
 *   down in [MeshtasticSession].
 *
 * `via_mqtt` is deliberately **not** a filter. A post injected from somebody's internet uplink is carried and
 * flagged, so the room can say where a post came from rather than hide it.
 */
internal object PublicChannelPolicy {
    /** The primary channel's index. Knit's setup (ADR 045) always writes a secondary and never touches this one. */
    const val PRIMARY_INDEX = 0

    /** Why a channel-0 packet was not delivered, for the counters `…debug.LORA` reports. */
    enum class Refusal {
        /** Slot 0 on this board is the Knit channel itself (the lab shape), so there is no primary to mirror. */
        KNIT_ON_PRIMARY,

        /** Addressed to one node rather than the whole channel. */
        NOT_BROADCAST,

        /** A portnum that is not chat: position, telemetry, routing, an app Knit knows nothing about. */
        NOT_TEXT,

        /** Empty after decoding and trimming — nothing to show. */
        EMPTY_BODY,

        /** No packet id, so no deterministic row id and no way to collapse the board's replay onto one row. */
        NO_PACKET_ID,

        /**
         * Our own board sent it — the echo of a post this phone put on the channel itself, which is already in
         * the room as our own row.
         *
         * Deliberately narrow: **only** our own board, never every radio that also speaks Knit. A contact's
         * board hearing our post off the air is exactly how it reaches them; their board hearing *theirs*
         * is how their post reaches us.
         */
        OWN_BOARD,
    }

    /** Either the post to deliver, or why it was refused. */
    sealed interface Verdict {
        data class Post(
            val post: MeshPost,
        ) : Verdict

        data class Refused(
            val reason: Refusal,
        ) : Verdict
    }

    /**
     * Judges one channel-0 [packet] against the board's [channels] table and [radio] settings. Callers filter
     * to `channelIndex == PRIMARY_INDEX` first — an off-primary packet is Knit's own traffic or another of the
     * user's channels, and neither is this policy's business.
     *
     * [name] is what the board's NodeDB calls the speaker, looked up by the caller so this stays pure.
     * [ownNode] is our own board's node number, so its own transmissions are not read back in as somebody
     * else's post ([Refusal.OWN_BOARD]).
     */
    fun judge(
        packet: ReceivedPacket,
        channels: List<ChannelInfo>,
        radio: LoraRadioConfig?,
        name: String? = null,
        ownNode: UInt? = null,
    ): Verdict {
        if (isKnitPrimary(channels)) return Verdict.Refused(Refusal.KNIT_ON_PRIMARY)
        if (ownNode != null && packet.from == ownNode) return Verdict.Refused(Refusal.OWN_BOARD)
        if (packet.to != MeshtasticProto.BROADCAST) return Verdict.Refused(Refusal.NOT_BROADCAST)
        if (packet.portnum != MeshtasticProto.PORT_TEXT_MESSAGE) return Verdict.Refused(Refusal.NOT_TEXT)
        if (packet.id == 0u) return Verdict.Refused(Refusal.NO_PACKET_ID)
        // Meshtastic text is UTF-8 by convention and unvalidated in fact, so decode leniently (a malformed
        // byte becomes U+FFFD) and clamp exactly like an inbound Knit chat body does — what a stranger puts on
        // an open channel bounds nothing by itself.
        val body =
            packet.payload
                .decodeToString()
                .trim()
                .take(TextLimits.MESSAGE)
        if (body.isEmpty()) return Verdict.Refused(Refusal.EMPTY_BODY)
        return Verdict.Post(
            MeshPost(
                node = packet.from.toLong(),
                packetId = packet.id.toLong(),
                body = body,
                name = name?.takeIf { it.isNotBlank() }?.take(TextLimits.DISPLAY_NAME),
                channel = primaryName(channels, radio),
                hops = packet.hopsAway,
                snrDeci = packet.rxSnr?.let { Math.round(it * DECI) },
                viaMqtt = packet.viaMqtt,
            ),
        )
    }

    /**
     * Whether slot 0 on this board is the **Knit channel itself**. ADR 045 never writes Knit there, but the
     * debug bridge can bind index 0 by hand and set it up; on such a board slot 0 carries Knit's own frames,
     * and there is no primary to mirror or to post on.
     *
     * Decided off the channel **table**, never off the bound index — the same reading as
     * `LoraMeshTransport.boundSlotIsKnit`. `SettingsStore.loraChannelIndex` defaults to 0, so a board that was
     * paired but never ran Knit's setup would otherwise read as "Knit at slot 0" and mirror nothing, when
     * slot 0 on it is exactly the primary the room is for. A board reporting no table cannot have Knit there.
     */
    fun isKnitPrimary(channels: List<ChannelInfo>): Boolean = channels.any { it.index == PRIMARY_INDEX && it.name == KnitChannel.NAME }

    /**
     * Whether slot 0 is keyed with something **everybody already has** — which is what decides whether the
     * room calls itself unencrypted or merely not end-to-end encrypted.
     *
     * Meshtastic encrypts every channel, so "unencrypted" is never literally true; what varies is who holds
     * the key. `ChannelSettings.psk` says which case this is, and the firmware's own encoding is what makes
     * the question answerable at all: **absent** means the default key (`Channels::initDefaultChannel` writes
     * the single byte 1), a **single byte** is that same well-known key with its last byte offset by the
     * value — 0 meaning no encryption at all — and only a **16- or 32-byte** psk is a key somebody chose.
     * The one-byte family is published in the firmware source, so a channel carrying it is readable by every
     * radio and every MQTT gateway on the band: unencrypted in every sense a user cares about, and the case
     * ADR 045 deliberately leaves a Knit board in.
     *
     * A board with no slot 0 in its table answers **true**. The unknown has to fall this way: telling
     * somebody their posts are open when they are shared-key costs them nothing, and the reverse mistake is
     * the one that gets words read by strangers.
     */
    fun primaryKeyIsPublic(channels: List<ChannelInfo>): Boolean {
        val primary = channels.firstOrNull { it.index == PRIMARY_INDEX } ?: return true
        return primary.psk.size <= 1
    }

    /**
     * Whether the primary still carries the name the firmware would give it — empty (which `Channels::getName`
     * substitutes the preset's display name for), or that name spelled out.
     *
     * Not a rule the room applies any more; it stays because the LoRa screen asks it for a different reason:
     * `LoraRadioUiState.customPrimary` warns about a **renamed** primary because renaming moves the radio to
     * another RF slot where no other Knit board is listening (ADR 045). The room mirrors a renamed primary
     * happily — the warning is about Knit's own hop, not about the room.
     *
     * Null [radio] is "we cannot tell" and answers **false**; the screen inverts this for its warning, so it
     * must handle the unknown case itself rather than let a board that has not reported its preset read as
     * renamed.
     */
    fun hasStockName(
        primary: ChannelInfo,
        radio: LoraRadioConfig?,
    ): Boolean {
        // The **preset's** own default name, never [primaryName] — that one answers "what is this channel
        // called" and folds the board's own name in, so comparing against it would be trivially true.
        val stockName = radio?.modemPreset?.defaultChannelName ?: return false
        return primary.name.isEmpty() || primary.name == stockName
    }

    /**
     * What the primary channel is called: its own name, or — when it is unnamed, the stock case — the display
     * name the firmware substitutes, which is the modem preset's (`LongFast`, `LongTurbo`, `MediumFast`, …).
     * An explicit name is known before the radio settings arrive, so it wins on its own; null only when the
     * primary is unnamed *and* the board has not reported its preset.
     */
    fun primaryName(
        channels: List<ChannelInfo>,
        radio: LoraRadioConfig?,
    ): String? {
        val named = channels.firstOrNull { it.index == PRIMARY_INDEX }?.name.orEmpty()
        return named.ifEmpty { radio?.modemPreset?.defaultChannelName }
    }

    /** SNR is carried as tenths of a dB so the row stores an integer; a float would round differently per build. */
    private const val DECI = 10f
}
