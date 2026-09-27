package app.getknit.knit.wear.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import app.getknit.knit.wear.Counts
import app.getknit.knit.wear.DayStats
import app.getknit.knit.wear.Glyph
import app.getknit.knit.wear.Line
import app.getknit.knit.wear.PeerLinks
import app.getknit.knit.wear.PhoneStatusReader
import app.getknit.knit.wear.R
import app.getknit.knit.wear.Sample
import app.getknit.knit.wear.Snapshot
import app.getknit.knit.wear.StatusHistory
import app.getknit.knit.wear.StatusLines
import app.getknit.knit.wear.StatusRefresh
import app.getknit.knit.wear.StatusText
import app.getknit.knit.wear.Today
import app.getknit.knit.wear.res
import app.getknit.knit.wearstatus.MeshState
import app.getknit.knit.wearstatus.Plane
import app.getknit.knit.wearstatus.WearLink
import app.getknit.knit.wearstatus.WearStatus
import kotlinx.coroutines.delay

/**
 * The status screen, Material 3 Expressive: a scrolling [TransformingLazyColumn] whose items morph at the
 * round screen's edges, under the time, with Refresh as the edge-hugging button. From the top: the peer map
 * (this phone, who it reaches and over what), the state, any radio that needs attention, then Today — relayed,
 * held for others, peers in range over the last twelve hours, and how the day went — and how old the reading
 * is. Radios are named only when one is weak or down; a healthy one is the normal case.
 *
 * While the screen is resumed it reads the phone every [POLL_MS] (the reader's 60 s cache and failure floor
 * keep that to at most one connect a minute); Refresh forces one.
 */
@Composable
fun StatusScreen(
    granted: Boolean,
    onAllow: () -> Unit,
) {
    val context = LocalContext.current
    val snapshot by PhoneStatusReader.snapshots(context).collectAsStateWithLifecycle()
    val history by StatusHistory.samples(context).collectAsStateWithLifecycle()
    val reading by StatusRefresh.reading.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                StatusRefresh.kick(context, force = false)
                delay(POLL_MS)
            }
        }
    }
    val status = StatusText.fresh(snapshot, now)
    val today = DayStats.today(history, StatusHistory.dayStart(now), now)
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    AppScaffold {
        ScreenScaffold(
            scrollState = listState,
            edgeButton = {
                EdgeButton(
                    onClick = { StatusRefresh.kick(context, force = true) },
                    buttonSize = EdgeButtonSize.Medium,
                    enabled = granted && !reading,
                ) {
                    if (!reading) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        when {
                            reading -> "Reading…"
                            status == null -> "Try again"
                            else -> "Refresh"
                        },
                    )
                }
            },
        ) { contentPadding ->
            TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
                item {
                    ListHeader(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, spec)
                                .minimumVerticalContentPadding(ListHeaderDefaults.minimumTopListContentPadding, 0.dp),
                        transformation = SurfaceTransformation(spec),
                    ) { Text("Knit mesh") }
                }
                item { Hero(status, reading) }
                when {
                    !granted -> item { PermissionCard(spec, onAllow) }
                    status == null -> item { UnreachableCard(spec, snapshot, now) }
                    StatusText.resting(status) -> item { RestingCard(spec, status) }
                    else -> for (problem in StatusLines.problems(status)) item { ProblemCard(spec, problem) }
                }
                item { ListSubHeader(transformation = SurfaceTransformation(spec)) { Text("Today") } }
                item { RelayedCard(spec, snapshot?.status, today) }
                status?.extra?.let { extra -> item { CarryingCard(spec, extra.carrying) } }
                item { NearbyCard(spec, history, today, now) }
                if (today.share.totalMs > 0) item { DayCard(spec, today, now) }
                item { Footer(snapshot, now) }
            }
        }
    }
}

