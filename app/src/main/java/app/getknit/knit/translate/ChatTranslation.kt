package app.getknit.knit.translate

import app.getknit.knit.call.SttModelState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What a chat sees of offline translation (ADR 2026-10.t2h6): the translations asked for in this process, every
 * direction's model with its download, and the reader's language, which a translation goes into. A chat in a build
 * without translation has none of this at all.
 */
interface ChatTranslation {
    val translations: StateFlow<Map<String, MessageTranslation>>

    val models: StateFlow<Map<TranslateDirection, SttModelState>>

    /** The language this phone's reader wants translations in ([ReaderLanguages]), or null when nothing says. */
    val reader: Flow<TranslateLang?>

    /** Whether a download now would ride mobile data or a Data Saver route. */
    fun metered(): Boolean

    fun download(direction: TranslateDirection): Boolean

    suspend fun pick(
        body: String,
        mentions: List<IntRange>,
    ): LanguagePick

    /**
     * [body]'s language by its words, then what its author declared on the frame ([declared]), then its author's usual
     * language in the chat, then the chat's ([chat]).
     */
    suspend fun resolve(
        body: String,
        mentions: List<IntRange>,
        conversationId: String,
        authorId: String?,
        chat: TranslateLang?,
        declared: TranslateLang?,
    ): Resolution

    /** Translates [body] from [from] into [to] ([TranslateTarget]); a null [to] shows that nothing goes there. */
    fun translate(
        messageId: String,
        body: String,
        from: TranslateLang,
        to: TranslateLang?,
        isRoom: Boolean,
        mentions: List<IntRange>,
        guessed: Boolean,
    ): Boolean

    fun dismiss(messageId: String)

    /** The person's own [draft], read as [from], translated into [to] now for them to read before sending; not screened. */
    suspend fun translateDraft(
        draft: String,
        from: TranslateLang,
        to: TranslateLang,
        mentions: List<IntRange>,
    ): TranslateOutcome
}
