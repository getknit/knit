package app.getknit.knit.di

import app.getknit.knit.moderation.HybridTextModerator
import app.getknit.knit.moderation.ImageModerator
import app.getknit.knit.moderation.LexicalTextFilter
import app.getknit.knit.moderation.MlTextModerator
import app.getknit.knit.moderation.NsfwImageModerator
import app.getknit.knit.moderation.TextModerator
import app.getknit.knit.moderation.WordList
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * On-device content moderation. Everything runs locally against bundled assets/models — the app has no
 * network. The text moderator runs the deterministic lexical filter first, then the on-device ML
 * toxicity classifier ([MlTextModerator], Detoxify/ALBERT) on anything it clears. Each moderator
 * degrades to allow-all if its model/tokenizer assets are missing.
 */
val moderationModule = module {
    single<TextModerator> {
        HybridTextModerator(
            lexical = LexicalTextFilter(WordList.loadProfanity(androidContext())),
            ml = MlTextModerator(androidContext()),
        )
    }
    single<ImageModerator> { NsfwImageModerator(androidContext()) }
}
