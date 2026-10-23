package app.getknit.knit.translate

import app.getknit.knit.call.SttModelState
import kotlinx.coroutines.flow.StateFlow

/**
 * What a chat sees of the translation spike (ADR 2026-10.t2h6): the translations asked for in this process and the
 * two directions' models, with their download. A chat in a build without the spike has none of this at all.
 */
interface ChatTranslation {
    val translations: StateFlow<Map<String, MessageTranslation>>

    val models: StateFlow<Map<TranslateDirection, SttModelState>>

    /** Whether a download now would ride mobile data or a Data Saver route. */
    fun metered(): Boolean

    fun download(direction: TranslateDirection): Boolean

    suspend fun pick(
        body: String,
        mentions: List<IntRange>,
    ): LanguagePick

    fun translate(
        messageId: String,
        body: String,
        from: TranslateLang,
        isRoom: Boolean,
        mentions: List<IntRange>,
    ): Boolean

    fun dismiss(messageId: String)

    /** The person's own [draft], read as [from], translated now for them to read before sending; not screened. */
    suspend fun translateDraft(
        draft: String,
        from: TranslateLang,
        mentions: List<IntRange>,
    ): TranslateOutcome
}
