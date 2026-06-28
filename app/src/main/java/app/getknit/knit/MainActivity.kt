package app.getknit.knit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import app.getknit.knit.ui.KnitApp
import app.getknit.knit.ui.share.ShareInbox
import app.getknit.knit.ui.share.SharedContent
import app.getknit.knit.ui.theme.KnitTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    // Single-shot holder for content arriving via the system share sheet; KnitApp/ChatScreen drain it.
    private val shareInbox: ShareInbox by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A cold-start share: stage the payload before composition so KnitApp opens the picker.
        handleShareIntent(intent)
        // Demo-screenshot builds may deep-link to a screen for deterministic capture, e.g.
        // `adb shell am start -n app.getknit.knit/.MainActivity --es demo_route chat/nearby`.
        val startRoute = if (BuildConfig.SEED_DEMO) intent?.getStringExtra(EXTRA_DEMO_ROUTE) else null
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
    }

    /** Parse an ACTION_SEND intent into the [ShareInbox]. Other intents (incl. the launcher) are ignored. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        // EXTRA_STREAM is only meaningful (and read-granted) for the image/* filter we declare.
        val imageUri = if (intent.type?.startsWith("image/") == true) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toString()
        } else {
            null
        }
        shareInbox.offer(SharedContent(text = text, imageUri = imageUri))
    }

    private companion object {
        const val EXTRA_DEMO_ROUTE = "demo_route"
    }
}
