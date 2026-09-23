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
            metrics.onBleSideDropped(
                when (drop) {
                    SideCarousel.Drop.STALE -> BleSideDrop.STALE
                    SideCarousel.Drop.OVERFLOW -> BleSideDrop.OVERFLOW
                },
            )
        }
    private val slots: List<BleAdvertiser> =
        List(config.slots) { i ->
            BleAdvertiser(
                advertiserProvider = { adapter?.bluetoothLeAdvertiser },
                log = { log("slot$i: $it") },
                serviceUuid = BleConstants.SIDE_SERVICE_UUID,
                params = BleAdvertiser.sideParams(),
                onStartStatus = { status -> onSlotStatus(i, status) },
            )
        }

    // What each set was last told to air (by reference — the carousel hands back the same part objects).
    private val pushed = arrayOfNulls<ByteArray>(config.slots)
    private val slotRetryAt = LongArray(config.slots)
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
        val page = minOf(PAGE_BYTES, maxData - AD_OVERHEAD)
        synchronized(lock) {
            pageMax = page
            live = extended && page >= MIN_PAGE_BYTES
            carousel.clear()
            pushed.fill(null)
            for (i in slots.indices) carousel.setSlotUsable(i, true)
        }
        synchronized(rxLock) {
            reassembler.clear()
            lastPage.clear()
        }
        log("ble-side bring-up extAdv=$extended maxAdvData=$maxData page=$page live=$live")
    }

    /** Radio down (adapter off, transport stop): every set and the scan go down; the seen window is kept. */
    fun tearDown() {
        synchronized(lock) {
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
                delay(wait)
                synchronized(lock) { if (live) applyLocked() }
            }
    }

    private fun onSlotStatus(
        index: Int,
        status: Int,
    ) {
        if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) return
        when (status) {
            AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> {
                goDark("advertising set refused: feature unsupported")
            }

            AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> {
                // The controller's maximum lied, or a vendor reserves header room: halve the page and start over
                // (the queued parts were cut for the old size; the flood carries them).
                synchronized(lock) {
                    pageMax /= 2
                    if (pageMax < MIN_PAGE_BYTES) {
                        goDarkLocked("page maximum too small after DATA_TOO_LARGE")
                        return
                    }
                    carousel.clear()
                    pushed.fill(null)
                    applyLocked()
                }
                log("ble-side page → $pageMax after DATA_TOO_LARGE")
            }

            // TOO_MANY_ADVERTISERS (the controller's set budget is device-wide), ALREADY_STARTED, INTERNAL_ERROR:
            // this slot sits out for a while; the others keep going.
            else -> {
                synchronized(lock) {
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

    companion object {
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
    }
}
