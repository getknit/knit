package app.getknit.knit.mesh.bluetooth

/**
 * When a Bluetooth link asks for a short connection interval, and when it has one (#120). A link's drain is the
 * host's share of the controller's buffers times the interval: the Pixel 9 keeps three packets of a link in flight and
 * about two cross per connection event, so a link at 45 ms drained ~9 KB/s and the same link at 15 ms 22.5–29
 * (2026-10-06 trial). [BleLinkBoost] does the GATT; this decides. Pure, so the rules are a JVM test
 * ([app.getknit.knit.LinkBoostPolicyTest]).
 *
 * - **Either side asks, while its own screen is on.** A side whose link starts carrying a file either way asks for
 *   `CONNECTION_PRIORITY_HIGH` (and, once per link, an ATT MTU: Android 12 sends 27-byte PDUs until one is exchanged).
 *   The linger's wait does not wake a suspended CPU, so only a side whose screen is on may ask: its release is sure to
 *   go out. A receiver looking at the chat thus speeds up a sender in a pocket, which earns from the receiver's ask.
 * - **Held while either direction streams.** The side that asked lets go (`CONNECTION_PRIORITY_BALANCED`) once it has
 *   neither fed nor received for [LINGER_MS]. A side that goes ineligible (the screen off, Coded, the switch off) lets
 *   go at once, even under the peer's feed: a dark phone cannot be trusted to let go later, and a peer still streaming
 *   with its own screen on asks again.
 * - **Earned by what the link reports,** not by the ask: [earned] is set by a successful update at or under
 *   [EARNED_INTERVAL_US], whoever asked for it, and cleared by a slower one, by this side's release, or by losing the
 *   client that heard it ([forget]). Android has no getter for the interval, so a link never heard stays unearned and
 *   keeps the base budget.
 *
 * A report of an update that failed (`0x2A`, two asks colliding) says the link did not move: it counts for nothing,
 * and the settle check answers a link that stays slow. A release is asked again only while no report has answered it
 * and nothing streams, and never once a file has started after it: so it never cuts a feed that began since, yet a
 * release made under the peer's feed is retried when that feed ends. Thread-safe: reports arrive on a
 * binder thread, the rest on the [BleLinkBoost] loop, and [earned] is read by the file feed.
 */
internal object LinkBoostPolicy {
    /** How long this side must neither feed nor receive before a boost it asked for is let go. */
    const val LINGER_MS = 15_000L

    /** How long after an ask the link must have reported, or it is asked again. */
    const val SETTLE_MS = 2_000L

    /** How many times one episode's ask (or one release) may be repeated, so a peer that moves the link is not fought. */
    const val MAX_REASKS = 2

    /** The slowest interval that earns the larger budget: what `CONNECTION_PRIORITY_HIGH` settled on in the trial. */
    const val EARNED_INTERVAL_US = 15_000

    /** The ATT MTU a boost asks for once per link (Android's maximum). */
    const val MTU = 517

    /**
     * Whether this side may listen to the link's parameters at all, so may hold a GATT client on it: the boost is on, the
     * peer is not an iPhone (its doorbell owns the parameters, ADR 2026-09.dqvb), and the link is not on Coded (its pace
     * sits outside the budget, and a 15 ms interval at S=8 is not what the trial measured). A dark screen still listens:
     * that is how a sender in a pocket earns the receiver's ask.
     */
    fun observes(
        enabled: Boolean,
        doorbell: Boolean,
        coded: Boolean,
    ): Boolean = enabled && !doorbell && !coded

    /**
     * Whether this side may ask: it [observes] the link, its screen is on, so its release cannot sleep, and no
     * Bluetooth audio streams (A2DP takes the air a 15 ms link would need, and a shorter interval contends with it).
     */
    fun eligible(
        enabled: Boolean,
        doorbell: Boolean,
        coded: Boolean,
        interactive: Boolean,
        audio: Boolean = false,
    ): Boolean = observes(enabled, doorbell, coded) && interactive && !audio

    enum class Ask { HIGH, BALANCED }

    /** What one step asks of the link: a priority, and whether the MTU goes with it. */
    data class Step(
        val ask: Ask? = null,
        val mtu: Boolean = false,
    ) {
        companion object {
            val NONE = Step()
        }
    }

