package app.getknit.knit.mesh.lora

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What [app.getknit.knit.mesh.lora.LoraMeshTransport] consumes: a managed link to one Meshtastic board.
 * The link owns connection lifecycle (dial, bond-on-demand, the config handshake, the keep-alive
 * heartbeat, and reconnect-with-backoff), surfaces inbound packets and back-pressure evidence, and
 * accepts one packet at a time to send. **Pacing is the transport's job** — the link never sleeps
 * between sends; it only reports what the board tells it (`queue` free/maxlen, `Busy`, NAKs).
 *
 * Implemented by [MeshtasticSession] (pure, over the [MeshtasticGattDialer] seam).
 */
internal interface MeshtasticLink {
    val state: StateFlow<LinkState>

    /** Inbound mesh packets, already decoded; `tryEmit`ted like the radio transports' `_inbound`. */
    val packets: SharedFlow<ReceivedPacket>

    /** Late NAKs (seconds after the send returned) keyed by our packet id — e.g. a duty-cycle refusal. */
    val outcomes: SharedFlow<PacketOutcome>

    /** The board's transmit-queue headroom from the latest `queueStatus`; null until first reported. */
    val queue: StateFlow<QueueInfo?>

    /**
     * The board's own battery, from its `NodeInfo` in the handshake and the device telemetry it sends the
     * phone about once a minute; null until reported and again once the link is stopped. Kept out of [state]
     * so it doesn't churn the state on every packet.
     */
    val battery: StateFlow<BoardBattery?>

    /**
     * The board's own duty-cycle measurement, from the same telemetry as [battery]; null until reported and
     * again once the link is stopped. It is the ground truth `LoraAirtime` only estimates — see [BoardAir].
     */
    val boardAir: StateFlow<BoardAir?>

    /**
     * What the mesh's other nodes call themselves, keyed by node number — the board's NodeDB as it streams in
     * the handshake and stays current through `NODEINFO_APP` broadcasts. Only the LongFast bridge reads it, to
     * put a name on a bridged post instead of a bare node number.
     *
     * Its own flow rather than a field on [LinkState.Ready], because a busy mesh pushes NODEINFO constantly and
     * folding it into the link state would re-emit the whole state — and every collector of it — each time.
     * Bounded and lossy by design: a fixed working set, oldest evicted, cleared with the link. A post whose
     * author is not in it renders as its `!hex` id, which is what a stock client shows too.
     */
    val nodes: StateFlow<Map<UInt, BoardOwner>>

    /** Enqueues one packet on the board. Returns synchronously once the board acknowledges (or refuses) it. */
    suspend fun send(
        payload: ByteArray,
        channelIndex: Int,
        portnum: Int = MeshtasticProto.PORT_PRIVATE_APP,
        hopLimit: Int? = null,
    ): SendResult

    /**
     * Sets the board up for Knit ([spec]), or puts it back the way it was ([ProvisionMode.Restore]). The
     * board typically reboots to apply the edit; the link rides that out (it re-handshakes) — the result is
     * returned as soon as the write is accepted, before the reboot.
     */
    suspend fun provisionChannel(spec: ProvisionSpec): ProvisionResult

    /** (Re)connects to [address], retrying with backoff while started. Idempotent for the same address. */
    fun start(address: String)

    fun stop()
}

/** The channel name + PSK to write, and which direction to write it. */
internal data class ProvisionSpec(
    val name: String,
    val psk: ByteArray,
    val mode: ProvisionMode = ProvisionMode.Setup,
    /**
     * The board's own intervals and name as recorded when it was set up. [ProvisionMode.Restore] writes them
     * back; [ProvisionMode.Setup] carries them forward untouched when all it has left to do is the rename.
     */
    val previous: BoardSettings? = null,
)

/**
 * What a user can do to a board (ADR 045). In a release build there are deliberately **two**, and no middle
 * setting: a board is either set up for Knit or it is a stock Meshtastic node, so any two Knit boards are
 * configured identically and meet without coordination.
 *
 * [SetupDedicated] is the debug-only third (ADR 067), and it is a different bargain rather than a lighter
 * one — see its doc. Both setups produce the same channel table; they differ only in whether the radio is
 * pinned off the shared public slot, and [Restore] undoes either.
 */
