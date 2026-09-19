package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.SeenSet
import app.getknit.knit.mesh.meshNodeLabel

/**
 * Answers a Meshtastic user who DMs a Knit board — once — with a line saying nobody reads it and where Knit
 * lives. The auto-reply's rule, kept pure so it is JVM-testable against fabricated packets
 * ([app.getknit.knit.mesh.lora.DmAutoReplyPolicyTest]); the sibling of [PublicChannelPolicy], which reads
 * the same slot's *broadcasts* into the Meshtastic room.
 *
 * ADR 2026-09.emd7 marks a set-up board `is_unmessagable` so clients that honour the flag grey it out, but
 * the flag is a `NodeInfo` hint that reaches neighbours slowly (the setup quiets node-info to 6 h) and older
 * clients ignore it. A DM still arrives, the firmware's routing layer ACKs it, and the sender's app shows a
 * **delivered** tick against words nobody will read. This is the reply that tick was missing: the same
 * fact the mark states, said once to the one person it matters to, with the link a curious neighbour would
 * want.
 *
 * **Only a unicast text addressed to our own board** ([Refusal.NOT_FOR_US]). A broadcast is the room's
 * business; a unicast to some other node is somebody else's conversation the board happened to decode.
 *
 * **Politeness is two caps, and both are this file's.** A sender hears it **once per [PER_SENDER_MS]**
 * ([Refusal.REPLIED_RECENTLY]), keyed by node number, so a second message — or a neighbour's bot answering
 * our answer — costs nothing; and the board speaks at most **once per [FLOOR_MS]** across every sender
 * ([Refusal.TOO_SOON]), so a burst of curious neighbours is one reply at a time, not a chorus. The memory
 * behind the first cap outlives the process through `LoraPlaneSnapshot.autoReplied` ([stamps] / [restore]),
 * because the board replays its queue on reconnect and a restart would otherwise answer the same message
 * twice. The airtime share the reply spends from is the transport's call ([LoraAirtime]'s `PUBLIC` bucket),
 * not this file's — hearing a DM costs nothing, and a refusal here is decided before any budget is asked.
 *
 * **The words are fixed, in English, and never the user's.** The reply speaks for the board, not the person
 * — it carries no display name (ADR 049's rule for everything on the public frequency) and no hint of who
 * paired it. [TEXT] is well under the 200-byte client convention ([PublicPostPolicy.MAX_ON_AIR_BYTES]) and
 * the PKI packet ceiling, so it goes out whole on every firmware.
 */
