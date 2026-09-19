package app.getknit.knit.ui.components

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.data.relay.RelayPlane
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The connection header's two-plane copy rules. Assertions go through the row's merged content
 * description rather than its text, because the row deliberately collapses dot + label + glyph into a
 * single semantics node — so the description is both what TalkBack reads and the only place the glyph's
 * state is spelled out in words. Follows the Compose-on-Robolectric pattern in
 * `DiagnosticsScreenContentTest`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionStatusRowTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun show(
        neighborCount: Int,
        health: TransportHealth,
        relay: RelayPlane,
        lora: LoraPlane = LoraPlane.Off,
    ) {
        compose.setContent {
            KnitTheme {
                ConnectionStatusRow(neighborCount = neighborCount, health = health, relay = relay, lora = lora)
            }
        }
    }

    /** The row's spoken form: the label, then one appended clause per armed plane, cloud first. */
    private fun described(
        label: String,
        plane: Int?,
        lora: Int? = null,
    ): String =
        listOfNotNull(plane, lora).fold(label) { spoken, clause ->
            context.getString(R.string.chat_connection_desc, spoken, context.getString(clause))
        }

    @Test
    fun aParkedPlaneLeavesTheMeshLineExactlyAsItWas() {
        show(3, TransportHealth.Healthy, RelayPlane.Off)

        val mesh = context.resources.getQuantityString(R.plurals.chat_connection_count, 3, 3)
        compose.onNodeWithContentDescription(described(mesh, plane = null)).assertIsDisplayed()
    }

    @Test
    fun bothPlanesUpKeepsTheMeshCountAndAddsOnlyTheGlyph() {
        show(3, TransportHealth.Healthy, RelayPlane.Live)

        val mesh = context.resources.getQuantityString(R.plurals.chat_connection_count, 3, 3)
        compose
            .onNodeWithContentDescription(described(mesh, R.string.chat_connection_relay_live_desc))
            .assertIsDisplayed()
    }

    @Test
    fun relaysCarryingWithNobodyNearbyReplacesTheEmptyMeshLine() {
        // "No mesh nodes connected" would be false here: messages are still moving, over the Internet.
        show(0, TransportHealth.Healthy, RelayPlane.Live)

        compose
            .onNodeWithContentDescription(
                described(
                    context.getString(R.string.chat_connection_relay_only),
                    R.string.chat_connection_relay_live_desc,
                ),
            ).assertIsDisplayed()
    }

    @Test
    fun radiosOffButRelaysUpDropsTheTurnThemOnHint() {
        show(0, TransportHealth.Unavailable, RelayPlane.Live)

        compose
            .onNodeWithContentDescription(
                described(
                    context.getString(R.string.chat_connection_relay_no_radios),
                    R.string.chat_connection_relay_live_desc,
                ),
            ).assertIsDisplayed()
    }

    // ForegroundOnly (ADR 2026-09.535d): the peers we hold keep their links, so the count stands; with none, the
    // OS's rule is named rather than "nobody nearby".
    @Test
    fun aSearchRefusedOffScreenKeepsTheCountItHolds() {
        show(2, TransportHealth.ForegroundOnly, RelayPlane.Off)
        val mesh = context.resources.getQuantityString(R.plurals.chat_connection_count, 2, 2)
        compose.onNodeWithContentDescription(described(mesh, plane = null)).assertIsDisplayed()
    }

    @Test
    fun aSearchRefusedOffScreenWithNobodyHeldNamesTheRule() {
        show(0, TransportHealth.ForegroundOnly, RelayPlane.Off)
        compose
            .onNodeWithContentDescription(described(context.getString(R.string.chat_connection_foreground_only), plane = null))
            .assertIsDisplayed()
    }

    @Test
    fun aSeizedRadioWithRelaysUpReadsTheSameWayAsRadiosOff() {
        // Degraded and Unavailable differ in cause but not in consequence once the Internet is carrying.
        show(0, TransportHealth.Degraded, RelayPlane.Live)

        compose
            .onNodeWithContentDescription(
                described(
                    context.getString(R.string.chat_connection_relay_no_radios),
                    R.string.chat_connection_relay_live_desc,
                ),
            ).assertIsDisplayed()
    }

    @Test
    fun aLiveLoraPlaneAddsItsGlyphAfterTheCloud() {
        show(3, TransportHealth.Healthy, RelayPlane.Live, LoraPlane.Live)

        val mesh = context.resources.getQuantityString(R.plurals.chat_connection_count, 3, 3)
        compose
            .onNodeWithContentDescription(
                described(mesh, R.string.chat_connection_relay_live_desc, R.string.chat_connection_lora_live_desc),
            ).assertIsDisplayed()
    }

    @Test
    fun aDownLoraPlaneIsSpokenAsNotConnected() {
        show(2, TransportHealth.Healthy, RelayPlane.Off, LoraPlane.Down)

        val mesh = context.resources.getQuantityString(R.plurals.chat_connection_count, 2, 2)
        compose
            .onNodeWithContentDescription(described(mesh, plane = null, lora = R.string.chat_connection_lora_down_desc))
            .assertIsDisplayed()
    }

    @Test
    fun loraAloneLeavesTheMeshLineUntouched() {
        // Unlike the Internet plane, a live board never rewrites the label: it needs this phone's Bluetooth,
        // so "no nearby radios" cannot be true while it is up, and a peer it hears already counts in the line.
        show(0, TransportHealth.Healthy, RelayPlane.Off, LoraPlane.Live)

        compose
            .onNodeWithContentDescription(
                described(context.getString(R.string.chat_connection_none), plane = null, lora = R.string.chat_connection_lora_live_desc),
            ).assertIsDisplayed()
    }

    @Test
    fun aDownPlaneNeverClaimsToBeRelaying() {
        show(0, TransportHealth.Unavailable, RelayPlane.Down)

        // Nothing crosses the plane, so the radio hint is still the useful thing to say.
        compose
            .onNodeWithContentDescription(
                described(
                    context.getString(R.string.chat_connection_radio_off),
                    R.string.chat_connection_relay_down_desc,
                ),
            ).assertIsDisplayed()
    }
}
