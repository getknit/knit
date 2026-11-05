package app.getknit.knit.translate

import app.getknit.knit.data.message.OwnSend
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * This phone's reader's language as it stands ([ReaderLanguages]): the choice in Settings, else the system's list
 * unless it is English, else what they write, else English. Their own messages are read through the identifier at
 * most once per [TTL_MS], in memory only, and held only once they name a language they write
 * ([ReaderLanguages.MIN_WRITTEN] picks of it): read before that (a fresh install, a process started before its first
 * send), they are read again at the next ask, or what this person writes would count for nothing for ten minutes.
 * [ownSends] reads their newest sent messages, [read] is the identifier over one body, [preferred] the system's
 * language tags. A message whose body is the composer's translation of the draft counts as the draft's language,
 * never the body's ([ReaderLanguages.ownPick], the 2026-11-02 amendment).
 */
class ReaderLanguage(
    private val ownSends: suspend (limit: Int) -> List<OwnSend>,
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
        val held = mutex.withLock { own?.takeIf { clock() - it.first < TTL_MS }?.second }
        val picks = held ?: ownSends(ReaderLanguages.OWN_SAMPLE).map { ReaderLanguages.ownPick(it.draftTranslatedFrom) { read(it.body) } }
        val estimate = ReaderLanguages.estimate(picks, preferred(), override)
        if (held == null) {
            val settled = estimate.written.values.any { it >= ReaderLanguages.MIN_WRITTEN }
            mutex.withLock { own = if (settled) clock() to picks else null }
        }
        return estimate
    }

    /** What this reader declares reading on what they send ([ReaderLanguages.declaredReads]), with [override]. */
    fun declaredReads(override: String?): String? = ReaderLanguages.declaredReads(override, perApp(), preferred())

    companion object {
        const val TTL_MS = 10 * 60_000L
    }
}
