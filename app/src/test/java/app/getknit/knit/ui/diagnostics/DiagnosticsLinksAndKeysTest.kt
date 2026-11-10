package app.getknit.knit.ui.diagnostics

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.mesh.LinkSecurity
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.ui.Reach
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Diagnostics cleanup's rows on the stateless `DiagnosticsScreenContent`: the section order, the link and key chips
 * (ADR 2026-11.8mhs, 2026-11.xp6d), the direct-link plane's count, the rotating-ID tag and the debug section. The
 * viewport is made tall enough for every section to compose, so a row below the fold is asserted where it stands rather
 * than after a scroll, and the order is read from the rows' positions.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h6000dp")
class DiagnosticsLinksAndKeysTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun state() = DiagnosticsUiState(myNodeId = "8f3a2b1c9d4e", myName = "Ada Lovelace")

    private fun show(
        state: DiagnosticsUiState = state(),
        presence: PresenceKeyInfo = PresenceKeyInfo(),
        walkieClipsOffered: Boolean = false,
        advertDebug: AdvertDebugInfo? = null,
    ) {
        compose.setContent {
            KnitTheme {
                Content(state, presence, walkieClipsOffered, advertDebug)
            }
        }
    }

    @Composable
    private fun Content(
        state: DiagnosticsUiState,
        presence: PresenceKeyInfo,
        walkieClipsOffered: Boolean,
        advertDebug: AdvertDebugInfo?,
    ) = DiagnosticsScreenContent(
        state = state,
        health = TransportHealth.Healthy,
        lastCrash = null,
        moderationLatched = false,
        now = 0L,
        snackbarHostState = SnackbarHostState(),
        onBack = {},
        onRestartMesh = {},
        onScan = {},
        onOpenCrashLog = {},
        onResetModeration = {},
        walkieClipsOffered = walkieClipsOffered,
        presence = presence,
        advertDebug = advertDebug,
    )

    private fun top(text: String): Float =
        compose
            .onNodeWithText(text)
            .getUnclippedBoundsInRoot()
            .top.value

    /** Reports first, then the planes and who they reach, then the relays and the metrics. */
    @Test
    fun theNodeListsComeBeforeTheRelaysAndTheMetrics() {
        show(
            state().copy(
                spools =
                    listOf(
                        SpoolStatus("wss://relay.example/spool/v1", connected = true, powBits = 0, lastError = null, scopes = emptyList()),
                    ),
            ),
        )

        val reports = top(context.getString(R.string.diagnostics_reports))
        val transports = top(context.getString(R.string.diagnostics_transports))
        val direct = top(context.getString(R.string.diagnostics_directly_connected, 0))
        val known = top(context.getString(R.string.diagnostics_via_relay, 0))
        val relays = top(context.getString(R.string.diagnostics_spools))
        val metrics = top(context.getString(R.string.diagnostics_metrics))
        assertTrue("reports above the planes", reports < transports)
        assertTrue("the planes above who they reach", transports < direct)
        assertTrue("who is reached above the relays", known < relays)
        assertTrue("the relays above the metrics", relays < metrics)
    }

    /** The field log sits in the reports section whether or not anything crashed. */
    @Test
    fun theFieldLogIsInTheReportsWithNoCrash() {
        show()

        compose.onNodeWithText(context.getString(R.string.field_log_row_label)).assertExists()
        assertTrue(top(context.getString(R.string.field_log_row_label)) < top(context.getString(R.string.diagnostics_controls)))
    }

    @Test
    fun aSealedLinkReadsEncryptedAPlainOneNotAndNoLinkNoChip() {
        val sealed = NodeInfo("aaaa1111bbbb", "Ada", Reach.Direct, null, setOf(TransportKind.Bluetooth), link = LinkSecurity.SEALED)
        val plain = NodeInfo("cccc2222dddd", "Grace", Reach.Direct, null, setOf(TransportKind.Bluetooth), link = LinkSecurity.LEGACY)
        val proven = NodeInfo("eeee3333ffff", "Edsger", Reach.Direct, null, setOf(TransportKind.WifiAware), link = LinkSecurity.PROVEN)
        val none = NodeInfo("1111aaaa2222", "Radia", Reach.Direct, null, setOf(TransportKind.WifiAware))
        show(state().copy(directNodes = listOf(sealed, plain, proven, none)))

        compose
            .onNodeWithTag("link_chip_${sealed.nodeId}")
            .assertContentDescriptionEquals(context.getString(R.string.diagnostics_link_encrypted_cd))
        compose
            .onNodeWithTag("link_chip_${plain.nodeId}")
            .assertContentDescriptionEquals(context.getString(R.string.diagnostics_link_plain_cd))
        compose
            .onNodeWithTag("link_chip_${proven.nodeId}")
            .assertContentDescriptionEquals(context.getString(R.string.diagnostics_link_plain_cd))
        compose.onNodeWithTag("link_chip_${none.nodeId}").assertDoesNotExist()
    }

    @Test
    fun theKeyChipMarksOnlyTheContactsWhoseKeyIsHeld() {
        val held = NodeInfo("aaaa1111bbbb", "Ada", Reach.Direct, null, setOf(TransportKind.Bluetooth))
        val notHeld = NodeInfo("cccc2222dddd", "Grace", Reach.Direct, null, setOf(TransportKind.Bluetooth))
        show(state().copy(directNodes = listOf(held, notHeld)), presence = PresenceKeyInfo(keyHolders = setOf(held.nodeId)))

        compose
            .onNodeWithTag("key_chip_${held.nodeId}")
            .assertContentDescriptionEquals(context.getString(R.string.diagnostics_key_chip_cd))
        compose.onNodeWithTag("key_chip_${notHeld.nodeId}").assertDoesNotExist()
    }

    /** ADR 2026-10.a5yj: a direct link is no radio, so its row counts links and never says "nearby". */
    @Test
    fun theDirectLinkPlaneCountsLinksNotNearby() {
        show(
            state().copy(
                transports =
                    listOf(
                        TransportRow.Live(TransportStatus(TransportKind.DirectLink, TransportHealth.Healthy, linked = 2, nearby = 2)),
                    ),
            ),
        )

        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.diagnostics_transport_links, 2, 2)).assertExists()
        compose.onNodeWithText(context.getString(R.string.diagnostics_transport_counts, 2, 2)).assertDoesNotExist()
    }

    @Test
    fun aContactOnADirectLinkIsTaggedLink() {
        val frances =
            NodeInfo("f6a1b2c3d4e5", "Frances", Reach.LongRange, null, setOf(TransportKind.DirectLink), link = LinkSecurity.SEALED)
        show(state().copy(longRangeNodes = listOf(frances)))

        compose.onNodeWithText(context.getString(R.string.diagnostics_tag_link)).assertExists()
        compose.onNodeWithTag("link_chip_${frances.nodeId}").assertExists()
    }

    @Test
    fun theBluetoothRowSaysRotatingIdOnlyWhileTheAdvertIsBlinded() {
        val blinded = TransportStatus(TransportKind.Bluetooth, TransportHealth.Healthy, linked = 1, nearby = 2, blindedAdvert = true)
        show(state().copy(transports = listOf(TransportRow.Live(blinded))))
        compose.onNodeWithText(context.getString(R.string.diagnostics_transport_blinded)).assertExists()
    }

    @Test
    fun aClearAdvertHasNoRotatingIdTag() {
        val clear = TransportStatus(TransportKind.Bluetooth, TransportHealth.Healthy, linked = 1, nearby = 2)
        show(state().copy(transports = listOf(TransportRow.Live(clear))))
        compose.onNodeWithText(context.getString(R.string.diagnostics_transport_blinded)).assertDoesNotExist()
    }

    @Test
    fun theSelfSectionSaysWhetherAPresenceKeyIsHeld() {
        show(presence = PresenceKeyInfo(ownKeyHeld = true))
        compose.onNodeWithText(context.getString(R.string.diagnostics_presence_key_held)).assertExists()
        compose.onNodeWithText(context.getString(R.string.diagnostics_presence_key_none)).assertDoesNotExist()
    }

    @Test
    fun heldContactKeysReadAsNOfM() {
        show(presence = PresenceKeyInfo(keyHolders = setOf("a", "b"), directContacts = 3))
        compose.onNodeWithText(context.getString(R.string.diagnostics_metric_presence_keys_of, 2, 3)).assertExists()
    }

    @Test
    fun debugOptionsAreAbsentUntilARowIsOffered() {
        show()
        compose.onNodeWithText(context.getString(R.string.diagnostics_debug_section)).assertDoesNotExist()
    }

    @Test
    fun debugOptionsGatherTheOfferedRowsAtTheFoot() {
        show(
            walkieClipsOffered = true,
            advertDebug =
                AdvertDebugInfo(
                    resolvedByKey = 4,
                    resolvedByClaim = 1,
                    expectedTags = 64,
                    expectedContacts = 2,
                    airingTag = true,
                ),
        )

        val debug = top(context.getString(R.string.diagnostics_debug_section))
        assertTrue("below the metrics", top(context.getString(R.string.diagnostics_metrics)) < debug)
        compose.onNodeWithTag("walkie_clips_switch").assertExists()
        compose.onNodeWithTag("advert_debug").assertExists()
        compose.onNodeWithText(context.getString(R.string.diagnostics_advert_debug_expected_value, 64, 2)).assertExists()
    }
}
