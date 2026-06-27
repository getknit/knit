package app.getknit.knit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.getknit.knit.ui.KnitApp
import app.getknit.knit.ui.theme.KnitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Demo-screenshot builds may deep-link to a screen for deterministic capture, e.g.
        // `adb shell am start -n app.getknit.knit/.MainActivity --es demo_route chat/nearby`.
        val startRoute = if (BuildConfig.SEED_DEMO) intent?.getStringExtra(EXTRA_DEMO_ROUTE) else null
        setContent {
            KnitTheme {
                KnitApp(startRoute = startRoute)
            }
        }
    }

    private companion object {
        const val EXTRA_DEMO_ROUTE = "demo_route"
    }
}
