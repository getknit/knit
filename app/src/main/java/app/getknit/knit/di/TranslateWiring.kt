package app.getknit.knit.di

import android.os.SystemClock
import android.util.Log
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.location.GeoUri
import app.getknit.knit.moderation.ModelLoadGuard
import app.getknit.knit.moderation.ScopedTextModerator
import app.getknit.knit.net.AndroidInternetGate
import app.getknit.knit.translate.AuthorLanguages
import app.getknit.knit.translate.ChatTranslation
import app.getknit.knit.translate.LanguagePick
import app.getknit.knit.translate.LanguagePolicy
import app.getknit.knit.translate.MessageTranslator
import app.getknit.knit.translate.TranslateDirection
import app.getknit.knit.translate.TranslateLang
import app.getknit.knit.translate.VerbatimSpans
import app.getknit.knit.ui.translate.SlimtEngine
import app.getknit.knit.ui.translate.TranslateModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The offline message-translation spike (ADR 2026-10.t2h6): a debug build made with `-Ptranslate=true`, which packages
 * `translate-native`. The one read of `BuildConfig.TRANSLATE`. Off, [translateModule] is null, no chat offers Translate
 * and R8 drops every class only this graph reaches.
 */
const val TRANSLATE_WIRED: Boolean = BuildConfig.TRANSLATE

private const val TAG = "Translate"

/** The translation graph, or null in a build without it ([TRANSLATE_WIRED]). */
fun translateModule(): Module? = if (TRANSLATE_WIRED) translateGraph() else null

private fun translateGraph(): Module =
    module {
        single { TranslateModelStore(androidContext(), get<AndroidInternetGate>(), get<CoroutineScope>(), log = { Log.i(TAG, it) }) }
        single { SlimtEngine(get(), get<ModelLoadGuard>(), get<CoroutineScope>(), log = { Log.i(TAG, it) }) }
        single {
            val engine = get<SlimtEngine>()
            val messages = get<MessageDao>()
            AuthorLanguages(
                bodies = messages::recentBodiesIn,
                read = { body ->
                    val words = VerbatimSpans.wordsOnly(body)
                    if (words.isBlank()) LanguagePick.Unsure else LanguagePolicy.pick(engine.detect(words), words)
                },
                clock = SystemClock::elapsedRealtime,
            )
        }
        single {
            val moderator = get<ScopedTextModerator>()
            MessageTranslator(
                engine = get<SlimtEngine>(),
                // A translation is screened as an inbound body is (MeshManager.isTextFlagged): its positions out first.
                flagged = { text, isRoom ->
                    val words = GeoUri.strip(text)
                    words.isNotBlank() && moderator.classify(words, isRoom).flagged
                },
                scope = get<CoroutineScope>(),
                dispatcher = Dispatchers.Default.limitedParallelism(1),
                clock = SystemClock::elapsedRealtime,
                log = { Log.i(TAG, it) },
                authors = get(),
            )
        }
        single<ChatTranslation> {
            val store = get<TranslateModelStore>()
            val translator = get<MessageTranslator>()
            val scope = get<CoroutineScope>()
            val (enEs, esEn) = TranslateDirection.EN_ES to TranslateDirection.ES_EN
            // Which models are on the phone, read once off the main thread (a chat resolves this lazily).
            scope.launch(Dispatchers.IO) { store.start() }
            object : ChatTranslation {
                override val translations = translator.state
                override val models =
                    combine(store.state(enEs), store.state(esEn)) { a, b -> mapOf(enEs to a, esEn to b) }
                        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

                override fun metered() = store.metered()

                override fun download(direction: TranslateDirection) = store.download(direction)

                override suspend fun pick(
                    body: String,
                    mentions: List<IntRange>,
                ) = translator.pick(body, mentions)

                override suspend fun resolve(
                    body: String,
                    mentions: List<IntRange>,
                    conversationId: String,
                    authorId: String?,
                    chat: TranslateLang?,
                ) = translator.resolve(body, mentions, conversationId, authorId, chat)

                override fun translate(
                    messageId: String,
                    body: String,
                    from: TranslateLang,
                    isRoom: Boolean,
                    mentions: List<IntRange>,
                    guessed: Boolean,
                ) = translator.translate(messageId, body, from, isRoom, mentions, guessed)

                override fun dismiss(messageId: String) = translator.dismiss(messageId)

                override suspend fun translateDraft(
                    draft: String,
                    from: TranslateLang,
                    mentions: List<IntRange>,
                ) = translator.translateNow(draft, from, mentions)
            }
        }
    }
