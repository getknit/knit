package app.getknit.knit.wear

import android.content.Context
import android.util.Log
import app.getknit.knit.wearstatus.MeshState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * The watch's own record of the phone's readings: one [Sample] per good read, kept [KEEP_MS] (today and
 * yesterday's tail, which today's first difference needs), in a small text file in the app's private storage.
 * About three hundred lines a day at the five-minute cadence. It never leaves the watch and is excluded from
 * backup with the rest of the app's data.
 *
 * Every surface reads [today] (or [samples]) — the complications, the tile and the app agree because they
 * compute from one list.
 */
object StatusHistory {
    private val latest = MutableStateFlow<List<Sample>>(emptyList())
    private var loaded = false

    fun samples(context: Context): StateFlow<List<Sample>> {
        ensureLoaded(context)
        return latest
    }

    /** Today's statistics at [nowMs], on the watch's time zone. */
    fun today(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): Today = DayStats.today(samples(context).value, dayStart(nowMs), nowMs)

    fun dayStart(nowMs: Long): Long {
        val zone = ZoneId.systemDefault()
        return Instant
            .ofEpochMilli(nowMs)
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Records [snapshot]. A reading within [MERGE_MS] of the last one replaces it (a forced refresh right after
     * a scheduled one), so a burst of reads does not weigh the day.
     */
    @Synchronized
    fun record(
        context: Context,
        snapshot: Snapshot,
    ) {
        ensureLoaded(context)
        val s = snapshot.status
        val sample =
            Sample(
                atMs = snapshot.fetchedAtMs,
                state = s.state,
                nearby = s.nearby,
                far = s.extra?.far ?: 0,
                relayed = s.relayed,
                carrying = s.extra?.carrying,
            )
        val kept =
            latest.value
                .filter { snapshot.fetchedAtMs - it.atMs < KEEP_MS }
                .filterNot { abs(it.atMs - sample.atMs) < MERGE_MS }
        val next = (kept + sample).sortedBy { it.atMs }
        latest.value = next
        runCatching {
            val file = file(context)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(next.joinToString("\n", transform = ::line))
            tmp.renameTo(file)
        }.onFailure { Log.w("KnitWear", "history not written: $it") }
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        latest.value =
            runCatching {
                file(context)
                    .takeIf { it.exists() }
                    ?.readLines()
                    .orEmpty()
                    .mapNotNull(::parse)
            }.getOrDefault(emptyList())
                .sortedBy { it.atMs }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    /** `atMs,state,nearby,far,relayed,carrying` — carrying −1 when the phone did not say. */
    private fun line(s: Sample): String = "${s.atMs},${s.state.name},${s.nearby},${s.far},${s.relayed},${s.carrying ?: -1}"

    private fun parse(line: String): Sample? {
        val f = line.split(',')
        if (f.size < FIELDS) return null
        return runCatching {
            Sample(
                atMs = f[0].toLong(),
                state = MeshState.valueOf(f[1]),
                nearby = f[2].toInt(),
                far = f[3].toInt(),
                relayed = f[4].toLong(),
                carrying = f[5].toInt().takeIf { it >= 0 },
            )
        }.getOrNull()
    }

    private const val FILE = "status_history.v1"
    private const val FIELDS = 6
    private const val KEEP_MS = 36 * 60 * 60_000L
    private const val MERGE_MS = 30_000L
}
