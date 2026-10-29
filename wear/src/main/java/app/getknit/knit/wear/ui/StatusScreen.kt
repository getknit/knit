package app.getknit.knit.wear.ui

import android.content.res.Resources
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.LocalTextStyle
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
import app.getknit.knit.wear.Tone
import app.getknit.knit.wear.locale
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
    // Aged past the live window it is still drawn, muted and dated, until a read finds no phone.
    val shown = StatusText.shown(snapshot, PhoneStatusReader.failedAt(context), now)
    val status = shown?.status
    val today = DayStats.today(history, StatusHistory.dayStart(now), now)
    val res = LocalResources.current
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
                    EdgeContent(
                        stringResource(
                            when {
                                reading -> R.string.reading
                                status == null -> R.string.try_again
                                else -> R.string.refresh
                            },
                        ),
                        icon = !reading,
                    )
                }
            },
        ) { contentPadding ->
            TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
                // No title: the map is the screen's subject and opens it, centred under the time.
                item {
                    if (granted) {
                        Hero(status, reading, agedSinceMs = shown?.agedSinceMs)
                    } else {
                        Hero(null, reading = false, agedSinceMs = null, blocked = true)
                    }
                }
                when {
                    !granted -> item { PermissionCard(spec, onAllow) }
                    status == null -> item { UnreachableCard(spec, snapshot, now) }
                    StatusText.resting(status) -> item { RestingCard(spec, status) }
                    else -> for (problem in StatusLines.problems(res, status)) item { ProblemCard(spec, problem) }
                }
                item { ListSubHeader(transformation = SurfaceTransformation(spec)) { Text(stringResource(R.string.today)) } }
                item { RelayedCard(spec, snapshot?.status, today) }
                status?.extra?.let { extra -> item { CarryingCard(spec, extra.carrying) } }
                item { NearbyCard(spec, history, today, now) }
                if (today.share.totalMs > 0) item { DayCard(spec, today, now) }
                item { Footer(snapshot, now) }
            }
        }
    }
}

/**
 * The peer map, the state under it, a count of who is where, and the legend of the lines drawn. A reading past
 * its live window ([agedSinceMs]) is drawn muted, with the time it was taken. Without the Bluetooth grant
 * ([blocked]) there is no reading to draw: the empty map names what is missing rather than blaming the phone.
 */
