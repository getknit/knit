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
        setContent {
            KnitTheme {
                KnitApp()
            }
        }
    }
}
