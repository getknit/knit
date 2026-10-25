package app.getknit.knit.di

import android.app.Application
import android.content.Context
import app.getknit.knit.call.CallMedia
import app.getknit.knit.call.CallSignals
import app.getknit.knit.call.CallWalkie
import app.getknit.knit.call.SpeechToText
import app.getknit.knit.call.SttModel
import app.getknit.knit.call.TonePlayer
import app.getknit.knit.call.TurnSource
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.settings.ContributionJournal
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.data.settings.ModelLoadJournal
import app.getknit.knit.fieldlog.FieldJournal
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.mesh.RadioSupport
import app.getknit.knit.mesh.spool.FreeKeyJournal
import app.getknit.knit.mesh.spool.SpoolTicketJournal
import app.getknit.knit.mesh.spool.TicketKeyJournal
import app.getknit.knit.moderation.TextModerator
import com.google.crypto.tink.KeysetHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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
        // Plus the voice calls' graph where this variant wires one (di/CallWiring; unit tests build the debug one, and
        // `-PliveCalls=false`, `-PmeshCalls=false`, `-Pstt=false` or `-PknitAnywhere=false` checks that build's).
        val calls = listOfNotNull(callModule()).toTypedArray()
        // And the developer view's (di/DevViewWiring, ADR 2026-10.s7wg) where this variant wires it.
        val devView = listOfNotNull(devViewModule()).toTypedArray()
        module {
            includes(appModule, meshModule, moderationModule, uiModule, *calls, *devView)
        }.verify(extraTypes = extraTypes + noLiveCalls)
    }

    private companion object {
        // A build without live calls hands CallManager `CallMedia.None` and `TurnSource.None` inline (ADR 2026-10.swvp);
        // one with them must resolve both to their definitions, so only that build excuses them.
        val noLiveCalls = if (LIVE_CALLS_WIRED) emptyList() else listOf(CallMedia::class, TurnSource::class)

        val extraTypes =
            listOf(
                // androidContext() — supplied by startKoin, not a definition.
                Context::class,
                Application::class,
                // Lambdas and values a module writes inline: EmojiCatalogLoader's asset opener and glyph test,
                // LinkPreviewService's screening hooks, ContactImporter's plane flag, CrashStore's directory,
                // ThemePreferences' dynamic-colour flow, and WalkieTalk's send (peer, chunk, cast ticket, cast too, continuation).
                Function0::class,
                Function1::class,
                Function2::class,
                Function3::class,
                Function4::class,
                Function5::class,
                Boolean::class,
                File::class,
                Flow::class,
                // Built in the lambda itself: the identity file's Keystore wrap, MessageCrypto's keysets (read off
                // IdentityKeyStore), ScopedTextModerator's per-scope screeners, Diagnostics' RadioSupport.probe.
                KeystoreSecret::class,
                KeysetHandle::class,
                TextModerator::class,
                RadioSupport::class,
                // The field log's journal, built over its noBackupFilesDir directory in the FieldLog definition.
                FieldJournal::class,
                // The call graph's recorder, a lambda over FieldLog resolved at each record (CallWiring.fieldLog).
                FieldRecorder::class,
                // Narrow interfaces a module satisfies with a concrete singleton it already holds:
                // `get<SettingsStore>()` as InboundSettings / ContributionJournal / ModelLoadJournal,
                // `get<Identity>()` as IdentitySource.
                InboundSettings::class,
                ContributionJournal::class,
                ModelLoadJournal::class,
                IdentitySource::class,
                // Free tickets' stores (ADR 2026-10.s3ep): `get<TicketKeyStore>()` as TicketKeyJournal / FreeKeyJournal and
                // `get<SettingsStore>()` as SpoolTicketJournal, handed to FreeUploads.
                TicketKeyJournal::class,
                FreeKeyJournal::class,
                SpoolTicketJournal::class,
                // The voice calls' seams, written inline over MeshManager so building the call graph builds nothing
                // else (di/CallWiring), and the ring's alert timeout.
                CallSignals::class,
                Long::class,
                // The direct leg's link, handed to the media as a field of the DirectCallPath it already holds.
                StateFlow::class,
                // The walkie's seam over the same late-bound reads (§3.11).
                CallWalkie::class,
                // The caller's tones' player (C-3.13-14), a ToneGeneratorPlayer built in CallToneSync's definition.
                TonePlayer::class,
                // The walkie transcript's model (C-3.11-9): the store's default pins, the engine handed over as the seam it
                // implements, and the one-lane dispatcher the module writes inline.
                SttModel::class,
                SpeechToText::class,
                CoroutineDispatcher::class,
            )
    }
}
