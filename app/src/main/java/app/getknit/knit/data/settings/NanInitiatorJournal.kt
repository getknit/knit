package app.getknit.knit.data.settings

/**
 * Whether this build on this ROM has given up **initiating** Wi-Fi Aware data paths, and when it last probed.
 *
 * @param stamp the app version code plus the OS build fingerprint under which the role was held, `""` when
 *   it is not. A new app version may ship a different data-path sequence and a flashed ROM may fix the
 *   firmware, so either half re-arms on its own — the same key [NanAttachJournal] uses for the attach give-up.
 * @param probedAt wall-clock epoch millis of the last initiate a held role was allowed (the daily probe), so
 *   a restart does not hand the next process a probe at once. 0 when not held.
 */
data class NanInitiatorLatch(
    val stamp: String,
    val probedAt: Long,
) {
    companion object {
        /** The role is open. */
        val NONE = NanInitiatorLatch(stamp = "", probedAt = 0L)
    }
}

/**
 * What survives a process death about this phone's Wi-Fi dropping whenever it starts a Wi-Fi Aware data path
 * (`mesh/wifiaware/NanInitiatorPolicy`, work item #78). The two-method slice of [SettingsStore] that
 * `WifiAwareTransport` reads once at start and writes on each transition — the [NanAttachJournal] shape, for
 * the same reason: `MeshService` is `START_STICKY`, so without it a fresh process re-learns the hold by
 * dropping the user's Wi-Fi three more times.
 *
 * Both fields go down in one `edit {}`, so a reader never sees a stamp without its probe time. The strikes
 * that lead up to the hold are deliberately not here: they are memory-only, and the transport is the single
 * writer of this record — Diagnostics' "Try again" reaches it through `MeshTransport.releaseInitiatorHold`,
 * never by writing the journal itself.
 */
interface NanInitiatorJournal {
    /** The hold as last written, or [NanInitiatorLatch.NONE]. */
    suspend fun initiatorLatch(): NanInitiatorLatch

    /** Records [latch]; [NanInitiatorLatch.NONE] re-arms the role. */
    suspend fun setInitiatorLatch(latch: NanInitiatorLatch)
}