internal enum class ProvisionMode {
    /**
     * Set the board up for Knit: the Knit channel goes into a free **secondary** slot — the primary, and so
     * the RF slot the firmware hashes out of its name, is left alone on purpose (ADR 045) — the board is
     * renamed for Knit ([BoardName], ADR 049), and its housekeeping broadcasts are quieted ([BoardQuiet]).
     */
    Setup,

    /**
     * [Setup], plus `lora.channel_num` pinned to the slot [LoraSlot] derives for the board's region and
     * preset — so the fleet has the frequency to itself and `LoraAirtime` drops the politeness ceiling
     * (ADR 067). **Debug builds only**, and never the default: it trades away the free relaying a stock
     * Meshtastic neighbourhood does for us, which is worth having wherever there is a neighbourhood. Where
     * there is not — an isolated farm, a mountain house — there is nothing to trade away and a household
     * fleet gets its region's whole duty cycle instead of a tenth of it.
     *
     * Refused as [ProvisionResult.NoDedicatedSlot] when Knit will not place a slot in the board's region.
     */
    SetupDedicated,

    /**
     * Undoes either setup: the Knit channel is disabled, the board's own intervals and name come back, and
     * `lora.channel_num` goes back to what the board had — which for a board Knit pinned is 0, the firmware
     * deriving its slot from the primary's name again.
     */
    Restore,
}

/** The outcome of [MeshtasticLink.provisionChannel]. */
internal sealed interface ProvisionResult {
    /** The Knit channel is at [index] (freshly written, or [alreadyPresent] and left as-is); bind the plane to it. */
    data class Provisioned(
        val index: Int,
        val alreadyPresent: Boolean,
        /**
         * The housekeeping intervals and the name the board had *before* the write, for the caller to
         * persist — without them a restore can only offer the firmware's defaults, not the user's own. Null
         * when nothing was written, so a re-run never overwrites the record with the quieted values. A board
         * that only needed the rename ([alreadyPresent]) reports the caller's own record with the old name
         * filled in, since that is the one moment the old name is still knowable.
         */
        val previous: BoardSettings? = null,
    ) : ProvisionResult

    /** Every secondary slot (1..7) is already taken by a different channel; the user must free one. */
    data object NoFreeSlot : ProvisionResult

    /**
     * [ProvisionMode.SetupDedicated] on a board whose region Knit will not place a dedicated RF slot in —
     * an unknown band, or one with no room to move ([LoraSlot.forRegion]). Nothing was written: picking the
     * frequency ourselves is only safe where the band is known exactly, so the refusal is the feature.
     */
    data class NoDedicatedSlot(
        val region: LoraRegion,
    ) : ProvisionResult

    /** The board is a stock Meshtastic node again; it carries no Knit channel, so the plane has nowhere to send. */
    data object Restored : ProvisionResult

    /** The board never accepted the write (e.g. it kept rejecting the admin session key). */
    data class Failed(
        val reason: String,
    ) : ProvisionResult

    /** The link wasn't [LinkState.Ready] when asked. */
    data class NotReady(
        val state: LinkState,
    ) : ProvisionResult
}

/** The link's lifecycle. Terminal states ([NeedsPairing], [StaleBond]) stop retrying until [MeshtasticLink.start]. */
internal sealed interface LinkState {
    data object Idle : LinkState

    data object Connecting : LinkState

    /** The stack is pairing (the board is showing its PIN); the first protected op waits this out. */
    data object Bonding : LinkState

    data class Handshaking(
        val board: BoardInfo?,
    ) : LinkState

    data class Ready(
        val board: BoardInfo,
        val channels: List<ChannelInfo>,
        val mtu: Int,
        /** The board's region + modem preset, once its config stream reported them; null on older firmware. */
        val radio: LoraRadioConfig? = null,
    ) : LinkState

    data class Disconnected(
        val reason: String,
        val retryAtMs: Long,
        val streak: Int,
    ) : LinkState

    /** The adapter is off (or absent) — nothing to do but wait for the user to turn Bluetooth on. */
    data object Unavailable : LinkState

    /** The board is bonded-away or never paired — the user must pair it in the picker; retries stop. */
    data class NeedsPairing(
        val address: String,
    ) : LinkState

    /** A stale bond the stack keeps rejecting — the user must forget the device in Settings and re-pair. */
    data class StaleBond(
        val address: String,
    ) : LinkState
}