internal class DmAutoReplyPolicy(
    private val clock: () -> Long,
    /** How many senders the once-a-day memory holds at once; a stranger past the cap is answered again. */
    maxSenders: Int = MAX_SENDERS,
) {
    /** Why a unicast text was not answered, for the counters `…debug.LORA` reports. */
    enum class Refusal {
        /** Addressed to some other node, or a broadcast — not a message to this board at all. */
        NOT_FOR_US,

        /** Our own board sent it (a loopback the firmware should never hand us, refused all the same). */
        OWN_BOARD,

        /** Not chat: position, telemetry, routing, an app Knit knows nothing about. */
        NOT_TEXT,

        /** No packet id, so no way to tell the board's reconnect replay from a new message. */
        NO_PACKET_ID,

        /** This sender heard the reply within [PER_SENDER_MS] already. */
        REPLIED_RECENTLY,

        /** The board answered somebody within [FLOOR_MS]; a burst of DMs is one reply at a time. */
        TOO_SOON,
    }

    /** Either the reply to put on the air, or why there is none. */
    sealed interface Verdict {
        data class Reply(
            /** The sender's node number — the reply's `to`. */
            val to: UInt,
            val text: String,
        ) : Verdict

        data class Refused(
            val reason: Refusal,
        ) : Verdict
    }

    private val replied = SeenSet(maxSize = maxSenders, ttlMillis = PER_SENDER_MS, clock = clock)

    /** When the board last answered anybody, on [clock]; [NEVER] until it has. */
    @Volatile
    private var lastReplyAt: Long = NEVER

    /**
     * Judges one [packet] the board handed us. A [Verdict.Reply] **claims** both caps at once — the sender
     * is stamped and the floor moved — so the caller must put it on the air or accept that this sender
     * has had their one answer; a reply refused for lack of airtime is counted there, not retried here.
     *
     * [ownNode] is our own board's node number: the reply goes only to a text addressed to it.
     */
    fun judge(
        packet: ReceivedPacket,
        ownNode: UInt,
    ): Verdict {
        if (packet.to == MeshtasticProto.BROADCAST || packet.to != ownNode) return Verdict.Refused(Refusal.NOT_FOR_US)
        if (packet.from == ownNode) return Verdict.Refused(Refusal.OWN_BOARD)
        if (packet.portnum != MeshtasticProto.PORT_TEXT_MESSAGE) return Verdict.Refused(Refusal.NOT_TEXT)
        if (packet.id == 0u) return Verdict.Refused(Refusal.NO_PACKET_ID)
        val now = clock()
        // The floor is asked before the sender is stamped: a sender refused for the floor has not had their
        // answer, and their next message — or this one, replayed by the board — still earns it.
        if (lastReplyAt != NEVER && now - lastReplyAt < FLOOR_MS) return Verdict.Refused(Refusal.TOO_SOON)
        if (!replied.add(senderKey(packet.from))) return Verdict.Refused(Refusal.REPLIED_RECENTLY)
        lastReplyAt = now
        return Verdict.Reply(to = packet.from, text = TEXT)
    }

    /** The senders answered inside the window, oldest first, on [clock] — what the plane snapshot persists. */
    fun stamps(): List<Pair<String, Long>> = replied.stamps()

    /** Re-applies [stamps] from a previous process, on [clock]; anything past the window is left out. */
    fun restore(stamps: List<Pair<String, Long>>) = replied.restore(stamps)

    companion object {
        /**
         * The line a Meshtastic user reads. 149 bytes of ASCII: under the 200-byte client convention every
         * stock app composes against, and under the PKI packet's own ceiling, so it is never trimmed. Plain
         * hyphens and no name, because it is read on a two-line OLED as often as on a phone.
         */
        const val TEXT =
            "Auto-reply: this node is unmonitored. Nobody reads messages sent here. " +
                "It's part of a Knit mesh, an offline messenger for phones. https://getknit.app"

        /**
         * One reply per sender per day. Long enough that a conversation's worth of messages — or a bot that
         * answers our answer — earns one reply and then silence; short enough that a neighbour who forgets
         * and writes again next week is told again.
         */
        const val PER_SENDER_MS = 24 * 60 * 60_000L

        /**
         * The shortest gap between two replies to anybody — the public post's own floor
         * (`LoraMeshTransport.PUBLIC_POST_FLOOR_MS`), applied to a second speaker on the same channel.
         * Kept as its own timestamp rather than shared with the user's posts: an auto-reply must never
         * make the user's own next post wait, nor the other way round.
         */
        const val FLOOR_MS = 30_000L

        /** Senders remembered at once. A mesh has hundreds of nodes; a day's worth of DMs to one board has not. */
        const val MAX_SENDERS = 256

        private const val NEVER = Long.MIN_VALUE

        /** The key a sender is remembered under — the `!hex` id every Meshtastic client prints. */
        fun senderKey(node: UInt): String = meshNodeLabel(node.toLong())
    }
}

/**
 * Why `LoraMeshTransport.onDirectMessage` did not answer a DM the policy would have — the transport's own
 * gates, counted into the same `autoReplyRefusedByReason` map as [DmAutoReplyPolicy.Refusal] by name.
 * Kept beside the policy so the whole vocabulary a `…debug.LORA` dump can print is in one file.
 */
internal enum class AutoReplyRefusal {
    /** The link dropped between the packet and the reply. */
    NOT_READY,

    /** The board is pinned to a dedicated RF slot (ADR 067): no Meshtastic neighbour can hear it, or DM it. */
    DEDICATED,

    /** Slot 0 on this board *is* the Knit channel (the lab shape), so there is no primary to answer on. */
    KNIT_ON_PRIMARY,

    /**
     * The board never ran Knit's setup (or reports no channel table). The setup's sheet is where the user
     * agreed to the board saying it is unmonitored (ADR 2026-09.emd7); a stock board stays as quiet as one.
     */
    NOT_SET_UP,

    /** Longer than one packet carries — unreachable for a fixed [DmAutoReplyPolicy.TEXT], kept so it is visible. */
    TOO_LARGE,

    /** The public bucket's share of the rolling window is spent. */
    NO_AIR,

    /** The board or the mesh refused the packet — a sender with no public key on file, most likely. */
    NAK,
}