/** The peer map, the state under it, a count of who is where, and the legend of the lines drawn. */
@Composable
private fun Hero(
    status: WearStatus?,
    reading: Boolean,
) {
    val tone = StatusText.tone(status?.state)
    val word = status?.let { StatusText.word(it.state) } ?: "No phone"
    val live = status?.takeUnless { StatusText.resting(it) }
    // A phone build without the link list: its nearby count, drawn as plain Bluetooth lines.
    val links =
        when {
            live == null -> emptyList()
            live.extra != null -> live.extra.links
            else -> List(minOf(live.nearby, MAX_FALLBACK_NODES)) { WearLink.BLE }
        }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    listOfNotNull(StatusLines.healthLine(status).text, PeerLinks.summary(status)).joinToString(". ")
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PeerMap(
            links = links,
            tone = tone,
            alone = status?.state == MeshState.Alone,
            reading = reading,
            center =
                if (live != null) {
                    null
                } else {
                    {
                        Icon(
                            painterResource(StatusText.glyph(status?.state).res),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
        )
        Text(word, style = MaterialTheme.typography.titleMedium, color = tone.color())
        PeerLinks.summary(status)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (live != null) {
            Spacer(Modifier.height(4.dp))
            val legend = PeerLinks.legend(links)
            if (legend.isNotEmpty()) {
                LinkLegend(legend)
            } else {
                Text(
                    listening(live),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** With nobody on the map, which radios are listening for someone: "Listening on Bluetooth · LoRa". */
private fun listening(status: WearStatus): String {
    val live = StatusText.planes(status).filter { it.plane == Plane.Live }.map { it.name }
    return if (live.isEmpty()) "No radio listening" else "Listening on " + live.joinToString(" · ")
}

@Composable
private fun TransformingLazyColumnItemScope.ProblemCard(
    spec: TransformationSpec,
    problem: Line,
) {
    StatusCard(spec) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(problem.icon.res),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = problem.tone.color(),
            )
            Spacer(Modifier.width(10.dp))
            Text(problem.text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.RelayedCard(
    spec: TransformationSpec,
    last: WearStatus?,
    today: Today,
) {
    MetricCard(
        spec,
        Glyph.Relayed,
        today.relayed?.let { "+" + Counts.grouped(it) } ?: StatusText.NO_DATA,
        "relayed today",
        last?.let { "${Counts.grouped(it.relayed)} in all" },
    )
}

@Composable
private fun TransformingLazyColumnItemScope.CarryingCard(
    spec: TransformationSpec,
    carrying: Int,
) {
    MetricCard(spec, Glyph.Carrying, Counts.grouped(carrying.toLong()), "held for others", null)
}

@Composable
private fun TransformingLazyColumnItemScope.MetricCard(
    spec: TransformationSpec,
    glyph: Glyph,
    value: String,
    label: String,
    detail: String?,
) {
    StatusCard(spec) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(glyph.res),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(value, style = MaterialTheme.typography.numeralExtraSmall)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodyExtraSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Peers in range over the last twelve hours, with the day's busiest moment under it. */
@Composable
private fun TransformingLazyColumnItemScope.NearbyCard(
    spec: TransformationSpec,
    history: List<Sample>,
    today: Today,
    now: Long,
) {
    val series = DayStats.nearbySeries(history, now - CHART_SPAN_MS, now, CHART_STEPS)
    StatusCard(spec) {
        Text("Nearby · last 12 h", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        if (series.all { it == null }) {
            Text(
                "Builds up as the watch reads your phone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            NearbySparkline(series)
            today.busiest?.let { (count, at) ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "Busiest: $count at ${Counts.clock(at)}",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** How the day went as one bar, the linked time in words, and the current run or the last peer seen. */
@Composable
private fun TransformingLazyColumnItemScope.DayCard(
    spec: TransformationSpec,
    today: Today,
    now: Long,
) {
    val share = today.share
    StatusCard(spec) {
        Text("Your day", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        DayBar(share)
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(
                share.linkedMs.takeIf { it > 0 }?.let { "Linked ${Counts.duration(it)}" },
                share.aloneMs.takeIf { it > 0 }?.let { "alone ${Counts.duration(it)}" },
            ).joinToString(" · ").ifEmpty { StatusLines.dayLine(today).text },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusLines.streak(today, now)?.let {
            Text(it, style = MaterialTheme.typography.bodyExtraSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.UnreachableCard(
    spec: TransformationSpec,
    snapshot: Snapshot?,
    now: Long,
) {
    StatusCard(spec) {
        Text("Phone out of reach", style = MaterialTheme.typography.titleSmall)
        Text(
            "Keep Bluetooth on, with Knit running on your phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        snapshot?.let {
            Text(
                "Last reading ${Counts.ago(now - it.fetchedAtMs)}",
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.RestingCard(
    spec: TransformationSpec,
    status: WearStatus,
) {
    StatusCard(spec) {
        Text("Mesh ${StatusText.word(status.state).lowercase()}", style = MaterialTheme.typography.titleSmall)
        Text(
            if (status.state == MeshState.Paused) {
                "It resumes on its own, or from Knit's notification on your phone."
            } else {
                "Start it from Knit on your phone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TransformingLazyColumnItemScope.PermissionCard(
    spec: TransformationSpec,
    onAllow: () -> Unit,
) {
    StatusCard(spec) {
        Text("Allow nearby devices", style = MaterialTheme.typography.titleSmall)
        Text(
            "The watch reads the mesh from your phone over Bluetooth.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Allow") }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.StatusCard(
    spec: TransformationSpec,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .transformedHeight(this, spec)
                .semantics(mergeDescendants = true) {},
        transformation = SurfaceTransformation(spec),
        content = content,
    )
}

@Composable
private fun Footer(
    snapshot: Snapshot?,
    now: Long,
) {
    Text(
        snapshot?.let { "Updated ${Counts.ago(now - it.fetchedAtMs)}" } ?: "Not read yet",
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        style = MaterialTheme.typography.bodyExtraSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private const val TICK_MS = 1_000L
private const val POLL_MS = 30_000L
private const val CHART_SPAN_MS = 12 * 60 * 60_000L
private const val CHART_STEPS = 48
private const val MAX_FALLBACK_NODES = 8
