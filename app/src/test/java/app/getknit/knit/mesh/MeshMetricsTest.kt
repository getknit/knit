package app.getknit.knit.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class MeshMetricsTest {
    @Test
    fun `a fresh snapshot is all zero with no per-reason buckets`() {
        val snap = MeshMetrics().snapshot()
        assertEquals(0L, snap.framesOriginated)
        assertEquals(0L, snap.framesDropped)
        assertEquals(emptyMap<DropReason, Long>(), snap.dropsByReason)
        assertEquals(emptyMap<ConnectFailReason, Long>(), snap.btConnectFailsByReason)
        assertEquals(0L, snap.nanServesPeak)
        assertEquals(emptyMap<FastPathDrop, Long>(), snap.fastDropsByReason)
    }

    @Test
    fun `fast-path counters surface in the snapshot and omit zero drop buckets`() {
        val metrics = MeshMetrics()
        metrics.onFastCompactSent()
        repeat(2) { metrics.onFastLegacySent() }
        metrics.onFastFragSent()
        metrics.onFastReassembled()
        metrics.onFastTooBig()
        metrics.onFastTranscodedSent()
        repeat(3) { metrics.onTranscodeFallback() }
        repeat(2) { metrics.onFastDropped(FastPathDrop.FRAG_TIMEOUT) }
        metrics.onFastDropped(FastPathDrop.UNKNOWN_TAG)

        val snap = metrics.snapshot()
        assertEquals(1L, snap.fastCompactSent)
        assertEquals(2L, snap.fastLegacySent)
        assertEquals(1L, snap.fastFragSent)
        assertEquals(1L, snap.fastReassembled)
        assertEquals(1L, snap.fastTooBig)
        assertEquals(1L, snap.fastTranscodedSent)
        assertEquals(3L, snap.transcodeFallbacks)
        assertEquals(
            mapOf(FastPathDrop.FRAG_TIMEOUT to 2L, FastPathDrop.UNKNOWN_TAG to 1L),
            snap.fastDropsByReason,
        )
    }

    @Test
    fun `counters surface in the snapshot`() {
        val metrics = MeshMetrics()
        repeat(3) { metrics.onOriginated() }
        metrics.onDelivered()
        metrics.onRelayed()
        metrics.onBytesSent(128)
        metrics.onBytesSent(64)
        metrics.onKeyServed()

        val snap = metrics.snapshot()
        assertEquals(3L, snap.framesOriginated)
        assertEquals(1L, snap.framesDelivered)
        assertEquals(1L, snap.framesRelayed)
        assertEquals(192L, snap.bytesSent)
        assertEquals(1L, snap.keysServed)
    }

    @Test
    fun `dropsByReason sums the total and omits zero buckets`() {
        val metrics = MeshMetrics()
        metrics.onDropped(DropReason.DECODE_FAILED)
        metrics.onDropped(DropReason.DECODE_FAILED)
        metrics.onDropped(DropReason.SIG_INVALID)

        val snap = metrics.snapshot()
        assertEquals(3L, snap.framesDropped)
        assertEquals(
            mapOf(DropReason.DECODE_FAILED to 2L, DropReason.SIG_INVALID to 1L),
            snap.dropsByReason,
        )
    }

    @Test
    fun `btConnectFailsByReason sums the total and omits zero buckets`() {
        val metrics = MeshMetrics()
        metrics.onBtConnectFailed(ConnectFailReason.TIMEOUT)
        metrics.onBtConnectFailed(ConnectFailReason.RADIO)
        metrics.onBtConnectFailed(ConnectFailReason.RADIO)

        val snap = metrics.snapshot()
        assertEquals(3L, snap.btConnectFails)
        assertEquals(
            mapOf(ConnectFailReason.TIMEOUT to 1L, ConnectFailReason.RADIO to 2L),
            snap.btConnectFailsByReason,
        )
    }

    @Test
    fun `onNanServes keeps the session peak, not the last value`() {
        val metrics = MeshMetrics()
        metrics.onNanServes(3)
        metrics.onNanServes(5)
        metrics.onNanServes(2)
        assertEquals(5L, metrics.snapshot().nanServesPeak)
    }

    @Test
    fun `onFileSent splits by transport plane`() {
        val metrics = MeshMetrics()
        metrics.onFileSent(TransportKind.WifiAware)
        metrics.onFileSent(TransportKind.WifiAware)
        metrics.onFileSent(TransportKind.Bluetooth)
        metrics.onFileSent(TransportKind.Other)

        val snap = metrics.snapshot()
        assertEquals(2L, snap.filesSentNan)
        assertEquals(1L, snap.filesSentBt)
    }

    @Test
    fun `lora counters surface in the snapshot`() {
        val metrics = MeshMetrics()
        metrics.onLoraSessionUp()
        repeat(3) { metrics.onLoraSent() }
        metrics.onLoraFragSent()
        repeat(2) { metrics.onLoraReceived() }
        metrics.onLoraReassembled()
        metrics.onLoraTooBig()
        metrics.onLoraTranscoded()
        metrics.onLoraPadded()
        metrics.onLoraDroppedQueue()
        metrics.onLoraAirtimeHeld("BRIDGE")
        metrics.onLoraAirtimeHeld("BRIDGE")
        metrics.onLoraAirtimeHeld("LIVE")
        metrics.onLoraSuppressed()
        metrics.onLoraNak()
        metrics.onFileSent(TransportKind.LoRa) // no-op: LoRa carries no files

        val snap = metrics.snapshot()
        assertEquals(1L, snap.loraSessionUps)
        assertEquals(3L, snap.loraSent)
        assertEquals(1L, snap.loraFragSent)
        assertEquals(2L, snap.loraReceived)
        assertEquals(1L, snap.loraReassembled)
        assertEquals(1L, snap.loraTooBig)
        assertEquals(1L, snap.loraTranscoded)
        assertEquals(1L, snap.loraPadded)
        assertEquals(1L, snap.loraDroppedQueue)
        // Held is not dropped: the two must never be read as one number, which is the point of the split.
        assertEquals(3L, snap.loraAirtimeHeld)
        assertEquals(mapOf("BRIDGE" to 2L, "LIVE" to 1L), snap.loraAirtimeHeldByBucket)
        assertEquals(1L, snap.loraSuppressed)
        assertEquals(1L, snap.loraNak)
    }
}