/** Identity of the connected board, learned from `my_info`/`metadata` during the handshake. */
internal data class BoardInfo(
    val myNodeNum: UInt,
    val pioEnv: String?,
    val firmwareVersion: String?,
    /**
     * What the board calls itself on the mesh, off its own `NodeInfo` in the handshake — the name on its
     * screen and in every other radio's node list. Null on firmware that never sends its own entry, which
     * the setup screen reads as "no reason to think it needs renaming".
     */
    val owner: BoardOwner? = null,
)

/** One inbound packet the board handed the phone. */
internal class ReceivedPacket(
    val from: UInt,
    val to: UInt,
    val id: UInt,
    val channelIndex: Int,
    val portnum: Int,
    val payload: ByteArray,
    val rxSnr: Float?,
    val rxRssi: Int?,
    val hopsAway: Int?,
    /** `MeshPacket.via_mqtt` — the packet came off an MQTT uplink rather than the air. Read by the bridge only. */
    val viaMqtt: Boolean = false,
)

/**
 * What the board says about the air, off its own `DeviceMetrics`. **Not comparable to `LoraAirtime`'s ledger
 * without saying so**, and the pair is worth keeping straight: [airUtilTxPercent] is every packet this radio
 * sent over the last **hour**, relays of other people's traffic included, while the governor tracks what Knit
 * handed the board over the last **fifteen minutes**. Reading one as the other is what made a quiet plane look
 * like it had leaked airtime. [channelUtilPercent] is the band around it, which Knit never controls at all.
 */
internal data class BoardAir(
    val channelUtilPercent: Float?,
    val airUtilTxPercent: Float?,
)

/** The board's transmit-queue headroom. */
internal data class QueueInfo(
    val free: Int,
    val maxlen: Int,
    val atMs: Long,
)

/**
 * One radio's last-heard signal quality, surfaced for the diagnostics/settings row. Owned by
 * [LoraMeshTransport] and keyed per radio, **not** by the link: the link sees the whole air — strangers on
 * the board's public primary channel, and the NodeDB replay the firmware sends at every handshake — and only
 * the transport knows which channel this plane is bound to. [atMs] is what ages a reading out with its radio.
 */
internal data class RxQuality(
    val snr: Float?,
    val rssi: Int?,
    val atMs: Long,
) {
    /**
     * This reading refreshed by a newer packet from the same radio. A field absent from [next] keeps the
     * value it had rather than blanking the row: a reception always proves the radio is there and when, and
     * usually — but not always — carries both numbers.
     */
    fun refreshedBy(next: RxQuality) = RxQuality(next.snr ?: snr, next.rssi ?: rssi, next.atMs)
}

/** The synchronous outcome of a [MeshtasticLink.send]. */
internal sealed interface SendResult {
    data class Queued(
        val id: UInt,
        val queue: QueueInfo,
    ) : SendResult

    /** The mesh refused the packet immediately (e.g. NO_CHANNEL, TOO_LARGE) — folded in from the drain. */
    data class Nak(
        val id: UInt,
        val reason: RoutingError,
    ) : SendResult

    /** The board's `queueStatus.res` was non-zero (a firmware error code). */
    data class Rejected(
        val id: UInt,
        val res: Int,
    ) : SendResult

    /** No headroom (`queue.free == 0`) — nothing was written; the transport paces and retries. */
    data object Busy : SendResult

    /** Larger than [MeshtasticProto.MAX_PAYLOAD]; refused locally, never written. */
    data object TooLarge : SendResult

    data class NotReady(
        val state: LinkState,
    ) : SendResult

    /** The write went out but no correlated `queueStatus` came back in time. */
    data object Timeout : SendResult
}

/** A NAK that arrived after [MeshtasticLink.send] had already returned. */
internal data class PacketOutcome(
    val id: UInt,
    val reason: RoutingError,
)

/** The board + channel the LoRa plane is bound to, derived from settings; null means the plane is off. */
internal data class LoraConfig(
    val address: String,
    val channelIndex: Int,
    /** Whether sealed DM-form chat may ride this plane (`SettingsStore.loraDmEnabled`, ADR 039); the room always does. */
    val dms: Boolean = true,
    /** Whether this board gossips and serves backfill between pockets (`SettingsStore.loraBridgeEnabled`, ADR 044). */
    val bridge: Boolean = true,
)
