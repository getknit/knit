package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import app.getknit.knit.mesh.BleAdvertWrite
import app.getknit.knit.mesh.BleSideDrop
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.SeenSet
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.link.FragReassembler
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The Bluetooth plane's **side channel** (knit/knit-next#13): small floodable frames carried on non-connectable
 * extended-advertising **pages**, connectionless and scheduled by the controller independently of the ACL, so
 * they bypass a file transfer head-of-line-blocking the one L2CAP stream and reach a sighted-but-unlinked peer
 * with no connection at all. It is the BLE mirror of the Wi-Fi Aware coordination plane's fast fan-out and
 * carries the same units — `FastFrameCodec`'s `0x03`/`0x05` frames and `0x04` fragments, one per page — with
 * the same contract: best-effort, deduped by the receiver, and never a replacement for the flood and custody.
 *
 * Two halves, both owned here so they share the adapter and the lifecycle of [BluetoothMeshTransport]:
 * - **Transmit** — [SideCarousel] decides what each of [SideCarousel.Config.slots] advertising sets has on air;
 *   this class raises the sets ([BleAdvertiser.sideParams]) under [BleConstants.SIDE_SERVICE_UUID] and swaps
 *   their payload in place. A frame is [offer]ed already gated by the caller ([BleFastRoutePolicy]): only when
 *   [live] and a flagged peer is nearby ([SideCapableTracker]).
 * - **Receive** — a second, hardware-filtered extended scan ([BleScanner] with `extended`), driven through
 *   [applyScanTier] by [SideScanPolicy] from the transport's loop. Every page heard is deduped twice — the same
 *   page repeats on every advertising event of its dwell (per advertiser address, one hash compare), and the
 *   same frame may arrive from another hop or over a link later ([SideCarousel.frameKey]) — then decoded and
 *   handed up as the frame it carries. A page is **never** presence: it has no PSM and no identity beyond the
 *   frame's author, so it feeds neither [BlePresenceTracker] nor the connectable-device map.
 *
 * Fragments reassemble by fragment id alone: each advertising set has its own resolvable private address (the
 * controller mints one per set), so the two parts of a frame airing on two slots arrive from two addresses and
 * an address-keyed store would never complete them. The id counter is seeded at random so two senders that
 * both booted recently do not collide, and a wrong assembly fails decode and is dropped — the flood heals it.
 *
 * Degrades rather than fails: no extended-advertising support, a page maximum too small, or a feature
 * refusal from either radio half turns the channel dark ([live] false, the flag leaves the presence advert);
 * a controller with too few advertising sets loses a slot for a while; a scan-budget refusal waits out the
 * gap. Android-bound (`android.bluetooth.*` stays under `mesh/bluetooth/`); the schedule, the scan table and
 * the routing are the pure classes beside it.
 */
