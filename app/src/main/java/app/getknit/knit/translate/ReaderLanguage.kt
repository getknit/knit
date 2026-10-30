package app.getknit.knit.translate

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * This phone's reader's language as it stands ([ReaderLanguages]): the choice in Settings, else what they write, else
 * the system's list. Their own messages are read through the identifier at most once per [TTL_MS], in memory only;
 * [ownBodies] reads their newest sent messages, [read] is the identifier over one, [preferred] the system's language tags.
 */
class ReaderLanguage(
    private val ownBodies: suspend (limit: Int) -> List<String>,
    private val read: suspend (String) -> LanguagePick,
    private val preferred: () -> List<String>,
    private val clock: () -> Long,
    // The language this person chose for the app itself, empty when they chose none (or before Android 13).
    private val perApp: () -> List<String> = { emptyList() },
) {
    private val mutex = Mutex()
    private var own: Pair<Long, List<LanguagePick>>? = null

    /** The estimate with [override] (the Settings code, null for Automatic). */
    suspend fun estimate(override: String?): ReaderLanguages.Estimate {
        val picks =
            mutex.withLock { own?.takeIf { clock() - it.first < TTL_MS }?.second }
                ?: ownBodies(ReaderLanguages.OWN_SAMPLE).map { read(it) }.also { mutex.withLock { own = clock() to it } }
        return ReaderLanguages.estimate(picks, preferred(), override)
    }

    /** What this reader declares reading on what they send ([ReaderLanguages.declaredReads]), with [override]. */
    fun declaredReads(override: String?): String? = ReaderLanguages.declaredReads(override, perApp(), preferred())

    companion object {
        const val TTL_MS = 10 * 60_000L
    }
}
