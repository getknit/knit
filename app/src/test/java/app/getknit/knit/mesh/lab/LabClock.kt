package app.getknit.knit.mesh.lab

import java.util.concurrent.ConcurrentHashMap

/**
 * One calendar for the whole lab: the wall clock plus a jump every node shares, so the nodes always agree
 * with each other — the property `spaced {}` and ADR 2026-09.gdhp rely on ("the wall clock every peer
 * shares") — while a scenario can move the date past a floor the field waits minutes or days for (the
 * router's 10-min seen window, the 15-min seed floor, the 24 h custody TTL, the 48 h intro grace).
 *
 * A jump moves *decisions* — expiry, floors, last-writer-wins, tombstones, presence, the pipes' crossing memo —
 * never *schedulers*: every periodic loop is `delay()`-based and blind to it, so a scenario that jumped pokes the
 * work itself (`LabNode.heal()`, `sweepLocalStorage()`, a re-link or `reoffer()` for the digest exchange, a fresh
 * send). Forward only:
 * `SeenSet`, the LoRa plane's restored state and the profile reflood floor all assume time never runs back.
 *
 * [skew] is the one seam to a *disagreeing* clock, for a scenario that originates a far-future frame: a
 * skew past `Protocol.MAX_FUTURE_SKEW_MS` is exactly what trips the custody refusal and `clampFuture`.
 */
class LabClock {
    @Volatile
    private var offsetMs = 0L
    private val skews = ConcurrentHashMap<String, Long>()

    /** The shared reading. */
    fun now(): Long = System.currentTimeMillis() + offsetMs

    /** Moves the calendar forward for every node at once. */
    fun advance(ms: Long) {
        require(ms >= 0) { "the lab calendar only moves forward" }
        offsetMs += ms
    }

    /** A node's own reading: [now] plus whatever [skew] gave it (nothing, for every node by default). */
    fun forNode(name: String): () -> Long = { now() + (skews[name] ?: 0L) }

    /**
     * Puts one node's clock [ms] ahead of everyone else's. A skew changed while the node's links are up moves its
     * pipes' crossing memo apart from the far routers' seen windows: re-link it before a jump that relies on the two
     * lapsing together.
     */
    fun skew(
        name: String,
        ms: Long,
    ) {
        skews[name] = ms
    }
}