    /** One link's boost. */
    class Boost(
        private val lingerMs: Long = LINGER_MS,
        private val settleMs: Long = SETTLE_MS,
        private val maxReasks: Int = MAX_REASKS,
    ) {
        private var feeding = false
        private var receiving = false
        private var eligible = false

        /** This side asked for HIGH and has not let go. */
        @Volatile
        var boosting = false
            private set

        @Volatile
        var earned = false
            private set

        private var mtuAsked = false
        private var askedAt: Long? = null
        private var reasks = 0
        private var idleSince: Long? = null
        private var releasedAt: Long? = null
        private var releaseReasks = 0
        private var releaseAnswered = false

        // The link was seen with nothing streaming since the release: a file after that is a new one, and ends it.
        private var idleSinceRelease = false

        private val busy get() = feeding || receiving

        /** The link's state as of [now]: called on every poke from the link, the screen or the PHY. */
        @Synchronized
        fun onState(
            feeding: Boolean,
            receiving: Boolean,
            eligible: Boolean,
            now: Long,
        ): Step {
            this.feeding = feeding
            this.receiving = receiving
            this.eligible = eligible
            if (releasedAt != null && !busy) idleSinceRelease = true
            // A file that started after the release ends it; one that was already streaming keeps its settle check, which
            // runs once that file ends (a release made under the peer's feed can fail, and nothing else would retry it).
            if (busy && !boosting && idleSinceRelease) releasedAt = null
            return when {
                !eligible && boosting -> release(now)
                eligible && busy && !boosting -> startBoost(now)
                else -> onTick(now) // a release's settle check runs on a dark screen too
            }
        }

        /** The link reported an update to [intervalUs] at [now], which [ok] says it made. Returns a re-ask when one is owed. */
        @Synchronized
        fun onParams(
            intervalUs: Int,
            ok: Boolean,
            now: Long,
        ): Step {
            if (!ok) return Step.NONE
            earned = intervalUs <= EARNED_INTERVAL_US
            // Slow, the release landed; fast, the peer asked since and holds it now. Either way it is not asked again.
            if (releasedAt != null) releaseAnswered = true
            // A put-back, or the peer letting go while this side still streams: ask again, a bounded number of times.
            return if (!earned && streamingBoost()) reask(now) else Step.NONE
        }

        /** The client that heard the link is gone: the next update would go unheard, so nothing heard before counts. */
        @Synchronized
        fun forget() {
            earned = false
        }

        /** Time passed: the settle check after an ask or a release, and the linger. */
        @Synchronized
        fun onTick(now: Long): Step = if (boosting) tickBoosting(now) else tickReleased(now)

        /** When [onTick] next has something to decide, or null when only a poke or a report can change anything. */
        @Synchronized
        fun dueAt(): Long? {
            if (!boosting) return releasedAt?.let { it + settleMs }?.takeIf { releaseUnanswered() }
            val linger = idleSince?.let { it + lingerMs }
            val settle = askedAt?.takeIf { askUnanswered() }?.let { it + settleMs }
            return listOfNotNull(linger, settle).minOrNull()
        }

        private fun tickBoosting(now: Long): Step {
            if (!eligible) return release(now)
            idleSince = if (busy) null else idleSince ?: now
            val lingered = idleSince?.let { now - it >= lingerMs } == true
            val settled = askedAt?.let { now - it >= settleMs } == true
            return when {
                lingered -> release(now)
                settled && askUnanswered() -> reask(now)
                else -> Step.NONE
            }
        }

        /** A release no report has answered yet is asked once more, unless the link has gone busy again. */
        private fun tickReleased(now: Long): Step {
            val settled = releasedAt?.let { now - it >= settleMs } == true
            if (!settled || !releaseUnanswered()) return Step.NONE
            releaseReasks += 1
            releasedAt = now
            return Step(Ask.BALANCED)
        }

        /** This side's boost while the link still streams and may be boosted. */
        private fun streamingBoost(): Boolean = boosting && busy && eligible

        /** The link has not shown the short interval since the ask, it still streams, and a re-ask is left. */
        private fun askUnanswered(): Boolean = !earned && busy && reasks < maxReasks

        /** No report has answered the release, nothing streams, and a re-ask is left. */
        private fun releaseUnanswered(): Boolean = !releaseAnswered && !busy && releaseReasks < maxReasks

        private fun startBoost(now: Long): Step {
            boosting = true
            askedAt = now
            reasks = 0
            idleSince = null
            releasedAt = null
            val mtu = !mtuAsked
            mtuAsked = true
            return Step(Ask.HIGH, mtu)
        }

        private fun reask(now: Long): Step {
            if (reasks >= maxReasks) {
                askedAt = null // spent: the link keeps what it has, and the base budget while it is slow
                return Step.NONE
            }
            reasks += 1
            askedAt = now
            return Step(Ask.HIGH)
        }

        private fun release(now: Long): Step {
            boosting = false
            earned = false // the pace drops at its next chunk, ahead of the update
            askedAt = null
            idleSince = null
            releasedAt = now
            releaseReasks = 0
            releaseAnswered = false
            idleSinceRelease = !busy
            return Step(Ask.BALANCED)
        }
    }
}
