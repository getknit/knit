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
import app.getknit.knit.moderation.MlTextModerator
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.ui.image.BlobFetcher
import app.getknit.knit.ui.image.BlobKeyer
import app.getknit.knit.ui.image.LinkCardFetcher
import app.getknit.knit.ui.image.LinkCardKeyer
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

        // Warm the toxicity model off the send path. The first classify() lazily loads a ~16 MB TFLite
        // model + tokenizer + Interpreter and pays first-inference allocation; done on the first outgoing
        // send it freezes the UI on a cold start (worst on low-end devices). Fire-and-forget on the
        // app-lifetime scope (Dispatchers.Default) so it never blocks startup; MlTextModerator degrades
        // gracefully if the assets fail to load, and warmUp() dedupes against a racing first send.
        //
        // Held back past the cold-start window on purpose: the load is CPU- and allocation-heavy, and
        // starting it here put it in contention with the main thread building the Koin graph and with
        // MeshService racing AOSP's 10 s startForegroundService grace — the tightest moment in the app's
        // life, and the one where a low-end device has the least headroom. Nothing needs a warm engine
        // until the user can actually send, which is many seconds out on any device slow enough for the
        // contention to matter, so the wait costs nothing; a first send that beats it still dedupes
        // through warmUp()'s own mutex and just pays the load itself, exactly as it did before.
        koinApp.koin.get<CoroutineScope>().launch {
            delay(WARMUP_DELAY_MS)
            koinApp.koin.get<MlTextModerator>().warmUp()
        }

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
        /** How long the toxicity warm-up waits out the cold-start window before it starts loading. */
        const val WARMUP_DELAY_MS = 5_000L
    }
}
