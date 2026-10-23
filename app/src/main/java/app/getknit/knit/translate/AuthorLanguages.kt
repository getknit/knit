package app.getknit.knit.translate

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The language each author writes in, per chat (ADR 2026-10.t2h6's short-line amendment): read from their newest
 * [SAMPLE] messages there that the identifier was sure of, and a majority of at least [MIN_PICKS] of them. Per chat,
 * because a person may write English in one group and Spanish in another, and because the messages table is indexed
 * that way. Held in memory and re-read after [TTL_MS]: derived from rows the phone already has, never stored and never
 * sent. [bodies] reads an author's messages in a chat, newest first; [read] is the identifier over one, without priors.
 */
class AuthorLanguages(
    private val bodies: suspend (conversationId: String, authorId: String, limit: Int) -> List<String>,
    private val read: suspend (String) -> LanguagePick,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    private val cache = HashMap<Pair<String, String>, Pair<Long, TranslateLang?>>()

    suspend fun of(
        conversationId: String,
        authorId: String,
    ): TranslateLang? {
        val key = conversationId to authorId
        mutex.withLock { cache[key]?.takeIf { clock() - it.first < TTL_MS }?.let { return it.second } }
        val lang = LanguagePolicy.majority(bodies(conversationId, authorId, SAMPLE).map { read(it) }, MIN_PICKS)
        mutex.withLock { cache[key] = clock() to lang }
        return lang
    }

    companion object {
        const val SAMPLE = 30
        const val MIN_PICKS = 3
        const val TTL_MS = 10 * 60_000L
    }
}
