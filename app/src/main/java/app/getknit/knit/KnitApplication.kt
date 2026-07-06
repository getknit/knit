package app.getknit.knit

import android.app.Application
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.di.appModule
import app.getknit.knit.di.meshModule
import app.getknit.knit.di.moderationModule
import app.getknit.knit.di.seedDemoIfEnabled
import app.getknit.knit.di.uiModule
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.ui.image.BlobFetcher
import app.getknit.knit.ui.image.BlobKeyer
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class KnitApplication :
    Application(),
    SingletonImageLoader.Factory {
    // Resolved lazily — first touched in newImageLoader(), which Coil calls well after startKoin().
    private val blobDao: BlobDao by inject()

    override fun onCreate() {
        super.onCreate()
        val koinApp =
            startKoin {
                androidLogger()
                androidContext(this@KnitApplication)
                modules(appModule, meshModule, moderationModule, uiModule)
            }
        // Register the message notification channel up front so it appears in system settings.
        koinApp.koin.get<Notifier>().createChannel()

        // Demo-screenshot mode (`-PseedDemo=true`): fill the DB with a realistic conversation history so
        // the app renders populated on an emulator. Debug-only — the seeder lives in `src/debug`, so this is
        // a no-op in release (see the per-variant di/DemoWiring). Off by default even in debug.
        seedDemoIfEnabled(koinApp.koin)
    }

    /**
     * App-wide Coil loader. Images come exclusively from the encrypted `blobs` table via
     * [BlobFetcher]/[BlobKeyer]; the disk cache is disabled so decrypted bytes are never persisted to
     * disk (only the in-memory bitmap cache is used). The animated decoder keeps GIFs/WebP animating.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .diskCache(null)
            .components {
                add(BlobKeyer())
                add(BlobFetcher.Factory(blobDao))
                add(AnimatedImageDecoder.Factory())
            }.build()
}
