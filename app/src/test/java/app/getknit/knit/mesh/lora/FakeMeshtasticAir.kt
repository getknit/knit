package app.getknit.knit.mesh.lora

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A tiny in-memory LoRa "air": every registered [FakeMeshtasticLink] floods each send to every OTHER
 * registered link (a board never echoes the phone's own packet back), so two [LoraMeshTransport]s can be
 * exercised end-to-end on the JVM with no radio and no GATT. Single-threaded by test contract.
 */
internal class FakeMeshtasticAir {
    private val links = mutableListOf<FakeMeshtasticLink>()
    var lossy: (from: UInt, to: UInt) -> Boolean = { _, _ -> false }

    fun register(link: FakeMeshtasticLink) {
        links += link
    }

    fun unregister(link: FakeMeshtasticLink) {
        links -= link
    }

    fun broadcast(
        from: UInt,
        channelIndex: Int,
        portnum: Int,
        payload: ByteArray,
    ) {
        links
            .filter { it.nodeNum != from && !lossy(from, it.nodeNum) }
            .forEach { it.deliver(from, channelIndex, portnum, payload) }
    }
}

/** A [MeshtasticLink] backed by [FakeMeshtasticAir]; goes Ready on start and floods sends to the air. */
internal class FakeMeshtasticLink(
    val nodeNum: UInt,
    private val air: FakeMeshtasticAir,
    private val channelName: String = KnitChannel.NAME,
    /** What the handshake reports as the board's firmware. Pre-2.8 by default: the signature era is opt-in. */
    private val firmware: String = "2.5.0",
) : MeshtasticLink {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state = _state

    private val _packets = MutableSharedFlow<ReceivedPacket>(extraBufferCapacity = 256)
    override val packets = _packets

    private val _outcomes = MutableSharedFlow<PacketOutcome>(extraBufferCapacity = 64)
    override val outcomes = _outcomes

    private val _queue = MutableStateFlow<QueueInfo?>(QueueInfo(free = 16, maxlen = 16, atMs = 0))
    override val queue = _queue

    override val battery = MutableStateFlow<BoardBattery?>(null)

    /** The board's free-slot count. Assigning it also publishes a [queue] update, as a real QueueStatus does. */
    var free: Int = 16
        set(value) {
            field = value
            _queue.value = QueueInfo(free = value, maxlen = 16, atMs = 0)
        }

    /**
     * When set, each accepted send costs a queue slot, the way a real board's does — so a fragmented frame
     * can run the board out of room part-way and get [SendResult.Busy] for the rest of itself. Off by
     * default: most tests want a board that always has room.
     */
    var queueFills = false
    private var nextId = 1u
    val sent = mutableListOf<ByteArray>()

    override suspend fun send(
        payload: ByteArray,
        channelIndex: Int,
        portnum: Int,
        hopLimit: Int?,
    ): SendResult {
        if (free == 0) return SendResult.Busy
        sent += payload
        val id = nextId++
        if (queueFills) free--
        air.broadcast(nodeNum, channelIndex, portnum, payload)
        return SendResult.Queued(id, QueueInfo(free, 16, 0))
    }

    /** What [provisionChannel] returns; a test can script a different outcome. */
    var provisionResult: ProvisionResult = ProvisionResult.Provisioned(index = 1, alreadyPresent = false)
    val provisioned = mutableListOf<ProvisionSpec>()

    override suspend fun provisionChannel(spec: ProvisionSpec): ProvisionResult {
        provisioned += spec
        return provisionResult
    }

    /** Off to model a board still connecting after [start]: the state parks at Connecting until [ready]. */
    var readyOnStart = true

    override fun start(address: String) {
        if (readyOnStart) ready() else _state.value = LinkState.Connecting
        air.register(this)
    }

    /** The handshake completing (at ATT MTU 512, the ESP32 line's ceiling): what a real board reports last. */
    fun ready() {
        _state.value = LinkState.Ready(BoardInfo(nodeNum, "heltec-v4", firmware), listOf(ChannelInfo(0, channelName, 1)), 512)
    }

    override fun stop() {
        air.unregister(this)
        _state.value = LinkState.Idle
    }

    /** A packet arriving from the air (another board's broadcast). */
    fun deliver(
        from: UInt,
        channelIndex: Int,
        portnum: Int,
        payload: ByteArray,
        rxSnr: Float? = 6.5f,
        rxRssi: Int? = -85,
    ) {
        _packets.tryEmit(
            ReceivedPacket(
                from = from,
                to = MeshtasticProto.BROADCAST,
                id = nextId++,
                channelIndex = channelIndex,
                portnum = portnum,
                payload = payload,
                rxSnr = rxSnr,
                rxRssi = rxRssi,
                hopsAway = 0,
            ),
        )
    }

    fun emitNak(
        id: UInt,
        reason: RoutingError,
    ) {
        _outcomes.tryEmit(PacketOutcome(id, reason))
    }

    fun updateHeadroom(value: Int) {
        free = value
        _queue.value = QueueInfo(value, 16, 0)
    }

    fun drop() {
        _state.value = LinkState.Disconnected("test", retryAtMs = 0, streak = 1)
    }
}
