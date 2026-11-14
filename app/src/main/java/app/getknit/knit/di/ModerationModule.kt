package app.getknit.knit.di

import android.os.Build
import app.getknit.knit.BuildConfig
import app.getknit.knit.crash.ProcessExitReasons
import app.getknit.knit.data.settings.ModelLoadJournal
import app.getknit.knit.data.settings.ModelLoadStore
import app.getknit.knit.moderation.HybridTextModerator
import app.getknit.knit.moderation.ImageModerator
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.moderation.LexicalTextFilter
import app.getknit.knit.moderation.MlTextModerator
import app.getknit.knit.moderation.ModelLoadGuard
import app.getknit.knit.moderation.NsfwImageModerator
import app.getknit.knit.moderation.ScopedTextModerator
import app.getknit.knit.moderation.ShownNames
import app.getknit.knit.moderation.TextVerdict
import app.getknit.knit.moderation.WordList
import app.getknit.knit.moderation.modelGuardStamp
import kotlinx.coroutines.CoroutineScope
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * On-device content moderation. Everything runs locally against bundled assets/models — moderation never
 * talks to a server; the app's only Internet users are the opt-in relay plane and opt-in link previews,
 * both off by default, and a preview card's picture and text pass through these same classifiers on both
 * ends. Text moderation is scoped by conversation ([ScopedTextModerator]): every scope runs the
 * deterministic identity-slur list first (ADR 2026-10.d62w) and the on-device ML toxicity classifier
 * ([MlTextModerator], Detoxify/ALBERT) last; the public Nearby broadcast room adds the lexical profanity
 * filter between them (profanity is limited to the public room). Each moderator degrades to allow-all if
 * its model/tokenizer assets are missing; the word lists still run when the model does not.
 */
val moderationModule =
    module {
        // The poison-pill both bundled models load behind (ADR 037): a native crash inside TFLite is
        // invisible to CrashHandler and, on the launch-path toxicity model, unrecoverable without it.
        single<ModelLoadJournal> { ModelLoadStore(get()) }
        single {
            ModelLoadGuard(
                journal = get<ModelLoadJournal>(),
                exits = get<ProcessExitReasons>()::lastExit,
                stamp = modelGuardStamp(BuildConfig.VERSION_CODE, Build.FINGERPRINT.orEmpty()),
            )
        }
        // Shared so the heavy toxicity model is loaded at most once across both moderation scopes. Both
        // models ride the app scope: that is where their idle reaper lives (ModelLease), and it outlives
        // any screen or service that asked for a verdict.
        single { MlTextModerator(androidContext(), guard = get(), scope = get<CoroutineScope>()) }
        single {
            val ml = get<MlTextModerator>()
            // Every scope: the slur list first. The classifier reads a slur aimed at someone as an insult, a
            // label it ignores, so without this a DM slur passes (ADR 2026-10.d62w).
            val slurs =
                LexicalTextFilter(WordList.loadSlurs(androidContext()), category = TextVerdict.Category.SLUR)
            ScopedTextModerator(
                // Nearby broadcast room: slurs, then the profanity word-list, then ML toxicity on what both clear.
                room =
                    HybridTextModerator(
                        lexical = slurs,
                        ml =
                            HybridTextModerator(
                                lexical = LexicalTextFilter(WordList.loadProfanity(androidContext())),
                                ml = ml,
                            ),
                    ),
                // DMs and groups: slurs, then toxicity.
                direct = HybridTextModerator(lexical = slurs, ml = ml),
            )
        }
        // Names, statuses and group names (ADR 2026-11.y4pc): the word lists through the name matcher, never the model.
        single { ShownNames(WordList.nameScreen(androidContext())) }
        // Bound concretely as well as behind the interface so the debug bridge can read its residency.
        single { NsfwImageModerator(androidContext(), guard = get(), scope = get<CoroutineScope>()) }
        single<ImageModerator> { get<NsfwImageModerator>() }
        // Screens attachment blobs — images against the classifier, a link-preview card's picture and text
        // against both — and caches the verdict by content hash (blobVerdictDao). Extracted from BlobRepository
        // so the data layer no longer invokes the classifier.
        single { ImageScreeningService(get(), get(), get()) }
    }
