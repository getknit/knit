package app.getknit.knit.di

import android.app.Application
import android.content.Context
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.settings.ContributionJournal
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.data.settings.ModelLoadJournal
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.mesh.RadioSupport
import app.getknit.knit.moderation.TextModerator
import com.google.crypto.tink.KeysetHandle
import kotlinx.coroutines.flow.Flow
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.verify.verify
import java.io.File

/**
 * The whole production graph — the four modules `KnitApplication` starts — checked without building it:
 * every constructor parameter of every definition must resolve to another definition. A type a module never
 * provides is a crash at first use on the phone (or, in a test, an exception that surfaces as the *next*
 * class's failure, `testing.md`'s `UncaughtExceptionsBeforeTest`), so it fails here instead, named.
 *
 * Koin's verifier reads constructors, not the lambdas, so a parameter the module passes by hand rather than
 * through `get()` is listed in [extraTypes] with the reason it is not a definition.
 */
class KoinGraphVerifyTest {
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun everyDefinitionResolves() {
        module { includes(appModule, meshModule, moderationModule, uiModule) }.verify(extraTypes = extraTypes)
    }

    private companion object {
        val extraTypes =
            listOf(
                // androidContext() — supplied by startKoin, not a definition.
                Context::class,
                Application::class,
                // Lambdas and values a module writes inline: EmojiCatalogLoader's asset opener and glyph test,
                // LinkPreviewService's screening hooks, ContactImporter's plane flag, CrashStore's directory,
                // ThemePreferences' dynamic-colour flow.
                Function0::class,
                Function1::class,
                Function2::class,
                Function3::class,
                Boolean::class,
                File::class,
                Flow::class,
                // Built in the lambda itself: the identity file's Keystore wrap, MessageCrypto's keysets (read off
                // IdentityKeyStore), ScopedTextModerator's per-scope screeners, Diagnostics' RadioSupport.probe.
                KeystoreSecret::class,
                KeysetHandle::class,
                TextModerator::class,
                RadioSupport::class,
                // Narrow interfaces a module satisfies with a concrete singleton it already holds:
                // `get<SettingsStore>()` as InboundSettings / ContributionJournal / ModelLoadJournal,
                // `get<Identity>()` as IdentitySource.
                InboundSettings::class,
                ContributionJournal::class,
                ModelLoadJournal::class,
                IdentitySource::class,
            )
    }
}
