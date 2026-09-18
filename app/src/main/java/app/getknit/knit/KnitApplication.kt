package app.getknit.knit

import android.app.Application
import app.getknit.knit.crash.CrashHandler
import app.getknit.knit.crash.crashStore
import app.getknit.knit.crash.currentCrashEnvironment
import app.getknit.knit.data.LinkCardStore
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.di.appModule
import app.getknit.knit.di.meshModule
import app.getknit.knit.di.moderationModule
import app.getknit.knit.di.seedDemoIfEnabled
import app.getknit.knit.di.startDemoDirectorIfEnabled
import app.getknit.knit.di.uiModule
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.transfer.DirectWifi
import app.getknit.knit.ui.image.BlobFetcher
import app.getknit.knit.ui.image.BlobKeyer
import app.getknit.knit.ui.image.LinkCardFetcher
import app.getknit.knit.ui.image.LinkCardKeyer
import app.getknit.knit.ui.theme.ThemePreferences
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class KnitApplication :
    Application(),
    SingletonImageLoader.Factory {
    // Resolved lazily — first touched in newImageLoader(), which Coil calls well after startKoin().
    private val blobDao: BlobDao by inject()
    private val linkCards: LinkCardStore by inject()

    override fun onCreate() {
        super.onCreate()
        // Before startKoin, deliberately. The crashes worth capturing most are the ones in startup itself:
        // an AndroidKeyStore fault in KeystoreSecret/DatabaseKey, a SQLCipher or tflite .so that won't load,
        // a Koin graph that throws while building KnitDatabase. Every one of those kills the app before any
        // injectable object exists, which is also why the store is built by hand here rather than resolved.
        // Chains to whatever handler was already default, so the "Knit keeps stopping" dialog and the
        // process kill still happen exactly as before.
        CrashHandler.install(crashStore(this), currentCrashEnvironment())
        val koinApp =
            startKoin {
                androidLogger()
                androidContext(this@KnitApplication)
                modules(appModule, meshModule, moderationModule, uiModule)
            }
        // Register the message notification channel up front so it appears in system settings.
        koinApp.koin.get<Notifier>().createChannel()

        // Start the theme-flag read now, not at setContent. Koin singles are lazy, so resolving it here is
        // what actually begins the DataStore collect; MainActivity then reads an already-warmed value and
        // the app does not repaint from coral to the wallpaper palette a frame into launch.
        koinApp.koin.get<ThemePreferences>()

        // Seed the shipped default spools once (res/values/spools.xml). Opens no socket by itself — the
        // Internet plane stays off until the user turns it on — and a later removal sticks. A no-op while
        // the plane is dark (`BuildConfig.INTERNET_PLANE`), including the seeded marker, so the defaults
        // land on the first run of the build that introduces the feature.
        //
        // Its OWN coroutine, deliberately: chained behind warmUp() it inherited a ~16 MB model load, so a
        // fresh install sat with an unconfigured spool list for tens of seconds (observed on a Pixel 8),
        // and any throw from the warm-up would have skipped the seed entirely. The two share a scope, not
        // a sequence.
        koinApp.koin.get<CoroutineScope>().launch {
            koinApp.koin.get<SettingsStore>().seedDefaultSpools(resources.getStringArray(R.array.default_spools).toList())
        }

        // Clear a Wi-Fi Direct group a previous run left on air. Nothing removes one when the process dies
        // mid-transfer, and a live group both beacons our credentials and keeps Wi-Fi Aware off the radio.
        // Cheap when there is nothing to find (a binder and one query), never touches a group it did not
        // name, and held back past the cold-start window.
        koinApp.koin.get<CoroutineScope>().launch {
            delay(SWEEP_DELAY_MS)
            runCatching { koinApp.koin.get<DirectWifi>().sweep() }
        }

        // Demo-screenshot mode (`-PseedDemo=true`): fill the DB with a realistic conversation history so
        // the app renders populated on an emulator. Debug-only — the seeder lives in `src/debug`, so this is
        // a no-op in release (see the per-variant di/DemoWiring). Off by default even in debug.
        seedDemoIfEnabled(koinApp.koin)
        // Demo-trailer mode (`-PdemoDirector=true`): play the scripted, animated promo conversation instead
        // of the static seed. Also debug-only and a no-op in release.
        startDemoDirectorIfEnabled(koinApp.koin)
    }

    /**
     * App-wide Coil loader. Images come exclusively from the encrypted `blobs` table via
     * [BlobFetcher]/[BlobKeyer] — and, for the picture inside a link-preview card, through the card store via
     * [LinkCardFetcher]/[LinkCardKeyer]; there is deliberately no network fetcher, so nothing in the app can
     * load a URL. The disk cache is disabled so decrypted bytes are never persisted to disk (only the
     * in-memory bitmap cache is used). The animated decoder keeps GIFs/WebP animating.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .diskCache(null)
            .components {
                add(BlobKeyer())
                add(BlobFetcher.Factory(blobDao))
                add(LinkCardKeyer())
                add(LinkCardFetcher.Factory(linkCards))
                add(AnimatedImageDecoder.Factory())
            }.build()

    private companion object {
        /** How long the leftover-group sweep waits out the cold-start window: a stale group has waited this long already. */
        const val SWEEP_DELAY_MS = 8_000L
    }
}
