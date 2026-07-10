package app.getknit.knit.mesh.link

/**
 * Pure, JVM-testable feed-pacing for a streamed file over a link — the average byte-rate limiter that keeps a
 * bulk transfer from monopolising a slow, shared channel.
 *
 * [FramedLink.streamFile] already interleaves live frames between file chunks, but on the Bluetooth **L2CAP CoC**
 * plane that interleaving is defeated below the app layer: `BluetoothSocket.getOutputStream().write()` buffers
 * into the local socket → BT-stack TX queue and returns, so the writer dumps a whole small blob into that queue
 * in a burst — a frame enqueued *afterwards* lands behind the entire file and only reaches the wire once it
 * drains ("text arrives only when the transfer completes"), and the saturated ACL starves the reverse direction
 * too. Pacing the file feed to ~link capacity keeps that queue shallow: interleaved frames sit near the wire
 * head and the freed connection-event budget carries reverse traffic.
 *
 * Kept free of Android and of a clock (the caller stamps `elapsedMs` from the link's injected `now`), like
 * [app.getknit.knit.mesh.bluetooth.ConnectBackoffPolicy], so the curve is asserted with the same unit-test
 * style. The Wi-Fi Aware NDP socket is fast and must not be throttled, so it runs unbounded
 * ([PaceConfig.bytesPerSec] ≤ 0).
 */
object TransferPacePolicy {
    /**
     * Milliseconds to wait before feeding the next chunk so the average feed rate holds at or below
     * [config].bytesPerSec, given [bytesSent] fed so far and [elapsedMs] since the transfer began. Returns 0
     * when unbounded ([config].bytesPerSec ≤ 0) or when the feed is still under budget (we've sent fewer bytes
     * than the elapsed time allows) — a delay is only imposed once the feed runs ahead of the target rate.
     */
    fun delayMs(
        bytesSent: Long,
        elapsedMs: Long,
        config: PaceConfig,
    ): Long {
        if (config.bytesPerSec <= 0) return 0 // unbounded (the NAN / default path)
        val targetMs = bytesSent * MS_PER_SEC / config.bytesPerSec
        return (targetMs - elapsedMs).coerceAtLeast(0L)
    }

    private const val MS_PER_SEC = 1000L
}

/** Tunable for [TransferPacePolicy]. */
data class PaceConfig(
    /**
     * Target average feed rate in bytes/second. **≤ 0 means unbounded** (no pacing) — the default, so a link
     * that doesn't opt in (Wi-Fi Aware) and every existing caller is unaffected. A positive value is the BLE
     * cap, chosen below measured L2CAP CoC throughput to leave reverse-direction headroom.
     */
    val bytesPerSec: Int = 0,
)
