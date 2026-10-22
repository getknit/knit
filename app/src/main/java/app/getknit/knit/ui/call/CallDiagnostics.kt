package app.getknit.knit.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.call.WalkiePolicy
import app.getknit.knit.call.WalkieStats
import java.util.Locale
import kotlin.math.log10

/**
 * The call screen's diagnostics card (experimental, `LIVE_CALLS_WIRED`): the live numbers of the media — round trip, jitter,
 * loss, both bitrates, the jitter buffer — over the path, the codec, the DTLS-SRTP suite and the ICE candidates. Drawn
 * for demos and for a tester's eye; nothing here is a call decision.
 */
@Composable
internal fun CallDiagnostics(
    stats: CallStats,
    modifier: Modifier = Modifier,
    // The microphone's path and health (C-3.13-13), null where nothing reports it.
    capture: CaptureStatus? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title(stringResource(R.string.call_diag_title), stringResource(R.string.call_diag_marker_live))
            Row(Modifier.fillMaxWidth()) {
                Metric(stringResource(R.string.call_diag_round_trip), ms(stats.rttMs), Modifier.weight(1f))
                Metric(stringResource(R.string.call_diag_jitter), ms(stats.jitterMs), Modifier.weight(1f))
                Metric(stringResource(R.string.call_diag_buffer), ms(stats.jitterBufferMs), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Metric(stringResource(R.string.call_diag_send), kbps(stats.sendKbps), Modifier.weight(1f))
                Metric(stringResource(R.string.call_diag_receive), kbps(stats.receiveKbps), Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
            // What the microphone hands WebRTC and what is heard: "silent" with packets flowing is a dead microphone.
            Row(Modifier.fillMaxWidth()) {
                Metric(stringResource(R.string.call_diag_mic_level), level(stats.micLevel), Modifier.weight(1f))
                Metric(stringResource(R.string.call_diag_heard_level), level(stats.heardLevel), Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
            // Loss each way now, then over the whole call: the first two are what the screen's warning reads.
            Row(Modifier.fillMaxWidth()) {
                Metric(stringResource(R.string.call_diag_sent_loss_now), percent(stats.sentLossPercent), Modifier.weight(1f))
                Metric(stringResource(R.string.call_diag_heard_loss_now), percent(stats.recentLossPercent), Modifier.weight(1f))
                Metric(stringResource(R.string.call_diag_heard_loss_call), percent(stats.lossPercent), Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Detail(stringResource(R.string.call_diag_path), pathOf(stats))
                Detail(stringResource(R.string.call_diag_relay), relayOf(stats))
                Detail(stringResource(R.string.call_diag_prefer), stats.prefer?.replace('_', ' ') ?: NONE)
                Detail(stringResource(R.string.call_diag_mic), captureOf(capture))
                Detail(stringResource(R.string.call_diag_codec), codecOf(stats))
                Detail(stringResource(R.string.call_diag_dtls), dtlsOf(stats))
                Detail(stringResource(R.string.call_diag_srtp), stats.srtpCipher ?: NONE)
                Detail(
                    stringResource(R.string.call_diag_ice),
                    "${stats.localCandidates} local · ${stats.remoteCandidates} remote candidates",
                )
                Detail(stringResource(R.string.call_diag_pairs), pairsText(stats), lines = stats.pairs.size + 1)
            }
        }
    }
}

/**
 * The diagnostics card of a walkie session (§3.11), laid out as [CallDiagnostics] is: the chunks' transit, jitter and
 * loss, the frames' rate each way over the latest burst and the player's start-up buffer, over the path a chunk takes,
 * the codec, the chunks and the frames. Transit is read off two phones' clocks; the rest is not.
 */
@Composable
internal fun WalkieDiagnostics(
    stats: WalkieStats,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title(stringResource(R.string.call_diag_walkie_title), stringResource(R.string.call_diag_marker_mesh))
            Row(Modifier.fillMaxWidth()) {
                Metric(stringResource(R.string.call_diag_transit), ms(stats.transitMs), Modifier.weight(1f))
                Metric(stringResource(R.string.call_diag_jitter), ms(stats.jitterMs), Modifier.weight(1f))
                Metric(stringResource(R.string.call_diag_loss), percent(stats.lossPercent), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Metric(stringResource(R.string.call_diag_send), kbps(stats.sendKbps), Modifier.weight(1f))
                Metric(stringResource(R.string.call_diag_receive), kbps(stats.receiveKbps), Modifier.weight(1f))
                Metric(stringResource(R.string.call_diag_buffer), ms(stats.bufferMs), Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Detail(stringResource(R.string.call_diag_path), stats.path ?: NONE)
                Detail(stringResource(R.string.call_diag_codec), WALKIE_CODEC)
                Detail(stringResource(R.string.call_diag_chunks), chunksOf(stats))
                Detail(stringResource(R.string.call_diag_frame), framesOf(stats))
                Detail(stringResource(R.string.call_diag_bursts), "${stats.burstsOut} sent · ${stats.burstsIn} heard")
            }
        }
    }
}

/** The card's title, with its live marker at the end. */
@Composable
internal fun Title(
    title: String,
    marker: String,
    icon: ImageVector = Icons.Outlined.Insights,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(title, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Text(marker, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Detail(
    label: String,
    value: String,
    lines: Int = 2,
) {
    val description = stringResource(R.string.call_diag_detail_description, label, value)
    Row(Modifier.clearAndSetSemantics { contentDescription = description }) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(DetailLabelWidth),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val DetailLabelWidth = 56.dp
private const val NONE = "—"
private const val DB_PER_DECADE = 20.0
private const val FINE_BELOW = 10.0
private const val HZ_PER_KHZ = 1_000
private const val BPS_PER_KBPS = 1_000
private const val WALKIE_PACKET_MS = 20

/** "Opus · 8 kbps · 16 kHz · mono · 20 ms": what a walkie burst is recorded as (C-3.11-5). */
private const val WALKIE_CODEC =
    "Opus · ${WalkiePolicy.BIT_RATE / BPS_PER_KBPS} kbps · ${WalkiePolicy.SAMPLE_RATE / HZ_PER_KHZ} kHz · mono · $WALKIE_PACKET_MS ms"

/** "480 ms · 42 out, 1 failed · 40 in, 2 lost": the chunks' length and how many went and came. */
internal fun chunksOf(stats: WalkieStats): String {
    val out = "${stats.chunksOut} out" + if (stats.chunksFailed > 0) ", ${stats.chunksFailed} failed" else ""
    val inbound = "${stats.chunksIn} in" + if (stats.chunksLost > 0) ", ${stats.chunksLost} lost" else ""
    return "${WalkiePolicy.CHUNK_MS} ms · $out · $inbound"
}

/** "712 B out · 708 B in · handed over in 12 ms": the average frame each way, and how long the radio took one. */
internal fun framesOf(stats: WalkieStats): String =
    listOfNotNull(
        stats.frameOut?.let { "$it B out" },
        stats.frameIn?.let { "$it B in" },
        stats.handMs?.let { "handed over in ${ms(it)}" },
    ).joinToString(" · ").ifEmpty { NONE }

internal fun ms(value: Double?): String = value?.let { "${decimal(it)} ms" } ?: NONE

internal fun kbps(value: Double?): String = value?.let { "${decimal(it)} kbps" } ?: NONE

/** An audio level (0..1) in dBFS, "silent" at exactly zero. */
internal fun level(value: Double?): String =
    when {
        value == null -> NONE
        value <= 0.0 -> "silent"
        else -> "%.0f dB".format(Locale.ROOT, DB_PER_DECADE * log10(value))
    }

/** "fallback · switched · live": the profile the call recorded on, whether it moved, and the microphone's health. */
internal fun captureOf(capture: CaptureStatus?): String =
    capture
        ?.let { listOfNotNull(it.profile, "switched".takeIf { _ -> it.switched }, it.health.name.lowercase()).joinToString(" · ") }
        ?: NONE

internal fun percent(value: Double?): String = value?.let { "%.1f %%".format(Locale.ROOT, it) } ?: NONE

/** One decimal under ten, whole numbers above: "2.4 ms", "48 ms". */
private fun decimal(value: Double): String =
    if (value <
        FINE_BELOW
    ) {
        "%.1f".format(Locale.ROOT, value)
    } else {
        "%.0f".format(Locale.ROOT, value)
    }

/** "relay (TURN/UDP) ↔ srflx · UDP · Wi-Fi": the pair's types, its transport and the local network's kind. */
internal fun pathOf(stats: CallStats): String {
    val local = stats.localType ?: return NONE
    val turn = stats.relayProtocol?.let { " (TURN/${it.uppercase(Locale.ROOT)})" }.orEmpty()
    return listOfNotNull("$local$turn ↔ ${stats.remoteType ?: "?"}", stats.protocol?.uppercase(Locale.ROOT), networkOf(stats))
        .joinToString(" · ")
}

/** The selected pair's network on this phone, as people say it: "Wi-Fi", "Cellular", "Wi-Fi Direct"; null unknown. */
internal fun networkOf(stats: CallStats): String? =
    when {
        stats.wifiDirect -> "Wi-Fi Direct"
        else -> stats.networkType?.let(::networkName)
    }

private fun networkName(type: String): String =
    when (type) {
        "wifi" -> "Wi-Fi"
        "cellular" -> "Cellular"
        "ethernet" -> "Ethernet"
        "vpn" -> "VPN"
        else -> type
    }

/** "stun.lab.lax.spool.getknit.app · tcp 4, tls 2, udp 5": the selected relay's server, then every relay gathered. */
internal fun relayOf(stats: CallStats): String =
    listOfNotNull(stats.relayHost, stats.relays?.let { "gathered $it" }).joinToString(" · ").ifEmpty { NONE }

/**
 * One line per answered pair, fastest first, the selected one starred: "★ relay/srflx udp/turn-tcp Wi-Fi 98 ms",
 * then how many are still unanswered.
 */
internal fun pairsText(stats: CallStats): String {
    val lines =
        stats.pairs.map { pair ->
            listOfNotNull(
                if (pair.selected) "★" else "·",
                "${pair.localType ?: "?"}/${pair.remoteType ?: "?"}",
                (pair.protocol ?: "?") + pair.relayProtocol?.let { "/turn-$it" }.orEmpty(),
                pair.networkType?.let(::networkName),
                ms(pair.rttMs),
            ).joinToString(" ")
        }
    return (lines + "${stats.pendingPairs} unanswered").joinToString("\n")
}

/** "Opus · 48 kHz · 2 ch · FEC". */
internal fun codecOf(stats: CallStats): String {
    val name = stats.codec ?: return NONE
    return listOfNotNull(
        name.replaceFirstChar { it.titlecase(Locale.ROOT) },
        stats.clockRate?.let { "${it / HZ_PER_KHZ} kHz" },
        stats.channels?.let { "$it ch" },
        "FEC".takeIf { stats.fec },
    ).joinToString(" · ")
}

/** "1.2 · ECDHE-ECDSA / AES-128-GCM-SHA256": the version and the suite, shortened the way OpenSSL spells it. */
internal fun dtlsOf(stats: CallStats): String {
    val suite =
        stats.dtlsCipher
            ?.removePrefix("TLS_")
            ?.replace("_WITH_", " / ")
            ?.replace('_', '-')
    return listOfNotNull(stats.dtlsVersion, suite).joinToString(" · ").ifEmpty { NONE }
}
