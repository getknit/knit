package app.getknit.knit.mesh.lora

import app.getknit.knit.TextLimits
import app.getknit.knit.mesh.MeshPost

/**
 * Decides which packets off the board are a **public post on the foreign mesh's primary channel** — the
 * LongFast bridge's inbound half. Pure, so the whole rule is JVM-testable against fabricated packets
 * ([app.getknit.knit.mesh.lora.LongFastPolicyTest]); the sibling of [LoraFramePolicy], which does the same job
 * for Knit's own frames.
 *
 * Receive-only: nothing here transmits, and hearing a post costs no airtime at all, which is why the plane's
 * budget ([LoraAirtime]) is not consulted anywhere in this file.
 *
 * The filters are all about *what is public*. Meshtastic's primary channel on its default key is a cleartext
 * broadcast to whoever is in range — the same kind of thing as Knit's Nearby room, and the only traffic on the
 * band that anybody has consented to be overheard on. Everything narrower than that is somebody's private
 * business and never reaches Knit:
 *
 * - **Index 0 only.** The secondary slots hold the user's own channels, Knit's included.
 * - **The stock primary only** ([isStockPrimary]). A board whose primary was renamed, or re-keyed, is a
 *   private group that happens to sit at index 0 — and a renamed primary is *already* a warning the LoRa
 *   screen shows (`LoraRadioUiState.customPrimary`), because it also moves the radio to another frequency.
 * - **Broadcast only.** A unicast text addressed to the board is not public, and ADR 2026-09.emd7 marks the
 *   board unmonitored precisely to say that Knit does not read it.
 * - **`TEXT_MESSAGE_APP` only.** Position, telemetry and routing say where somebody is and how their hardware
 *   is doing; none of that belongs in a chat room. `NODEINFO_APP` feeds the name directory instead, one layer
 *   down in [MeshtasticSession].
 *
 * `via_mqtt` is deliberately **not** a filter. A post injected from somebody's internet uplink is carried and
 * flagged, because measuring how much of a neighbourhood's LongFast traffic arrives that way is the reason the
 * receive-only phase exists — the decision about hiding it is downstream of that number, not ahead of it.
 */
internal object LongFastPolicy {
    /** The primary channel's index. Knit itself is always a secondary (ADR 045), and never writes this one. */
    const val PRIMARY_INDEX = 0

    /** Why a channel-0 packet was not ingested, for the counters the receive-only phase is here to produce. */
    enum class Refusal {
        /** The board's primary is renamed or re-keyed — somebody's private group, not the public channel. */
        NOT_STOCK_PRIMARY,

        /** Addressed to one node rather than the whole channel. */
        NOT_BROADCAST,

        /** A portnum that is not chat: position, telemetry, routing, an app Knit knows nothing about. */
        NOT_TEXT,

        /** Empty after decoding and trimming — nothing to show. */
        EMPTY_BODY,

        /** No packet id, so no deterministic frame id and no way for two gateways to converge on one copy. */
        NO_PACKET_ID,
    }

    /** Either the post to publish, or why it was refused. */
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
     * to `channelIndex == PRIMARY_INDEX` first — an off-primary packet is Knit's own traffic or a private
     * channel, and neither is this policy's business.
     *
     * [name] is what the board's NodeDB calls the speaker, looked up by the caller so this stays pure.
     */
    fun judge(
        packet: ReceivedPacket,
        channels: List<ChannelInfo>,
        radio: LoraRadioConfig?,
        name: String? = null,
    ): Verdict {
        if (!isStockPrimary(channels, radio)) return Verdict.Refused(Refusal.NOT_STOCK_PRIMARY)
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
     * Whether the board's primary is Meshtastic's **stock public channel** — the one every unconfigured radio
     * in the region lands on, and the only one whose traffic is public by construction.
     *
     * Two halves, and both are needed. The **name** must be the preset's own default (or empty, which the
     * firmware substitutes that default for), because the firmware hashes it into the RF slot: a renamed
     * primary is on another frequency with another set of listeners. The **key** must be the default one,
     * because a name says nothing about who can read the channel — a group that keeps the stock name and
     * changes the PSK is exactly as private as one that renames.
     *
     * A board that reports no channel table at all is refused rather than given the benefit of the doubt. This
     * is the opposite reading from `LoraMeshTransport.boundSlotIsKnit`, which admits an empty table because
     * going mute on unreadable firmware is the worse failure there. Here the failure directions are reversed:
     * guessing wrong means ingesting somebody's private channel into a room, so silence is the safe answer.
     */
    fun isStockPrimary(
        channels: List<ChannelInfo>,
        radio: LoraRadioConfig?,
    ): Boolean {
        val primary = channels.firstOrNull { it.index == PRIMARY_INDEX } ?: return false
        return primary.isDefaultKey && hasStockName(primary, radio)
    }

    /**
     * The **name** half of [isStockPrimary], on its own: whether the primary still carries the name the
     * firmware would give it — empty (which `Channels::getName` substitutes the preset's display name for), or
     * that name spelled out.
     *
     * Public because the LoRa screen asks the same question for a different reason, and a rule stated twice
     * drifts: `LoraRadioUiState.customPrimary` warns about a **renamed** primary because renaming moves the
     * radio to another RF slot where no other Knit board is listening (ADR 045), while the bridge refuses one
     * because it is somebody's own channel rather than the public one. Same test, two consequences.
     *
     * Null [radio] is "we cannot tell" and answers **false** here — the bridge's safe direction. The screen
     * inverts this for its warning, so it must handle the unknown case itself rather than let a board that has
     * not reported its preset read as renamed.
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
     * name the firmware substitutes, which is the modem preset's. Null when the board has not reported its
     * radio settings, since without the preset there is nothing to compare a name against.
     */
    fun primaryName(
        channels: List<ChannelInfo>,
        radio: LoraRadioConfig?,
    ): String? {
        val preset = radio?.modemPreset ?: return null
        val named = channels.firstOrNull { it.index == PRIMARY_INDEX }?.name.orEmpty()
        return named.ifEmpty { preset.defaultChannelName }
    }

    /** SNR is carried as tenths of a dB so the frame encoding pins byte-exactly; a float would not. */
    private const val DECI = 10f
}
