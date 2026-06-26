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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.ui.util.compactTimeAgo
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

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.diagnostics_title)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SelfSection(name = state.myName, nodeId = state.myNodeId) }

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
                items(state.directNodes, key = { it.nodeId }) { NodeRow(it) }
            }

            item {
                SectionHeader(stringResource(R.string.diagnostics_via_relay, state.relayNodes.size))
            }
            if (state.relayNodes.isEmpty()) {
                item { EmptyLine(stringResource(R.string.diagnostics_none_relay)) }
            } else {
                items(state.relayNodes, key = { it.nodeId }) { NodeRow(it) }
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
private fun NodeRow(node: NodeInfo) {
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
                    compactTimeAgo(updatedAt, System.currentTimeMillis()),
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
