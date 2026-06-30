package app.getknit.knit.ui.diagnostics

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import app.getknit.knit.ui.util.compactTimeAgo
import app.getknit.knit.ui.util.rememberCurrentTimeMillis
import org.koin.androidx.compose.koinViewModel

/**
 * Read-only mesh diagnostics: this device's identity, the live mesh metrics, directly-connected
 * nodes, and nodes reachable only via relay. Reached from the chat-list overflow menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    // A ticking clock so each node's "profile updated N ago" label recomposes as time passes; a bare
    // System.currentTimeMillis() read would freeze at first composition (see rememberCurrentTimeMillis).
    val now by rememberCurrentTimeMillis()

    val snackbarHostState = remember { SnackbarHostState() }
    // Resolve the action-feedback strings at composition (lint forbids LocalContext.getString here),
    // then map the emitted resource id to the matching message.
    val restartedMsg = stringResource(R.string.diagnostics_mesh_restarted)
    val scanningMsg = stringResource(R.string.diagnostics_scanning)
    LaunchedEffect(restartedMsg, scanningMsg) {
        viewModel.events.collect { resId ->
            snackbarHostState.showSnackbar(
                if (resId == R.string.diagnostics_mesh_restarted) restartedMsg else scanningMsg,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.diagnostics_title)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SelfSection(name = state.myName, nodeId = state.myNodeId) }

            item { SectionHeader(stringResource(R.string.diagnostics_controls)) }
            item {
                MeshControlsSection(
                    health = health,
                    onRestart = viewModel::restartMesh,
                    onScan = viewModel::rescan,
                )
            }

            item { SectionHeader(stringResource(R.string.diagnostics_metrics)) }
            item { MetricsSection(state.metrics) }

            item {
                SectionHeader(
                    stringResource(R.string.diagnostics_directly_connected, state.directNodes.size),
                )
            }
            if (state.directNodes.isEmpty()) {
                item { EmptyLine(stringResource(R.string.diagnostics_none_direct)) }
            } else {
                items(state.directNodes, key = { it.nodeId }) { NodeRow(it, now) }
            }

            item {
                SectionHeader(stringResource(R.string.diagnostics_via_relay, state.relayNodes.size))
            }
            if (state.relayNodes.isEmpty()) {
                item { EmptyLine(stringResource(R.string.diagnostics_none_relay)) }
            } else {
                items(state.relayNodes, key = { it.nodeId }) { NodeRow(it, now) }
            }
        }
    }
}

@Composable
private fun SelfSection(name: String, nodeId: String) {
    SectionHeader(stringResource(R.string.diagnostics_self))
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text = name, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.profile_node_id, nodeId),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MeshControlsSection(
    health: TransportHealth,
    onRestart: () -> Unit,
    onScan: () -> Unit,
) {
    val degraded = health == TransportHealth.Degraded
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(
                if (degraded) R.string.diagnostics_status_degraded else R.string.diagnostics_status_healthy,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (degraded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (degraded) {
            Text(
                text = stringResource(R.string.diagnostics_status_degraded_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.RestartAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.diagnostics_restart_mesh))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Sync, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.diagnostics_scan_now))
        }
    }
}

@Composable
private fun MetricsSection(metrics: MeshMetrics.Snapshot) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        MetricRow(stringResource(R.string.diagnostics_metric_originated), metrics.framesOriginated.toString())
        MetricRow(stringResource(R.string.diagnostics_metric_delivered), metrics.framesDelivered.toString())
        MetricRow(stringResource(R.string.diagnostics_metric_relayed), metrics.framesRelayed.toString())
        MetricRow(stringResource(R.string.diagnostics_metric_suppressed), metrics.framesSuppressed.toString())
        MetricRow(stringResource(R.string.diagnostics_metric_deduped), metrics.framesDeduped.toString())
        MetricRow(
            stringResource(R.string.diagnostics_metric_bytes_sent),
            Formatter.formatShortFileSize(context, metrics.bytesSent),
        )
        // Inbound drops are normally zero; surface a total plus a per-reason breakdown only when any
        // occur, so a staged rollout can spot a version causing frames to be discarded.
        if (metrics.framesDropped > 0) {
            MetricRow(stringResource(R.string.diagnostics_metric_dropped), metrics.framesDropped.toString())
            metrics.dropsByReason.forEach { (reason, count) ->
                MetricRow("   ${reason.name}", count.toString())
            }
        }
        // Key recovery (inbound key-request): surfaced only once it's been exercised, so a mesh that never
        // hit a missing-key drop stays uncluttered. A rising NO_SENDER_KEY drop with a matching rise in
        // recovered keys is the signal that the gap is self-healing rather than losing frames.
        if (metrics.keyRequestsSent > 0) {
            MetricRow(stringResource(R.string.diagnostics_metric_key_requests), metrics.keyRequestsSent.toString())
            MetricRow(stringResource(R.string.diagnostics_metric_keys_served), metrics.keysServed.toString())
            MetricRow(stringResource(R.string.diagnostics_metric_keys_recovered), metrics.keysRecovered.toString())
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NodeRow(node: NodeInfo, now: Long) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Filled tertiary dot = live direct neighbor; muted dot = known only via relay.
        val dotColor =
            if (node.direct) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = node.nodeId,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        node.profileUpdatedAt?.let { updatedAt ->
            Text(
                text = stringResource(
                    R.string.diagnostics_profile_age,
                    compactTimeAgo(updatedAt, now),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Preview(showBackground = true)
@Composable
fun SelfSectionPreview() = KnitPreview {
    // SelfSection emits a header + a Column as siblings; wrap so the preview lays them out vertically
    // (the real screen places it in a LazyColumn item).
    Column { SelfSection(name = "Ada Lovelace", nodeId = "8f3a2b1c9d4e5f60") }
}

@Preview(showBackground = true)
@Composable
fun MeshControlsSectionHealthyPreview() = KnitPreview {
    MeshControlsSection(health = TransportHealth.Healthy, onRestart = {}, onScan = {})
}

@Preview(showBackground = true)
@Composable
fun MeshControlsSectionDegradedPreview() = KnitPreview {
    MeshControlsSection(health = TransportHealth.Degraded, onRestart = {}, onScan = {})
}

@Preview(showBackground = true)
@Composable
fun MetricsSectionPopulatedPreview() = KnitPreview {
    MetricsSection(
        metrics = MeshMetrics.Snapshot(
            framesOriginated = 128,
            framesDelivered = 96,
            framesRelayed = 1_024,
            framesSuppressed = 12,
            framesDeduped = 340,
            bytesSent = 2_500_000,
        ),
    )
}

@Preview(showBackground = true)
@Composable
fun MetricsSectionEmptyPreview() = KnitPreview {
    MetricsSection(
        metrics = MeshMetrics.Snapshot(
            framesOriginated = 0,
            framesDelivered = 0,
            framesRelayed = 0,
            framesSuppressed = 0,
            framesDeduped = 0,
            bytesSent = 0,
        ),
    )
}

@Preview(showBackground = true)
@Composable
fun NodeRowDirectPreview() = KnitPreview {
    NodeRow(
        node = NodeInfo(
            nodeId = "8f3a2b1c9d4e",
            displayName = "Ada Lovelace",
            direct = true,
            profileUpdatedAt = PREVIEW_NOW - 3 * 60_000L,
        ),
        now = PREVIEW_NOW,
    )
}

@Preview(showBackground = true)
@Composable
fun NodeRowRelayPreview() = KnitPreview {
    NodeRow(
        node = NodeInfo(
            nodeId = "a1b2c3d4e5f6",
            displayName = "Grace Hopper",
            direct = false,
            profileUpdatedAt = null,
        ),
        now = PREVIEW_NOW,
    )
}

@Preview(showBackground = true)
@Composable
fun DiagnosticsRowsPreview() = KnitPreview {
    Column {
        SectionHeader(text = "Metrics")
        MetricRow(label = "Frames originated", value = "128")
        MetricRow(label = "Frames relayed", value = "1,024")
        EmptyLine(text = "No nodes connected directly.")
    }
}
