package app.getknit.knit.screenshot

import androidx.compose.runtime.Composable
import app.getknit.knit.ui.diagnostics.AdvertDebugSectionPreview
import app.getknit.knit.ui.diagnostics.BleLinkCapRowPreview
import app.getknit.knit.ui.diagnostics.BlePhyModeRowPreview
import app.getknit.knit.ui.diagnostics.CrashLogScreenEmptyPreview
import app.getknit.knit.ui.diagnostics.CrashLogScreenPreview
import app.getknit.knit.ui.diagnostics.CrashRowPreview
import app.getknit.knit.ui.diagnostics.DiagnosticsRowsPreview
import app.getknit.knit.ui.diagnostics.DiagnosticsScreenEmptyDegradedPreview
import app.getknit.knit.ui.diagnostics.DiagnosticsScreenPopulatedPreview
import app.getknit.knit.ui.diagnostics.DiagnosticsScreenSelectingPreview
import app.getknit.knit.ui.diagnostics.FieldLogScreenOffPreview
import app.getknit.knit.ui.diagnostics.FieldLogScreenPreview
import app.getknit.knit.ui.diagnostics.KnownActionsPreview
import app.getknit.knit.ui.diagnostics.KnownRowSelectablePreview
import app.getknit.knit.ui.diagnostics.KnownRowsSelectingPreview
import app.getknit.knit.ui.diagnostics.MeshControlsSectionDegradedPreview
import app.getknit.knit.ui.diagnostics.MeshControlsSectionHealthyPreview
import app.getknit.knit.ui.diagnostics.MeshControlsSectionUnavailableBleOnlyPreview
import app.getknit.knit.ui.diagnostics.MeshControlsSectionUnavailablePreview
import app.getknit.knit.ui.diagnostics.MetricsSectionEmptyPreview
import app.getknit.knit.ui.diagnostics.MetricsSectionPopulatedPreview
import app.getknit.knit.ui.diagnostics.NodeRowAllChipsPreview
import app.getknit.knit.ui.diagnostics.NodeRowCodedPreview
import app.getknit.knit.ui.diagnostics.NodeRowDirectLinkPreview
import app.getknit.knit.ui.diagnostics.NodeRowDirectPreview
import app.getknit.knit.ui.diagnostics.NodeRowIndirectPreview
import app.getknit.knit.ui.diagnostics.NodeRowPlainLinkPreview
import app.getknit.knit.ui.diagnostics.NodeRowRelayPreview
import app.getknit.knit.ui.diagnostics.SelfSectionPreview
import app.getknit.knit.ui.diagnostics.TransportsSectionAbsentPreview
import app.getknit.knit.ui.diagnostics.TransportsSectionPreview
import com.android.tools.screenshot.PreviewTest

// Screenshot tests over the previews in `ui.diagnostics`.

@PreviewTest
@ScreenShots
@Composable
fun CrashLogScreen() = CrashLogScreenPreview()

@PreviewTest
@ScreenShots
@Composable
fun CrashLogScreenEmpty() = CrashLogScreenEmptyPreview()

@PreviewTest
@ComponentShots
@Composable
fun SelfSection() = SelfSectionPreview()

@PreviewTest
@ComponentShots
@Composable
fun MeshControlsSectionHealthy() = MeshControlsSectionHealthyPreview()

@PreviewTest
@ComponentShots
@Composable
fun MeshControlsSectionDegraded() = MeshControlsSectionDegradedPreview()

@PreviewTest
@ComponentShots
@Composable
fun MeshControlsSectionUnavailable() = MeshControlsSectionUnavailablePreview()

@PreviewTest
@ComponentShots
@Composable
fun MeshControlsSectionUnavailableBleOnly() = MeshControlsSectionUnavailableBleOnlyPreview()

@PreviewTest
@ComponentShots
@Composable
fun MetricsSectionPopulated() = MetricsSectionPopulatedPreview()

@PreviewTest
@ComponentShots
@Composable
fun AdvertDebugSection() = AdvertDebugSectionPreview()

@PreviewTest
@ComponentShots
@Composable
fun MetricsSectionEmpty() = MetricsSectionEmptyPreview()

@PreviewTest
@ComponentShots
@Composable
fun TransportsSection() = TransportsSectionPreview()

@PreviewTest
@ComponentShots
@Composable
fun TransportsSectionAbsent() = TransportsSectionAbsentPreview()

@PreviewTest
@ComponentShots
@Composable
fun NodeRowDirect() = NodeRowDirectPreview()

@PreviewTest
@ComponentShots
@Composable
fun NodeRowPlainLink() = NodeRowPlainLinkPreview()

@PreviewTest
@ComponentShots
@Composable
fun NodeRowAllChips() = NodeRowAllChipsPreview()

@PreviewTest
@ComponentShots
@Composable
fun NodeRowDirectLink() = NodeRowDirectLinkPreview()

@PreviewTest
@ComponentShots
@Composable
fun NodeRowCoded() = NodeRowCodedPreview()

@PreviewTest
@ComponentShots
@Composable
fun NodeRowIndirect() = NodeRowIndirectPreview()

@PreviewTest
@ComponentShots
@Composable
fun NodeRowRelay() = NodeRowRelayPreview()

@PreviewTest
@ComponentShots
@Composable
fun KnownRowSelectable() = KnownRowSelectablePreview()

@PreviewTest
@ComponentShots
@Composable
fun KnownRowsSelecting() = KnownRowsSelectingPreview()

@PreviewTest
@ComponentShots
@Composable
fun KnownActions() = KnownActionsPreview()

@PreviewTest
@ScreenShots
@Composable
fun DiagnosticsScreenSelecting() = DiagnosticsScreenSelectingPreview()

@PreviewTest
@ComponentShots
@Composable
fun DiagnosticsRows() = DiagnosticsRowsPreview()

@PreviewTest
@ComponentShots
@Composable
fun CrashRow() = CrashRowPreview()

@PreviewTest
@ScreenShots
@Composable
fun DiagnosticsScreenPopulated() = DiagnosticsScreenPopulatedPreview()

@PreviewTest
@ScreenShots
@Composable
fun DiagnosticsScreenEmptyDegraded() = DiagnosticsScreenEmptyDegradedPreview()

@PreviewTest
@ComponentShots
@Composable
fun BleLinkCapRow() = BleLinkCapRowPreview()

@PreviewTest
@ComponentShots
@Composable
fun BlePhyModeRow() = BlePhyModeRowPreview()

@PreviewTest
@ScreenShots
@Composable
fun FieldLogScreen() = FieldLogScreenPreview()

@PreviewTest
@ScreenShots
@Composable
fun FieldLogScreenOff() = FieldLogScreenOffPreview()
