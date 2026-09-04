package app.getknit.knit.di

import android.os.Build
import app.getknit.knit.BuildConfig
import app.getknit.knit.crash.ProcessExitReasons
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.moderation.HybridTextModerator
import app.getknit.knit.moderation.ImageModerator
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.moderation.LexicalTextFilter
import app.getknit.knit.moderation.MlTextModerator
import app.getknit.knit.moderation.ModelLoadGuard
import app.getknit.knit.moderation.NsfwImageModerator
import app.getknit.knit.moderation.ScopedTextModerator
import app.getknit.knit.moderation.WordList
import app.getknit.knit.moderation.modelGuardStamp
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * On-device content moderation. Everything runs locally against bundled assets/models — moderation never
 * talks to a server; the app's only Internet users are the opt-in relay plane and opt-in link previews,
 * both off by default, and a preview card's picture and text pass through these same classifiers on both
 * ends. Text moderation is scoped by conversation ([ScopedTextModerator]): the public Nearby
 * broadcast room runs the deterministic lexical profanity filter first, then the on-device ML toxicity
 * classifier ([MlTextModerator], Detoxify/ALBERT) on anything it clears; private DMs and groups run the
 * toxicity classifier only (profanity is limited to the public room). Each moderator degrades to
 * allow-all if its model/tokenizer assets are missing.
 */
val moderationModule =
    module {
        // The poison-pill both bundled models load behind (ADR 037): a native crash inside TFLite is
        // invisible to CrashHandler and, on the launch-path toxicity model, unrecoverable without it.
        single {
            ModelLoadGuard(
                journal = get<SettingsStore>(),
                exits = get<ProcessExitReasons>()::lastExit,
                stamp = modelGuardStamp(BuildConfig.VERSION_CODE, Build.FINGERPRINT.orEmpty()),
            )
        }
        // Shared so the heavy toxicity model is loaded at most once across both moderation scopes.
        single { MlTextModerator(androidContext(), guard = get()) }
        single {
            ScopedTextModerator(
                // Nearby broadcast room: profanity word-list, then ML toxicity on what it clears.
                room =
                    HybridTextModerator(
                        lexical = LexicalTextFilter(WordList.loadProfanity(androidContext())),
                        ml = get<MlTextModerator>(),
                    ),
                // DMs and groups: toxicity only.
                direct = get<MlTextModerator>(),
            )
        }
        single<ImageModerator> { NsfwImageModerator(androidContext(), guard = get()) }
        // Screens attachment blobs — images against the classifier, a link-preview card's picture and text
        // against both — and caches the verdict by content hash (blobVerdictDao). Extracted from BlobRepository
        // so the data layer no longer invokes the classifier.
        single { ImageScreeningService(get(), get(), get()) }
    }
