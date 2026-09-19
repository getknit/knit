package app.getknit.knit.ui.yourmesh

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.R
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.preview.PREVIEW_NOW
import app.getknit.knit.ui.theme.knitColors
import org.koin.androidx.compose.koinViewModel

/**
 * Your mesh: what keeping Knit running has done for the people around this phone, in plain words. Reached
 * by tapping the chat list's status line (the "N people nearby" under the title) or its overflow menu; the
 * Technical details button at the bottom hands off to Diagnostics for the version of this with counters.
 */
@Composable
fun YourMeshScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: YourMeshViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    YourMeshScreenContent(state = state, onBack = onBack, onOpenDiagnostics = onOpenDiagnostics)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun YourMeshScreenContent(
    state: YourMeshUiState,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag("screen_your_mesh"),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.your_mesh_title)) },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NearbyHero(count = state.nearbyCount, health = state.health)
            CarryingCard(count = state.carryingNow)
            LifetimeBlock(state)
            Text(
                text =
                    if (state.passedAlong > 0L || state.peopleMet > 0) {
                        stringResource(R.string.your_mesh_encouragement)
                    } else {
                        stringResource(R.string.your_mesh_encouragement_fresh)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { testTag = "your_mesh_encouragement" },
            )
            TextButton(onClick = onOpenDiagnostics, modifier = Modifier.semantics { testTag = "your_mesh_diagnostics" }) {
                Text(stringResource(R.string.your_mesh_technical_details))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * The big number and its dot, with the dot's colour rule copied from the chat list's status row so the two
 * can never say different things: the positive green only for a healthy radio with someone in range,
 * neutral for nobody or radios off, the error tint for a radio another app is holding. One semantics node,
 * read as "3 people nearby, Your phone is part of the mesh right now."
 */
@Composable
private fun NearbyHero(
    count: Int,
    health: TransportHealth,
) {
    val dot =
        when (health) {
            TransportHealth.Unavailable -> MaterialTheme.colorScheme.outline
            TransportHealth.ForegroundOnly -> MaterialTheme.colorScheme.outline
            TransportHealth.Degraded -> MaterialTheme.colorScheme.error
            TransportHealth.Healthy -> if (count > 0) MaterialTheme.knitColors.positive else MaterialTheme.colorScheme.outline
        }
    val status =
        when (health) {
            TransportHealth.Unavailable -> R.string.your_mesh_status_radios_off
            TransportHealth.ForegroundOnly -> R.string.your_mesh_status_foreground_only
            TransportHealth.Degraded -> R.string.your_mesh_status_degraded
            TransportHealth.Healthy -> if (count > 0) R.string.your_mesh_status_connected else R.string.your_mesh_status_alone
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .semantics(mergeDescendants = true) { testTag = "your_mesh_hero" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color = dot, shape = CircleShape))
            Spacer(Modifier.width(12.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = pluralStringResource(R.plurals.your_mesh_nearby, count),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(status),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CarryingCard(count: Int) {
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { testTag = "your_mesh_carrying" },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.your_mesh_carrying_heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    if (count > 0) {
                        pluralStringResource(R.plurals.your_mesh_carrying, count, count)
                    } else {
                        stringResource(R.string.your_mesh_carrying_none)
                    },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.your_mesh_carrying_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LifetimeBlock(state: YourMeshUiState) {
    val heading =
        if (state.since > 0L) {
            val context = LocalContext.current
            stringResource(
                R.string.your_mesh_lifetime_since,
                DateUtils.formatDateTime(context, state.since, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR),
            )
        } else {
            stringResource(R.string.your_mesh_lifetime_heading)
        }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        StatRow(
            value = state.passedAlong.toString(),
            label = stringResource(R.string.your_mesh_passed_along),
            tag = "your_mesh_passed_along",
        )
        StatRow(
            value = state.handedDirect.toString(),
            label = stringResource(R.string.your_mesh_handed_direct),
            tag = "your_mesh_handed_direct",
        )
        StatRow(value = state.peopleMet.toString(), label = stringResource(R.string.your_mesh_people_met), tag = "your_mesh_people_met")
    }
}

/** One lifetime number and what it counts, merged into a single TalkBack stop ("312, messages passed along…"). */
@Composable
private fun StatRow(
    value: String,
    label: String,
    tag: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { testTag = tag },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
fun YourMeshScreenPopulatedPreview() =
    KnitPreview {
        YourMeshScreenContent(
            state =
                YourMeshUiState(
                    nearbyCount = 3,
                    carryingNow = 6,
                    passedAlong = 312,
                    handedDirect = 41,
                    peopleMet = 48,
                    since = PREVIEW_NOW - 40L * 24 * 60 * 60_000L,
                ),
            onBack = {},
            onOpenDiagnostics = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun YourMeshScreenFreshPreview() =
    KnitPreview {
        YourMeshScreenContent(state = YourMeshUiState(), onBack = {}, onOpenDiagnostics = {})
    }

@Preview(showBackground = true)
@Composable
fun YourMeshScreenRadiosOffPreview() =
    KnitPreview {
        YourMeshScreenContent(
            state =
                YourMeshUiState(
                    health = TransportHealth.Unavailable,
                    passedAlong = 12,
                    handedDirect = 2,
                    peopleMet = 5,
                    since = PREVIEW_NOW,
                ),
            onBack = {},
            onOpenDiagnostics = {},
        )
    }
