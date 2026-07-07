package app.getknit.knit.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The diagnostics screen composes on real hardware. It has no testTags, so we assert the self-identity name
 * at the top of the list (the transports section below renders `DemoTransport`'s Healthy status).
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsInstrumentedTest : SeededUiTest() {
    @Test
    fun rendersSelfIdentity() {
        launch("diagnostics")

        awaitText("Maya Okonkwo")
    }
}
