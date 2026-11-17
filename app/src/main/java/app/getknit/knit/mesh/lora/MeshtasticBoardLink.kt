package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.TransportHealth
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/**
 * The unchanged [MeshtasticLink] seen through the neutral [BoardLink] (ADR 2026-11.gsqu, Stage 1b.2). It adds no
 * buffering, no scope and no coroutine of its own: [state] is a value-mapping view of the link's state flow, read on
 * every `value` (about ten sites read it synchronously) and mapped as it is collected; [packets] and [outcomes] are
 * mapped on the core's own collector, so an answer queued behind a stalled outcomes hop is dropped where it always was
 * (`MeshtasticSession`'s buffer, `LoraMeshTransport`'s `onRadioReached` contract).
 *
 * [send] writes exactly the fields the transport wrote before the seam: `hop_limit` 3 ([LoraMeshTransport.HOP_LIMIT])
 * on every Knit, public and reply packet and none on the admin write, the port per purpose, `want_ack` on a compact DM
 * alone, and the bound slot or the primary.
 */
internal class MeshtasticBoardLink(
    private val link: MeshtasticLink,
) : BoardLink {
    override val state: StateFlow<BoardState> = MappedStateFlow(link.state, ::boardState)

    override val packets: Flow<BoardPacket> = link.packets.map(::boardPacket)

    override val outcomes: Flow<PacketOutcome> = link.outcomes

    override val headroom: Flow<Int> = link.queue.mapNotNull { it?.free }

    override val headroomNow: Int? get() = link.queue.value?.free

    override val battery: StateFlow<BoardBattery?> = link.battery

    override val boardAir: StateFlow<BoardAir?> = link.boardAir

    override suspend fun send(
        payload: ByteArray,
        purpose: SendPurpose,
    ): BoardSend {
        // Stamped before the write, as the transport always did: a key the NodeInfo stream carries after it raced a `39`.
        val preSend = link.userSeq()
        val result =
            when (purpose) {
                is SendPurpose.KnitFrame -> {
                    link.send(payload, purpose.channel, portnum = MeshtasticProto.PORT_PRIVATE_APP, hopLimit = HOP)
                }

                is SendPurpose.DirectDm -> {
                    direct(purpose.to, ack = true)?.let {
                        link.send(payload, purpose.channel, portnum = MeshtasticProto.PORT_PRIVATE_APP, hopLimit = HOP, destination = it)
                    } ?: unaddressable()
                }

                is SendPurpose.PublicPost -> {
                    link.send(payload, PublicChannelPolicy.PRIMARY_INDEX, portnum = MeshtasticProto.PORT_TEXT_MESSAGE, hopLimit = HOP)
                }

                is SendPurpose.AutoReply -> {
                    direct(purpose.to, ack = false)?.let {
                        link.send(
                            payload,
                            PublicChannelPolicy.PRIMARY_INDEX,
                            portnum = MeshtasticProto.PORT_TEXT_MESSAGE,
                            hopLimit = HOP,
                            destination = it,
                        )
                    } ?: unaddressable()
                }

                // MeshCore's debug payload (Stage 7): no Meshtastic form, its own bridge (`…debug.LORATX`) is the raw path here.
                is SendPurpose.Debug -> {
                    unaddressable()
                }

                is SendPurpose.Admin -> {
                    direct(purpose.to, ack = false)?.let {
                        link.send(
                            payload,
                            PublicChannelPolicy.PRIMARY_INDEX,
                            portnum = MeshtasticProto.PORT_ADMIN,
                            hopLimit = null,
                            destination = it,
                        )
                    } ?: unaddressable()
                }
            }
        return BoardSend(result, preSend)
    }

    override suspend fun provision(spec: ProvisionSpec): ProvisionResult = link.provisionChannel(spec)

    override fun start(address: String) = link.start(address)

    override fun stop() = link.stop()

    /** A Meshtastic board is addressed by its node number; any other kind's address is not one this link can reach. */
    private fun direct(
        to: BoardAddr,
        ack: Boolean,
    ): LinkDestination.Direct? = (to as? BoardAddr.Meshtastic)?.let { LinkDestination.Direct(it, ack) }

    /** Refused locally, never written: the plane gives it up (`RefusalClass.OTHER`) as it would any rejected packet. */
    private fun unaddressable(): SendResult = SendResult.Rejected(0u, UNADDRESSABLE)

    private fun boardState(state: LinkState): BoardState =
        when (state) {
            is LinkState.Ready -> {
                BoardState.Ready(
                    own = BoardAddr(state.board.myNodeNum),
                    payloadCap = payloadCap(state.mtu),
                    radio = state.radio?.let { MeshtasticTraits.radioParams(it.modemPreset) },
                    channel = state.channels.firstOrNull { it.name == KnitChannel.NAME }?.index,
                    detail = state,
                )
            }

            LinkState.Connecting, LinkState.Bonding, is LinkState.Handshaking, is LinkState.Disconnected -> {
                BoardState.Down(TransportHealth.Degraded, state)
            }

            LinkState.Idle, LinkState.Unavailable, is LinkState.NeedsPairing, is LinkState.StaleBond -> {
                BoardState.Down(TransportHealth.Unavailable, state)
            }
        }

    /** One packet, kind-neutral; the [ReceivedPacket] rides as its attachment. A `from` of 0 is one the board could not decode. */
    private fun boardPacket(packet: ReceivedPacket): BoardPacket {
        val own = (link.state.value as? LinkState.Ready)?.board?.myNodeNum
        return BoardPacket(
            from = packet.from.takeIf { it != 0u }?.let { BoardAddr(it) },
            toUs = own != null && packet.to == own,
            channel = packet.channelIndex,
            payload = packet.payload,
            rxSnr = packet.rxSnr,
            rxRssi = packet.rxRssi,
            hopsAway = packet.hopsAway,
            rxTimeMs = packet.rxTime?.let { it.toLong() * MS_PER_SECOND },
            kind = packet,
        )
    }

    companion object {
        private const val HOP = LoraMeshTransport.HOP_LIMIT
        private const val MS_PER_SECOND = 1_000L

        /** Not a firmware code: what [send] answers for an address that is not a Meshtastic node. */
        const val UNADDRESSABLE = -1

        /**
         * The largest `Data.payload` one packet carries at an ATT [mtu]: the MTU less the ToRadio framing
         * ([LoraMeshTransport.TORADIO_OVERHEAD]), so a full fragment's write fits one ATT op, within the codec's floor and
         * the router's cap.
         */
        fun payloadCap(mtu: Int): Int =
            (mtu - LoraMeshTransport.TORADIO_OVERHEAD).coerceIn(LoraFrameCodec.MIN_PAYLOAD, MeshtasticProto.MAX_PAYLOAD)
    }
}

/** The Meshtastic record of an inbound [BoardPacket], or null for another kind's. */
internal val BoardPacket.meshtastic: ReceivedPacket? get() = kind as? ReceivedPacket

/** The Meshtastic link state under a [BoardState] ([LinkState.Idle] for another kind's). */
internal val BoardState.meshtastic: LinkState get() = detail as? LinkState ?: LinkState.Idle

/** The Meshtastic session's Ready under a [BoardState], or null when it is not Ready (or another kind's). */
internal val BoardState.meshtasticReady: LinkState.Ready? get() = detail as? LinkState.Ready

/** The node number of a Meshtastic [BoardAddr], or null for another kind's: the Meshtastic-only paths' one read of it. */
internal val BoardAddr.meshtasticNode: UInt? get() = (this as? BoardAddr.Meshtastic)?.node

/**
 * A [StateFlow] that is [source] seen through [transform]: [value] maps the source's value on every read, and a collector
 * gets every source value mapped, on its own coroutine. No `stateIn`, so no scope and no second copy of the state to go
 * stale — a synchronous read is always the source's latest.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MappedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R get() = transform(source.value)

    override val replayCache: List<R> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing = source.collect { collector.emit(transform(it)) }
}
