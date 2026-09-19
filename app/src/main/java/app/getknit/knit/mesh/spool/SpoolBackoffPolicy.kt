package app.getknit.knit.mesh.spool

/**
 * How long a relay worker waits before dialling again, by how many sessions in a row ended without a
 * completed hello. Pure, like the Bluetooth plane's `ConnectBackoffPolicy`, so the curve is a table test.
 *
 * Two tiers. The first is the one the spec's C-7.1-11 clients have always run: a second, doubling to a
 * minute — a relay that hiccups is back within seconds, a captive Wi-Fi that swallows the socket is left
 * alone within a couple of tries. The second is for a relay that is simply gone: after [LONG_TIER_AFTER]
 * failures (about six minutes at the ceiling) the wait doubles on from a minute to [LONG_BACKOFF_MS], because
 * ~1,440 dials a day — each a DNS lookup, a TCP connect and a TLS handshake on a radio that could sleep —
 * bought nothing the ~100 the long tier still makes would not. Both ends of the curve reset the same way:
 * a reached session, or a new validated network (`ScopeSync.onRouteChanged`). A spool's `Retry-After` is
 * applied by the caller as a floor on top, never in place of this.
 */
object SpoolBackoffPolicy {
    /** The wait after [failures] consecutive unreached sessions; zero failures is the reconnect after a clean session. */
    fun waitMs(failures: Int): Long =
        when {
            failures <= 0 -> MIN_BACKOFF_MS
            failures <= LONG_TIER_AFTER -> (MIN_BACKOFF_MS shl failures.coerceAtMost(MAX_SHIFT)).coerceAtMost(MAX_BACKOFF_MS)
            else -> (MAX_BACKOFF_MS shl (failures - LONG_TIER_AFTER).coerceAtMost(MAX_SHIFT)).coerceAtMost(LONG_BACKOFF_MS)
        }

    const val MIN_BACKOFF_MS = 1_000L
    const val MAX_BACKOFF_MS = 60_000L
    const val LONG_BACKOFF_MS = 15 * 60_000L
    const val LONG_TIER_AFTER = 10

    // 1 s shl 16 already dwarfs the ceiling; bounding the shift keeps the Long from wrapping on a huge streak.
    private const val MAX_SHIFT = 16
}