@Composable
private fun Hero(
    status: WearStatus?,
    reading: Boolean,
    agedSinceMs: Long?,
    blocked: Boolean = false,
) {
    val res = LocalResources.current
    val tone = if (agedSinceMs != null) Tone.Muted else StatusText.tone(status?.state)
    val glyph = if (blocked) Glyph.Bluetooth else StatusText.glyph(status?.state)
    val word =
        when {
            blocked -> stringResource(R.string.needs_permission)
            status == null -> stringResource(R.string.no_phone)
            else -> StatusText.word(res, status.state)
        }
    val summary = PeerLinks.summary(res, status)
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
            .padding(top = 8.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) {
                val health = StatusLines.healthLine(res, status).text
                contentDescription =
                    when {
                        blocked -> res.getString(R.string.needs_permission_description)
                        summary == null -> health
                        else -> res.getString(R.string.joined_sentences, health, summary)
                    }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PeerMap(
            links = links,
            tone = tone,
            alone = status?.state == MeshState.Alone,
            reading = reading,
        ) {
            // Inside the cookie: who is in range right now, or at rest the state's glyph.
            if (live != null) {
                Text(
                    live.nearby.toString(),
                    style = MaterialTheme.typography.numeralSmall,
                    color = COOKIE_INK,
                )
            } else {
                Icon(
                    painterResource(glyph.res),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = COOKIE_INK,
                )
            }
        }
        Text(word, style = MaterialTheme.typography.titleMedium, color = tone.color())
        summary?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        agedSinceMs?.let {
            Text(
                stringResource(R.string.as_of, Counts.clock(it, locale = res.locale)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (live != null) {
            Spacer(Modifier.height(4.dp))
            val legend = PeerLinks.legend(links)
            if (legend.isNotEmpty()) {
                LinkLegend(legend, tone)
            } else {
                Text(
                    listening(res, live),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** With nobody on the map, which radios are listening for someone: "Listening on Bluetooth · LoRa". */
private fun listening(
    res: Resources,
    status: WearStatus,
): String {
    val live = StatusText.planes(status).filter { it.plane == Plane.Live }.map { res.getString(it.name) }
    if (live.isEmpty()) return res.getString(R.string.no_radio_listening)
    return res.getString(R.string.listening_on, live.reduce { list, next -> res.getString(R.string.joined_with_dot, list, next) })
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
            // "disconnected", "unreachable": at a large font size on a small watch one word can outgrow the line,
            // and a hyphen beats a letter wrapped on its own (WO-V1).
            Text(problem.text, style = MaterialTheme.typography.labelMedium.copy(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph))
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.RelayedCard(
    spec: TransformationSpec,
    last: WearStatus?,
    today: Today,
) {
    val res = LocalResources.current
    MetricCard(
        spec,
        Glyph.Relayed,
        today.relayed?.let { "+" + Counts.grouped(it, res.locale) } ?: StatusText.NO_DATA,
        stringResource(R.string.label_relayed_today),
        last?.let { StatusLines.relayedInAll(res, it.relayed) },
    )
}

@Composable
private fun TransformingLazyColumnItemScope.CarryingCard(
    spec: TransformationSpec,
    carrying: Int,
) {
    MetricCard(
        spec,
        Glyph.Carrying,
        Counts.grouped(carrying.toLong(), LocalResources.current.locale),
        stringResource(R.string.label_held_for_others),
        null,
    )
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
        Text(stringResource(R.string.nearby_last_12h), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        if (series.all { it == null }) {
            Text(
                stringResource(R.string.chart_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            NearbySparkline(series)
            today.busiest?.let { (count, at) ->
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.busiest, count, Counts.clock(at, locale = LocalResources.current.locale)),
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
    val res = LocalResources.current
    val linked = share.linkedMs.takeIf { it > 0 }?.let { res.getString(R.string.day_linked, Counts.duration(res, it)) }
    val alone = share.aloneMs.takeIf { it > 0 }?.let { res.getString(R.string.day_alone, Counts.duration(res, it)) }
    StatusCard(spec) {
        Text(stringResource(R.string.title_your_day), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        DayBar(share)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                linked != null && alone != null -> res.getString(R.string.joined_with_dot, linked, alone)
                else -> linked ?: alone ?: StatusLines.dayLine(res, today).text
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusLines.streak(res, today, now)?.let {
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
        Text(stringResource(R.string.phone_out_of_reach), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.unreachable_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        snapshot?.let {
            Text(
                stringResource(R.string.last_reading, Counts.ago(LocalResources.current, now - it.fetchedAtMs)),
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
        Text(StatusText.meshResting(LocalResources.current, status.state), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(if (status.state == MeshState.Paused) R.string.paused_hint else R.string.off_hint),
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
        Text(stringResource(R.string.allow_nearby_devices), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.allow_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.allow)) }
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
    val res = LocalResources.current
    // The transport rides along while the prototype gathers which one each watch's bond carries: its name is
    // Bluetooth's own ("Classic", "LE"), the same in every language.
    Text(
        snapshot?.let { s ->
            val updated = res.getString(R.string.updated, Counts.ago(res, now - s.fetchedAtMs))
            s.via?.let { res.getString(R.string.joined_with_dot, updated, it.label) } ?: updated
        } ?: stringResource(R.string.not_read_yet),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        style = MaterialTheme.typography.bodyExtraSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * The edge button's content: the refresh [icon] and [label] on one line. With the user's font size up on a small
 * watch the two do not fit side by side (at 1.24x on a 192 dp screen "Refresh" wrapped mid-word under the icon,
 * WO-V1), so the icon goes first, and then the label shrinks toward [MIN_LABEL]. Material's own [Text] has no
 * auto-size, so the label is [BasicText] in the button's text style and content colour.
 */
@Composable
private fun RowScope.EdgeContent(
    label: String,
    icon: Boolean,
) {
    val style = LocalTextStyle.current.merge(color = LocalContentColor.current)
    val measurer = rememberTextMeasurer()
    val iconRoom = with(LocalDensity.current) { (EDGE_ICON + EDGE_GAP).roundToPx() }
    // The weight bounds the content by the button's width; without it the row measures it unbounded.
    BoxWithConstraints(Modifier.weight(1f, fill = false), contentAlignment = Alignment.Center) {
        val labelWidth = measurer.measure(label, style, maxLines = 1, softWrap = false).size.width
        val withIcon = icon && labelWidth + iconRoom <= constraints.maxWidth
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (withIcon) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, Modifier.size(EDGE_ICON))
                Spacer(Modifier.width(EDGE_GAP))
            }
            BasicText(
                label,
                style = style,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = MIN_LABEL, maxFontSize = style.fontSize, stepSize = 1.sp),
            )
        }
    }
}

private val EDGE_ICON = 20.dp
private val EDGE_GAP = 6.dp

/** The smallest the edge button's label may shrink to: the guidelines' floor for essential text (WO-V14). */
private val MIN_LABEL = 12.sp

/** Dark ink on the cookie: every tone is a light pastel, so one ink reads on all of them. */
private val COOKIE_INK = Color(0xFF1D1B1A)

private const val TICK_MS = 1_000L
private const val POLL_MS = 30_000L
private const val CHART_SPAN_MS = 12 * 60 * 60_000L
private const val CHART_STEPS = 48
private const val MAX_FALLBACK_NODES = 8
