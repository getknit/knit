package app.getknit.knit.di

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.location.GeoUri
import app.getknit.knit.moderation.ModelLoadGuard
import app.getknit.knit.moderation.ScopedTextModerator
import app.getknit.knit.net.AndroidInternetGate
import app.getknit.knit.translate.AuthorLanguages
import app.getknit.knit.translate.ChatTranslation
import app.getknit.knit.translate.LanguagePick
import app.getknit.knit.translate.LanguagePolicy
import app.getknit.knit.translate.MessageTranslator
import app.getknit.knit.translate.ReaderLanguage
import app.getknit.knit.translate.TranslateDirection
import app.getknit.knit.translate.TranslateLang
import app.getknit.knit.translate.VerbatimSpans
import app.getknit.knit.ui.translate.SlimtEngine
import app.getknit.knit.ui.translate.TranslateModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The offline message-translation spike (ADR 2026-10.t2h6): a build made with `-Ptranslate=true`, which packages the
 * `translate-native` AAR. The one read of `BuildConfig.TRANSLATE`. Off, [translateModule] is null, no chat offers Translate
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
            val engine = get<SlimtEngine>()
            val messages = get<MessageDao>()
            val identity = get<Identity>()
            ReaderLanguage(
                ownBodies = { limit -> messages.recentOwnBodies(identity.nodeId(), limit) },
                read = { body ->
                    val words = VerbatimSpans.wordsOnly(body)
                    if (words.isBlank()) LanguagePick.Unsure else LanguagePolicy.pick(engine.detect(words), words)
                },
                // The system's list, never the app's: a per-app language says what the app is shown in, not what its
                // reader reads (ADR 2026-10.t2h6).
                preferred = {
                    Resources
                        .getSystem()
                        .configuration.locales
                        .let { l -> (0 until l.size()).map { l[it].toLanguageTag() } }
                },
                clock = SystemClock::elapsedRealtime,
                // Settings › Apps › Knit › Language (Android 13+): a choice, so evidence of what this person reads.
                perApp = { appLanguages(androidContext()) },
            )
        }
        single {
            val moderator = get<ScopedTextModerator>()
            val messages = get<MessageDao>()
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
                save = { id, done -> messages.setTranslation(id, done.text, done.from.code, done.to.code, done.flagged, done.guessed) },
            )
        }
        single<ChatTranslation> {
            val store = get<TranslateModelStore>()
            val scope = get<CoroutineScope>()
            // Which models are on the phone, read once off the main thread (a chat resolves this lazily).
            scope.launch(Dispatchers.IO) { store.start() }
            chatTranslation(store, get(), get(), get(), scope)
        }
    }

// What a chat sees of translation: the translator's state and calls, every model's state, and the reader's language.
private fun chatTranslation(
    store: TranslateModelStore,
    translator: MessageTranslator,
    readerLanguage: ReaderLanguage,
    settings: SettingsStore,
    scope: CoroutineScope,
): ChatTranslation =
    object : ChatTranslation {
        override val translations = translator.state
        override val models =
            combine(TranslateDirection.ALL.map { d -> store.state(d).map { d to it } }) { it.toMap() }
                .stateIn(scope, SharingStarted.Eagerly, emptyMap())

        @OptIn(ExperimentalCoroutinesApi::class)
        override val reader = settings.translateTarget.mapLatest { readerLanguage.estimate(it).target }

        override fun metered() = store.metered()

        override fun download(direction: TranslateDirection) = store.download(direction)

        override suspend fun pick(
            body: String,
            mentions: List<IntRange>,
        ) = translator.pick(body, mentions)

        override suspend fun declaredReads() = readerLanguage.declaredReads(settings.translateTarget.first())

        override suspend fun resolve(
            body: String,
            mentions: List<IntRange>,
            conversationId: String,
            authorId: String?,
            chat: TranslateLang?,
            declared: String?,
        ) = translator.resolve(body, mentions, conversationId, authorId, chat, declared)

        override fun translate(
            messageId: String,
            body: String,
            from: TranslateLang,
            to: TranslateLang?,
            isRoom: Boolean,
            mentions: List<IntRange>,
            guessed: Boolean,
        ) = translator.translate(messageId, body, from, to, isRoom, mentions, guessed)

        override fun dismiss(messageId: String) = translator.dismiss(messageId)

        override suspend fun translateDraft(
            draft: String,
            from: TranslateLang,
            to: TranslateLang,
            mentions: List<IntRange>,
        ) = translator.translateNow(draft, from, to, mentions)
    }

// The languages this person chose for Knit in Settings › Apps (Android 13+), empty when none was chosen.
private fun appLanguages(context: Context): List<String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyList()
    val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return emptyList()
    return (0 until locales.size()).map { locales[it].toLanguageTag() }
}
