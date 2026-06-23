package app.getknit.knit

import android.app.Application
import app.getknit.knit.di.appModule
import app.getknit.knit.di.meshModule
import app.getknit.knit.di.uiModule
import app.getknit.knit.notifications.Notifier
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class KnitApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        val koinApp = startKoin {
            androidLogger()
            androidContext(this@KnitApplication)
            modules(appModule, meshModule, uiModule)
        }
        // Register the message notification channel up front so it appears in system settings.
        koinApp.koin.get<Notifier>().createChannel()
    }

    /** App-wide Coil loader with the animated decoder so message GIFs/WebP actually animate (API 28+). */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(AnimatedImageDecoder.Factory()) }
            .build()
}
