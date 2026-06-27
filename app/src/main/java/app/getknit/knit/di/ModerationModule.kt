package app.getknit.knit.di

import app.getknit.knit.moderation.HybridTextModerator
import app.getknit.knit.moderation.ImageModerator
import app.getknit.knit.moderation.LexicalTextFilter
import app.getknit.knit.moderation.NsfwImageModerator
import app.getknit.knit.moderation.TextModerator
import app.getknit.knit.moderation.WordList
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * On-device content moderation. Everything runs locally against bundled assets/models — the app has no
 * network. The text moderator is lexical-only for now; the ML toxicity classifier is layered into the
 * hybrid once a vetted model ships (see the moderation plan). The image moderator degrades to allow-all
 * until a compatible NSFW model is dropped at `assets/moderation/nsfw.tflite`.
 */
val moderationModule = module {
    single<TextModerator> {
        HybridTextModerator(LexicalTextFilter(WordList.loadProfanity(androidContext())))
    }
    single<ImageModerator> { NsfwImageModerator(androidContext()) }
}
