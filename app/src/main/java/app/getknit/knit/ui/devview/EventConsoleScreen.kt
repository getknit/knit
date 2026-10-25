package app.getknit.knit.ui.devview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.ui.components.BarTitle
import app.getknit.knit.ui.preview.KnitPreview
import org.koin.androidx.compose.koinViewModel

/**
 * The developer view's event console (ADR 2026-10.s7wg, its slice-2 amendment): what the mesh recorded, newest first,
 * on this phone only. Reached from the HUD's panel, and registered only where the view is wired.
 */
@Composable
fun EventConsoleScreen(
    onBack: () -> Unit,
    viewModel: EventConsoleViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EventConsoleScreenContent(
        state = state,
        onBack = onBack,
        onShowPlane = viewModel::showPlane,
        onTogglePause = viewModel::togglePause,
        onClear = viewModel::clear,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventConsoleScreenContent(
    state: EventConsoleUiState,
    onBack: () -> Unit = {},
    onShowPlane: (ConsolePlane?) -> Unit = {},
    onTogglePause: () -> Unit = {},
    onClear: () -> Unit = {},
) {
    Scaffold(
        modifier = Modifier.testTag("screen_dev_console"),
        topBar = {
            TopAppBar(
                title = { BarTitle(stringResource(R.string.dev_console_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onTogglePause, modifier = Modifier.testTag("dev_console_pause")) {
                        Icon(
                            if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription =
                                stringResource(if (state.paused) R.string.dev_console_resume else R.string.dev_console_pause),
                        )
                    }
                    IconButton(onClick = onClear, modifier = Modifier.testTag("dev_console_clear")) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.dev_console_clear))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PlaneFilters(selected = state.plane, onSelect = onShowPlane)
            if (state.rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.dev_console_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp).testTag("dev_console_empty"),
                )
            } else {
                SelectionContainer {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize().testTag("dev_console_list"),
                    ) {
                        itemsIndexed(state.rows) { _, row -> ConsoleRowLine(row) }
                    }
                }
            }
        }
    }
}

/** All, then one chip per plane, by the code word the HUD uses for it. */
@Composable
private fun PlaneFilters(
    selected: ConsolePlane?,
    onSelect: (ConsolePlane?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.dev_console_all)) },
        )
        ConsolePlane.entries.forEach { plane ->
            FilterChip(
                selected = selected == plane,
                onClick = { onSelect(plane) },
                label = { Text(plane.label) },
                modifier = Modifier.testTag("dev_console_plane_${plane.name}"),
            )
        }
    }
}

/** One event: its time and kind on a line, its fields under them. One accessibility node. */
@Composable
private fun ConsoleRowLine(row: ConsoleRow) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = row.time,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = row.kind,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (row.fields.isNotEmpty()) {
            Text(text = row.fields, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Preview(name = "Event console")
@Composable
fun EventConsoleScreenPreview() =
    KnitPreview {
        EventConsoleScreenContent(
            state =
                EventConsoleUiState(
                    rows =
                        listOf(
                            ConsoleRow(
                                "03:04:43.055",
                                "bt_up",
                                ConsolePlane.Bluetooth,
                                "peer=Pixel 3 (dvha4aed) dialed=true dial=812 sighted=true links=2",
                            ),
                            ConsoleRow("03:04:40.210", "nan_up", ConsolePlane.WifiAware, "peer=Kai (hzwyqdbj) initiator=false links=1"),
                            ConsoleRow(
                                "03:04:39.001",
                                "spool_up",
                                ConsolePlane.Relay,
                                "relay=relay.example hello=412 fails=0 pow=0 attach=true ent=false turn=false cast=false",
                            ),
                            ConsoleRow("03:04:38.126", "radio", ConsolePlane.Bluetooth, "radio=Bluetooth health=Healthy linked=2 nearby=3"),
                            ConsoleRow("03:04:37.900", "mesh_on", ConsolePlane.Mesh, "vc=34"),
                        ),
                    held = 5,
                ),
        )
    }

@Preview(name = "Event console empty")
@Composable
fun EventConsoleScreenEmptyPreview() =
    KnitPreview {
        EventConsoleScreenContent(state = EventConsoleUiState(plane = ConsolePlane.LoRa))
    }