@SuppressLint("MissingPermission")
class BleSideChannel internal constructor(
    context: Context,
    private val metrics: MeshMetrics,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val now: () -> Long,
    config: SideCarousel.Config,
) {
    /** The production shape (DI): the elapsed-realtime clock and the default schedule. */
    constructor(
        context: Context,
        metrics: MeshMetrics,
        scope: CoroutineScope,
        log: (String) -> Unit,
    ) : this(context, metrics, scope, log, SystemClock::elapsedRealtime, SideCarousel.Config())

    /** What the transport hears from the channel. */
    interface Listener {
        /** A frame heard off a page, decoded and deduped; [env] is [wire]'s decoded envelope. */
        fun onFrame(
            wire: WireEnvelope,
            env: RelayEnvelope,
        )

        /** [live] changed after bring-up (a runtime refusal) — the presence advert's flag must follow. */
        fun onAvailabilityChanged()

        /** A slot's enable was refused: [retryRefused] has a retry coming due, so the caller's loop should re-read [retryWaitMs]. */
        fun onAdvertRefused()
    }

    private val adapter: BluetoothAdapter? =
        context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

    // Transmit state (carousel, slots, timer) and the scan state share [lock]; the receive path has its own.
    private val lock = Any()
    private val rxLock = Any()

    /** This controller advertises and scans pages — what the presence advert's flag says. */
    @Volatile
    var live = false
        private set

    /** Bytes one page may carry: the AUX PDU budget, or less if the controller's maximum is smaller. */
    @Volatile
    var pageMax = 0
        private set

    @Volatile
    private var listener: Listener? = null

    private val carousel =
        SideCarousel(now, config) { drop ->
            when (drop) {
                SideCarousel.Drop.STALE -> metrics.onBleSideDropped(BleSideDrop.STALE)

                SideCarousel.Drop.OVERFLOW -> metrics.onBleSideDropped(BleSideDrop.OVERFLOW)

                // A rotation's count stays in STATE (never the field log, which would date it).
                SideCarousel.Drop.ROTATED -> metrics.onBleAdvertWrite(BleAdvertWrite.SIDE_FRAME_DROPPED)
            }
        }

    // Each slot's refused-enable retries (ADR 2026-10.9utz, amendment 2026-10-09). No net of their own: the slots ride
    // the presence set's turns ([reassert]), as the Coded set does, so only a reported refusal brings one forward.
    private val keepers: List<AdvertReassertPolicy.Keeper> =
        List(config.slots) { AdvertReassertPolicy.Keeper(alonePeriodMs = NO_NET_MS, linkedPeriodMs = NO_NET_MS) }

    // One set per slot, replaced by a fresh instance at each [rotate] (a new address, and a callback a late start of the
    // old one cannot reach). Written under [lock]; a slot's callback reads its own entry without it ([isSlot]).
    private val slots: Array<BleAdvertiser> = Array(config.slots) { newSlot(it) }

    /**
     * A page set for slot [index]: its callbacks act only while it is still that slot's set, so a retired set's late word
     * never lands on the set that replaced it (and [rotate] moves [generation], which drops a reaction already launched).
     */
    private fun newSlot(index: Int): BleAdvertiser {
        lateinit var set: BleAdvertiser
        set =
            BleAdvertiser(
                advertiserProvider = { adapter?.bluetoothLeAdvertiser },
                log = { log("slot$index: $it") },
                serviceUuid = BleConstants.SIDE_SERVICE_UUID,
                params = BleAdvertiser.sideParams(),
                onStartStatus = { status -> if (isSlot(index, set)) onSlotStatus(index, status) },
                onEnableStatus = { enabled, status -> if (isSlot(index, set)) onSlotEnabled(index, enabled, status) },
            )
        return set
    }

    // A racy read on a binder thread, on purpose: taking [lock] inside a slot's callback could deadlock (the order
    // everywhere else is [lock], then the slot). A stale answer either way is what [react]'s generation check settles.
    private fun isSlot(
        index: Int,
        set: BleAdvertiser,
    ): Boolean = slots[index] === set

    // What each set was last told to air (by reference — the carousel hands back the same part objects).
    private val pushed = arrayOfNulls<ByteArray>(config.slots)
    private val slotRetryAt = LongArray(config.slots)

    // Bumped under [lock] by every [bringUp] and [tearDown]. A slot's start reaction runs late, on [scope]
    // ([onSlotStatus]), so one from before an adapter bounce must not land on the session after it (a fast bounce
    // skips STATE_OFF, 557d2d86): read without [lock] on the binder thread, compared under it.
    @Volatile
    private var generation = 0
    private var timer: Job? = null

    private val scanner =
        BleScanner(
            scannerProvider = { adapter?.bluetoothLeScanner },
            onResult = ::onScanResult,
            log = { log("rx: $it") },
            serviceUuid = BleConstants.SIDE_SERVICE_UUID,
            extended = true,
            onFailed = ::onScanFailed,
        )

    @Volatile
    private var tier = SideScanPolicy.Tier.Off
    private var lastScanStartAt = -SideScanPolicy.MIN_RESTART_GAP_MS
    private var scanStartedAt = 0L

    private val seen = SeenSet(maxSize = HEARD_MAX, ttlMillis = HEARD_TTL_MS, clock = now)
    private val reassembler =
        FragReassembler<Int>(now, capacity = FRAG_CAPACITY, timeoutMs = FRAG_TIMEOUT_MS) { drop ->
            metrics.onBleSideDropped(
                when (drop) {
                    FragReassembler.Drop.TIMEOUT -> BleSideDrop.FRAG_TIMEOUT
                    FragReassembler.Drop.OVERFLOW -> BleSideDrop.FRAG_OVERFLOW
                },
            )
        }

    // Per advertiser address, the hash of the last page heard — the same page repeats every advertising event.
    private val lastPage =
        object : LinkedHashMap<String, Int>(LAST_PAGE_MAX, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean = size > LAST_PAGE_MAX
        }
    private val fragSeq = AtomicInteger(Random.nextInt(FRAG_ID_MASK + 1))

    fun bind(listener: Listener) {
        this.listener = listener
    }

    /** The current scan tier, for the transport's diag line and its change log. */
    internal val scanTier: SideScanPolicy.Tier get() = tier

    /**
     * Probes the controller (adapter on) and starts clean. Runs before the presence advert is (re)published so
     * the first advert already carries the right flag.
     */
    fun bringUp() {
        val a = adapter
        val extended = a != null && a.isEnabled && a.isLeExtendedAdvertisingSupported
        val maxData = if (extended) a.leMaximumAdvertisingDataLength else 0
        val page = pageFor(maxData)
        synchronized(lock) {
            generation++
            pageMax = page
            live = extended && page >= MIN_PAGE_BYTES
            carousel.clear()
            pushed.fill(null)
            for (i in slots.indices) carousel.setSlotUsable(i, true)
            keepers.forEach { it.start(now()) }
        }
        synchronized(rxLock) {
            reassembler.clear()
            lastPage.clear()
        }
        log("ble-side bring-up extAdv=$extended maxAdvData=$maxData page=$page live=$live")
    }

    /**
     * The presence advert rotated (ADR 2026-11.xp6d's writer, step 5b's W4): each slot gets a **fresh** set (a new
     * address; a stop and restart of the old instance would also reuse its callback, which a late start of the old set
     * reaches), the old sets retire, and the page each slot had on the air is not aired again ([SideCarousel.rotate]): a
     * side address heard beside one tag and then beside the next, or a page relayed on both sides of the edge, would link
     * the two tags for any scanner logging addresses (the bluetooth and security design passes). The queue stays, and the
     * carousel fills the fresh sets from it at once. A slot's start reaction from before the rotation is dropped (the
     * generation moves). Called by the transport's one advert writer, on its worker, never under the writer's lock.
     */
    fun rotate() {
        synchronized(lock) {
            generation++
            for (i in slots.indices) {
                slots[i].retire()
                slots[i] = newSlot(i)
            }
            val dropped = carousel.rotate()
            pushed.fill(null)
            if (dropped > 0) log("ble-side rotate dropped=$dropped")
            if (live) applyLocked() else rescheduleLocked()
        }
    }

    /** Radio down (adapter off, transport stop): every set and the scan go down; the seen window is kept. */
    fun tearDown() {
        synchronized(lock) {
            generation++
            timer?.cancel()
            timer = null
            slots.forEach { it.stop() }
            scanner.stop()
            tier = SideScanPolicy.Tier.Off
            carousel.clear()
            pushed.fill(null)
            live = false
        }
        synchronized(rxLock) {
            reassembler.clear()
            lastPage.clear()
        }
    }

    /**
     * Offers [wire] to the pages: the smaller of its `0x03`/`0x05` encodings, fragmented past [pageMax]. Null
     * when no encoding fits (past `FastFrameCodec.MAX_PARTS`, or unrepresentable) — the frame rides the links
     * and the flood alone, exactly as a too-big frame does on the Wi-Fi Aware plane.
     */
    internal fun offer(
        wire: WireEnvelope,
        env: RelayEnvelope,
        kind: SideCarousel.Kind,
        coalesceKey: String?,
    ): SideCarousel.Offer? {
        if (!live) return null
        val page = pageMax
        val best = FastFrameCodec.encodeBest(wire, transcode = true) ?: return null
        if (best.transcodeRefused) metrics.onTranscodeFallback()
        val parts =
            if (best.frame.size <= page) {
                listOf(best.frame)
            } else {
                FastFrameCodec.fragment(best.frame, page, fragSeq.getAndIncrement() and FRAG_ID_MASK) ?: return null
            }
        val result =
            synchronized(lock) {
                val r = carousel.offer(parts, kind, coalesceKey, SideCarousel.frameKey(wire, env))
                if (r == SideCarousel.Offer.QUEUED || r == SideCarousel.Offer.REPLACED) {
                    metrics.onBleSideOffered()
                    applyLocked()
                }
                r
            }
        log("ble-side offer ${env.type} id=${env.id} bytes=${best.frame.size} parts=${parts.size} → $result")
        return result
    }

    /**
     * Applies the scan tier the policy wants. Off is immediate; a start (a tier change, or the periodic
     * restart of a long-running scan) waits out the shared start budget — the caller's loop re-asks.
     */
    internal fun applyScanTier(wanted: SideScanPolicy.Tier) {
        synchronized(lock) {
            if (wanted == SideScanPolicy.Tier.Off) {
                stopScanLocked()
            } else if (live) {
                startScanLocked(wanted)
            }
        }
    }

    /** Holds [lock]. */
    private fun stopScanLocked() {
        if (tier == SideScanPolicy.Tier.Off) return
        scanner.stop()
        tier = SideScanPolicy.Tier.Off
        log("ble-side rx → Off")
    }

    /** Holds [lock]: a tier change or the periodic restart, if the shared start budget allows one now. */
    private fun startScanLocked(wanted: SideScanPolicy.Tier) {
        val t = now()
        val periodic = tier == wanted && SideScanPolicy.dueRestart(scanStartedAt, t)
        if ((tier == wanted && !periodic) || !SideScanPolicy.mayRestart(lastScanStartAt, t)) return
        scanner.stop()
        scanner.start(if (wanted == SideScanPolicy.Tier.Balanced) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER)
        lastScanStartAt = t
        if (scanner.isScanning) {
            scanStartedAt = t
            tier = wanted
            log("ble-side rx → $wanted${if (periodic) " (periodic restart)" else ""}")
        } else {
            tier = SideScanPolicy.Tier.Off
        }
    }

    /** One grep-stable fragment for the transport's periodic state line. */
    fun diag(): String = "side=${if (live) "live" else "dark"} rx=$tier page=$pageMax onAir=${carousel.onAir()} queued=${carousel.queued()}"

    // --- Transmit ---

    /** Holds [lock]: advances the carousel, pushes what moved to the sets, re-arms the timer. */
    private fun applyLocked() {
        val t = now()
        for (i in slots.indices) {
            if (slotRetryAt[i] != 0L && t >= slotRetryAt[i]) {
                slotRetryAt[i] = 0L
                carousel.setSlotUsable(i, true)
            }
        }
        carousel.tick()
        val pages = carousel.pages()
        for (i in pages.indices) {
            val bytes = pages[i]
            if (bytes === pushed[i]) continue
            pushed[i] = bytes
            if (bytes == null) {
                slots[i].stop()
            } else {
                slots[i].update(bytes)
                metrics.onBleSidePartAired()
            }
        }
        rescheduleLocked()
    }

    /**
     * Enables again every slot with a page on the air (ADR 2026-10.9utz, amendment 2026-10-09). The stack pauses all
     * advertising sets around each connection and resumes them in one HCI enable with callbacks off, so a refused
     * resume (0x0d) leaves a slot dark while the carousel counts its page as aired, and only the next pause and resume
     * that happens to land would bring it back. The transport calls this on the presence set's turns (`advertLoop`'s
     * settle after an ACL edge, its net, a heal), after the presence and Coded sets, each set its own enable.
     *
     * A slot is raised only while the channel is [live] and [pushed] holds its page. A slot the carousel took down, and
     * one sitting out a refused start ([SlotReaction.SitOut]), was also [BleAdvertiser.stop]ped, which keeps it down by
     * itself. A slot whose start failed but whose reaction has not landed yet may be started again here; the reaction
     * stops it when it lands ([onSlotStatus]). Returns how many slots it asked the stack about.
     */
    fun reassert(): Int = synchronized(lock) { reassertLocked { true } }

    /** Re-enables only the slots whose refused enable is due its retry ([keepers]' doubling wait). */
    fun retryRefused(): Int =
        synchronized(lock) {
            val t = now()
            reassertLocked { keepers[it].isDue(t, linked = true) }
        }

    /** How long until a slot's refused enable is due its retry ([retryRefused]); a very long wait while none is. */
    fun retryWaitMs(): Long {
        val t = now()
        return keepers.minOf { it.waitMs(t, linked = true) }
    }

    /** Holds [lock]: enables each [due] slot with a page on the air, and spends the retry of every [due] one. */
    private inline fun reassertLocked(due: (Int) -> Boolean): Int {
        val t = now()
        var asked = 0
        for (i in slots.indices) {
            if (!due(i)) continue
            if (live && pushed[i] != null && slots[i].reassert()) asked++
            keepers[i].reasserted(t) // spent even with nothing to air, or a stale retry would spin the caller's loop
        }
        return asked
    }

    /**
     * Every enable outcome the stack reports for a slot: our own [reassert]s (a page set is non-connectable, so the
     * stack's terminated-set re-enable, the one resume that calls back, never happens to it). Runs on a binder thread
     * holding the slot's own lock, so it touches only the slot's keeper and never [lock] — the order everywhere else is
     * [lock], then the slot.
     */
    private fun onSlotEnabled(
        index: Int,
        enabled: Boolean,
        status: Int,
    ) {
        val t = now()
        if (enabled && status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            keepers[index].onEnabled(t)?.let { log("ble-side slot$index advert enabled again (refused for ${it}ms)") }
            return
        }
        val what = if (enabled) "refused $status" else "disabled"
        keepers[index].onRefused(t)?.let { log("ble-side slot$index advert $what, retry in ${it}ms") }
        listener?.onAdvertRefused()
    }

    /** Holds [lock]. */
    private fun rescheduleLocked() {
        timer?.cancel()
        timer = null
        var at = carousel.nextDeadlineMs()
        if (carousel.queued() > 0) {
            for (i in slots.indices) if (slotRetryAt[i] != 0L) at = at?.let { minOf(it, slotRetryAt[i]) } ?: slotRetryAt[i]
        }
        val deadline = at ?: return
        val wait = (deadline - now()).coerceAtLeast(0L)
        timer =
            scope.launch {
                // stretches: the carousel's next turn; the controller airs the current page while the phone sleeps (ADR 2026-09.sjaa).
                delay(wait)
                synchronized(lock) { if (live) applyLocked() }
            }
    }

    /**
     * A slot's start outcome. The reaction runs off the caller's thread: the stack reports a start on a binder thread
     * holding the slot's own lock, and everything else here takes [lock] first and the slot second ([applyLocked],
     * [reassert]), so taking [lock] inside the callback could deadlock the two. Two things follow from running late: a
     * reaction from an earlier session ([generation]) is dropped, and a reaction that stands down a slot stops its set
     * rather than only forgetting it, because the slot may have been started again, and succeeded, in between.
     */
    private fun onSlotStatus(
        index: Int,
        status: Int,
    ) {
        val reaction = slotReaction(status)
        if (reaction == SlotReaction.None) return
        val gen = generation
        scope.launch { react(index, status, reaction, gen) }
    }

    private fun react(
        index: Int,
        status: Int,
        reaction: SlotReaction,
        gen: Int,
    ) {
        when (reaction) {
            SlotReaction.None -> {}

            SlotReaction.Dark -> {
                synchronized(lock) {
                    if (gen != generation) return
                    goDarkLocked("advertising set refused: feature unsupported")
                }
            }

            SlotReaction.HalvePage -> {
                // The controller's maximum lied, or a vendor reserves header room: halve the page and start over
                // (the queued parts were cut for the old size; the flood carries them).
                synchronized(lock) {
                    if (gen != generation || !live) return
                    pageMax /= 2
                    if (pageMax < MIN_PAGE_BYTES) {
                        goDarkLocked("page maximum too small after DATA_TOO_LARGE")
                        return
                    }
                    // Every set goes down with the pages it was told to air: a slot left up here would air a page
                    // nothing tracks any more, and hold a controller set.
                    slots.forEach { it.stop() }
                    carousel.clear()
                    pushed.fill(null)
                    applyLocked()
                }
                log("ble-side page → $pageMax after DATA_TOO_LARGE")
            }

            // TOO_MANY_ADVERTISERS (the controller's set budget is device-wide), ALREADY_STARTED, INTERNAL_ERROR:
            // this slot sits out for a while; the others keep going.
            SlotReaction.SitOut -> {
                synchronized(lock) {
                    if (gen != generation || !live) return
                    // Stopped, not only forgotten: the slot may have been started again, and succeeded, since it failed.
                    slots[index].stop()
                    carousel.setSlotUsable(index, false)
                    pushed[index] = null
                    slotRetryAt[index] = now() + SLOT_RETRY_MS
                    applyLocked()
                }
                log("ble-side slot$index down for ${SLOT_RETRY_MS / MS_PER_S}s: status $status")
            }
        }
    }

    private fun goDark(reason: String) {
        synchronized(lock) { goDarkLocked(reason) }
    }

    /** Holds [lock]. */
    private fun goDarkLocked(reason: String) {
        if (!live) return
        live = false
        timer?.cancel()
        timer = null
        slots.forEach { it.stop() }
        scanner.stop()
        tier = SideScanPolicy.Tier.Off
        carousel.clear()
        pushed.fill(null)
        log("ble-side dark: $reason")
        listener?.onAvailabilityChanged()
    }

    // --- Receive ---

    private fun onScanFailed(code: Int) {
        synchronized(lock) { tier = SideScanPolicy.Tier.Off } // the loop re-applies after the start gap
        if (code == ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) goDark("extended scan unsupported")
    }

    private fun onScanResult(result: ScanResult) {
        val data = result.scanRecord?.getServiceData(BleConstants.SIDE_SERVICE_UUID) ?: return
        if (data.isEmpty()) return
        val from = result.device?.address ?: return
        synchronized(rxLock) {
            val hash = data.contentHashCode()
            if (lastPage[from] == hash) {
                metrics.onBleSideDeduped()
                return
            }
            lastPage[from] = hash
            when (data[0]) {
                FastFrameCodec.TAG_COMPACT, FastFrameCodec.TAG_TRANSCODED -> {
                    onFrameBytes(data)
                }

                FastFrameCodec.TAG_FRAG -> {
                    val frag = FastFrameCodec.parseFragment(data)
                    if (frag == null) {
                        metrics.onBleSideDropped(BleSideDrop.DECODE_FAILED)
                        return
                    }
                    val whole = reassembler.accept(frag.fragId, frag) ?: return
                    // Anti-recursion, as on the Wi-Fi Aware plane: a set must assemble into a complete tagged frame.
                    if (whole.isEmpty() || !FastFrameCodec.isFrameTag(whole[0])) {
                        metrics.onBleSideDropped(BleSideDrop.DECODE_FAILED)
                        return
                    }
                    metrics.onBleSideReassembled()
                    onFrameBytes(whole)
                }

                else -> {
                    metrics.onBleSideDropped(BleSideDrop.UNKNOWN_TAG)
                }
            }
        }
    }

    /** Holds [rxLock]. */
    private fun onFrameBytes(bytes: ByteArray) {
        val wire = FastFrameCodec.decodeCompact(bytes)
        val env = wire?.let { WireCodec.decodeEnvelope(it.signed) }
        if (wire == null || env == null) {
            metrics.onBleSideDropped(BleSideDrop.DECODE_FAILED)
            return
        }
        if (!seen.add(SideCarousel.frameKey(wire, env))) {
            metrics.onBleSideDeduped()
            return
        }
        metrics.onBleSideHeard()
        listener?.onFrame(wire, env)
    }

    /** What a page slot's advertising-set status asks of the channel ([slotReaction]). */
    internal enum class SlotReaction { None, Dark, HalvePage, SitOut }

    companion object {
        /**
         * The page a controller whose largest extended advertisement is [maxData] bytes can carry: [PAGE_BYTES] at
         * most, less the AD header ([AD_OVERHEAD]). Under [MIN_PAGE_BYTES] (a legacy-only controller reports 0) the
         * channel stays dark.
         */
        internal fun pageFor(maxData: Int): Int = minOf(PAGE_BYTES, maxData - AD_OVERHEAD)

        /**
         * What a page slot's [status] asks: nothing on success; dark for good when the controller refuses the feature;
         * a halved page when it says the data is too large (its maximum lied, or a vendor reserves header room); and
         * any other refusal (TOO_MANY_ADVERTISERS, the set budget being device-wide; ALREADY_STARTED; INTERNAL_ERROR)
         * sits that slot out for [SLOT_RETRY_MS] while the others keep going.
         */
        internal fun slotReaction(status: Int): SlotReaction =
            when (status) {
                AdvertisingSetCallback.ADVERTISE_SUCCESS -> SlotReaction.None
                AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> SlotReaction.Dark
                AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> SlotReaction.HalvePage
                else -> SlotReaction.SitOut
            }

        /**
         * Service-data bytes one page may carry: what fits one AUX_ADV_IND (255 B PDU, ~10 B extended header,
         * 4 B AD header) with room for a vendor's AuxPtr reservation — so a page is never chained, and a
         * `setAdvertisingData` on a live set is always one complete HCI operation. The measured sealed tick
         * (221 B) and reaction (229 B) ride one page; a post, a profile, a coalesced tick ride two.
         */
        const val PAGE_BYTES = 236

        /** The 16-bit service-data AD structure's header: length, type, two UUID bytes. */
        const val AD_OVERHEAD = 4

        /** Below this a controller cannot carry even the tick in `FastFrameCodec.MAX_PARTS` parts: stay dark. */
        const val MIN_PAGE_BYTES = 128

        /** A refused slot sits out this long before the carousel offers it work again. */
        const val SLOT_RETRY_MS = 60_000L

        /** Parts of one frame air for a dwell each (sequentially on one slot), so the set may span a minute. */
        const val FRAG_TIMEOUT_MS = 60_000L

        private const val FRAG_CAPACITY = 8
        private const val FRAG_ID_MASK = 0xFFFF
        private const val HEARD_MAX = 512
        private const val HEARD_TTL_MS = 10 * 60_000L
        private const val LAST_PAGE_MAX = 64
        private const val MS_PER_S = 1_000L

        // A slot's keeper has no net: the presence set's turns are it, so its period never comes due.
        private const val NO_NET_MS = Long.MAX_VALUE / 2
    }
}
