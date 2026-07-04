package app.getknit.knit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import app.getknit.knit.ui.KnitApp
import app.getknit.knit.ui.RouteInbox
import app.getknit.knit.ui.share.ShareInbox
import app.getknit.knit.ui.share.SharedContent
import app.getknit.knit.ui.theme.KnitTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    // Single-shot holder for content arriving via the system share sheet; KnitApp/ChatScreen drain it.
    private val shareInbox: ShareInbox by inject()

    // Single-shot holder for a notification-tap deep-link route (e.g. "chat/<id>"); KnitApp drains it.
    private val routeInbox: RouteInbox by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A cold-start share: stage the payload before composition so KnitApp opens the picker.
        handleShareIntent(intent)
        // A cold-start notification tap: stage its deep-link route so KnitApp navigates to that thread.
        handleRouteIntent(intent)
        // Debug builds honor a deep-link route extra so screenshots (demo builds) and automation agents
        // (any debug build, over the real mesh) can jump straight to a screen, e.g.
        // `adb shell am start -n app.getknit.knit/.MainActivity --es demo_route chat/nearby`. Gated to
        // debug so release never reads it. (Demo builds still swap in DemoTransport via SEED_DEMO.)
        val startRoute =
            if (BuildConfig.SEED_DEMO || BuildConfig.DEBUG) {
                intent?.getStringExtra(EXTRA_DEMO_ROUTE)
            } else {
                null
            }
        setContent {
            KnitTheme {
                KnitApp(startRoute = startRoute)
            }
        }
    }

    // Share into an already-running instance (launchMode=singleTask). Re-stage into the inbox; KnitApp
    // observes it and routes to the share-target picker.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        // A notification tap on an already-running instance: stage the deep-link route; KnitApp navigates.
        handleRouteIntent(intent)
    }

    /** Stage a notification deep-link route ([EXTRA_ROUTE], e.g. "chat/<id>") into the [RouteInbox]. */
    private fun handleRouteIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ROUTE)?.let { routeInbox.offer(it) }
    }

    /** Parse an ACTION_SEND intent into the [ShareInbox]. Other intents (incl. the launcher) are ignored. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        // EXTRA_STREAM is only meaningful (and read-granted) for the image/* filter we declare.
        val imageUri =
            if (intent.type?.startsWith("image/") == true) {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toString()
            } else {
                null
            }
        shareInbox.offer(SharedContent(text = text, imageUri = imageUri))
    }

    companion object {
        /** Deep-link route extra set by [app.getknit.knit.notifications.MessageNotifier] on a notification tap. */
        const val EXTRA_ROUTE = "app.getknit.knit.NOTIF_ROUTE"
        private const val EXTRA_DEMO_ROUTE = "demo_route"
    }
}
